package test.java.util.concurrent;

import java.util.concurrent.Semaphore;

/**
 * 信号量测试
 */
public class SemaphoreTest {


    /**
     * 一个池子的案例
     */
    class Pool {
        // 池子的最大容量
        private static final int MAX_AVAILABLE = 100;
        // 信号量，最大并发是 100，true 表示是公平锁，
        private final Semaphore available = new Semaphore(MAX_AVAILABLE, true);

        // 尝试获取资源
        public Object getItem() throws InterruptedException {
            // 尝试获取锁
            available.acquire();
            return getNextAvailableItem();
        }

        // 归还资源到池子
        public void putItem(Object x) {
            if (markAsUnused(x)) {
                available.release();
            }
        }

        // 池子
        protected Object[] items = new Object[MAX_AVAILABLE];
        // 标记池子中的指定索引处的资源是否被使用
        protected boolean[] used = new boolean[MAX_AVAILABLE];

        // 从头开始遍历 used 数组，获取第一个空闲的资源
        protected synchronized Object getNextAvailableItem() {
            for (int i = 0; i < MAX_AVAILABLE; i++) {
                if (!used[i]) {
                    used[i] = true;
                    return items[i];
                }
            }
            // 什么也没找到
            return null;
        }

        // 因为归还资源了，把池子中对应的资源标记位可用
        protected synchronized boolean markAsUnused(Object item) {
            for (int i = 0; i < MAX_AVAILABLE; i++) {
                if (item == items[i]) {
                    if (used[i]) {
                        used[i] = false;
                        return true;
                    } else {
                        return false;
                    }
                }
            }
            return false;
        }
    }
}
