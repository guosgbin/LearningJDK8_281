package test.java.util.concurrent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class DelayQueueTest {

    public static void main(String[] args) {
        DelayQueue<Task> delayQueue = new DelayQueue<>();
        new Thread(new Producer(delayQueue), "producer").start();
        new Thread(new Consumer(delayQueue), "consumer").start();

    }
}

class Consumer implements Runnable {
    private DelayQueue<Task> delayQueue;

    public Consumer(DelayQueue<Task> delayQueue) {
        this.delayQueue = delayQueue;
    }

    @Override
    public void run() {
        // 消费数据
        while (true) {
            try {
                Task task = delayQueue.take();
                System.out.println(Thread.currentThread().getName() + ": take " + task);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}

class Producer implements Runnable {
    private DelayQueue<Task> delayQueue;

    public Producer(DelayQueue<Task> delayQueue) {
        this.delayQueue = delayQueue;
    }

    @Override
    public void run() {
        // 生产数据
        int i = 5;
        while (i-- > 0) {
            long currentTime = System.currentTimeMillis();
            long remainTime = ThreadLocalRandom.current().nextInt(1000, 100000);

            Task task = new Task(currentTime + remainTime);
            delayQueue.put(task);
            System.out.println(Thread.currentThread().getName() + ": put " + task);

            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}


class Task implements Delayed {
    /**
     * Sequence number to break ties FIFO
     */
    // 任务的序列号，用于 compareTo 方法时比较
    private final long sequenceNumber;

    /**
     * The time the task is enabled to execute in nanoTime units
     */
    // Task 有效的截止时间
    private long time;

    private static final AtomicLong atomic = new AtomicLong(0);
    private static DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm:ss");


    public Task(long time) {
        this.sequenceNumber = atomic.getAndIncrement();
        this.time = time;
    }

    // 获取延迟时间
    public long getDelay(TimeUnit unit) {
        return unit.convert(time - System.currentTimeMillis(), MILLISECONDS);
    }

    /*
     * 因为实现了 Comparable 接口，需要重写
     * a negative integer, zero, or a positive integer as this object is
     * less than, equal to, or greater than the specified object.
     */
    public int compareTo(Delayed other) {
        if (other == this) // compare zero if same object
            return 0;
        if (other instanceof Task) {
            Task x = (Task) other;
            long diff = time - x.time;
            if (diff < 0)
                return -1;
            else if (diff > 0)
                return 1;
            else if (sequenceNumber < x.sequenceNumber)
                return -1;
            else
                return 1;
        }
        // 不是 Task 类型，直接比较 time 大小
        long diff = getDelay(MILLISECONDS) - other.getDelay(MILLISECONDS);
        return (diff < 0) ? -1 : (diff > 0) ? 1 : 0;
    }

    @Override
    public String toString() {
        return "Task{" +
                "sequenceNumber=" + sequenceNumber +
                ", time=" + formatter.format(LocalDateTime.ofInstant(new Date(time).toInstant(), ZoneId.of("+8"))) +
                '}';
    }
}
