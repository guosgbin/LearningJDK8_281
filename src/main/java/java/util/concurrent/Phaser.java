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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * A reusable synchronization barrier, similar in functionality to
 * {@link java.util.concurrent.CyclicBarrier CyclicBarrier} and
 * {@link java.util.concurrent.CountDownLatch CountDownLatch}
 * but supporting more flexible usage.
 *
 * <p><b>Registration.</b> Unlike the case for other barriers, the
 * number of parties <em>registered</em> to synchronize on a phaser
 * may vary over time.  Tasks may be registered at any time (using
 * methods {@link #register}, {@link #bulkRegister}, or forms of
 * constructors establishing initial numbers of parties), and
 * optionally deregistered upon any arrival (using {@link
 * #arriveAndDeregister}).  As is the case with most basic
 * synchronization constructs, registration and deregistration affect
 * only internal counts; they do not establish any further internal
 * bookkeeping, so tasks cannot query whether they are registered.
 * (However, you can introduce such bookkeeping by subclassing this
 * class.)
 *
 * <p><b>Synchronization.</b> Like a {@code CyclicBarrier}, a {@code
 * Phaser} may be repeatedly awaited.  Method {@link
 * #arriveAndAwaitAdvance} has effect analogous to {@link
 * java.util.concurrent.CyclicBarrier#await CyclicBarrier.await}. Each
 * generation of a phaser has an associated phase number. The phase
 * number starts at zero, and advances when all parties arrive at the
 * phaser, wrapping around to zero after reaching {@code
 * Integer.MAX_VALUE}. The use of phase numbers enables independent
 * control of actions upon arrival at a phaser and upon awaiting
 * others, via two kinds of methods that may be invoked by any
 * registered party:
 *
 * <ul>
 *
 *   <li> <b>Arrival.</b> Methods {@link #arrive} and
 *       {@link #arriveAndDeregister} record arrival.  These methods
 *       do not block, but return an associated <em>arrival phase
 *       number</em>; that is, the phase number of the phaser to which
 *       the arrival applied. When the final party for a given phase
 *       arrives, an optional action is performed and the phase
 *       advances.  These actions are performed by the party
 *       triggering a phase advance, and are arranged by overriding
 *       method {@link #onAdvance(int, int)}, which also controls
 *       termination. Overriding this method is similar to, but more
 *       flexible than, providing a barrier action to a {@code
 *       CyclicBarrier}.
 *
 *   <li> <b>Waiting.</b> Method {@link #awaitAdvance} requires an
 *       argument indicating an arrival phase number, and returns when
 *       the phaser advances to (or is already at) a different phase.
 *       Unlike similar constructions using {@code CyclicBarrier},
 *       method {@code awaitAdvance} continues to wait even if the
 *       waiting thread is interrupted. Interruptible and timeout
 *       versions are also available, but exceptions encountered while
 *       tasks wait interruptibly or with timeout do not change the
 *       state of the phaser. If necessary, you can perform any
 *       associated recovery within handlers of those exceptions,
 *       often after invoking {@code forceTermination}.  Phasers may
 *       also be used by tasks executing in a {@link ForkJoinPool},
 *       which will ensure sufficient parallelism to execute tasks
 *       when others are blocked waiting for a phase to advance.
 *
 * </ul>
 *
 * <p><b>Termination.</b> A phaser may enter a <em>termination</em>
 * state, that may be checked using method {@link #isTerminated}. Upon
 * termination, all synchronization methods immediately return without
 * waiting for advance, as indicated by a negative return value.
 * Similarly, attempts to register upon termination have no effect.
 * Termination is triggered when an invocation of {@code onAdvance}
 * returns {@code true}. The default implementation returns {@code
 * true} if a deregistration has caused the number of registered
 * parties to become zero.  As illustrated below, when phasers control
 * actions with a fixed number of iterations, it is often convenient
 * to override this method to cause termination when the current phase
 * number reaches a threshold. Method {@link #forceTermination} is
 * also available to abruptly release waiting threads and allow them
 * to terminate.
 *
 * <p><b>Tiering.</b> Phasers may be <em>tiered</em> (i.e.,
 * constructed in tree structures) to reduce contention. Phasers with
 * large numbers of parties that would otherwise experience heavy
 * synchronization contention costs may instead be set up so that
 * groups of sub-phasers share a common parent.  This may greatly
 * increase throughput even though it incurs greater per-operation
 * overhead.
 *
 * <p>In a tree of tiered phasers, registration and deregistration of
 * child phasers with their parent are managed automatically.
 * Whenever the number of registered parties of a child phaser becomes
 * non-zero (as established in the {@link #Phaser(Phaser,int)}
 * constructor, {@link #register}, or {@link #bulkRegister}), the
 * child phaser is registered with its parent.  Whenever the number of
 * registered parties becomes zero as the result of an invocation of
 * {@link #arriveAndDeregister}, the child phaser is deregistered
 * from its parent.
 *
 * <p><b>Monitoring.</b> While synchronization methods may be invoked
 * only by registered parties, the current state of a phaser may be
 * monitored by any caller.  At any given moment there are {@link
 * #getRegisteredParties} parties in total, of which {@link
 * #getArrivedParties} have arrived at the current phase ({@link
 * #getPhase}).  When the remaining ({@link #getUnarrivedParties})
 * parties arrive, the phase advances.  The values returned by these
 * methods may reflect transient states and so are not in general
 * useful for synchronization control.  Method {@link #toString}
 * returns snapshots of these state queries in a form convenient for
 * informal monitoring.
 *
 * <p><b>Sample usages:</b>
 *
 * <p>A {@code Phaser} may be used instead of a {@code CountDownLatch}
 * to control a one-shot action serving a variable number of parties.
 * The typical idiom is for the method setting this up to first
 * register, then start the actions, then deregister, as in:
 *
 *  <pre> {@code
 * void runTasks(List<Runnable> tasks) {
 *   final Phaser phaser = new Phaser(1); // "1" to register self
 *   // create and start threads
 *   for (final Runnable task : tasks) {
 *     phaser.register();
 *     new Thread() {
 *       public void run() {
 *         phaser.arriveAndAwaitAdvance(); // await all creation
 *         task.run();
 *       }
 *     }.start();
 *   }
 *
 *   // allow threads to start and deregister self
 *   phaser.arriveAndDeregister();
 * }}</pre>
 *
 * <p>One way to cause a set of threads to repeatedly perform actions
 * for a given number of iterations is to override {@code onAdvance}:
 *
 *  <pre> {@code
 * void startTasks(List<Runnable> tasks, final int iterations) {
 *   final Phaser phaser = new Phaser() {
 *     protected boolean onAdvance(int phase, int registeredParties) {
 *       return phase >= iterations || registeredParties == 0;
 *     }
 *   };
 *   phaser.register();
 *   for (final Runnable task : tasks) {
 *     phaser.register();
 *     new Thread() {
 *       public void run() {
 *         do {
 *           task.run();
 *           phaser.arriveAndAwaitAdvance();
 *         } while (!phaser.isTerminated());
 *       }
 *     }.start();
 *   }
 *   phaser.arriveAndDeregister(); // deregister self, don't wait
 * }}</pre>
 *
 * If the main task must later await termination, it
 * may re-register and then execute a similar loop:
 *  <pre> {@code
 *   // ...
 *   phaser.register();
 *   while (!phaser.isTerminated())
 *     phaser.arriveAndAwaitAdvance();}</pre>
 *
 * <p>Related constructions may be used to await particular phase numbers
 * in contexts where you are sure that the phase will never wrap around
 * {@code Integer.MAX_VALUE}. For example:
 *
 *  <pre> {@code
 * void awaitPhase(Phaser phaser, int phase) {
 *   int p = phaser.register(); // assumes caller not already registered
 *   while (p < phase) {
 *     if (phaser.isTerminated())
 *       // ... deal with unexpected termination
 *     else
 *       p = phaser.arriveAndAwaitAdvance();
 *   }
 *   phaser.arriveAndDeregister();
 * }}</pre>
 *
 *
 * <p>To create a set of {@code n} tasks using a tree of phasers, you
 * could use code of the following form, assuming a Task class with a
 * constructor accepting a {@code Phaser} that it registers with upon
 * construction. After invocation of {@code build(new Task[n], 0, n,
 * new Phaser())}, these tasks could then be started, for example by
 * submitting to a pool:
 *
 *  <pre> {@code
 * void build(Task[] tasks, int lo, int hi, Phaser ph) {
 *   if (hi - lo > TASKS_PER_PHASER) {
 *     for (int i = lo; i < hi; i += TASKS_PER_PHASER) {
 *       int j = Math.min(i + TASKS_PER_PHASER, hi);
 *       build(tasks, i, j, new Phaser(ph));
 *     }
 *   } else {
 *     for (int i = lo; i < hi; ++i)
 *       tasks[i] = new Task(ph);
 *       // assumes new Task(ph) performs ph.register()
 *   }
 * }}</pre>
 *
 * The best value of {@code TASKS_PER_PHASER} depends mainly on
 * expected synchronization rates. A value as low as four may
 * be appropriate for extremely small per-phase task bodies (thus
 * high rates), or up to hundreds for extremely large ones.
 *
 * <p><b>Implementation notes</b>: This implementation restricts the
 * maximum number of parties to 65535. Attempts to register additional
 * parties result in {@code IllegalStateException}. However, you can and
 * should create tiered phasers to accommodate arbitrarily large sets
 * of participants.
 *
 * @since 1.7
 * @author Doug Lea
 */
public class Phaser {
    /*
     * This class implements an extension of X10 "clocks".  Thanks to
     * Vijay Saraswat for the idea, and to Vivek Sarkar for
     * enhancements to extend functionality.
     */

    /**
     * Primary state representation, holding four bit-fields:
     *
     * unarrived  -- the number of parties yet to hit barrier (bits  0-15)
     * parties    -- the number of parties to wait            (bits 16-31)
     * phase      -- the generation of the barrier            (bits 32-62)
     * terminated -- set if barrier is terminated             (bit  63 / sign)
     *
     * Except that a phaser with no registered parties is
     * distinguished by the otherwise illegal state of having zero
     * parties and one unarrived parties (encoded as EMPTY below).
     *
     * To efficiently maintain atomicity, these values are packed into
     * a single (atomic) long. Good performance relies on keeping
     * state decoding and encoding simple, and keeping race windows
     * short.
     *
     * All state updates are performed via CAS except initial
     * registration of a sub-phaser (i.e., one with a non-null
     * parent).  In this (relatively rare) case, we use built-in
     * synchronization to lock while first registering with its
     * parent.
     *
     * The phase of a subphaser is allowed to lag that of its
     * ancestors until it is actually accessed -- see method
     * reconcileState.
     */
    /*
     * 同步状态值，用四个段表示
     * unarrived  -- 未到达屏障的参与者的个数 (bits  0-15)
     * parties    -- 总参与者（需要等待的参与者）的个数 (bits 16-31)
     * phase      -- 当前阶段 (bits 32-62)
     * terminated -- 屏障终止标志 (bit  63 / sign)
     *
     * 初始时，state == 1，EMPTY，表示此 Phaser 对象还没有线程来注册过，
     * 之所以不是 0，是因为 0 有特殊的含义，比如当所有的线程都到达屏障了，此时 unarrived 也是 0
     * 初始时，parties == 0，而 unarrived == 1，更易于辨别
     *
     *
     * 除了没有注册方的移相器的不同之处在于具有零方和一个未到达方的非法状态（以下编码为 EMPTY）。
     * 为了有效地保持原子性，这些值被打包成一个原子类型的 long。良好的性能依赖于保持状态解码和编码简单，并保持比赛窗口短。
     * 所有状态更新都通过 CAS 执行，除了子相位器的初始注册（即具有非 null 父级的子相位器）。
     * 在这种（相对罕见的）情况下，我们在首次向其父级注册时使用内置同步来锁定。
     * 子相位器的相位可以滞后于其祖先的相位，直到它被实际访问——参见方法 reconcileState。
     *
     * long 64 位
     *
     * | 高 1 位 - terminated | 高 31 位 - phase | 中 16 位 - parties | 低 16 位 - unarrived |
     */
    private volatile long state;

    // 最多的参与者个数 65535
    private static final int  MAX_PARTIES     = 0xffff;
    // 最多 PHASE 个数，int 最大值
    private static final int  MAX_PHASE       = Integer.MAX_VALUE;
    // 参与者个数位移数
    private static final int  PARTIES_SHIFT   = 16;
    // PHASE 个数位移数
    private static final int  PHASE_SHIFT     = 32;
    // 11111111 11111111，用于计算未到达的参与者数目
    private static final int  UNARRIVED_MASK  = 0xffff;      // to mask ints
    // 11111111 11111111 00000000 00000000，用于计算参与者数目
    private static final long PARTIES_MASK    = 0xffff0000L; // to mask longs
    // 11111111 11111111 11111111 11111111
    // counts的掩码，counts等于参与者数和未完成的参与者数的'|'操作
    private static final long COUNTS_MASK     = 0xffffffffL;
    // 表示屏障终止的标志位
    private static final long TERMINATION_BIT = 1L << 63;

    // some special values
    // 一个参与者到达
    private static final int  ONE_ARRIVAL     = 1;
    // 一个参与者
    private static final int  ONE_PARTY       = 1 << PARTIES_SHIFT;
    // 注销一个参与者
    private static final int  ONE_DEREGISTER  = ONE_ARRIVAL|ONE_PARTY;
    // 初始值
    private static final int  EMPTY           = 1;

    // The following unpacking methods are usually manually inlined

    // 获取未达到屏障的参与者的数量
    private static int unarrivedOf(long s) {
        int counts = (int)s;
        return (counts == EMPTY) ? 0 : (counts & UNARRIVED_MASK);
    }

    // 获取参与者的数量
    private static int partiesOf(long s) {
        return (int)s >>> PARTIES_SHIFT;
    }

    // 获取 phase 数（阶段数）
    private static int phaseOf(long s) {
        return (int)(s >>> PHASE_SHIFT);
    }

    // 获取到达屏障的线程数，就是    总的参与者个数-未到达的参与者个数
    private static int arrivedOf(long s) {
        int counts = (int)s;
        return (counts == EMPTY) ? 0 :
            (counts >>> PARTIES_SHIFT) - (counts & UNARRIVED_MASK);
    }

    /**
     * The parent of this phaser, or null if none
     */
    // 当前 Phaser 的父级，为 null 表示没有
    private final Phaser parent;

    /**
     * The root of phaser tree. Equals this if not in a tree.
     */
    private final Phaser root;

    /**
     * Heads of Treiber stacks for waiting threads. To eliminate
     * contention when releasing some threads while adding others, we
     * use two of them, alternating across even and odd phases.
     * Subphasers share queues with root to speed up releases.
     */
    // 等待线程的 Treiber 堆栈头。
    // 为了在释放一些线程同时添加其他线程时消除争用，我们使用其中两个，在偶数和奇数阶段交替。
    // Subphasers 与 root 共享队列以加快发布速度
    //
    // evenQ和oddQ，已完成的参与者存储的队列，
    // 当最后一个参与者完成任务后唤醒队列中的参与者继续执行下一个阶段的任务，或者结束任务。
    // 偶数栈
    private final AtomicReference<QNode> evenQ;
    // 奇数栈
    private final AtomicReference<QNode> oddQ;

    private AtomicReference<QNode> queueFor(int phase) {
        return ((phase & 1) == 0) ? evenQ : oddQ;
    }

    /**
     * Returns message string for bounds exceptions on arrival.
     */
    private String badArrive(long s) {
        return "Attempted arrival of unregistered party for " +
            stateToString(s);
    }

    /**
     * Returns message string for bounds exceptions on registration.
     */
    private String badRegister(long s) {
        return "Attempt to register more than " +
            MAX_PARTIES + " parties for " + stateToString(s);
    }

    /**
     * Main implementation for methods arrive and arriveAndDeregister.
     * Manually tuned to speed up and minimize race windows for the
     * common case of just decrementing unarrived field.
     *
     * @param adjust value to subtract from state;
     *               ONE_ARRIVAL for arrive,
     *               ONE_DEREGISTER for arriveAndDeregister
     */
    private int doArrive(int adjust) {
        final Phaser root = this.root;
        for (;;) {
            long s = (root == this) ? state : reconcileState();
            // 获取当前阶段数
            int phase = (int)(s >>> PHASE_SHIFT);
            if (phase < 0)
                return phase;
            int counts = (int)s;
            // 获取当前阶段还有多少参与者未到达屏障
            int unarrived = (counts == EMPTY) ? 0 : (counts & UNARRIVED_MASK);
            if (unarrived <= 0)
                throw new IllegalStateException(badArrive(s));
            // 尝试 CAS 更新 state, 其实就是尝试将 unarrived 减 1
            if (UNSAFE.compareAndSwapLong(this, stateOffset, s, s-=adjust)) {
                // unarrived == 1 说明当前参与者是最后一个到达屏障的
                if (unarrived == 1) {
                    // 组装下一个阶段的 state
                    long n = s & PARTIES_MASK;  // base of next state
                    int nextUnarrived = (int)n >>> PARTIES_SHIFT;
                    if (root == this) {
                        // 调用子类可重写的 onAdvance 方法
                        if (onAdvance(phase, nextUnarrived))
                            n |= TERMINATION_BIT;
                        else if (nextUnarrived == 0)
                            // unarrived 的初始值是 1
                            n |= EMPTY;
                        else
                            n |= nextUnarrived;
                        // 阶段 phase 加 1
                        int nextPhase = (phase + 1) & MAX_PHASE;
                        n |= (long)nextPhase << PHASE_SHIFT;
                        // CAS 更新 state 值
                        UNSAFE.compareAndSwapLong(this, stateOffset, s, n);
                        // 移除节点并唤醒线程
                        releaseWaiters(phase);
                    }
                    else if (nextUnarrived == 0) { // propagate deregistration
                        phase = parent.doArrive(ONE_DEREGISTER);
                        UNSAFE.compareAndSwapLong(this, stateOffset,
                                                  s, s | EMPTY);
                    }
                    else
                        phase = parent.doArrive(ONE_ARRIVAL);
                }
                return phase;
            }
        }
    }

    /**
     * Implementation of register, bulkRegister
     *
     * @param registrations number to add to both parties and
     * unarrived fields. Must be greater than zero.
     */
    //（1）增加一个参与者，需要同时增加parties和unarrived两个数值，也就是state的中16位和低16位；
    //（2）如果是第一个参与者，则尝试原子更新state的值，如果成功了就退出；
    //（3）如果不是第一个参与者，则检查是不是在执行onAdvance()，如果是等待onAdvance()执行完成，
    //      如果否则尝试原子更新state的值，直到成功退出；
    //（4）等待onAdvance()完成是采用先自旋后进入队列排队的方式等待，减少线程上下文切换；
    private int doRegister(int registrations) {
        // adjustment to state
        // 这里相当于 parties 和 unarrived 都加了 registrations
        long adjust = ((long)registrations << PARTIES_SHIFT) | registrations;
        final Phaser parent = this.parent;
        int phase;
        for (;;) {
            long s = (parent == null) ? state : reconcileState();
            int counts = (int)s;
            // 位移获取 parties 和 unarrived
            int parties = counts >>> PARTIES_SHIFT;
            int unarrived = counts & UNARRIVED_MASK;
            // 检查参与者个数是否有溢出
            if (registrations > MAX_PARTIES - parties)
                throw new IllegalStateException(badRegister(s));
            phase = (int)(s >>> PHASE_SHIFT);
            if (phase < 0)
                break;
            // 不是第一个参与者
            if (counts != EMPTY) {                  // not 1st registration
                if (parent == null || reconcileState() == s) {
                    // unarrived 等于 0 说明当前阶段正在执行 onAdvance() 方法，等待其执行完毕
                    if (unarrived == 0)             // wait out advance
                        root.internalAwaitAdvance(phase, null);
                    // 否则就修改 state 的值，增加 adjust，如果成功就跳出循环
                    else if (UNSAFE.compareAndSwapLong(this, stateOffset,
                                                       s, s + adjust))
                        break;
                }
            }
            // 是第一个参与者
            else if (parent == null) {              // 1st root registration
                // 计算 state 的值
                long next = ((long)phase << PHASE_SHIFT) | adjust;
                // 修改 state 的值，如果成功就跳出循环
                if (UNSAFE.compareAndSwapLong(this, stateOffset, s, next))
                    break;
            }
            // 多层级阶段的处理方式
            else {
                synchronized (this) {               // 1st sub registration
                    if (state == s) {               // recheck under lock
                        phase = parent.doRegister(1);
                        if (phase < 0)
                            break;
                        // finish registration whenever parent registration
                        // succeeded, even when racing with termination,
                        // since these are part of the same "transaction".
                        while (!UNSAFE.compareAndSwapLong
                               (this, stateOffset, s,
                                ((long)phase << PHASE_SHIFT) | adjust)) {
                            s = state;
                            phase = (int)(root.state >>> PHASE_SHIFT);
                            // assert (int)s == EMPTY;
                        }
                        break;
                    }
                }
            }
        }
        return phase;
    }

    /**
     * Resolves lagged phase propagation from root if necessary.
     * Reconciliation normally occurs when root has advanced but
     * subphasers have not yet done so, in which case they must finish
     * their own advance by setting unarrived to parties (or if
     * parties is zero, resetting to unregistered EMPTY state).
     *
     * @return reconciled state
     */
    // 调整当前 Phaser 的同步状态值，需要和 root 保持一致，
    // 因为当出现树形结构时，根节点首先进行 phase 的更新，因此需要显示同步，以便和根节点保持一致
    private long reconcileState() {
        final Phaser root = this.root;
        long s = state;
        if (root != this) {
            int phase, p;
            // CAS to root phase with current parties, tripping unarrived
            while ((phase = (int)(root.state >>> PHASE_SHIFT)) !=
                   (int)(s >>> PHASE_SHIFT) &&
                   !UNSAFE.compareAndSwapLong
                   (this, stateOffset, s,
                    s = (((long)phase << PHASE_SHIFT) |
                         ((phase < 0) ? (s & COUNTS_MASK) :
                          (((p = (int)s >>> PARTIES_SHIFT) == 0) ? EMPTY :
                           ((s & PARTIES_MASK) | p))))))
                s = state;
        }
        return s;
    }

    /**
     * Creates a new phaser with no initially registered parties, no
     * parent, and initial phase number 0. Any thread using this
     * phaser will need to first register for it.
     */
    // 创建一个没有初始注册参与者、没有父级和初始phase编号 0 的 phaser 对象。
    // 使用此 phaser 的任何线程都需要先注册它。
    public Phaser() {
        this(null, 0);
    }

    /**
     * Creates a new phaser with the given number of registered
     * unarrived parties, no parent, and initial phase number 0.
     *
     * @param parties the number of parties required to advance to the
     * next phase
     * @throws IllegalArgumentException if parties less than zero
     * or greater than the maximum number of parties supported
     */
    public Phaser(int parties) {
        this(null, parties);
    }

    /**
     * Equivalent to {@link #Phaser(Phaser, int) Phaser(parent, 0)}.
     *
     * @param parent the parent phaser
     */
    public Phaser(Phaser parent) {
        this(parent, 0);
    }

    /**
     * Creates a new phaser with the given parent and number of
     * registered unarrived parties.  When the given parent is non-null
     * and the given number of parties is greater than zero, this
     * child phaser is registered with its parent.
     *
     * @param parent the parent phaser
     * @param parties the number of parties required to advance to the
     * next phase
     * @throws IllegalArgumentException if parties less than zero
     * or greater than the maximum number of parties supported
     */
    public Phaser(Phaser parent, int parties) {
        if (parties >>> PARTIES_SHIFT != 0)
            // 超过了 65535 个
            throw new IllegalArgumentException("Illegal number of parties");
        // 初始状态阶段是 0
        int phase = 0;
        this.parent = parent;
        if (parent != null) {
            // 设置根节点
            final Phaser root = parent.root;
            this.root = root;
            // 共用父节点的奇偶无锁栈
            this.evenQ = root.evenQ;
            this.oddQ = root.oddQ;
            if (parties != 0)
                // 假如参与者不是 0，则向父 Phaser 注册一个参与者，注意这里是 1 个
                phase = parent.doRegister(1);
        }
        else {
            // 根节点设置为自己
            this.root = this;
            // 偶数栈
            this.evenQ = new AtomicReference<QNode>();
            // 奇数栈
            this.oddQ = new AtomicReference<QNode>();
        }
        // 初始化 Phaser 的 state
        this.state = (parties == 0) ? (long)EMPTY :
            ((long)phase << PHASE_SHIFT) |
            ((long)parties << PARTIES_SHIFT) |
            ((long)parties);
    }

    /**
     * Adds a new unarrived party to this phaser.  If an ongoing
     * invocation of {@link #onAdvance} is in progress, this method
     * may await its completion before returning.  If this phaser has
     * a parent, and this phaser previously had no registered parties,
     * this child phaser is also registered with its parent. If
     * this phaser is terminated, the attempt to register has
     * no effect, and a negative value is returned.
     *
     * @return the arrival phase number to which this registration
     * applied.  If this value is negative, then this phaser has
     * terminated, in which case registration has no effect.
     * @throws IllegalStateException if attempting to register more
     * than the maximum supported number of parties
     */
    // 注册单个参与者到 phaser
    public int register() {
        return doRegister(1);
    }

    /**
     * Adds the given number of new unarrived parties to this phaser.
     * If an ongoing invocation of {@link #onAdvance} is in progress,
     * this method may await its completion before returning.  If this
     * phaser has a parent, and the given number of parties is greater
     * than zero, and this phaser previously had no registered
     * parties, this child phaser is also registered with its parent.
     * If this phaser is terminated, the attempt to register has no
     * effect, and a negative value is returned.
     *
     * @param parties the number of additional parties required to
     * advance to the next phase
     * @return the arrival phase number to which this registration
     * applied.  If this value is negative, then this phaser has
     * terminated, in which case registration has no effect.
     * @throws IllegalStateException if attempting to register more
     * than the maximum supported number of parties
     * @throws IllegalArgumentException if {@code parties < 0}
     */
    // 注册多个参与者到 phaser
    public int bulkRegister(int parties) {
        if (parties < 0)
            throw new IllegalArgumentException();
        if (parties == 0)
            return getPhase();
        return doRegister(parties);
    }

    /**
     * Arrives at this phaser, without waiting for others to arrive.
     *
     * <p>It is a usage error for an unregistered party to invoke this
     * method.  However, this error may result in an {@code
     * IllegalStateException} only upon some subsequent operation on
     * this phaser, if ever.
     *
     * @return the arrival phase number, or a negative value if terminated
     * @throws IllegalStateException if not terminated and the number
     * of unarrived parties would become negative
     */
    /*
     * 到达屏障，无需等待其他参与者到达，也就是说不会阻塞当前线程
     * 未注册的参与者调用和这个方法是错误用法，此错误可能仅在此 Phaser 的某个后续操作时导致 IllegalStateException
     *
     * 到达的阶段数，如果终止则为负值
     */
    public int arrive() {
        return doArrive(ONE_ARRIVAL);
    }

    /**
     * Arrives at this phaser and deregisters from it without waiting
     * for others to arrive. Deregistration reduces the number of
     * parties required to advance in future phases.  If this phaser
     * has a parent, and deregistration causes this phaser to have
     * zero parties, this phaser is also deregistered from its parent.
     *
     * <p>It is a usage error for an unregistered party to invoke this
     * method.  However, this error may result in an {@code
     * IllegalStateException} only upon some subsequent operation on
     * this phaser, if ever.
     *
     * @return the arrival phase number, or a negative value if terminated
     * @throws IllegalStateException if not terminated and the number
     * of registered or unarrived parties would become negative
     */
    /*
     * 到达屏障，并且将自己从 Phaser 中注销，无需等待其他参与者到达，也就是说不会阻塞当前线程。
     * 如果有父级 Phaser 也会注销
     */
    public int arriveAndDeregister() {
        return doArrive(ONE_DEREGISTER);
    }

    /**
     * Arrives at this phaser and awaits others. Equivalent in effect
     * to {@code awaitAdvance(arrive())}.  If you need to await with
     * interruption or timeout, you can arrange this with an analogous
     * construction using one of the other forms of the {@code
     * awaitAdvance} method.  If instead you need to deregister upon
     * arrival, use {@code awaitAdvance(arriveAndDeregister())}.
     *
     * <p>It is a usage error for an unregistered party to invoke this
     * method.  However, this error may result in an {@code
     * IllegalStateException} only upon some subsequent operation on
     * this phaser, if ever.
     *
     * @return the arrival phase number, or the (negative)
     * {@linkplain #getPhase() current phase} if terminated
     * @throws IllegalStateException if not terminated and the number
     * of unarrived parties would become negative
     */
    // 到达屏障
    //（1）修改state中unarrived部分的值减1；
    //（2）如果不是最后一个到达的，则调用internalAwaitAdvance()方法自旋或排队等待；
    //（3）如果是最后一个到达的，则调用onAdvance()方法，然后修改state的值为下一阶段对应的值，并唤醒其它等待的线程；
    //（4）返回下一阶段的值；
    public int arriveAndAwaitAdvance() {
        // Specialization of doArrive+awaitAdvance eliminating some reads/paths
        final Phaser root = this.root;
        for (;;) {
            // state的值
            long s = (root == this) ? state : reconcileState();
            // 获取 phase
            int phase = (int)(s >>> PHASE_SHIFT);
            if (phase < 0)
                return phase;
            int counts = (int)s;
            // 获取 unarrived
            int unarrived = (counts == EMPTY) ? 0 : (counts & UNARRIVED_MASK);
            if (unarrived <= 0)
                throw new IllegalStateException(badArrive(s));
            // CAS 尝试更新 unarrived 的值，将其减1
            if (UNSAFE.compareAndSwapLong(this, stateOffset, s, s -= ONE_ARRIVAL)) {
                if (unarrived > 1)
                    // 说明当前线程不是最后一个到达的线程，等待onAdvance()方法执行完毕
                    return root.internalAwaitAdvance(phase, null);
                if (root != this)
                    return parent.arriveAndAwaitAdvance();
                // 获取参与者的数量
                long n = s & PARTIES_MASK;  // base of next state
                // 获取下一个阶段的 unarrived 的值
                int nextUnarrived = (int)n >>> PARTIES_SHIFT;
                // 调用子类可重写的 onAdvance 方法
                if (onAdvance(phase, nextUnarrived))
                    // 置为终止状态
                    n |= TERMINATION_BIT;
                else if (nextUnarrived == 0)
                    n |= EMPTY;
                else
                    // n 加上unarrived的值
                    n |= nextUnarrived;
                // 下一个阶段等待当前阶段加 1
                int nextPhase = (phase + 1) & MAX_PHASE;
                // n 加上下一阶段的值
                n |= (long)nextPhase << PHASE_SHIFT;
                // 修改state的值为n
                if (!UNSAFE.compareAndSwapLong(this, stateOffset, s, n))
                    return (int)(state >>> PHASE_SHIFT); // terminated
                // 唤醒其它参与者并进入下一个阶段
                releaseWaiters(phase);
                return nextPhase;
            }
        }
    }

    /**
     * Awaits the phase of this phaser to advance from the given phase
     * value, returning immediately if the current phase is not equal
     * to the given phase value or this phaser is terminated.
     *
     * @param phase an arrival phase number, or negative value if
     * terminated; this argument is normally the value returned by a
     * previous call to {@code arrive} or {@code arriveAndDeregister}.
     * @return the next arrival phase number, or the argument if it is
     * negative, or the (negative) {@linkplain #getPhase() current phase}
     * if terminated
     */
    /*
     * 这个方法通常是先调用 arrive 或者 arriveAndDeregister 方法。
     * 当当前 phase 阶段值不等于传入的值时或者当前 Phaser 是终止状态，直接返回
     *
     */
    public int awaitAdvance(int phase) {
        final Phaser root = this.root;
        // 获取 state
        long s = (root == this) ? state : reconcileState();
        // 获取当前 phaser 的阶段值
        int p = (int)(s >>> PHASE_SHIFT);
        if (phase < 0)
            return phase;
        if (p == phase)
            // 等待 onAdvance() 方法执行完毕
            return root.internalAwaitAdvance(phase, null);
        return p;
    }

    /**
     * Awaits the phase of this phaser to advance from the given phase
     * value, throwing {@code InterruptedException} if interrupted
     * while waiting, or returning immediately if the current phase is
     * not equal to the given phase value or this phaser is
     * terminated.
     *
     * @param phase an arrival phase number, or negative value if
     * terminated; this argument is normally the value returned by a
     * previous call to {@code arrive} or {@code arriveAndDeregister}.
     * @return the next arrival phase number, or the argument if it is
     * negative, or the (negative) {@linkplain #getPhase() current phase}
     * if terminated
     * @throws InterruptedException if thread interrupted while waiting
     */
    // 可被中断的 awaitAdvance
    public int awaitAdvanceInterruptibly(int phase)
        throws InterruptedException {
        final Phaser root = this.root;
        long s = (root == this) ? state : reconcileState();
        int p = (int)(s >>> PHASE_SHIFT);
        if (phase < 0)
            return phase;
        if (p == phase) {
            // 创建一个 QNode 节点，标记它可以被中断
            QNode node = new QNode(this, phase, true, false, 0L);
            // 自旋，阻塞，等待其他参与者到达
            p = root.internalAwaitAdvance(phase, node);
            if (node.wasInterrupted)
                throw new InterruptedException();
        }
        return p;
    }

    /**
     * Awaits the phase of this phaser to advance from the given phase
     * value or the given timeout to elapse, throwing {@code
     * InterruptedException} if interrupted while waiting, or
     * returning immediately if the current phase is not equal to the
     * given phase value or this phaser is terminated.
     *
     * @param phase an arrival phase number, or negative value if
     * terminated; this argument is normally the value returned by a
     * previous call to {@code arrive} or {@code arriveAndDeregister}.
     * @param timeout how long to wait before giving up, in units of
     *        {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the
     *        {@code timeout} parameter
     * @return the next arrival phase number, or the argument if it is
     * negative, or the (negative) {@linkplain #getPhase() current phase}
     * if terminated
     * @throws InterruptedException if thread interrupted while waiting
     * @throws TimeoutException if timed out while waiting
     */
    // 支持超时，可中断的 awaitAdvance
    public int awaitAdvanceInterruptibly(int phase,
                                         long timeout, TimeUnit unit)
        throws InterruptedException, TimeoutException {
        long nanos = unit.toNanos(timeout);
        final Phaser root = this.root;
        long s = (root == this) ? state : reconcileState();
        int p = (int)(s >>> PHASE_SHIFT);
        if (phase < 0)
            return phase;
        if (p == phase) {
            // 支持超时，支持中断
            QNode node = new QNode(this, phase, true, true, nanos);
            p = root.internalAwaitAdvance(phase, node);
            if (node.wasInterrupted)
                throw new InterruptedException();
            else if (p == phase)
                throw new TimeoutException();
        }
        return p;
    }

    /**
     * Forces this phaser to enter termination state.  Counts of
     * registered parties are unaffected.  If this phaser is a member
     * of a tiered set of phasers, then all of the phasers in the set
     * are terminated.  If this phaser is already terminated, this
     * method has no effect.  This method may be useful for
     * coordinating recovery after one or more tasks encounter
     * unexpected exceptions.
     */
    // 强制终止 Phaser
    public void forceTermination() {
        // Only need to change root state
        final Phaser root = this.root;
        long s;
        while ((s = root.state) >= 0) {
            if (UNSAFE.compareAndSwapLong(root, stateOffset,
                                          s, s | TERMINATION_BIT)) {
                // signal all threads
                releaseWaiters(0); // Waiters on evenQ
                releaseWaiters(1); // Waiters on oddQ
                return;
            }
        }
    }

    /**
     * Returns the current phase number. The maximum phase number is
     * {@code Integer.MAX_VALUE}, after which it restarts at
     * zero. Upon termination, the phase number is negative,
     * in which case the prevailing phase prior to termination
     * may be obtained via {@code getPhase() + Integer.MIN_VALUE}.
     *
     * @return the phase number, or a negative value if terminated
     */
    public final int getPhase() {
        return (int)(root.state >>> PHASE_SHIFT);
    }

    /**
     * Returns the number of parties registered at this phaser.
     *
     * @return the number of parties
     */
    public int getRegisteredParties() {
        return partiesOf(state);
    }

    /**
     * Returns the number of registered parties that have arrived at
     * the current phase of this phaser. If this phaser has terminated,
     * the returned value is meaningless and arbitrary.
     *
     * @return the number of arrived parties
     */
    public int getArrivedParties() {
        return arrivedOf(reconcileState());
    }

    /**
     * Returns the number of registered parties that have not yet
     * arrived at the current phase of this phaser. If this phaser has
     * terminated, the returned value is meaningless and arbitrary.
     *
     * @return the number of unarrived parties
     */
    public int getUnarrivedParties() {
        return unarrivedOf(reconcileState());
    }

    /**
     * Returns the parent of this phaser, or {@code null} if none.
     *
     * @return the parent of this phaser, or {@code null} if none
     */
    public Phaser getParent() {
        return parent;
    }

    /**
     * Returns the root ancestor of this phaser, which is the same as
     * this phaser if it has no parent.
     *
     * @return the root ancestor of this phaser
     */
    public Phaser getRoot() {
        return root;
    }

    /**
     * Returns {@code true} if this phaser has been terminated.
     *
     * @return {@code true} if this phaser has been terminated
     */
    public boolean isTerminated() {
        return root.state < 0L;
    }

    /**
     * Overridable method to perform an action upon impending phase
     * advance, and to control termination. This method is invoked
     * upon arrival of the party advancing this phaser (when all other
     * waiting parties are dormant).  If this method returns {@code
     * true}, this phaser will be set to a final termination state
     * upon advance, and subsequent calls to {@link #isTerminated}
     * will return true. Any (unchecked) Exception or Error thrown by
     * an invocation of this method is propagated to the party
     * attempting to advance this phaser, in which case no advance
     * occurs.
     *
     * <p>The arguments to this method provide the state of the phaser
     * prevailing for the current transition.  The effects of invoking
     * arrival, registration, and waiting methods on this phaser from
     * within {@code onAdvance} are unspecified and should not be
     * relied on.
     *
     * <p>If this phaser is a member of a tiered set of phasers, then
     * {@code onAdvance} is invoked only for its root phaser on each
     * advance.
     *
     * <p>To support the most common use cases, the default
     * implementation of this method returns {@code true} when the
     * number of registered parties has become zero as the result of a
     * party invoking {@code arriveAndDeregister}.  You can disable
     * this behavior, thus enabling continuation upon future
     * registrations, by overriding this method to always return
     * {@code false}:
     *
     * <pre> {@code
     * Phaser phaser = new Phaser() {
     *   protected boolean onAdvance(int phase, int parties) { return false; }
     * }}</pre>
     *
     * @param phase the current phase number on entry to this method,
     * before this phaser is advanced
     * @param registeredParties the current number of registered parties
     * @return {@code true} if this phaser should terminate
     */
    /*
     * 提供给子类覆盖的方法，当所有线程都到达屏障时候会调用这个方法
     * 返回 true 表示需要终止 Phaser，否则继续下一轮的 phase
     */
    protected boolean onAdvance(int phase, int registeredParties) {
        return registeredParties == 0;
    }

    /**
     * Returns a string identifying this phaser, as well as its
     * state.  The state, in brackets, includes the String {@code
     * "phase = "} followed by the phase number, {@code "parties = "}
     * followed by the number of registered parties, and {@code
     * "arrived = "} followed by the number of arrived parties.
     *
     * @return a string identifying this phaser, as well as its state
     */
    public String toString() {
        return stateToString(reconcileState());
    }

    /**
     * Implementation of toString and string-based error messages
     */
    private String stateToString(long s) {
        return super.toString() +
            "[phase = " + phaseOf(s) +
            " parties = " + partiesOf(s) +
            " arrived = " + arrivedOf(s) + "]";
    }

    // Waiting mechanics

    /**
     * Removes and signals threads from queue for phase.
     */
    // 根据 phase 的奇偶性获取不同的队列，移除节点并唤醒线程
    private void releaseWaiters(int phase) {
        QNode q;   // first element of queue
        Thread t;  // its thread
        // 根据奇偶性获取不同的队列
        AtomicReference<QNode> head = (phase & 1) == 0 ? evenQ : oddQ;
        while ((q = head.get()) != null &&
               q.phase != (int)(root.state >>> PHASE_SHIFT)) { // 第二个判断不等于是因为在调这个方法之前 phase 已经加 1 了
            // CAS 第一个节点出队，
            if (head.compareAndSet(q, q.next) && (t = q.thread) != null) {
                q.thread = null;
                // 唤醒
                LockSupport.unpark(t);
            }
        }
    }

    /**
     * Variant of releaseWaiters that additionally tries to remove any
     * nodes no longer waiting for advance due to timeout or
     * interrupt. Currently, nodes are removed only if they are at
     * head of queue, which suffices to reduce memory footprint in
     * most usages.
     *
     * @return current phase on exit
     */
    private int abortWait(int phase) {
        AtomicReference<QNode> head = (phase & 1) == 0 ? evenQ : oddQ;
        for (;;) {
            Thread t;
            QNode q = head.get();
            int p = (int)(root.state >>> PHASE_SHIFT);
            if (q == null || ((t = q.thread) != null && q.phase == p))
                return p;
            if (head.compareAndSet(q, q.next) && t != null) {
                q.thread = null;
                LockSupport.unpark(t);
            }
        }
    }

    /** The number of CPUs, for spin control */
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * The number of times to spin before blocking while waiting for
     * advance, per arrival while waiting. On multiprocessors, fully
     * blocking and waking up a large number of threads all at once is
     * usually a very slow process, so we use rechargeable spins to
     * avoid it when threads regularly arrive: When a thread in
     * internalAwaitAdvance notices another arrival before blocking,
     * and there appear to be enough CPUs available, it spins
     * SPINS_PER_ARRIVAL more times before blocking. The value trades
     * off good-citizenship vs big unnecessary slowdowns.
     */
    // 每次到达的自旋次数
    static final int SPINS_PER_ARRIVAL = (NCPU < 2) ? 1 : 1 << 8;

    /**
     * Possibly blocks and waits for phase to advance unless aborted.
     * Call only on root phaser.
     *
     * @param phase current phase
     * @param node if non-null, the wait node to track interrupt and timeout;
     * if null, denotes noninterruptible wait
     * @return current phase
     */
    /*
     * 原理是先自旋一定次数，等待当前 phase 结束，假如在指定自旋次数中已经结束了，那么退出自旋，并唤醒链表中其他阻塞的线程
     * 假如在自旋一定次数后，当前的 phase 还未结束，那么会创建一个 QNode 节点，在下一次循环中添加到链表头部，添加成功后下次循环阻塞当前线程
     * 在最后一个参与者到达屏障后，会调用 releaseWaiters 方法唤醒所有阻塞的线程
     */
    // 等待onAdvance()方法执行完毕
    // 原理是自旋
    // 原理是先自旋一定次数，如果进入下一个阶段，这个方法直接就返回了，
    // 如果自旋一定次数后还没有进入下一个阶段，则当前线程入队列，等待onAdvance()执行完毕唤醒
    private int internalAwaitAdvance(int phase, QNode node) {
        // assert root == this;
        // 保证旧的队列为空，其实就是移除并唤醒之前的节点
        releaseWaiters(phase-1);          // ensure old queue clean
        boolean queued = false;           // true when node is enqueued
        int lastUnarrived = 0;            // to increase spins upon change
        // 自旋的次数
        int spins = SPINS_PER_ARRIVAL;
        long s;     // 当前循环的 state
        int p;      // 当前循环的 phase
        // 检查当前阶段是否变化，如果变化了说明进入下一个阶段了，这时候就没有必要自旋了
        while ((p = (int)((s = state) >>> PHASE_SHIFT)) == phase) {
            if (node == null) {           // spinning in noninterruptible mode
                // 未完成的参与者数量
                int unarrived = (int)s & UNARRIVED_MASK;
                // unarrived 有变化，增加自旋次数
                if (unarrived != lastUnarrived && (lastUnarrived = unarrived) < NCPU)
                    spins += SPINS_PER_ARRIVAL;
                boolean interrupted = Thread.interrupted();
                // 自旋次数完了，则新建一个节点，记录当前线程是否被中断
                if (interrupted || --spins < 0) { // need node to record intr
                    node = new QNode(this, phase, false, false, 0L);
                    node.wasInterrupted = interrupted;
                }
            }
            else if (node.isReleasable()) // done or aborted
                break;
            else if (!queued) {           // push onto queue
                // 节点入队列
                AtomicReference<QNode> head = (phase & 1) == 0 ? evenQ : oddQ;
                QNode q = node.next = head.get();
                if ((q == null || q.phase == phase) &&
                    (int)(state >>> PHASE_SHIFT) == phase) // avoid stale enq
                    queued = head.compareAndSet(q, node);
            }
            else {
                try {
                    // 当前线程进入阻塞状态，等待被唤醒
                    ForkJoinPool.managedBlock(node);
                } catch (InterruptedException ie) {
                    node.wasInterrupted = true;
                }
            }
        }

        // 到这里说明节点所在线程已经被唤醒了
        if (node != null) {
            // 置空节点中的线程
            if (node.thread != null)
                node.thread = null;       // avoid need for unpark()
            if (node.wasInterrupted && !node.interruptible)
                Thread.currentThread().interrupt();
            if (p == phase && (p = (int)(state >>> PHASE_SHIFT)) == phase)
                return abortWait(phase); // possibly clean up on abort
        }
        // 唤醒当前阶段阻塞着的线程
        releaseWaiters(phase);
        return p;
    }

    /**
     * Wait nodes for Treiber stack representing wait queue
     */
    // 内部保存了线程信息和 Phsaer 对象信息
    // ForkJoinPool.ManagedBlocker 是当栈包含 ForkJoinWorkerThread 类型的 QNode 阻塞的时候，
    // ForkJoinPool 内部会增加一个工作线程来保证并行度
    // 一个参与者在达到阶段屏障时，还有其他参与者未到达时，自旋一段时间，其余参与者还未到达，将其封装成 QNode 加入链表中
    static final class QNode implements ForkJoinPool.ManagedBlocker {
        final Phaser phaser;
        // 当前节点所处的屏障 Phaser 阶段
        final int phase;
        // 当前等待节点封装的线程是否可被中断，true-可被中断
        final boolean interruptible;
        // 当前等待节点是否支持等待超时，true-支持
        final boolean timed;
        // 当前等待节点线程在等待的过程中是否被中断过的标记，true-被中断过了
        boolean wasInterrupted;
        // 当前等待节点等待的超时时间
        long nanos;
        // 当前等待节点等待的超时的时间点
        final long deadline;
        // 当前等待节点的线程，可能为空，当前节点被取消等待
        volatile Thread thread; // nulled to cancel wait
        // 由于QNode是个链表，当前等待节点的下一等待节点
        QNode next;

        // QNode构造函数，传入phaser实例，phase当前等待节点所处的阶段，
        // interruptible当前节点线程是否可被中断，
        // timed当前等待节点线程是否超时等待，
        // nanos当前等待线程如果支持超时等待，等待的超时时间
        QNode(Phaser phaser, int phase, boolean interruptible,
              boolean timed, long nanos) {
            this.phaser = phaser;
            this.phase = phase;
            this.interruptible = interruptible;
            this.nanos = nanos;
            this.timed = timed;
            this.deadline = timed ? System.nanoTime() + nanos : 0L;
            thread = Thread.currentThread();
        }

        /**
         * 如果不需要阻塞就返回 true
         * @return
         */
        public boolean isReleasable() {
            if (thread == null)
                return true;
            // 不是当前阶段不阻塞了
            if (phaser.getPhase() != phase) {
                thread = null;
                return true;
            }
            // 当前线程被中断了，赋值中断标记
            if (Thread.interrupted())
                wasInterrupted = true;
            // 假如当前线程被中断了，而且当前线程封装的节点允许中断，那么就会返回 true
            if (wasInterrupted && interruptible) {
                thread = null;
                return true;
            }
            // 支持超时的判断
            if (timed) {
                if (nanos > 0L) {
                    nanos = deadline - System.nanoTime();
                }
                if (nanos <= 0L) {
                    thread = null;
                    return true;
                }
            }
            return false;
        }

        public boolean block() {
            if (isReleasable())
                return true;
            else if (!timed)
                LockSupport.park(this);
            else if (nanos > 0L)
                LockSupport.parkNanos(this, nanos);
            return isReleasable();
        }
    }

    // Unsafe mechanics

    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = Phaser.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
