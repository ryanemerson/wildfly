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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the current state of an SFSB instance. Required for WFLY-6215 in order to provide serialization when a Tx reaper thread
 * calls an SFSB's afterCompletion callback.  Simple locks are not appropriate as this could block the Tx reaper thread for the
 * duration of a SFSB method call.
 *
 * @author Ryan Emerson
 */
class SessionSynchStateMachine {

    // Allowed Synchronization States
    private static byte EJB_FINISHED = 0;
    private static byte EJB_RUNNING = 1;
    private static byte AFTER_COMPLETION_DELAYED = 2;
    private static byte COMPLETE = 3;

    private static int MAX_EJB_NESTING_COUNT = (int) Math.pow(2, 26);
    private static int TX_STATUS_NOT_SET = 10; // javax.transaction.Status uses 0..9, so we use 10 as not yet set.
    private static int DEFAULT_SYNCH_STATE = TX_STATUS_NOT_SET << 2; // Count = 0, TxStatus = 10, SynchronizationState = EJB_FINISHED (0)

    // int packing layout
    // Nested EJB Count | TxStatus | SynchronizationState
    // 26 bits          | 4 bits   | 2 bits
    private AtomicInteger atomicState = new AtomicInteger(DEFAULT_SYNCH_STATE);

    private final StatefulSessionComponentInstance instance;

    SessionSynchStateMachine(StatefulSessionComponentInstance instance) {
        this.instance = instance;
    }

    void startEJBInvocation() {
        for (;;) {
            int allState = atomicState.get();
            int[] stateArr = unpackBits(allState);
            int ejbCount = stateArr[0];
            int txStatus = stateArr[1];
            int currentState = stateArr[2];

            if (currentState == COMPLETE) {
                throw EjbLogger.ROOT_LOGGER.transactionNoLongerActive(txStatus);
            } else if (compareAndSet(EJB_RUNNING, EJB_RUNNING, TX_STATUS_NOT_SET, TX_STATUS_NOT_SET, ejbCount, ejbCount + 1)) {
                break;
            } else if (compareAndSet(AFTER_COMPLETION_DELAYED, AFTER_COMPLETION_DELAYED, txStatus, txStatus, ejbCount, ejbCount + 1)) {
                break;
            } else if (compareAndSet(EJB_FINISHED, EJB_RUNNING, txStatus, TX_STATUS_NOT_SET, ejbCount, ejbCount)) {
                break;
            }
        }
    }

    void endEJBInvocation(StatefulSessionSynchronizationInterceptor interceptor) {
        for (;;) {
            int allState = atomicState.get();
            int[] stateArr = unpackBits(allState);
            int ejbCount = stateArr[0];
            int txStatus = stateArr[1];
            int currentState = stateArr[2];

            if (currentState == COMPLETE) {
                break;
            } else if (ejbCount > 0 && compareAndSet(EJB_RUNNING, EJB_RUNNING, txStatus, txStatus, ejbCount, ejbCount + 1)) {
                break;
            } else if (compareAndSet(AFTER_COMPLETION_DELAYED, COMPLETE, txStatus, txStatus, ejbCount, ejbCount)) {
                interceptor.executeAfterCompletion(instance, txStatus);
                break;
            } else if (compareAndSet(EJB_RUNNING, EJB_FINISHED, txStatus, txStatus, ejbCount, ejbCount)) {
                break;
            }
        }
    }

    void processAfterCompletionCallback(StatefulSessionSynchronizationInterceptor interceptor, int newTxStatus) {
        for (;;) {
            int allState = atomicState.get();
            int[] stateArr = unpackBits(allState);
            int ejbCount = stateArr[0];
            int txStatus = stateArr[1];
            int currentState = stateArr[2];


            if (currentState == COMPLETE) {
                // Should never happen, as to reach COMPLETE state afterCompletion callback must have been called already
                // In the event that it is called multiple times, do nothing!
                break;
            } else if (compareAndSet(EJB_RUNNING, AFTER_COMPLETION_DELAYED, txStatus, newTxStatus, ejbCount, ejbCount)) {
                break;
            } else if (compareAndSet(EJB_FINISHED, COMPLETE, txStatus, newTxStatus, ejbCount, ejbCount)) {
                interceptor.executeAfterCompletion(instance, newTxStatus);
                break;
            }
        }
    }

    private boolean compareAndSet(int expectedState, int newState, int expectedTxStatus, int newTxStatus, int expectedEjbCount,
            int newEjbCount) {
        int expectedInt = packBits(expectedState, expectedTxStatus, expectedEjbCount);
        int newInt = packBits(newState, newTxStatus, newEjbCount);
        return atomicState.compareAndSet(expectedInt, newInt);
    }

    private int packBits(int state, int txStatus, int ejbCount) {
        validateInput(txStatus, ejbCount);
        return (ejbCount << 6) | (txStatus << 2) | state;
    }

    private int[] unpackBits(int bits) {
        int[] retVal = new int[3];
        retVal[0] = bits >>> 6; // Count
        retVal[1] = (bits >>> 2) & 15; // TxStatus
        retVal[2] = bits & 3; // SynchState
        return retVal;
    }

    private void validateInput(int txStatus, int count) {
        if (txStatus < 0 || txStatus > 10)
            throw new IllegalArgumentException("Invalid txStatus (" + txStatus + "), expected an integer in the range 0..10");

        if (count < 0)
            throw new IllegalStateException("The nested EJB invocation count should never be < 0");

        if (count > MAX_EJB_NESTING_COUNT)
            throw new IllegalStateException("The total number of nested EJB invocations cannot exceed " + MAX_EJB_NESTING_COUNT);
    }
}
