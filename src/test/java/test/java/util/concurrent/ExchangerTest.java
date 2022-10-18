package test.java.util.concurrent;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public class ExchangerTest {

    /**
     * T1 线程和 T2 线程交换数据，假如 T2 线程的数据没准备好，T1线程需要等待直到T2线程准备好
     */
    @Test
    public void test01() throws InterruptedException {
        // 定义 Exchanger 类，该类是一个泛型类，String 类型标明一对线程交换的数据只能是 String 类型
        Exchanger<String> exchanger = new Exchanger<>();
        // 定义线程T1
        Thread t1 = new Thread(() -> {
            try {
                // 随机休眠1～10秒钟
                TimeUnit.SECONDS.sleep(1);
                // 执行 exchange 方法，将对应的数据传递给 T2 线程，同时从 T2 线程获取交换的数据
                // 返回值是T2线程中返回的数据
                System.out.println(Thread.currentThread().getName() + " 开始..." + LocalDateTime.now());
                String dataFromT2 = exchanger.exchange("ThreadA的数据");
                System.out.println(Thread.currentThread().getName() + " 获得: " + dataFromT2 + ">>>" + LocalDateTime.now());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println(Thread.currentThread().getName() + " 结束...");
        }, "ThreadA");

        // 定义线程T1
        Thread t2 = new Thread(() -> {
            try {
                // 随机休眠1～10秒钟
//                TimeUnit.SECONDS.sleep(2);
                TimeUnit.MILLISECONDS.sleep(1020);
                // 执行exchange方法，将对应的数据传递给T2线程，同时从T2线程获取交换的数据
                // 返回值是T1线程中返回的数据
                System.out.println(Thread.currentThread().getName() + " 开始..." + LocalDateTime.now());
                String dataFromT1 = exchanger.exchange("ThreadB的数据");
                System.out.println(Thread.currentThread().getName() + " 获取: " + dataFromT1 + ">>>" + LocalDateTime.now());
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.out.println(Thread.currentThread().getName() + " 结束...");
        }, "ThreadB");

        t1.start();
        t2.start();

        t1.join();
        t1.join();
    }

    public void sleep(TimeUnit timeUnit, long times) {
        try {
            timeUnit.sleep(times);
        } catch (InterruptedException ignored) {
        }
    }

    @Test
    public void test02() {
//        int MMASK = 0xff;
//        int NCPU = Runtime.getRuntime().availableProcessors();
//        int result = (NCPU >= (MMASK << 1)) ? MMASK : NCPU >>> 1;
//        System.out.println(NCPU + "=====" + result);
//
//        int SPINS = 1 << 10;
//        int result = (SPINS >>> 1) - 1;
//        // 10 00000000
//        // 01 11111111
//        System.out.println(result);
//        System.out.println(Integer.toBinaryString(result));

//        System.out.println(1 << 7);
        System.out.println(Integer.toBinaryString(-1));
        System.out.println(Integer.toBinaryString(-1 << 29));
        System.out.println(Integer.toBinaryString(0 << 29));
        System.out.println(Integer.toBinaryString(1 << 29));
        System.out.println(Integer.toBinaryString(2 << 29));
        System.out.println(Integer.toBinaryString(3 << 29));

        System.out.println(Integer.toBinaryString( (1 << 29) - 1));

        // 11100000 00000000 00000000 00000000
        // 00000000 00000000 00000000 00000000
        // 00100000 00000000 00000000 00000000
        // 01000000 00000000 00000000 00000000
        // 01100000 00000000 00000000 00000000
    }
}

