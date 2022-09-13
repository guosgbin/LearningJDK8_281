package test.java.util.concurrent.locks;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 经典使用案例
 * <p>
 * 主要是两个
 * 1.
 */
public class CountDownLatchTest {
    private static final int TASK_COUNT = 10;
    private static final Random random = new Random();

    /**
     * 主线程等待所有子线程任务完成
     */
    @Test
    public void test01() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(TASK_COUNT);
        for (int i = 0; i < 10; i++) {
            new Thread(new WorkerRunnable(i, latch), "线程" + i).start();
        }
        System.out.println("主线程等待所有子任务完成");
        long start = System.currentTimeMillis();
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("所有子任务完成...，耗时：" + (end - start));
    }


    /**
     * 让子任务同时开启，主线程等待所有子任务执行完毕
     */
    @Test
    public void test02() throws InterruptedException {
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch endSignal = new CountDownLatch(10);
        for (int i = 0; i < 10; i++) {
            new Thread(new Worker(i, startSignal, endSignal), "线程" + i).start();
        }

        TimeUnit.SECONDS.sleep(1);

        System.out.println("所有子栏删都开启...");
        startSignal.countDown();

        System.out.println("等待子线程结束");
        long start = System.currentTimeMillis();
        endSignal.await();
        long end = System.currentTimeMillis();
        System.out.println("所有子任务已经运行结束，耗时：" + (end - start));

    }

    static class Worker implements Runnable {
        private int taskId;
        private CountDownLatch startSignal;
        private CountDownLatch endSignal;

        public Worker(int taskId, CountDownLatch startSignal, CountDownLatch endSignal) {
            this.taskId = taskId;
            this.startSignal = startSignal;
            this.endSignal = endSignal;
        }

        @Override
        public void run() {
            try {
                int time = random.nextInt(10);
                System.out.printf("%s 任务 id：%s，准备执行任务..., 该任务需耗时： %s \n", Thread.currentThread().getName(), taskId, time);

                startSignal.await();

                TimeUnit.SECONDS.sleep(time);
                System.out.printf("任务 id：%s，执行任务完成...\n", taskId);
            } catch (InterruptedException ignored) {
            } finally {
                endSignal.countDown();
            }
        }
    }

    static class WorkerRunnable implements Runnable {
        private int taskId;
        private CountDownLatch latch;

        public WorkerRunnable(int taskId, CountDownLatch latch) {
            this.taskId = taskId;
            this.latch = latch;
        }

        @Override
        public void run() {
            try {
                int time = random.nextInt(10);
                System.out.printf("%s 任务 id：%s，正在执行任务中..., 需耗时： %s \n", Thread.currentThread().getName(), taskId, time);
                TimeUnit.SECONDS.sleep(time);
                System.out.printf("任务 id：%s，执行任务完成...\n", taskId);
            } catch (InterruptedException ignored) {
            } finally {
                latch.countDown();
            }

        }
    }
}
