package test.java.util.concurrent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ThreadPoolExecutorTest {

    @Test
    public void test01() {
        Executors.newSingleThreadExecutor().execute(() -> { /* do something */});
    }

    /**
     * 测试 AbstractExecutorService#doInvokeAny 方法 中  active == 0 的 if 条件
     */
    @Test
    public void test02() throws ExecutionException, InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Callable<Integer>> taskList = new ArrayList<>();
        // 组装任务
        for (int i = 0; i < 5; i++) {
            int finalI = i;
            Callable<Integer> task = () -> {
                try {
                    System.out.println("任务" + finalI + "开始");
                    TimeUnit.SECONDS.sleep(ThreadLocalRandom.current().nextInt(5));
                    System.out.println("任务" + finalI + "结束");
                    if (finalI != 3) {
                        System.out.println("任务抛异常" + finalI);
                        throw new RuntimeException("yichang===" + finalI);
                    } else {
                        //
                    }
                } catch (InterruptedException ignored) {
                    System.out.println("任务" + finalI + "被中端了...");
                }
                return finalI;
            };
            taskList.add(task);
        }
        // 执行任务
        Integer integer = executor.invokeAny(taskList);
        System.out.println("=========  " + integer);
        TimeUnit.SECONDS.sleep(10000);
    }


    /**
     * 自定义拒绝策略
     */
    public class CustomRejectedExecutionHandler implements RejectedExecutionHandler {
        // 共抛弃多少个任务
        private final AtomicLong handlerCount = new AtomicLong(0L);
        // 每抛弃多少个任务报警一次
        private Long period;
        private CustomRejectedExecutionHandler(Long period) {
            this.period = period;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (!executor.isShutdown()) {
                executor.getQueue().poll();
                executor.execute(r);
            }
            try {
                long currentCount = handlerCount.incrementAndGet();
                long model = currentCount % period;
                if (currentCount == 1L || model == 0L) {
                    // 打日志，发送短信或者钉钉......
                }
            } catch (Exception e) {
                // 处理...
            }
        }
    }
}
