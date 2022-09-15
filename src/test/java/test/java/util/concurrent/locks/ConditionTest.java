package test.java.util.concurrent.locks;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 测试 ReentrantLock 的 Condition 用法
 */
public class ConditionTest {

    public void sleep(long time, TimeUnit timeUnit) {
        try {
            timeUnit.sleep(time);
        } catch (InterruptedException ignore) {
        }
    }


    @Test
    public void testCondition() throws InterruptedException {
        // 创建线程1、线程2
        PrintOneThread printOneThread = new PrintOneThread();
        PrintTwoThread printTwoThread = new PrintTwoThread();

        // 启动线程1、线程2，就会看到数字1和2交替打印的效果；实现了线程的控制
        printOneThread.start();
        printTwoThread.start();

        // 主线程等待printOneThread、printTwoThread线程运行结束再往后走
        printOneThread.join();
        printTwoThread.join();

        System.out.println("运行结束");
    }

    public static int value = 1;
    // 声明一个reentrantLock互斥锁
    public static ReentrantLock reentrantLock = new ReentrantLock();
    // reentrantLock创建一个Condition
    public static Condition condition = reentrantLock.newCondition();

    // 线程1：始终打印1的线程
    public static class PrintOneThread extends Thread {
        @Override
        public void run() {
            // 打印10000次1
            for (int i = 0; i < 10000; i++) {
                try {
                    // 由于变量value是非线程安全的，每次操作前需加锁
                    reentrantLock.lock();
                    // 当value的值不是奇数的时候，直接沉睡等待
                    while (value % 2 != 1) {
                        // 调用condition的await方法，释放锁，同时进入沉睡
                        // 等待别人调用singal/singalAll唤醒自己，然后重新获取锁
                        condition.await();
                    }
                    // 走到这里说明value是奇数，并且自己获取了锁
                    System.out.print("1");
                    // 执行value的++操作，
                    value++;
                    // 唤醒调用condition.await而陷入等待的线程；这里就是唤醒线程2
                    condition.signal();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // 释放锁
                    reentrantLock.unlock();
                }

            }
        }
    }

    // 线程2：始终打印2的线程
    public static class PrintTwoThread extends Thread {
        @Override
        public void run() {
            for (int i = 0; i < 10000; i++) {
                try {
                    // 获取独占锁
                    reentrantLock.lock();
                    // 当value的值不是偶数，就直接沉睡等待
                    while (value % 2 != 0) {
                        // 这里调用condition.await沉睡等待，同时释放独占锁
                        // 等待别人调用singal/singalAll唤醒自己，然后自己重新竞争锁
                        condition.await();
                    }
                    // 走到这里说明value是偶数，打印2
                    System.out.print("2");
                    // 执行value的++操作，让value变为奇数
                    value++;
                    // 唤醒因为调用condition.await而陷入等待的线程；这里是唤醒线程1
                    condition.signal();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // 释放独占锁
                    reentrantLock.unlock();
                }
            }
        }
    }
}
