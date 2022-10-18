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

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

/**
 * An {@link ExecutorService} that executes each submitted task using
 * one of possibly several pooled threads, normally configured
 * using {@link Executors} factory methods.
 *
 * <p>Thread pools address two different problems: they usually
 * provide improved performance when executing large numbers of
 * asynchronous tasks, due to reduced per-task invocation overhead,
 * and they provide a means of bounding and managing the resources,
 * including threads, consumed when executing a collection of tasks.
 * Each {@code ThreadPoolExecutor} also maintains some basic
 * statistics, such as the number of completed tasks.
 *
 * <p>To be useful across a wide range of contexts, this class
 * provides many adjustable parameters and extensibility
 * hooks. However, programmers are urged to use the more convenient
 * {@link Executors} factory methods {@link
 * Executors#newCachedThreadPool} (unbounded thread pool, with
 * automatic thread reclamation), {@link Executors#newFixedThreadPool}
 * (fixed size thread pool) and {@link
 * Executors#newSingleThreadExecutor} (single background thread), that
 * preconfigure settings for the most common usage
 * scenarios. Otherwise, use the following guide when manually
 * configuring and tuning this class:
 *
 * <dl>
 *
 * <dt>Core and maximum pool sizes</dt>
 *
 * <dd>A {@code ThreadPoolExecutor} will automatically adjust the
 * pool size (see {@link #getPoolSize})
 * according to the bounds set by
 * corePoolSize (see {@link #getCorePoolSize}) and
 * maximumPoolSize (see {@link #getMaximumPoolSize}).
 *
 * When a new task is submitted in method {@link #execute(Runnable)},
 * and fewer than corePoolSize threads are running, a new thread is
 * created to handle the request, even if other worker threads are
 * idle.  If there are more than corePoolSize but less than
 * maximumPoolSize threads running, a new thread will be created only
 * if the queue is full.  By setting corePoolSize and maximumPoolSize
 * the same, you create a fixed-size thread pool. By setting
 * maximumPoolSize to an essentially unbounded value such as {@code
 * Integer.MAX_VALUE}, you allow the pool to accommodate an arbitrary
 * number of concurrent tasks. Most typically, core and maximum pool
 * sizes are set only upon construction, but they may also be changed
 * dynamically using {@link #setCorePoolSize} and {@link
 * #setMaximumPoolSize}. </dd>
 *
 * <dt>On-demand construction</dt>
 *
 * <dd>By default, even core threads are initially created and
 * started only when new tasks arrive, but this can be overridden
 * dynamically using method {@link #prestartCoreThread} or {@link
 * #prestartAllCoreThreads}.  You probably want to prestart threads if
 * you construct the pool with a non-empty queue. </dd>
 *
 * <dt>Creating new threads</dt>
 *
 * <dd>New threads are created using a {@link ThreadFactory}.  If not
 * otherwise specified, a {@link Executors#defaultThreadFactory} is
 * used, that creates threads to all be in the same {@link
 * ThreadGroup} and with the same {@code NORM_PRIORITY} priority and
 * non-daemon status. By supplying a different ThreadFactory, you can
 * alter the thread's name, thread group, priority, daemon status,
 * etc. If a {@code ThreadFactory} fails to create a thread when asked
 * by returning null from {@code newThread}, the executor will
 * continue, but might not be able to execute any tasks. Threads
 * should possess the "modifyThread" {@code RuntimePermission}. If
 * worker threads or other threads using the pool do not possess this
 * permission, service may be degraded: configuration changes may not
 * take effect in a timely manner, and a shutdown pool may remain in a
 * state in which termination is possible but not completed.</dd>
 *
 * <dt>Keep-alive times</dt>
 *
 * <dd>If the pool currently has more than corePoolSize threads,
 * excess threads will be terminated if they have been idle for more
 * than the keepAliveTime (see {@link #getKeepAliveTime(TimeUnit)}).
 * This provides a means of reducing resource consumption when the
 * pool is not being actively used. If the pool becomes more active
 * later, new threads will be constructed. This parameter can also be
 * changed dynamically using method {@link #setKeepAliveTime(long,
 * TimeUnit)}.  Using a value of {@code Long.MAX_VALUE} {@link
 * TimeUnit#NANOSECONDS} effectively disables idle threads from ever
 * terminating prior to shut down. By default, the keep-alive policy
 * applies only when there are more than corePoolSize threads. But
 * method {@link #allowCoreThreadTimeOut(boolean)} can be used to
 * apply this time-out policy to core threads as well, so long as the
 * keepAliveTime value is non-zero. </dd>
 *
 * <dt>Queuing</dt>
 *
 * <dd>Any {@link BlockingQueue} may be used to transfer and hold
 * submitted tasks.  The use of this queue interacts with pool sizing:
 *
 * <ul>
 *
 * <li> If fewer than corePoolSize threads are running, the Executor
 * always prefers adding a new thread
 * rather than queuing.</li>
 *
 * <li> If corePoolSize or more threads are running, the Executor
 * always prefers queuing a request rather than adding a new
 * thread.</li>
 *
 * <li> If a request cannot be queued, a new thread is created unless
 * this would exceed maximumPoolSize, in which case, the task will be
 * rejected.</li>
 *
 * </ul>
 *
 * There are three general strategies for queuing:
 * <ol>
 *
 * <li> <em> Direct handoffs.</em> A good default choice for a work
 * queue is a {@link SynchronousQueue} that hands off tasks to threads
 * without otherwise holding them. Here, an attempt to queue a task
 * will fail if no threads are immediately available to run it, so a
 * new thread will be constructed. This policy avoids lockups when
 * handling sets of requests that might have internal dependencies.
 * Direct handoffs generally require unbounded maximumPoolSizes to
 * avoid rejection of new submitted tasks. This in turn admits the
 * possibility of unbounded thread growth when commands continue to
 * arrive on average faster than they can be processed.  </li>
 *
 * <li><em> Unbounded queues.</em> Using an unbounded queue (for
 * example a {@link LinkedBlockingQueue} without a predefined
 * capacity) will cause new tasks to wait in the queue when all
 * corePoolSize threads are busy. Thus, no more than corePoolSize
 * threads will ever be created. (And the value of the maximumPoolSize
 * therefore doesn't have any effect.)  This may be appropriate when
 * each task is completely independent of others, so tasks cannot
 * affect each others execution; for example, in a web page server.
 * While this style of queuing can be useful in smoothing out
 * transient bursts of requests, it admits the possibility of
 * unbounded work queue growth when commands continue to arrive on
 * average faster than they can be processed.  </li>
 *
 * <li><em>Bounded queues.</em> A bounded queue (for example, an
 * {@link ArrayBlockingQueue}) helps prevent resource exhaustion when
 * used with finite maximumPoolSizes, but can be more difficult to
 * tune and control.  Queue sizes and maximum pool sizes may be traded
 * off for each other: Using large queues and small pools minimizes
 * CPU usage, OS resources, and context-switching overhead, but can
 * lead to artificially low throughput.  If tasks frequently block (for
 * example if they are I/O bound), a system may be able to schedule
 * time for more threads than you otherwise allow. Use of small queues
 * generally requires larger pool sizes, which keeps CPUs busier but
 * may encounter unacceptable scheduling overhead, which also
 * decreases throughput.  </li>
 *
 * </ol>
 *
 * </dd>
 *
 * <dt>Rejected tasks</dt>
 *
 * <dd>New tasks submitted in method {@link #execute(Runnable)} will be
 * <em>rejected</em> when the Executor has been shut down, and also when
 * the Executor uses finite bounds for both maximum threads and work queue
 * capacity, and is saturated.  In either case, the {@code execute} method
 * invokes the {@link
 * RejectedExecutionHandler#rejectedExecution(Runnable, ThreadPoolExecutor)}
 * method of its {@link RejectedExecutionHandler}.  Four predefined handler
 * policies are provided:
 *
 * <ol>
 *
 * <li> In the default {@link ThreadPoolExecutor.AbortPolicy}, the
 * handler throws a runtime {@link RejectedExecutionException} upon
 * rejection. </li>
 *
 * <li> In {@link ThreadPoolExecutor.CallerRunsPolicy}, the thread
 * that invokes {@code execute} itself runs the task. This provides a
 * simple feedback control mechanism that will slow down the rate that
 * new tasks are submitted. </li>
 *
 * <li> In {@link ThreadPoolExecutor.DiscardPolicy}, a task that
 * cannot be executed is simply dropped.  </li>
 *
 * <li>In {@link ThreadPoolExecutor.DiscardOldestPolicy}, if the
 * executor is not shut down, the task at the head of the work queue
 * is dropped, and then execution is retried (which can fail again,
 * causing this to be repeated.) </li>
 *
 * </ol>
 *
 * It is possible to define and use other kinds of {@link
 * RejectedExecutionHandler} classes. Doing so requires some care
 * especially when policies are designed to work only under particular
 * capacity or queuing policies. </dd>
 *
 * <dt>Hook methods</dt>
 *
 * <dd>This class provides {@code protected} overridable
 * {@link #beforeExecute(Thread, Runnable)} and
 * {@link #afterExecute(Runnable, Throwable)} methods that are called
 * before and after execution of each task.  These can be used to
 * manipulate the execution environment; for example, reinitializing
 * ThreadLocals, gathering statistics, or adding log entries.
 * Additionally, method {@link #terminated} can be overridden to perform
 * any special processing that needs to be done once the Executor has
 * fully terminated.
 *
 * <p>If hook or callback methods throw exceptions, internal worker
 * threads may in turn fail and abruptly terminate.</dd>
 *
 * <dt>Queue maintenance</dt>
 *
 * <dd>Method {@link #getQueue()} allows access to the work queue
 * for purposes of monitoring and debugging.  Use of this method for
 * any other purpose is strongly discouraged.  Two supplied methods,
 * {@link #remove(Runnable)} and {@link #purge} are available to
 * assist in storage reclamation when large numbers of queued tasks
 * become cancelled.</dd>
 *
 * <dt>Finalization</dt>
 *
 * <dd>A pool that is no longer referenced in a program <em>AND</em>
 * has no remaining threads will be {@code shutdown} automatically. If
 * you would like to ensure that unreferenced pools are reclaimed even
 * if users forget to call {@link #shutdown}, then you must arrange
 * that unused threads eventually die, by setting appropriate
 * keep-alive times, using a lower bound of zero core threads and/or
 * setting {@link #allowCoreThreadTimeOut(boolean)}.  </dd>
 *
 * </dl>
 *
 * <p><b>Extension example</b>. Most extensions of this class
 * override one or more of the protected hook methods. For example,
 * here is a subclass that adds a simple pause/resume feature:
 *
 *  <pre> {@code
 * class PausableThreadPoolExecutor extends ThreadPoolExecutor {
 *   private boolean isPaused;
 *   private ReentrantLock pauseLock = new ReentrantLock();
 *   private Condition unpaused = pauseLock.newCondition();
 *
 *   public PausableThreadPoolExecutor(...) { super(...); }
 *
 *   protected void beforeExecute(Thread t, Runnable r) {
 *     super.beforeExecute(t, r);
 *     pauseLock.lock();
 *     try {
 *       while (isPaused) unpaused.await();
 *     } catch (InterruptedException ie) {
 *       t.interrupt();
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 *
 *   public void pause() {
 *     pauseLock.lock();
 *     try {
 *       isPaused = true;
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 *
 *   public void resume() {
 *     pauseLock.lock();
 *     try {
 *       isPaused = false;
 *       unpaused.signalAll();
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */

/*
 * 翻译：
 * 通常使用 Executors 工厂方法配置 ExecutorService，每个提交的任务都由池中的线程执行。
 *
 * 线程池解决了两个问题：当执行大量的异步任务时提供了更好的性能，这是因为线程池减少了每个任务的调用开销，
 * 并且它们提供了一种限制和管理资源的方法，包括执行时一组任务的消耗，
 * 每个线程池保持了一些基本的统计信息，例如总完成任务数。
 *
 * JDK 建议我们使用 Executors 工厂来获得线程池对象。
 * 假如使用构造方法创建线程池的话，下面是建议：
 * 1. 核心线程数和最大线程数
 *      ThreadPoolExecutor 会根据 corePoolSize 和 maximumPoolSize 自动调整线程池的线程的个数。
 *      当使用 execute(Runnable) 添加了一个新任务，并且线程池当前运行的线程池少于 corePoolSize，会创建一个新的线程去处理这个任务，
 *      即使其他工作线程是空闲的。如果运行的线程超过 corePoolSize 线程数且小于 maximumPoolSize ，当队列满的时候才会去创建一个新的线程去处理。
 *
 *      通过设置相同的 corePoolSize 和 maximumPoolSize，就是创建了一个固定大小的线程池了。
 *      通过设置设置 maximumPoolSize 一个很大的值，例如 Integer.MAX_VALUE，表示允许线程池容纳任务数量的并发任务。
 *      核心线程数和最大线程数一般是在构造方法时设置，也可以通过方法设置。
 *
 * 2.预启动线程
 *      默认情况下，即使是核心线程也只会在新任务到达时才会创建和启动，可以使用方法预先启动线程，
 *      prestartCoreThread 或者 prestartAllCoreThreads
 *
 * 3.创建新线程
 *      通过 ThreadFactory 创建线程工厂，如果没有另外指定，使用的是默认工厂 DefaultThreadFactory。
 *      创建的所有线程都是相同的 ThreadGroup，相同的优先级和是否是守护线程。
 *      通过提供不同的 ThreadFactory ，我们可以更改线程的名字，线程组，优先级和守护进程状态等等。
 *      如果 ThreadFactory 通过 newThread 方法创建线程失败，此时返回 null，执行器会继续执行，但是可能无法执行任务任务。
 *      这些线程应该具有"modifyThread"的权限。如果工作线程或者使用池的其他线程不具备这个权限，则服务可能会降级：配置更改可能无法及时生效，
 *      关闭池可能会保持，可能终止
 *
 * 4.Keep Alive times 线程存活时间
 *      如果当前线程池有超过 corePoolSize 的线程，假如超过的线程空闲了 keepAliveTime 时间，则会被终止。
 *      这提供了一种当线程池没有被积极使用时减少资源的方法，如果线程池变得活跃使用了，后面会重新创建新的线程。
 *      这个参数也可以使用方法修改。
 *      默认情况下，keepAliveTimes 策略只适用于超过 corePoolSize 的线程，
 *      但是allowCoreThreadTimeOut 方法可以将这个超时策略应用于核心线程，只要 keepAliveTime不是 0 就行了
 *
 * 5.队列
 *      任何 BlockingQueue 都可以用来传输和保存提交的任务。这个队列的使用和池的大小交互
 *
 *      如果运行的线程少于 corePoolSize，则执行器总是更喜欢添加新的线程而不是排队；
 *      如果运行的线程 corePoolSize 或更多的线程，执行器总是喜欢排队请求而不是添加新的线程；
 *      如果请求无法排队，则创建一个新的线程，除非这会超过 maximumPoolSize ，在这种情况下任务会被拒绝。
 *
 *      队列有三种通用策略：
 *          1.直接切换。工作队列的一个很好的默认选择是 SynchronousQueue，它将任务交给线程而不用其他方式保留它们。
 *          在这里，如果没有立即可用的线程来运行任务，则尝试将任务排队将失败，因此将构造一个新线程。
 *          在处理可能具有内部依赖关系的请求集时，此策略可避免锁定。
 *          直接切换通常需要无限的 maximumPoolSizes 以避免拒绝新提交的任务。
 *          这反过来又承认了当命令的平均到达速度快于它们的处理速度时，线程无限增长的可能性。 <li>
 *          2.无界队列，例如 LinkedBlockingQueue，这将会导致新任务在所有 corePoolSize 线程都忙时在队列中等待。
 *          因此，不会超过 corePoolSize 的线程。所有这种情况其实 maximumPoolSize 的值对线程池没有任何影响。
 *          当每个任务完全独立于其他任务时，这可能是合适的，因此任务不会影响彼此的执行。
 *          虽然这种排队方式在平滑请求的瞬时突发时很有用，但是当命令的平均到达数据大于处理速度时，工作队列可能会无限增长。
 *          3.有界队列。例如，ArrayBlockingQueue 在与有限的 maximumPoolSizes 一起使用时有助于防止资源耗尽，但可能更难以调整和控制。
 *          队列大小和最大池大小可以相互权衡：使用大队列和小池可以最大限度地减少 CPU 使用率、操作系统资源和上下文切换开销，
 *          但可能会导致人为地降低吞吐量。如果任务经常阻塞（例如，如果它们受 IO 限制），则系统可能能够为比您允许的更多线程安排时间。
 *          使用小队列通常需要更大的池大小，这会使 CPU 更忙，但可能会遇到不可接受的调度开销，这也会降低吞吐量。
 *
 * 6.拒绝策略
 *      在方法 execute(Runnable) 中提交的新任务将在 Executor 关闭时被拒绝，
 *      并且当 Executor 对最大线程和工作队列容量使用有限边界时，并且是饱和的。
 *      在任何一种情况下，execute 方法都会调用其 RejectedExecutionHandler 的拒绝方法。提供了四个预定义的处理程序策略：
 *          1.在默认的 ThreadPoolExecutor.AbortPolicy 中，处理程序在拒绝时抛出运行时 RejectedExecutionException。
 *          2.在 ThreadPoolExecutor.CallerRunsPolicy 中，调用execute 的线程本身运行任务。
 *          这提供了一种简单的反馈控制机制，可以减慢提交新任务的速度。
 *          3.在 ThreadPoolExecutor.DiscardPolicy 中，一个无法执行的任务被简单地丢弃。
 *          4.在 ThreadPoolExecutor.DiscardOldestPolicy 中，如果executor没有关闭，则丢弃工作队列头部的任务，
 *          然后重试执行（可能再次失败，导致这个重复。）
 *          可以定义和使用其他类型的  RejectedExecutionHandler 类。这样做需要小心谨慎，尤其是当策略设计为仅在特定容量或排队策略下工作时。
 *
 * 7.钩子方法
 *      此类提供 protected 可覆盖的 beforeExecute(Thread, Runnable) 和 afterExecute(Runnable, Throwable)方法，
 *      它们在每个任务执行之前和之后调用。这些可用于操纵执行环境；
 *      例如，重新初始化 ThreadLocals、收集统计信息或添加日志条目。
 *      此外，可以重写方法 terminate 以执行任何需要在 Executor 完全终止后完成的特殊处理。
 *      如果钩子或回调方法抛出异常，内部工作线程可能会依次失败并突然终止。
 *
 * 8.队列的维护
 *      方法 getQueue() 允许访问工作队列以进行监视和调试。强烈建议不要将此方法用于任何其他目的。
 *      当大量排队的任务被取消时，提供了两种方法 remove(Runnable) 和 purge 可用于协助存储回收。
 *
 * 9.Finalization
 *      程序中不再引用的池 AND 没有剩余线程将自动 shutdown。
 *      如果您想确保即使用户忘记调用  shutdown 也能回收未引用的池，那么您必须安排未使用的线程最终死亡，
 *      方法是设置适当的保持活动时间，使用零核心线程的下限和或设置 allowCoreThreadTimeOut(boolean)
 *
 */
public class ThreadPoolExecutor extends AbstractExecutorService {
    /**
     * The main pool control state, ctl, is an atomic integer packing
     * two conceptual fields
     *   workerCount, indicating the effective number of threads
     *   runState,    indicating whether running, shutting down etc
     *
     * In order to pack them into one int, we limit workerCount to
     * (2^29)-1 (about 500 million) threads rather than (2^31)-1 (2
     * billion) otherwise representable. If this is ever an issue in
     * the future, the variable can be changed to be an AtomicLong,
     * and the shift/mask constants below adjusted. But until the need
     * arises, this code is a bit faster and simpler using an int.
     *
     * The workerCount is the number of workers that have been
     * permitted to start and not permitted to stop.  The value may be
     * transiently different from the actual number of live threads,
     * for example when a ThreadFactory fails to create a thread when
     * asked, and when exiting threads are still performing
     * bookkeeping before terminating. The user-visible pool size is
     * reported as the current size of the workers set.
     *
     * The runState provides the main lifecycle control, taking on values:
     *
     *   RUNNING:  Accept new tasks and process queued tasks
     *   SHUTDOWN: Don't accept new tasks, but process queued tasks
     *   STOP:     Don't accept new tasks, don't process queued tasks,
     *             and interrupt in-progress tasks
     *   TIDYING:  All tasks have terminated, workerCount is zero,
     *             the thread transitioning to state TIDYING
     *             will run the terminated() hook method
     *   TERMINATED: terminated() has completed
     *
     * The numerical order among these values matters, to allow
     * ordered comparisons. The runState monotonically increases over
     * time, but need not hit each state. The transitions are:
     *
     * RUNNING -> SHUTDOWN
     *    On invocation of shutdown(), perhaps implicitly in finalize()
     * (RUNNING or SHUTDOWN) -> STOP
     *    On invocation of shutdownNow()
     * SHUTDOWN -> TIDYING
     *    When both queue and pool are empty
     * STOP -> TIDYING
     *    When pool is empty
     * TIDYING -> TERMINATED
     *    When the terminated() hook method has completed
     *
     * Threads waiting in awaitTermination() will return when the
     * state reaches TERMINATED.
     *
     * Detecting the transition from SHUTDOWN to TIDYING is less
     * straightforward than you'd like because the queue may become
     * empty after non-empty and vice versa during SHUTDOWN state, but
     * we can only terminate if, after seeing that it is empty, we see
     * that workerCount is 0 (which sometimes entails a recheck -- see
     * below).
     */

    /**
     * 线程池的状态 ctl，是一个原子整数，包装了两个概念字段
     * workerCount，表示有效线程数
     * runState，表示是否运行、关闭等
     *
     * 为了将它们打包到一个 int 中，我们将 workerCount 限制为 (2^29)-1（约 5 亿）个线程，而不是 (2^31)-1（20 亿）个线程。
     * 如果这在将来成为问题，可以将变量更改为 AtomicLong，并调整下面的 shiftmask 常量。
     * 但是在需要之前，这段代码使用 int 会更快更简单一些。
     *
     * workerCount 是允许启动和不允许停止的工作程序的数量。该值可能与实际的活动线程数暂时不同，
     * 例如，当 ThreadFactory 在被询问时未能创建线程时，以及退出线程在终止前仍在执行簿记时。
     * 用户可见的池大小报告为工作集的当前大小
     *
     * runState 提供主要的生命周期控制，取值：
     *
     * RUNNING:  接受新任务并处理排队的任务
     * SHUTDOWN: 不接受新任务，但处理排队的任务
     * STOP:    不接受新任务，不处理排队的任务，中断正在进行的任务
     * TIDYING:  所有任务都已终止，workerCount 为零，转换到状态 TIDYING 的线程将运行 terminate() 钩子方法
     * TERMINATED: terminated() 方法执行完了
     *
     * 这些值之间的数字顺序很重要，以便进行有序比较。 runState 随着时间的推移单调增加，但不需要达到每个状态。过渡是：
     * RUNNING -> SHUTDOWN
     *    在调用 shutdown() 时，可能隐含在 finalize() 中
     * (RUNNING or SHUTDOWN) -> STOP
     *    调用 shutdownNow()
     * SHUTDOWN -> TIDYING
     *    当队列和池都为空时
     * STOP -> TIDYING
     *    当池为空时
     * TIDYING -> TERMINATED
     *    当 terminate() 钩子方法完成时
     *
     * 当状态达到 TERMINATED 时，在 awaitTermination() 中等待的线程将返回。
     *
     * 检测从 SHUTDOWN 到 TIDYING 的转换并不像你想的那么简单，因为队列可能在非空后变为空，
     * 在 SHUTDOWN 状态下反之亦然，但我们只能在看到它为空之后终止，我们看到 workerCount为 0（有时需要重新检查——见下文）。
     */
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));
    // 用于计算最大线程个数，(2^29)-1
    private static final int COUNT_BITS = Integer.SIZE - 3;
    // 用于计算最大线程个数，(2^29)-1
    // 00001111 11111111 11111111 11111111
    private static final int CAPACITY   = (1 << COUNT_BITS) - 1;

    // runState is stored in the high-order bits
    // runState 存储在高位
    // 111 00000 00000000 00000000 00000000
    private static final int RUNNING    = -1 << COUNT_BITS;
    // 000 00000 00000000 00000000 00000000
    private static final int SHUTDOWN   =  0 << COUNT_BITS;
    // 001 00000 00000000 00000000 00000000
    private static final int STOP       =  1 << COUNT_BITS;
    // 010 00000 00000000 00000000 00000000
    private static final int TIDYING    =  2 << COUNT_BITS;
    // 011 00000 00000000 00000000 00000000
    private static final int TERMINATED =  3 << COUNT_BITS;

    // Packing and unpacking ctl
    // 获取线程池的运行状态
    private static int runStateOf(int c)     { return c & ~CAPACITY; }
    // 获取 worker 线程数
    private static int workerCountOf(int c)  { return c & CAPACITY; }
    // 根据线程池 worker 数和线程池的状态构建 ctl
    private static int ctlOf(int rs, int wc) { return rs | wc; }

    /*
     * Bit field accessors that don't require unpacking ctl.
     * These depend on the bit layout and on workerCount being never negative.
     */

    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    /**
     * Attempts to CAS-increment the workerCount field of ctl.
     */
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    /**
     * Attempts to CAS-decrement the workerCount field of ctl.
     */
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    /**
     * Decrements the workerCount field of ctl. This is called only on
     * abrupt termination of a thread (see processWorkerExit). Other
     * decrements are performed within getTask.
     */
    private void decrementWorkerCount() {
        do {} while (! compareAndDecrementWorkerCount(ctl.get()));
    }

    /**
     * The queue used for holding tasks and handing off to worker
     * threads.  We do not require that workQueue.poll() returning
     * null necessarily means that workQueue.isEmpty(), so rely
     * solely on isEmpty to see if the queue is empty (which we must
     * do for example when deciding whether to transition from
     * SHUTDOWN to TIDYING).  This accommodates special-purpose
     * queues such as DelayQueues for which poll() is allowed to
     * return null even if it may later return non-null when delays
     * expire.
     */
    // 用于保存任务并移交给 worker 线程的队列。
    // 我们并不要求 workQueue.poll() 返回 null 一定意味着 workQueue.isEmpty()，
    // 所以完全依赖 isEmpty 来查看队列是否为空（例如，我们必须这样做，在决定是否从 SHUTDOWN 过渡到 TIDYING 时） .
    // 这适用于特殊用途的队列，例如 DelayQueues，其中 poll() 允许返回 null，即使它稍后可能在延迟到期时返回非 null。
    private final BlockingQueue<Runnable> workQueue;

    /**
     * Lock held on access to workers set and related bookkeeping.
     * While we could use a concurrent set of some sort, it turns out
     * to be generally preferable to use a lock. Among the reasons is
     * that this serializes interruptIdleWorkers, which avoids
     * unnecessary interrupt storms, especially during shutdown.
     * Otherwise exiting threads would concurrently interrupt those
     * that have not yet interrupted. It also simplifies some of the
     * associated statistics bookkeeping of largestPoolSize etc. We
     * also hold mainLock on shutdown and shutdownNow, for the sake of
     * ensuring workers set is stable while separately checking
     * permission to interrupt and actually interrupting.
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * Set containing all worker threads in pool. Accessed only when
     * holding mainLock.
     */
    // 包含池中所有工作线程的集合。仅在持有 mainLock 时访问。
    private final HashSet<Worker> workers = new HashSet<Worker>();

    /**
     * Wait condition to support awaitTermination
     */
    private final Condition termination = mainLock.newCondition();

    /**
     * Tracks largest attained pool size. Accessed only under
     * mainLock.
     */
    // 跟踪获得的最大池大小。只能在 mainLock 下访问。
    private int largestPoolSize;

    /**
     * Counter for completed tasks. Updated only on termination of
     * worker threads. Accessed only under mainLock.
     */
    // 已完成任务的计数器。仅在工作线程终止时更新。只能在 mainLock 下访问。
    private long completedTaskCount;

    /*
     * All user control parameters are declared as volatiles so that
     * ongoing actions are based on freshest values, but without need
     * for locking, since no internal invariants depend on them
     * changing synchronously with respect to other actions.
     */
    /*
     * 所有用户控制参数都被声明为 volatile，因此正在进行的操作基于最新值，
     * 但不需要锁定，因为没有内部不变量依赖于它们相对于其他操作同步变化。
     */

    /**
     * Factory for new threads. All threads are created using this
     * factory (via method addWorker).  All callers must be prepared
     * for addWorker to fail, which may reflect a system or user's
     * policy limiting the number of threads.  Even though it is not
     * treated as an error, failure to create threads may result in
     * new tasks being rejected or existing ones remaining stuck in
     * the queue.
     *
     * We go further and preserve pool invariants even in the face of
     * errors such as OutOfMemoryError, that might be thrown while
     * trying to create threads.  Such errors are rather common due to
     * the need to allocate a native stack in Thread.start, and users
     * will want to perform clean pool shutdown to clean up.  There
     * will likely be enough memory available for the cleanup code to
     * complete without encountering yet another OutOfMemoryError.
     */
    /*
     * 新线程的工厂。所有线程都是使用这个工厂创建的（通过方法 addWorker）。
     * 所有调用者都必须为 addWorker 失败做好准备，这可能反映了系统或用户限制线程数的策略。
     * 即使它不被视为错误，创建线程失败也可能导致新任务被拒绝或现有任务卡在队列中。
     * 即使在尝试创建线程时可能会抛出诸如 OutOfMemoryError 之类的错误时，我们也会更进一步并保留池不变量。
     * 由于需要在 Thread.start 中分配本机堆栈，因此此类错误相当普遍，并且用户将希望执行干净的池关闭以进行清理。
     * 可能有足够的内存可供清理代码完成，而不会遇到另一个 OutOfMemoryError。
     */
    private volatile ThreadFactory threadFactory;

    /**
     * Handler called when saturated or shutdown in execute.
     */
    // 拒绝策略处理器
    private volatile RejectedExecutionHandler handler;

    /**
     * Timeout in nanoseconds for idle threads waiting for work.
     * Threads use this timeout when there are more than corePoolSize
     * present or if allowCoreThreadTimeOut. Otherwise they wait
     * forever for new work.
     */
    // 空闲线程的超时时间。默认是配置给超过 corePoolSize 的线程的，当配置了 allowCoreThreadTimeOut 时，是可以应用到核心线程的
    private volatile long keepAliveTime;

    /**
     * If false (default), core threads stay alive even when idle.
     * If true, core threads use keepAliveTime to time out waiting
     * for work.
     */
    // 默认 false，表示核心线程空闲时也不删除；true 表示核心线程空闲超时后会被删除；
    private volatile boolean allowCoreThreadTimeOut;

    /**
     * Core pool size is the minimum number of workers to keep alive
     * (and not allow to time out etc) unless allowCoreThreadTimeOut
     * is set, in which case the minimum is zero.
     */
    // 核心线程数
    private volatile int corePoolSize;

    /**
     * Maximum pool size. Note that the actual maximum is internally
     * bounded by CAPACITY.
     */
    // 最大线程数，也是有限制的 CAPACITY
    private volatile int maximumPoolSize;

    /**
     * The default rejected execution handler
     */
    // 默认的拒绝策略
    private static final RejectedExecutionHandler defaultHandler =
        new AbortPolicy();

    /**
     * Permission required for callers of shutdown and shutdownNow.
     * We additionally require (see checkShutdownAccess) that callers
     * have permission to actually interrupt threads in the worker set
     * (as governed by Thread.interrupt, which relies on
     * ThreadGroup.checkAccess, which in turn relies on
     * SecurityManager.checkAccess). Shutdowns are attempted only if
     * these checks pass.
     *
     * All actual invocations of Thread.interrupt (see
     * interruptIdleWorkers and interruptWorkers) ignore
     * SecurityExceptions, meaning that the attempted interrupts
     * silently fail. In the case of shutdown, they should not fail
     * unless the SecurityManager has inconsistent policies, sometimes
     * allowing access to a thread and sometimes not. In such cases,
     * failure to actually interrupt threads may disable or delay full
     * termination. Other uses of interruptIdleWorkers are advisory,
     * and failure to actually interrupt will merely delay response to
     * configuration changes so is not handled exceptionally.
     */
    /*
     * 调用 shutdown 和 shutdownNow 需要的权限。
     * 我们还要求（请参阅checkShutdownAccess）调用方有权实际中断 worker set 中的线程
     * （由Thread.interrupt管理，它依赖于ThreadGroup.checkAccess，它又依赖于SecurityManager.checkAccess）
     * shutdown 只有在上面这些权限通过了才会。
     *
     * Thread的所有实际调用。中断（参见interruptIdleWorkers和interruptWorkers）忽略 SecurityExceptions，
     * 表示尝试的中断无声地失败。在停机的情况下，它们不应出现故障除非SecurityManager的策略不一致，有时允许访问线程，有时不允许。
     * 在这种情况下， 未能真正中断线程可能会禁用或延迟完全结束interruptIdleWorkers的其他用途是建议性的，而未能真正中断只会延迟响应
     * 配置更改，因此不会异常处理。
     */
    // ThreadPoolExcutor中的shutdownPerm运行时权限 https://blog.csdn.net/john1337/article/details/102912070
    private static final RuntimePermission shutdownPerm =
        new RuntimePermission("modifyThread");

    /* The context to be used when executing the finalizer, or null. */
    private final AccessControlContext acc;

    /**
     * Class Worker mainly maintains interrupt control state for
     * threads running tasks, along with other minor bookkeeping.
     * This class opportunistically extends AbstractQueuedSynchronizer
     * to simplify acquiring and releasing a lock surrounding each
     * task execution.  This protects against interrupts that are
     * intended to wake up a worker thread waiting for a task from
     * instead interrupting a task being run.  We implement a simple
     * non-reentrant mutual exclusion lock rather than use
     * ReentrantLock because we do not want worker tasks to be able to
     * reacquire the lock when they invoke pool control methods like
     * setCorePoolSize.  Additionally, to suppress interrupts until
     * the thread actually starts running tasks, we initialize lock
     * state to a negative value, and clear it upon start (in
     * runWorker).
     */
    /*
     * 继承 AQS
     *
     * 类Worker主要维护运行任务的线程的中断控制状态，以及其他次要的簿记。
     * 此类机会主义地扩展了AbstractQueuedSynchronizer，以简化获取和释放围绕每个任务执行的锁。
     * 这可以防止中断，这些中断旨在唤醒等待任务的工作线程，而不是中断正在运行的任务。
     *
     * 我们实现了一个简单的不可重入互斥锁，而不是使用ReentrantLock，
     * 因为我们不希望工作任务在调用setCorePoolSize等池控制方法时能够重新获取锁。
     * 此外，为了在线程实际开始运行任务之前禁止中断，我们将锁定状态初始化为负值，并在启动时清除它（在runWorker中）。
     */
    private final class Worker
        extends AbstractQueuedSynchronizer
        implements Runnable
    {
        /**
         * This class will never be serialized, but we provide a
         * serialVersionUID to suppress a javac warning.
         */
        private static final long serialVersionUID = 6138294804551838833L;

        /** Thread this worker is running in.  Null if factory fails. */
        // worker 的线程，当在线程工厂创建失败的时候是 null
        final Thread thread;
        /** Initial task to run.  Possibly null. */
        // 不是 null 的话，当线程启动后会第一个执行这个任务
        Runnable firstTask;
        /** Per-thread task counter */
        // 线程完成任务个数
        volatile long completedTasks;

        /**
         * Creates with given first task and thread from ThreadFactory.
         * @param firstTask the first task (null if none)
         */
        Worker(Runnable firstTask) {
            // 防止在 runWorker 之前被中断
            setState(-1); // inhibit interrupts until runWorker
            this.firstTask = firstTask;
            // 通过线程工厂创建线程，注意线程可能创建失败
            this.thread = getThreadFactory().newThread(this);
        }

        /** Delegates main run loop to outer runWorker  */
        // 线程执行入口
        public void run() {
            runWorker(this);
        }

        // Lock methods
        //
        // The value 0 represents the unlocked state.
        // The value 1 represents the locked state.

        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        /**
         * 尝试加锁
         */
        protected boolean tryAcquire(int unused) {
            // 尝试修改同步状态为 0 -> 1 ，修改成功表示加锁成功
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        /**
         * 尝试释放锁
         */
        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        // 加锁
        public void lock()        { acquire(1); }
        // 尝试加锁
        public boolean tryLock()  { return tryAcquire(1); }
        // 释放锁
        // 启动worker之前会先调用unlock()这个方法,
        // 会强制刷新独占线程会强制刷新独占线程ExclusiveOwnerThread为null，同步状态State为0
        public void unlock()      { release(1); }
        // 返回当前worker的独占锁lock是否被占用
        public boolean isLocked() { return isHeldExclusively(); }

        void interruptIfStarted() {
            Thread t;
            // 中断所有已经启动的线程
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }

    /*
     * Methods for setting control state
     */

    /**
     * Transitions runState to given target, or leaves it alone if
     * already at least the given target.
     *
     * @param targetState the desired state, either SHUTDOWN or STOP
     *        (but not TIDYING or TERMINATED -- use tryTerminate for that)
     */
    /*
     * 转换运行状态到给定的目标，或者如果已经至少是给定的目标，则让它保持不变。
     * targetState 是我们要转换的目标状态，可能是 SHUTDOWN 也可能是 STOP。
     * 但是不可能是 TIDYING 和 TERMINATED，一般用 tryTerminate 方法来实现这两个状态。
     */
    private void advanceRunState(int targetState) {
        for (;;) {
            int c = ctl.get();
            if (runStateAtLeast(c, targetState) ||
                ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }

    /**
     * Transitions to TERMINATED state if either (SHUTDOWN and pool
     * and queue empty) or (STOP and pool empty).  If otherwise
     * eligible to terminate but workerCount is nonzero, interrupts an
     * idle worker to ensure that shutdown signals propagate. This
     * method must be called following any action that might make
     * termination possible -- reducing worker count or removing tasks
     * from the queue during shutdown. The method is non-private to
     * allow access from ScheduledThreadPoolExecutor.
     */
    /*
     * 如果（SHUTDOWN和池及队列为空）或（STOP和池为空），则转换为TERMINATED状态。
     * 如果符合终止条件，但workerCount不为零，则中断空闲工作进程以确保关闭信号传播。
     * 必须在执行任何可能导致终止的操作后调用此方法，
     * 这些操作包括减少工作人员计数或在关闭期间从队列中删除任务。
     * 该方法是非私有的，允许从ScheduledThreadPoolExecutor访问。
     */
    final void tryTerminate() {
        // 自旋
        for (;;) {
            int c = ctl.get();
            /*
             * 1 当前线程池是RUNNING状态 此时无需结束线程池
             * 2 当前线程池的状态大于等于TIDYING，说明已经有其它线程在执行TIDYING -> TERMINATED状态了，
             *   也就是已经走到了下面的步骤，线程池即将终结，此线程直接返回
             * 3 当前线程池是SHUTDOWN状态且任务队列不为空。说明还有任务需要处理 不能改变线程池状态
             */
            if (isRunning(c) ||
                runStateAtLeast(c, TIDYING) ||
                (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()))
                return;
            /*
             * 运行到此处前提是
             * 1-线程池的状态为STOP
             * 2-线程池的状态为SHUTDOWN，但是队列为空
             */
            if (workerCountOf(c) != 0) { // Eligible to terminate
                // 中断一个空闲的线程
                // 这里中断是 getTask 方法中阻塞等待任务的线程，中断后 getTask 返回 null
                // 返回 null 后，就会调用 processWorkerExit 中继续调用 tryTerminate 方法
                // 中断一个空闲的线程，相当于传播一个关闭线程池的信号
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            // 走到这里说明当前工作线程数已经为0了，
            // 也就是说当前线程是最后一个执行任务的线程，此时需要完成结束线程池的动作
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                // CAS 尝试创建 c   是 TIDYING 状态且 workcount 是 0
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        // 终止，钩子方法，子类实现
                        terminated();
                    } finally {
                        // 设置 TERMINATED 状态且 workcount 是 0
                        ctl.set(ctlOf(TERMINATED, 0));
                        // 唤醒阻塞在termination条件的所有线程，这个变量的await()方法在awaitTermination()中调用
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }

    /*
     * Methods for controlling interrupts to worker threads.
     */

    /**
     * If there is a security manager, makes sure caller has
     * permission to shut down threads in general (see shutdownPerm).
     * If this passes, additionally makes sure the caller is allowed
     * to interrupt each worker thread. This might not be true even if
     * first check passed, if the SecurityManager treats some threads
     * specially.
     */
    private void checkShutdownAccess() {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                for (Worker w : workers)
                    security.checkAccess(w.thread);
            } finally {
                mainLock.unlock();
            }
        }
    }

    /**
     * Interrupts all threads, even if active. Ignores SecurityExceptions
     * (in which case some threads may remain uninterrupted).
     */
    private void interruptWorkers() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers)
                w.interruptIfStarted();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Interrupts threads that might be waiting for tasks (as
     * indicated by not being locked) so they can check for
     * termination or configuration changes. Ignores
     * SecurityExceptions (in which case some threads may remain
     * uninterrupted).
     *
     * @param onlyOne If true, interrupt at most one worker. This is
     * called only from tryTerminate when termination is otherwise
     * enabled but there are still other workers.  In this case, at
     * most one waiting worker is interrupted to propagate shutdown
     * signals in case all threads are currently waiting.
     * Interrupting any arbitrary thread ensures that newly arriving
     * workers since shutdown began will also eventually exit.
     * To guarantee eventual termination, it suffices to always
     * interrupt only one idle worker, but shutdown() interrupts all
     * idle workers so that redundant workers exit promptly, not
     * waiting for a straggler task to finish.
     */
    /*
     * 中断可能正在等待任务的线程，以便它们可以检查终止或配置更改。
     * 忽略SecurityExceptions（在这种情况下，某些线程可能保持不间断）。
     *
     * onlyOne如果为true，则最多中断一个工作线程。
     * 只有在启用终止但仍有其他工作线程时，才会从tryTerminate调用此函数。
     * 在这种情况下，如果所有线程当前都在等待，则最多会中断一个等待的工作线程来传播关闭信号。
     * 中断任意线程可以确保自关闭开始以来新到达的工人最终也会退出。
     * 为了保证最终的终止，只中断一个空闲的工作线程就足够了，但shutdown（）会中断所有线程闲置的工人，
     * 以便多余的工人及时退出，而不是等待零散的任务完成。
     */
    private void interruptIdleWorkers(boolean onlyOne) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (Worker w : workers) {
                Thread t = w.thread;
                // !t.isInterrupted()  == true  说明当前遍历到的线程没有被中断。
                // tryLock 表示需要尝试获取锁，获取成功，说明线程未在执行任务，因为执行任务的时候需要加锁
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        // 中断线程，这个线程可能是在等待阻塞队列中的任务而被阻塞
                        // 给它一个中断信号。
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        w.unlock();
                    }
                }
                if (onlyOne)
                    break;
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Common form of interruptIdleWorkers, to avoid having to
     * remember what the boolean argument means.
     */
    // 中断所有的工作线程
    private void interruptIdleWorkers() {
        interruptIdleWorkers(false);
    }

    private static final boolean ONLY_ONE = true;

    /*
     * Misc utilities, most of which are also exported to
     * ScheduledThreadPoolExecutor
     */

    /**
     * Invokes the rejected execution handler for the given command.
     * Package-protected for use by ScheduledThreadPoolExecutor.
     */
    final void reject(Runnable command) {
        handler.rejectedExecution(command, this);
    }

    /**
     * Performs any further cleanup following run state transition on
     * invocation of shutdown.  A no-op here, but used by
     * ScheduledThreadPoolExecutor to cancel delayed tasks.
     */
    void onShutdown() {
    }

    /**
     * State check needed by ScheduledThreadPoolExecutor to
     * enable running tasks during shutdown.
     *
     * @param shutdownOK true if should return true if SHUTDOWN
     */
    // ScheduledThreadPoolExecutor 需要进行状态检查，以便在 shutdown 期间启用正在运行的任务。
    final boolean isRunningOrShutdown(boolean shutdownOK) {
        int rs = runStateOf(ctl.get());
        // 如果是 RUNNING 直接返回 true，表示需要执行
        // 假如是 SHUTDOWN 状态，根据入参 shutdownOK 决定是否在这个状态执行任务
        return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
    }

    /**
     * Drains the task queue into a new list, normally using
     * drainTo. But if the queue is a DelayQueue or any other kind of
     * queue for which poll or drainTo may fail to remove some
     * elements, it deletes them one by one.
     */
    // 移除任务队列的任务，并将其收集到一个List集合，通常使用drainTo。
    // 但是，如果队列是DelayQueue或任何其他类型的队列，poll或drainTo可能无法删除某些元素，那么它会一个一个地删除它们。
    private List<Runnable> drainQueue() {
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<Runnable>();
        // 从此队列中删除所有可用元素，并将它们添加到给定集合taskList中
        q.drainTo(taskList);
        if (!q.isEmpty()) {
            // 如果队列中还有部分元素没有导出，不为null，会一个一个地删除它们
            for (Runnable r : q.toArray(new Runnable[0])) {
                if (q.remove(r))
                    taskList.add(r);
            }
        }
        return taskList;
    }

    /*
     * Methods for creating, running and cleaning up after workers
     */

    /**
     * Checks if a new worker can be added with respect to current
     * pool state and the given bound (either core or maximum). If so,
     * the worker count is adjusted accordingly, and, if possible, a
     * new worker is created and started, running firstTask as its
     * first task. This method returns false if the pool is stopped or
     * eligible to shut down. It also returns false if the thread
     * factory fails to create a thread when asked.  If the thread
     * creation fails, either due to the thread factory returning
     * null, or due to an exception (typically OutOfMemoryError in
     * Thread.start()), we roll back cleanly.
     *
     * @param firstTask the task the new thread should run first (or
     * null if none). Workers are created with an initial first task
     * (in method execute()) to bypass queuing when there are fewer
     * than corePoolSize threads (in which case we always start one),
     * or when the queue is full (in which case we must bypass queue).
     * Initially idle threads are usually created via
     * prestartCoreThread or to replace other dying workers.
     *
     * @param core if true use corePoolSize as bound, else
     * maximumPoolSize. (A boolean indicator is used here rather than a
     * value to ensure reads of fresh values after checking other pool
     * state).
     * @return true if successful
     */
    /*
     * 检查是否可以根据当前池状态和给定绑定（核心或最大）添加新工作线程。
     * 如果是这样，则会相应地调整工作者计数，如果可能，会创建并启动一个新的工作者，并将firstTask作为其第一个任务运行。
     * 如果池已停止或符合关闭条件，此方法将返回false。
     * 如果线程工厂在被询问时未能创建线程，它也会返回false。
     * 如果线程创建失败，或者是因为线程工厂返回null，或者是由于异常（通常是thread.start（）中的OutOfMemoryError），那么我们将完全回滚。
     *
     * firstTask新线程应该首先运行的任务（如果没有，则为null）。
     * 当线程少于corePoolSize时（在这种情况下，我们总是启动一个线程），或者当队列已满时（在那种情况下，必须绕过队列），
     * 创建带有初始第一个任务（在方法execute（）中）的工作线程来绕过队列。
     * 最初，空闲线程通常是通过prestartCoreThread创建的，或者用来替换其他即将死亡的工作线程。
     *
     * core if true使用corePoolSize作为绑定，否则使用 maximumPoolSize。（此处使用布尔指示符而不是值，以确保在检查其他池状态后读取新值）。
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
        // 外部循环标记 retry
        retry:
        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            /*
             * 判断线程池在此状态是否允许添加线程
             * 条件 1：rs >= SHUTDOWN：成立则说明不是 running 状态
             * 条件 2：结果取反，注意的是当线程池是 SHOTDOWN 状态时，如果队列中还有任务未处理完
             *      这时是允许添加 Worker 的，但是不允许再次提交任务
             *
             * 那么这么说，什么时候走后面的逻辑呢？就是 if 条件返回 false 的时候
             * 1. rs 运行态
             * 2. rs 是 SHUTDOWN 且 firstTask == null 且队列不是空
             */
            if (rs >= SHUTDOWN &&
                ! (rs == SHUTDOWN && firstTask == null && ! workQueue.isEmpty()))
                // 进入这个的情况
                // 1. 当前池子的状态 > SHUTDOWN
                // 2. 当前池子状态是 SHUTDOWN，但是 firstTask 不是 null
                // 3. 当前池子状态是 SHUTDOWN，但是 firstTask 是 null 且队列是空的，也就是没任务了
                return false;

            // 内部自旋，主要是为了增加线程的个数
            for (;;) {
                int wc = workerCountOf(c);
                // 假如当前线程池的线程个数已经达到了 CAPACITY 或者根据 core 变量校验线程个数
                if (wc >= CAPACITY ||
                    wc >= (core ? corePoolSize : maximumPoolSize))
                    // 线程个数被限制了，返回 false
                    return false;
                // CAS 增加 WorkerCount，增加成功退出外循环 retry
                if (compareAndIncrementWorkerCount(c))
                    break retry;
                c = ctl.get();  // Re-read ctl
                // 自旋过程中，发现线程池的状态改变了，重新开始外循环，重新校验
                if (runStateOf(c) != rs)
                    continue retry;
                // else CAS failed due to workerCount change; retry inner loop
            }
        }

        // 走到这里 workCount 已经成功 +1 了

        // 表示创建的 Worker 是否已经启动
        boolean workerStarted = false;
        // 表示创建的 Worker 是否添加到池子中
        boolean workerAdded = false;
        Worker w = null;
        try {
            // 创建新线程
            w = new Worker(firstTask);
            final Thread t = w.thread;
            // 判断 t != null 的原因是，线程是通过线程工厂获取的，线程工厂可能返回的是 null
            if (t != null) {
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // Recheck while holding lock.
                    // Back out on ThreadFactory failure or if
                    // shut down before lock acquired.
                    // 双重检查，如果出现ThreadFactory故障或在获取锁之前关闭，请退出。
                    int rs = runStateOf(ctl.get());

                    // 校验：线程池运行态 || (SHUTDOWN && firstTask == null)
                    if (rs < SHUTDOWN ||
                        (rs == SHUTDOWN && firstTask == null)) {
                        // 校验线程是否调用了 start 方法，这是防止自己实现的 ThreadFactory 在创建线程的时候调了 start
                        if (t.isAlive()) // precheck that t is startable
                            throw new IllegalThreadStateException();
                        workers.add(w);
                        int s = workers.size();
                        // 获取 Set 的大小，假如大于记录的最大 worker 数量 largestPoolSize，则更新
                        if (s > largestPoolSize)
                            largestPoolSize = s;
                        // worker 添加成功标志
                        workerAdded = true;
                    }
                } finally {
                    mainLock.unlock();
                }
                // 线程校验添加处理成功，则开启线程
                if (workerAdded) {
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            if (! workerStarted)
                // 线程启动失败，则进入这里
                // 主要就是将 Worker 数量减 1，移除刚刚保存到 set 的 worker
                addWorkerFailed(w);
        }
        // 返回是否成功启动线程
        return workerStarted;
    }

    /**
     * Rolls back the worker thread creation.
     * - removes worker from workers, if present
     * - decrements worker count
     * - rechecks for termination, in case the existence of this
     *   worker was holding up termination
     */
    /*
     * 在调用addWorker方法时，假如在经过一系列校验通过后，
     * - 假如worker在workers中，移除它。workers就是存放创建的Worker的Set集合。
     * - 减少Worker的数量，因为之前已经增加1了。
     * - 重新检查终止TERMINATED状态，以防该Worker的存在阻碍了终止。
     */
    private void addWorkerFailed(Worker w) {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            if (w != null)
                // 假如worker已经创建了，则移除它
                workers.remove(w);
            // worker的数量减1 因为在 addWorker 方法的循环校验阶段就增加了线程个数
            decrementWorkerCount();
            tryTerminate();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Performs cleanup and bookkeeping for a dying worker. Called
     * only from worker threads. Unless completedAbruptly is set,
     * assumes that workerCount has already been adjusted to account
     * for exit.  This method removes thread from worker set, and
     * possibly terminates the pool or replaces the worker if either
     * it exited due to user task exception or if fewer than
     * corePoolSize workers are running or queue is non-empty but
     * there are no workers.
     *
     * @param w the worker
     * @param completedAbruptly if the worker died due to user exception
     */
    /*
     * 这个方法将要退出的线程从 WorkerSet 中移除，并尝试 tryTerminate，
     * 在下面几种情况会创建新的 Worker 添加到池中
     * 1.当前线程在执行任务时发生异常，需要创建一个新 worker 到线程池。
     * 2.!workQueue.isEmpty() 说明任务队列中还有任务，最起码要留一个线程。 当前状态为 RUNNING || SHUTDOWN
     * 3.当前线程数量 < corePoolSize值，此时会创建线程，维护线程池数量在corePoolSize个。
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        if (completedAbruptly) // If abrupt, then workerCount wasn't adjusted
            // 如果是发生异常退出循环的，需要减少worker的数量
            // 异常退出时，ctl 计数，并没有-1
            decrementWorkerCount();

        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 将单个 worker 完成的任务数量累加到线程池的完成数量
            completedTaskCount += w.completedTasks;
            /*
             * 把当前 Worker（也就是当前线程）剔除出 workers 集合中，等待GC
             * 要么是空闲的核心线程超时需要被销毁，要么是空闲的非核心线程超时需要被销毁。
             * 不管属于哪一种，当前线程都是要被销毁的
             */
            workers.remove(w);
        } finally {
            mainLock.unlock();
        }

        // 尝试将线程池改为Terminate状态
        tryTerminate();

        int c = ctl.get();
        // 条件成立，说明当前线程池的状态是 RUNNING 或者 SHUTDOWN
        if (runStateLessThan(c, STOP)) {
            // 条件成立说明，当前线程的退出是正常退出，不是异常
            if (!completedAbruptly) {
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                // 此时如果队列里还有任务没执行（可能在执行到此处的时候，又有人提交了任务到队列中了），
                // 就使 min=1，因为还需要至少留一个线程去执行task任务
                if (min == 0 && ! workQueue.isEmpty())
                    min = 1;
                // 假如当前线程池的worker的数量大于等于min，说明线程池的worker数量足够，直接返回
                if (workerCountOf(c) >= min)
                    return; // replacement not needed
            }
            // 此处前置条件：1-线程执行任务发生异常 2-正常执行任务但是workerCountOf(c) < min
            // 1.当前线程在执行任务时发生异常，需要创建一个新 worker 到线程池。
            // 2.!workQueue.isEmpty() 说明任务队列中还有任务，最起码要留一个线程。 当前状态为 RUNNING || SHUTDOWN
            // 3.当前线程数量 < corePoolSize值，此时会创建线程，维护线程池数量在corePoolSize个。
            addWorker(null, false);
        }
    }

    /**
     * Performs blocking or timed wait for a task, depending on
     * current configuration settings, or returns null if this worker
     * must exit because of any of:
     * 1. There are more than maximumPoolSize workers (due to
     *    a call to setMaximumPoolSize).
     * 2. The pool is stopped.
     * 3. The pool is shutdown and the queue is empty.
     * 4. This worker timed out waiting for a task, and timed-out
     *    workers are subject to termination (that is,
     *    {@code allowCoreThreadTimeOut || workerCount > corePoolSize})
     *    both before and after the timed wait, and if the queue is
     *    non-empty, this worker is not the last thread in the pool.
     *
     * @return task, or null if the worker must exit, in which case
     *         workerCount is decremented
     */
    /*
     * 阻塞或者超时等待一个任务，依赖当前的配置，或者返回 null 表示当前 Worker 需要退出
     * 1. 超过了 maximumPoolSize 个 Worker ，可能是因为调用了 setMaximumPoolSize 方法
     * 2. 线程池 stopped 了
     * 3. 线程池 shutdown 了，或者队列中没任务了
     * 4. 此工作线程在等待任务时超时，超时的工作线程在超时等待前后都会终止
     * （即{@code allowCoreThreadTimeOut||workerCount>corePoolSize}），如果队列非空，则此工作线程不是池中的最后一个线程。
     */
    private Runnable getTask() {
        boolean timedOut = false; // Did the last poll() time out?

        for (;;) {
            int c = ctl.get();
            int rs = runStateOf(c);

            /*
             * Check if queue empty only if necessary.
             * 条件1：rs >= SHUTDOWN 线程池的状态不是RUNNING状态
             * 条件2：rs >= STOP || workQueue.isEmpty()
             * 	线程池的状态为STOP，TIDYING，TERMINATED
             *  或者 线程池的状态为SHUTDOWN状态且队列中没有任务了
             * 这两种状态已经不需要获取任务了，返回null之后runWorker就退出循环了
             */
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
            }

            // 走到这里说明：1-线程池时RUNNING 2-SHUTDOWN但是阻塞队列中还有任务

            int wc = workerCountOf(c);

            // Are workers subject to culling?
            // allowCoreThreadTimeOut 是控制核心线程是否允许超时的
            // timed表示当前线程是否允许超时 销毁
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;
            /*
             * timedOut 为 true说明不是第一次循环了 上次循环中已经发生了poll的超时
             * 条件一：(wc > maximumPoolSize || (timed && timedOut))
             * 1.1：wc > maximumPoolSize  为什么会成立？setMaximumPoolSize()方法，可能外部线程将线程池最大线程数设置为比初始化时的要小
             * 1.2: (timed && timedOut) 条件成立：前置条件，当前线程使用 poll方式获取task。上一次循环时  使用poll方式获取任务时，超时了
             * 条件一 为true 表示 线程可以被回收，达到回收标准，当确实需要回收时再回收。
             * 条件二：(wc > 1 || workQueue.isEmpty())
             * 2.1: wc > 1  条件成立，说明当前线程池中还有其他线程，当前线程可以直接回收，返回null
             * 2.2: workQueue.isEmpty() 前置条件 wc == 1， 条件成立：说明当前任务队列 已经空了，最后一个线程，也可以放心的退出。
             */
            if ((wc > maximumPoolSize || (timed && timedOut))
                && (wc > 1 || workQueue.isEmpty())) {
                if (compareAndDecrementWorkerCount(c))
                    return null;
                continue;
            }

            try {
                /*
                 * 如果 timed 为true，则通过 poll 方法进行限时拿取（超过 keepAliveTime 时间没有拿取到，就直接返回 null），
                 * 否则通过 take 方法进行拿取（如果阻塞队列为空，take 方法在此时就会被阻塞住，也就是本线程会被阻塞住，直到
                 * 阻塞队列中有数据了。也就是说如果 timed 为 false 的话，这些工作线程会一直被阻塞在这里）
                 */
                Runnable r = timed ?
                    workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                    workQueue.take();
                if (r != null)
                    return r;
                timedOut = true;
            } catch (InterruptedException retry) {
                timedOut = false;
            }
        }
    }

    /**
     * Main worker run loop.  Repeatedly gets tasks from queue and
     * executes them, while coping with a number of issues:
     *
     * 1. We may start out with an initial task, in which case we
     * don't need to get the first one. Otherwise, as long as pool is
     * running, we get tasks from getTask. If it returns null then the
     * worker exits due to changed pool state or configuration
     * parameters.  Other exits result from exception throws in
     * external code, in which case completedAbruptly holds, which
     * usually leads processWorkerExit to replace this thread.
     *
     * 2. Before running any task, the lock is acquired to prevent
     * other pool interrupts while the task is executing, and then we
     * ensure that unless pool is stopping, this thread does not have
     * its interrupt set.
     *
     * 3. Each task run is preceded by a call to beforeExecute, which
     * might throw an exception, in which case we cause thread to die
     * (breaking loop with completedAbruptly true) without processing
     * the task.
     *
     * 4. Assuming beforeExecute completes normally, we run the task,
     * gathering any of its thrown exceptions to send to afterExecute.
     * We separately handle RuntimeException, Error (both of which the
     * specs guarantee that we trap) and arbitrary Throwables.
     * Because we cannot rethrow Throwables within Runnable.run, we
     * wrap them within Errors on the way out (to the thread's
     * UncaughtExceptionHandler).  Any thrown exception also
     * conservatively causes thread to die.
     *
     * 5. After task.run completes, we call afterExecute, which may
     * also throw an exception, which will also cause thread to
     * die. According to JLS Sec 14.20, this exception is the one that
     * will be in effect even if task.run throws.
     *
     * The net effect of the exception mechanics is that afterExecute
     * and the thread's UncaughtExceptionHandler have as accurate
     * information as we can provide about any problems encountered by
     * user code.
     *
     * @param w the worker
     */
    /**
     * 主要是执行 firstTask 任务和阻塞队列中的任务
     * 1. 假如 firstTask 不为 null，第一次执行此任务。其他只要线程池在运行就会去队列中获取任务并执行。
     *    假如 getTask 方法返回 null，然后 worker 退出，此种情况有可能是池子的状态或者配置被修改了。
     *    其他情况的 worker 退出可能是因为外部代码抛出异常，此时变量 completedAbruptly 就为 true 了，
     *    然后 processWorkerExit 方法会创建一个 Worker 来替换此 Worker。
     * 2. 在运行任何任务之前，将获取锁以防止任务执行期间被其他线程池中断。
     *    然后确保除非池正在停止的状态，也就是 STOPPING ，此线程将不会设置其中断状态。
     * 3. 每个任务执行前都会调用 beforeExecute ，这是个钩子方法，具体的实现可能会抛出异常。
     *    抛出异常时，并不会执行线程，而是让线程死亡（通过设置 completedAbruptly 为 true 退出循环）
     * 4. 假如 beforeExecute 正常执行完毕，收集 task 的 run 方法中抛出的异常，
     *    作为参数到 afterExecute 方法中，这也是钩子方法，需要子类实现，
     *    我们可以在 afterExecute 方法中针对异常做对应的处理。分别处理 RuntimeException、Error(规范保证捕获这两种错误)
     *    和任意的可抛出对象。因为我们不能在 Runnable.run 中重新抛出可抛出对象，
     *    所以我们在抛出时将它们包装在错误中(到线程的UncaughtExceptionHandler)。任何抛出的异常也会导致线程死亡。
     * 5. 假如 task 的 run 方法正常执行完毕，就会调用 afterExecute 方法。因为是子类实现的方法，所以可能会抛出异常，此时也会导致线程死亡。
     */
    final void runWorker(Worker w) {
        Thread wt = Thread.currentThread();
        Runnable task = w.firstTask;
        w.firstTask = null; // help GC
        /*
         * 因为之前创建 Worker 的时候将 AQS 的 state 初始为 -1，是为了防止线程被中断
         * 而这里 unlock 方法是把 state 重置为 0，exclusiveOwnerThread ==null。
         * 意思就是已经进入到 runWorker 方法中，可以允许中断了
         */
        w.unlock(); // allow interrupts
        // 是否是正常退出标志，false 正常退出 true 字面意思突然退出，也就是有异常
        boolean completedAbruptly = true;
        try {
            // 执行 firstTask 任务，或者从阻塞队列中获取任务执行
            while (task != null || (task = getTask()) != null) {
                // 设置独占锁 不是可重入锁，为了确保下面的代码不会被同一线程所重入，同时可以做到不同线程可以并发执行
                // shutdown 时会判断当前 worker 状态，根据独占锁是否空闲来判断当前 worker 是否正在工作。
                w.lock();
                // If pool is stopping, ensure thread is interrupted;
                // if not, ensure thread is not interrupted.  This
                // requires a recheck in second case to deal with
                // shutdownNow race while clearing interrupt
                // 假如线程池的状态 >= STOP 需要保证线程被中断。
                // 线程池的状态 < STOP，需要保证线程未被中断。
                // 这需要在第二种情况下重新检查，以处理 shutdownNow 争用，同时清除中断状态
                // ((当前线程池的状态 >= STOP) || (当前线程是否被中断 && 当前线程池的状态 >= STOP)) && 当前线程未被中断
                if ((runStateAtLeast(ctl.get(), STOP) || (Thread.interrupted() && runStateAtLeast(ctl.get(), STOP)))
                        && !wt.isInterrupted())
                    // 中断线程
                    wt.interrupt();
                try {
                    // 执行任务前的逻辑
                    beforeExecute(wt, task);
                    Throwable thrown = null;
                    try {
                        // 执行任务的 run 方法
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        // 执行任务后执行的逻辑
                        afterExecute(task, thrown);
                    }
                } finally {
                    // beforeExecute，run，afterExecute都可能会抛出异常
                    // 任务置null 下次循环的时候就会在阻塞队列中拿取下一个任务了
                    task = null;
                    // 完成任务+1
                    w.completedTasks++;
                    // 释放独占锁
                    w.unlock();
                    // 正常情况下，会继续进行while循环
                    // 异常情况下，会直接退出循环，直接跳到processWorkerExit，此时completedAbruptly为true
                }
            }
            // 走到此处说明 Worker 和队列中都已经没有了任务
            completedAbruptly = false;
        } finally {
            // 执行退出 Worker 的逻辑
            processWorkerExit(w, completedAbruptly);
        }
    }

    // Public constructors and methods

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default thread factory and rejected execution handler.
     * It may be more convenient to use one of the {@link Executors} factory
     * methods instead of this general purpose constructor.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default rejected execution handler.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code threadFactory} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             threadFactory, defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default thread factory.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code handler} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), handler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code threadFactory} or {@code handler} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,
                              RejectedExecutionHandler handler) {
        if (corePoolSize < 0 ||
            maximumPoolSize <= 0 ||
            maximumPoolSize < corePoolSize ||
            keepAliveTime < 0)
            throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();
        this.acc = System.getSecurityManager() == null ?
                null :
                AccessController.getContext();
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = workQueue;
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    /**
     * Executes the given task sometime in the future.  The task
     * may execute in a new thread or in an existing pooled thread.
     *
     * If the task cannot be submitted for execution, either because this
     * executor has been shutdown or because its capacity has been reached,
     * the task is handled by the current {@code RejectedExecutionHandler}.
     *
     * @param command the task to execute
     * @throws RejectedExecutionException at discretion of
     *         {@code RejectedExecutionHandler}, if the task
     *         cannot be accepted for execution
     * @throws NullPointerException if {@code command} is null
     */
    // 将任务提交到线程池，会在将来的某个时间执行
    // 任务可能是创建新的线程执行，也可能是已经存在池中的线程
    // 如果由于此执行器已关闭或已达到其容量，无法提交任务以供执行，则该任务由当前 RejectedExecutionHandler 处理。
    public void execute(Runnable command) {
        if (command == null)
            throw new NullPointerException();
        /*
         * Proceed in 3 steps:
         *
         * 1. If fewer than corePoolSize threads are running, try to
         * start a new thread with the given command as its first
         * task.  The call to addWorker atomically checks runState and
         * workerCount, and so prevents false alarms that would add
         * threads when it shouldn't, by returning false.
         *
         * 2. If a task can be successfully queued, then we still need
         * to double-check whether we should have added a thread
         * (because existing ones died since last checking) or that
         * the pool shut down since entry into this method. So we
         * recheck state and if necessary roll back the enqueuing if
         * stopped, or start a new thread if there are none.
         *
         * 3. If we cannot queue task, then we try to add a new
         * thread.  If it fails, we know we are shut down or saturated
         * and so reject the task.
         */
        /*
         * 1.如果池中的线程数小于 corePoolSize，尝试创建一个新的线程去执行任务。
         *      会调用 addWorker 方法原子校验 runState 和 workCout，
         *      通过返回false，可以防止错误警报在不应该添加线程时添加线程。
         * 2.如果一个任务成功入队，当添加线程时我们仍然需要 double-check，因为已经存在的线程可能在上次检查时死亡了，
         *      或者线程池可能进入这个方法的时候被关闭了，所以需要再次检查状态，
         *      因此我们重新检查状态，如果需要，在停止时回滚排队，如果没有，则启动新线程
         * 3.如果任务无法入队，我们需要创建一个新的线程。如果失败，我们知道我们已经关闭或饱和，因此拒绝任务。
         */
        int c = ctl.get();
        // 校验：当前线程池的存活的线程数量 小于 核心线程数吗？
        if (workerCountOf(c) < corePoolSize) {
            // 小于核心线程数就尝试创建一个新的线程，添加成功后直接 return
            // 入参 true 表示使用核心线程数 corePoolSize 来限制
            if (addWorker(command, true))
                return;
            // 到这里说明添加新线程失败（并发），重新获取原子值
            c = ctl.get();
        }
        // 前置条件：当前线程池的线程数 >= 核心线程容量，或者是 addWorker 创建线程失败了
        // 校验：当前线程池是运行状态 && 任务入队成功
        if (isRunning(c) && workQueue.offer(command)) {
            // 重新获取原子状态 double-check
            int recheck = ctl.get();
            // 校验：当前线程池不是运行状态 && 从队列移除任务成功，则执行拒绝策略
            /*
             * CASE 1：!isRunning(recheck) 为 true 的话
             * 	 说明任务提交到队列之后，线程池状态被外部线程给修改 比如：shutdown() shutdownNow()
             *   此时需要用 remove 将刚刚提交的任务从队列移除
             * CASE 2：remove(command)
             *   移除成功：说明这个任务提交之后，没有线程过来处理
             *   移除失败：说明这个任务提交之后，在线程池关闭前被其他线程给处理了
             */
            if (! isRunning(recheck) && remove(command))
                reject(command);
            // 前置条件：线程池是运行态，或者不是运行态但是移除队列失败了
            // 假如线程池的线程个数是 0，那么需要创建一个 worker 线程保证这个任务执行
            // 此处是保证线程池此时至少得有一个线程，防止线程池没有线程了
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false);
        }
        // 前置条件：线程池不是运行态，或者是运行态但是任务入队失败
        // 此时尝试创建新 worker 线程去执行，
        // 1.线程池状态不是运行态，addWorker 肯定会失败，执行拒绝策略
        // 2.假如是任务入队失败，需要调用 addWorker 方法去尝试创建新线程，添加失败需要执行拒绝策略
        else if (!addWorker(command, false))
            reject(command);
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
     * @throws SecurityException {@inheritDoc}
     */
    /*
     * 启动有序关闭，其中执行以前提交的任务，但不接受新任务。
     * 如果调用已经关闭，则没有额外的效果。
     * 此方法不会等待以前提交的任务完成执行。使用{@link#awaitTermination-awaitTermination}执行此操作。
     */
    public void shutdown() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 检查关闭的权限
            checkShutdownAccess();
            // 尝试将线程池的状态改为 SHUTDOWN
            advanceRunState(SHUTDOWN);
            // 中断空闲线程
            interruptIdleWorkers();
            // ScheduledThreadPoolExecutor 的钩子方法
            onShutdown(); // hook for ScheduledThreadPoolExecutor
        } finally {
            mainLock.unlock();
        }
        tryTerminate();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution. These tasks are drained (removed)
     * from the task queue upon return from this method.
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
     * @throws SecurityException {@inheritDoc}
     */
    // 改为 STOP
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 校验关闭线程的权限
            checkShutdownAccess();
            // 线程池的状态改为 STOP
            advanceRunState(STOP);
            // 中断所有已经启动的线程
            interruptWorkers();
            // 导出未处理的任务列表
            tasks = drainQueue();
        } finally {
            mainLock.unlock();
        }
        // 根据线程池状态来判断是否应该结束线程池
        tryTerminate();
        // 返回当前任务队列中 未处理的任务。
        return tasks;
    }

    // 是否 shutdown 状态
    public boolean isShutdown() {
        return ! isRunning(ctl.get());
    }

    /**
     * Returns true if this executor is in the process of terminating
     * after {@link #shutdown} or {@link #shutdownNow} but has not
     * completely terminated.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, causing this executor not
     * to properly terminate.
     *
     * @return {@code true} if terminating but not yet terminated
     */
    // 是否在 running 状态和 TERMINATED 状态之间，其实就是关闭线程池的过程中
    public boolean isTerminating() {
        int c = ctl.get();
        return ! isRunning(c) && runStateLessThan(c, TERMINATED);
    }

    // 是否是 TERMINATED 状态
    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    /**
     * 等待线程池的状态是 TERMINATED，才退出阻塞
     * 其实就是等待任务全部完成，具体可在 tryTerminated 方法看到
     */
    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (;;) {
                // 自旋直到线程池的状态是 TERMINATED
                if (runStateAtLeast(ctl.get(), TERMINATED))
                    return true;
                if (nanos <= 0)
                    return false;
                // 阻塞等待
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Invokes {@code shutdown} when this executor is no longer
     * referenced and it has no threads.
     */
    protected void finalize() {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null || acc == null) {
            shutdown();
        } else {
            PrivilegedAction<Void> pa = () -> { shutdown(); return null; };
            AccessController.doPrivileged(pa, acc);
        }
    }

    /**
     * Sets the thread factory used to create new threads.
     *
     * @param threadFactory the new thread factory
     * @throws NullPointerException if threadFactory is null
     * @see #getThreadFactory
     */
    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null)
            throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    /**
     * Returns the thread factory used to create new threads.
     *
     * @return the current thread factory
     * @see #setThreadFactory(ThreadFactory)
     */
    // 线程工厂
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    /**
     * Sets a new handler for unexecutable tasks.
     *
     * @param handler the new handler
     * @throws NullPointerException if handler is null
     * @see #getRejectedExecutionHandler
     */
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null)
            throw new NullPointerException();
        this.handler = handler;
    }

    /**
     * Returns the current handler for unexecutable tasks.
     *
     * @return the current handler
     * @see #setRejectedExecutionHandler(RejectedExecutionHandler)
     */
    // 获取拒绝策略
    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    /**
     * Sets the core number of threads.  This overrides any value set
     * in the constructor.  If the new value is smaller than the
     * current value, excess existing threads will be terminated when
     * they next become idle.  If larger, new threads will, if needed,
     * be started to execute any queued tasks.
     *
     * @param corePoolSize the new core size
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @see #getCorePoolSize
     */
    // 设置核心线程数
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0)
            throw new IllegalArgumentException();
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize)
            interruptIdleWorkers();
        else if (delta > 0) {
            // We don't really know how many new threads are "needed".
            // As a heuristic, prestart enough new workers (up to new
            // core size) to handle the current number of tasks in
            // queue, but stop if queue becomes empty while doing so.
            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) {
                if (workQueue.isEmpty())
                    break;
            }
        }
    }

    /**
     * Returns the core number of threads.
     *
     * @return the core number of threads
     * @see #setCorePoolSize
     */
    // 核心线程数
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /**
     * Starts a core thread, causing it to idly wait for work. This
     * overrides the default policy of starting core threads only when
     * new tasks are executed. This method will return {@code false}
     * if all core threads have already been started.
     *
     * @return {@code true} if a thread was started
     */
    // 开启一个核心线程
    public boolean prestartCoreThread() {
        return workerCountOf(ctl.get()) < corePoolSize &&
            addWorker(null, true);
    }

    /**
     * Same as prestartCoreThread except arranges that at least one
     * thread is started even if corePoolSize is 0.
     */
    // 与 prestartCoreThread 相同，
    // 即使 corePoolSize为0，也至少启动一个线程。
    void ensurePrestart() {
        int wc = workerCountOf(ctl.get());
        if (wc < corePoolSize)
            addWorker(null, true);
        else if (wc == 0)
            addWorker(null, false);
    }

    /**
     * Starts all core threads, causing them to idly wait for work. This
     * overrides the default policy of starting core threads only when
     * new tasks are executed.
     *
     * @return the number of threads started
     */
    // 启动所有核心线程，不用等待任务到来时再创建线程
    public int prestartAllCoreThreads() {
        int n = 0;
        while (addWorker(null, true))
            ++n;
        return n;
    }

    /**
     * Returns true if this pool allows core threads to time out and
     * terminate if no tasks arrive within the keepAlive time, being
     * replaced if needed when new tasks arrive. When true, the same
     * keep-alive policy applying to non-core threads applies also to
     * core threads. When false (the default), core threads are never
     * terminated due to lack of incoming tasks.
     *
     * @return {@code true} if core threads are allowed to time out,
     *         else {@code false}
     *
     * @since 1.6
     */
    // 获取 allowCoreThreadTimeOut
    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    /**
     * Sets the policy governing whether core threads may time out and
     * terminate if no tasks arrive within the keep-alive time, being
     * replaced if needed when new tasks arrive. When false, core
     * threads are never terminated due to lack of incoming
     * tasks. When true, the same keep-alive policy applying to
     * non-core threads applies also to core threads. To avoid
     * continual thread replacement, the keep-alive time must be
     * greater than zero when setting {@code true}. This method
     * should in general be called before the pool is actively used.
     *
     * @param value {@code true} if should time out, else {@code false}
     * @throws IllegalArgumentException if value is {@code true}
     *         and the current keep-alive time is not greater than zero
     *
     * @since 1.6
     */
    // 空闲线程超时机制是否应用到核心线程
    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveTime <= 0)
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            // 假如是 true，需要中断线程，因为核心线程可能在 获取阻塞队列的任务时 被阻塞了
            if (value)
                interruptIdleWorkers();
        }
    }

    /**
     * Sets the maximum allowed number of threads. This overrides any
     * value set in the constructor. If the new value is smaller than
     * the current value, excess existing threads will be
     * terminated when they next become idle.
     *
     * @param maximumPoolSize the new maximum
     * @throws IllegalArgumentException if the new maximum is
     *         less than or equal to zero, or
     *         less than the {@linkplain #getCorePoolSize core pool size}
     * @see #getMaximumPoolSize
     */
    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        if (workerCountOf(ctl.get()) > maximumPoolSize)
            interruptIdleWorkers();
    }

    /**
     * Returns the maximum allowed number of threads.
     *
     * @return the maximum allowed number of threads
     * @see #setMaximumPoolSize
     */
    // maximumPoolSize
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    /**
     * Sets the time limit for which threads may remain idle before
     * being terminated.  If there are more than the core number of
     * threads currently in the pool, after waiting this amount of
     * time without processing a task, excess threads will be
     * terminated.  This overrides any value set in the constructor.
     *
     * @param time the time to wait.  A time value of zero will cause
     *        excess threads to terminate immediately after executing tasks.
     * @param unit the time unit of the {@code time} argument
     * @throws IllegalArgumentException if {@code time} less than zero or
     *         if {@code time} is zero and {@code allowsCoreThreadTimeOut}
     * @see #getKeepAliveTime(TimeUnit)
     */
    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0)
            throw new IllegalArgumentException();
        if (time == 0 && allowsCoreThreadTimeOut())
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        long keepAliveTime = unit.toNanos(time);
        long delta = keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        if (delta < 0)
            interruptIdleWorkers();
    }

    /**
     * Returns the thread keep-alive time, which is the amount of time
     * that threads in excess of the core pool size may remain
     * idle before being terminated.
     *
     * @param unit the desired time unit of the result
     * @return the time limit
     * @see #setKeepAliveTime(long, TimeUnit)
     */
    // 超时时间
    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
    }

    /* User-level queue utilities */

    /**
     * Returns the task queue used by this executor. Access to the
     * task queue is intended primarily for debugging and monitoring.
     * This queue may be in active use.  Retrieving the task queue
     * does not prevent queued tasks from executing.
     *
     * @return the task queue
     */
    // 获取工作队列
    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    /**
     * Removes this task from the executor's internal queue if it is
     * present, thus causing it not to be run if it has not already
     * started.
     *
     * <p>This method may be useful as one part of a cancellation
     * scheme.  It may fail to remove tasks that have been converted
     * into other forms before being placed on the internal queue. For
     * example, a task entered using {@code submit} might be
     * converted into a form that maintains {@code Future} status.
     * However, in such cases, method {@link #purge} may be used to
     * remove those Futures that have been cancelled.
     *
     * @param task the task to remove
     * @return {@code true} if the task was removed
     */
    /*
     * 如果存在此任务，则将其从执行器的内部队列中删除，从而导致尚未启动的任务无法运行
     * 此方法作为取消方案的一部分可能有用。
     * 它可能无法删除在放入内部队列之前已转换为其他表单的任务。
     * 例如，使用{@code submit}输入的任务可能会转换为保持{@code Future}状态的表单。
     * 然而，在这种情况下，可以使用方法{@link#purge}删除那些已取消的期货。
     */
    public boolean remove(Runnable task) {
        boolean removed = workQueue.remove(task);
        tryTerminate(); // In case SHUTDOWN and now empty
        return removed;
    }

    /**
     * Tries to remove from the work queue all {@link Future}
     * tasks that have been cancelled. This method can be useful as a
     * storage reclamation operation, that has no other impact on
     * functionality. Cancelled tasks are never executed, but may
     * accumulate in work queues until worker threads can actively
     * remove them. Invoking this method instead tries to remove them now.
     * However, this method may fail to remove tasks in
     * the presence of interference by other threads.
     */
    /*
     * 尝试从工作队列中删除所有已取消的{@link Future}任务。
     * 此方法可以用作存储回收操作，对功能没有其他影响。
     * 取消的任务永远不会执行，但可能会累积在工作队列中，直到工作线程可以主动删除它们。
     * 现在调用此方法会尝试删除它们。但是，在存在其他线程的干扰时，此方法可能无法删除任务。
     */
    public void purge() {
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            while (it.hasNext()) {
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    it.remove();
            }
        } catch (ConcurrentModificationException fallThrough) {
            // Take slow path if we encounter interference during traversal.
            // Make copy for traversal and call remove for cancelled entries.
            // The slow path is more likely to be O(N*N).
            for (Object r : q.toArray())
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    q.remove(r);
        }

        tryTerminate(); // In case SHUTDOWN and now empty
    }

    /* Statistics */

    /**
     * Returns the current number of threads in the pool.
     *
     * @return the number of threads
     */
    // 获取当前线程池工作线程的个数
    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // Remove rare and surprising possibility of
            // isTerminated() && getPoolSize() > 0
            return runStateAtLeast(ctl.get(), TIDYING) ? 0
                : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate number of threads that are actively
     * executing tasks.
     *
     * @return the number of threads
     */
    // 返回当前正在执行任务的线程的近似数量。
    public int getActiveCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            int n = 0;
            for (Worker w : workers)
                // isLocked 返回 true，说明该线程正在执行任务，因为执行任务的时候才会加锁
                if (w.isLocked())
                    ++n;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the largest number of threads that have ever
     * simultaneously been in the pool.
     *
     * @return the number of threads
     */
    // 返回池中同时存在的最大线程数。
    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate total number of tasks that have ever been
     * scheduled for execution. Because the states of tasks and
     * threads may change dynamically during computation, the returned
     * value is only an approximation.
     *
     * @return the number of tasks
     */
    // 获取任务的数量，是个近似值，因为 completedTaskCount 只有在Worker 线程退出的时候才会更新
    // 已经执行的任务的数量（近似值）+ 阻塞队列中的任务个数
    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
                if (w.isLocked())
                    ++n;
            }
            return n + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate total number of tasks that have
     * completed execution. Because the states of tasks and threads
     * may change dynamically during computation, the returned value
     * is only an approximation, but one that does not ever decrease
     * across successive calls.
     *
     * @return the number of tasks
     */
    // 返回执行任务的近似值，由于任务和线程的状态在计算过程中可能会动态变化，因此返回的值只是一个近似值，但不会在连续调用中减少。
    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // completedTaskCount 只会在Worker 线程退出时，才会累加（processWorkerExit 中）
            long n = completedTaskCount;
            for (Worker w : workers)
                n += w.completedTasks;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns a string identifying this pool, as well as its state,
     * including indications of run state and estimated worker and
     * task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        long ncompleted;
        int nworkers, nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked())
                    ++nactive;
            }
        } finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String rs = (runStateLessThan(c, SHUTDOWN) ? "Running" :
                     (runStateAtLeast(c, TERMINATED) ? "Terminated" :
                      "Shutting down"));
        return super.toString() +
            "[" + rs +
            ", pool size = " + nworkers +
            ", active threads = " + nactive +
            ", queued tasks = " + workQueue.size() +
            ", completed tasks = " + ncompleted +
            "]";
    }

    /* Extension hooks */

    /**
     * Method invoked prior to executing the given Runnable in the
     * given thread.  This method is invoked by thread {@code t} that
     * will execute task {@code r}, and may be used to re-initialize
     * ThreadLocals, or to perform logging.
     *
     * <p>This implementation does nothing, but may be customized in
     * subclasses. Note: To properly nest multiple overridings, subclasses
     * should generally invoke {@code super.beforeExecute} at the end of
     * this method.
     *
     * @param t the thread that will run task {@code r}
     * @param r the task that will be executed
     */
    protected void beforeExecute(Thread t, Runnable r) { }

    /**
     * Method invoked upon completion of execution of the given Runnable.
     * This method is invoked by the thread that executed the task. If
     * non-null, the Throwable is the uncaught {@code RuntimeException}
     * or {@code Error} that caused execution to terminate abruptly.
     *
     * <p>This implementation does nothing, but may be customized in
     * subclasses. Note: To properly nest multiple overridings, subclasses
     * should generally invoke {@code super.afterExecute} at the
     * beginning of this method.
     *
     * <p><b>Note:</b> When actions are enclosed in tasks (such as
     * {@link FutureTask}) either explicitly or via methods such as
     * {@code submit}, these task objects catch and maintain
     * computational exceptions, and so they do not cause abrupt
     * termination, and the internal exceptions are <em>not</em>
     * passed to this method. If you would like to trap both kinds of
     * failures in this method, you can further probe for such cases,
     * as in this sample subclass that prints either the direct cause
     * or the underlying exception if a task has been aborted:
     *
     *  <pre> {@code
     * class ExtendedExecutor extends ThreadPoolExecutor {
     *   // ...
     *   protected void afterExecute(Runnable r, Throwable t) {
     *     super.afterExecute(r, t);
     *     if (t == null && r instanceof Future<?>) {
     *       try {
     *         Object result = ((Future<?>) r).get();
     *       } catch (CancellationException ce) {
     *           t = ce;
     *       } catch (ExecutionException ee) {
     *           t = ee.getCause();
     *       } catch (InterruptedException ie) {
     *           Thread.currentThread().interrupt(); // ignore/reset
     *       }
     *     }
     *     if (t != null)
     *       System.out.println(t);
     *   }
     * }}</pre>
     *
     * @param r the runnable that has completed
     * @param t the exception that caused termination, or null if
     * execution completed normally
     */
    protected void afterExecute(Runnable r, Throwable t) { }

    /**
     * Method invoked when the Executor has terminated.  Default
     * implementation does nothing. Note: To properly nest multiple
     * overridings, subclasses should generally invoke
     * {@code super.terminated} within this method.
     */
    protected void terminated() { }

    /* Predefined RejectedExecutionHandlers */

    /**
     * A handler for rejected tasks that runs the rejected task
     * directly in the calling thread of the {@code execute} method,
     * unless the executor has been shut down, in which case the task
     * is discarded.
     */
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code CallerRunsPolicy}.
         */
        public CallerRunsPolicy() { }

        /**
         * Executes task r in the caller's thread, unless the executor
         * has been shut down, in which case the task is discarded.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    /**
     * A handler for rejected tasks that throws a
     * {@code RejectedExecutionException}.
     */
    public static class AbortPolicy implements RejectedExecutionHandler {
        /**
         * Creates an {@code AbortPolicy}.
         */
        public AbortPolicy() { }

        /**
         * Always throws RejectedExecutionException.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         * @throws RejectedExecutionException always
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            throw new RejectedExecutionException("Task " + r.toString() +
                                                 " rejected from " +
                                                 e.toString());
        }
    }

    /**
     * A handler for rejected tasks that silently discards the
     * rejected task.
     */
    public static class DiscardPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardPolicy}.
         */
        public DiscardPolicy() { }

        /**
         * Does nothing, which has the effect of discarding task r.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }

    /**
     * A handler for rejected tasks that discards the oldest unhandled
     * request and then retries {@code execute}, unless the executor
     * is shut down, in which case the task is discarded.
     */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardOldestPolicy} for the given executor.
         */
        public DiscardOldestPolicy() { }

        /**
         * Obtains and ignores the next task that the executor
         * would otherwise execute, if one is immediately available,
         * and then retries execution of task r, unless the executor
         * is shut down, in which case task r is instead discarded.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}
