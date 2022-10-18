package test.java.util.concurrent.locks;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class ReentrantReadWriteLockTest {

    /**
     * 测试 jdk 提供的案例 1
     */
    @Test
    public void test01() {
        CachedData cachedData = new CachedData();
        cachedData.processCachedData();
    }

    /**
     * 测试 jdk 提供的案例 2
     */
    @Test
    public void test02() {
        RWDictionary<String> rwDic = new RWDictionary<>();
        rwDic.put("key1","value1");
        rwDic.put("key2","value2");

        System.out.println(Arrays.toString(rwDic.allKeys()));
    }


    /**
     * 测试获取写锁
     * - 有线程持有读锁，当前线程不能获取写锁；
     * - 有线程持有写锁，但是不是当前线程，此时当前线程也不能获取写锁；
     */
    @Test
    public void test03() throws InterruptedException {
        ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
        ReentrantReadWriteLock.ReadLock readLock = lock.readLock();

        new Thread(readLock::lock).start();
        TimeUnit.MILLISECONDS.sleep(300);

        System.out.println("尝试获取写锁");
        writeLock.lock();
        System.out.println("结束...");
    }


}

/**
 * JDK 提供的案例 1
 * <p>
 * 展示了如何在更新缓存后执行锁降级（当以非嵌套方式处理多个锁时，异常处理尤其棘手）：
 */
class CachedData {
    // 保存的数据
    Object data;
    // 缓存数据是否有效的标志
    volatile boolean cacheValid;
    // 读写锁对象
    final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();

    /**
     * 处理缓存数据
     */
    void processCachedData() {
        // 获取读锁
        rwl.readLock().lock();
        // 校验缓存是否有效
        if (!cacheValid) {
            // Must release read lock before acquiring write lock
            // 释放读锁，尝试获取写锁
            rwl.readLock().unlock();
            rwl.writeLock().lock();
            try {
                // Recheck state because another thread might have
                // acquired write lock and changed state before we did.
                // 双重检查，再次检查缓存的状态
                if (!cacheValid) {
                    data = getData();
                    cacheValid = true;
                }
                // Downgrade by acquiring read lock before releasing write lock
                // 再次获取读锁
                rwl.readLock().lock();
            } finally {
                rwl.writeLock().unlock(); // Unlock write, still hold read
            }
        }
        try {
            useDate(data);
        } finally {
            rwl.readLock().unlock();
        }
    }

    private String getData() {
        return "模拟获取数据...";
    }

    private void useDate(Object data) {
        System.out.println(data);
    }
}


/**
 * ReentrantReadWriteLocks可用于改善某些类型集合的某些使用中的并发性。
 * 只有当集合预期很大，读线程比写线程访问的多，并且需要的操作开销超过同步开销时，这通常才是值得的。
 * 例如，这里有一个使用TreeMap的类，该类应该很大，并且可以并发访问。
 */
class RWDictionary<T> {
    // 封装的 map
    private final Map<String, T> m = new TreeMap<String, T>();
    // 读写锁对象
    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private final Lock r = rwl.readLock();
    private final Lock w = rwl.writeLock();

    public T get(String key) {
        r.lock();
        try {
            return m.get(key);
        } finally {
            r.unlock();
        }
    }

    public String[] allKeys() {
        r.lock();
        try {
            return m.keySet().toArray(new String[0]);
        } finally {
            r.unlock();
        }
    }

    public T put(String key, T value) {
        w.lock();
        try {
            return m.put(key, value);
        } finally {
            w.unlock();
        }
    }

    public void clear() {
        w.lock();
        try {
            m.clear();
        } finally {
            w.unlock();
        }
    }
}