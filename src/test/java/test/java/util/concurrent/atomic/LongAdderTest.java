package test.java.util.concurrent.atomic;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

@Warmup(iterations = 5, time = 10, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = TimeUnit.MILLISECONDS)
@State(value = Scope.Benchmark)
public class LongAdderTest {


    // 每个线程累加次数
    private static final int CIRCLE_COUNT = 100 * 10000;
    //
    @Param(value = {"1", "10", "50", "100"})
    private static int THREAD_COUNT;

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void testAtomicLong() throws InterruptedException {
        calculateAtomicLong(THREAD_COUNT, CIRCLE_COUNT);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void testLongAdder() throws InterruptedException {
        calculateLongAdder(THREAD_COUNT, CIRCLE_COUNT);
    }

    /**
     * @param threadCount 线程个数
     * @param circleCount 每个线程累加次数
     * @throws InterruptedException
     */
    private long calculateAtomicLong(int threadCount, int circleCount) throws InterruptedException {
        // AtomicLong原子类
        AtomicLong atomicLong = new AtomicLong(0);
        List<Thread> threadList = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(() -> {
                for (int j = 0; j < circleCount; j++) {
                    atomicLong.incrementAndGet();
                }
            });
            thread.start();
            threadList.add(thread);
        }
        // 让主线程等待其他线程执行完
        for (Thread thread : threadList) {
            thread.join();
        }
        return atomicLong.get();
    }

    /**
     * @param threadCount 线程个数
     * @param circleCount 每个线程累加次数
     * @throws InterruptedException
     */
    private long calculateLongAdder(int threadCount, int circleCount) throws InterruptedException {
        LongAdder longAdder = new LongAdder();
        List<Thread> threadList = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(() -> {
                for (int j = 0; j < circleCount; j++) {
                    longAdder.increment();
                }
            });
            thread.start();
            threadList.add(thread);
        }
        // 让主线程等待其他线程执行完
        for (Thread thread : threadList) {
            thread.join();
        }
        return longAdder.sum();
    }

    // 开启性能测试
    public static void main(String[] args) throws RunnerException {
        Options opt = new OptionsBuilder()
                .include(LongAdderTest.class.getSimpleName())
                .forks(1)
                .result("result.json")
                .resultFormat(ResultFormatType.JSON)
                .build();
        new Runner(opt).run();
    }
}
