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

package java.util.concurrent.locks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.LockSupport;

/**
 * A capability-based lock with three modes for controlling read/write
 * access.  The state of a StampedLock consists of a version and mode.
 * Lock acquisition methods return a stamp that represents and
 * controls access with respect to a lock state; "try" versions of
 * these methods may instead return the special value zero to
 * represent failure to acquire access. Lock release and conversion
 * methods require stamps as arguments, and fail if they do not match
 * the state of the lock. The three modes are:
 *
 * <ul>
 *
 *  <li><b>Writing.</b> Method {@link #writeLock} possibly blocks
 *   waiting for exclusive access, returning a stamp that can be used
 *   in method {@link #unlockWrite} to release the lock. Untimed and
 *   timed versions of {@code tryWriteLock} are also provided. When
 *   the lock is held in write mode, no read locks may be obtained,
 *   and all optimistic read validations will fail.  </li>
 *
 *  <li><b>Reading.</b> Method {@link #readLock} possibly blocks
 *   waiting for non-exclusive access, returning a stamp that can be
 *   used in method {@link #unlockRead} to release the lock. Untimed
 *   and timed versions of {@code tryReadLock} are also provided. </li>
 *
 *  <li><b>Optimistic Reading.</b> Method {@link #tryOptimisticRead}
 *   returns a non-zero stamp only if the lock is not currently held
 *   in write mode. Method {@link #validate} returns true if the lock
 *   has not been acquired in write mode since obtaining a given
 *   stamp.  This mode can be thought of as an extremely weak version
 *   of a read-lock, that can be broken by a writer at any time.  The
 *   use of optimistic mode for short read-only code segments often
 *   reduces contention and improves throughput.  However, its use is
 *   inherently fragile.  Optimistic read sections should only read
 *   fields and hold them in local variables for later use after
 *   validation. Fields read while in optimistic mode may be wildly
 *   inconsistent, so usage applies only when you are familiar enough
 *   with data representations to check consistency and/or repeatedly
 *   invoke method {@code validate()}.  For example, such steps are
 *   typically required when first reading an object or array
 *   reference, and then accessing one of its fields, elements or
 *   methods. </li>
 *
 * </ul>
 *
 * <p>This class also supports methods that conditionally provide
 * conversions across the three modes. For example, method {@link
 * #tryConvertToWriteLock} attempts to "upgrade" a mode, returning
 * a valid write stamp if (1) already in writing mode (2) in reading
 * mode and there are no other readers or (3) in optimistic mode and
 * the lock is available. The forms of these methods are designed to
 * help reduce some of the code bloat that otherwise occurs in
 * retry-based designs.
 *
 * <p>StampedLocks are designed for use as internal utilities in the
 * development of thread-safe components. Their use relies on
 * knowledge of the internal properties of the data, objects, and
 * methods they are protecting.  They are not reentrant, so locked
 * bodies should not call other unknown methods that may try to
 * re-acquire locks (although you may pass a stamp to other methods
 * that can use or convert it).  The use of read lock modes relies on
 * the associated code sections being side-effect-free.  Unvalidated
 * optimistic read sections cannot call methods that are not known to
 * tolerate potential inconsistencies.  Stamps use finite
 * representations, and are not cryptographically secure (i.e., a
 * valid stamp may be guessable). Stamp values may recycle after (no
 * sooner than) one year of continuous operation. A stamp held without
 * use or validation for longer than this period may fail to validate
 * correctly.  StampedLocks are serializable, but always deserialize
 * into initial unlocked state, so they are not useful for remote
 * locking.
 *
 * <p>The scheduling policy of StampedLock does not consistently
 * prefer readers over writers or vice versa.  All "try" methods are
 * best-effort and do not necessarily conform to any scheduling or
 * fairness policy. A zero return from any "try" method for acquiring
 * or converting locks does not carry any information about the state
 * of the lock; a subsequent invocation may succeed.
 *
 * <p>Because it supports coordinated usage across multiple lock
 * modes, this class does not directly implement the {@link Lock} or
 * {@link ReadWriteLock} interfaces. However, a StampedLock may be
 * viewed {@link #asReadLock()}, {@link #asWriteLock()}, or {@link
 * #asReadWriteLock()} in applications requiring only the associated
 * set of functionality.
 *
 * <p><b>Sample Usage.</b> The following illustrates some usage idioms
 * in a class that maintains simple two-dimensional points. The sample
 * code illustrates some try/catch conventions even though they are
 * not strictly needed here because no exceptions can occur in their
 * bodies.<br>
 *
 *  <pre>{@code
 * class Point {
 *   private double x, y;
 *   private final StampedLock sl = new StampedLock();
 *
 *   void move(double deltaX, double deltaY) { // an exclusively locked method
 *     long stamp = sl.writeLock();
 *     try {
 *       x += deltaX;
 *       y += deltaY;
 *     } finally {
 *       sl.unlockWrite(stamp);
 *     }
 *   }
 *
 *   double distanceFromOrigin() { // A read-only method
 *     long stamp = sl.tryOptimisticRead();
 *     double currentX = x, currentY = y;
 *     if (!sl.validate(stamp)) {
 *        stamp = sl.readLock();
 *        try {
 *          currentX = x;
 *          currentY = y;
 *        } finally {
 *           sl.unlockRead(stamp);
 *        }
 *     }
 *     return Math.sqrt(currentX * currentX + currentY * currentY);
 *   }
 *
 *   void moveIfAtOrigin(double newX, double newY) { // upgrade
 *     // Could instead start with optimistic, not read mode
 *     long stamp = sl.readLock();
 *     try {
 *       while (x == 0.0 && y == 0.0) {
 *         long ws = sl.tryConvertToWriteLock(stamp);
 *         if (ws != 0L) {
 *           stamp = ws;
 *           x = newX;
 *           y = newY;
 *           break;
 *         }
 *         else {
 *           sl.unlockRead(stamp);
 *           stamp = sl.writeLock();
 *         }
 *       }
 *     } finally {
 *       sl.unlock(stamp);
 *     }
 *   }
 * }}</pre>
 *
 * @since 1.8
 * @author Doug Lea
 */

/**
 * 一种 capability-based 的锁，具有三种模式用于控制读/写访问。
 * StampedLock的状态由版本和模式组成。
 * 锁的采集方法返回一个表示和控制相对于锁定状态的访问的印记; 这些方法的“try”版本可能会返回特殊值为零以表示获取访问失败。
 * 锁的释放和转换方法要求 stamp 作为参数，如果它们与锁的状态不匹配则失败。 这三种模式是：
 *
 * Writing
 * 方法 writeLock() 可能阻塞等待独占访问的权限，返回一个在 unlockWrite(long) 方法中使用的 stamp，用于释放锁。
 * 也提供了不支持超时的和超时版本 tryWriteLock 方法。
 * 当锁保持写入模式时，不能获得读取锁定，并且所有乐观读取验证都将失败。
 *
 * Reading
 * 方法 readLock() 可能会阻塞等待非独占的访问，
 * 返回可用于方法 unlockRead(long) 释放锁的 stamp。 也提供了支持超时和不支持超时的 tryReadLock
 *
 * Optimistic Reading.
 * 方法 tryOptimisticRead() 只有当锁当前未保持在写入模式时才返回非 0 stamp。
 * 方法 validate(long) 在锁未在给的的 stamp 的写模式下获取，才返回 true
 * 这种模式可以被认为是一个非常弱的版本的读锁，可以随时由写模式打破。
 * 对简单的只读代码段使用乐观模式通常会减少争用并提高吞吐量。
 * 然而，其使用本质上是脆弱的。 乐观读 部分只能读取字段并将其保存在局部变量中，以供后验证使用。
 * 以乐观模式读取的字段可能会非常不一致，因此只有在熟悉数据表示以检查一致性和/或重复调用方法validate()时，使用情况才适用。
 * 例如，当首次读取对象或数组引用，然后访问其字段，元素或方法之一时，通常需要这样的步骤。
 *
 * 此类还支持有条件地在三种模式下提供转换的方法。
 * 例如，方法tryConvertToWriteLock(long)尝试“升级”模式，
 * 如果（1）在读取模式下已经在写入模式（2）中并且没有其他读取器或（3）处于乐观模式并且锁可用，则返回有效写入戳记。
 * 这些方法的形式旨在帮助减少在基于重试的设计中出现的一些代码膨胀。
 */
public class StampedLock implements java.io.Serializable {
    /*
     * Algorithmic notes:
     *
     * The design employs elements of Sequence locks
     * (as used in linux kernels; see Lameter's
     * http://www.lameter.com/gelato2005.pdf
     * and elsewhere; see
     * Boehm's http://www.hpl.hp.com/techreports/2012/HPL-2012-68.html)
     * and Ordered RW locks (see Shirako et al
     * http://dl.acm.org/citation.cfm?id=2312015)
     *
     * Conceptually, the primary state of the lock includes a sequence
     * number that is odd when write-locked and even otherwise.
     * However, this is offset by a reader count that is non-zero when
     * read-locked.  The read count is ignored when validating
     * "optimistic" seqlock-reader-style stamps.  Because we must use
     * a small finite number of bits (currently 7) for readers, a
     * supplementary reader overflow word is used when the number of
     * readers exceeds the count field. We do this by treating the max
     * reader count value (RBITS) as a spinlock protecting overflow
     * updates.
     *
     * Waiters use a modified form of CLH lock used in
     * AbstractQueuedSynchronizer (see its internal documentation for
     * a fuller account), where each node is tagged (field mode) as
     * either a reader or writer. Sets of waiting readers are grouped
     * (linked) under a common node (field cowait) so act as a single
     * node with respect to most CLH mechanics.  By virtue of the
     * queue structure, wait nodes need not actually carry sequence
     * numbers; we know each is greater than its predecessor.  This
     * simplifies the scheduling policy to a mainly-FIFO scheme that
     * incorporates elements of Phase-Fair locks (see Brandenburg &
     * Anderson, especially http://www.cs.unc.edu/~bbb/diss/).  In
     * particular, we use the phase-fair anti-barging rule: If an
     * incoming reader arrives while read lock is held but there is a
     * queued writer, this incoming reader is queued.  (This rule is
     * responsible for some of the complexity of method acquireRead,
     * but without it, the lock becomes highly unfair.) Method release
     * does not (and sometimes cannot) itself wake up cowaiters. This
     * is done by the primary thread, but helped by any other threads
     * with nothing better to do in methods acquireRead and
     * acquireWrite.
     *
     * These rules apply to threads actually queued. All tryLock forms
     * opportunistically try to acquire locks regardless of preference
     * rules, and so may "barge" their way in.  Randomized spinning is
     * used in the acquire methods to reduce (increasingly expensive)
     * context switching while also avoiding sustained memory
     * thrashing among many threads.  We limit spins to the head of
     * queue. A thread spin-waits up to SPINS times (where each
     * iteration decreases spin count with 50% probability) before
     * blocking. If, upon wakening it fails to obtain lock, and is
     * still (or becomes) the first waiting thread (which indicates
     * that some other thread barged and obtained lock), it escalates
     * spins (up to MAX_HEAD_SPINS) to reduce the likelihood of
     * continually losing to barging threads.
     *
     * Nearly all of these mechanics are carried out in methods
     * acquireWrite and acquireRead, that, as typical of such code,
     * sprawl out because actions and retries rely on consistent sets
     * of locally cached reads.
     *
     * As noted in Boehm's paper (above), sequence validation (mainly
     * method validate()) requires stricter ordering rules than apply
     * to normal volatile reads (of "state").  To force orderings of
     * reads before a validation and the validation itself in those
     * cases where this is not already forced, we use
     * Unsafe.loadFence.
     *
     * The memory layout keeps lock state and queue pointers together
     * (normally on the same cache line). This usually works well for
     * read-mostly loads. In most other cases, the natural tendency of
     * adaptive-spin CLH locks to reduce memory contention lessens
     * motivation to further spread out contended locations, but might
     * be subject to future improvements.
     */

    /*
     * 从概念上讲，锁的主要状态包括一个序列号，当写锁定时为奇数，否则为偶数。
     * 但是，当读锁定时，这会被非零的读计数抵消。验证“乐观”的 seqlock-reader-style stamps 时，将忽略读取计数。
     * 因为我们必须为读操作使用少量有限的位（当前为7位），所以当读操作的数量超过计数字段时，会使用一个附加的读计数的溢出字。
     * 为此，我们将最大读计数值（RBITS）视为一个自旋锁，以保护溢出更新。
     *
     * Waiter 使用一种根据 AbstractQueuedSynchronizer（有关更完整的帐户，请参阅其内部文档） 改良的CLH锁，
     * 其中每个节点都被标记为 reader 或 writer（字段模式）.
     * waiter readers 的集合被分组（链接）在一个公共节点（字段cowait）下，因此对于大多数CLH机制来说，它们作为单个节点。
     * 由于队列结构，wait 节点实际上不需要保存序列数字；我们知道每一个都比它的前驱更大。
     * 这将调度策略简化为以FIFO为主的方案，该方案包含了Phase Fair锁的元素（请参见Brandenburg&Anderson，尤其是http://www.cs.unc.edu/~bbb/diss/）。
     * 特别是，我们使用了阶段公平反议价规则：如果传入的 reader 在保持读锁的同时到达，但队列中存在 reader ，则此传入 reader 需要排队。
     * （这条规则造成了方法acquireRead的一些复杂性，但如果没有它，锁就会变得非常不公平。）方法发布本身不会（有时也不会）唤醒cowaiter。
     * 这是由主线程完成的，但在方法acquireRead 和 acquireWrite。
     *
     * 这些规则适用于实际排队的线程。
     * 所有tryLock表单都会机会尝试获取锁，而不考虑首选项规则，因此可能会“闯入”它们。
     * 随机旋转用于获取方法中，以减少（日益昂贵的）上下文切换，同时避免许多线程之间的持续内存颠簸。
     * 我们将自旋限制在队列的最前端。线程旋转最多等待SPINS次（其中每个迭代以50%的概率减少自旋计数）。
     * 如果在唤醒时无法获得锁，并且仍然是（或成为）第一个等待线程（这表明其他线程已阻塞并获得锁），
     * 则它会升级旋转（最大为MAX_HEAD_spins），以减少不断丢失阻塞线程的可能性。
     *
     * 几乎所有这些机制都是在方法acquireWrite和acquireRead中执行的，作为此类代码的典型代表，
     * 方法的扩展是因为操作和重试依赖于一致的本地缓存读取集。
     *
     * 正如Boehm的论文（上文）所述，序列验证（主要是方法validate（））需要比普通volatile读取（“state”）更严格的排序规则。
     * 为了在验证之前强制读取顺序，以及在尚未强制执行的情况下强制验证本身，我们使用Unsafe.loadFence。
     *
     * 内存布局将锁定状态和队列指针保持在一起（通常在同一缓存线上）。
     * 这通常适用于以读取为主的负载。在大多数其他情况下，
     * 自适应自旋CLH锁减少内存争用的自然趋势降低了进一步扩展争用位置的动机，但可能会受到未来改进的影响。
     */

    private static final long serialVersionUID = -6001602636862214147L;

    /** Number of processors, for spin control */
    // 用于自旋，CPU 核心数
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    /** Maximum number of retries before enqueuing on acquisition */
    // 尝试获取锁时，如果超过该值还未获取到锁，则进入等待队列
    // 获取时排队前的最大重试次数
    // 1 << 6 = 100 0000 = 64
    private static final int SPINS = (NCPU > 1) ? 1 << 6 : 0;

    /** Maximum number of retries before blocking at head on acquisition */
    // 等待队列的首节点，自旋获取锁失败时会继续阻塞
    private static final int HEAD_SPINS = (NCPU > 1) ? 1 << 10 : 0;

    /** Maximum number of retries before re-blocking */
    // 再次进入阻塞之前的最大重试次数
    private static final int MAX_HEAD_SPINS = (NCPU > 1) ? 1 << 16 : 0;

    /** The period for yielding when waiting for overflow spinlock */
    private static final int OVERFLOW_YIELD_RATE = 7; // must be power 2 - 1

    /** The number of bits to use for reader count before overflowing */
    // 溢出前用于读取器计数的位数
    // 用于计算state值的位常量
    private static final int LG_READERS = 7;

    // Values for lock state and stamp operations

    // 每获取一个读锁的增加值
    private static final long RUNIT = 1L;
    // 写锁标志位 1000 0000
    private static final long WBIT  = 1L << LG_READERS;
    // 读状态标志 0111 1111
    private static final long RBITS = WBIT - 1L;
    // 读锁的最大数量 0111 1110    126个
    private static final long RFULL = RBITS - 1L;
    // 用于获取读写状态 1111 1111
    private static final long ABITS = RBITS | WBIT;
    // 11111111 11111111 11111111 11111111 11111111 11111111 11111111 1000 0000
    private static final long SBITS = ~RBITS; // note overlap with ABITS

    // Initial value for lock state; avoid failure value zero
    // 初始 state 值  1 0000 0000
    private static final long ORIGIN = WBIT << 1;

    // Special value from cancelled acquire methods so caller can throw IE
    // TODO-KWOK 取消的获取方法的特殊值，以便调用方可以抛出IE
    // 特殊值：
    private static final long INTERRUPTED = 1L;

    // Values for node status; order matters
    // 节点的状态
    private static final int WAITING   = -1;
    private static final int CANCELLED =  1;

    // Modes for nodes (int not boolean to allow arithmetic)
    // 节点的模式
    private static final int RMODE = 0;
    private static final int WMODE = 1;

    /** Wait nodes */
    // 等待队列的节点
    static final class WNode {
        // 前驱节点
        volatile WNode prev;
        // 后驱节点
        volatile WNode next;
        // 指向一个栈，用于保存读线程
        volatile WNode cowait;    // list of linked readers
        volatile Thread thread;   // non-null while possibly parked
        volatile int status;      // 0, WAITING, or CANCELLED
        // 读写模式
        final int mode;           // RMODE or WMODE
        WNode(int m, WNode p) { mode = m; prev = p; }
    }

    /** Head of CLH queue */
    // 指向头节点
    private transient volatile WNode whead;
    /** Tail (last) of CLH queue */
    // 指向尾节点
    private transient volatile WNode wtail;

    // views
    // 这些视图其实是对StampedLock方法的封装，便于习惯了ReentrantReadWriteLock的用户使用
    transient ReadLockView readLockView;
    transient WriteLockView writeLockView;
    transient ReadWriteLockView readWriteLockView;

    /** Lock sequence/state */
    // 同步状态state，处于写锁使用第8位（为1表示占用），读锁使用前7位（为1~126，附加的 readerOverflow 用于当读锁超过 126 时）
    private transient volatile long state;
    /** extra reader count when state read count saturated */
    // 因为读锁只使用了前 7 位，所以当超过对应数值之后需要使用一个 int 型保存
    private transient int readerOverflow;

    /**
     * Creates a new lock, initially in unlocked state.
     */
    public StampedLock() {
        state = ORIGIN;
    }

    /**
     * Exclusively acquires the lock, blocking if necessary
     * until available.
     *
     * @return a stamp that can be used to unlock or convert mode
     */
    // 获取写锁，独占获取锁，必要时阻塞直到锁可用
    // 不响应中断，返回一个 0 值表示成功，用于解锁或者转换锁模式
    public long writeLock() {
        long s, next;  // bypass acquireWrite in fully unlocked case only
        // 条件 1： ((s = state) & ABITS) == 0L 表示读锁和写锁都未被使用
        // 条件 2：U.compareAndSwapLong(this, STATE, s, next = s + WBIT)  CAS 将第 8 位置为 1，表示写锁被占用
        // 条件成功则返回  s + WBIT，就是新的 state，失败则调用 acquireWrite 方法加入等待队列
        return ((((s = state) & ABITS) == 0L &&
                 U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ?
                next : acquireWrite(false, 0L));
    }

    /**
     * Exclusively acquires the lock if it is immediately available.
     *
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     */
    /*
     * 非阻塞的获取写锁，
     * 如果获取写锁失败返回 stamp 为 0，
     * 如果当前处于无锁状态并且 cas 更新 StampedLock 的 state 属性成功，返回 s + WBIT(1<<7) 的 stamp
     */
    public long tryWriteLock() {
        long s, next;
        return ((((s = state) & ABITS) == 0L &&
                 U.compareAndSwapLong(this, STATE, s, next = s + WBIT)) ?
                next : 0L);
    }

    /**
     * Exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long,TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    // 超时的获取写锁，并且支持中断操作
    public long tryWriteLock(long time, TimeUnit unit)
        throws InterruptedException {
        long nanos = unit.toNanos(time);
        if (!Thread.interrupted()) {
            long next, deadline;
            if ((next = tryWriteLock()) != 0L)
                // 说明获取写锁成功了
                return next;
            if (nanos <= 0L)
                return 0L;
            if ((deadline = System.nanoTime() + nanos) == 0L)
                deadline = 1L;
            // 调用 acquireWrite 方法尝试获取写锁
            if ((next = acquireWrite(true, deadline)) != INTERRUPTED)
                return next;
        }
        throw new InterruptedException();
    }

    /**
     * Exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    // 中断的获取写锁，获取不到写锁抛出中断异常
    public long writeLockInterruptibly() throws InterruptedException {
        long next;
        if (!Thread.interrupted() &&
            (next = acquireWrite(true, 0L)) != INTERRUPTED)
            return next;
        throw new InterruptedException();
    }

    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available.
     *
     * @return a stamp that can be used to unlock or convert mode
     */
    // 获取读锁，获取共享锁
    // 不响应中断，返回非 0 表示成功
    public long readLock() {
        long s = state, next;  // bypass acquireRead on common uncontended case
        // 条件 1：whead == wtail 头指针和尾指针指向同一个位置，说明等待队列没有元素
        // 条件 2：(s & ABITS) < RFULL 表示写锁未占用且读锁数量未超过最大值
        // 条件 3：U.compareAndSwapLong(this, STATE, s, next = s + RUNIT)  CAS state 值
        return ((whead == wtail && (s & ABITS) < RFULL &&
                 U.compareAndSwapLong(this, STATE, s, next = s + RUNIT)) ?
                next : acquireRead(false, 0L));
    }

    /**
     * Non-exclusively acquires the lock if it is immediately available.
     *
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     */
    public long tryReadLock() {
        for (;;) {
            long s, m, next;
            if ((m = (s = state) & ABITS) == WBIT)
                return 0L;
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                    return next;
            }
            else if ((next = tryIncReaderOverflow(s)) != 0L)
                return next;
        }
    }

    /**
     * Non-exclusively acquires the lock if it is available within the
     * given time and the current thread has not been interrupted.
     * Behavior under timeout and interruption matches that specified
     * for method {@link Lock#tryLock(long,TimeUnit)}.
     *
     * @param time the maximum time to wait for the lock
     * @param unit the time unit of the {@code time} argument
     * @return a stamp that can be used to unlock or convert mode,
     * or zero if the lock is not available
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long tryReadLock(long time, TimeUnit unit)
        throws InterruptedException {
        long s, m, next, deadline;
        long nanos = unit.toNanos(time);
        if (!Thread.interrupted()) {
            if ((m = (s = state) & ABITS) != WBIT) {
                if (m < RFULL) {
                    if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                        return next;
                }
                else if ((next = tryIncReaderOverflow(s)) != 0L)
                    return next;
            }
            if (nanos <= 0L)
                return 0L;
            if ((deadline = System.nanoTime() + nanos) == 0L)
                deadline = 1L;
            if ((next = acquireRead(true, deadline)) != INTERRUPTED)
                return next;
        }
        throw new InterruptedException();
    }

    /**
     * Non-exclusively acquires the lock, blocking if necessary
     * until available or the current thread is interrupted.
     * Behavior under interruption matches that specified
     * for method {@link Lock#lockInterruptibly()}.
     *
     * @return a stamp that can be used to unlock or convert mode
     * @throws InterruptedException if the current thread is interrupted
     * before acquiring the lock
     */
    public long readLockInterruptibly() throws InterruptedException {
        long next;
        if (!Thread.interrupted() &&
            (next = acquireRead(true, 0L)) != INTERRUPTED)
            return next;
        throw new InterruptedException();
    }

    /**
     * Returns a stamp that can later be validated, or zero
     * if exclusively locked.
     *
     * @return a stamp, or zero if exclusively locked
     */
    public long tryOptimisticRead() {
        long s;
        return (((s = state) & WBIT) == 0L) ? (s & SBITS) : 0L;
    }

    /**
     * Returns true if the lock has not been exclusively acquired
     * since issuance of the given stamp. Always returns false if the
     * stamp is zero. Always returns true if the stamp represents a
     * currently held lock. Invoking this method with a value not
     * obtained from {@link #tryOptimisticRead} or a locking method
     * for this lock has no defined effect or result.
     *
     * @param stamp a stamp
     * @return {@code true} if the lock has not been exclusively acquired
     * since issuance of the given stamp; else false
     */
    public boolean validate(long stamp) {
        U.loadFence();
        return (stamp & SBITS) == (state & SBITS);
    }

    /**
     * If the lock state matches the given stamp, releases the
     * exclusive lock.
     *
     * @param stamp a stamp returned by a write-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    public void unlockWrite(long stamp) {
        WNode h;
        if (state != stamp || (stamp & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        state = (stamp += WBIT) == 0L ? ORIGIN : stamp;
        if ((h = whead) != null && h.status != 0)
            release(h);
    }

    /**
     * If the lock state matches the given stamp, releases the
     * non-exclusive lock.
     *
     * @param stamp a stamp returned by a read-lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    public void unlockRead(long stamp) {
        long s, m; WNode h;
        for (;;) {
            if (((s = state) & SBITS) != (stamp & SBITS) ||
                (stamp & ABITS) == 0L || (m = s & ABITS) == 0L || m == WBIT)
                throw new IllegalMonitorStateException();
            if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    break;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)
                break;
        }
    }

    /**
     * If the lock state matches the given stamp, releases the
     * corresponding mode of the lock.
     *
     * @param stamp a stamp returned by a lock operation
     * @throws IllegalMonitorStateException if the stamp does
     * not match the current state of this lock
     */
    public void unlock(long stamp) {
        long a = stamp & ABITS, m, s; WNode h;
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L)
                break;
            else if (m == WBIT) {
                if (a != m)
                    break;
                state = (s += WBIT) == 0L ? ORIGIN : s;
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return;
            }
            else if (a == 0L || a >= WBIT)
                break;
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)
                return;
        }
        throw new IllegalMonitorStateException();
    }

    /**
     * If the lock state matches the given stamp, performs one of
     * the following actions. If the stamp represents holding a write
     * lock, returns it.  Or, if a read lock, if the write lock is
     * available, releases the read lock and returns a write stamp.
     * Or, if an optimistic read, returns a write stamp only if
     * immediately available. This method returns zero in all other
     * cases.
     *
     * @param stamp a stamp
     * @return a valid write stamp, or zero on failure
     */
    public long tryConvertToWriteLock(long stamp) {
        long a = stamp & ABITS, m, s, next;
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L) {
                if (a != 0L)
                    break;
                if (U.compareAndSwapLong(this, STATE, s, next = s + WBIT))
                    return next;
            }
            else if (m == WBIT) {
                if (a != m)
                    break;
                return stamp;
            }
            else if (m == RUNIT && a != 0L) {
                if (U.compareAndSwapLong(this, STATE, s,
                                         next = s - RUNIT + WBIT))
                    return next;
            }
            else
                break;
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp, performs one of
     * the following actions. If the stamp represents holding a write
     * lock, releases it and obtains a read lock.  Or, if a read lock,
     * returns it. Or, if an optimistic read, acquires a read lock and
     * returns a read stamp only if immediately available. This method
     * returns zero in all other cases.
     *
     * @param stamp a stamp
     * @return a valid read stamp, or zero on failure
     */
    public long tryConvertToReadLock(long stamp) {
        long a = stamp & ABITS, m, s, next; WNode h;
        while (((s = state) & SBITS) == (stamp & SBITS)) {
            if ((m = s & ABITS) == 0L) {
                if (a != 0L)
                    break;
                else if (m < RFULL) {
                    if (U.compareAndSwapLong(this, STATE, s, next = s + RUNIT))
                        return next;
                }
                else if ((next = tryIncReaderOverflow(s)) != 0L)
                    return next;
            }
            else if (m == WBIT) {
                if (a != m)
                    break;
                state = next = s + (WBIT + RUNIT);
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return next;
            }
            else if (a != 0L && a < WBIT)
                return stamp;
            else
                break;
        }
        return 0L;
    }

    /**
     * If the lock state matches the given stamp then, if the stamp
     * represents holding a lock, releases it and returns an
     * observation stamp.  Or, if an optimistic read, returns it if
     * validated. This method returns zero in all other cases, and so
     * may be useful as a form of "tryUnlock".
     *
     * @param stamp a stamp
     * @return a valid optimistic read stamp, or zero on failure
     */
    public long tryConvertToOptimisticRead(long stamp) {
        long a = stamp & ABITS, m, s, next; WNode h;
        U.loadFence();
        for (;;) {
            if (((s = state) & SBITS) != (stamp & SBITS))
                break;
            if ((m = s & ABITS) == 0L) {
                if (a != 0L)
                    break;
                return s;
            }
            else if (m == WBIT) {
                if (a != m)
                    break;
                state = next = (s += WBIT) == 0L ? ORIGIN : s;
                if ((h = whead) != null && h.status != 0)
                    release(h);
                return next;
            }
            else if (a == 0L || a >= WBIT)
                break;
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, next = s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return next & SBITS;
                }
            }
            else if ((next = tryDecReaderOverflow(s)) != 0L)
                return next & SBITS;
        }
        return 0L;
    }

    /**
     * Releases the write lock if it is held, without requiring a
     * stamp value. This method may be useful for recovery after
     * errors.
     *
     * @return {@code true} if the lock was held, else false
     */
    public boolean tryUnlockWrite() {
        long s; WNode h;
        if (((s = state) & WBIT) != 0L) {
            state = (s += WBIT) == 0L ? ORIGIN : s;
            if ((h = whead) != null && h.status != 0)
                release(h);
            return true;
        }
        return false;
    }

    /**
     * Releases one hold of the read lock if it is held, without
     * requiring a stamp value. This method may be useful for recovery
     * after errors.
     *
     * @return {@code true} if the read lock was held, else false
     */
    public boolean tryUnlockRead() {
        long s, m; WNode h;
        while ((m = (s = state) & ABITS) != 0L && m < WBIT) {
            if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    return true;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)
                return true;
        }
        return false;
    }

    // status monitoring methods

    /**
     * Returns combined state-held and overflow read count for given
     * state s.
     */
    private int getReadLockCount(long s) {
        long readers;
        if ((readers = s & RBITS) >= RFULL)
            readers = RFULL + readerOverflow;
        return (int) readers;
    }

    /**
     * Returns {@code true} if the lock is currently held exclusively.
     *
     * @return {@code true} if the lock is currently held exclusively
     */
    public boolean isWriteLocked() {
        return (state & WBIT) != 0L;
    }

    /**
     * Returns {@code true} if the lock is currently held non-exclusively.
     *
     * @return {@code true} if the lock is currently held non-exclusively
     */
    public boolean isReadLocked() {
        return (state & RBITS) != 0L;
    }

    /**
     * Queries the number of read locks held for this lock. This
     * method is designed for use in monitoring system state, not for
     * synchronization control.
     * @return the number of read locks held
     */
    public int getReadLockCount() {
        return getReadLockCount(state);
    }

    /**
     * Returns a string identifying this lock, as well as its lock
     * state.  The state, in brackets, includes the String {@code
     * "Unlocked"} or the String {@code "Write-locked"} or the String
     * {@code "Read-locks:"} followed by the current number of
     * read-locks held.
     *
     * @return a string identifying this lock, as well as its lock state
     */
    public String toString() {
        long s = state;
        return super.toString() +
            ((s & ABITS) == 0L ? "[Unlocked]" :
             (s & WBIT) != 0L ? "[Write-locked]" :
             "[Read-locks:" + getReadLockCount(s) + "]");
    }

    // views

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #readLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link
     * Lock#newCondition()} throws {@code
     * UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asReadLock() {
        ReadLockView v;
        return ((v = readLockView) != null ? v :
                (readLockView = new ReadLockView()));
    }

    /**
     * Returns a plain {@link Lock} view of this StampedLock in which
     * the {@link Lock#lock} method is mapped to {@link #writeLock},
     * and similarly for other methods. The returned Lock does not
     * support a {@link Condition}; method {@link
     * Lock#newCondition()} throws {@code
     * UnsupportedOperationException}.
     *
     * @return the lock
     */
    public Lock asWriteLock() {
        WriteLockView v;
        return ((v = writeLockView) != null ? v :
                (writeLockView = new WriteLockView()));
    }

    /**
     * Returns a {@link ReadWriteLock} view of this StampedLock in
     * which the {@link ReadWriteLock#readLock()} method is mapped to
     * {@link #asReadLock()}, and {@link ReadWriteLock#writeLock()} to
     * {@link #asWriteLock()}.
     *
     * @return the lock
     */
    public ReadWriteLock asReadWriteLock() {
        ReadWriteLockView v;
        return ((v = readWriteLockView) != null ? v :
                (readWriteLockView = new ReadWriteLockView()));
    }

    // view classes

    /**
     * 读锁
     */
    final class ReadLockView implements Lock {
        public void lock() { readLock(); }
        public void lockInterruptibly() throws InterruptedException {
            readLockInterruptibly();
        }
        public boolean tryLock() { return tryReadLock() != 0L; }
        public boolean tryLock(long time, TimeUnit unit)
            throws InterruptedException {
            return tryReadLock(time, unit) != 0L;
        }
        public void unlock() { unstampedUnlockRead(); }
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 写锁
     */
    final class WriteLockView implements Lock {
        public void lock() { writeLock(); }
        public void lockInterruptibly() throws InterruptedException {
            writeLockInterruptibly();
        }
        public boolean tryLock() { return tryWriteLock() != 0L; }
        public boolean tryLock(long time, TimeUnit unit)
            throws InterruptedException {
            return tryWriteLock(time, unit) != 0L;
        }
        public void unlock() { unstampedUnlockWrite(); }
        public Condition newCondition() {
            throw new UnsupportedOperationException();
        }
    }

    final class ReadWriteLockView implements ReadWriteLock {
        public Lock readLock() { return asReadLock(); }
        public Lock writeLock() { return asWriteLock(); }
    }

    // Unlock methods without stamp argument checks for view classes.
    // Needed because view-class lock methods throw away stamps.

    final void unstampedUnlockWrite() {
        WNode h; long s;
        if (((s = state) & WBIT) == 0L)
            throw new IllegalMonitorStateException();
        state = (s += WBIT) == 0L ? ORIGIN : s;
        if ((h = whead) != null && h.status != 0)
            release(h);
    }

    final void unstampedUnlockRead() {
        for (;;) {
            long s, m; WNode h;
            if ((m = (s = state) & ABITS) == 0L || m >= WBIT)
                throw new IllegalMonitorStateException();
            else if (m < RFULL) {
                if (U.compareAndSwapLong(this, STATE, s, s - RUNIT)) {
                    if (m == RUNIT && (h = whead) != null && h.status != 0)
                        release(h);
                    break;
                }
            }
            else if (tryDecReaderOverflow(s) != 0L)
                break;
        }
    }

    private void readObject(java.io.ObjectInputStream s)
        throws java.io.IOException, ClassNotFoundException {
        s.defaultReadObject();
        state = ORIGIN; // reset to unlocked state
    }

    // internals

    /**
     * Tries to increment readerOverflow by first setting state
     * access bits value to RBITS, indicating hold of spinlock,
     * then updating, then releasing.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    // 尝试通过首先将状态访问位值设置为 RBITS 来增加 readerOverflow，指示持有自旋锁，然后更新，然后释放。
    private long tryIncReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        // 条件成立：说明当前正好是尝试获取第 126 个读锁的线程
        if ((s & ABITS) == RFULL) {
            // cas 更新 state 状态
            if (U.compareAndSwapLong(this, STATE, s, s | RBITS)) {
                // 溢出的计数自增
                ++readerOverflow;
                state = s;
                return s;
            }
        }
        else if ((LockSupport.nextSecondarySeed() & OVERFLOW_YIELD_RATE) == 0)
            // 线程让步
            Thread.yield();
        return 0L;
    }

    /**
     * Tries to decrement readerOverflow.
     *
     * @param s a reader overflow stamp: (s & ABITS) >= RFULL
     * @return new stamp on success, else zero
     */
    private long tryDecReaderOverflow(long s) {
        // assert (s & ABITS) >= RFULL;
        if ((s & ABITS) == RFULL) {
            if (U.compareAndSwapLong(this, STATE, s, s | RBITS)) {
                int r; long next;
                if ((r = readerOverflow) > 0) {
                    readerOverflow = r - 1;
                    next = s;
                }
                else
                    next = s - RUNIT;
                 state = next;
                 return next;
            }
        }
        else if ((LockSupport.nextSecondarySeed() &
                  OVERFLOW_YIELD_RATE) == 0)
            Thread.yield();
        return 0L;
    }

    /**
     * Wakes up the successor of h (normally whead). This is normally
     * just h.next, but may require traversal from wtail if next
     * pointers are lagging. This may fail to wake up an acquiring
     * thread when one or more have been cancelled, but the cancel
     * methods themselves provide extra safeguards to ensure liveness.
     */
    private void release(WNode h) {
        if (h != null) {
            WNode q; Thread w;
            U.compareAndSwapInt(h, WSTATUS, WAITING, 0);
            if ((q = h.next) == null || q.status == CANCELLED) {
                for (WNode t = wtail; t != null && t != h; t = t.prev)
                    if (t.status <= 0)
                        q = t;
            }
            if (q != null && (w = q.thread) != null)
                U.unpark(w);
        }
    }

    /**
     * See above for explanation.
     *
     * @param interruptible true if should check interrupts and if so
     * return INTERRUPTED
     * @param deadline if nonzero, the System.nanoTime value to timeout
     * at (and return zero)
     * @return next state, or INTERRUPTED
     */
    /**
     * 总共两个自旋操作：
     * 第一个自旋做的事情：
     *      在创建 node 节点插插入队列前，如果当前持有的是独占锁，且 wtail 和 whead 为同一个节点，
     *      则自旋 SPINS 次尝试获取锁。自旋完成之后再进行队列初始化（如果还没初始化的话）、
     *      为当前线程创建 node 节点、检查 wtail 节点是否改变和 CAS 设置当前节点为 wtail 节点4个小步骤，
     *      在每个小步骤进行前都会先CAS进行一次获取锁尝试。
     *
     *      1.每次自旋会判断，假如当前 stampedLock 的状态 state 是 0，说明没有线程持有写锁或者读锁，
     *          此时会尝试 CAS 修改 state 的状态；
     *      2.假如当前有线程持有独占锁，且 wtail 和 whead 相等，则尝试自旋获取锁，
     *          自旋次数的校验，会根据一个伪随机数，随机的减少自旋次数，每次自旋都校验第 1 步；
     *      3.当自旋次数 spin 减到 0 时，假如等待队列还未初始化，此时需要初始化等待队列，
     *          初始化的时候就是创建一个 WMODE 节点，whead 和 wtail 指针都指向这个节点；
     *      4.创建一个 WMODE 节点并将该节点，尝试 CAS 添加到等待队列的尾部，wtail 指针指向这个新创建的节点；
     *
     *  第二个自旋做的事情：
     *      阻塞线程部分逻辑包含两个嵌套的循环，外部循环用于进行操作，内部循环用于自旋获取 CAS 尝试获取锁。
     *      在阻塞线程前，如果 node 的前驱节点§ 为 whead 节点，则自旋 HEAD_SPINS 次进行 CAS 尝试获取锁，
     *      只要 node 的前驱节点§ 为 whead 节点的条件满足，将重复进入自旋，每一次进入自旋都会将自旋次数增大一倍，
     *      直到自旋次数大于或等于 MAX_HEAD_SPINS，条件不满足时如果 whead 的 cowait 栈不为空，将唤醒 cowait 栈。
     *
     *      完成以上操作后，再次判断 head 节点是否变化，当前节点的前驱节点是否变化。如果当前节点的前驱节点状态未设置，
     *      将状态设置为 WAITING，状态为 CANCELLED 则将前驱节点移出队列。都校验通过后，计算阻塞时间是否超时，
     *      再次判断前驱节是否等待状态、当前是否持有锁，whead 和前驱节点是否改变，都检查通过后将阻塞线程。
     *
     *      线程被唤醒后判断是否是接受到中断信号，如果 interruptible 为 true，接受到中断信号将取消当前节点，
     *      并返回 INTERRUPTED 中断标志。
     *
     *      1.如果 node 节点的前驱节点是 whead 节点，那么会在 head 处自旋获取写锁，同样是通过伪随机数减少自旋次数 spin，
     *          假如真的获取写锁成功了，那么会将刚才入队的节点作为新的 whead 节点，之前的旧节点移除队列
     */
    private long acquireWrite(boolean interruptible, long deadline) {
        /*
         * p：指向队列最后一个节点
         */
        WNode node = null, p;
        // 该自旋主要作用是创建节点并入队
        // 首先是尝试自旋一段时间，让线程在这里自旋等待获取读锁，自旋一段次数后就会将当前线程封装成节点入队
        for (int spins = -1;;) { // spin while enqueuing
            /*
             * m：state 的低 8 位的值
             * s：state
             * ns: 感觉是 nextState 的缩写，就是 CAS 更新后的 state
             */
            long m, s, ns;
            // 条件成立：说明当前没有线程持有读锁和写锁
            if ((m = (s = state) & ABITS) == 0L) {
                // 尝试 CAS 更新 state 值，将写锁状态位加 1，更新成功直接返回新的状态
                if (U.compareAndSwapLong(this, STATE, s, ns = s + WBIT))
                    return ns;
            }
            // 前置条件：m != 0 说明当前有线程持有读锁或者写锁
            // 条件成立：
            else if (spins < 0)
                // 更新 spins 的值
                // m == WBIT && wtail == whead 表明当前有线程持有写锁，且等待队列是空的，则赋值 SPINS，否则 0
                spins = (m == WBIT && wtail == whead) ? SPINS : 0;
            // 前置条件：m != 0 && spins >= 0
            // 条件成立：
            else if (spins > 0) {
                // 获取一个伪随机数，50%的概率减少自旋计数
                if (LockSupport.nextSecondarySeed() >= 0)
                    --spins;
            }
            // 前置条件：m != 0 && spins == 0 说明已经自旋了一段时间了，此时还有线程持有读锁或者写锁
            // 条件成立：说明队列尾指针是 null，需要初始化队列
            else if ((p = wtail) == null) { // initialize queue
                // 创建 WMODE 节点，并添加到队列中，添加成功后 whead 和 wtail 都是指向 hd 节点
                WNode hd = new WNode(WMODE, null);
                if (U.compareAndSwapObject(this, WHEAD, null, hd))
                    wtail = hd;
            }
            // 前置条件：m != 0 && spins == 0 && 队列中有元素
            // 条件成立：说明还未创建节点
            else if (node == null)
                node = new WNode(WMODE, p);
            // 前置条件：m != 0 && spins == 0 && 队列中有元素 && node != null
            // 条件成立：说明当前 node 的前驱不是 p，wtail 节点改变，为 node 重新指定前驱节点
            else if (node.prev != p)
                node.prev = p;
            // 前置条件：m != 0 && spins == 0 && 队列中有元素 && node != null && node.prev == p
            // 条件成立：说明 CAS 插入节点到队尾
            else if (U.compareAndSwapObject(this, WTAIL, p, node)) {
                p.next = node;
                break;
            }
        }

        // 走到这里上面的 p 其实指的是 node.prev 节点,阻塞当前线程进行排队
        // 阻塞当前线程，再阻塞当前线程之前，如果头节点和尾节点相等，让其自旋一段时间获取写锁。
        // 如果头结点不为空，释放头节点的cowait队列
        for (int spins = -1;;) {
            /*
             * h：whead 指针，每次自旋都会更新为最新的
             * np：node.prev
             * pp：
             * ps：p.status
             */
            WNode h, np, pp; int ps;
            // 条件成立：新加入的节点的 prev 节点是 whead 节点
            // 这里和 AQS 的意思是一样的，也是只有 head.next 节点才有就机会去尝试回去锁
            if ((h = whead) == p) {
                if (spins < 0)
                    // 初始化自旋次数
                    spins = HEAD_SPINS;
                else if (spins < MAX_HEAD_SPINS)
                    // 如果下面在 head 自旋还未成功，则尝试将自旋次数增大一倍
                    spins <<= 1;

                // 在 head 处自旋获取写锁
                for (int k = spins;;) { // spin at head
                    /*
                     * s：state
                     * ns：nextState
                     */
                    long s, ns;
                    // 条件成立：说明当前没有线程持有写锁或者读锁，则尝试 CAS 更新 state
                    if (((s = state) & ABITS) == 0L) {
                        if (U.compareAndSwapLong(this, STATE, s,
                                                 ns = s + WBIT)) {
                            // 如果写锁设置成功，将其当前节点的前驱节点设置为空，并且将其节点设置为头节点
                            // node 是在第一个 for 循环中创建的新的节点
                            whead = node;
                            node.prev = null;
                            return ns;
                        }
                    }
                    // 伪随机数，减少自旋次数
                    else if (LockSupport.nextSecondarySeed() >= 0 &&
                             --k <= 0)
                        break;
                }
            }
            // 前置条件：h != p，说明 node 不是 whead.next 节点
            // 条件成立：
            else if (h != null) { // help release stale waiters
                // c：
                WNode c; Thread w;
                // 如果头结点的 cowait 队列（RMODE的节点）不为空，唤醒 cowait 队列中阻塞的线程
                // 循环唤醒 whead 的 cowait 栈
                while ((c = h.cowait) != null) {
                    // cowait 节点和对应的节点都不为空唤醒其线程，
                    // 循环的唤醒 cowait 节点队列中 Thread 不为空的线程
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                        (w = c.thread) != null)
                        U.unpark(w);
                }
            }

            // whead 结点未改变
            if (whead == h) {
                // 如果当前节点的前驱节点和尾节点不一致，将 p 设置为当前节点的前驱节点，
                // 可以使节点往头节点移动
                if ((np = node.prev) != p) {
                    if (np != null)
                        (p = np).next = node;   // stale
                }
                // 如果当前节点的前驱节点状态为0，将其前驱节点设置为等待状态
                else if ((ps = p.status) == 0)
                    U.compareAndSwapInt(p, WSTATUS, 0, WAITING);
                // 如果当前节点的前驱节点状态为取消
                else if (ps == CANCELLED) {
                    // 重新设置前驱节点，移除原前驱节点
                    if ((pp = p.prev) != null) {
                        node.prev = pp;
                        pp.next = node;
                    }
                }
                else {
                    // 传入0，表示阻塞直到 UnSafe.unpark 唤醒
                    long time; // 0 argument to park means no timeout
                    if (deadline == 0L)
                        time = 0L;
                    // 已经等待超时，取消当前等待节点
                    else if ((time = deadline - System.nanoTime()) <= 0L)
                        return cancelWaiter(node, node, false);
                    Thread wt = Thread.currentThread();
                    // 设置线程 Thread 的 parkblocker 属性，表示当前线程被谁阻塞，用于监控线程使用
                    U.putObject(wt, PARKBLOCKER, this);
                    // 将其当前线程设置为当前节点
                    node.thread = wt;
                    // 当前节点的前驱节点为等待状态，并且队列的头节点不是前驱节点或者当前状态为有锁状态
                    // 队列头节点和当前节点的前驱节点未改变，则阻塞当前线程
                    if (p.status < 0 && (p != h || (state & ABITS) != 0L) &&
                        whead == h && node.prev == p)
                        U.park(false, time);  // emulate LockSupport.park
                    // 将当前node的线程设置为null
                    node.thread = null;
                    // 当前线程的监控对象也置为空
                    U.putObject(wt, PARKBLOCKER, null);
                    // 如果传入的参数interruptible为true，并且当前线程中断，取消当前节点
                    if (interruptible && Thread.interrupted())
                        return cancelWaiter(node, node, true);
                }
            }
        }
    }

    /**
     * See above for explanation.
     *
     * @param interruptible true if should check interrupts and if so
     * return INTERRUPTED
     * @param deadline if nonzero, the System.nanoTime value to timeout
     * at (and return zero)
     * @return next state, or INTERRUPTED
     */
    /*
     * 尝试自旋的获取读锁，获取不到则加入等待队列，并阻塞线程
     * interruptible true 表示检车中断，如果线程被中断过，则最终返回 INTERRUPTED
     * deadline 如果不是 0，则表示限时获取
     * 返回值，非 0 表示获取成功，INTERRUPTED 表示中途被中断过
     */
    private long acquireRead(boolean interruptible, long deadline) {
        WNode node = null, p;
        /*
         * 自旋入队操作
         * 如果写锁未被占用, 则立即尝试获取读锁, 获取成功则返回.
         * 如果写锁被占用, 则将当前读线程包装成结点, 并插入等待队列（如果队尾是写结点,直接链接到队尾;否则,链接到队尾读结点的栈中）
         */
        /** 如果头结点和尾节点相等，先让其线程自旋一段时间，如果队列为空初始化队列，
         生成头结点和尾节点。如果自旋操作没有获取到锁，并且头结点和尾节点相等，或者当前
         stampedLock的状态为写锁状态，将其当前节点加入队列中，如果加入当前队列失败，
         或者头结点和尾节点不相等，或者当前处于读锁状态，将其加入尾节点的cwait中，
         如果头结点的cwait节点不为空，并且线程也不为空，唤醒其cwait队列，阻塞当前节点**/
        for (int spins = -1;;) {
            // 当前循环的头指针
            WNode h;
            // 条件成立则说明当前队列是空的或者只有头节点，则会立即尝试获取读锁
            // 如果头尾节点相等，先让其自旋一段时间
            if ((h = whead) == (p = wtail)) {
                /*
                 * m：state 的低 8 位
                 * s：state
                 * ns：nextState 更新后的新的 state 值
                 */
                for (long m, s, ns;;) {
                    // 条件：m = (s = state) & ABITS) < RFULL 说明当前没有线程获取读锁，判断读锁是否已经超过了 126
                    // 写锁未被占用且读锁未超过 126，则尝试 CAS 更新 state，更新成功则直接返回 ns
                    // 写锁未被占用且读锁超过了 126，则调用 tryIncReaderOverflow 将溢出的加到 readerOverflow 字段，返回 0 表示加失败了
                    if ((m = (s = state) & ABITS) < RFULL ?
                            U.compareAndSwapLong(this, STATE, s, ns = s + RUNIT) :
                            (m < WBIT && (ns = tryIncReaderOverflow(s)) != 0L))
                        return ns;
                    // 前置条件是上面的 if 不满足
                    // m >= WBIT 表示写锁已经被占用了，以随机方式探测是否需要退出循环
                    else if (m >= WBIT) {
                        if (spins > 0) {
                            // 获取个伪随机数
                            if (LockSupport.nextSecondarySeed() >= 0)
                                --spins;
                        }
                        else {
                            // 自旋足够次数后，spins 减到 0 了
                            if (spins == 0) {
                                WNode nh = whead, np = wtail;
                                // nh == h && np == p 说明队列的头节点和尾结点都没改的过
                                // (h = nh) != (p = np) 前置条件是队列的头节点和尾结点有改动，但是新的头节点 != 尾结点
                                if ((nh == h && np == p) || (h = nh) != (p = np))
                                    break;
                            }
                            // 第一次走到这里赋值自旋次数
                            spins = SPINS;
                        }
                    }
                }
            }
            // 走到这里说明读锁获取失败了
            // p == null 表示队列为空, 则初始化队列(构造头结点)
            if (p == null) { // initialize queue
                WNode hd = new WNode(WMODE, null);
                if (U.compareAndSwapObject(this, WHEAD, null, hd))
                    wtail = hd;
            }
            // 前置条件：p != null，说明等待队列中已经有节点了
            // 条件成立：需要将当前先包装成读节点
            else if (node == null)
                node = new WNode(RMODE, p);
            // 前置条件：p != null && node != null 说明队列有元素且当前节点已经创建好了
            // 条件成立：如果队列只有一个头结点, 或队尾结点不是读结点, 则直接将结点链接到队尾, 链接完成后退出自旋
            // 如果头结点和尾节点相等，或者当前StampedLock的state状态不为读锁状态
            else if (h == p || p.mode != RMODE) {
                // 如果当前节点的前驱节点不是尾节点，重新设置当前节点的前驱节点
                if (node.prev != p)
                    node.prev = p;
                // 将其当前节点加入队列中，并且当前节点做为尾节点，如果成功，直接退出循环操作
                else if (U.compareAndSwapObject(this, WTAIL, p, node)) {
                    p.next = node;
                    break;
                }
            }
            // 前置条件：p != null && node != null TODO-KWOK
            // 队列不为空, 且队尾是读结点, 则将添加当前结点链接到队尾结点的cowait链中（实际上构成一个栈, p是栈顶指针 ）
            // 这里就是头插法，将 node 插入到 p 的 cowait 的上面
            else if (!U.compareAndSwapObject(p, WCOWAIT,
                                             node.cowait = p.cowait, node))
                // 插入失败则清空
                node.cowait = null;
            // 前置条件：p != null && node != null TODO-KWOK {占位} 且上面的 else if 的 CAS 操作成功了
            // 如果当前队列不为空，当前节点不为空，并且头结点和尾节点不相等，并且当前
            // StampedLock的状态为读锁状态，并且当前节点cas加入尾节点的cowait队列中失败
            else {
                for (;;) {
                    WNode pp, c; Thread w;
                    // 尝试唤醒头结点的cowait中的第一个元素, 假如是读锁会通过循环释放cowait链
                    // 如果头结点的cowait队列不为空，并且其线程也不为null，将其cowait队列唤醒
                    if ((h = whead) != null && (c = h.cowait) != null &&
                        U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                        (w = c.thread) != null) // help release
                        // 唤醒cowait队列中的节点线程
                        U.unpark(w);
                    // 如果当前头结点为尾节点的前驱节点，或者头尾节点相等，或者尾节点的前驱节点为空
                    if (h == (pp = p.prev) || h == p || pp == null) {
                        long m, s, ns;
                        do {
                            // 判断当前状态是否处于读锁状态，如果是，并且读锁没有溢出，state进行cas加1操作
                            if ((m = (s = state) & ABITS) < RFULL ?
                                U.compareAndSwapLong(this, STATE, s, ns = s + RUNIT) :
                                (m < WBIT && (ns = tryIncReaderOverflow(s)) != 0L))  // 否则进行溢出操作，看上面tryIncReaderOverflow方法介绍
                                return ns;
                        // 当前StampedLock的state状态不是写模式，才能进行循环操作
                        } while (m < WBIT);
                    }
                    // 如果头结点没有改变，并且尾节点的前驱节点不变
                    if (whead == h && p.prev == pp) {
                        long time;
                        // 如果尾节点的前驱节点为空，或者头尾节点相等，或者尾节点的状态为取消
                        if (pp == null || h == p || p.status > 0) {
                            // 将其当前节点设置为空，退出循环
                            node = null; // throw away
                            break;
                        }
                        // 如果超时时间为0，会一直阻塞，直到调用UnSafe的unpark方法
                        if (deadline == 0L)
                            time = 0L;
                        // 如果传入的超时时间已经过期，将当前节点取消，
                        // 看上面cancelWaiter方法的介绍
                        else if ((time = deadline - System.nanoTime()) <= 0L)
                            return cancelWaiter(node, p, false);
                        Thread wt = Thread.currentThread();
                        U.putObject(wt, PARKBLOCKER, this);
                        // 将其当前节点的线程设置为当前线程
                        node.thread = wt;
                        // 如果头节点和尾节点的前驱节点不相等，或者当前StampedLock的
                        // state状态为写锁，并且头结点不变，尾节点的前驱节点不变
                        if ((h != pp || (state & ABITS) == WBIT) &&
                            whead == h && p.prev == pp)
                            // 写锁被占用, 且当前结点不是队首结点, 则阻塞当前线程
                            U.park(false, time);
                        // 将其当前节点的线程置为空
                        node.thread = null;
                        // 将其当前线程的监控对象置为空
                        U.putObject(wt, PARKBLOCKER, null);
                        // 如果传入进来的interruptible是要求中断的，并且当前线程被中断
                        if (interruptible && Thread.interrupted())
                            return cancelWaiter(node, p, true);
                    }
                }
            }
        }

        for (int spins = -1;;) { // 依然会再次尝试获取读锁，如果这次再获取不到，就会将前驱的等待状态置为WAITING, 表示我（当前线程）要去睡了（阻塞），到时记得叫醒我：
            WNode h, np, pp; int ps;
            if ((h = whead) == p) { // 假如当前线程是队首节点
                if (spins < 0)
                    spins = HEAD_SPINS;
                else if (spins < MAX_HEAD_SPINS)
                    spins <<= 1;
                for (int k = spins;;) { // spin at head // 在头节点自旋，尝试获取锁
                    long m, s, ns;
                    if ((m = (s = state) & ABITS) < RFULL ?
                            U.compareAndSwapLong(this, STATE, s, ns = s + RUNIT) :
                            (m < WBIT && (ns = tryIncReaderOverflow(s)) != 0L)) {
                        WNode c; Thread w; // 进入到这里说明，没有线程持有写锁，且读锁的计数增加完成
                        whead = node;
                        node.prev = null; // 释放头结点, 当前队首结点成为新的头结点
                        while ((c = node.cowait) != null) { // 获取读锁成功，释放cowait链中的所有读结点
                            if (U.compareAndSwapObject(node, WCOWAIT,
                                                       c, c.cowait) &&
                                (w = c.thread) != null)
                                U.unpark(w);
                        }
                        return ns;
                    }
                    else if (m >= WBIT &&
                             LockSupport.nextSecondarySeed() >= 0 && --k <= 0)
                        break;
                }
            }
            else if (h != null) { // 如果头结点存在cowait链, 则唤醒链中所有读线程
                WNode c; Thread w;
                while ((c = h.cowait) != null) {
                    if (U.compareAndSwapObject(h, WCOWAIT, c, c.cowait) &&
                        (w = c.thread) != null)
                        U.unpark(w);
                }
            }
            if (whead == h) {
                if ((np = node.prev) != p) {
                    if (np != null)
                        (p = np).next = node;   // stale
                }
                else if ((ps = p.status) == 0) // 将前驱结点的等待状态置为WAITING, 表示之后将唤醒当前结点
                    U.compareAndSwapInt(p, WSTATUS, 0, WAITING);
                else if (ps == CANCELLED) {
                    if ((pp = p.prev) != null) {
                        node.prev = pp;
                        pp.next = node;
                    }
                }
                else {
                    long time;
                    if (deadline == 0L)
                        time = 0L;
                    else if ((time = deadline - System.nanoTime()) <= 0L)
                        return cancelWaiter(node, node, false);
                    Thread wt = Thread.currentThread();
                    U.putObject(wt, PARKBLOCKER, this);
                    node.thread = wt;
                    if (p.status < 0 &&
                        (p != h || (state & ABITS) == WBIT) &&
                        whead == h && node.prev == p)
                        U.park(false, time);
                    node.thread = null;
                    U.putObject(wt, PARKBLOCKER, null);
                    if (interruptible && Thread.interrupted())
                        return cancelWaiter(node, node, true);
                }
            }
        }
    }

    /**
     * If node non-null, forces cancel status and unsplices it from
     * queue if possible and wakes up any cowaiters (of the node, or
     * group, as applicable), and in any case helps release current
     * first waiter if lock is free. (Calling with null arguments
     * serves as a conditional form of release, which is not currently
     * needed but may be needed under possible future cancellation
     * policies). This is a variant of cancellation methods in
     * AbstractQueuedSynchronizer (see its detailed explanation in AQS
     * internal documentation).
     *
     * @param node if nonnull, the waiter
     * @param group either node or the group node is cowaiting with
     * @param interrupted if already interrupted
     * @return INTERRUPTED if interrupted or Thread.interrupted, else zero
     */
    /**
     * cancelWaiter 方法如果节点非空，则强制取消状态并在可能的情况下将其从队列中解开并唤醒任何 cowaiters（节点或组，如适用），
     * 并且在任何情况下如果锁空闲，则有助于释放当前的第一个 waiter。
     * （使用空参数调用作为释放的条件形式，目前不需要，但在未来可能的取消政策下可能需要）。
     * 这是 AbstractQueuedSynchronizer 中取消方法的一种变体（请参阅 AQS 内部文档中的详细说明）。
     */
    private long cancelWaiter(WNode node, WNode group, boolean interrupted) {
        if (node != null && group != null) {
            // 将其当前节点的状态设置为取消状态
            Thread w;
            node.status = CANCELLED;
            // unsplice cancelled nodes from group
            // 遍历 group 的cowait栈，清除CANCELLED节点
            for (WNode p = group, q; (q = p.cowait) != null;) {
                if (q.status == CANCELLED) {
                    U.compareAndSwapObject(p, WCOWAIT, q, q.cowait);
                    // 从头开始遍历
                    p = group; // restart
                }
                else
                    p = q;
            }
            // node和group相同
            if (group == node) {
                // 唤醒group的cowait栈
                for (WNode r = group.cowait; r != null; r = r.cowait) {
                    if ((w = r.thread) != null)
                        U.unpark(w);       // wake up uncancelled co-waiters
                }
                // 将当前取消接节点的前驱节点的下一个节点设置为当前取消节点的next节点
                for (WNode pred = node.prev; pred != null; ) { // unsplice
                    WNode succ, pp;        // find valid successor
                    // 如果当前取消节点的下一个节点为空或者是取消状态，从尾节点开始寻找有效的节点
                    // 并重新指定下一个节点
                    while ((succ = node.next) == null ||
                           succ.status == CANCELLED) {
                        WNode q = null;    // find successor the slow way
                        // 从wtail向前遍历到node，找到离node最近一个未被取消的节点
                        for (WNode t = wtail; t != null && t != node; t = t.prev)
                            if (t.status != CANCELLED)
                                q = t;     // don't link if succ cancelled
                        if (succ == q ||   // ensure accurate successor
                            // 修改node的后继节点为最近一个未被取消的节点
                            U.compareAndSwapObject(node, WNEXT,
                                                   succ, succ = q)) {
                            // 当前节点后继节点为空，且当前节点为wtail节点
                            if (succ == null && node == wtail)
                                // 将当前节点的前置节点设置为wtail
                                U.compareAndSwapObject(this, WTAIL, node, pred);
                            // 退出循环
                            break;
                        }
                    }
                    if (pred.next == node) // unsplice pred link
                        // 修改node前置节点的next为node后最近一个未被取消的节点
                        U.compareAndSwapObject(pred, WNEXT, node, succ);
                    // 后继节点存在，且在线程阻塞状态
                    if (succ != null && (w = succ.thread) != null) {
                        // 唤醒后继节点
                        succ.thread = null;
                        U.unpark(w);       // wake up succ to observe new pred
                    }
                    // 当前节点的前驱节点未被取消，或者前驱接点的前驱节点为空，退出循环
                    if (pred.status != CANCELLED || (pp = pred.prev) == null)
                        break;
                    // 重新设置当前取消节点的前驱节点
                    node.prev = pp;        // repeat if new pred wrong/cancelled
                    // 重新设置当前取消节点的前驱节点
                    U.compareAndSwapObject(pp, WNEXT, pred, succ);
                    // 将其前驱节点设置为pp，重新循环
                    pred = pp;
                }
            }
        }
        WNode h; // Possibly release first waiter
        // 头节点不为空，尝试唤醒头结点的后继节点
        while ((h = whead) != null) {
            long s; WNode q; // similar to release() but check eligibility
            // 头节点的下一节点为空或者是取消状态，从尾节点开始寻找有效的节点（包括等待状态，和运行状态）
            if ((q = h.next) == null || q.status == CANCELLED) {
                for (WNode t = wtail; t != null && t != h; t = t.prev)
                    if (t.status <= 0)
                        q = t;
            }
            // 如果头节点没有改变
            if (h == whead) {
                // 头节点的下一有效节点不为空，并且头节点的状态为0，并且当前StampedLock的不为写锁状态
                // 并且头节点的下一节点为读模式，唤醒头结点的下一节点
                if (q != null && h.status == 0 &&
                    ((s = state) & ABITS) != WBIT && // waiter is eligible
                    (s == 0L || q.mode == RMODE))
                    // 唤醒头结点的下一有效节点，下面会介绍release方法
                    release(h);
                break;
            }
        }
        //如果当前线程被中断或者传入进来的interrupted为true，直接返回中断标志位，否则返回0
        return (interrupted || Thread.interrupted()) ? INTERRUPTED : 0L;
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final long STATE;
    private static final long WHEAD;
    private static final long WTAIL;
    private static final long WNEXT;
    private static final long WSTATUS;
    private static final long WCOWAIT;
    private static final long PARKBLOCKER;

    static {
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> k = StampedLock.class;
            Class<?> wk = WNode.class;
            STATE = U.objectFieldOffset
                (k.getDeclaredField("state"));
            WHEAD = U.objectFieldOffset
                (k.getDeclaredField("whead"));
            WTAIL = U.objectFieldOffset
                (k.getDeclaredField("wtail"));
            WSTATUS = U.objectFieldOffset
                (wk.getDeclaredField("status"));
            WNEXT = U.objectFieldOffset
                (wk.getDeclaredField("next"));
            WCOWAIT = U.objectFieldOffset
                (wk.getDeclaredField("cowait"));
            Class<?> tk = Thread.class;
            PARKBLOCKER = U.objectFieldOffset
                (tk.getDeclaredField("parkBlocker"));

        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
