package test.java.util.concurrent;

import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ThreadPoolExecutorTest {

    @Test
    public void test01() {
        Executors.newSingleThreadExecutor().execute(() -> { /* do something */});
    }

    /**
     * 测试 AbstractExecutorService#doInvokeAny 方法 中  active == 0 的 if 条件
     */
    @Test
    public void test02() throws ExecutionException, InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(5);
        List<Callable<Integer>> taskList = new ArrayList<>();
        // 组装任务
        for (int i = 0; i < 5; i++) {
            int finalI = i;
            Callable<Integer> task = () -> {
                try {
                    System.out.println("任务" + finalI + "开始");
                    TimeUnit.SECONDS.sleep(ThreadLocalRandom.current().nextInt(5));
                    System.out.println("任务" + finalI + "结束");
                    if (finalI != 3) {
                        System.out.println("任务抛异常" + finalI);
                        throw new RuntimeException("yichang===" + finalI);
                    } else {
                        //
                    }
                } catch (InterruptedException ignored) {
                    System.out.println("任务" + finalI + "被中端了...");
                }
                return finalI;
            };
            taskList.add(task);
        }
        // 执行任务
        Integer integer = executor.invokeAny(taskList);
        System.out.println("=========  " + integer);
        TimeUnit.SECONDS.sleep(10000);
    }


    /**
     * 自定义拒绝策略
     */
    public class CustomRejectedExecutionHandler implements RejectedExecutionHandler {
        // 共抛弃多少个任务
        private final AtomicLong handlerCount = new AtomicLong(0L);
        // 每抛弃多少个任务报警一次
        private Long period;
        private CustomRejectedExecutionHandler(Long period) {
            this.period = period;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (!executor.isShutdown()) {
                executor.getQueue().poll();
                executor.execute(r);
            }
            try {
                long currentCount = handlerCount.incrementAndGet();
                long model = currentCount % period;
                if (currentCount == 1L || model == 0L) {
                    // 打日志，发送短信或者钉钉......
                }
            } catch (Exception e) {
                // 处理...
            }
        }
    }
}

interface TaskCallable<T> {
    T callable(T t);
}

class TaskResult implements Serializable {
    private static final long serialVersionUID = 0L;
    // 任务状态
    private Integer taskStatus;
    // 任务消息
    private String taskMessage;
    // 任务结果数据
    private String taskResult;

    public Integer getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(Integer taskStatus) {
        this.taskStatus = taskStatus;
    }

    public String getTaskMessage() {
        return taskMessage;
    }

    public void setTaskMessage(String taskMessage) {
        this.taskMessage = taskMessage;
    }

    public String getTaskResult() {
        return taskResult;
    }

    public void setTaskResult(String taskResult) {
        this.taskResult = taskResult;
    }

    @Override
    public String toString() {
        return "TaskResult{" +
                "taskStatus=" + taskStatus +
                ", taskMessage='" + taskMessage + '\'' +
                ", taskResult='" + taskResult + '\'' +
                '}';
    }
}

class TaskHandler implements TaskCallable<TaskResult> {

    @Override
    public TaskResult callable(TaskResult taskResult) {
        // 拿到数据后进一步处理
        System.out.println(taskResult);
        return null;
    }
}

class TaskExecutor implements Runnable {
    private TaskCallable<TaskResult> taskCallable;
    private String taskParameter;

    public TaskExecutor(TaskCallable<TaskResult> taskCallable, String taskParameter) {
        this.taskCallable = taskCallable;
        this.taskParameter = taskParameter;
    }

    @Override
    public void run() {
        // 一系列业务逻辑，将结果数据封装成 TaskResult 对象并返回
        TaskResult result = new TaskResult();
        result.setTaskStatus(1);
        result.setTaskMessage(this.taskParameter);
        result.setTaskResult("异步回调成功");
        taskCallable.callable(result);
    }
}

class TaskCalllableTest{
    public static void main(String[] args) {
        // 创建一个任务执行后的回调函数
        TaskCallable<TaskResult> taskCallable = new TaskHandler();
        TaskExecutor taskExecutor = new TaskExecutor(taskCallable, "测试回调任务");
        new Thread(taskExecutor).start();
    }
}
