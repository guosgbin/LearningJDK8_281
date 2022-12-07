/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.AbstractQueue;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.SortedSet;
import java.util.Spliterator;
import java.util.function.Consumer;
import sun.misc.SharedSecrets;

/**
 * An unbounded {@linkplain BlockingQueue blocking queue} that uses
 * the same ordering rules as class {@link PriorityQueue} and supplies
 * blocking retrieval operations.  While this queue is logically
 * unbounded, attempted additions may fail due to resource exhaustion
 * (causing {@code OutOfMemoryError}). This class does not permit
 * {@code null} elements.  A priority queue relying on {@linkplain
 * Comparable natural ordering} also does not permit insertion of
 * non-comparable objects (doing so results in
 * {@code ClassCastException}).
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.  The Iterator provided in method {@link
 * #iterator()} is <em>not</em> guaranteed to traverse the elements of
 * the PriorityBlockingQueue in any particular order. If you need
 * ordered traversal, consider using
 * {@code Arrays.sort(pq.toArray())}.  Also, method {@code drainTo}
 * can be used to <em>remove</em> some or all elements in priority
 * order and place them in another collection.
 *
 * <p>Operations on this class make no guarantees about the ordering
 * of elements with equal priority. If you need to enforce an
 * ordering, you can define custom classes or comparators that use a
 * secondary key to break ties in primary priority values.  For
 * example, here is a class that applies first-in-first-out
 * tie-breaking to comparable elements. To use it, you would insert a
 * {@code new FIFOEntry(anEntry)} instead of a plain entry object.
 *
 *  <pre> {@code
 * class FIFOEntry<E extends Comparable<? super E>>
 *     implements Comparable<FIFOEntry<E>> {
 *   static final AtomicLong seq = new AtomicLong(0);
 *   final long seqNum;
 *   final E entry;
 *   public FIFOEntry(E entry) {
 *     seqNum = seq.getAndIncrement();
 *     this.entry = entry;
 *   }
 *   public E getEntry() { return entry; }
 *   public int compareTo(FIFOEntry<E> other) {
 *     int res = entry.compareTo(other.entry);
 *     if (res == 0 && other.entry != this.entry)
 *       res = (seqNum < other.seqNum ? -1 : 1);
 *     return res;
 *   }
 * }}</pre>
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 */

/*
 * 堆，数组表示
 * 不支持 null 元素
 *
 * 该实现使用基于数组的二进制堆，公共操作由单个锁保护。
 * 然而，调整大小期间的分配使用一个简单的自旋锁（仅在不持有主锁的情况下使用），以便允许take与分配同时操作。
 * 这避免了等待消费者的重复推迟和随后的要素积累。
 * 在分配过程中需要远离锁，这使得不可能简单地包装委托的锁中的PriorityQueue操作，就像在该类的早期版本中所做的那样。
 * 为了保持互操作性，在序列化过程中仍然使用普通的PriorityQueue，这以暂时加倍开销为代价来保持兼容性。
 *
 */
@SuppressWarnings("unchecked")
public class PriorityBlockingQueue<E> extends AbstractQueue<E>
    implements BlockingQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = 5595510919245408276L;

    /*
     * The implementation uses an array-based binary heap, with public
     * operations protected with a single lock. However, allocation
     * during resizing uses a simple spinlock (used only while not
     * holding main lock) in order to allow takes to operate
     * concurrently with allocation.  This avoids repeated
     * postponement of waiting consumers and consequent element
     * build-up. The need to back away from lock during allocation
     * makes it impossible to simply wrap delegated
     * java.util.PriorityQueue operations within a lock, as was done
     * in a previous version of this class. To maintain
     * interoperability, a plain PriorityQueue is still used during
     * serialization, which maintains compatibility at the expense of
     * transiently doubling overhead.
     */

    /**
     * Default array capacity.
     */
    // 默认数组的容量
    private static final int DEFAULT_INITIAL_CAPACITY = 11;

    /**
     * The maximum size of array to allocate.
     * Some VMs reserve some header words in an array.
     * Attempts to allocate larger arrays may result in
     * OutOfMemoryError: Requested array size exceeds VM limit
     */
    // 允许的最大的数组
    private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

    /**
     * Priority queue represented as a balanced binary heap: the two
     * children of queue[n] are queue[2*n+1] and queue[2*(n+1)].  The
     * priority queue is ordered by comparator, or by the elements'
     * natural ordering, if comparator is null: For each node n in the
     * heap and each descendant d of n, n <= d.  The element with the
     * lowest value is in queue[0], assuming the queue is nonempty.
     */
    /*
     * 平衡二叉树，最小堆
     * 父节点 queue[n]
     * 左子节点 queue[2n+1]
     * 右子节点 queue[2n+2]
     */
    private transient Object[] queue;

    /**
     * The number of elements in the priority queue.
     */
    // 优先队列的元素个数
    private transient int size;

    /**
     * The comparator, or null if priority queue uses elements'
     * natural ordering.
     */
    // Comparator，为 null 表示使用元素的自然顺序
    private transient Comparator<? super E> comparator;

    /**
     * Lock used for all public operations
     */
    // 公共操作的锁对象
    private final ReentrantLock lock;

    /**
     * Condition for blocking when empty
     */
    // 条件队列
    // PriorityBlockingQueue只有一个条件等待队列——notEmpty，
    // 因为构造时不会限制最大容量且会自动扩容，所以插入元素并不会阻塞，仅当队列为空时，才可能阻塞“出队”线程。
    private final Condition notEmpty;

    /**
     * Spinlock for allocation, acquired via CAS.
     */
    // 分配的自旋锁，0 表示空闲，1 表示加锁
    private transient volatile int allocationSpinLock;

    /**
     * A plain PriorityQueue used only for serialization,
     * to maintain compatibility with previous versions
     * of this class. Non-null only during serialization/deserialization.
     */
    // 序列化时使用，以保持与该类的早期版本的兼容性。仅在序列化/反序列化期间为非null。
    private PriorityQueue<E> q;

    /**
     * Creates a {@code PriorityBlockingQueue} with the default
     * initial capacity (11) that orders its elements according to
     * their {@linkplain Comparable natural ordering}.
     */
    // 创建默认大小的数组，11
    public PriorityBlockingQueue() {
        this(DEFAULT_INITIAL_CAPACITY, null);
    }

    /**
     * Creates a {@code PriorityBlockingQueue} with the specified
     * initial capacity that orders its elements according to their
     * {@linkplain Comparable natural ordering}.
     *
     * @param initialCapacity the initial capacity for this priority queue
     * @throws IllegalArgumentException if {@code initialCapacity} is less
     *         than 1
     */
    public PriorityBlockingQueue(int initialCapacity) {
        this(initialCapacity, null);
    }

    /**
     * Creates a {@code PriorityBlockingQueue} with the specified initial
     * capacity that orders its elements according to the specified
     * comparator.
     *
     * @param initialCapacity the initial capacity for this priority queue
     * @param  comparator the comparator that will be used to order this
     *         priority queue.  If {@code null}, the {@linkplain Comparable
     *         natural ordering} of the elements will be used.
     * @throws IllegalArgumentException if {@code initialCapacity} is less
     *         than 1
     */
    public PriorityBlockingQueue(int initialCapacity,
                                 Comparator<? super E> comparator) {
        if (initialCapacity < 1)
            throw new IllegalArgumentException();
        this.lock = new ReentrantLock();
        this.notEmpty = lock.newCondition();
        this.comparator = comparator;
        this.queue = new Object[initialCapacity];
    }

    /**
     * Creates a {@code PriorityBlockingQueue} containing the elements
     * in the specified collection.  If the specified collection is a
     * {@link SortedSet} or a {@link PriorityQueue}, this
     * priority queue will be ordered according to the same ordering.
     * Otherwise, this priority queue will be ordered according to the
     * {@linkplain Comparable natural ordering} of its elements.
     *
     * @param  c the collection whose elements are to be placed
     *         into this priority queue
     * @throws ClassCastException if elements of the specified collection
     *         cannot be compared to one another according to the priority
     *         queue's ordering
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    public PriorityBlockingQueue(Collection<? extends E> c) {
        this.lock = new ReentrantLock();
        this.notEmpty = lock.newCondition();
        boolean heapify = true; // true if not known to be in heap order
        boolean screen = true;  // true if must screen for nulls
        if (c instanceof SortedSet<?>) {
            SortedSet<? extends E> ss = (SortedSet<? extends E>) c;
            this.comparator = (Comparator<? super E>) ss.comparator();
            heapify = false;
        }
        else if (c instanceof PriorityBlockingQueue<?>) {
            PriorityBlockingQueue<? extends E> pq =
                (PriorityBlockingQueue<? extends E>) c;
            this.comparator = (Comparator<? super E>) pq.comparator();
            screen = false;
            if (pq.getClass() == PriorityBlockingQueue.class) // exact match
                heapify = false;
        }
        Object[] a = c.toArray();
        int n = a.length;
        if (c.getClass() != java.util.ArrayList.class)
            a = Arrays.copyOf(a, n, Object[].class);
        if (screen && (n == 1 || this.comparator != null)) {
            for (int i = 0; i < n; ++i)
                if (a[i] == null)
                    throw new NullPointerException();
        }
        this.queue = a;
        this.size = n;
        if (heapify)
            heapify();
    }

    /**
     * Tries to grow array to accommodate at least one more element
     * (but normally expand by about 50%), giving up (allowing retry)
     * on contention (which we expect to be rare). Call only while
     * holding lock.
     *
     * @param array the heap array
     * @param oldCap the length of the array
     */
    private void tryGrow(Object[] array, int oldCap) {
        // 先释放锁
        lock.unlock(); // must release and then re-acquire main lock
        // newArray，只有线程扩容完成后，它才不会是null。其余时候，它都是null。
        Object[] newArray = null;
        // CAS 尝试将 allocationSpinLock 0 修改为 1，表示加锁
        if (allocationSpinLock == 0 &&
            UNSAFE.compareAndSwapInt(this, allocationSpinLockOffset,
                                     0, 1)) {
            try {
                // 如果就容量小于64，那么容量翻倍再增加两个位置。否则，新容量 = 旧容量 * 1.5。
                int newCap = oldCap + ((oldCap < 64) ?
                                       (oldCap + 2) : // grow faster if small
                                       (oldCap >> 1));
                if (newCap - MAX_ARRAY_SIZE > 0) {    // possible overflow
                    // 处理溢出情况
                    int minCap = oldCap + 1;
                    if (minCap < 0 || minCap > MAX_ARRAY_SIZE)
                        throw new OutOfMemoryError();
                    newCap = MAX_ARRAY_SIZE;
                }
                if (newCap > oldCap && queue == array)
                    // 创建新数组
                    newArray = new Object[newCap];
            } finally {
                // 解锁
                allocationSpinLock = 0;
            }
        }
        if (newArray == null) // back off if another thread is allocating
            // 如果另一个线程正在分配，让当前线程让步
            Thread.yield();
        // 再加锁
        lock.lock();
        if (newArray != null && queue == array) {
            // 指向新的数组，复制数据
            queue = newArray;
            System.arraycopy(array, 0, newArray, 0, oldCap);
        }
    }

    /**
     * Mechanics for poll().  Call only while holding lock.
     */
    // 出队操作
    private E dequeue() {
        int n = size - 1;
        if (n < 0)
            // 队列没有元素，返回 null
            return null;
        else {
            Object[] array = queue;
            // result 表示堆顶元素
            E result = (E) array[0];
            // 获取数组（堆中的）最后一个元素
            E x = (E) array[n];
            array[n] = null;
            Comparator<? super E> cmp = comparator;
            if (cmp == null)
                // 下沉
                siftDownComparable(0, x, array, n);
            else
                // 下沉
                siftDownUsingComparator(0, x, array, n, cmp);
            size = n;
            return result;
        }
    }

    /**
     * Inserts item x at position k, maintaining heap invariant by
     * promoting x up the tree until it is greater than or equal to
     * its parent, or is the root.
     *
     * To simplify and speed up coercions and comparisons. the
     * Comparable and Comparator versions are separated into different
     * methods that are otherwise identical. (Similarly for siftDown.)
     * These methods are static, with heap state as arguments, to
     * simplify use in light of possible comparator exceptions.
     *
     * @param k the position to fill
     * @param x the item to insert
     * @param array the heap array
     */
    /*
     * 插入元素
     * 每次插入元素时，现将元素放到最后一个位置，由底部向上冒泡
     *
     * 堆顶存放的优先级高的元素，关于堆的上浮，假如堆是这样的[1,3,5,6,7,8,9]，流程是
     * 1. 假如要插入 2，先将要插入的元素放到数组最后，先将 2 放到数组最后，[1,3,5,6,7,8,9,  2];
     * 2. 然后依次和父节点做比较；
     * 3. 假如当前元素的优先级要高于父节点，则交换他们的位置，直到新元素上浮到根节点或者父节点的优先级比当前元素高为止；
     */
    private static <T> void siftUpComparable(int k, T x, Object[] array) {
        Comparable<? super T> key = (Comparable<? super T>) x;
        // k 表示，k 初始值是优先队列中的元素个数，所以这里可以看成是先把元素放在数组的 k 位置
        // 假如 k==0，说明已经冒泡到堆顶节点了，
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            // 获取父节点的元素
            Object e = array[parent];
            // 判断待插入节点和父节点的大小
            if (key.compareTo((T) e) >= 0)
                // 待插入节点大，说明已经找到位置了，直接退出循环
                break;
            // 待插入节点小
            array[k] = e;
            // 将 k 赋值为父节点的索引，继续向上冒泡
            k = parent;
        }
        // 插入节点
        array[k] = key;
    }

    // 和 siftUpComparable 逻辑一样，向上浮动
    private static <T> void siftUpUsingComparator(int k, T x, Object[] array,
                                       Comparator<? super T> cmp) {
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Object e = array[parent];
            if (cmp.compare(x, (T) e) >= 0)
                break;
            array[k] = e;
            k = parent;
        }
        array[k] = x;
    }

    /**
     * Inserts item x at position k, maintaining heap invariant by
     * demoting x down the tree repeatedly until it is less than or
     * equal to its children or is a leaf.
     *
     * @param k the position to fill
     * @param x the item to insert
     * @param array the heap array
     * @param n heap size
     */
    /*
     * 下沉操作
     * 删除元素的流程，执行向下冒泡的逻辑，假如要删除 1，先将数组最后一个节点的放到 数组[0] 的位置，将数组的最后一个节点置为 null
     * [9,3,5,6,7,8,null]，获取左右子节点优先级高的节点，最小堆来说就是值小的节点， 和值小的节点交换位置
     * 直到已经比较到底层了或者当前节点优先级比左右子节点都优先
     *
     * @param k     初始值是 0
     * @param x     初始值是 array[0]，就是数组的最后一个元素
     * @param array 数组（堆）
     * @param n     size - 1
     */
    private static <T> void siftDownComparable(int k, T x, Object[] array,
                                               int n) {
        if (n > 0) {
            // key 是优先级最低的节点
            Comparable<? super T> key = (Comparable<? super T>)x;
            // half 表示满二叉树的底层的最左节点的索引值，当 k == half 时，说明当前 key 已经下坠到最底层了，无需再下坠了
            int half = n >>> 1;           // loop while a non-leaf
            while (k < half) {
                // 表示 k 的左右子节点中，优先级高的节点，初始值是左子节点索引
                int child = (k << 1) + 1; // assume left child is least
                Object c = array[child];
                // 右子节点的索引
                int right = child + 1;
                if (right < n &&
                    ((Comparable<? super T>) c).compareTo((T) array[right]) > 0)
                    // 表示有右子节点，且右子节点要比左子节点优先级高
                    c = array[child = right];
                if (key.compareTo((T) c) <= 0)
                    // key 要比 c 的优先级高，找到位置了，退出循环
                    break;
                // 这里说明 key 的优先级要比 c 低
                array[k] = c;
                // 继续向下找
                k = child;
            }
            // 走到这里，说明已经找到合适的位置 k 了，赋值即可
            array[k] = key;
        }
    }

    private static <T> void siftDownUsingComparator(int k, T x, Object[] array,
                                                    int n,
                                                    Comparator<? super T> cmp) {
        if (n > 0) {
            int half = n >>> 1;
            while (k < half) {
                int child = (k << 1) + 1;
                Object c = array[child];
                int right = child + 1;
                if (right < n && cmp.compare((T) c, (T) array[right]) > 0)
                    c = array[child = right];
                if (cmp.compare(x, (T) c) <= 0)
                    break;
                array[k] = c;
                k = child;
            }
            array[k] = x;
        }
    }

    /**
     * Establishes the heap invariant (described above) in the entire tree,
     * assuming nothing about the order of the elements prior to the call.
     */
    private void heapify() {
        Object[] array = queue;
        int n = size;
        int half = (n >>> 1) - 1;
        Comparator<? super E> cmp = comparator;
        if (cmp == null) {
            for (int i = half; i >= 0; i--)
                siftDownComparable(i, (E) array[i], array, n);
        }
        else {
            for (int i = half; i >= 0; i--)
                siftDownUsingComparator(i, (E) array[i], array, n, cmp);
        }
    }

    /**
     * Inserts the specified element into this priority queue.
     *
     * @param e the element to add
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws ClassCastException if the specified element cannot be compared
     *         with elements currently in the priority queue according to the
     *         priority queue's ordering
     * @throws NullPointerException if the specified element is null
     */
    public boolean add(E e) {
        return offer(e);
    }

    /**
     * Inserts the specified element into this priority queue.
     * As the queue is unbounded, this method will never return {@code false}.
     *
     * @param e the element to add
     * @return {@code true} (as specified by {@link Queue#offer})
     * @throws ClassCastException if the specified element cannot be compared
     *         with elements currently in the priority queue according to the
     *         priority queue's ordering
     * @throws NullPointerException if the specified element is null
     */
    /*
     * 插入元素到优先队列，因为是无界的，所以一定会插入元素成功
     */
    public boolean offer(E e) {
        if (e == null)
            throw new NullPointerException();
        final ReentrantLock lock = this.lock;
        lock.lock();
        // n：优先队列的元素个数
        // cap：是数组的长度
        int n, cap;
        Object[] array;
        while ((n = size) >= (cap = (array = queue).length))
            // 队列已经满了，进行扩容操作
            tryGrow(array, cap);
        try {
            Comparator<? super E> cmp = comparator;
            if (cmp == null)
                // 使用自然排序
                siftUpComparable(n, e, array);
            else
                // 使用指定的 Comparator 比较器
                siftUpUsingComparator(n, e, array, cmp);
            // 元素个数加 1
            size = n + 1;
            // 唤醒可能因为队列为空而阻塞的出队线程
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
        return true;
    }

    /**
     * Inserts the specified element into this priority queue.
     * As the queue is unbounded, this method will never block.
     *
     * @param e the element to add
     * @throws ClassCastException if the specified element cannot be compared
     *         with elements currently in the priority queue according to the
     *         priority queue's ordering
     * @throws NullPointerException if the specified element is null
     */
    public void put(E e) {
        offer(e); // never need to block
    }

    /**
     * Inserts the specified element into this priority queue.
     * As the queue is unbounded, this method will never block or
     * return {@code false}.
     *
     * @param e the element to add
     * @param timeout This parameter is ignored as the method never blocks
     * @param unit This parameter is ignored as the method never blocks
     * @return {@code true} (as specified by
     *  {@link BlockingQueue#offer(Object,long,TimeUnit) BlockingQueue.offer})
     * @throws ClassCastException if the specified element cannot be compared
     *         with elements currently in the priority queue according to the
     *         priority queue's ordering
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e, long timeout, TimeUnit unit) {
        return offer(e); // never need to block
    }

    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return dequeue();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 阻塞获取元素
     */
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        E result;
        try {
            while ( (result = dequeue()) == null)
                notEmpty.await();
        } finally {
            lock.unlock();
        }
        return result;
    }

    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        E result;
        try {
            while ( (result = dequeue()) == null && nanos > 0)
                nanos = notEmpty.awaitNanos(nanos);
        } finally {
            lock.unlock();
        }
        return result;
    }

    public E peek() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return (size == 0) ? null : (E) queue[0];
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the comparator used to order the elements in this queue,
     * or {@code null} if this queue uses the {@linkplain Comparable
     * natural ordering} of its elements.
     *
     * @return the comparator used to order the elements in this queue,
     *         or {@code null} if this queue uses the natural
     *         ordering of its elements
     */
    public Comparator<? super E> comparator() {
        return comparator;
    }

    public int size() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Always returns {@code Integer.MAX_VALUE} because
     * a {@code PriorityBlockingQueue} is not capacity constrained.
     * @return {@code Integer.MAX_VALUE} always
     */
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    private int indexOf(Object o) {
        if (o != null) {
            Object[] array = queue;
            int n = size;
            for (int i = 0; i < n; i++)
                if (o.equals(array[i]))
                    return i;
        }
        return -1;
    }

    /**
     * Removes the ith element from queue.
     */
    private void removeAt(int i) {
        Object[] array = queue;
        int n = size - 1;
        if (n == i) // removed last element
            array[i] = null;
        else {
            E moved = (E) array[n];
            array[n] = null;
            Comparator<? super E> cmp = comparator;
            if (cmp == null)
                siftDownComparable(i, moved, array, n);
            else
                siftDownUsingComparator(i, moved, array, n, cmp);
            if (array[i] == moved) {
                if (cmp == null)
                    siftUpComparable(i, moved, array);
                else
                    siftUpUsingComparator(i, moved, array, cmp);
            }
        }
        size = n;
    }

    /**
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.  Returns {@code true} if and only if this queue contained
     * the specified element (or equivalently, if this queue changed as a
     * result of the call).
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     */
    public boolean remove(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int i = indexOf(o);
            if (i == -1)
                return false;
            removeAt(i);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Identity-based version for use in Itr.remove
     */
    void removeEQ(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            Object[] array = queue;
            for (int i = 0, n = size; i < n; i++) {
                if (o == array[i]) {
                    removeAt(i);
                    break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns {@code true} if this queue contains the specified element.
     * More formally, returns {@code true} if and only if this queue contains
     * at least one element {@code e} such that {@code o.equals(e)}.
     *
     * @param o object to be checked for containment in this queue
     * @return {@code true} if this queue contains the specified element
     */
    public boolean contains(Object o) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return indexOf(o) != -1;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an array containing all of the elements in this queue.
     * The returned array elements are in no particular order.
     *
     * <p>The returned array will be "safe" in that no references to it are
     * maintained by this queue.  (In other words, this method must allocate
     * a new array).  The caller is thus free to modify the returned array.
     *
     * <p>This method acts as bridge between array-based and collection-based
     * APIs.
     *
     * @return an array containing all of the elements in this queue
     */
    public Object[] toArray() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return Arrays.copyOf(queue, size);
        } finally {
            lock.unlock();
        }
    }

    public String toString() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = size;
            if (n == 0)
                return "[]";
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < n; ++i) {
                Object e = queue[i];
                sb.append(e == this ? "(this Collection)" : e);
                if (i != n - 1)
                    sb.append(',').append(' ');
            }
            return sb.append(']').toString();
        } finally {
            lock.unlock();
        }
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        return drainTo(c, Integer.MAX_VALUE);
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c, int maxElements) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        if (maxElements <= 0)
            return 0;
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = Math.min(size, maxElements);
            for (int i = 0; i < n; i++) {
                c.add((E) queue[0]); // In this order, in case add() throws.
                dequeue();
            }
            return n;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically removes all of the elements from this queue.
     * The queue will be empty after this call returns.
     */
    public void clear() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            Object[] array = queue;
            int n = size;
            size = 0;
            for (int i = 0; i < n; i++)
                array[i] = null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an array containing all of the elements in this queue; the
     * runtime type of the returned array is that of the specified array.
     * The returned array elements are in no particular order.
     * If the queue fits in the specified array, it is returned therein.
     * Otherwise, a new array is allocated with the runtime type of the
     * specified array and the size of this queue.
     *
     * <p>If this queue fits in the specified array with room to spare
     * (i.e., the array has more elements than this queue), the element in
     * the array immediately following the end of the queue is set to
     * {@code null}.
     *
     * <p>Like the {@link #toArray()} method, this method acts as bridge between
     * array-based and collection-based APIs.  Further, this method allows
     * precise control over the runtime type of the output array, and may,
     * under certain circumstances, be used to save allocation costs.
     *
     * <p>Suppose {@code x} is a queue known to contain only strings.
     * The following code can be used to dump the queue into a newly
     * allocated array of {@code String}:
     *
     *  <pre> {@code String[] y = x.toArray(new String[0]);}</pre>
     *
     * Note that {@code toArray(new Object[0])} is identical in function to
     * {@code toArray()}.
     *
     * @param a the array into which the elements of the queue are to
     *          be stored, if it is big enough; otherwise, a new array of the
     *          same runtime type is allocated for this purpose
     * @return an array containing all of the elements in this queue
     * @throws ArrayStoreException if the runtime type of the specified array
     *         is not a supertype of the runtime type of every element in
     *         this queue
     * @throws NullPointerException if the specified array is null
     */
    public <T> T[] toArray(T[] a) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            int n = size;
            if (a.length < n)
                // Make a new array of a's runtime type, but my contents:
                return (T[]) Arrays.copyOf(queue, size, a.getClass());
            System.arraycopy(queue, 0, a, 0, n);
            if (a.length > n)
                a[n] = null;
            return a;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns an iterator over the elements in this queue. The
     * iterator does not return the elements in any particular order.
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue
     */
    public Iterator<E> iterator() {
        return new Itr(toArray());
    }

    /**
     * Snapshot iterator that works off copy of underlying q array.
     */
    final class Itr implements Iterator<E> {
        final Object[] array; // Array of all elements
        int cursor;           // index of next element to return
        int lastRet;          // index of last element, or -1 if no such

        Itr(Object[] array) {
            lastRet = -1;
            this.array = array;
        }

        public boolean hasNext() {
            return cursor < array.length;
        }

        public E next() {
            if (cursor >= array.length)
                throw new NoSuchElementException();
            lastRet = cursor;
            return (E)array[cursor++];
        }

        public void remove() {
            if (lastRet < 0)
                throw new IllegalStateException();
            removeEQ(array[lastRet]);
            lastRet = -1;
        }
    }

    /**
     * Saves this queue to a stream (that is, serializes it).
     *
     * For compatibility with previous version of this class, elements
     * are first copied to a java.util.PriorityQueue, which is then
     * serialized.
     *
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        lock.lock();
        try {
            // avoid zero capacity argument
            q = new PriorityQueue<E>(Math.max(size, 1), comparator);
            q.addAll(this);
            s.defaultWriteObject();
        } finally {
            q = null;
            lock.unlock();
        }
    }

    /**
     * Reconstitutes this queue from a stream (that is, deserializes it).
     * @param s the stream
     * @throws ClassNotFoundException if the class of a serialized object
     *         could not be found
     * @throws java.io.IOException if an I/O error occurs
     */
    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        try {
            s.defaultReadObject();
            int sz = q.size();
            SharedSecrets.getJavaOISAccess().checkArray(s, Object[].class, sz);
            this.queue = new Object[sz];
            comparator = q.comparator();
            addAll(q);
        } finally {
            q = null;
        }
    }

    // Similar to Collections.ArraySnapshotSpliterator but avoids
    // commitment to toArray until needed
    static final class PBQSpliterator<E> implements Spliterator<E> {
        final PriorityBlockingQueue<E> queue;
        Object[] array;
        int index;
        int fence;

        PBQSpliterator(PriorityBlockingQueue<E> queue, Object[] array,
                       int index, int fence) {
            this.queue = queue;
            this.array = array;
            this.index = index;
            this.fence = fence;
        }

        final int getFence() {
            int hi;
            if ((hi = fence) < 0)
                hi = fence = (array = queue.toArray()).length;
            return hi;
        }

        public Spliterator<E> trySplit() {
            int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
            return (lo >= mid) ? null :
                new PBQSpliterator<E>(queue, array, lo, index = mid);
        }

        @SuppressWarnings("unchecked")
        public void forEachRemaining(Consumer<? super E> action) {
            Object[] a; int i, hi; // hoist accesses and checks from loop
            if (action == null)
                throw new NullPointerException();
            if ((a = array) == null)
                fence = (a = queue.toArray()).length;
            if ((hi = fence) <= a.length &&
                (i = index) >= 0 && i < (index = hi)) {
                do { action.accept((E)a[i]); } while (++i < hi);
            }
        }

        public boolean tryAdvance(Consumer<? super E> action) {
            if (action == null)
                throw new NullPointerException();
            if (getFence() > index && index >= 0) {
                @SuppressWarnings("unchecked") E e = (E) array[index++];
                action.accept(e);
                return true;
            }
            return false;
        }

        public long estimateSize() { return (long)(getFence() - index); }

        public int characteristics() {
            return Spliterator.NONNULL | Spliterator.SIZED | Spliterator.SUBSIZED;
        }
    }

    /**
     * Returns a {@link Spliterator} over the elements in this queue.
     *
     * <p>The returned spliterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The {@code Spliterator} reports {@link Spliterator#SIZED} and
     * {@link Spliterator#NONNULL}.
     *
     * @implNote
     * The {@code Spliterator} additionally reports {@link Spliterator#SUBSIZED}.
     *
     * @return a {@code Spliterator} over the elements in this queue
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return new PBQSpliterator<E>(this, null, 0, -1);
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long allocationSpinLockOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = PriorityBlockingQueue.class;
            allocationSpinLockOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("allocationSpinLock"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
