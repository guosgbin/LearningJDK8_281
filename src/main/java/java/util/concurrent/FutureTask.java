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
import java.util.concurrent.locks.LockSupport;

/**
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     *
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     *
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    /*
     * 初始值是 NEW，
     * 此任务的运行状态，最初为NEW。运行状态仅在方法set、setException和cancel中转换为终端状态。
     * 在完成过程中，状态可能会出现瞬态值COMPLETING（当设置结果时）或INTERRUPTING（仅当中断跑步者以满足取消（true））。
     * 从这些中间状态到最终状态的转换使用更便宜的有序/惰性写入，因为值是唯一的无法进一步修改。
     */

    // 当前 FutureTask 的状态
    private volatile int state;
    // 当前任务新建状态，还没执行
    private static final int NEW          = 0;
    // 当前任务完成临界状态，快完成了，有可能是正常结束可能是抛出异常
    private static final int COMPLETING   = 1;
    // 当前任务正常完成的状态
    private static final int NORMAL       = 2;
    // 当前任务在执行过程中发生了异常，内部封装的callable.run()向上抛出异常了
    private static final int EXCEPTIONAL  = 3;
    // 当前任务被取消
    private static final int CANCELLED    = 4;
    // 当前任务正在中断中
    private static final int INTERRUPTING = 5;
    // 当前任务已经被中断
    private static final int INTERRUPTED  = 6;

    /** The underlying callable; nulled out after running */
    // 底层的调用的Callable对象，运行后置空null
    private Callable<V> callable;
    /** The result to return or exception to throw from get() */
    // get()方法获取的值，可能是正常计算出的结果，也可能是抛出的异常
    private Object outcome; // non-volatile, protected by state reads/writes
    /** The thread running the callable; CASed during run() */
    // 正在执行Callable任务的线程
    private volatile Thread runner;
    /** Treiber stack of waiting threads */
    // 可能有多个线程去get()获取执行结果，使用栈stack来存储等待的线程
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * @param s completed state value
     */
    // 上报返回值，可能是正常的返回值，也可能是返回的异常
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        Object x = outcome;
        if (s == NORMAL)
            // 返回正常执行结束的值
            return (V)x;
        if (s >= CANCELLED)
            throw new CancellationException();
        // 抛出异常
        throw new ExecutionException((Throwable)x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * @param  callable the callable task
     * @throws NullPointerException if the callable is null
     */
    // 指定 Callable
    public FutureTask(Callable<V> callable) {
        if (callable == null)
            throw new NullPointerException();
        this.callable = callable;
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result the result to return on successful completion. If
     * you don't need a particular result, consider using
     * constructions of the form:
     * {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    // 指定 Runnable，假如执行成功返回指定值 result
    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;       // ensure visibility of callable
    }

    // 当前任务是否被取消，中断也算取消
    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    public boolean isDone() {
        return state != NEW;
    }

    /**
     * 取消任务的执行。取消操作可能是会失败的。
     * 如果任务已经结束或者已经被取消，则此处操作失败。如果任务还没有开始，那么任务就不会执行。
     *
     * @param mayInterruptIfRunning
     */
    public boolean cancel(boolean mayInterruptIfRunning) {
        // 条件 1：说明当前状态是 NEW 状态
        // 条件 2：尝试 CAS 更改状态
        if (!(state == NEW &&
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                  mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            // 状态不是NEW，或者是NEW但是CAS操作失败
            return false;
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        // 中断正在执行任务的线程
                        t.interrupt();
                } finally { // final state
                    // 中断后设置状态为 INTERRUPTED
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            // 移除并唤醒所有的等待线程
            finishCompletion();
        }
        return true;
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    // 阻塞获取结果
    public V get() throws InterruptedException, ExecutionException {
        int s = state;
        // 条件成立说明当前任务还未执行完，需要去阻塞等待
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);
        return report(s);
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    // 超时等待获取结果
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        // 调用支持超时的 awaitDone 方法
        if (s <= COMPLETING &&
            (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    // 钩子方法，子类可重写
    protected void done() { }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    protected void set(V v) {
        // 尝试 CAS 将状态从 NEW 修改为 COMPLETING
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            // 设置返回值
            outcome = v;
            // 设置 state 是 NORMAL，表示正常完成
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t) {
        // CAS 将 state 从 NEW 修改为 COMPLETING
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = t;
            // CAS 将 state 设置为 EXCEPTIONAL
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state
            finishCompletion();
        }
    }

    /**
     * 线程执行 run 方法
     */
    public void run() {
        // 首先需要判断当前的状态是否是新建状态 NEW，防止被取消或中断
        // 再尝试 CAS 将 runner 变量设置为当前线程引用，设置成功则说明没有别的线程竞争。
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return;
        try {
            Callable<V> c = callable;
            // 再次判断 state 是否是 NEW，防止其他线程修改了状态
            if (c != null && state == NEW) {
                // 返回值
                V result;
                boolean ran;
                try {
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    // 发生异常了
                    result = null;
                    ran = false;
                    setException(ex);
                }
                // 根据是否执行成功，设置返回值
                if (ran)
                    set(result);
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            // 直到 set 状态前，runner 一直都是非空的，为了防止并发调用 run() 方法。
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            // 有别的线程要中断当前线程，把CPU让出去，自旋等一下
            int s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     */
    // 移除并唤醒所有的等待线程，
    private void finishCompletion() {
        // assert state > COMPLETING;
        for (WaitNode q; (q = waiters) != null;) {
            // 尝试CAS更新队列的头结点为null
            // 可能失败，失败原因可能是被其他线程CANCEL掉了，再出去就可能外循环条件不成立并退出
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                for (;;) {
                    Thread t = q.thread;
                    if (t != null) {
                        q.thread = null;
                        // 唤醒线程
                        LockSupport.unpark(t);
                    }
                    WaitNode next = q.next;
                    if (next == null)
                        break;
                    q.next = null; // unlink to help gc
                    q = next;
                }
                break;
            }
        }

        done();

        callable = null;        // to reduce footprint
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     */
    // 创建节点入栈，阻塞当前线程
    private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
        // 计算超时的截止时间
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;
        for (;;) {
            if (Thread.interrupted()) {
                // 假如当前线程被中断了，需要移除等待的线程
                removeWaiter(q);
                throw new InterruptedException();
            }

            int s = state;
            // CASE1：条件成立说明当前任务已经执行结束或者发生异常了，被取消了等
            if (s > COMPLETING) {
                if (q != null)
                    q.thread = null; // help GC
                return s;
            }
            // CASE2：任务在完成的临界，需要让出 cpu 资源等待
            else if (s == COMPLETING) // cannot time out yet
                Thread.yield();
            // CASE3：第一次自旋，当前线程还未创建 WaitNode 对象，等待线程节点为空,则初始化新节点并关联当前线程
            else if (q == null)
                q = new WaitNode();
            // CASE4：第二次自旋，当前线程已经创建了WaitNode对象，但是还未入栈
            // 头插法
            else if (!queued)
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                     q.next = waiters, q);
            // CASE5：假如支持超时，当超时后移除等待的线程并返回 state，当未超时则阻塞当前线程
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            }
            // CASE6：直接阻塞当前线程
            else
                LockSupport.park(this);
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    /**
     * - 首先将传入的结点对象的thread属性置为null，作为判断是否是待移除结点的条件。
     * - 有两个循环，外循环主要是为了防止出现多个待删除的结点和CAS移除头节点失败。内循环主要是在遍历栈/队列。
     * - 内循环：假如当前结点的thread属性不为null，则说明不是待删除的结点。直到找到待删除的结点。
     * - 找到待删除的结点，再判断是否是头结点来进行不同的操作。
     *
     *  O -> O -> node -> O -> O 说明移除的 waiter 不是头节点
     *  node -> O -> O -> O -> O 说明移除的 waiter 是头节点
     *  下面代码没看懂的话，画图就完事了
     */
    private void removeWaiter(WaitNode node) {
        if (node != null) {
            // 将结点对象的thread属性置为null，help GC
            node.thread = null;
            retry:
            for (;;) {          // restart on removeWaiter race
                /*
                 * pred: 当前结点的上一个结点
                 * q: 每次遍历的结点，第一次是栈顶
                 * s: 每次遍历的结点的下一个结点
                 */
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    s = q.next;
                    // 判断当前结点的线程是否是null
                    // 不为null，则将pred指向q，执行下一次循环
                    // 因为在上面我们将node.thread = null操作
                    if (q.thread != null)
                        pred = q;
                    // 前置条件：q.thread == null
                    // 判断pred是否为null，说明当前结点不是头节点
                    else if (pred != null) {
                        // 将q移出去
                        pred.next = s;
                        // 如果 pred.thread==null，则继续外循环
                        // 可能的原因是pred结点可能也被其他线程调了removeWaiter方法
                        if (pred.thread == null) // check for race
                            continue retry;
                    }
                    // 前置条件 q.thread == null 且 pred == null
                    // 说明当前待移出的结点是头结点
                    // 尝试CAS直接将头结点设置为s，也就是q的下一个结点
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                          q, s))
                        // CAS操作失败，则继续外循环，继续操作
                        continue retry;
                }
                // 内循环移除成功，就break
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
