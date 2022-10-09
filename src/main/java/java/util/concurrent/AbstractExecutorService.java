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
import java.util.*;

/**
 * Provides default implementations of {@link ExecutorService}
 * execution methods. This class implements the {@code submit},
 * {@code invokeAny} and {@code invokeAll} methods using a
 * {@link RunnableFuture} returned by {@code newTaskFor}, which defaults
 * to the {@link FutureTask} class provided in this package.  For example,
 * the implementation of {@code submit(Runnable)} creates an
 * associated {@code RunnableFuture} that is executed and
 * returned. Subclasses may override the {@code newTaskFor} methods
 * to return {@code RunnableFuture} implementations other than
 * {@code FutureTask}.
 *
 * <p><b>Extension example</b>. Here is a sketch of a class
 * that customizes {@link ThreadPoolExecutor} to use
 * a {@code CustomTask} class instead of the default {@code FutureTask}:
 *  <pre> {@code
 * public class CustomThreadPoolExecutor extends ThreadPoolExecutor {
 *
 *   static class CustomTask<V> implements RunnableFuture<V> {...}
 *
 *   protected <V> RunnableFuture<V> newTaskFor(Callable<V> c) {
 *       return new CustomTask<V>(c);
 *   }
 *   protected <V> RunnableFuture<V> newTaskFor(Runnable r, V v) {
 *       return new CustomTask<V>(r, v);
 *   }
 *   // ... add constructors, etc.
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */

/**
 * 提供ExecutorService执行方法的默认实现。
 * 此类使用newTaskFor返回的RunnableFuture实现submit，invokeAny和invokeAll方法，
 * 默认为本包中提供的FutureTask类。
 * 例如， submit(Runnable)的实现创建了一个关联的RunnableFuture ，该 RunnableFuture 被执行并返回。
 * 子类可以覆盖newTaskFor方法以返回FutureTask以外的RunnableFuture实现。
 */
public abstract class AbstractExecutorService implements ExecutorService {

    /**
     * Returns a {@code RunnableFuture} for the given runnable and default
     * value.
     *
     * @param runnable the runnable task being wrapped
     * @param value the default value for the returned future
     * @param <T> the type of the given value
     * @return a {@code RunnableFuture} which, when run, will run the
     * underlying runnable and which, as a {@code Future}, will yield
     * the given value as its result and provide for cancellation of
     * the underlying task
     * @since 1.6
     */
    // 通过传入 Runnable 返回 RunnableFuture
    protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
        return new FutureTask<T>(runnable, value);
    }

    /**
     * Returns a {@code RunnableFuture} for the given callable task.
     *
     * @param callable the callable task being wrapped
     * @param <T> the type of the callable's result
     * @return a {@code RunnableFuture} which, when run, will call the
     * underlying callable and which, as a {@code Future}, will yield
     * the callable's result as its result and provide for
     * cancellation of the underlying task
     * @since 1.6
     */
    // 通过传入 Callable 返回 RunnableFuture
    protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
        return new FutureTask<T>(callable);
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    // 提交一个 Runnable 任务
    public Future<?> submit(Runnable task) {
        if (task == null) throw new NullPointerException();
        RunnableFuture<Void> ftask = newTaskFor(task, null);
        execute(ftask);
        return ftask;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    // 提交一个 Runnable 任务，任务执行成功后返回传入值 result
    public <T> Future<T> submit(Runnable task, T result) {
        if (task == null) throw new NullPointerException();
        RunnableFuture<T> ftask = newTaskFor(task, result);
        execute(ftask);
        return ftask;
    }

    /**
     * @throws RejectedExecutionException {@inheritDoc}
     * @throws NullPointerException       {@inheritDoc}
     */
    // 提交一个 Callable 任务
    public <T> Future<T> submit(Callable<T> task) {
        if (task == null) throw new NullPointerException();
        RunnableFuture<T> ftask = newTaskFor(task);
        execute(ftask);
        return ftask;
    }

    /**
     * the main mechanics of invokeAny.
     */
    private <T> T doInvokeAny(Collection<? extends Callable<T>> tasks,
                              boolean timed, long nanos)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (tasks == null)
            throw new NullPointerException();
        // 剩余任务数，初始值是任务总数
        int ntasks = tasks.size();
        if (ntasks == 0)
            throw new IllegalArgumentException();
        // 任务返回的 Future 列表
        ArrayList<Future<T>> futures = new ArrayList<Future<T>>(ntasks);
        ExecutorCompletionService<T> ecs =
            new ExecutorCompletionService<T>(this);

        // For efficiency, especially in executors with limited
        // parallelism, check to see if previously submitted tasks are
        // done before submitting more of them. This interleaving
        // plus the exception mechanics account for messiness of main
        // loop.

        // 为了提高效率，特别是在并行度有限的执行器中，
        // 请在提交更多任务之前检查以前提交的任务是否已完成。
        // 这种交错加上异常机制解释了主循环的混乱。

        try {
            // Record exceptions so that if we fail to obtain any
            // result, we can throw the last exception we got.
            // 记录异常，以便如果我们无法获得任何结果，我们可以抛出最后一个异常。
            ExecutionException ee = null;
            // 计算任务的截止时间
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            Iterator<? extends Callable<T>> it = tasks.iterator();

            // Start one task for sure; the rest incrementally
            // 一定要开始一项任务；其余部分递增
            futures.add(ecs.submit(it.next()));
            --ntasks;
            // 记录正在执行的任务数量
            int active = 1;

            // 每次提交一个任务，都会重新检查 ecs 在是否有任务已经完成了
            for (;;) {
                // 尝试获取并移除 ecs 的队首元素
                Future<T> f = ecs.poll();
                // 条件成立，说明还没有任何一个任务完成
                if (f == null) {
                    // 如果还有剩余的任务，则提交下一个任务
                    if (ntasks > 0) {
                        --ntasks;
                        // 提交一个任务
                        futures.add(ecs.submit(it.next()));
                        ++active;
                    }
                    // 前置条件：ntasks <= 0 说明所有任务都已经提交到执行器了
                    // active == 0 说明任务出现异常了
                    // 捕获到了异常，则跳出主循环，进行异常的抛出
                    else if (active == 0)
                    {
                        System.out.println("都出现异常了.............");
                        break;
                    }
                    // 前置条件 ntasks <= 0 && active != 0 说明有任务在执行
                    // 添加成立，说明当前支持超时
                    else if (timed) {
                        // 获取 ExecutorCompletionService 的队首元素
                        f = ecs.poll(nanos, TimeUnit.NANOSECONDS);
                        // f == null 说明没有任务执行完，也没有取消，也没有异常，说明执行超时了
                        if (f == null)
                            throw new TimeoutException();
                        // 重新计算超时时间
                        nanos = deadline - System.nanoTime();
                    }
                    // 前置条件：ntasks <= 0 && active != 0 且不支持超时，说明有任务在执行，需要等待
                    else
                        // 阻塞等待获取队首元素
                        f = ecs.take();
                }
                // f!=null 说明有任务执行完了(包括出现异常)
                if (f != null) {
                    --active;
                    try {
                        return f.get();
                    } catch (ExecutionException eex) {
                        ee = eex;
                    } catch (RuntimeException rex) {
                        ee = new ExecutionException(rex);
                    }
                }
            }

            if (ee == null)
                ee = new ExecutionException();
            throw ee;

        } finally {
            // 取消任务
            for (int i = 0, size = futures.size(); i < size; i++)
                futures.get(i).cancel(true);
        }
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
        throws InterruptedException, ExecutionException {
        try {
            return doInvokeAny(tasks, false, 0);
        } catch (TimeoutException cannotHappen) {
            assert false;
            return null;
        }
    }

    public <T> T invokeAny(Collection<? extends Callable<T>> tasks,
                           long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        return doInvokeAny(tasks, true, unit.toNanos(timeout));
    }

    /**
     * 执行所有任务
     */
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks)
        throws InterruptedException {
        if (tasks == null)
            throw new NullPointerException();
        ArrayList<Future<T>> futures = new ArrayList<Future<T>>(tasks.size());
        boolean done = false;
        try {
            // 循环将 RunnableFuture 添加到列表，依次执行任务
            for (Callable<T> t : tasks) {
                RunnableFuture<T> f = newTaskFor(t);
                futures.add(f);
                execute(f);
            }
            // 循环获取任务的执行结果
            for (int i = 0, size = futures.size(); i < size; i++) {
                Future<T> f = futures.get(i);
                // 假如某个任务未完成，则阻塞直到任务完成
                if (!f.isDone()) {
                    try {
                        f.get();
                    } catch (CancellationException ignore) { // 忽略了一些异常
                    } catch (ExecutionException ignore) {
                    }
                }
            }
            // 当所有任务已经完成了（不管是正常完成还是异常完成，
            // 如发生CancellationException、ExecutionException ），
            // 则将完成标志设为true，并返回结果集合
            done = true;
            return futures;
        } finally {
            // 假如在 done 赋值为 true 之前出现异常了，则取消所有任务
            if (!done)
                for (int i = 0, size = futures.size(); i < size; i++)
                    futures.get(i).cancel(true);
        }
    }

    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks,
                                         long timeout, TimeUnit unit)
        throws InterruptedException {
        if (tasks == null)
            throw new NullPointerException();
        long nanos = unit.toNanos(timeout);
        ArrayList<Future<T>> futures = new ArrayList<Future<T>>(tasks.size());
        boolean done = false;
        try {
            for (Callable<T> t : tasks)
                futures.add(newTaskFor(t));

            final long deadline = System.nanoTime() + nanos;
            final int size = futures.size();

            // Interleave time checks and calls to execute in case
            // executor doesn't have any/much parallelism.
            for (int i = 0; i < size; i++) {
                execute((Runnable)futures.get(i));
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L)
                    return futures;
            }

            for (int i = 0; i < size; i++) {
                Future<T> f = futures.get(i);
                if (!f.isDone()) {
                    if (nanos <= 0L)
                        return futures;
                    try {
                        f.get(nanos, TimeUnit.NANOSECONDS);
                    } catch (CancellationException ignore) {
                    } catch (ExecutionException ignore) {
                    } catch (TimeoutException toe) {
                        return futures;
                    }
                    nanos = deadline - System.nanoTime();
                }
            }
            done = true;
            return futures;
        } finally {
            if (!done)
                for (int i = 0, size = futures.size(); i < size; i++)
                    futures.get(i).cancel(true);
        }
    }

}
