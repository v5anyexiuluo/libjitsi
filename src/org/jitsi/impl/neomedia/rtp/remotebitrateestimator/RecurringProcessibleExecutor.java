/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.impl.neomedia.rtp.remotebitrateestimator;

import java.util.*;
import java.util.concurrent.*;

import org.jitsi.util.*;

/**
 * webrtc/webrtc/modules/utility/interface/process_thread.h
 * webrtc/webrtc/modules/utility/source/process_thread_impl.cc
 * webrtc/webrtc/modules/utility/source/process_thread_impl.h
 *
 * @author Lyubomir Marinov
 */
public class RecurringProcessibleExecutor
    implements Executor
{
    /**
     * The <tt>Logger</tt> used by the <tt>RecurringProcessibleExecutor</tt>
     * class and its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(RecurringProcessibleExecutor.class);

    private final List<RecurringProcessible> recurringProcessibles
        = new LinkedList<RecurringProcessible>();

    private Thread thread;

    public boolean deRegisterRecurringProcessible(
            RecurringProcessible recurringProcessible)
    {
        if (recurringProcessible == null)
        {
            return false;
        }
        else
        {
            synchronized (recurringProcessibles)
            {
                boolean removed
                    = recurringProcessibles.remove(recurringProcessible);

                if (removed)
                    startOrNotifyThread();
                return removed;
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * Accepts for execution {@link RecurringProcessible}s only.
     */
    @Override
    public void execute(Runnable command)
    {
        if (command == null)
        {
            throw new NullPointerException("command");
        }
        else if (command instanceof RecurringProcessible)
        {
            registerRecurringProcessible((RecurringProcessible) command);
        }
        else
        {
            throw new RejectedExecutionException(
                    "The class " + command.getClass().getName()
                        + " of command does not implement "
                        + RecurringProcessible.class.getName());
        }
    }

    private boolean process()
    {
        // Wait for the recurringProcessible that should be called next, but
        // don't block thread longer than 100 ms.
        long minTimeToNext = 100L;

        synchronized (recurringProcessibles)
        {
            if (!Thread.currentThread().equals(thread)
                    || recurringProcessibles.isEmpty())
            {
                return false;
            }
            for (RecurringProcessible recurringProcessible
                    : recurringProcessibles)
            {
                long timeToNext
                    = recurringProcessible.getTimeUntilNextProcess();

                if (minTimeToNext > timeToNext)
                    minTimeToNext = timeToNext;
            }
        }

        if (minTimeToNext > 0L)
        {
            synchronized (recurringProcessibles)
            {
                if (!Thread.currentThread().equals(thread)
                        || recurringProcessibles.isEmpty())
                {
                    return false;
                }
                try
                {
                    recurringProcessibles.wait(minTimeToNext);
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                }
                return true;
            }
        }
        synchronized (recurringProcessibles)
        {
            for (RecurringProcessible recurringProcessible
                    : recurringProcessibles)
            {
                long timeToNext
                    = recurringProcessible.getTimeUntilNextProcess();

                if (timeToNext < 1L)
                {
                    try
                    {
                        recurringProcessible.process();
                    }
                    catch (Throwable t)
                    {
                        if (t instanceof InterruptedException)
                        {
                            Thread.currentThread().interrupt();
                        }
                        else if (t instanceof ThreadDeath)
                        {
                            throw (ThreadDeath) t;
                        }
                        else
                        {
                            logger.error(
                                    "The invocation of the method "
                                        + recurringProcessible
                                            .getClass().getName()
                                        + ".process() threw an exception.",
                                    t);
                        }
                    }
                }
            }
        }
        return true;
    }

    public boolean registerRecurringProcessible(
            RecurringProcessible recurringProcessible)
    {
        if (recurringProcessible == null)
        {
            throw new NullPointerException("recurringProcessible");
        }
        else
        {
            synchronized (recurringProcessibles)
            {
                // Only allow recurringProcessible to be registered once.
                if (recurringProcessibles.contains(recurringProcessible))
                {
                    return false;
                }
                else
                {
                    recurringProcessibles.add(0, recurringProcessible);

                    // Wake the thread calling process() to update the waiting
                    // time. The waiting time for the just registered
                    // recurringProcessible may be shorter than all other
                    // registered recurringProcessibles.
                    startOrNotifyThread();
                    return true;
                }
            }
        }
    }

    /**
     * Runs in {@link #thread}.
     */
    private void runInThread()
    {
        try
        {
            while (process());
        }
        finally
        {
            synchronized (recurringProcessibles)
            {
                if (Thread.currentThread().equals(thread))
                {
                    thread = null;
                    // If the (current) thread dies in an unexpected way, make
                    // sure that a new thread will replace it if necessary.
                    startOrNotifyThread();
                }
            }
        }
    }

    /**
     * Starts or notifies {@link #thread} depending on and in accord with the
     * state of this instance.
     */
    private void startOrNotifyThread()
    {
        synchronized (recurringProcessibles)
        {
            if (this.thread == null)
            {
                if (!recurringProcessibles.isEmpty())
                {
                    Thread thread
                        = new Thread()
                                {
                                    @Override
                                    public void run()
                                    {
                                        RecurringProcessibleExecutor.this
                                            .runInThread();
                                    }
                                };

                    thread.setDaemon(true);
                    thread.setName(
                            RecurringProcessibleExecutor.class.getName()
                                + ".thread");

                    boolean started = false;

                    this.thread = thread;
                    try
                    {
                        thread.start();
                        started = true;
                    }
                    finally
                    {
                        if (!started && thread.equals(this.thread))
                            this.thread = null;
                    }
                }
            }
            else
            {
                recurringProcessibles.notifyAll();
            }
        }
    }
}
