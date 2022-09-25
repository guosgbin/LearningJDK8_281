package test.java.util.concurrent;


import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

public class PhaserTest {

    /**
     * 可以使用 Phaser 代替 CountDownLatch 来作为开关，多个线程在某个时刻同时执行
     * 典型的习惯用法是将方法设置为首先注册，然后启动操作，然后取消注册，
     * 官方的案例
     * 模拟几个人做饭
     */
    @Test
    public void test01() {
        Runnable task1 = () -> {
            System.out.println("晓明在煮饭..." + System.currentTimeMillis());
        };
        Runnable task2 = () -> {
            System.out.println("冈冈在洗菜..." + System.currentTimeMillis());
        };
        Runnable task3 = () -> {
            System.out.println("炸雷在煲汤..." + System.currentTimeMillis());
        };
        List<Runnable> tasks = new ArrayList<>(Arrays.asList(task1, task2, task3));
        runTasks(tasks);
        System.out.println("完毕" + System.currentTimeMillis());
    }

    /**
     * 执行任务
     */
    void runTasks(List<Runnable> tasks) {
        final Phaser phaser = new Phaser(1); // "1" to register self
        // create and start threads
        for (final Runnable task : tasks) {
            phaser.register();
            new Thread(() -> {
                phaser.arriveAndAwaitAdvance(); // await all creation
                task.run();
            }).start();
        }
        System.out.println("为了更明显，这里等待 1 秒...");
        sleep(TimeUnit.SECONDS, 1);
        // 等待所有线程准备就绪，就继续向下执行
        phaser.arriveAndDeregister();
    }

    public void sleep(TimeUnit timeUnit, long times) {
        try {
            timeUnit.sleep(times);
        } catch (InterruptedException ignored) {
        }
    }


    /**
     * 使一组线程重复执行给定次数的操作的一种方法是覆盖onAdvance
     */
    @Test
    public void test02() {
        Runnable task1 = () -> {
            System.out.println("晓明在煮饭..." + System.currentTimeMillis());
        };
        Runnable task2 = () -> {
            System.out.println("冈冈在洗菜..." + System.currentTimeMillis());
        };
        Runnable task3 = () -> {
            System.out.println("炸雷在煲汤..." + System.currentTimeMillis());
        };
        List<Runnable> tasks = new ArrayList<>(Arrays.asList(task1, task2, task3));
        // 重复执行 3 次
        startTasks(tasks, 3);
    }

    void startTasks(List<Runnable> tasks, final int iterations) {
        final Phaser phaser = new Phaser() {
            // 返回 true 表示需要停止 phaser
            protected boolean onAdvance(int phase, int registeredParties) {
                System.out.printf("=====[phase=%s]=====[parties=%s]=====\n", phase, registeredParties);
                return phase >= iterations - 1 || registeredParties == 0;
            }
        };
        phaser.register();
        for (final Runnable task : tasks) {
            phaser.register();
            new Thread(() -> {
                while (!phaser.isTerminated()) {
                    task.run();
                    phaser.arriveAndAwaitAdvance();
                }
            }).start();
        }
        phaser.arriveAndDeregister(); // deregister self, don't wait
    }

//    要使用 Phaser
//    树创建一组n任务，您可以使用以下形式的代码，
//    假设 Task
//    类的构造函数接受它在构造时注册的Phaser 。
//
//    在调用build(new Task[n], 0,n, new Phaser())之后，可以启动这些任务，例如通过提交到池：
//            TASKS_PER_PHASER的最佳值主要取决于预期的同步速率。对于极小的每阶段任务主体（因此高速率），低至四的值可能适合，对于非常大的任务主体，可能适合高达数百。
//            实施说明：此实施将参与方的最大数量限制为 65535。尝试注册其他参与方会导致IllegalStateException 。但是，您可以并且应该创建分层移相器以容纳任意大的参与者集
    @Test
    public void test03() {
        int iterations = 3;
        Phaser phaser = new Phaser() {
            @Override
            protected boolean onAdvance(int phase, int registeredParties) {
                System.out.printf("=====[phase=%s]=====[parties=%s]=====\n", phase, registeredParties);
                return phase >= iterations - 1 || registeredParties == 0;
            }
        };

        Task[] tasks = new Task[10];
        build(tasks, 0, tasks.length, phaser);
        for (int i = 0; i < tasks.length; i++) {          // 执行任务
            Thread thread = new Thread(tasks[i]);
            thread.start();
        }
    }

    // 每个 Phaser 最多三个参与者
    final int TASKS_PER_PHASER = 3;

    /**
     * 每个 Phaser 最多分 3 个任务
     */
    void build(Task[] tasks, int lo, int hi, Phaser phaser) {
        if (hi - lo < TASKS_PER_PHASER) {
            for (int k = lo; k < hi; ++k)
                tasks[k] = new Task(phaser);
        } else {
            for (int i = lo; i < hi; i += TASKS_PER_PHASER) {
                int j = Math.min(i + TASKS_PER_PHASER, hi);
                Phaser subPhaser = new Phaser(phaser);
                for (int k = i; k < j; ++k)
                    tasks[k] = new Task(subPhaser);         // assumes new Task(ph) performs ph.register()
            }
        }

    }


//    /**
//     * 每个 Phaser 最多分 3 个任务
//     */
//    void build(Task[] tasks, int lo, int hi, Phaser phaser) {
//        if (hi - lo > TASKS_PER_PHASER) {
//            for (int i = lo; i < hi; i += TASKS_PER_PHASER) {
//                int j = Math.min(i + TASKS_PER_PHASER, hi);
//                // 递归创建子 Phaser
//                build(tasks, i, j, new Phaser(phaser));
//            }
//        } else {
//            for (int i = lo; i < hi; ++i)
//                tasks[i] = new Task(phaser);         // assumes new Task(ph) performs ph.register()
//        }
//    }

    class Task implements Runnable {
        private final Phaser phaser;

        Task(Phaser phaser) {
            this.phaser = phaser;
            this.phaser.register();
        }

        @Override
        public void run() {
            while (!phaser.isTerminated()) {   //只要Phaser没有终止, 各个线程的任务就会一直执行
                int i = phaser.arriveAndAwaitAdvance();     // 等待其它参与者线程到达
                // do something
                System.out.println(Thread.currentThread().getName() + ": 执行完任务");
            }
        }
    }

}























