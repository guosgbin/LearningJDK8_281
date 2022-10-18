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
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;

/**
 * A {@link ThreadPoolExecutor} that can additionally schedule
 * commands to run after a given delay, or to execute
 * periodically. This class is preferable to {@link java.util.Timer}
 * when multiple worker threads are needed, or when the additional
 * flexibility or capabilities of {@link ThreadPoolExecutor} (which
 * this class extends) are required.
 *
 * <p>Delayed tasks execute no sooner than they are enabled, but
 * without any real-time guarantees about when, after they are
 * enabled, they will commence. Tasks scheduled for exactly the same
 * execution time are enabled in first-in-first-out (FIFO) order of
 * submission.
 *
 * <p>When a submitted task is cancelled before it is run, execution
 * is suppressed. By default, such a cancelled task is not
 * automatically removed from the work queue until its delay
 * elapses. While this enables further inspection and monitoring, it
 * may also cause unbounded retention of cancelled tasks. To avoid
 * this, set {@link #setRemoveOnCancelPolicy} to {@code true}, which
 * causes tasks to be immediately removed from the work queue at
 * time of cancellation.
 *
 * <p>Successive executions of a task scheduled via
 * {@code scheduleAtFixedRate} or
 * {@code scheduleWithFixedDelay} do not overlap. While different
 * executions may be performed by different threads, the effects of
 * prior executions <a
 * href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * those of subsequent ones.
 *
 * <p>While this class inherits from {@link ThreadPoolExecutor}, a few
 * of the inherited tuning methods are not useful for it. In
 * particular, because it acts as a fixed-sized pool using
 * {@code corePoolSize} threads and an unbounded queue, adjustments
 * to {@code maximumPoolSize} have no useful effect. Additionally, it
 * is almost never a good idea to set {@code corePoolSize} to zero or
 * use {@code allowCoreThreadTimeOut} because this may leave the pool
 * without threads to handle tasks once they become eligible to run.
 *
 * <p><b>Extension notes:</b> This class overrides the
 * {@link ThreadPoolExecutor#execute(Runnable) execute} and
 * {@link AbstractExecutorService#submit(Runnable) submit}
 * methods to generate internal {@link ScheduledFuture} objects to
 * control per-task delays and scheduling.  To preserve
 * functionality, any further overrides of these methods in
 * subclasses must invoke superclass versions, which effectively
 * disables additional task customization.  However, this class
 * provides alternative protected extension method
 * {@code decorateTask} (one version each for {@code Runnable} and
 * {@code Callable}) that can be used to customize the concrete task
 * types used to execute commands entered via {@code execute},
 * {@code submit}, {@code schedule}, {@code scheduleAtFixedRate},
 * and {@code scheduleWithFixedDelay}.  By default, a
 * {@code ScheduledThreadPoolExecutor} uses a task type extending
 * {@link FutureTask}. However, this may be modified or replaced using
 * subclasses of the form:
 *
 *  <pre> {@code
 * public class CustomScheduledExecutor extends ScheduledThreadPoolExecutor {
 *
 *   static class CustomTask<V> implements RunnableScheduledFuture<V> { ... }
 *
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Runnable r, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(r, task);
 *   }
 *
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Callable<V> c, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(c, task);
 *   }
 *   // ... add constructors, etc.
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */

/*
 * {@link ThreadPoolExecutor}，它可以额外安排命令在给定延迟后运行或定期执行。
 * 当需要多个工作线程时，或者当需要{@link ThreadPoolExecutor}（该类扩展了它）的额外灵活性或功能时，
 * 该类比{@link-java.util.Timer}更好。
 *
 * 延迟任务在启用后立即执行，但无法实时保证启用后何时开始。
 * 按提交的先进先出（FIFO）顺序启用计划执行时间完全相同的任务。
 *
 * 当提交的任务在运行前被取消时，将禁止执行。
 * 默认情况下，这样一个被取消的任务在延迟结束之前不会自动从工作队列中删除。
 * 虽然这样可以进行进一步的检查和监控，但也可能导致取消的任务无限期保留。
 * 为了避免这种情况，请将{@link#setRemoveOnCancelPolicy}设置为{@code-true}，这会导致任务在取消时立即从工作队列中删除。
 *
 * 通过{@code scheduleAtFixedRate}或{@code cheduleWithFixedDelay}计划的任务的连续执行不重叠。
 * 虽然不同的线程可以执行不同的执行，但先前执行<a href=“package summary.html#MemoryVisibility”><i>的影响先于后续执行</i></a>。
 *
 * 虽然这个类继承自{@link ThreadPoolExecutor}，但一些继承的优化方法对它没有用处。
 * 特别是，因为它使用{@code-corePoolSize}线程和无限队列充当固定大小的池，所以对{@code maximumPoolSize}的调整没有任何效果。
 * 此外，将{@code-corePoolSize}设置为零或使用{@code allowCoreThreadTimeOut}几乎从来都不是一个好主意，
 * 因为这可能会使池中没有线程来处理符合运行条件的任务。
 *
 * <p><b>扩展说明：
 * </b>此类重写{@link ThreadPoolExecutor#execute（Runnable）execute}
 * 和{@link-AbstractExecutor Service#submit（Runnable）submit}方法，
 * 以生成内部{@link-ScheduledFuture}对象，以控制每个任务的延迟和调度。
 * 为了保持功能，子类中这些方法的任何进一步重写都必须调用超类版本，这实际上禁用了额外的任务定制。
 * 然而，这个类提供了替代的受保护扩展方法{@code decorateTask}（{@code Runnable}和{@code Callable}各有一个版本），
 * 可以用来自定义用于执行通过{@code execute}输入的命令的具体任务类型，{@code提交}、{@code计划}、}
 * @code计划AtFixedRate}和{@code安排WithFixedDelay}。
 * 默认情况下，{@code ScheduledThreadPoolExecutor}使用扩展{@link FutureTask}的任务类型。
 * 但是，可以使用以下形式的子类对其进行修改或替换：
 *
 *
 * public class CustomScheduledExecutor extends ScheduledThreadPoolExecutor {
 *   static class CustomTask<V> implements RunnableScheduledFuture<V> { ... }
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Runnable r, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(r, task);
 *   }
 *   protected <V> RunnableScheduledFuture<V> decorateTask(
 *                Callable<V> c, RunnableScheduledFuture<V> task) {
 *       return new CustomTask<V>(c, task);
 *   }
 *   // ... add constructors, etc.
 * }
 */
public class ScheduledThreadPoolExecutor
        extends ThreadPoolExecutor
        implements ScheduledExecutorService {

    /*
     * This class specializes ThreadPoolExecutor implementation by
     *
     * 1. Using a custom task type, ScheduledFutureTask for
     *    tasks, even those that don't require scheduling (i.e.,
     *    those submitted using ExecutorService execute, not
     *    ScheduledExecutorService methods) which are treated as
     *    delayed tasks with a delay of zero.
     *
     * 2. Using a custom queue (DelayedWorkQueue), a variant of
     *    unbounded DelayQueue. The lack of capacity constraint and
     *    the fact that corePoolSize and maximumPoolSize are
     *    effectively identical simplifies some execution mechanics
     *    (see delayedExecute) compared to ThreadPoolExecutor.
     *
     * 3. Supporting optional run-after-shutdown parameters, which
     *    leads to overrides of shutdown methods to remove and cancel
     *    tasks that should NOT be run after shutdown, as well as
     *    different recheck logic when task (re)submission overlaps
     *    with a shutdown.
     *
     * 4. Task decoration methods to allow interception and
     *    instrumentation, which are needed because subclasses cannot
     *    otherwise override submit methods to get this effect. These
     *    don't have any impact on pool control logic though.
     */
    /*
     * 1.使用自定义的任务类型 ScheduledFutureTask，甚至那些不需要调度的任务
     * （即那些使用ExecutorService执行而不是ScheduledExecutor服务方法提交的任务）也被视为延迟为零的延迟任务。
     * 2.使用自定义的阻塞队列 DelayedWorkQueue，它是一种无界队列。
     *  与ThreadPoolExecutor相比，缺少容量限制以及corePoolSize和maximumPoolSize 实际上是相同的这一事实简化了一些执行机制（请参阅delayedExecute）。
     * 3.支持可选的关机后运行参数，这将导致覆盖关机方法以删除和取消关机后不应运行的任务，
     *  以及任务（重新）提交与关机重叠时的不同复查逻辑。
     * 4.任务修饰方法允许拦截和检测，这是必需的，
     *  因为子类不能通过其他方式重写提交方法来获得这种效果。但这些对池控制逻辑没有任何影响。
     */

    /**
     * False if should cancel/suppress periodic tasks on shutdown.
     */
    // false 表示应在 shutdown 时取消/禁止定周期任务
    private volatile boolean continueExistingPeriodicTasksAfterShutdown;

    /**
     * False if should cancel non-periodic tasks on shutdown.
     */
    // false 表示应在 shutdown 时取消延迟任务。
    private volatile boolean executeExistingDelayedTasksAfterShutdown = true;

    /**
     * True if ScheduledFutureTask.cancel should remove from queue
     */
    // true 表示在 ScheduledFutureTask.cancel 后需要从队列中移除
    private volatile boolean removeOnCancel = false;

    /**
     * Sequence number to break scheduling ties, and in turn to
     * guarantee FIFO order among tied entries.
     */
    private static final AtomicLong sequencer = new AtomicLong();

    /**
     * Returns current nanosecond time.
     */
    final long now() {
        return System.nanoTime();
    }

    /**
     * 任务对象
     */
    private class ScheduledFutureTask<V>
            extends FutureTask<V> implements RunnableScheduledFuture<V> {

        /** Sequence number to break ties FIFO */
        // 任务的序列号
        private final long sequenceNumber;

        /** The time the task is enabled to execute in nanoTime units */
        // 任务的执行时间，相对时间
        private long time;

        /**
         * Period in nanoseconds for repeating tasks.  A positive
         * value indicates fixed-rate execution.  A negative value
         * indicates fixed-delay execution.  A value of 0 indicates a
         * non-repeating task.
         */
        /*
         * 任务的执行周期
         * > 0 表示固定的周期任务，scheduleAtFixedRate 提交的，不考虑任务的执行时间
         * < 0 表示固定的周期任务，scheduleWithFixedDelay 提交的，考虑任务的执行时间
         * = 0 表示不重复的任务
         */
        private final long period;

        /** The actual task to be re-enqueued by reExecutePeriodic */
        // 周期性任务需要重新排队（reExecutePeriodic 方法）
        RunnableScheduledFuture<V> outerTask = this;

        /**
         * Index into delay queue, to support faster cancellation.
         */
        // 索引到延迟队列，以支持更快的取消
        int heapIndex;

        /**
         * Creates a one-shot action with given nanoTime-based trigger time.
         */
        // 使用给定的基于 nanoTime 的触发时间创建一次性操作。
        ScheduledFutureTask(Runnable r, V result, long ns) {
            super(r, result);
            this.time = ns;
            this.period = 0;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        /**
         * Creates a periodic action with given nano time and period.
         */
        // 创建一个周期任务
        ScheduledFutureTask(Runnable r, V result, long ns, long period) {
            super(r, result);
            this.time = ns;
            this.period = period;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        /**
         * Creates a one-shot action with given nanoTime-based trigger time.
         */
        // 使用给定的基于 nanoTime 的触发时间创建一次性操作。
        ScheduledFutureTask(Callable<V> callable, long ns) {
            super(callable);
            this.time = ns;
            this.period = 0;
            this.sequenceNumber = sequencer.getAndIncrement();
        }

        // 获取延迟时间
        public long getDelay(TimeUnit unit) {
            return unit.convert(time - now(), NANOSECONDS);
        }

        /*
         * 因为实现了 Comparable 接口，需要重写
         * a negative integer, zero, or a positive integer as this object is
         * less than, equal to, or greater than the specified object.
         */
        public int compareTo(Delayed other) {
            if (other == this) // compare zero if same object
                return 0;
            if (other instanceof ScheduledFutureTask) {
                ScheduledFutureTask<?> x = (ScheduledFutureTask<?>)other;
                long diff = time - x.time;
                if (diff < 0)
                    // other 的 time 要大
                    return -1;
                else if (diff > 0)
                    // this 的 time 要大
                    return 1;
                // 走到这里说明 两个的 time 一样大，需要用序列号来判断大小
                // 序列号大的 表示更大
                else if (sequenceNumber < x.sequenceNumber)
                    return -1;
                else
                    return 1;
            }
            // 不是 ScheduledFutureTask 类型，直接比较 time 大小
            long diff = getDelay(NANOSECONDS) - other.getDelay(NANOSECONDS);
            return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
        }

        /**
         * Returns {@code true} if this is a periodic (not a one-shot) action.
         *
         * @return {@code true} if periodic
         */
        // 返回 true 表示是周期性任务
        public boolean isPeriodic() {
            return period != 0;
        }

        /**
         * Sets the next time to run for a periodic task.
         */
        // 设置周期任务的下次执行时间
        // period > 0 固定频率执行，下次触发时间是直接加 period
        // period < 0 固定延迟执行，下次触发时间是在 now() 的基础上加 period，这说明考虑了任务的执行时间
        private void setNextRunTime() {
            long p = period;
            if (p > 0)
                time += p;
            else
                time = triggerTime(-p);
        }

        /*
         * 取消任务
         *
         * 如果调用父类的 cancel 方法移除成功
         */
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            // todo-kwok
            if (cancelled && removeOnCancel && heapIndex >= 0)
                remove(this);
            return cancelled;
        }

        /**
         * Overrides FutureTask version so as to reset/requeue if periodic.
         */
        // 覆盖FutureTask版本，以便定期重置/重新排队。
        public void run() {
            // 是否是周期任务
            boolean periodic = isPeriodic();
            // 校验是否可执行任务，如果不能执行，则取消任务
            if (!canRunInCurrentRunState(periodic))
                cancel(false);
            // 假如不是周期任务直接调用父类的 run 方法
            else if (!periodic)
                ScheduledFutureTask.super.run();
            // 走到这里说明是周期任务，调用 FutureTask 的 runAndReset 方法执行任务
            // 当任务执行成功，则返回 true
            else if (ScheduledFutureTask.super.runAndReset()) {
                // 设置下一次任务的执行时间
                setNextRunTime();
                // 将任务重新入队
                reExecutePeriodic(outerTask);
            }
        }
    }

    /**
     * Returns true if can run a task given current run state
     * and run-after-shutdown parameters.
     *
     * @param periodic true if this task periodic, false if delayed
     */
    // true - 根据当前线程池的状态，和 run-after-shutdown 参数决定当前是否允许运行任务
    // periodic true-周期任务 false-延迟任务
    boolean canRunInCurrentRunState(boolean periodic) {
        // 根据是否是周期任务传入不同的入参
        return isRunningOrShutdown(periodic ?
                                   continueExistingPeriodicTasksAfterShutdown :
                                   executeExistingDelayedTasksAfterShutdown);
    }

    /**
     * Main execution method for delayed or periodic tasks.  If pool
     * is shut down, rejects the task. Otherwise adds task to queue
     * and starts a thread, if necessary, to run it.  (We cannot
     * prestart the thread to run the task because the task (probably)
     * shouldn't be run yet.)  If the pool is shut down while the task
     * is being added, cancel and remove it if required by state and
     * run-after-shutdown parameters.
     *
     * @param task the task
     */
    // 延迟和周期任务。
    // 如果线程池关闭了，拒绝任务
    // 否则，添加任务到阻塞队列，然后开启一个新线程
    // 我们不能事先启动这个线程去执行任务，因为任务可能还不能执行
    // 如果线程池在提交任务后关闭了，根据参数 run-after-shutdown 决定是否取消并移除这个任务
    private void delayedExecute(RunnableScheduledFuture<?> task) {
        if (isShutdown())
            // shutdown 状态拒绝任务
            reject(task);
        else {
            // 添加任务到阻塞队列
            super.getQueue().add(task);
            // 线程池状态是 shutdown，且当前任务是周期任务
            // 参数控制不允许在 shutdown 执行任务时，且阻塞队列移除任务成功
            if (isShutdown() &&
                !canRunInCurrentRunState(task.isPeriodic()) &&
                remove(task))
                // 取消任务
                task.cancel(false);
            else
                // 确保线程足够
                ensurePrestart();
        }
    }

    /**
     * Requeues a periodic task unless current run state precludes it.
     * Same idea as delayedExecute except drops task rather than rejecting.
     *
     * @param task the task
     */
    // 请求定期任务，除非当前运行状态排除它。
    // 与delayedExecute的想法相同，只是放弃了任务而不是拒绝。
    void reExecutePeriodic(RunnableScheduledFuture<?> task) {
        if (canRunInCurrentRunState(true)) {
            // 将任务再次加入到任务队列
            super.getQueue().add(task);
            // 将任务加到任务队列之后，再次判断线程池的状态是否可以执行任务， 加入不允许执行了，需要将任务从队列移除
            if (!canRunInCurrentRunState(true) && remove(task))
                // 将任务从队列移除之后，需要取消当前任务
                task.cancel(false);
            else
                // 确保线程池中至少有一个线程处理任务
                ensurePrestart();
        }
    }

    /**
     * Cancels and clears the queue of all tasks that should not be run
     * due to shutdown policy.  Invoked within super.shutdown.
     */
    @Override void onShutdown() {
        BlockingQueue<Runnable> q = super.getQueue();
        boolean keepDelayed =
            getExecuteExistingDelayedTasksAfterShutdownPolicy();
        boolean keepPeriodic =
            getContinueExistingPeriodicTasksAfterShutdownPolicy();
        if (!keepDelayed && !keepPeriodic) {
            for (Object e : q.toArray())
                if (e instanceof RunnableScheduledFuture<?>)
                    ((RunnableScheduledFuture<?>) e).cancel(false);
            q.clear();
        }
        else {
            // Traverse snapshot to avoid iterator exceptions
            for (Object e : q.toArray()) {
                if (e instanceof RunnableScheduledFuture) {
                    RunnableScheduledFuture<?> t =
                        (RunnableScheduledFuture<?>)e;
                    if ((t.isPeriodic() ? !keepPeriodic : !keepDelayed) ||
                        t.isCancelled()) { // also remove if already cancelled
                        if (q.remove(t))
                            t.cancel(false);
                    }
                }
            }
        }
        tryTerminate();
    }

    /**
     * Modifies or replaces the task used to execute a runnable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * @param runnable the submitted Runnable
     * @param task the task created to execute the runnable
     * @param <V> the type of the task's result
     * @return a task that can execute the runnable
     * @since 1.6
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(
        Runnable runnable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * Modifies or replaces the task used to execute a callable.
     * This method can be used to override the concrete
     * class used for managing internal tasks.
     * The default implementation simply returns the given task.
     *
     * @param callable the submitted Callable
     * @param task the task created to execute the callable
     * @param <V> the type of the task's result
     * @return a task that can execute the callable
     * @since 1.6
     */
    /*
     * 修改或替换用于执行可调用的任务。
     * 此方法可用于重写用于管理内部任务的具体类。
     *
     * 默认实现只返回给定的任务。
     */
    protected <V> RunnableScheduledFuture<V> decorateTask(
        Callable<V> callable, RunnableScheduledFuture<V> task) {
        return task;
    }

    /**
     * Creates a new {@code ScheduledThreadPoolExecutor} with the
     * given core pool size.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     */
    // 这几个构造方法的 maximumPoolSize 都是 Integer.MAX_VALUE
    // 使用的都是无界队列 DelayedWorkQueue
    public ScheduledThreadPoolExecutor(int corePoolSize) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue());
    }

    /**
     * Creates a new {@code ScheduledThreadPoolExecutor} with the
     * given initial parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException if {@code threadFactory} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       ThreadFactory threadFactory) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue(), threadFactory);
    }

    /**
     * Creates a new ScheduledThreadPoolExecutor with the given
     * initial parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException if {@code handler} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue(), handler);
    }

    /**
     * Creates a new ScheduledThreadPoolExecutor with the given
     * initial parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @throws NullPointerException if {@code threadFactory} or
     *         {@code handler} is null
     */
    public ScheduledThreadPoolExecutor(int corePoolSize,
                                       ThreadFactory threadFactory,
                                       RejectedExecutionHandler handler) {
        super(corePoolSize, Integer.MAX_VALUE, 0, NANOSECONDS,
              new DelayedWorkQueue(), threadFactory, handler);
    }

    /**
     * Returns the trigger time of a delayed action.
     */
    // 获取下一次执行任务的具体是时间
    private long triggerTime(long delay, TimeUnit unit) {
        return triggerTime(unit.toNanos((delay < 0) ? 0 : delay));
    }

    /**
     * Returns the trigger time of a delayed action.
     */
    // 获取下一次执行任务的具体是时间，需要注意 overflowFree 处理溢出的情况
    long triggerTime(long delay) {
        return now() +
            ((delay < (Long.MAX_VALUE >> 1)) ? delay : overflowFree(delay));
    }

    /**
     * Constrains the values of all delays in the queue to be within
     * Long.MAX_VALUE of each other, to avoid overflow in compareTo.
     * This may occur if a task is eligible to be dequeued, but has
     * not yet been, while some other task is added with a delay of
     * Long.MAX_VALUE.
     */
    /*
     * 将队列中所有延迟的值彼此限制在 Long.MAX_VALUE 范围内，以避免在 compareTo 中溢出。
     * 如果某个任务有资格出列，但尚未出列，而添加其他任务时延迟为 Long.MAX_VALUE，则可能会发生这种情况。
     *
     * 本质上就是为了限制队列中的所有节点的延迟时间在 Long.MAX_VALUE 之内，防止 ScheduledFutureTask 类中的 compareTo 方法溢出
     */
    private long overflowFree(long delay) {
        // 获取队列中的节点
        Delayed head = (Delayed) super.getQueue().peek();
        if (head != null) {
            // 从队列节点中获取延迟时间
            long headDelay = head.getDelay(NANOSECONDS);
            // 如果从队列获取的延迟时间小于 0，并且传递的 delay 值减去队列节点中获取延迟时间小于0
            if (headDelay < 0 && (delay - headDelay < 0))
                delay = Long.MAX_VALUE + headDelay;
        }
        return delay;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    // 延迟任务，在给定延迟后执行一次任务
    public ScheduledFuture<?> schedule(Runnable command,
                                       long delay,
                                       TimeUnit unit) {
        if (command == null || unit == null)
            throw new NullPointerException();
        // 封装任务对象
        RunnableScheduledFuture<?> t = decorateTask(command,
            new ScheduledFutureTask<Void>(command, null,
                                          triggerTime(delay, unit)));
        // 执行延迟任务
        delayedExecute(t);
        return t;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    // 延迟任务，在给定延迟后执行一次任务
    public <V> ScheduledFuture<V> schedule(Callable<V> callable,
                                           long delay,
                                           TimeUnit unit) {
        if (callable == null || unit == null)
            throw new NullPointerException();
        RunnableScheduledFuture<V> t = decorateTask(callable,
            new ScheduledFutureTask<V>(callable,
                                       triggerTime(delay, unit)));
        // 执行延迟任务
        delayedExecute(t);
        return t;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     * @throws IllegalArgumentException   {@inheritDoc}
     */
    // 周期任务，在给定延迟后执行周期任务，不考虑任务的执行时间
    // eg. 周期任务 10min 一次， 在 0:00 执行，下次执行在 0:10
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                  long initialDelay,
                                                  long period,
                                                  TimeUnit unit) {
        if (command == null || unit == null)
            throw new NullPointerException();
        if (period <= 0)
            throw new IllegalArgumentException();
        // 将 Runnable 对象封装成 ScheduledFutureTask 任务
        ScheduledFutureTask<Void> sft =
            new ScheduledFutureTask<Void>(command,
                                          null,
                                          triggerTime(initialDelay, unit),
                                          unit.toNanos(period));
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        sft.outerTask = t;
        // 执行延迟任务
        delayedExecute(t);
        return t;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     * @throws IllegalArgumentException   {@inheritDoc}
     */
    // 周期任务，在给定延迟后执行周期任务，考虑任务的执行时间
    // 此时设置的执行周期规则是：下一次任务的执行时间是上一次任务完成的时间加上 delay 时长，
    // 也就是说，具体的执行时间不是固定的，但是执行的周期是固定的
    // 注意：传入的 delay 参数是负数
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                     long initialDelay,
                                                     long delay,
                                                     TimeUnit unit) {
        if (command == null || unit == null)
            throw new NullPointerException();
        if (delay <= 0)
            throw new IllegalArgumentException();
        // 将 Runnable 对象封装成 ScheduledFutureTask
        ScheduledFutureTask<Void> sft =
            new ScheduledFutureTask<Void>(command,
                                          null,
                                          triggerTime(initialDelay, unit),
                                          unit.toNanos(-delay));
        RunnableScheduledFuture<Void> t = decorateTask(command, sft);
        sft.outerTask = t;
        // 执行延迟任务
        delayedExecute(t);
        return t;
    }

    /**
     * Executes {@code command} with zero required delay.
     * This has effect equivalent to
     * {@link #schedule(Runnable,long,TimeUnit) schedule(command, 0, anyUnit)}.
     * Note that inspections of the queue and of the list returned by
     * {@code shutdownNow} will access the zero-delayed
     * {@link ScheduledFuture}, not the {@code command} itself.
     *
     * <p>A consequence of the use of {@code ScheduledFuture} objects is
     * that {@link ThreadPoolExecutor#afterExecute afterExecute} is always
     * called with a null second {@code Throwable} argument, even if the
     * {@code command} terminated abruptly.  Instead, the {@code Throwable}
     * thrown by such a task can be obtained via {@link Future#get}.
     *
     * @throws RejectedExecutionException at discretion of
     *         {@code RejectedExecutionHandler}, if the task
     *         cannot be accepted for execution because the
     *         executor has been shut down
     * @throws NullPointerException {@inheritDoc}
     */
    public void execute(Runnable command) {
        schedule(command, 0, NANOSECONDS);
    }

    // Override AbstractExecutorService methods

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public Future<?> submit(Runnable task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Runnable task, T result) {
        return schedule(Executors.callable(task, result), 0, NANOSECONDS);
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    public <T> Future<T> submit(Callable<T> task) {
        return schedule(task, 0, NANOSECONDS);
    }

    /**
     * Sets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow} or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @param value if {@code true}, continue after shutdown, else don't
     * @see #getContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    public void setContinueExistingPeriodicTasksAfterShutdownPolicy(boolean value) {
        continueExistingPeriodicTasksAfterShutdown = value;
        if (!value && isShutdown())
            onShutdown();
    }

    /**
     * Gets the policy on whether to continue executing existing
     * periodic tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow} or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code false}.
     *
     * @return {@code true} if will continue after shutdown
     * @see #setContinueExistingPeriodicTasksAfterShutdownPolicy
     */
    public boolean getContinueExistingPeriodicTasksAfterShutdownPolicy() {
        return continueExistingPeriodicTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @param value if {@code true}, execute after shutdown, else don't
     * @see #getExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    public void setExecuteExistingDelayedTasksAfterShutdownPolicy(boolean value) {
        executeExistingDelayedTasksAfterShutdown = value;
        if (!value && isShutdown())
            onShutdown();
    }

    /**
     * Gets the policy on whether to execute existing delayed
     * tasks even when this executor has been {@code shutdown}.
     * In this case, these tasks will only terminate upon
     * {@code shutdownNow}, or after setting the policy to
     * {@code false} when already shutdown.
     * This value is by default {@code true}.
     *
     * @return {@code true} if will execute after shutdown
     * @see #setExecuteExistingDelayedTasksAfterShutdownPolicy
     */
    public boolean getExecuteExistingDelayedTasksAfterShutdownPolicy() {
        return executeExistingDelayedTasksAfterShutdown;
    }

    /**
     * Sets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @param value if {@code true}, remove on cancellation, else don't
     * @see #getRemoveOnCancelPolicy
     * @since 1.7
     */
    public void setRemoveOnCancelPolicy(boolean value) {
        removeOnCancel = value;
    }

    /**
     * Gets the policy on whether cancelled tasks should be immediately
     * removed from the work queue at time of cancellation.  This value is
     * by default {@code false}.
     *
     * @return {@code true} if cancelled tasks are immediately removed
     *         from the queue
     * @see #setRemoveOnCancelPolicy
     * @since 1.7
     */
    public boolean getRemoveOnCancelPolicy() {
        return removeOnCancel;
    }

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * Invocation has no additional effect if already shut down.
     *
     * <p>This method does not wait for previously submitted tasks to
     * complete execution.  Use {@link #awaitTermination awaitTermination}
     * to do that.
     *
     * <p>If the {@code ExecuteExistingDelayedTasksAfterShutdownPolicy}
     * has been set {@code false}, existing delayed tasks whose delays
     * have not yet elapsed are cancelled.  And unless the {@code
     * ContinueExistingPeriodicTasksAfterShutdownPolicy} has been set
     * {@code true}, future executions of existing periodic tasks will
     * be cancelled.
     *
     * @throws SecurityException {@inheritDoc}
     */
    public void shutdown() {
        super.shutdown();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution.
     *
     * <p>This method does not wait for actively executing tasks to
     * terminate.  Use {@link #awaitTermination awaitTermination} to
     * do that.
     *
     * <p>There are no guarantees beyond best-effort attempts to stop
     * processing actively executing tasks.  This implementation
     * cancels tasks via {@link Thread#interrupt}, so any task that
     * fails to respond to interrupts may never terminate.
     *
     * @return list of tasks that never commenced execution.
     *         Each element of this list is a {@link ScheduledFuture},
     *         including those tasks submitted using {@code execute},
     *         which are for scheduling purposes used as the basis of a
     *         zero-delay {@code ScheduledFuture}.
     * @throws SecurityException {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        return super.shutdownNow();
    }

    /**
     * Returns the task queue used by this executor.  Each element of
     * this queue is a {@link ScheduledFuture}, including those
     * tasks submitted using {@code execute} which are for scheduling
     * purposes used as the basis of a zero-delay
     * {@code ScheduledFuture}.  Iteration over this queue is
     * <em>not</em> guaranteed to traverse tasks in the order in
     * which they will execute.
     *
     * @return the task queue
     */
    public BlockingQueue<Runnable> getQueue() {
        return super.getQueue();
    }

    /**
     * Specialized delay queue. To mesh with TPE declarations, this
     * class must be declared as a BlockingQueue<Runnable> even though
     * it can only hold RunnableScheduledFutures.
     */
    /*
     * 专用延迟队列。要与TPE声明相匹配，
     * 即使该类只能保存 RunnableScheduledFutures，也必须将其声明为BlockingQueue<Runnable>。
     *
     * 最小堆，满二叉树，使用数组表示二叉树
     *
     * 1.查询父节点 floor(i -1) / 2
     * 2.查询左子节点  i * 2 + 1
     * 3.查询右子节点 i * 2 + 2
     *
     * 假如堆 是这样的 [1,3,5,6,7,8,9]
     * (1) 插入元素的流程，执行向上冒泡的逻辑，假如要插入 2，先将 2 放到数组最后，[1,3,5,6,7,8,9,  2]，然后依次和父节点做比较
     *      假如当前元素大于父节点，则交换他们的位置，直到新元素成功根节点或者父节点比新元素大
     * (2) 删除元素的流程，执行向下冒泡的逻辑，假如要删除 1，先将数组最后一个节点的放到 数组[0] 的位置，将数组的最后一个节点置为 null
     *      [9,3,5,6,7,8,null]，获取左右子节点优先级高的节点，最小堆来说就是值小的节点， 和值小的节点交换位置，
     *      直到已经比较到底层了或者当前节点优先级比左右子节点都优先
     */
    static class DelayedWorkQueue extends AbstractQueue<Runnable>
        implements BlockingQueue<Runnable> {

        /*
         * A DelayedWorkQueue is based on a heap-based data structure
         * like those in DelayQueue and PriorityQueue, except that
         * every ScheduledFutureTask also records its index into the
         * heap array. This eliminates the need to find a task upon
         * cancellation, greatly speeding up removal (down from O(n)
         * to O(log n)), and reducing garbage retention that would
         * otherwise occur by waiting for the element to rise to top
         * before clearing. But because the queue may also hold
         * RunnableScheduledFutures that are not ScheduledFutureTasks,
         * we are not guaranteed to have such indices available, in
         * which case we fall back to linear search. (We expect that
         * most tasks will not be decorated, and that the faster cases
         * will be much more common.)
         *
         * All heap operations must record index changes -- mainly
         * within siftUp and siftDown. Upon removal, a task's
         * heapIndex is set to -1. Note that ScheduledFutureTasks can
         * appear at most once in the queue (this need not be true for
         * other kinds of tasks or work queues), so are uniquely
         * identified by heapIndex.
         */
        /*
         * DelayedWorkQueue 基于基于堆的数据结构，就像 DelayQueue 和 PriorityQueue 中的数据结构一样，
         * 区别是每个 ScheduledFutureTask 还将其索引记录到堆数组中。
         * 大大加快了删除速度（从O（n）降到O（log n）），并减少了在清除之前等待元素上升到顶部时可能发生的垃圾保留。
         * 但是，因为队列可能 hold RunnableScheduledFutures 而不是 ScheduledFutureTasks，则我们不能保证有可用的此类索引，
         * 在这种情况下，我们将回到线性搜索。（我们预计大多数任务不会被修饰，速度更快的案例将更加常见。）
         *
         * 所有堆操作都必须记录索引更改，主要是在siftUp和siftDown中。
         * 删除后，任务的heapIndex设置为-1。
         * 请注意，ScheduledFutureTasks 在队列中最多只能出现一次（其他类型的任务或工作队列不需要出现这种情况），因此由heapIndix唯一标识。
         */

        // 队列初始容量 16
        private static final int INITIAL_CAPACITY = 16;
        // 创建 INITIAL_CAPACITY 的 RunnableScheduledFuture 数组
        // 满二叉树，存储提交的任务
        private RunnableScheduledFuture<?>[] queue =
            new RunnableScheduledFuture<?>[INITIAL_CAPACITY];
        private final ReentrantLock lock = new ReentrantLock();
        // 任务队列中存储的任务数量
        private int size = 0;

        /**
         * Thread designated to wait for the task at the head of the
         * queue.  This variant of the Leader-Follower pattern
         * (http://www.cs.wustl.edu/~schmidt/POSA/POSA2/) serves to
         * minimize unnecessary timed waiting.  When a thread becomes
         * the leader, it waits only for the next delay to elapse, but
         * other threads await indefinitely.  The leader thread must
         * signal some other thread before returning from take() or
         * poll(...), unless some other thread becomes leader in the
         * interim.  Whenever the head of the queue is replaced with a
         * task with an earlier expiration time, the leader field is
         * invalidated by being reset to null, and some waiting
         * thread, but not necessarily the current leader, is
         * signalled.  So waiting threads must be prepared to acquire
         * and lose leadership while waiting.
         */
        /*
         * 指定在队列头等待任务的线程。
         * 领导-追随者模式的这种变体(http://www.cs.wustl.edu/~schmidt/POSA/POSA2/）有助于减少不必要的定时等待。
         * 当一个线程成为引导线程时，它只等待下一个延迟，而其他线程则无限期地等待。
         * 引导线程必须在从take（）或poll（…）返回之前向其他线程发出信号，除非在此期间其他线程成为引导线程。
         * 每当队列头被过期时间更早的任务替换时，leader字段就会被重置为null而无效，并且会向某些等待线程发出信号，但不一定是当前的leader。
         * 因此，等待线程必须做好准备，以便在等待时获得和失去领导权。
         *
         * 假如堆顶的任务还未到执行时间，假如 leader 线程是 null，则当前线程在条件队列中阻塞，超时等待
         * 假如 leader 线程不是 null，则需要在条件队列中无限等待
         *
         * 当 leader 线程超时唤醒后，就会调用 signal 方法给条件队列的元素发出信号，就会被转移到 AQS 的等待队列中去了
         */
        private Thread leader = null;

        /**
         * Condition signalled when a newer task becomes available at the
         * head of the queue or a new thread may need to become leader.
         */
        // 当队列前端有新任务可用或新线程可能需要成为引导线程时，会发出条件信号。
        private final Condition available = lock.newCondition();

        /**
         * Sets f's heapIndex if it is a ScheduledFutureTask.
         */
        /*
         * 设置调度任务的 heapIndex 字段
         * @param f 任务对象
         * @param idx 在堆数组中的索引
         */

        private void setIndex(RunnableScheduledFuture<?> f, int idx) {
            if (f instanceof ScheduledFutureTask)
                ((ScheduledFutureTask)f).heapIndex = idx;
        }

        /**
         * Sifts element added at bottom up to its heap-ordered spot.
         * Call only when holding lock.
         */
        /*
         * 插入节点，向上冒泡
         *
         * @param k 新添加元素的初始位置，k 会不断更新，是最终存放位置的索引
         * @param key 要添加的元素（就是任务）
         */
        private void siftUp(int k, RunnableScheduledFuture<?> key) {
            // k == 0 表示找到最小堆的堆顶元素了，
            while (k > 0) {
                // 获取父节点的索引
                int parent = (k - 1) >>> 1;
                // 获取父节点封装的任务
                RunnableScheduledFuture<?> e = queue[parent];
                // 比较两个任务的优先级
                // 条件成立，说明新添加的元素 key 的值要比父节点的值要大（最小堆），说明找到位置了，直接退出循环
                if (key.compareTo(e) >= 0)
                    break;
                // 走到这里，说明 key 的值要比父节点的要小，这里需要交换位置，继续向上冒泡
                queue[k] = e;
                setIndex(e, k);
                // 将待比较的索引更新
                k = parent;
            }
            // 走到这里，已经找到要存放的位置了
            queue[k] = key;
            setIndex(key, k);
        }

        /**
         * Sifts element added at top down to its heap-ordered spot.
         * Call only when holding lock.
         */
        /*
         * 向下冒泡
         *
         * @param k 表示要向下冒泡的初始位置，会不断变大，直到找到合适位置
         * @param key 要冒泡的任务
         */
        private void siftDown(int k, RunnableScheduledFuture<?> key) {
            // size 在调这个方法之前已经做减 1 操作了
            // half 表示满二叉树的底层的最左节点的索引值，当 k == half 时，说明当前 key 已经下坠到最底层了，无需再下坠了
            int half = size >>> 1;
            while (k < half) {
                // 表示 k 的左右子节点中，优先级高的节点
                // 初始值是左子节点
                int child = (k << 1) + 1;
                RunnableScheduledFuture<?> c = queue[child];
                // 获取右子节点
                int right = child + 1;
                // right < size 说明 key 存在右子节点，存在的话，就比较左右子节点
                // 条件成立，说明左子节点的值要比右子节点的大，（最小堆），则说明右子节点的优先级高
                if (right < size && c.compareTo(queue[right]) > 0)
                    // 右子节点的优先级高，重新赋值 c
                    c = queue[child = right];
                if (key.compareTo(c) <= 0)
                    // 条件成立，说明 key 的优先级要比 c 高，找到位置了，退出循环
                    break;

                // 执行到这里，说明 key 的优先级要比子节点的低，需要向下冒泡
                queue[k] = c;
                setIndex(c, k);
                // 更新 k 值，向下冒泡
                k = child;
            }
            // 走到这里，说明已经找到合适的位置 k 了，赋值即可
            queue[k] = key;
            setIndex(key, k);
        }

        /**
         * Resizes the heap array.  Call only when holding lock.
         */
        // 数组扩容，每次扩容 1.5 倍
        private void grow() {
            int oldCapacity = queue.length;
            int newCapacity = oldCapacity + (oldCapacity >> 1); // grow 50%
            if (newCapacity < 0) // overflow
                newCapacity = Integer.MAX_VALUE;
            queue = Arrays.copyOf(queue, newCapacity);
        }

        /**
         * Finds index of given object, or -1 if absent.
         */
        // 查询 x 是否在任务队列中，假如存在的话，就返回索引值
        private int indexOf(Object x) {
            if (x != null) {
                if (x instanceof ScheduledFutureTask) {
                    int i = ((ScheduledFutureTask) x).heapIndex;
                    // Sanity check; x could conceivably be a
                    // ScheduledFutureTask from some other pool.
                    if (i >= 0 && i < size && queue[i] == x)
                        return i;
                } else {
                    for (int i = 0; i < size; i++)
                        if (x.equals(queue[i]))
                            return i;
                }
            }
            return -1;
        }

        /**
         * 检查 x 是否在队列内
         */
        public boolean contains(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return indexOf(x) != -1;
            } finally {
                lock.unlock();
            }
        }

        public boolean remove(Object x) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = indexOf(x);
                if (i < 0)
                    return false;

                setIndex(queue[i], -1);
                int s = --size;
                RunnableScheduledFuture<?> replacement = queue[s];
                queue[s] = null;
                if (s != i) {
                    siftDown(i, replacement);
                    if (queue[i] == replacement)
                        siftUp(i, replacement);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        public int size() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return size;
            } finally {
                lock.unlock();
            }
        }

        public boolean isEmpty() {
            return size() == 0;
        }

        public int remainingCapacity() {
            return Integer.MAX_VALUE;
        }

        public RunnableScheduledFuture<?> peek() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return queue[0];
            } finally {
                lock.unlock();
            }
        }

        /**
         * 添加任务的入口
         */
        public boolean offer(Runnable x) {
            if (x == null)
                throw new NullPointerException();
            RunnableScheduledFuture<?> e = (RunnableScheduledFuture<?>)x;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                int i = size;
                if (i >= queue.length)
                    // 扩容
                    grow();
                size = i + 1;
                // 条件成立，说明当前节点是最小堆的第一个节点（任务）
                if (i == 0) {
                    queue[0] = e;
                    setIndex(e, 0);
                } else {
                    // 说明不是第一个任务，需要进行向上冒泡
                    // i：是新节点的冒泡前的位置
                    // e：新任务的节点
                    siftUp(i, e);
                }
                if (queue[0] == e) {
                    /*
                     * 两种情况，
                     * 1. 当前任务是第一个添加到堆内的任务
                     * 2. 当前任务不是第一个添加到堆内的任务，但是由于它的优先级比较高，冒泡到了堆顶，
                     *
                     * case1. 当前任务是第一个添加到堆内的任务，当前任务加入到 queue 之前，take()线程会直接到 available不设置超时时间的挂起，
                     *       因为是第一个加入的任务，此时 leader 是 null 的，调用 signal 方法会唤醒一个线程去消费
                     * case2. 当前任务优先级比较高，冒泡到堆顶了，因为之前堆顶的元素可能占用了 leader 属性，leader  线程可能正在超时挂起呢
                     *       这时需要将其置为 null，并唤醒 leader 线程，唤醒之后就会检查堆顶，如果堆顶任务可以消费，则直接获取走了
                     *       否则继续成为 leader 线程继续等待
                     */

                    leader = null;
                    available.signal();
                }
            } finally {
                lock.unlock();
            }
            return true;
        }

        public void put(Runnable e) {
            offer(e);
        }

        public boolean add(Runnable e) {
            return offer(e);
        }

        public boolean offer(Runnable e, long timeout, TimeUnit unit) {
            return offer(e);
        }

        /**
         * Performs common bookkeeping for poll and take: Replaces
         * first element with last and sifts it down.  Call only when
         * holding lock.
         * @param f the task to remove and return
         */
        private RunnableScheduledFuture<?> finishPoll(RunnableScheduledFuture<?> f) {
            // 堆大小减 1，减 1 后的 s 是当前二叉树上最后一个节点的索引
            int s = --size;
            // 获取堆中最后一个任务
            RunnableScheduledFuture<?> x = queue[s];
            queue[s] = null; // help GC
            if (s != 0)
                // 最后一个任务不是堆顶节点的话，就需要向下冒泡
                // 将最后一个节点看成堆顶，向下冒泡找到合适位置
                // k : 开始冒泡的位置  x: 在冒泡的任务对象
                siftDown(0, x);
            setIndex(f, -1);
            // 返回堆顶任务
            return f;
        }

        public RunnableScheduledFuture<?> poll() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first = queue[0];
                if (first == null || first.getDelay(NANOSECONDS) > 0)
                    return null;
                else
                    return finishPoll(first);
            } finally {
                lock.unlock();
            }
        }

        /*
         * 阻塞获取任务
         */
        public RunnableScheduledFuture<?> take() throws InterruptedException {
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                // 自旋，退出自旋说明获取到任务了，或者收到了中断异常
                for (;;) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if (first == null)
                        // 假如堆内没有元素，在条件队列不设置超时时间的等待，会在添加任务的时候被唤醒
                        available.await();
                    else {
                        // 走到这里说明堆顶是有元素的

                        // 获取延迟时间
                        long delay = first.getDelay(NANOSECONDS);
                        if (delay <= 0)
                            // 说明堆顶任务已经到执行时间了，需要将堆顶元素移除，返回堆顶任务
                            return finishPoll(first);
                        // 走到这里，说明堆顶任务还未到执行时间
                        first = null; // don't retain ref while waiting
                        if (leader != null)
                            // 无限等待，
                            // 有堆顶任务，会在最下面的 finally 块里唤醒
                            // 没有堆顶任务，会在添加 offer 任务的时候唤醒
                            available.await();
                        else {
                            // 尝试占用 leader
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                // 注意，这整块代码都在锁里面的
                                // 等待指定时间，这个 delay 是堆顶任务要执行相对时间
                                // 等待指定时间后会自动唤醒，也可能是 offer 了一个优先级更高的任务，这时也会唤醒这里的
                                // 从这里醒来肯定是拿到锁了的
                                available.awaitNanos(delay);
                            } finally {
                                // 如果唤醒后，leader 还是当前线程，需要置空
                                if (leader == thisThread)
                                    leader = null;
                            }
                        }
                    }
                }
            } finally {
                if (leader == null && queue[0] != null)
                    // 说明队列中还有下一个等待者，需要唤醒，让他去尝试获取最新的堆顶节点
                    available.signal();
                lock.unlock();
            }
        }

        public RunnableScheduledFuture<?> poll(long timeout, TimeUnit unit)
            throws InterruptedException {
            long nanos = unit.toNanos(timeout);
            final ReentrantLock lock = this.lock;
            lock.lockInterruptibly();
            try {
                for (;;) {
                    RunnableScheduledFuture<?> first = queue[0];
                    if (first == null) {
                        if (nanos <= 0)
                            return null;
                        else
                            nanos = available.awaitNanos(nanos);
                    } else {
                        long delay = first.getDelay(NANOSECONDS);
                        if (delay <= 0)
                            return finishPoll(first);
                        if (nanos <= 0)
                            return null;
                        first = null; // don't retain ref while waiting
                        if (nanos < delay || leader != null)
                            nanos = available.awaitNanos(nanos);
                        else {
                            Thread thisThread = Thread.currentThread();
                            leader = thisThread;
                            try {
                                long timeLeft = available.awaitNanos(delay);
                                nanos -= delay - timeLeft;
                            } finally {
                                if (leader == thisThread)
                                    leader = null;
                            }
                        }
                    }
                }
            } finally {
                if (leader == null && queue[0] != null)
                    available.signal();
                lock.unlock();
            }
        }

        public void clear() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                for (int i = 0; i < size; i++) {
                    RunnableScheduledFuture<?> t = queue[i];
                    if (t != null) {
                        queue[i] = null;
                        setIndex(t, -1);
                    }
                }
                size = 0;
            } finally {
                lock.unlock();
            }
        }

        /**
         * Returns first element only if it is expired.
         * Used only by drainTo.  Call only when holding lock.
         */
        private RunnableScheduledFuture<?> peekExpired() {
            // assert lock.isHeldByCurrentThread();
            RunnableScheduledFuture<?> first = queue[0];
            return (first == null || first.getDelay(NANOSECONDS) > 0) ?
                null : first;
        }

        public int drainTo(Collection<? super Runnable> c) {
            if (c == null)
                throw new NullPointerException();
            if (c == this)
                throw new IllegalArgumentException();
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first;
                int n = 0;
                while ((first = peekExpired()) != null) {
                    c.add(first);   // In this order, in case add() throws.
                    finishPoll(first);
                    ++n;
                }
                return n;
            } finally {
                lock.unlock();
            }
        }

        public int drainTo(Collection<? super Runnable> c, int maxElements) {
            if (c == null)
                throw new NullPointerException();
            if (c == this)
                throw new IllegalArgumentException();
            if (maxElements <= 0)
                return 0;
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                RunnableScheduledFuture<?> first;
                int n = 0;
                while (n < maxElements && (first = peekExpired()) != null) {
                    c.add(first);   // In this order, in case add() throws.
                    finishPoll(first);
                    ++n;
                }
                return n;
            } finally {
                lock.unlock();
            }
        }

        public Object[] toArray() {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                return Arrays.copyOf(queue, size, Object[].class);
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("unchecked")
        public <T> T[] toArray(T[] a) {
            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                if (a.length < size)
                    return (T[]) Arrays.copyOf(queue, size, a.getClass());
                System.arraycopy(queue, 0, a, 0, size);
                if (a.length > size)
                    a[size] = null;
                return a;
            } finally {
                lock.unlock();
            }
        }

        public Iterator<Runnable> iterator() {
            return new Itr(Arrays.copyOf(queue, size));
        }

        /**
         * Snapshot iterator that works off copy of underlying q array.
         */
        private class Itr implements Iterator<Runnable> {
            final RunnableScheduledFuture<?>[] array;
            int cursor = 0;     // index of next element to return
            int lastRet = -1;   // index of last element, or -1 if no such

            Itr(RunnableScheduledFuture<?>[] array) {
                this.array = array;
            }

            public boolean hasNext() {
                return cursor < array.length;
            }

            public Runnable next() {
                if (cursor >= array.length)
                    throw new NoSuchElementException();
                lastRet = cursor;
                return array[cursor++];
            }

            public void remove() {
                if (lastRet < 0)
                    throw new IllegalStateException();
                DelayedWorkQueue.this.remove(array[lastRet]);
                lastRet = -1;
            }
        }
    }
}
