package com.laamella.code_state_machine

import grizzled.slf4j.Logging

import scala.collection.mutable

/**
 * A condition that is met or not.
 *
 * E is the event type.
 */
trait Condition[E] {
  /**
   * Handle an event.
   *
   * @param event the event that has occurred.
   */
  def handleEvent(event: E)

  /**
   * @return whether the condition is met.
   */
  def isMet: Boolean

  /**
   * This method is called every time the sourceState for this transition is
   * entered. It can be used to implement stateful transitions, like
   * transitions that fire after a certain amount of time.
   */
  def reset()
}

/**
 * A conditional transition between two states.
 *
 * @tparam T type of state.
 * @tparam E type of event.
 * @tparam P type of priority.
 */
class Transition[T, E, P <: Ordered[P]](val sourceState: T, val destinationState: T, val conditions: Seq[Condition[E]], val priority: P, val actions: Seq[() => Unit]) extends Ordered[Transition[T, E, P]] {
  override def toString = s"Transition from $sourceState to $destinationState, condition $conditions, action $actions, priority $priority"

  /** Compares transitions on their priorities. */
  override def compare(that: Transition[T, E, P]): Int = priority.compareTo(that.priority)
}

/**
 * A programmer friendly state machine.
 * <p/>
 * Features:
 * <ul>
 * <li>It is non-deterministic, but has the tools to become deterministic.</li>
 * <li>It allows multiple start states.</li>
 * <li>It allows multiple active states.</li>
 * <li>It allows multiple end states.</li>
 * <li>States and their transitions do not have to form a single graph. Separate
 * graphs may exist inside a single state machine.</li>
 * <li>Each state has a chain of entry and exit actions.</li>
 * <li>Each transition has a chain of actions.</li>
 * <li>It does not do any kind of compilation.</li>
 * <li>Its code is written in a straightforward way, and is hopefully easy to
 * understand.</li>
 * <li>It has a priority system for transitions.</li>
 * <li>It does not have sub state machines; a state machine is not a state.</li>
 * <li>It has transitions that use a state machine for their condition.</li>
 * <li>With the DSL, transitions to a certain state can be added for multiple
 * source states, thereby faking global transitions.</li>
 * <li>It tries to put as few constraints as possible on the user.</li>
 * <li>It has only one dependency: slf4j for logging, which can be configured to
 * use any other logging framework.</li>
 * <li>The state type can be anything.</li>
 * <li>The event type can be anything.</li>
 * <li>The priority type can be anything as long as it's Ordered.</li>
 * <li>It has two, always accessible modes of usage: asking the state machine
 * for the current state, or having the state machine trigger actions that
 * change the user code state.
 * </ul>
 *
 * @tparam T
 * State type. Each state should have a single instance of this type.
 * An enum is a good fit.
 * @tparam E
 * Event type. Events come into the state machine from the outside
 * world, and are used to trigger state transitions.
 * @tparam P
 * Priority type. Will be used to give priorities to transitions.
 * Enums and Integers are useful here.
 */
class StateMachine[T, E, P <: Ordered[P]] extends Logging {
  private val startStates = mutable.HashSet[T]()
  private val endStates = mutable.HashSet[T]()
  val activeStates = mutable.HashSet[T]()
  private val exitEvents = mutable.HashMap[T, Seq[() => Unit]]()
  private val entryEvents = mutable.HashMap[T, Seq[() => Unit]]()
  private val transitions = mutable.HashMap[T, mutable.PriorityQueue[Transition[T, E, P]]]()

  debug("New Machine")

  /**
   * Resets all active states to the start states.
   */
  def reset() {
    debug("reset()")
    if (startStates.isEmpty) {
      warn("State machine does not contain any start states.")
    }
    activeStates.clear()
    for (startState <- startStates) {
      enterState(startState)
    }
  }

  /**
   * @return whether state is currently active.
   */
  def active(state: T): Boolean = activeStates.contains(state)

  /**
   * @return whether no states are active. Can be caused by all active states
   *         having disappeared into end states, or by having no start states
   *         at all.
   */
  def finished = activeStates.isEmpty

  /**
   * Handle an event coming from the user application. After sending the event
   * to all transitions that have an active source state, poll() will be
   * called.
   *
   * @param event
	 * some event that has happened.
   */
  def handleEvent(event: E) {
    debug(s"handle event $event")

    for (sourceState <- activeStates) {
      for (transition <- transitions(sourceState)) {
        transition.conditions.foreach(_.handleEvent(event))
      }
    }
    poll()
  }

  /**
   * Tells the state machine to look for state changes to execute. This method
   * has to be called regularly, or the state machine will do nothing at all.
   * <ul>
   * <li>Repeat...</li>
   * <ol>
   * <li>For all transitions that have an active source state, find the
   * transitions that will fire.</li>
   * <ul>
   * <li>Ignore transitions that have already fired in this poll().</li>
   * <li>For a single source state, find the transition of the highest
   * priority which will fire (if any fire at all.) If multiple transitions
   * share this priority, fire them all.</li>
   * </ul>
   * <li>For all states that will be exited, fire the exit state event.</li>
   * <li>For all transitions that fire, fire the transition action.</li>
   * <li>For all states that will be entered, fire the entry state event.</li>
   * </ol>
   * <li>... until no new transitions have fired.</li>
   * </ul>
   * <p/>
   * This method prevents itself from looping endlessly on a loop in the state
   * machine by only considering transitions that have not fired before in
   * this poll.
   */
  def poll() {
    var stillNewTransitionsFiring = true
    val transitionsThatHaveFiredBefore = mutable.HashSet[Transition[T, E, P]]()

    do {
      stillNewTransitionsFiring = false
      val statesToExit = mutable.HashSet[T]()
      val transitionsToFire = mutable.HashSet[Transition[T, E, P]]()
      val statesToEnter = mutable.HashSet[T]()

      for (sourceState <- activeStates) {
        var firingPriority: Option[P] = None
        for (transition <- transitions(sourceState)) {
          if (!transitionsThatHaveFiredBefore.contains(transition)) {
            // TODO put in 1 expression
            if (firingPriority.isDefined && transition.priority != firingPriority.get) {
              // We reached a lower prio while higher prio transitions are firing.
              // Don't consider these anymore, go to the next source state.

              // TODO
              //							break;
            } else
            if (transition.conditions.forall(_.isMet)) {
              statesToExit.add(sourceState)
              transitionsToFire.add(transition)
              statesToEnter.add(transition.destinationState)
              firingPriority = Some(transition.priority)
            }
          }
        }
      }

      for (stateToExit <- statesToExit) {
        exitState(stateToExit)
      }
      for (transitionToFire: Transition[T, E, P] <- transitionsToFire) {
        transitionToFire.actions.foreach(_ ())
        transitionsThatHaveFiredBefore.add(transitionToFire)
        stillNewTransitionsFiring = true
      }
      for (stateToEnter <- statesToEnter) {
        enterState(stateToEnter)
      }

    } while (stillNewTransitionsFiring)
  }

  private def exitState(state: T) {
    debug(s"exit state $state")
    if (activeStates.contains(state)) {
      executeExitActions(state)
      activeStates.remove(state)
    }
  }

  private def enterState(newState: T) {
    if (endStates.contains(newState)) {
      debug(s"enter end state $newState")
      executeEntryActions(newState)
      if (activeStates.isEmpty) {
        debug(s"machine is finished")
      }
      return
    }
    if (activeStates.add(newState)) {
      debug(s"enter state $newState")
      executeEntryActions(newState)
      resetTransitions(newState)
    }
  }

  private def resetTransitions(sourceState: T) {
    transitions.get(sourceState).foreach(_.foreach(_.conditions.foreach(_.reset())))
  }

  private def executeExitActions(state: T) {
    exitEvents.get(state).foreach(_.foreach(_ ()))
  }

  private def executeEntryActions(state: T) {
    entryEvents.get(state).foreach(_.foreach(_ ()))
  }

  /**
   * Gives access to the internals of the state machine.
   */
  class Internals {
    /**
     * @return the end states.
     */
    def endStates: Set[T] = Set() ++ StateMachine.this.endStates

    /**
     * @return the start states.
     */
    def startStates: Set[T] = Set() ++ StateMachine.this.startStates

    /**
     * @return the states that have outgoing transitions defined.
     */
    def sourceStates: Set[T] = Set() ++ StateMachine.this.transitions.keySet

    /**
     * @return the outgoing transitions for a source state.
     */
    def transitionsForSourceState(sourceState: T) = StateMachine.this.transitions(sourceState)

    // TODO complete meta information
    /**
     * Add 0 or more actions to be executed when the state is exited.
     */
    def addExitActions(state: T, actions: Seq[() => Unit]) {
      debug(s"Create exit action for '$state' ($actions)")
      // TODO there's probably a better way to express this
      if (!stateMachine.exitEvents.contains(state)) {
        stateMachine.exitEvents.put(state, actions)
        return
      }
      stateMachine.exitEvents(state) ++= actions
    }

    /**
     * Add 0 or more actions to be executed when the state is entered.
     */
    def addEntryActions(state: T, actions: Seq[() => Unit]) {
      debug(s"Create entry action for '$state' ($actions)")
      if (!stateMachine.entryEvents.contains(state)) {
        stateMachine.entryEvents.put(state, actions)
        return
      }
      stateMachine.entryEvents(state) ++= actions
    }

    /**
     * Add an end state.
     */
    def addEndState(endState: T) {
      debug(s"Add end state '$endState'")
      stateMachine.endStates.add(endState)
    }

    /**
     * Add a transition.
     */
    def addTransition(transition: Transition[T, E, P]) {
      val sourceState = transition.sourceState
      debug(s"Create transition from '$sourceState' to '${transition.destinationState}' (pre: '${transition.conditions}', action: '${transition.actions}')")
      if (!transitions.contains(sourceState)) {
        transitions.put(sourceState, mutable.PriorityQueue[Transition[T, E, P]]())
      }
      transitions(sourceState) += transition
    }

    /**
     * Adds a start state, and immediately activates it.
     */
    def addStartState(startState: T) {
      debug(s"Add start state '$startState'")
      stateMachine.startStates.add(startState)
      stateMachine.activeStates.add(startState)
    }

    /**
     * @return the statemachine whose internals these are.
     */
    def stateMachine: StateMachine[T, E, P] = StateMachine.this
  }

}
