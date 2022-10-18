package test.java.util.concurrent.locks;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.StampedLock;

public class StampedLockTest {

    @Test
    public void test01() {
        StampedLock lock = new StampedLock();
        new Thread(lock::writeLock, "ThreadA").start();
        sleep(100);
        new Thread(lock::writeLock, "ThreadB").start();
        sleep(10000000000000L);
        new Thread(lock::readLock, "ThreadC").start();
        sleep(10000000000000L);
        new Thread(lock::writeLock, "ThreadD").start();
        sleep(10000000000000L);
        new Thread(lock::readLock, "ThreadE").start();
        sleep(10000000000000L);
    }

    private void sleep(long l) {
        try {
            TimeUnit.MILLISECONDS.sleep(l);
        } catch (InterruptedException ignored) {
        }
    }
}
