package test.java.util.concurrent.locks;


import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class SimpleBlockingQueue<T> implements BlockingQueue {

    private ReentrantLock lock = new ReentrantLock();
    private Condition notFull = lock.newCondition();
    private Condition notEmpty = lock.newCondition();

    // 队列大小
    private int size;

    // 底层数组
    private Object[] queues;

    // 当前队列中的数据量
    private int count;

    // 记录生产者存放数据的下一次位置，每个生产者生产完一个数据后，将 putPtr++
    private int putPtr;

    // 记录消费者消费数据的下一次位置，每个消费者消费完一个数据后，将 takePtr++
    private int takePtr;

    public SimpleBlockingQueue(int size) {
        this.size = size;
        this.queues = new Object[size];
    }

    @Override
    public void put(Object element) {
        lock.lock();
        try {
            while (count == size) {
                // 队列是满的，需要睡眠，等待消费者去唤醒
                notFull.await();
            }

            queues[putPtr] = element;
            putPtr++;
            if (putPtr == size) {
                putPtr = 0;
            }
            count++;
            // 这里因为添加了一个数据，需要唤醒消费者线程
            notEmpty.signalAll();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Object take() throws InterruptedException {
        lock.lock();
        try {
            while (count == 0) {
                // 队列是空的，需要睡眠，等待生产者去唤醒
                notEmpty.await();
            }

            Object element = queues[takePtr];
            takePtr++;
            if (takePtr == size) {
                takePtr = 0;
            }
            count--;
            // 这里因为添加了一个数据，需要唤醒消费者线程
            notFull.signalAll();
            return element;
        } finally {
            lock.unlock();
        }
    }


    public static void main(String[] args) {
        BlockingQueue queue = new SimpleBlockingQueue<>(10);

        new Thread(() -> {
            int i = 0;
            while (true) {
                try {
                    i++;
                    if (i == 10) i = 0;
                    TimeUnit.MILLISECONDS.sleep(500);
                    System.out.println(Thread.currentThread().getName() + " 生产数据：" + i);
                    queue.put(i);
                } catch (InterruptedException e) {
                }
            }
        }, "生产者").start();

        new Thread(() -> {
            int i = 0;
            while (true) {
                try {
                    i++;
                    if (i == 10) i = 0;
                    TimeUnit.MILLISECONDS.sleep(500);
                    System.out.println(Thread.currentThread().getName() + " 生产数据：" + i);
                    queue.put(i);
                } catch (InterruptedException e) {
                }
            }
        }, "生产者2").start();

        new Thread(() -> {
            while (true) {
                try {
                    Object take = queue.take();
                    System.out.println("消费数据：" + take);
                } catch (InterruptedException e) {
                }
            }
        }, "消费者").start();
    }
}


/**
 * 接口
 */
interface BlockingQueue {
    void put(Object t);

    Object take() throws InterruptedException;
}

