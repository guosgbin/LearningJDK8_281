package test.java.util.concurrent.locks;

import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * LockSupport 的官方案例，从源码中拷贝过来的
 */
public class LockSupportTest {

    /**
     * 使用 jstask 来分析是什么阻塞的当前线程
     */
    @Test
    public void testPark() {
        LockSupport.park();
        System.out.println("结束");
    }

    public static void main(String[] args) {
        LockSupportTest obj = new LockSupportTest();
        LockSupport.park(obj);
        System.out.println("结束");
    }


    class FIFOMutex {
        // 当锁用
        private final AtomicBoolean locked = new AtomicBoolean(false);
        // 等待队列
        private final Queue<Thread> waiters = new ConcurrentLinkedQueue<Thread>();

        /**
         * 加锁
         *
         * 1.先将当前线程加入到等待队列；
         * 2.判断当前线程是否是等待队列的队首线程，只有队首线程才有资格获取锁
         * 3.获取锁失败则 park 阻塞，获取成功则从等待队列移除
         */
        public void lock() {
            boolean wasInterrupted = false;
            Thread current = Thread.currentThread();
            waiters.add(current);
            // Block while not first in queue or cannot acquire lock
            while (waiters.peek() != current || !locked.compareAndSet(false, true)) {
                // 不是队首线程或者是队首线程但是抢锁失败，阻塞当前线程
                LockSupport.park(this);
                if (Thread.interrupted()) // ignore interrupts while waiting
                    wasInterrupted = true;
            }
            waiters.remove();
            if (wasInterrupted)          // reassert interrupt status on exit
                current.interrupt();
        }

        public void unlock() {
            locked.set(false);
            // 唤醒队首线程
            LockSupport.unpark(waiters.peek());
        }
    }
}
