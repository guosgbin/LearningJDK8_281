/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A synchronization aid that allows a set of threads to all wait for
 * each other to reach a common barrier point.  CyclicBarriers are
 * useful in programs involving a fixed sized party of threads that
 * must occasionally wait for each other. The barrier is called
 * <em>cyclic</em> because it can be re-used after the waiting threads
 * are released.
 *
 * <p>A {@code CyclicBarrier} supports an optional {@link Runnable} command
 * that is run once per barrier point, after the last thread in the party
 * arrives, but before any threads are released.
 * This <em>barrier action</em> is useful
 * for updating shared-state before any of the parties continue.
 *
 * <p><b>Sample usage:</b> Here is an example of using a barrier in a
 * parallel decomposition design:
 *
 *  <pre> {@code
 * class Solver {
 *   final int N;
 *   final float[][] data;
 *   final CyclicBarrier barrier;
 *
 *   class Worker implements Runnable {
 *     int myRow;
 *     Worker(int row) { myRow = row; }
 *     public void run() {
 *       while (!done()) {
 *         processRow(myRow);
 *
 *         try {
 *           barrier.await();
 *         } catch (InterruptedException ex) {
 *           return;
 *         } catch (BrokenBarrierException ex) {
 *           return;
 *         }
 *       }
 *     }
 *   }
 *
 *   public Solver(float[][] matrix) {
 *     data = matrix;
 *     N = matrix.length;
 *     Runnable barrierAction =
 *       new Runnable() { public void run() { mergeRows(...); }};
 *     barrier = new CyclicBarrier(N, barrierAction);
 *
 *     List<Thread> threads = new ArrayList<Thread>(N);
 *     for (int i = 0; i < N; i++) {
 *       Thread thread = new Thread(new Worker(i));
 *       threads.add(thread);
 *       thread.start();
 *     }
 *
 *     // wait until done
 *     for (Thread thread : threads)
 *       thread.join();
 *   }
 * }}</pre>
 *
 * Here, each worker thread processes a row of the matrix then waits at the
 * barrier until all rows have been processed. When all rows are processed
 * the supplied {@link Runnable} barrier action is executed and merges the
 * rows. If the merger
 * determines that a solution has been found then {@code done()} will return
 * {@code true} and each worker will terminate.
 *
 * <p>If the barrier action does not rely on the parties being suspended when
 * it is executed, then any of the threads in the party could execute that
 * action when it is released. To facilitate this, each invocation of
 * {@link #await} returns the arrival index of that thread at the barrier.
 * You can then choose which thread should execute the barrier action, for
 * example:
 *  <pre> {@code
 * if (barrier.await() == 0) {
 *   // log the completion of this iteration
 * }}</pre>
 *
 * <p>The {@code CyclicBarrier} uses an all-or-none breakage model
 * for failed synchronization attempts: If a thread leaves a barrier
 * point prematurely because of interruption, failure, or timeout, all
 * other threads waiting at that barrier point will also leave
 * abnormally via {@link BrokenBarrierException} (or
 * {@link InterruptedException} if they too were interrupted at about
 * the same time).
 *
 * <p>Memory consistency effects: Actions in a thread prior to calling
 * {@code await()}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions that are part of the barrier action, which in turn
 * <i>happen-before</i> actions following a successful return from the
 * corresponding {@code await()} in other threads.
 *
 * @since 1.5
 * @see CountDownLatch
 *
 * @author Doug Lea
 */
public class CyclicBarrier {
    /**
     * Each use of the barrier is represented as a generation instance.
     * The generation changes whenever the barrier is tripped, or
     * is reset. There can be many generations associated with threads
     * using the barrier - due to the non-deterministic way the lock
     * may be allocated to waiting threads - but only one of these
     * can be active at a time (the one to which {@code count} applies)
     * and all the rest are either broken or tripped.
     * There need not be an active generation if there has been a break
     * but no subsequent reset.
     */
    // 屏障的每次使用都表示为一个生成实例。
    // 每当栅栏被触发或重置时，生成就会发生变化。
    // 可能有许多代与使用屏障的线程相关联
    // 由于锁定可能分配给等待线程的不确定方式
    // 但一次只能其中一个处于活动状态（ count适用的那个）并且所有其余的要么坏要么绊倒。
    // 如果有中断但没有后续重置，则不需要活动生成。
    private static class Generation {
        boolean broken = false;
    }

    /** The lock for guarding barrier entry */
    private final ReentrantLock lock = new ReentrantLock();
    /** Condition to wait on until tripped */
    private final Condition trip = lock.newCondition();
    /** The number of parties */
    // 线程数
    private final int parties;
    /* The command to run when tripped */
    // 最后一个通过的线程执行的任务，可以为 null，代表不执行
    private final Runnable barrierCommand;
    /** The current generation */
    // 代
    private Generation generation = new Generation();

    /**
     * Number of parties still waiting. Counts down from parties to 0
     * on each generation.  It is reset to parties on each new
     * generation or when broken.
     */
    // 表示当前代中还有多少个线程未到达
    private int count;

    /**
     * Updates state on barrier trip and wakes up everyone.
     * Called only while holding lock.
     */
    private void nextGeneration() {
        // signal completion of last generation
        // 唤醒所有等待的线程
        trip.signalAll();
        // set up next generation
        // 重置 count
        count = parties;
        // 开启下一代
        generation = new Generation();
    }

    /**
     * Sets current barrier generation as broken and wakes up everyone.
     * Called only while holding lock.
     */
    private void breakBarrier() {
        // 打断当前代
        generation.broken = true;
        // 重置 count
        count = parties;
        // 唤醒所有 await 的线程
        trip.signalAll();
    }

    /**
     * Main barrier code, covering the various policies.
     *
     * 阻塞当前线程直到所有的线程都在此代（屏障）上调用了 await 方法，或者等待超时了
     * 不是最后一个调用 await 的方法会在以下几种情况发生前一直阻塞：
     * 1. 最后一个线程调了 await 方法，会唤醒所有阻塞的线程；
     * 2. 外部线程中断了当前线程，当前代（屏障）会被打破，会唤醒所有阻塞的线程；
     * 3. 外部线程中断了某个阻塞中的线程，当前代（屏障）会被打破，会唤醒所有阻塞的线程；
     * 4. 支持超时的模式下，当前线程的阻塞的时间到了，则会打破当前代（屏障），会唤醒所有阻塞的线程；
     * 5. 支持超时的模式下，别的线程的阻塞的时间到了，则会打破当前代（屏障），会唤醒所有阻塞的线程；
     * 6. 外部线程调用了 CyclicBarrier#reset 方法打破了当前代（屏障），会唤醒所有阻塞的线程；
     *
     * @param timed 是否支持超时
     * @param nanos 超时时间
     */
    private int dowait(boolean timed, long nanos)
        throws InterruptedException, BrokenBarrierException,
               TimeoutException {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            // 创建一个"代"
            final Generation g = generation;

            if (g.broken)
                // 如果当前代是被打破状态，直接抛出异常
                throw new BrokenBarrierException();

            if (Thread.interrupted()) {
                // 如果当前线程被中断，则打断当前代
                breakBarrier();
                throw new InterruptedException();
            }

            // index 的作用是，找到最后一个 await 的线程来调用 barrierCommand 任务
            int index = --count;
            if (index == 0) {  // tripped
                // 标记执行任务是否是正常执行结束，false 表示有问题
                boolean ranAction = false;
                try {
                    final Runnable command = barrierCommand;
                    if (command != null)
                        // 执行任务
                        command.run();
                    ranAction = true;
                    // 当前代结束，开启下一代
                    nextGeneration();
                    return 0;
                } finally {
                    if (!ranAction)
                        // 执行任务发生异常，打断当前代
                        breakBarrier();
                }
            }

            // 走到这里说明当前线程不是最后一个进入的线程

            // loop until tripped, broken, interrupted, or timed out
            // 自旋，直到条件满足，当前代被打破，当前线程被中断，或者超时
            for (;;) {
                try {
                    // timed false 表示不带超时的条件，表示一直阻塞
                    if (!timed)
                        // 将当前线程封装成节点加入到 condition 队列中等待被唤醒
                        trip.await();
                    else if (nanos > 0L)
                        // 带超时的 await
                        nanos = trip.awaitNanos(nanos);
                } catch (InterruptedException ie) {
                    // 走到到这里面说明被中断了，
                    // condition 的 await 方法抛出异常的地方是在 condition 队列中被中断，才会抛出中断异常
                    if (g == generation && ! g.broken) {
                        // 说明当前代没有被打破，需要打破当前代，并唤醒所有等待的线程
                        breakBarrier();
                        // 继续向外抛异常
                        throw ie;
                    } else {
                        // We're about to finish waiting even if we had not
                        // been interrupted, so this interrupt is deemed to
                        // "belong" to subsequent execution.
                        // 整个中断标记
                        Thread.currentThread().interrupt();
                    }
                    // 到这里当前代肯定是被打破了的
                }

                // 1.正常情况，当前 barrier 已经开启了新的一代
                // 2.当前代被打断了
                // 3.当前代超时了

                if (g.broken)
                    // 说明当前代被打破了，抛个异常
                    throw new BrokenBarrierException();

                if (g != generation)
                    // 说明当前代正常结束了，直接返回
                    // 正常结束时在最后一个线程里面调用了 CyclicBarrier.nextGeneration 方法开启下一代
                    return index;

                if (timed && nanos <= 0L) {
                    // 说明当前代超时了，需要打破当前代
                    breakBarrier();
                    throw new TimeoutException();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates a new {@code CyclicBarrier} that will trip when the
     * given number of parties (threads) are waiting upon it, and which
     * will execute the given barrier action when the barrier is tripped,
     * performed by the last thread entering the barrier.
     *
     * @param parties the number of threads that must invoke {@link #await}
     *        before the barrier is tripped
     * @param barrierAction the command to execute when the barrier is
     *        tripped, or {@code null} if there is no action
     * @throws IllegalArgumentException if {@code parties} is less than 1
     */
    public CyclicBarrier(int parties, Runnable barrierAction) {
        if (parties <= 0) throw new IllegalArgumentException();
        this.parties = parties;
        this.count = parties;
        this.barrierCommand = barrierAction;
    }

    /**
     * Creates a new {@code CyclicBarrier} that will trip when the
     * given number of parties (threads) are waiting upon it, and
     * does not perform a predefined action when the barrier is tripped.
     *
     * @param parties the number of threads that must invoke {@link #await}
     *        before the barrier is tripped
     * @throws IllegalArgumentException if {@code parties} is less than 1
     */
    public CyclicBarrier(int parties) {
        this(parties, null);
    }

    /**
     * Returns the number of parties required to trip this barrier.
     *
     * @return the number of parties required to trip this barrier
     */
    public int getParties() {
        return parties;
    }

    /**
     * Waits until all {@linkplain #getParties parties} have invoked
     * {@code await} on this barrier.
     *
     * <p>If the current thread is not the last to arrive then it is
     * disabled for thread scheduling purposes and lies dormant until
     * one of the following things happens:
     * <ul>
     * <li>The last thread arrives; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * one of the other waiting threads; or
     * <li>Some other thread times out while waiting for barrier; or
     * <li>Some other thread invokes {@link #reset} on this barrier.
     * </ul>
     *
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * <p>If the barrier is {@link #reset} while any thread is waiting,
     * or if the barrier {@linkplain #isBroken is broken} when
     * {@code await} is invoked, or while any thread is waiting, then
     * {@link BrokenBarrierException} is thrown.
     *
     * <p>If any thread is {@linkplain Thread#interrupt interrupted} while waiting,
     * then all other waiting threads will throw
     * {@link BrokenBarrierException} and the barrier is placed in the broken
     * state.
     *
     * <p>If the current thread is the last thread to arrive, and a
     * non-null barrier action was supplied in the constructor, then the
     * current thread runs the action before allowing the other threads to
     * continue.
     * If an exception occurs during the barrier action then that exception
     * will be propagated in the current thread and the barrier is placed in
     * the broken state.
     *
     * @return the arrival index of the current thread, where index
     *         {@code getParties() - 1} indicates the first
     *         to arrive and zero indicates the last to arrive
     * @throws InterruptedException if the current thread was interrupted
     *         while waiting
     * @throws BrokenBarrierException if <em>another</em> thread was
     *         interrupted or timed out while the current thread was
     *         waiting, or the barrier was reset, or the barrier was
     *         broken when {@code await} was called, or the barrier
     *         action (if present) failed due to an exception
     */
    /*
     * 等到所有各方都在此屏障上调用了await 。
     * 如果当前线程不是最后到达的，则出于线程调度目的将其禁用并处于休眠状态，直到发生以下情况之一：
     * 1. 最后一个线程到达；
     * 2. 其他一些线程中断当前线程；
     * 3. 其他一些线程中断了其他等待线程之一；
     * 4. 其他一些线程在等待屏障时超时；
     * 5. 其他一些线程在此屏障上调用reset；
     *
     * 如果当前线程：
     * 在进入此方法时设置其中断状态或者等待时被打断，然后抛出InterruptedException并清除当前线程的中断状态。
     *
     * 如果在任何线程等待时屏障被reset ，或者在调用await时屏障被破坏，或者在任何线程正在等待时，则抛出BrokenBarrierException 。
     * 如果任何线程在等待时被中断，那么所有其他等待的线程都会抛出BrokenBarrierException并且屏障处于损坏状态。
     * 如果当前线程是最后到达的线程，并且在构造函数中提供了非空屏障操作，则当前线程在允许其他线程继续之前运行该操作。如果在屏障操作期间发生异常，则该异常将在当前线程中传播，并且屏障处于损坏状态。
     */
    public int await() throws InterruptedException, BrokenBarrierException {
        try {
            return dowait(false, 0L);
        } catch (TimeoutException toe) {
            throw new Error(toe); // cannot happen
        }
    }

    /**
     * Waits until all {@linkplain #getParties parties} have invoked
     * {@code await} on this barrier, or the specified waiting time elapses.
     *
     * <p>If the current thread is not the last to arrive then it is
     * disabled for thread scheduling purposes and lies dormant until
     * one of the following things happens:
     * <ul>
     * <li>The last thread arrives; or
     * <li>The specified timeout elapses; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * one of the other waiting threads; or
     * <li>Some other thread times out while waiting for barrier; or
     * <li>Some other thread invokes {@link #reset} on this barrier.
     * </ul>
     *
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * <p>If the specified waiting time elapses then {@link TimeoutException}
     * is thrown. If the time is less than or equal to zero, the
     * method will not wait at all.
     *
     * <p>If the barrier is {@link #reset} while any thread is waiting,
     * or if the barrier {@linkplain #isBroken is broken} when
     * {@code await} is invoked, or while any thread is waiting, then
     * {@link BrokenBarrierException} is thrown.
     *
     * <p>If any thread is {@linkplain Thread#interrupt interrupted} while
     * waiting, then all other waiting threads will throw {@link
     * BrokenBarrierException} and the barrier is placed in the broken
     * state.
     *
     * <p>If the current thread is the last thread to arrive, and a
     * non-null barrier action was supplied in the constructor, then the
     * current thread runs the action before allowing the other threads to
     * continue.
     * If an exception occurs during the barrier action then that exception
     * will be propagated in the current thread and the barrier is placed in
     * the broken state.
     *
     * @param timeout the time to wait for the barrier
     * @param unit the time unit of the timeout parameter
     * @return the arrival index of the current thread, where index
     *         {@code getParties() - 1} indicates the first
     *         to arrive and zero indicates the last to arrive
     * @throws InterruptedException if the current thread was interrupted
     *         while waiting
     * @throws TimeoutException if the specified timeout elapses.
     *         In this case the barrier will be broken.
     * @throws BrokenBarrierException if <em>another</em> thread was
     *         interrupted or timed out while the current thread was
     *         waiting, or the barrier was reset, or the barrier was broken
     *         when {@code await} was called, or the barrier action (if
     *         present) failed due to an exception
     */
    public int await(long timeout, TimeUnit unit)
        throws InterruptedException,
               BrokenBarrierException,
               TimeoutException {
        return dowait(true, unit.toNanos(timeout));
    }

    /**
     * Queries if this barrier is in a broken state.
     *
     * @return {@code true} if one or more parties broke out of this
     *         barrier due to interruption or timeout since
     *         construction or the last reset, or a barrier action
     *         failed due to an exception; {@code false} otherwise.
     */
    public boolean isBroken() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return generation.broken;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resets the barrier to its initial state.  If any parties are
     * currently waiting at the barrier, they will return with a
     * {@link BrokenBarrierException}. Note that resets <em>after</em>
     * a breakage has occurred for other reasons can be complicated to
     * carry out; threads need to re-synchronize in some other way,
     * and choose one to perform the reset.  It may be preferable to
     * instead create a new barrier for subsequent use.
     */
    public void reset() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            breakBarrier();   // break the current generation
            nextGeneration(); // start a new generation
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the number of parties currently waiting at the barrier.
     * This method is primarily useful for debugging and assertions.
     *
     * @return the number of parties currently blocked in {@link #await}
     */
    public int getNumberWaiting() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return parties - count;
        } finally {
            lock.unlock();
        }
    }
}
