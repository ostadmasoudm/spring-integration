/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.springframework.integration.aggregator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.integration.core.Message;
import org.springframework.integration.handler.AbstractMessageHandler;
import org.springframework.integration.message.MessageSource;
import org.springframework.integration.store.MessageGroup;
import org.springframework.integration.store.MessageGroupStore;
import org.springframework.integration.store.SimpleMessageStore;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * This Endpoint serves as a barrier for messages that should not be processed yet. The decision when a message can be
 * processed is delegated to a {@link org.springframework.integration.aggregator.ReleaseStrategy ReleaseStrategy}.
 * When a message can be processed it is up to the client to take care of the locking (potentially from the ReleaseStrategy's
 * {@link org.springframework.integration.aggregator.ReleaseStrategy#canRelease(org.springframework.integration.store.MessageGroup) canRelease(..)}
 * method).
 * <p/>
 * The messages will be stored in a {@link org.springframework.integration.store.MessageGroupStore MessageStore}
 * for each correlation key.
 *
 * @author Iwein Fuld
 */
public class CorrelatingMessageBarrier extends AbstractMessageHandler implements MessageSource {
	private static final Log log = LogFactory.getLog(CorrelatingMessageBarrier.class);

	private CorrelationStrategy correlationStrategy;
	private ReleaseStrategy releaseStrategy;

	private final ConcurrentMap<Object, Object> correlationLocks = new ConcurrentHashMap<Object, Object>();
	private final MessageGroupStore store;

	public CorrelatingMessageBarrier(MessageGroupStore store) {
		this.store = store;
	}

	public CorrelatingMessageBarrier() {
		this(new SimpleMessageStore(0));
	}

	/**
	 * Set the CorrelationStrategy to be used to determine the correlation key for incoming messages
	 */
	public void setCorrelationStrategy(CorrelationStrategy correlationStrategy) {
		this.correlationStrategy = correlationStrategy;
	}

	/**
	 * Set the ReleaseStrategy that should be used when deciding if a group in this barrier may be released.
	 */
	public void setReleaseStrategy(ReleaseStrategy releaseStrategy) {
		this.releaseStrategy = releaseStrategy;
	}

	@Override
	protected void handleMessageInternal(Message<?> message) throws Exception {
		Object correlationKey = correlationStrategy.getCorrelationKey(message);
		Object lock = getLock(correlationKey);
		synchronized (lock) {
			store.addMessageToGroup(correlationKey, message);
		}
		if (log.isDebugEnabled()) {
			log.debug(String.format("Handled message for key [%s]: %s.", correlationKey, message));
		}
	}

	private Object getLock(Object correlationKey) {
		Object existingLock = correlationLocks.putIfAbsent(correlationKey, correlationKey);
		return existingLock == null ? correlationKey : existingLock;
	}


	public Message receive() {
		for (Object key : correlationLocks.keySet()) {
			Object lock = getLock(key);
			synchronized (lock) {
				MessageGroup group = store.getMessageGroup(key);
				//group might be removed by another thread
				if (group != null) {
					if (releaseStrategy.canRelease(group)) {
						Message<?> nextMessage = null;

						Iterator<Message<?>> unmarked = group.getUnmarked().iterator();
						if (unmarked.hasNext()) {
							nextMessage = unmarked.next();
							store.removeMessageFromGroup(key, nextMessage);
							if (log.isDebugEnabled()) {
								log.debug(String.format("Released message for key [%s]: %s.", key, nextMessage));
							}
						} else {
							remove(key);
						}
						return nextMessage;
					}
				}
			}
		}
		return null;
	}

	private void remove(Object key) {
		correlationLocks.remove(key);
		store.removeMessageGroup(key);
	}
}