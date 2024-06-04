//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IteratingCallbackTest
{
    private Scheduler scheduler;

    @BeforeEach
    public void prepare() throws Exception
    {
        scheduler = new ScheduledExecutorScheduler();
        scheduler.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        scheduler.stop();
    }

    @Test
    public void testNonWaitingProcess() throws Exception
    {
        TestCB cb = new TestCB()
        {
            int i = 10;

            @Override
            protected Action process() throws Exception
            {
                processed++;
                if (i-- > 1)
                {
                    succeeded(); // fake a completed IO operation
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };

        cb.iterate();
        assertTrue(cb.waitForComplete());
        assertEquals(10, cb.processed);
    }

    @Test
    public void testWaitingProcess() throws Exception
    {
        TestCB cb = new TestCB()
        {
            int i = 4;

            @Override
            protected Action process() throws Exception
            {
                processed++;
                if (i-- > 1)
                {
                    scheduler.schedule(successTask, 50, TimeUnit.MILLISECONDS);
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };

        cb.iterate();

        assertTrue(cb.waitForComplete());

        assertEquals(4, cb.processed);
    }

    @Test
    public void testWaitingProcessSpuriousIterate() throws Exception
    {
        final TestCB cb = new TestCB()
        {
            int i = 4;

            @Override
            protected Action process() throws Exception
            {
                processed++;
                if (i-- > 1)
                {
                    scheduler.schedule(successTask, 50, TimeUnit.MILLISECONDS);
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };

        cb.iterate();
        scheduler.schedule(new Runnable()
        {
            @Override
            public void run()
            {
                cb.iterate();
                if (!cb.isSucceeded())
                    scheduler.schedule(this, 50, TimeUnit.MILLISECONDS);
            }
        }, 49, TimeUnit.MILLISECONDS);

        assertTrue(cb.waitForComplete());

        assertEquals(4, cb.processed);
    }

    @Test
    public void testNonWaitingProcessFailure() throws Exception
    {
        TestCB cb = new TestCB()
        {
            int i = 10;

            @Override
            protected Action process() throws Exception
            {
                processed++;
                if (i-- > 1)
                {
                    if (i > 5)
                        succeeded(); // fake a completed IO operation
                    else
                        failed(new Exception("testing"));
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };

        cb.iterate();
        assertFalse(cb.waitForComplete());
        assertEquals(5, cb.processed);
    }

    @Test
    public void testWaitingProcessFailure() throws Exception
    {
        TestCB cb = new TestCB()
        {
            int i = 4;

            @Override
            protected Action process() throws Exception
            {
                processed++;
                if (i-- > 1)
                {
                    scheduler.schedule(i > 2 ? successTask : failTask, 50, TimeUnit.MILLISECONDS);
                    return Action.SCHEDULED;
                }
                return Action.SUCCEEDED;
            }
        };

        cb.iterate();

        assertFalse(cb.waitForComplete());
        assertEquals(2, cb.processed);
    }

    @Test
    public void testIdleWaiting() throws Exception
    {
        final CountDownLatch idle = new CountDownLatch(1);

        TestCB cb = new TestCB()
        {
            int i = 5;

            @Override
            protected Action process()
            {
                processed++;

                return switch (i--)
                {
                    case 1, 4 ->
                    {
                        scheduler.schedule(successTask, 5, TimeUnit.MILLISECONDS);
                        yield Action.SCHEDULED;
                    }
                    case 2, 5 ->
                    {
                        succeeded();
                        yield Action.SCHEDULED;
                    }
                    case 3 ->
                    {
                        scheduler.schedule(idle::countDown, 5, TimeUnit.MILLISECONDS);
                        yield Action.IDLE;
                    }
                    case 0 -> Action.SUCCEEDED;
                    default -> throw new IllegalStateException();
                };
            }
        };

        cb.iterate();
        idle.await(10, TimeUnit.SECONDS);
        assertTrue(cb.isIdle());

        cb.iterate();
        assertTrue(cb.waitForComplete());
        assertEquals(6, cb.processed);
    }

    @Test
    public void testCloseDuringProcessingReturningScheduled() throws Exception
    {
        testCloseDuringProcessing(IteratingCallback.Action.SCHEDULED);
    }

    @Test
    public void testCloseDuringProcessingReturningSucceeded() throws Exception
    {
        testCloseDuringProcessing(IteratingCallback.Action.SUCCEEDED);
    }

    private void testCloseDuringProcessing(final IteratingCallback.Action action) throws Exception
    {
        final CountDownLatch failureLatch = new CountDownLatch(1);
        IteratingCallback callback = new IteratingCallback()
        {
            @Override
            protected Action process()
            {
                close();
                return action;
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                failureLatch.countDown();
            }
        };

        callback.iterate();

        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
    }

    private abstract static class TestCB extends IteratingCallback
    {
        protected Runnable successTask = this::succeeded;
        protected Runnable failTask = () -> failed(new Exception("testing failure"));
        protected CountDownLatch completed = new CountDownLatch(1);
        protected int processed = 0;

        @Override
        protected void onCompleteSuccess()
        {
            completed.countDown();
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            completed.countDown();
        }

        boolean waitForComplete() throws InterruptedException
        {
            completed.await(10, TimeUnit.SECONDS);
            return isSucceeded();
        }
    }

    @Test
    public void testMultipleFailures() throws Exception
    {
        AtomicInteger process = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        IteratingCallback icb = new IteratingCallback()
        {
            @Override
            protected Action process() throws Throwable
            {
                process.incrementAndGet();
                return Action.SCHEDULED;
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                super.onCompleteFailure(cause);
                failure.incrementAndGet();
            }
        };

        icb.iterate();
        assertEquals(1, process.get());
        assertEquals(0, failure.get());

        icb.failed(new Throwable("test1"));

        assertEquals(1, process.get());
        assertEquals(1, failure.get());

        icb.succeeded();
        assertEquals(1, process.get());
        assertEquals(1, failure.get());

        icb.failed(new Throwable("test2"));
        assertEquals(1, process.get());
        assertEquals(1, failure.get());
    }

    @Test
    public void testWhenIdleAbortSerializesOnCompleteFailure() throws Exception
    {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch ocfLatch = new CountDownLatch(1);
        IteratingCallback icb = new IteratingCallback()
        {
            @Override
            protected Action process()
            {
                count.incrementAndGet();
                return Action.IDLE;
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                ocfLatch.countDown();
            }
        };

        icb.iterate();

        assertEquals(1, count.get());

        // Aborting should not iterate.
        icb.abort(new Exception());

        assertTrue(ocfLatch.await(5, TimeUnit.SECONDS));
        assertTrue(icb.isAborted());
        assertEquals(1, count.get());
    }

    @Test
    public void testWhenProcessingAbortSerializesOnCompleteFailure() throws Exception
    {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch ocfLatch = new CountDownLatch(1);
        IteratingCallback icb = new IteratingCallback()
        {
            @Override
            protected Action process() throws Throwable
            {
                count.incrementAndGet();
                abort(new Exception());

                // After calling abort, onCompleteFailure() must not be called yet.
                assertFalse(ocfLatch.await(1, TimeUnit.SECONDS));

                return Action.SCHEDULED;
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                ocfLatch.countDown();
            }
        };

        icb.iterate();

        assertEquals(1, count.get());

        assertTrue(ocfLatch.await(5, TimeUnit.SECONDS));
        assertTrue(icb.isAborted());

        // Calling succeeded() won't cause further iterations.
        icb.succeeded();

        assertEquals(1, count.get());
    }
}
