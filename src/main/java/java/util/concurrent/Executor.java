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

/**
 * An object that executes submitted {@link Runnable} tasks. This
 * interface provides a way of decoupling task submission from the
 * mechanics of how each task will be run, including details of thread
 * use, scheduling, etc.  An {@code Executor} is normally used
 * instead of explicitly creating threads. For example, rather than
 * invoking {@code new Thread(new(RunnableTask())).start()} for each
 * of a set of tasks, you might use:
 *
 * <pre>
 * Executor executor = <em>anExecutor</em>;
 * executor.execute(new RunnableTask1());
 * executor.execute(new RunnableTask2());
 * ...
 * </pre>
 *
 * However, the {@code Executor} interface does not strictly
 * require that execution be asynchronous. In the simplest case, an
 * executor can run the submitted task immediately in the caller's
 * thread:
 *
 *  <pre> {@code
 * class DirectExecutor implements Executor {
 *   public void execute(Runnable r) {
 *     r.run();
 *   }
 * }}</pre>
 *
 * More typically, tasks are executed in some thread other
 * than the caller's thread.  The executor below spawns a new thread
 * for each task.
 *
 *  <pre> {@code
 * class ThreadPerTaskExecutor implements Executor {
 *   public void execute(Runnable r) {
 *     new Thread(r).start();
 *   }
 * }}</pre>
 *
 * Many {@code Executor} implementations impose some sort of
 * limitation on how and when tasks are scheduled.  The executor below
 * serializes the submission of tasks to a second executor,
 * illustrating a composite executor.
 *
 *  <pre> {@code
 * class SerialExecutor implements Executor {
 *   final Queue<Runnable> tasks = new ArrayDeque<Runnable>();
 *   final Executor executor;
 *   Runnable active;
 *
 *   SerialExecutor(Executor executor) {
 *     this.executor = executor;
 *   }
 *
 *   public synchronized void execute(final Runnable r) {
 *     tasks.offer(new Runnable() {
 *       public void run() {
 *         try {
 *           r.run();
 *         } finally {
 *           scheduleNext();
 *         }
 *       }
 *     });
 *     if (active == null) {
 *       scheduleNext();
 *     }
 *   }
 *
 *   protected synchronized void scheduleNext() {
 *     if ((active = tasks.poll()) != null) {
 *       executor.execute(active);
 *     }
 *   }
 * }}</pre>
 *
 * The {@code Executor} implementations provided in this package
 * implement {@link ExecutorService}, which is a more extensive
 * interface.  The {@link ThreadPoolExecutor} class provides an
 * extensible thread pool implementation. The {@link Executors} class
 * provides convenient factory methods for these Executors.
 *
 * <p>Memory consistency effects: Actions in a thread prior to
 * submitting a {@code Runnable} object to an {@code Executor}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * its execution begins, perhaps in another thread.
 *
 * @since 1.5
 * @author Doug Lea
 */

/*
 * 执行提交的Runnable任务的对象。
 * 该接口提供了一种将任务提交与每个任务将如何运行的机制解耦的方法，包括线程使用、调度等的细节。
 * 通常使用Executor而不是显式创建线程。
 * 例如，不是为一组任务中的每一个调用new Thread(new(RunnableTask())).start() ，
 * 您可以使用：
 *    Executor executor = anExecutor;
 *    executor.execute(new RunnableTask1());
 *    executor.execute(new RunnableTask2());
 *    ...
 *
 * 但是， Executor接口并不严格要求执行是异步的。
 * 在最简单的情况下，执行者可以立即在调用者的线程中运行提交的任务：
 *   class DirectExecutor implements Executor {
 *      public void execute(Runnable r) {
 *          r.run();
 *      }
 *   }
 *
 * 更典型的是，任务在调用者线程之外的某个线程中执行。下面的执行器为每个任务生成一个新线程。
 *   class ThreadPerTaskExecutor implements Executor {
 *      public void execute(Runnable r) {
 *          new Thread(r).start();
 *      }
 *   }
 *
 * 许多Executor实现对任务的调度方式和时间施加了某种限制。
 * 下面的 executor 将任务提交到第二个 executor 序列化，说明了一个复合 executor。
 *
 * class SerialExecutor implements Executor {
 *     final Queue<Runnable> tasks = new ArrayDeque<Runnable>();
 *     final Executor executor;
 *     Runnable active;
 *
 *     SerialExecutor(Executor executor) {
 *         this.executor = executor;
 *     }
 *
 *     public synchronized void execute(final Runnable r) {
 *         tasks.offer(new Runnable() {
 *             public void run() {
 *                 try {
 *                     r.run();
 *                 } finally {
 *                     scheduleNext();
 *                 }
 *             }
 *         });
 *         if (active == null) {
 *             scheduleNext();
 *         }
 *     }
 *
 *     protected synchronized void scheduleNext() {
 *         if ((active = tasks.poll()) != null) {
 *             executor.execute(active);
 *         }
 *     }
 * }
 */
public interface Executor {

    /**
     * Executes the given command at some time in the future.  The command
     * may execute in a new thread, in a pooled thread, or in the calling
     * thread, at the discretion of the {@code Executor} implementation.
     *
     * @param command the runnable task
     * @throws RejectedExecutionException if this task cannot be
     * accepted for execution
     * @throws NullPointerException if command is null
     */
    // 在将来的某个时间执行给定的命令。
    // 根据Executor实现的决定，该命令可以在新线程、池线程或调用线程中执行。
    void execute(Runnable command);
}
