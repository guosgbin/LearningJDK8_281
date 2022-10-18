package test.java.util.concurrent;

import java.util.concurrent.Exchanger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * https://blog.csdn.net/wyaoyao93/article/details/115907353
 */
public class ExchangerTest2 {
    public static void main(String[] args) throws InterruptedException {
        // 定义数据类型为String的Exchanger
        final Exchanger<String> exchanger = new Exchanger<>();
        // 定义 StringProducer 线程，并将该线程命名为 Producer
        StringProducer producer = new StringProducer("Producer", exchanger);
        // 定义 StringConsumer 线程，并将该线程命名为 Consumer
        StringConsumer consumer = new StringConsumer("Consumer", exchanger);
        // 分别启动线程
        consumer.start();
        producer.start();

        // 休眠1分钟后，将 Producer 和 Consumer 线程关闭
        TimeUnit.MINUTES.sleep(1);
        consumer.close();
        producer.close();
    }

    interface Closable {
        /**
         * 关闭方法
         */
        void close();

        /**
         * 判断当前线程是否被关闭
         * true: 关闭
         */
        boolean closed();
    }

    static abstract class ClosableThread extends Thread implements Closable {
        // 使用string模拟交换的数据类型
        protected final Exchanger<String> exchanger;

        private volatile boolean closed = false;

        private ClosableThread(String name, Exchanger<String> exchanger) {
            super(name);
            this.exchanger = exchanger;
        }

        @Override
        public void run() {
            // 判断当前线程是否关闭
            while (!closed) {
                // 没有关闭，就执行doExchange
                this.doExchange();
            }
        }

        /**
         * 核心交换逻辑实现
         */
        protected abstract void doExchange();


        @Override
        public void close() {
            System.out.println(this.getName() + " will be closed");
            this.closed = true;
            this.interrupt();
        }

        @Override
        public boolean closed() {
            return this.closed || this.isInterrupted();
        }
    }

    static class StringProducer extends ClosableThread {
        private char initialChar = 'A';

        public StringProducer(String name, Exchanger<String> exchanger) {
            super(name, exchanger);
        }

        @Override
        protected void doExchange() {
            // 模拟复杂的数据生成过程
            StringBuilder str = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                // 模拟耗时
                try {
                    TimeUnit.SECONDS.sleep(ThreadLocalRandom.current().nextInt(5));
                } catch (InterruptedException e) {
                    // ignore
                }
                str.append(initialChar++);
            }
            System.out.println(Thread.currentThread().getName() + " produce data is " + str);
            try {
                //  如果当前线程未关闭，则执行Exchanger的exchange方法
                if (!this.closed()) {
                    exchanger.exchange(str.toString());
                }
            } catch (InterruptedException e) {
                // 如果closed()方法之后执行了close方法，那么执行中断操作时此处会捕获到中断信号
                System.out.println(currentThread() + "received the close signal.");
            }
        }
    }

    private static class StringConsumer extends ClosableThread {


        private StringConsumer(String name, Exchanger<String> exchanger) {
            super(name, exchanger);
        }


        @Override
        protected void doExchange() {
            try {
                // 如果当前线程未关闭，则执行Exchanger的exchange方法
                // Consumer线程直接使用null值作为exchange的数据对象
                if (!this.closed()) {
                    String data = exchanger.exchange(null);
                    System.out.println(currentThread().getName() + " received the data: " + data);
                }
            } catch (InterruptedException e) {
                System.out.println(currentThread().getName() + " received the close signal.");
            }
        }
    }
}
