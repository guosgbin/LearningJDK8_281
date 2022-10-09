package test.java.util.concurrent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

public class ExecutorCompletionServiceTest {

    /**
     * 提交一个待执行的任务列表，依次使用它们异步执行的结果
     */
    void solve(Executor e, Collection<Callable<Result>> solvers)
            throws InterruptedException, ExecutionException {
        CompletionService<Result> ecs = new ExecutorCompletionService<>(e);
        // 提交所有要执行的 Callable 任务
        for (Callable<Result> s : solvers) {
            ecs.submit(s);
        }
        // 总共要执行任务的个数
        int n = solvers.size();
        // 依次获取 ExecutorCompletionService 的内部队列中已完成的任务的结果
        for (int i = 0; i < n; ++i) {
            Result r = ecs.take().get();
            if (r != null) {
                // 使用异步执行的结果
                use(r);
            }
        }
    }

    void use(Result result) {
        System.out.println("使用 result..." + result);
    }

    static class Result {

    }

    /**
     * 提交一批任务，获取其中最先完成的任务的 Future，获取它的结果并使用，取消其他任务的执行
     */
    void solve2(Executor e, Collection<Callable<Result>> solvers)
            throws InterruptedException {
        CompletionService<Result> ecs = new ExecutorCompletionService<>(e);
        int n = solvers.size();
        //
        List<Future<Result>> futures = new ArrayList<>(n);
        Result result = null;
        try {
            // 提交所有要执行的 Callable 任务，并将它们的 Future 对象添加到集合中
            for (Callable<Result> s : solvers) {
                futures.add(ecs.submit(s));
            }
            // 这个 for 循环其实就是获取 ExecutorCompletionService 内部队列的队首的元素，
            // 只要第一个完成的任务的 Future 不是 null 就退出循环
            for (int i = 0; i < n; ++i) {
                try {
                    Result r = ecs.take().get();
                    if (r != null) {
                        result = r;
                        break;
                    }
                } catch (ExecutionException ignore) {
                }
            }
        } finally {
            // 取消所有任务
            for (Future<Result> f : futures)
                f.cancel(true);
        }
        if (result != null)
            use(result);
    }

}
