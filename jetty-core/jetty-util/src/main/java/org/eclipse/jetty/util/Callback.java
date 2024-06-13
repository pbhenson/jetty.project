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

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.function.Consumer;

import org.eclipse.jetty.util.thread.Invocable;

/**
 * <p>A callback abstraction that handles completed/failed events of asynchronous operations.</p>
 *
 * <p>Semantically this is equivalent to an optimise Promise&lt;Void&gt;, but callback is a more meaningful
 * name than EmptyPromise</p>
 */
public interface Callback extends Invocable
{
    /**
     * Instance of Adapter that can be used when the callback methods need an empty
     * implementation without incurring in the cost of allocating a new Adapter object.
     */
    Callback NOOP = new Callback()
    {
        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        public String toString()
        {
            return "Callback.NOOP";
        }
    };

    /**
     * <p>Completes this callback with the given {@link CompletableFuture}.</p>
     * <p>When the CompletableFuture completes normally, this callback is succeeded;
     * when the CompletableFuture completes exceptionally, this callback is failed.</p>
     *
     * @param completable the CompletableFuture that completes this callback
     */
    default void completeWith(CompletableFuture<?> completable)
    {
        completable.whenComplete((o, x) ->
        {
            if (x == null)
                succeeded();
            else
                failed(x);
        });
    }

    /**
     * <p>Callback invoked when the operation completes.</p>
     *
     * @see #failed(Throwable)
     */
    default void succeeded()
    {
    }

    /**
     * <p>Cancel the callback, prior to either {@link #succeeded()} or {@link #failed(Throwable)} being called.
     * The operation to which the {@code Callback} has been passed must ultimately call either {@link #succeeded()} or
     * {@link #failed(Throwable)}</p>
     *
     * @param cause the reason for the operation failure
     * @return {@code true} if the call to abort was prior to a call to either {@link #succeeded()}, {@link #failed(Throwable)}
     * or another call to {@code abort(Throwable)}.
     * @see Abstract
     */
    default boolean abort(Throwable cause)
    {
        failed(cause);
        return true;
    }

    /**
     * <p>Callback invoked when the operation fails.</p>
     *
     * @param cause the reason for the operation failure
     */
    default void failed(Throwable cause)
    {
    }

    /**
     * <p>Creates a non-blocking callback from the given incomplete CompletableFuture.</p>
     * <p>When the callback completes, either succeeding or failing, the
     * CompletableFuture is also completed, respectively via
     * {@link CompletableFuture#complete(Object)} or
     * {@link CompletableFuture#completeExceptionally(Throwable)}.</p>
     *
     * @param completable the CompletableFuture to convert into a callback
     * @return a callback that when completed, completes the given CompletableFuture
     */
    static Callback from(CompletableFuture<?> completable)
    {
        return from(completable, InvocationType.NON_BLOCKING);
    }

    /**
     * <p>Creates a callback from the given incomplete CompletableFuture,
     * with the given {@code blocking} characteristic.</p>
     *
     * @param completable the CompletableFuture to convert into a callback
     * @param invocation whether the callback is blocking
     * @return a callback that when completed, completes the given CompletableFuture
     */
    static Callback from(CompletableFuture<?> completable, InvocationType invocation)
    {
        if (completable instanceof Callback)
            return (Callback)completable;

        return new Callback()
        {
            @Override
            public void succeeded()
            {
                completable.complete(null);
            }

            @Override
            public void failed(Throwable x)
            {
                try
                {
                    completable.completeExceptionally(x);
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(t, x);
                    throw t;
                }
            }

            @Override
            public InvocationType getInvocationType()
            {
                return invocation;
            }
        };
    }

    /**
     * Creates a callback from the given success and failure lambdas.
     *
     * @param success Called when the callback succeeds
     * @param failure Called when the callback fails
     * @return a new Callback
     */
    static Callback from(Runnable success, Consumer<Throwable> failure)
    {
        return from(InvocationType.BLOCKING, success, failure);
    }

    /**
     * Creates a callback with the given InvocationType from the given success and failure lambdas.
     *
     * @param invocationType the Callback invocation type
     * @param success Called when the callback succeeds
     * @param failure Called when the callback fails
     * @return a new Callback
     */
    static Callback from(InvocationType invocationType, Runnable success, Consumer<Throwable> failure)
    {
        return new Abstract()
        {
            @Override
            public void onCompleteSuccess()
            {
                success.run();
            }

            @Override
            public void onCompleteFailure(Throwable x)
            {
                try
                {
                    failure.accept(x);
                }
                catch (Throwable t)
                {
                    ExceptionUtil.addSuppressedIfNotAssociated(t, x);
                    throw t;
                }
            }

            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }

            @Override
            public String toString()
            {
                return "Callback@%x{%s, %s,%s}".formatted(hashCode(), invocationType, success, failure);
            }
        };
    }

    /**
     * Creates a callback that runs completed when it succeeds or fails
     *
     * @param completed The completion to run on success or failure
     * @return a new callback
     */
    static Callback from(Runnable completed)
    {
        return from(Invocable.getInvocationType(completed), completed);
    }

    /**
     * <p>Creates a Callback with the given {@code invocationType},
     * that runs the given {@code Runnable} when it succeeds or fails.</p>
     *
     * @param invocationType the invocation type of the returned Callback
     * @param completed the Runnable to run when the callback either succeeds or fails
     * @return a new Callback with the given invocation type
     */
    static Callback from(InvocationType invocationType, Runnable completed)
    {
        return new Abstract()
        {
            @Override
            public void onCompleted()
            {
                completed.run();
            }

            @Override
            public InvocationType getInvocationType()
            {
                return invocationType;
            }

            @Override
            public String toString()
            {
                return "Callback.Completing@%x{%s,%s}".formatted(hashCode(), invocationType, completed);
            }
        };
    }

    /**
     * Creates a nested callback that runs completed after
     * completing the nested callback.
     *
     * @param callback The nested callback
     * @param completed The completion to run after the nested callback is completed
     * @return a new callback.
     */
    static Callback from(Callback callback, Runnable completed)
    {
        return new Nested(callback)
        {
            @Override
            public void onCompleted()
            {
                completed.run();
            }
        };
    }

    /**
     * Creates a nested callback that runs completed after
     * completing the nested callback.
     *
     * @param callback The nested callback
     * @param completed The completion to run after the nested callback is completed
     * @return a new callback.
     */
    static Callback from(Callback callback, Consumer<Throwable> completed)
    {
        return new Abstract()
        {
            @Override
            public boolean abort(Throwable cause)
            {
                return callback.abort(cause) && super.abort(cause);
            }

            @Override
            public void failed(Throwable cause)
            {
                callback.failed(cause);
            }

            @Override
            public void succeeded()
            {
                callback.succeeded();
            }

            @Override
            protected void onCompleteSuccess()
            {
                completed.accept(null);
            }

            @Override
            protected void onCompleteFailure(Throwable cause)
            {
                super.onCompleteFailure(cause);
            }
        };
    }

    /**
     * Creates a nested callback that runs completed before
     * completing the nested callback.
     *
     * @param callback The nested callback
     * @param completed The completion to run before the nested callback is completed. Any exceptions thrown
     * from completed will result in a callback failure.
     * @return a new callback.
     */
    static Callback from(Runnable completed, Callback callback)
    {
        return new Callback()
        {
            @Override
            public void succeeded()
            {
                try
                {
                    completed.run();
                    callback.succeeded();
                }
                catch (Throwable t)
                {
                    Callback.failed(callback, t);
                }
            }

            @Override
            public boolean abort(Throwable cause)
            {
                return callback.abort(cause);
            }

            private void completed(Throwable ignored)
            {
                completed.run();
            }

            @Override
            public void failed(Throwable x)
            {
                Callback.failed(this::completed, callback::failed, x);
            }
        };
    }

    static Callback from(Runnable success, Consumer<Throwable> failure, Runnable complete)
    {
        return new Abstract()
        {
            @Override
            protected void onCompleteSuccess()
            {
                ExceptionUtil.callThen(success, complete);
            }

            @Override
            public void onFailure(Throwable cause)
            {
                failure.accept(cause);
            }

            @Override
            public void onCompleted()
            {
                complete.run();
            }
        };
    }

    /**
     * Creates a nested callback which always fails the nested callback on completion.
     *
     * @param callback The nested callback
     * @param cause The cause to fail the nested callback, if the new callback is failed the reason
     * will be added to this cause as a suppressed exception.
     * @return a new callback.
     */
    static Callback from(Callback callback, Throwable cause)
    {
        return new Callback()
        {
            @Override
            public void succeeded()
            {
                callback.failed(cause);
            }

            @Override
            public boolean abort(Throwable abortCause)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(cause, abortCause);
                return callback.abort(cause);
            }

            @Override
            public void failed(Throwable x)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(cause, x);
                Callback.failed(callback, cause);
            }
        };
    }

    /**
     * Creates a callback which combines two other callbacks and will succeed or fail them both.
     * @param callback1 The first callback
     * @param callback2 The second callback
     * @return a new callback.
     */
    static Callback from(Callback callback1, Callback callback2)
    {
        return combine(callback1, callback2);
    }

    /**
     * <p>A Callback implementation that calls the {@link #completed()} method when it either succeeds or fails.</p>
     * @deprecated use {@link Abstract}
     */
    @Deprecated (forRemoval = true, since = "12.0.11")
    interface Completing extends Callback
    {
        void completed();

        @Override
        default void succeeded()
        {
            completed();
        }

        @Override
        default void failed(Throwable x)
        {
            try
            {
                completed();
            }
            catch (Throwable t)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(t, x);
                throw t;
            }
        }
    }

    /**
     * <p>A Callback implementation that calls the {@link #onCompleted()} method when it either succeeds or fails.
     * If the callback is aborted, then {@link #onAbort(Throwable)} is called, but the {@link #onCompleteFailure(Throwable)}
     * and {@link #onCompleted()} methods are not called until either {@link #succeeded()} or {@link #failed(Throwable)}
     * are called.</p>
     */
    class Abstract implements Callback
    {
        private static final Throwable SUCCEEDED = new StaticException("Completed");
        private final AtomicMarkableReference<Throwable> _completion = new AtomicMarkableReference<>(null, false);

        @Override
        public void succeeded()
        {
            if (_completion.compareAndSet(null, SUCCEEDED, false, true))
            {
                try
                {
                    onCompleteSuccess();
                }
                finally
                {
                    onCompleted();
                }
                return;
            }

            if (_completion.isMarked())
                return;

            Throwable cause = _completion.getReference();
            if (_completion.compareAndSet(cause, cause, false, true))
            {
                doCompleteFailure(cause);
            }
        }

        /**
         * Abort the callback if it has not already been completed.
         * The {@link #onAbort(Throwable)} method will be called, then the {@link #onCompleteFailure(Throwable)}
         * will be called only once the {@link #succeeded()} or {@link #failed(Throwable)} methods are called, followed
         * by a call to {@link #onCompleted()}.
         * @param cause The cause of the abort
         * @return true if the callback was aborted
         */
        @Override
        public boolean abort(Throwable cause)
        {
            if (cause == null)
                cause = new CancellationException();
            // Try aborting directly by assuming that the callback is neither failed nor aborted.
            if (_completion.compareAndSet(null, cause, false, false))
            {
                ExceptionUtil.callThen(cause, this::onAbort, this::onFailure);
                return true;
            }

            Throwable failure = _completion.getReference();
            ExceptionUtil.addSuppressedIfNotAssociated(failure, cause);
            return false;
        }

        @Override
        public void failed(Throwable cause)
        {
            if (cause == null)
                cause = new Exception();
            // Try failing directly by assuming that the callback is neither failed nor aborted.
            if (_completion.compareAndSet(null, cause, false, true))
            {
                ExceptionUtil.callThen(cause, this::onFailure, this::doCompleteFailure);
                return;
            }

            Throwable failure = _completion.getReference();
            ExceptionUtil.addSuppressedIfNotAssociated(failure, cause);

            // Have we somehow already completed?
            if (_completion.isMarked())
                return;

            // Have we aborted? in which case we can complete
            if (failure != null && _completion.compareAndSet(failure, failure, false, true))
            {
                doCompleteFailure(failure);
            }
        }

        private void doCompleteFailure(Throwable failure)
        {
            ExceptionUtil.callThen(failure, this::onCompleteFailure, this::onCompleted);
        }

        /**
         * Called when the callback has been {@link #succeeded() succeeded} and not {@link #abort(Throwable) aborted}.
         * The {@link #onCompleted()} method will be also be called after this call.
         * Typically, this method is implement to act on the success.  It can release or reuse any resources that may have
         * been in use by the scheduled operation, but it may defer that release or reuse to the subsequent call to
         * {@link #onCompleted()} to avoid double releasing.
         */
        protected void onCompleteSuccess()
        {
        }

        /**
         * Called when the callback has been {@link #abort(Throwable) aborted}.
         * The {@link #onCompleteFailure(Throwable)} method will ultimately be called, but only once the callback has been
         * {@link #succeeded() succeeded} or {@link #failed(Throwable)}, and then the {@link #onCompleted()} method will be also be called.
         * Typically, this method is implemented to act on the failure, but it should not release or reuse any resources that may
         * be in use by the schedule operation.
         * @param cause The cause of the abort
         */
        protected void onAbort(Throwable cause)
        {
        }

        /**
         * Called when the callback has either been {@link #abort(Throwable) aborted} or {@link #failed(Throwable)}.
         * The {@link #onCompleteFailure(Throwable)} method will ultimately be called, but only once the callback has been
         * {@link #succeeded() succeeded} or {@link #failed(Throwable)}, and then the {@link #onCompleted()} method will be also be called.
         * Typically, this method is implemented to act on the failure, but it should not release or reuse any resources that may
         * be in use by the schedule operation.
         * @param cause The cause of the failure
         */
        protected void onFailure(Throwable cause)
        {
        }

        /**
         * Called when the callback has been {@link #failed(Throwable) failed} or {@link #abort(Throwable) aborted} and
         * then either {@link #succeeded() succeeded} or {@link #failed(Throwable)} called.
         * The {@link #onCompleted()} method will be also be called after this call.
         * Typically, this method is implemented to act on the failure.It can release or reuse any resources that may have
         * been in use by the scheduled operation, but it may defer that release or reuse to the subsequent call to
         * {@link #onCompleted()} to avoid double releasing
         * @param cause The cause of the failure
         */
        protected void onCompleteFailure(Throwable cause)
        {
        }

        /**
         * Called once the callback has been either {@link #succeeded() succeeded} or {@link #failed(Throwable)}.
         * Typically, this method is implemented to release resources that may be used by the scheduled operation.
         */
        public void onCompleted()
        {
        }
    }

    /*
     * A Callback that wraps another Callback
     */
    class Wrapper implements Callback
    {
        private final Callback callback;

        public Wrapper(Callback callback)
        {
            this.callback = Objects.requireNonNull(callback);
        }

        public Callback getCallback()
        {
            return callback;
        }

        @Override
        public boolean abort(Throwable cause)
        {
            return callback.abort(cause);
        }

        @Override
        public void failed(Throwable cause)
        {
            callback.failed(cause);
        }

        @Override
        public void succeeded()
        {
            callback.succeeded();
        }

        @Override
        public InvocationType getInvocationType()
        {
            return callback.getInvocationType();
        }

        @Override
        public String toString()
        {
            return "%s@%x:%s".formatted(getClass().getSimpleName(), hashCode(), callback);
        }
    }

    /**
     * Nested Completing Callback that completes after
     * completing the nested callback
     */
    class Nested extends Abstract
    {
        private final Callback callback;

        public Nested(Callback callback)
        {
            this.callback = Objects.requireNonNull(callback);
        }

        @Override
        protected void onAbort(Throwable cause)
        {
            callback.abort(cause);
        }

        @Override
        protected void onCompleteSuccess()
        {
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            callback.failed(cause);
        }
    }

    static Callback combine(Callback cb1, Callback cb2)
    {
        if (cb1 == null || cb1 == cb2)
            return cb2;
        if (cb2 == null)
            return cb1;

        return new Callback()
        {
            @Override
            public void succeeded()
            {
                try
                {
                    cb1.succeeded();
                }
                finally
                {
                    cb2.succeeded();
                }
            }

            @Override
            public boolean abort(Throwable cause)
            {
                boolean c1 = cb1.abort(cause);
                boolean c2 = cb2.abort(cause);
                return c1 || c2;
            }

            @Override
            public void failed(Throwable x)
            {
                Callback.failed(cb1::failed, cb2::failed, x);
            }

            @Override
            public InvocationType getInvocationType()
            {
                return Invocable.combine(Invocable.getInvocationType(cb1), Invocable.getInvocationType(cb2));
            }
        };
    }

    /**
     * <p>A {@link CompletableFuture} that is also a {@link Callback}.</p>
     * @deprecated TODO This should either be a Callback that wraps a CompletableFuture OR a CompletableFuture that
     *                  wraps a Callback, but not both.
     */
    @Deprecated
    class Completable extends CompletableFuture<Void> implements Callback
    {
        /**
         * <p>Creates a new {@code Completable} to be consumed by the given
         * {@code consumer}, then returns the newly created {@code Completable}.</p>
         *
         * @param consumer the code that consumes the newly created {@code Completable}
         * @return the newly created {@code Completable}
         */
        public static Completable with(Consumer<Completable> consumer)
        {
            Completable completable = new Completable();
            consumer.accept(completable);
            return completable;
        }

        /**
         * Creates a completable future given a callback.
         *
         * @param callback The nested callback.
         * @return a new Completable which will succeed this callback when completed.
         */
        public static Completable from(Callback callback)
        {
            return new Completable(callback.getInvocationType())
            {
                @Override
                public void succeeded()
                {
                    callback.succeeded();
                    super.succeeded();
                }

                @Override
                public boolean abort(Throwable cause)
                {
                    return callback.abort(cause) && super.abort(cause);
                }

                @Override
                public void failed(Throwable x)
                {
                    Callback.failed(callback::failed, super::failed, x);
                }
            };
        }

        private final InvocationType invocation;

        public Completable()
        {
            this(Invocable.InvocationType.NON_BLOCKING);
        }

        public Completable(InvocationType invocation)
        {
            this.invocation = invocation;
        }

        @Override
        public void succeeded()
        {
            complete(null);
        }

        @Override
        public boolean abort(Throwable cause)
        {
            return cancel(false);
        }

        @Override
        public void failed(Throwable x)
        {
            completeExceptionally(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return invocation;
        }

        /**
         * <p>Returns a new {@link Completable} that, when this {@link Completable}
         * succeeds, is passed to the given consumer and then returned.</p>
         * <p>If this {@link Completable} fails, the new {@link Completable} is
         * also failed, and the consumer is not invoked.</p>
         *
         * @param consumer the consumer that receives the {@link Completable}
         * @return a new {@link Completable} passed to the consumer
         * @see #with(Consumer)
         */
        public Completable compose(Consumer<Completable> consumer)
        {
            Completable completable = new Completable();
            whenComplete((r, x) ->
            {
                if (x == null)
                    consumer.accept(completable);
                else
                    completable.failed(x);
            });
            return completable;
        }
    }

    /**
     * Invoke a callback failure, handling any {@link Throwable} thrown
     * by adding the passed {@code failure} as a suppressed with
     * {@link ExceptionUtil#addSuppressedIfNotAssociated(Throwable, Throwable)}.
     * @param callback The callback to fail
     * @param failure The failure
     * @throws RuntimeException If thrown, will have the {@code failure} added as a suppressed.
     */
    private static void failed(Callback callback, Throwable failure)
    {
        try
        {
            callback.failed(failure);
        }
        catch (Throwable t)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(t, failure);
            throw t;
        }
    }

    /**
     * Invoke two consumers of a failure, handling any {@link Throwable} thrown
     * by adding the passed {@code failure} as a suppressed with
     * {@link ExceptionUtil#addSuppressedIfNotAssociated(Throwable, Throwable)}.
     * @param first The first consumer of a failure
     * @param second The first consumer of a failure
     * @param failure The failure
     * @throws RuntimeException If thrown, will have the {@code failure} added as a suppressed.
     */
    private static void failed(Consumer<Throwable> first, Consumer<Throwable> second,  Throwable failure)
    {
        try
        {
            first.accept(failure);
        }
        catch (Throwable t)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(failure, t);
        }
        try
        {
            second.accept(failure);
        }
        catch (Throwable t)
        {
            ExceptionUtil.addSuppressedIfNotAssociated(t, failure);
            throw t;
        }
    }
}
