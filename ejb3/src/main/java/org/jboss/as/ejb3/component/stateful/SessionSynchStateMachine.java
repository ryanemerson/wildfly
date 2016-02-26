/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.ejb3.component.stateful;

import org.jboss.as.ejb3.logging.EjbLogger;

import java.util.concurrent.atomic.AtomicStampedReference;

/**
 * Tracks the current state of an SFSB instance. Required for WFLY-6215 in order to provide serialization when a Tx reaper thread
 * calls an SFSB's afterCompletion callback.  Simple locks are not appropriate as this could block the Tx reaper thread for the
 * duration of a SFSB method call.
 *
 * @author Ryan Emerson
 */
class SessionSynchStateMachine {

    private enum State {
        EJB_FINISHED, // Initial State
        EJB_RUNNING,
        AFTER_COMPLETION_DELAYED,
        COMPLETE // End Sate
    }

    private final AtomicStampedReference<State> stateMachine = new AtomicStampedReference<>(State.EJB_FINISHED, -1);
    private final StatefulSessionComponentInstance instance;

    SessionSynchStateMachine(StatefulSessionComponentInstance instance) {
        this.instance = instance;
    }

    void startEJBInvocation() {
        for (;;) {
            State currentState = stateMachine.getReference();
            int currentStatus = stateMachine.getStamp();
            if (currentState == State.COMPLETE || currentState == State.AFTER_COMPLETION_DELAYED) {
                throw EjbLogger.ROOT_LOGGER.callerTransactionAlreadyRolledBack();
            } else if (stateMachine.compareAndSet(State.EJB_FINISHED, State.EJB_RUNNING, currentStatus, -1)) {
                break;
            }
        }
    }

    void endEJBInvocation(StatefulSessionSynchronizationInterceptor interceptor) {
        for (;;) {
            State currentState = stateMachine.getReference();
            int currentStatus = stateMachine.getStamp();

            if (currentState == State.COMPLETE) {
                break;
            } else if (stateMachine.compareAndSet(State.AFTER_COMPLETION_DELAYED, State.COMPLETE, currentStatus, currentStatus)) {
                // Execute AfterCompletion
                interceptor.executeAfterCompletion(instance, currentStatus);
                break;
            } else if (stateMachine.compareAndSet(State.EJB_RUNNING, State.EJB_FINISHED, currentStatus, currentStatus)) {
                break;
            }
        }
    }

    void processAfterCompletionCallback(StatefulSessionSynchronizationInterceptor interceptor, int txStatus) {
        for (;;) {
            State currentState = stateMachine.getReference();
            int currentStatus = stateMachine.getStamp();

            if (currentState == State.COMPLETE) {
                // Should never happen, as to reach COMPLETE state afterCompletion callback must have been called already
                // In the event that it is called multiple times, do nothing!
                break;
            } else if (stateMachine.compareAndSet(State.EJB_RUNNING, State.AFTER_COMPLETION_DELAYED, currentStatus, txStatus)) {
                break;
            } else if (stateMachine.compareAndSet(State.EJB_FINISHED, State.COMPLETE, currentStatus, txStatus)) {
                interceptor.executeAfterCompletion(instance, txStatus);
                break;
            }
        }
    }
}
