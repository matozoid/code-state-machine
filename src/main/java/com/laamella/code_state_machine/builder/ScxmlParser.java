package com.laamella.code_state_machine.builder;

import java.io.IOException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.laamella.code_state_machine.Action;
import com.laamella.code_state_machine.ActionChain;
import com.laamella.code_state_machine.Transition;
import com.laamella.code_state_machine.Precondition;
import com.laamella.code_state_machine.StateMachine;
import com.laamella.code_state_machine.StateMachine.Builder;
import com.laamella.code_state_machine.precondition.AlwaysPrecondition;

/**
 * A State machine builder that attempts to read the SCXML format. Since many
 * features are mismatched, it does not do a very good job.
 * <table>
 * <tr>
 * <td>Supported?
 * <td>Feature
 * </tr>
 * <tr>
 * <td>&#x2713;
 * <td>normal/start/end states
 * </tr>
 * <tr>
 * <td>&#x2713; / &#x2717;
 * <td>on entry/on exit events (interpretation is up to the user)
 * </tr>
 * <tr>
 * <td>&#x2713; / &#x2717;
 * <td>event (action) on transition (interpretation is up to the user)
 * </tr>
 * <tr>
 * <td>&#x2713; / &#x2717;
 * <td>(pre)conditions for transitions (interpretation is up to the user)
 * </tr>
 * <tr>
 * <td>&#x2717;
 * <td>clusters (treated as normal states. Substates are mixed into the main
 * state machine)
 * </tr>
 * <tr>
 * <td>&#x2717;
 * <td>executable content
 * </tr>
 * <tr>
 * <td>&#x2717;
 * <td>parallel states (treated as normal states)
 * </tr>
 */
public abstract class ScxmlParser<T, E, P extends Comparable<P>> {
	private static final String PARALLEL_ELEMENT = "parallel";

	private static final String TRANSITION_ELEMENT = "transition";

	private static final Logger log = LoggerFactory.getLogger(ScxmlParser.class);

	private static final String CONDITION_ATTRIBUTE = "cond";
	private static final String EVENT_ATTRIBUTE = "event";
	private static final String TARGET_ATTRIBUTE = "target";
	private static final String ROOT_STATE_MACHINE_ELEMENT = "scxml";
	private static final String INITIAL_ATTRIBUTE = "initial";
	private static final String ID_ATTRIBUTE = "id";
	private static final String STATE_ELEMENT = "state";
	private static final String FINAL_STATE_ELEMENT = "final";

	private final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();

	private final P defaultPriority;

	public ScxmlParser(final P defaultPriority) {
		this.defaultPriority = defaultPriority;
	}

	public StateMachine.Builder<T, E, P> parse(final InputSource xml) throws ParserConfigurationException,
			SAXException, IOException {
		final Builder<T, E, P> builder = new StateMachine.Builder<T, E, P>();
		final DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
		final Element root = (Element) documentBuilder.parse(xml).getChildNodes().item(0);

		parseState(root, builder);
		return builder;
	}

	private T parseState(final Element stateElement, final Builder<T, E, P> builder) {
		if (stateElement.hasAttribute(INITIAL_ATTRIBUTE)) {
			final T initialState = interpretStateName(stateElement.getAttribute(INITIAL_ATTRIBUTE));
			builder.addStartState(initialState);
		}
		final String stateName = stateElement.getAttribute(ID_ATTRIBUTE);
		final T state = interpretStateName(stateName);

		final NodeList childNodes = stateElement.getChildNodes();
		for (int i = 0; i < childNodes.getLength(); i++) {
			final Node subNode = childNodes.item(i);
			if (subNode.getNodeType() == Node.ELEMENT_NODE) {
				final Element subElement = (Element) subNode;
				final String subNodeName = subElement.getNodeName();
				if (subNodeName.equals(STATE_ELEMENT) || subNodeName.equals(STATE_ELEMENT)
						|| subNodeName.equals(PARALLEL_ELEMENT) || subNodeName.equals(ROOT_STATE_MACHINE_ELEMENT)) {
					parseState(subElement, builder);
				} else if (subNodeName.equals(FINAL_STATE_ELEMENT)) {
					builder.addEndState(parseState(subElement, builder));
				} else if (subNodeName.equals(TRANSITION_ELEMENT)) {
					if (subElement.hasAttribute(TARGET_ATTRIBUTE)) {
						final T targetState = interpretStateName(subElement.getAttribute(TARGET_ATTRIBUTE));

						Precondition<E> condition = new AlwaysPrecondition<E>();
						if (subElement.hasAttribute(CONDITION_ATTRIBUTE)) {
							condition = interpretCondition(subElement.getAttribute(CONDITION_ATTRIBUTE));
						}

						final ActionChain actions = new ActionChain();
						if (subElement.hasAttribute(EVENT_ATTRIBUTE)) {
							actions.add(interpretEvent(subElement.getAttribute(EVENT_ATTRIBUTE)));
						}

						// TODO do something about priorities
						builder.addTransition(new Transition<T, E, P>(state, targetState, condition,
								defaultPriority, actions));
					} else {
						log.warn("State " + stateName + " has a transition going nowhere.");
					}
				} else if (subNodeName.equals("onentry")) {
					builder.addEntryActions(state, interpretEvent(subNode.getTextContent()));
				} else if (subNodeName.equals("onexit")) {
					builder.addExitActions(state, interpretEvent(subNode.getTextContent()));
				}
			}
		}
		return state;
	}

	protected abstract Action interpretEvent(final String attribute);

	protected abstract Precondition<E> interpretCondition(final String attribute);

	protected abstract T interpretStateName(final String name);
}
