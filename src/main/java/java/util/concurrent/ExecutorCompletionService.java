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
 * A {@link CompletionService} that uses a supplied {@link Executor}
 * to execute tasks.  This class arranges that submitted tasks are,
 * upon completion, placed on a queue accessible using {@code take}.
 * The class is lightweight enough to be suitable for transient use
 * when processing groups of tasks.
 *
 * <p>
 *
 * <b>Usage Examples.</b>
 *
 * Suppose you have a set of solvers for a certain problem, each
 * returning a value of some type {@code Result}, and would like to
 * run them concurrently, processing the results of each of them that
 * return a non-null value, in some method {@code use(Result r)}. You
 * could write this as:
 *
 * <pre> {@code
 * void solve(Executor e,
 *            Collection<Callable<Result>> solvers)
 *     throws InterruptedException, ExecutionException {
 *     CompletionService<Result> ecs
 *         = new ExecutorCompletionService<Result>(e);
 *     for (Callable<Result> s : solvers)
 *         ecs.submit(s);
 *     int n = solvers.size();
 *     for (int i = 0; i < n; ++i) {
 *         Result r = ecs.take().get();
 *         if (r != null)
 *             use(r);
 *     }
 * }}</pre>
 *
 * Suppose instead that you would like to use the first non-null result
 * of the set of tasks, ignoring any that encounter exceptions,
 * and cancelling all other tasks when the first one is ready:
 *
 * <pre> {@code
 * void solve(Executor e,
 *            Collection<Callable<Result>> solvers)
 *     throws InterruptedException {
 *     CompletionService<Result> ecs
 *         = new ExecutorCompletionService<Result>(e);
 *     int n = solvers.size();
 *     List<Future<Result>> futures
 *         = new ArrayList<Future<Result>>(n);
 *     Result result = null;
 *     try {
 *         for (Callable<Result> s : solvers)
 *             futures.add(ecs.submit(s));
 *         for (int i = 0; i < n; ++i) {
 *             try {
 *                 Result r = ecs.take().get();
 *                 if (r != null) {
 *                     result = r;
 *                     break;
 *                 }
 *             } catch (ExecutionException ignore) {}
 *         }
 *     }
 *     finally {
 *         for (Future<Result> f : futures)
 *             f.cancel(true);
 *     }
 *
 *     if (result != null)
 *         use(result);
 * }}</pre>
 */

/**
 * 这个类主要是里面的 BlockingQueue 队列，
 * QueueingFuture 重写了 FutureTask 钩子方法 done()，每次任务完成/异常/取消 后 都会添加到 BlockingQueue 队列中
 */
public class ExecutorCompletionService<V> implements CompletionService<V> {
    // 委托的执行器 Executor
    private final Executor executor;
    // 抽象的执行器，主要作用是为了调用它的 AbstractExecutorService.newTaskFor
    // 可以为 null
    private final AbstractExecutorService aes;
    // 每个任务执行完或者异常或者取消，都会将任务添加到这个阻塞队列里
    private final BlockingQueue<Future<V>> completionQueue;

    /**
     * FutureTask extension to enqueue upon completion
     */
    // 继承 FutureTask，重写了钩子方法 done
    private class QueueingFuture extends FutureTask<Void> {
        QueueingFuture(RunnableFuture<V> task) {
            super(task, null);
            this.task = task;
        }
        // 在任务完成时，或者异常结束，或者被取消了，之后调用该方法
        protected void done() { completionQueue.add(task); }
        private final Future<V> task;
    }

    /**
     * 获取指定任务封装后的 RunnableFuture
     */
    private RunnableFuture<V> newTaskFor(Callable<V> task) {
        if (aes == null)
            return new FutureTask<V>(task);
        else
            return aes.newTaskFor(task);
    }

    /**
     * 获取指定任务封装后的 RunnableFuture
     */
    private RunnableFuture<V> newTaskFor(Runnable task, V result) {
        if (aes == null)
            return new FutureTask<V>(task, result);
        else
            return aes.newTaskFor(task, result);
    }

    /**
     * Creates an ExecutorCompletionService using the supplied
     * executor for base task execution and a
     * {@link LinkedBlockingQueue} as a completion queue.
     *
     * @param executor the executor to use
     * @throws NullPointerException if executor is {@code null}
     */
    // 创建 ExecutorCompletionService，指定执行器 Executor，创建一个 LinkedBlockingQueue
    public ExecutorCompletionService(Executor executor) {
        if (executor == null)
            throw new NullPointerException();
        this.executor = executor;
        this.aes = (executor instanceof AbstractExecutorService) ?
            (AbstractExecutorService) executor : null;
        this.completionQueue = new LinkedBlockingQueue<Future<V>>();
    }

    /**
     * Creates an ExecutorCompletionService using the supplied
     * executor for base task execution and the supplied queue as its
     * completion queue.
     *
     * @param executor the executor to use
     * @param completionQueue the queue to use as the completion queue
     *        normally one dedicated for use by this service. This
     *        queue is treated as unbounded -- failed attempted
     *        {@code Queue.add} operations for completed tasks cause
     *        them not to be retrievable.
     * @throws NullPointerException if executor or completionQueue are {@code null}
     */
    // 创建 ExecutorCompletionService，指定执行器 Executor 和 LinkedBlockingQueue
    public ExecutorCompletionService(Executor executor,
                                     BlockingQueue<Future<V>> completionQueue) {
        if (executor == null || completionQueue == null)
            throw new NullPointerException();
        this.executor = executor;
        this.aes = (executor instanceof AbstractExecutorService) ?
            (AbstractExecutorService) executor : null;
        this.completionQueue = completionQueue;
    }

    /**
     * 提交一个 Callable 任务
     */
    public Future<V> submit(Callable<V> task) {
        if (task == null) throw new NullPointerException();
        RunnableFuture<V> f = newTaskFor(task);
        // 封装成 QueueingFuture 对象，这个重写了 FutureTask 的钩子方法 done()
        executor.execute(new QueueingFuture(f));
        return f;
    }

    /**
     * 提交一个任务
     */
    public Future<V> submit(Runnable task, V result) {
        if (task == null) throw new NullPointerException();
        RunnableFuture<V> f = newTaskFor(task, result);
        executor.execute(new QueueingFuture(f));
        return f;
    }

    /**
     * 获取队首元素，
     * 假如没有队首元素，则等待元素直到有元素
     */
    public Future<V> take() throws InterruptedException {
        return completionQueue.take();
    }

    /**
     * 获取队首元素，
     * 假如没有队首元素，则返回 null
     */
    public Future<V> poll() {
        return completionQueue.poll();
    }

    /**
     * 获取队首元素，
     * 假如在超时时间到后还没有队首元素，则返回 null
     */
    public Future<V> poll(long timeout, TimeUnit unit)
            throws InterruptedException {
        return completionQueue.poll(timeout, unit);
    }

}
