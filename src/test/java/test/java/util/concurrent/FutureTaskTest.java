package test.java.util.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

public class FutureTaskTest {

    /**
     * 测试 Runnable 执行
     */
    @Test
    public void test01() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("任务执行...");
            }
        }, "ThreadA").start();
    }

    /**
     * 测试 Callable 执行
     */
    @Test
    public void test02() throws ExecutionException, InterruptedException {
        Callable<String> task = new Callable<String>() {
            @Override
            public String call() throws Exception {
                System.out.println("任务执行...");
                return "我是返回结果";
            }
        };
        FutureTask<String> future = new FutureTask<>(task);
        // 执行任务
        Executors.newSingleThreadExecutor().execute(future);
        String result = future.get();
        System.out.println(result);
    }
}
