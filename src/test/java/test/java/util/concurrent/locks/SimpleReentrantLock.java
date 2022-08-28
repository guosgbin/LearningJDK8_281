package test.java.util.concurrent.locks;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;

/**
 * 写一个丐版的 SimpleReentrantLock
 */
public class SimpleReentrantLock implements Lock {

    /**
     * 锁状态
     * 0 - 锁空闲状态
     * 1 - 锁占用状态
     */
    private volatile int state;

    /**
     * 当前是独占锁，该变量表示当前持有锁的线程
     */
    private Thread exclusiveOwnerThread;

    /**
     * 指向队首
     *
     * 注意：head 节点对应的线程是当前获取到锁的线程
     */
    private Node head;

    /**
     * 指向队尾
     */
    private Node tail;

    /**
     * 锁阻塞的线程排队队列 FIFO
     * 双向链表
     */
    static final class Node {
        /**
         * 前驱
         */
        volatile Node prev;

        /**
         * 后驱
         */
        volatile Node next;

        /**
         * 当前线程本身
         */
        volatile Thread thread;

        public Node() {
        }

        public Node(Thread thread) {
            this.thread = thread;
        }
    }


    /**
     * 尝试加锁，未拿到锁则阻塞
     * 模拟公平锁
     *
     * 1. 线程进来发现锁是空闲状态，直接获取锁
     * 2. 线程进来发现锁不是空闲状态
     */
    @Override
    public void lock() {
        acquire(1);
    }

    /**
     * 获取锁
     * 1.尝试获取锁，成功占用
     * 2.获取锁失败，阻塞线程
     *
     * @param arg
     */
    private void acquire(int arg) {
        if (tryAcquire(arg)) {
            // 尝试获取锁成功
            return;
        }
        // 获取锁失败
        // 当前节点需要入队，并且 park 阻塞线程
        // 当线程被唤醒的时候，需要判断当前线程是否是 head.next 线程，假如是就尝试获取锁，
        // 因为 head.thread 是之前持有锁的线程，此处被唤醒，说明是已经释放锁了，可以争抢了
        // 这里只有 head.next 去抢锁，这是实现公平锁的公平的核心体现
        Node node = addWaiter();
        acquireQueued(node, arg);
    }

    private void acquireQueued(Node node, int arg) {
        // 只有当前 node 成功获取到锁后才会跳出自旋
        for (;;) {
            Node prev = node.prev;
            // 尝试获取锁
            if (prev == head && tryAcquire(arg)) {
                // 获取成功
                setHead(node);
                // 出队 helpGC
                prev.next = null;
                return;
            } else {
                // 获取失败，挂起当前线程
                System.out.println(Thread.currentThread().getName() + "挂起");
                LockSupport.park();
                // 在解锁的时候唤醒
                System.out.println(Thread.currentThread().getName() + "被唤醒");
            }
        }
    }

    /**
     * 线程入队
     */
    private Node addWaiter() {
        Node newNode = new Node(Thread.currentThread());
        // 找到 node 的前驱
        // 入队尾
        Node prev = tail;
        if (prev != null) {  // 说明当前队列不是空队列
            newNode.prev = prev;
            // CAS 更新 tail 节点为 node，防止并发操作
            if (compareAndSetTail(tail, newNode)) {
                prev.next = newNode;
                return newNode;
            }
        }

        // 说明队列是空 或者 cas 设置 tail 时失败
        enq(newNode);
        return newNode;
    }

    /**
     * 入队操作，自旋保证一定入队
     *
     * @param node
     * @return
     */
    private void enq(Node node) {
        for (;;) {
            // CASE1 队列是空队列，说明当前线程是第一个抢占锁失败的线程
            // 需要帮抢到锁的线程做一些处理，之前说了 head 是指向持有锁的线程的节点
            // 所以这里要帮它创建一个节点，并赋值给 head
            if (tail == null) {
                if (compareAndSetHead(new Node())) {
                    tail = head;
                    // 这里并没有返回，继续自旋
                }
            }
            // CASE2 入队
            else {
                Node prev = tail;
                if (prev != null) {
                    node.prev = prev;
                    if (compareAndSetTail(tail, node)) {
                        prev.next = node;
                        // 入队成功直接返回
                        return;
                    }
                }
            }
        }
    }

    /**
     * 尝试获取锁
     *
     * @param arg
     * @return true 获取锁成功，false 获取锁失败
     *
     */
    private boolean tryAcquire(int arg) {
        if (state == 0) {
            if (!hasQueuedPredecessor() && compareAndSetState(0, arg)) {
                // 获取锁成功
                this.exclusiveOwnerThread = Thread.currentThread();
                return true;
            }
        } else if (Thread.currentThread() == this.exclusiveOwnerThread) {
            // 锁重入，不用 cas 是因为当前线程已经是加锁的线程了，其他线程进不来
            int state = getState();
            int newState = state + arg;
            if (newState < 0) {
                throw new RuntimeException("越界了");
            }
            this.state = newState;
            return true;
        }
        // 没有获取到锁
        return false;
    }

    /**
     * 表示当前线程前是否有已经排队等待的线程
     *
     * 当前队列是空，
     * 当前线程是 head.next 线程
     *
     * @return 返回 false 表示当前线程是可以尝试拿锁的线程
     */
    private boolean hasQueuedPredecessor() {
        Node h = head;
        Node t = tail;
        Node s;

        // h != t
        // 成立：说明当前队列中已经有 node 了
        // 不成立：1. h == t == null 说明队列还未初始化
        //        2. h == t == head 说明第一个锁获取失败的线程，会为当前拿到锁的线程补充创建一个 head 节点

        // 返回 false 的情况
        // 1. h == t 当前队列是空队列
        // 2. h != t ,但是 (s = h.next) != null 且 head.next 是当前线程，说明自己有资格尝试获取锁

        return h != t
                && ((s = h.next) == null || s.thread != Thread.currentThread());
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        release(1);
    }

    private void release(int arg) {
        if (tryRelease(arg)) {
            // 唤醒阻塞的线程
            Node head = this.head;
            if (head.next != null) {
                // 公平锁，唤醒后一个节点
                Thread thread = head.next.thread;
                if (thread != null) {
                    LockSupport.unpark(thread);
                }
            }
        }
    }

    /**
     * 尝试释放锁
     *
     * @param arg
     * @return
     */
    private boolean tryRelease(int arg) {
        int c = getState() - arg;
        if (exclusiveOwnerThread != Thread.currentThread()) {
            throw new RuntimeException("必须是获取锁的线程才能释放锁");
        }
        // 到这里没有并发了
        if (c == 0) {
            exclusiveOwnerThread = null;
            this.state = c;
            return true;
        }
        this.state = c;
        return false;
    }


    private void setHead(Node node) {
        this.head = node;
        // 因为当前节点已经获取到锁了，所以置空
        node.thread = null;
        //
        node.prev = null;
    }

    public int getState() {
        return state;
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        throw new UnsupportedOperationException("丐版不支持该方法");
    }

    @Override
    public boolean tryLock() {
        throw new UnsupportedOperationException("丐版不支持该方法");
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        throw new UnsupportedOperationException("丐版不支持该方法");
    }

    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException("丐版不支持该方法");
    }


    private static final Unsafe unsafe;
    private static final long stateOffset;
    private static final long headOffset;
    private static final long tailOffset;
//    private static final long waitStatusOffset;
//    private static final long nextOffset;

    static {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            unsafe = (Unsafe) f.get(null);
            stateOffset = unsafe.objectFieldOffset(SimpleReentrantLock.class.getDeclaredField("state"));
            headOffset = unsafe.objectFieldOffset(SimpleReentrantLock.class.getDeclaredField("head"));
            tailOffset = unsafe.objectFieldOffset(SimpleReentrantLock.class.getDeclaredField("tail"));
//            waitStatusOffset = unsafe.objectFieldOffset(Node.class.getDeclaredField("waitStatus"));
//            nextOffset = unsafe.objectFieldOffset(Node.class.getDeclaredField("next"));

        } catch (Exception ex) { throw new Error(ex); }
    }

    // CAS 修改等待队列的头指针
    private final boolean compareAndSetHead(Node update) {
        return unsafe.compareAndSwapObject(this, headOffset, null, update);
    }

    // CAS 修改等待队列的尾指针
    private final boolean compareAndSetTail(Node expect, Node update) {
        return unsafe.compareAndSwapObject(this, tailOffset, expect, update);
    }

    // CAS 修改同步状态值
    protected final boolean compareAndSetState(int expect, int update) {
        // See below for intrinsics setup to support this
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }
}
