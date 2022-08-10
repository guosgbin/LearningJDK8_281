package test.java.util.concurrent.atomic;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;

public class AtomicStampedReferenceTest {

    /**
     * 测试获取相关的方法
     */
    @Test
    public void testGet() {
        // 初始版本号是 1
        AtomicStampedReference<String> ref = new AtomicStampedReference<>("特朗普", 1);

        // 获取版本号
        System.out.println(ref.getStamp());
        // 获取引用
        System.out.println(ref.getReference());

        // 获取引用和版本号
        int[] stampHolder = new int[1];
        String value = ref.get(stampHolder);
        System.out.printf("value = %s, version = %s", value, stampHolder[0]);
    }

    /**
     * 无条件设置 reference 和 stamp
     *
     * @see AtomicStampedReference#set
     */
    @Test
    public void testSet() {
        AtomicStampedReference<String> ref = new AtomicStampedReference<>("特朗普", 1);
        ref.set("佩洛西", 200);

        // 获取引用和版本号
        int[] stampHolder = new int[1];
        String value = ref.get(stampHolder);
        System.out.printf("value = %s, version = %s", value, stampHolder[0]);
    }

    /**
     * 测试 cas
     *
     * @see AtomicStampedReference#weakCompareAndSet
     * @see AtomicStampedReference#compareAndSet
     */
    @Test
    public void testCompareAndSet() {
        AtomicStampedReference<String> ref = new AtomicStampedReference<>("特朗普", 1);
        // 成功更新
        ref.compareAndSet("特朗普","佩洛西", 1, 100);
        // 下面是不成功的更新
        // 1.版本号不对，无法更新
        // 2.reference 的地址不对，无法更新
//        ref.compareAndSet("特朗普","佩洛西", 2, 100);

        // 获取引用和版本号
        int[] stampHolder = new int[1];
        String value = ref.get(stampHolder);
        System.out.printf("value = %s, version = %s", value, stampHolder[0]);
    }


    /**
     * 更新版本号 attemptStamp
     *
     * @see AtomicStampedReference#attemptStamp
     */
    @Test
    public void testAttemptStamp() {
        AtomicStampedReference<String> ref = new AtomicStampedReference<>("特朗普", 1);

        ref.attemptStamp("特朗普", 23);

        // 获取引用和版本号
        int[] stampHolder = new int[1];
        String value = ref.get(stampHolder);
        System.out.printf("value = %s, version = %s", value, stampHolder[0]);
    }


    /**
     * 解决 ABA 问题
     */
    @Test
    public void testABAProblem() throws InterruptedException {
        // 初始版本号是 1
        AtomicStampedReference<Integer> ref = new AtomicStampedReference<>(100, 1);
        Thread t1 = new Thread(() -> {
            int stamp = ref.getStamp();
            ref.compareAndSet(100, 50, stamp, stamp + 1);
            try {
                TimeUnit.MILLISECONDS.sleep(100);
            } catch (InterruptedException e) {
            }
            int stamp2 = ref.getStamp();
            ref.compareAndSet(50, 100, stamp2, stamp2 + 1);
            System.out.println("引用值从 100 -> 50 -> 100");
        }, "update-thread");

        Thread t2 = new Thread(() -> {
            String name = Thread.currentThread().getName();
            Integer value = ref.getReference();
            int stamp = ref.getStamp();
            // 拿到了 value 值，模拟去做别的操作
            try {
                TimeUnit.MILLISECONDS.sleep(1000);
            } catch (InterruptedException e) {
            }

            boolean updateSuccess = ref.compareAndSet(value, 200, stamp, stamp + 1);
            if (updateSuccess) {
                System.out.printf("%s 更新成功\n", name);
            } else {
                System.out.printf("%s 更新失败，实际的版本号 %s，当前线程得到的版本号 %s\n", name, ref.getStamp(), stamp);
            }
        }, "read-thread");

        Thread t3 = new Thread(() -> {
            int i = 0;
            while (true) {
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException e) {
                }
                System.out.printf("%s %s %s 版本号 %s\n", i++, Thread.currentThread().getName(), ref.getReference(), ref.getStamp());
            }
        }, "monitor-thread");

        t3.setDaemon(true);
        t3.start();
        TimeUnit.MILLISECONDS.sleep(100);
        t1.start();
        t2.start();

        t1.join();
        t2.join();
        System.out.println("===");
    }
}
