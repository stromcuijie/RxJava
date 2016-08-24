/**
 * Copyright 2016 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See
 * the License for the specific language governing permissions and limitations under the License.
 */

package io.reactivex.processors;

import java.util.concurrent.atomic.*;

import org.reactivestreams.*;

import io.reactivex.internal.functions.ObjectHelper;
import io.reactivex.internal.fuseable.QueueSubscription;
import io.reactivex.internal.queue.SpscLinkedArrayQueue;
import io.reactivex.internal.subscriptions.*;
import io.reactivex.internal.util.BackpressureHelper;
import io.reactivex.plugins.RxJavaPlugins;

/**
 * Subject that allows only a single Subscriber to subscribe to it during its lifetime.
 * 
 * <p>This subject buffers notifications and replays them to the Subscriber as requested.
 * 
 * <p>This subject holds an unbounded internal buffer.
 * 
 * <p>If more than one Subscriber attempts to subscribe to this Subject, they
 * will receive an IllegalStateException if this Subject hasn't terminated yet,
 * or the Subscribers receive the terminal event (error or completion) if this
 * Subject has terminated.
 * 
 * @param <T> the value type unicasted
 */
public final class UnicastProcessor<T> extends FlowableProcessor<T> {

    final SpscLinkedArrayQueue<T> queue;
    
    final AtomicReference<Runnable> onTerminate;
    
    volatile boolean done;
    Throwable error;
    
    final AtomicReference<Subscriber<? super T>> actual;
    
    volatile boolean cancelled;
    
    final AtomicBoolean once;

    final BasicIntQueueSubscription<T> wip;

    final AtomicLong requested;
    
    boolean enableOperatorFusion;

    public UnicastProcessor() {
        this(bufferSize());
    }

    /**
     * Creates an UnicastProcessor with the given capacity hint.
     * @param capacityHint the capacity hint for the internal, unbounded queue
     */
    public UnicastProcessor(int capacityHint) {
        this.queue = new SpscLinkedArrayQueue<T>(capacityHint);
        this.onTerminate = new AtomicReference<Runnable>();
        this.actual = new AtomicReference<Subscriber<? super T>>();
        this.once = new AtomicBoolean();
        this.wip = new UnicastQueueSubscription();
        this.requested = new AtomicLong();
    }

    /**
     * Creates an UnicastProcessor with the given capacity hint and callback
     * for when the Processor is terminated normally or its single Subscriber cancels.
     * @param capacityHint the capacity hint for the internal, unbounded queue
     * @param onTerminate the callback to run when the Processor is terminated or cancelled, null allowed
     */
    public UnicastProcessor(int capacityHint, Runnable onTerminate) {
        this.queue = new SpscLinkedArrayQueue<T>(capacityHint);
        this.onTerminate = new AtomicReference<Runnable>(ObjectHelper.requireNonNull(onTerminate, "onTerminate"));
        this.actual = new AtomicReference<Subscriber<? super T>>();
        this.once = new AtomicBoolean();
        this.wip = new UnicastQueueSubscription();
        this.requested = new AtomicLong();
    }
    
    void doTerminate() {
        Runnable r = onTerminate.get();
        if (r != null && onTerminate.compareAndSet(r, null)) {
            r.run();
        }
    }
    
    void drainRegular(Subscriber<? super T> a) {
        int missed = 1;
        
        final SpscLinkedArrayQueue<T> q = queue;
        
        for (;;) {

            long r = requested.get();
            long e = 0L;
            
            while (r != e) {
                boolean d = done;
                
                T t = q.poll();
                boolean empty = t == null;
                
                if (checkTerminated(d, empty, a, q)) {
                    return;
                }
                
                if (empty) {
                    break;
                }
                
                a.onNext(t);
                
                e++;
            }
            
            if (r == e && checkTerminated(done, q.isEmpty(), a, q)) {
                return;
            }
            
            if (e != 0 && r != Long.MAX_VALUE) {
                requested.addAndGet(-e);
            }
            
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                break;
            }
        }
    }
    
    void drainFused(Subscriber<? super T> a) {
        int missed = 1;
        
        final SpscLinkedArrayQueue<T> q = queue;
        
        for (;;) {
            
            if (cancelled) {
                q.clear();
                actual.lazySet(null);
                return;
            }
            
            boolean d = done;
            
            a.onNext(null);
            
            if (d) {
                actual.lazySet(null);
                
                Throwable ex = error;
                if (ex != null) {
                    a.onError(ex);
                } else {
                    a.onComplete();
                }
                return;
            }
            
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                break;
            }
        }
    }
    
    void drain() {
        if (wip.getAndIncrement() != 0) {
            return;
        }

        int missed = 1;
        
        for (;;) {
            Subscriber<? super T> a = actual.get();
            if (a != null) {
    
                if (enableOperatorFusion) {
                    drainFused(a);
                } else {
                    drainRegular(a);
                }
                return;
            }
            
            missed = wip.addAndGet(-missed);
            if (missed == 0) {
                break;
            }
        }
    }
    
    boolean checkTerminated(boolean d, boolean empty, Subscriber<? super T> a, SpscLinkedArrayQueue<T> q) {
        if (cancelled) {
            q.clear();
            actual.lazySet(null);
            return true;
        }
        if (d && empty) {
            Throwable e = error;
            actual.lazySet(null);
            if (e != null) {
                a.onError(e);
            } else {
                a.onComplete();
            }
            return true;
        }
        
        return false;
    }
    
    @Override
    public void onSubscribe(Subscription s) {
        if (done || cancelled) {
            s.cancel();
        } else {
            s.request(Long.MAX_VALUE);
        }
    }
    
    @Override
    public void onNext(T t) {
        if (done || cancelled) {
            return;
        }
        
        if (!queue.offer(t)) {
            onError(new IllegalStateException("The queue is full"));
            return;
        }
        drain();
    }
    
    @Override
    public void onError(Throwable t) {
        if (done || cancelled) {
            RxJavaPlugins.onError(t);
            return;
        }
        
        error = t;
        done = true;

        doTerminate();
        
        drain();
    }
    
    @Override
    public void onComplete() {
        if (done || cancelled) {
            return;
        }
        
        done = true;

        doTerminate();
        
        drain();
    }
    
    @Override
    protected void subscribeActual(Subscriber<? super T> s) {
        if (!once.get() && once.compareAndSet(false, true)) {
            
            s.onSubscribe(wip);
            actual.set(s);
            if (cancelled) {
                actual.lazySet(null);
            } else {
                drain();
            }
        } else {
            EmptySubscription.error(new IllegalStateException("This processor allows only a single Subscriber"), s);
        }
    }

    final class UnicastQueueSubscription extends BasicIntQueueSubscription<T> {

        /** */
        private static final long serialVersionUID = -4896760517184205454L;

        @Override
        public T poll() {
            return queue.poll();
        }

        @Override
        public boolean isEmpty() {
            return queue.isEmpty();
        }

        @Override
        public void clear() {
            queue.clear();
        }

        @Override
        public int requestFusion(int requestedMode) {
            if ((requestedMode & QueueSubscription.ASYNC) != 0) {
                enableOperatorFusion = true;
                return QueueSubscription.ASYNC;
            }
            return QueueSubscription.NONE;
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.add(requested, n);
                drain();
            }
        }
        
        @Override
        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;

            doTerminate();

            if (!enableOperatorFusion) {
                if (wip.getAndIncrement() == 0) {
                    queue.clear();
                    actual.lazySet(null);
                }
            }
        }
    }
    
    @Override
    public boolean hasSubscribers() {
        return actual.get() != null;
    }
    
    @Override
    public Throwable getThrowable() {
        if (done) {
            return error;
        }
        return null;
    }
    
    @Override
    public boolean hasComplete() {
        return done;
    }
    
    @Override
    public boolean hasThrowable() {
        return done && error != null;
    }
}