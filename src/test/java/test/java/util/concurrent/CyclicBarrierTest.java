package test.java.util.concurrent;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class CyclicBarrierTest {

    public static void main(String[] args) {
        int colleague = 10;
        CyclicBarrier cb = new CyclicBarrier(colleague,
                () -> System.out.println("所有同事都到齐了,开始出发..."));
        for (int i = 0; i < colleague; i++) {
            Thread t = new Thread(new GatherTask(cb), "同事『" + i + "』");
            t.start();
        }
    }

    static class GatherTask implements Runnable {
        private final CyclicBarrier cb;

        GatherTask(CyclicBarrier cb) {
            this.cb = cb;
        }

        @Override
        public void run() {
            try {
                TimeUnit.MILLISECONDS.sleep(ThreadLocalRandom.current().nextInt(1000));
                System.out.printf("%s 到集合地点了\n", Thread.currentThread().getName());
                // 在屏障处等待
                cb.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (BrokenBarrierException e) {
                e.printStackTrace();
            }
        }
    }
}
