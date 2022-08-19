package test.java.util.concurrent.atomic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 * @see #testIntroduceWrong() 引入为何有 AtomicReference 这个类
 * @see #testIntroduceLock()  悲观的思想，给所有加锁
 * @see #testIntroduceRight() 乐观锁， 自旋 + cas， 给了一个 AtomicReference 使用模板
 * @see #testABAProblem() 测试 ABA 问题
 */
public class AtomicReferenceTest {

    /**
     * 引入
     * 为什么需要给引用对象封装原子类呢？难道多个线程同时对一个引用变量赋值也会出现并发问题？
     * 引用变量的赋值本身没有并发问题，也就是说对于引用变量 var ，类似下面的赋值操作本身就是原子操作:
     *
     * AtomicReference 的引入是为了可以用一种类似乐观锁的方式操作共享资源，在某些情景下以提升性能。
     */
    private Stock stock = new Stock("华为手机", 0);
    private AtomicReference<Stock> stockRef = new AtomicReference<>(stock);

    /**
     * 这样加出来的 count 是有问题的，
     * 单个引用变量的赋值是原子操作，但是多个引用变量赋值，就不是一个原子操作了
     */
    @Test
    public void testIntroduceWrong() {
        for (int i = 0; i < 100; i++) {
            new Thread(() -> {
                Stock stockTemp = stock;
                stockTemp = new Stock(stockTemp.getGoodsName(), stockTemp.count + 1);
                stock = stockTemp;
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                }
            }).start();
        }
        System.out.println(stock);
    }

    /**
     * 既然上面的代码有问题， stock 属性属于共享资源，那么我们可以对他进行加锁
     */
    @Test
    public void testIntroduceLock() throws InterruptedException {
        List<Thread> list = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Thread thread = new Thread(() -> {
                synchronized (this) {
                    Stock stockTemp = stock;
                    stockTemp = new Stock(stockTemp.getGoodsName(), stockTemp.count + 1);
                    stock = stockTemp;
                    try {
                        TimeUnit.MILLISECONDS.sleep(100);
                    } catch (InterruptedException e) {
                    }
                }
            });
            list.add(thread);
            thread.start();
        }
        for (Thread thread : list) {
            thread.join();
        }

        System.out.println(stock);
    }

    /**
     * 使用原子类封装 stock
     *
     * AtomicReference 使用模板
     *
     * AtomicReference<Object> ref = new AtomicReference<>(new Object());
     * // 自旋 + cas
     * for(;;) {
     *     Object oldObj = ref.get();
     *     // 对旧的 oldObj 做了一些操作，得到一个新的 Object 对象
     *     Object newObj = doSomeOperate(oldObj);
     *     // cas
     *     if (ref.compareAndSet(oldObj, newObj)) {
     *          break;
     *     }
     * }
     *
     *
     */
    @Test
    public void testIntroduceRight() throws InterruptedException {
        List<Thread> list = new ArrayList<>();

        Runnable task = () -> {
            for (; ; ) { // 自旋
                Stock stock = stockRef.get();
                Stock newStock = new Stock(stock.getGoodsName(), stock.count + 1);
                // CAS 操作
                if (stockRef.compareAndSet(stock, newStock)) {
                    System.out.println(newStock);
                    break;
                }
                try {
                    TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                }
            }
        };

        for (int i = 0; i < 100; i++) {
            Thread thread = new Thread(task);
            list.add(thread);
            thread.start();
        }

        for (Thread thread : list) {
            thread.join();
        }

        System.out.println("============");
        System.out.println(stockRef.get());
    }


    /**
     * 测试 ABA 问题
     */
    @Test
    public void testABAProblem() throws InterruptedException {
        AtomicReference<Integer> ref = new AtomicReference<>(100);
        Thread t1 = new Thread(() -> {
            sleep(100);
            // 先从 100 设置到 50
            ref.compareAndSet(100, 50);
            sleep(100);
            // 再将 50 恢复到 100
            ref.compareAndSet(50, 100);
            System.out.println("引用值从 100 -> 50 -> 100");
        }, "update-thread");

        Thread t2 = new Thread(() -> {
            Integer value = ref.get();
            System.out.println(Thread.currentThread().getName() + "获取到值：" + value);
            // 拿到了 value 值，模拟去做别的操作
            sleep(1000);
            boolean setSuccess = ref.compareAndSet(value, 200);
            if (setSuccess) {
                System.out.println(Thread.currentThread().getName() + "发现引用值还是 100，就改成了 200");
            } else {
                System.out.println(Thread.currentThread().getName() + "设置 200 失败");
            }
        }, "read-thread");

        // 打印监控线程
        Thread t3 = new Thread(() -> {
            int i = 0;
            while (true) {
                sleep(10);
                System.out.println(i++ + " " + Thread.currentThread().getName() + " " + ref.get());
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

    public void sleep(long milliseconds) {
        try {
            TimeUnit.MILLISECONDS.sleep(milliseconds);
        } catch (InterruptedException e) {
        }
    }



    class Stock {
        // 商品名
        private String goodsName;
        // 商品库存
        private Integer count;

        public Stock(String goodsName, Integer count) {
            this.goodsName = goodsName;
            this.count = count;
        }

        public String getGoodsName() {
            return goodsName;
        }

        public Integer getCount() {
            return count;
        }

        @Override
        public String toString() {
            return "Stock{" +
                    "goodsName='" + goodsName + '\'' +
                    ", count='" + count + '\'' +
                    '}';
        }
    }

}
