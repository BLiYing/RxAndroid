/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reactivex.android.schedulers;

import android.os.Handler;
import android.os.Message;
import io.reactivex.Scheduler;
import io.reactivex.disposables.Disposable;
import io.reactivex.disposables.Disposables;
import io.reactivex.exceptions.OnErrorNotImplementedException;
import io.reactivex.plugins.RxJavaPlugins;
import java.util.concurrent.TimeUnit;

final class HandlerScheduler extends Scheduler {
    /** A period value which indicates that the scheduled action should only run once. */
    private static final long NO_PERIOD = -1;

    private final Handler handler;

    HandlerScheduler(Handler handler) {
        this.handler = handler;
    }

    @Override
    public Disposable scheduleDirect(Runnable run, long delay, TimeUnit unit) {
        return schedule(run, delay, NO_PERIOD, unit);
    }

    @Override
    public Disposable schedulePeriodicallyDirect(Runnable run, long initialDelay, long period,
            TimeUnit unit) {
        if (period < 0) throw new IllegalArgumentException("period < 0: " + period);
        return schedule(run, initialDelay, period, unit);
    }

    private Disposable schedule(Runnable run, long initialDelay, long period, TimeUnit unit) {
        if (run == null) throw new NullPointerException("run == null");
        if (initialDelay < 0) throw new IllegalArgumentException("delay < 0: " + initialDelay);
        if (unit == null) throw new NullPointerException("unit == null");

        run = RxJavaPlugins.onSchedule(run);
        ScheduledRunnable scheduled = new ScheduledRunnable(handler, run, period, unit);
        handler.postDelayed(scheduled, unit.toMillis(initialDelay));
        return scheduled;
    }

    @Override
    public Worker createWorker() {
        return new HandlerWorker(handler);
    }

    private static final class HandlerWorker extends Worker {
        private final Handler handler;

        private volatile boolean disposed;

        HandlerWorker(Handler handler) {
            this.handler = handler;
        }

        @Override
        public Disposable schedule(Runnable run, long delay, TimeUnit unit) {
            return schedule(run, delay, NO_PERIOD, unit);
        }

        @Override
        public Disposable schedulePeriodically(Runnable run, long delay, long period,
                TimeUnit unit) {
            if (period < 0) throw new IllegalArgumentException("period < 0: " + period);
            return schedule(run, delay, period, unit);
        }

        private Disposable schedule(Runnable run, long initialDelay, long period, TimeUnit unit) {
            if (run == null) throw new NullPointerException("run == null");
            if (initialDelay < 0) throw new IllegalArgumentException("delay < 0: " + initialDelay);
            if (unit == null) throw new NullPointerException("unit == null");

            if (disposed) {
                return Disposables.disposed();
            }

            run = RxJavaPlugins.onSchedule(run);

            ScheduledRunnable scheduled = new ScheduledRunnable(handler, run, period, unit);

            Message message = Message.obtain(handler, scheduled);
            message.obj = this; // Used as token for batch disposal of this worker's runnables.

            handler.sendMessageDelayed(message, unit.toMillis(initialDelay));

            // Re-check disposed state for removing in case we were racing a call to dispose().
            if (disposed) {
                handler.removeCallbacks(scheduled);
                return Disposables.disposed();
            }

            return scheduled;
        }

        @Override
        public void dispose() {
            disposed = true;
            handler.removeCallbacksAndMessages(this /* token */);
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }

    private static final class ScheduledRunnable implements Runnable, Disposable {
        private final Handler handler;
        private final Runnable delegate;
        /** The time delay for rescheduling or {@link #NO_PERIOD} if a one-shot execution. */
        private final long periodMillis;

        private volatile boolean disposed;

        ScheduledRunnable(Handler handler, Runnable delegate, long period, TimeUnit unit) {
            this.handler = handler;
            this.delegate = delegate;
            this.periodMillis = period == NO_PERIOD ? NO_PERIOD : unit.toMillis(period);
        }

        @Override
        public void run() {
            try {
                delegate.run();
            } catch (Throwable t) {
                IllegalStateException ie;
                if (t instanceof OnErrorNotImplementedException) {
                    ie = new IllegalStateException(
                        "Exception thrown on Scheduler. Add `onError` handling.", t);
                } else {
                    ie = new IllegalStateException("Fatal Exception thrown on Scheduler.", t);
                }
                RxJavaPlugins.onError(ie);
                Thread thread = Thread.currentThread();
                thread.getUncaughtExceptionHandler().uncaughtException(thread, ie);
                return;
            }

            if (periodMillis != NO_PERIOD && !disposed) {
                handler.postDelayed(this, periodMillis);

                // Re-check disposed state for removing in case we were racing a call to dispose().
                if (disposed) {
                    handler.removeCallbacks(this);
                }
            }
        }

        @Override
        public void dispose() {
            disposed = true;
            handler.removeCallbacks(this);
        }

        @Override
        public boolean isDisposed() {
            return disposed;
        }
    }
}
