package test.java.util.concurrent.locks;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

public class SimpleReentrantLockTest {

    @Test
    public void testOne() throws InterruptedException {
        SimpleReentrantLock lock = new SimpleReentrantLock();
        Runnable task = () -> {
            lock.lock();
            try {
                System.out.println(Thread.currentThread().getName() + "跑起来了");
                sleep(1000);
            } finally {
                lock.unlock();
            }
        };

        Thread t1 = new Thread(task, "线程1");
        Thread t2 = new Thread(task, "线程2");
        Thread t3 = new Thread(task, "线程3");

        t1.start();
        t3.start();
        t2.start();

        t1.join();
        t2.join();
        t3.join();

        System.out.println("结束");
    }


    private void sleep(long l) {
        try {
            TimeUnit.MILLISECONDS.sleep(l);
        } catch (InterruptedException ignored) {
        }
    }
}