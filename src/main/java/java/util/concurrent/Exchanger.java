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
 * Written by Doug Lea, Bill Scherer, and Michael Scott with
 * assistance from members of JCP JSR-166 Expert Group and released to
 * the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * A synchronization point at which threads can pair and swap elements
 * within pairs.  Each thread presents some object on entry to the
 * {@link #exchange exchange} method, matches with a partner thread,
 * and receives its partner's object on return.  An Exchanger may be
 * viewed as a bidirectional form of a {@link SynchronousQueue}.
 * Exchangers may be useful in applications such as genetic algorithms
 * and pipeline designs.
 *
 * <p><b>Sample Usage:</b>
 * Here are the highlights of a class that uses an {@code Exchanger}
 * to swap buffers between threads so that the thread filling the
 * buffer gets a freshly emptied one when it needs it, handing off the
 * filled one to the thread emptying the buffer.
 *  <pre> {@code
 * class FillAndEmpty {
 *   Exchanger<DataBuffer> exchanger = new Exchanger<DataBuffer>();
 *   DataBuffer initialEmptyBuffer = ... a made-up type
 *   DataBuffer initialFullBuffer = ...
 *
 *   class FillingLoop implements Runnable {
 *     public void run() {
 *       DataBuffer currentBuffer = initialEmptyBuffer;
 *       try {
 *         while (currentBuffer != null) {
 *           addToBuffer(currentBuffer);
 *           if (currentBuffer.isFull())
 *             currentBuffer = exchanger.exchange(currentBuffer);
 *         }
 *       } catch (InterruptedException ex) { ... handle ... }
 *     }
 *   }
 *
 *   class EmptyingLoop implements Runnable {
 *     public void run() {
 *       DataBuffer currentBuffer = initialFullBuffer;
 *       try {
 *         while (currentBuffer != null) {
 *           takeFromBuffer(currentBuffer);
 *           if (currentBuffer.isEmpty())
 *             currentBuffer = exchanger.exchange(currentBuffer);
 *         }
 *       } catch (InterruptedException ex) { ... handle ...}
 *     }
 *   }
 *
 *   void start() {
 *     new Thread(new FillingLoop()).start();
 *     new Thread(new EmptyingLoop()).start();
 *   }
 * }}</pre>
 *
 * <p>Memory consistency effects: For each pair of threads that
 * successfully exchange objects via an {@code Exchanger}, actions
 * prior to the {@code exchange()} in each thread
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * those subsequent to a return from the corresponding {@code exchange()}
 * in the other thread.
 *
 * @since 1.5
 * @author Doug Lea and Bill Scherer and Michael Scott
 * @param <V> The type of objects that may be exchanged
 */
public class Exchanger<V> {

    /*
     * Overview: The core algorithm is, for an exchange "slot",
     * and a participant (caller) with an item:
     *
     * for (;;) {
     *   if (slot is empty) {                       // offer
     *     place item in a Node;
     *     if (can CAS slot from empty to node) {
     *       wait for release;
     *       return matching item in node;
     *     }
     *   }
     *   else if (can CAS slot from node to empty) { // release
     *     get the item in node;
     *     set matching item in node;
     *     release waiting thread;
     *   }
     *   // else retry on CAS failure
     * }
     *
     * This is among the simplest forms of a "dual data structure" --
     * see Scott and Scherer's DISC 04 paper and
     * http://www.cs.rochester.edu/research/synchronization/pseudocode/duals.html
     *
     * This works great in principle. But in practice, like many
     * algorithms centered on atomic updates to a single location, it
     * scales horribly when there are more than a few participants
     * using the same Exchanger. So the implementation instead uses a
     * form of elimination arena, that spreads out this contention by
     * arranging that some threads typically use different slots,
     * while still ensuring that eventually, any two parties will be
     * able to exchange items. That is, we cannot completely partition
     * across threads, but instead give threads arena indices that
     * will on average grow under contention and shrink under lack of
     * contention. We approach this by defining the Nodes that we need
     * anyway as ThreadLocals, and include in them per-thread index
     * and related bookkeeping state. (We can safely reuse per-thread
     * nodes rather than creating them fresh each time because slots
     * alternate between pointing to a node vs null, so cannot
     * encounter ABA problems. However, we do need some care in
     * resetting them between uses.)
     *
     * Implementing an effective arena requires allocating a bunch of
     * space, so we only do so upon detecting contention (except on
     * uniprocessors, where they wouldn't help, so aren't used).
     * Otherwise, exchanges use the single-slot slotExchange method.
     * On contention, not only must the slots be in different
     * locations, but the locations must not encounter memory
     * contention due to being on the same cache line (or more
     * generally, the same coherence unit).  Because, as of this
     * writing, there is no way to determine cacheline size, we define
     * a value that is enough for common platforms.  Additionally,
     * extra care elsewhere is taken to avoid other false/unintended
     * sharing and to enhance locality, including adding padding (via
     * sun.misc.Contended) to Nodes, embedding "bound" as an Exchanger
     * field, and reworking some park/unpark mechanics compared to
     * LockSupport versions.
     *
     * The arena starts out with only one used slot. We expand the
     * effective arena size by tracking collisions; i.e., failed CASes
     * while trying to exchange. By nature of the above algorithm, the
     * only kinds of collision that reliably indicate contention are
     * when two attempted releases collide -- one of two attempted
     * offers can legitimately fail to CAS without indicating
     * contention by more than one other thread. (Note: it is possible
     * but not worthwhile to more precisely detect contention by
     * reading slot values after CAS failures.)  When a thread has
     * collided at each slot within the current arena bound, it tries
     * to expand the arena size by one. We track collisions within
     * bounds by using a version (sequence) number on the "bound"
     * field, and conservatively reset collision counts when a
     * participant notices that bound has been updated (in either
     * direction).
     *
     * The effective arena size is reduced (when there is more than
     * one slot) by giving up on waiting after a while and trying to
     * decrement the arena size on expiration. The value of "a while"
     * is an empirical matter.  We implement by piggybacking on the
     * use of spin->yield->block that is essential for reasonable
     * waiting performance anyway -- in a busy exchanger, offers are
     * usually almost immediately released, in which case context
     * switching on multiprocessors is extremely slow/wasteful.  Arena
     * waits just omit the blocking part, and instead cancel. The spin
     * count is empirically chosen to be a value that avoids blocking
     * 99% of the time under maximum sustained exchange rates on a
     * range of test machines. Spins and yields entail some limited
     * randomness (using a cheap xorshift) to avoid regular patterns
     * that can induce unproductive grow/shrink cycles. (Using a
     * pseudorandom also helps regularize spin cycle duration by
     * making branches unpredictable.)  Also, during an offer, a
     * waiter can "know" that it will be released when its slot has
     * changed, but cannot yet proceed until match is set.  In the
     * mean time it cannot cancel the offer, so instead spins/yields.
     * Note: It is possible to avoid this secondary check by changing
     * the linearization point to be a CAS of the match field (as done
     * in one case in the Scott & Scherer DISC paper), which also
     * increases asynchrony a bit, at the expense of poorer collision
     * detection and inability to always reuse per-thread nodes. So
     * the current scheme is typically a better tradeoff.
     *
     * On collisions, indices traverse the arena cyclically in reverse
     * order, restarting at the maximum index (which will tend to be
     * sparsest) when bounds change. (On expirations, indices instead
     * are halved until reaching 0.) It is possible (and has been
     * tried) to use randomized, prime-value-stepped, or double-hash
     * style traversal instead of simple cyclic traversal to reduce
     * bunching.  But empirically, whatever benefits these may have
     * don't overcome their added overhead: We are managing operations
     * that occur very quickly unless there is sustained contention,
     * so simpler/faster control policies work better than more
     * accurate but slower ones.
     *
     * Because we use expiration for arena size control, we cannot
     * throw TimeoutExceptions in the timed version of the public
     * exchange method until the arena size has shrunken to zero (or
     * the arena isn't enabled). This may delay response to timeout
     * but is still within spec.
     *
     * Essentially all of the implementation is in methods
     * slotExchange and arenaExchange. These have similar overall
     * structure, but differ in too many details to combine. The
     * slotExchange method uses the single Exchanger field "slot"
     * rather than arena array elements. However, it still needs
     * minimal collision detection to trigger arena construction.
     * (The messiest part is making sure interrupt status and
     * InterruptedExceptions come out right during transitions when
     * both methods may be called. This is done by using null return
     * as a sentinel to recheck interrupt status.)
     *
     * As is too common in this sort of code, methods are monolithic
     * because most of the logic relies on reads of fields that are
     * maintained as local variables so can't be nicely factored --
     * mainly, here, bulky spin->yield->block/cancel code), and
     * heavily dependent on intrinsics (Unsafe) to use inlined
     * embedded CAS and related memory access operations (that tend
     * not to be as readily inlined by dynamic compilers when they are
     * hidden behind other methods that would more nicely name and
     * encapsulate the intended effects). This includes the use of
     * putOrderedX to clear fields of the per-thread Nodes between
     * uses. Note that field Node.item is not declared as volatile
     * even though it is read by releasing threads, because they only
     * do so after CAS operations that must precede access, and all
     * uses by the owning thread are otherwise acceptably ordered by
     * other operations. (Because the actual points of atomicity are
     * slot CASes, it would also be legal for the write to Node.match
     * in a release to be weaker than a full volatile write. However,
     * this is not done because it could allow further postponement of
     * the write, delaying progress.)
     */

    /**
     * The byte distance (as a shift value) between any two used slots
     * in the arena.  1 << ASHIFT should be at least cacheline size.
     */
    // 多槽交换的数组中任意两个已使用插槽之间的字节距离（作为移位值）。
    // 1 << ASHIFT 至少应为高速缓存行大小。
    // 防止伪共享，CacheLine 填充
    private static final int ASHIFT = 7;

    /**
     * The maximum supported arena index. The maximum allocatable
     * arena size is MMASK + 1. Must be a power of two minus one, less
     * than (1<<(31-ASHIFT)). The cap of 255 (0xff) more than suffices
     * for the expected scaling limits of the main algorithms.
     */
    // 支持的最大多槽交换的数组索引。
    // 最大可分配的 arena 大小为 MMASK + 1。必须是 2 的 n次幂减 1 的值，小于 (1<<(31-ASHIFT))。
    // 255 (0xff) 的上限足以满足主要算法的预期扩展限制
    // 11111111
    private static final int MMASK = 0xff;

    /**
     * Unit for sequence/version bits of bound field. Each successful
     * change to the bound also adds SEQ.
     */
    // bound 字段的版本号，对 bound 的每次成功更改会增加一个 SEQ
    private static final int SEQ = MMASK + 1;

    /** The number of CPUs, for sizing and spin control */
    // CPU 的数量，用于调整大小和旋转控制
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    /**
     * The maximum slot index of the arena: The number of slots that
     * can in principle hold all threads without contention, or at
     * most the maximum indexable value.
     */
    // arena 的最大槽索引：原则上可以容纳所有线程而不会发生争用的槽数，或最多可索引的最大值。
    // eg. 核心数是 8 的话，FULL 就是 4
    static final int FULL = (NCPU >= (MMASK << 1)) ? MMASK : NCPU >>> 1;

    /**
     * The bound for spins while waiting for a match. The actual
     * number of iterations will on average be about twice this value
     * due to randomization. Note: Spinning is disabled when NCPU==1.
     */
    // 等待比赛时旋转的界限。由于随机化，实际迭代次数平均约为该值的两倍。注意：当 NCPU==1 时禁用旋转。
    // 1024
    private static final int SPINS = 1 << 10;

    /**
     * Value representing null arguments/returns from public
     * methods. Needed because the API originally didn't disallow null
     * arguments, which it should have.
     */
    // 空对象
    private static final Object NULL_ITEM = new Object();

    /**
     * Sentinel value returned by internal exchange methods upon
     * timeout, to avoid need for separate timed versions of these
     * methods.
     */
    private static final Object TIMED_OUT = new Object();

    /**
     * Nodes hold partially exchanged data, plus other per-thread
     * bookkeeping. Padded via @sun.misc.Contended to reduce memory
     * contention.
     */
    // @sun.misc.Contended 填充以减少内存争用。 伪共享
    @sun.misc.Contended static final class Node {
        // arena 数组的索引
        int index;              // Arena index
        // 记录上次的 bound
        int bound;              // Last recorded value of Exchanger.bound
        // 在当前 bound 下 CAS 失败的次数
        int collides;           // Number of CAS failures at current bound

        // 线程的伪随机数，用于自旋优化
        int hash;               // Pseudo-random for spins
        // Node 封装的数据
        Object item;            // This thread's current item
        // 配对线程提供的数据（后达到的线程会将自身携带的值设置到配对线程的该字段上）
        volatile Object match;  // Item provided by releasing thread
        // 此节点上的阻塞线程（先到达的并阻塞的线程会设置该值自身）
        volatile Thread parked; // Set to this thread when parked, else null
    }

    /** The corresponding thread local class */
    // 线程本地变量，就是保存一个 Node
    static final class Participant extends ThreadLocal<Node> {
        public Node initialValue() { return new Node(); }
    }

    /**
     * Per-thread state
     */
    private final Participant participant;

    /**
     * Elimination array; null until enabled (within slotExchange).
     * Element accesses use emulation of volatile gets and CAS.
     */
    // 多槽交换的数组
    private volatile Node[] arena;

    /**
     * Slot used until contention detected.
     */
    // 单槽交换节点
    private volatile Node slot;

    /**
     * The index of the largest valid arena position, OR'ed with SEQ
     * number in high bits, incremented on each update.  The initial
     * update from 0 to SEQ is used to ensure that the arena array is
     * constructed only once.
     */
    // 最大有效 arean 位置的索引，与高位的 SEQ 编号进行或运算，每次更新时递增。
    // 从 0 到 SEQ 的初始更新用于确保 arena 数组只构造一次。
    // bound 的初始值是 SEQ，在创建 arean 的时候赋值的

    // bound，记录最大有效的 arena 索引，动态变化，竞争激烈时（槽位全满）增加， 槽位空旷时减小。
    private volatile int bound;

    /**
     * Exchange function when arenas enabled. See above for explanation.
     *
     * @param item the (non-null) item to exchange
     * @param timed true if the wait is timed
     * @param ns if timed, the maximum wait time, else 0L
     * @return the other thread's item; or null if interrupted; or
     * TIMED_OUT if timed and timed out
     */
    // TODO-KWOK https://houbb.github.io/2020/10/17/lock-12-tool-exchanger
    private final Object arenaExchange(Object item, boolean timed, long ns) {
        // 多槽交换的数组，在单槽交换的方法里面创建的
        Node[] a = arena;
        // 获取当前线程的 Node
        Node p = participant.get();
        for (int i = p.index;;) {                      // access slot at i
            /*
             * b: bound 记录最大有效的 arena 索引，动态变化，竞争激烈时（槽位全满）增加， 槽位空旷时减小。
             * m: bound & MMASK 的结果，其实就是获取 bound 的低 8 位
             * c:
             * j: 表示 i 位置的元素在 arena 数组的地址偏移量
             */
            int b, m, c; long j;                       // j is raw array offset
            // 从 arena 数组中选出偏移地址为 (i << ASHIFT) + ABASE 的元素, 即真正可用的 Node
            Node q = (Node)U.getObjectVolatile(a, j = (i << ASHIFT) + ABASE);
            // CASE1：槽不为空，说明已经有线程到达并在等待了
            // 尝试 CAS 将该槽位置的 Node 置为 null
            if (q != null && U.compareAndSwapObject(a, j, q, null)) {
                // 获取已经到达的线程所携带的值
                Object v = q.item;                     // release
                // 把当前线程携带的值交换给已经到达的线程
                q.match = item;
                // q.parked指向已经到达的线程
                Thread w = q.parked;
                if (w != null)
                    // 唤醒已经到达的线程
                    U.unpark(w);
                return v;
            }
            // 前置条件：q == null 或者 cas 更新 Node 值失败
            // CASE2: 有效槽位位置且槽位为空，尝试占用槽位
            // bound 是最大的有效的 位置，和MMASK相与，得到真正的存储数据的索引最大值
            // bound 初始值是 SEQ
            //  1 00000000 & 11111111 = 0 000000000
            else if (i <= (m = (b = bound) & MMASK) && q == null) {
                p.item = item;                         // offer
                // 设置该槽位数据(在该槽位等待其它线程来交换数据)
                if (U.compareAndSwapObject(a, j, null, p)) { // 占用该槽位, 成功
                    long end = (timed && m == 0) ? System.nanoTime() + ns : 0L;
                    Thread t = Thread.currentThread(); // wait
                    // 自旋等待一段时间,看看有没其它配对线程到达该槽位
                    for (int h = p.hash, spins = SPINS;;) {
                        Object v = p.match;
                        // 在自旋的过程中，有线程来和该线程交换数据
                        if (v != null) {
                            // 有配对线程到达了该槽位
                            // 交换数据后，清空部分设置，返回交换得到的数据
                            U.putOrderedObject(p, MATCH, null);
                            p.item = null;             // clear for next use
                            p.hash = h;
                            // 返回配对线程交换过来的值
                            return v;
                        }
                        else if (spins > 0) {
                            h ^= h << 1;
                            h ^= h >>> 3;
                            h ^= h << 10; // xorshift
                            if (h == 0)                // initialize hash
                                h = SPINS | (int)t.getId();
                            else if (h < 0 &&          // approx 50% true
                                     (--spins & ((SPINS >>> 1) - 1)) == 0)
                                // 每一次等待有两次让出CPU的时机
                                Thread.yield();        // two yields per wait
                        }
                        // 优化操作:配对线程已经到达, 但是还未完全准备好, 所以需要再自旋等待一会儿
                        // 交换数据的线程到来，但是还没有设置好match，再稍等一会
                        else if (U.getObjectVolatile(a, j) != p)
                            spins = SPINS;       // releaser hasn't set match yet
                        // 等不到配对线程了, 阻塞当前线程
                        // 符合条件，特别注意 m==0 这个说明已经到达area 中最小的存储数据槽位了
                        // 没有其他线程在槽位等待了，所有当前线程需要阻塞在这里
                        // 有个 m == 0 的条件，说明当 m != 0 的节点是不会阻塞的
                        else if (!t.isInterrupted() && m == 0 &&
                                 (!timed ||
                                  (ns = end - System.nanoTime()) > 0L)) {
                            U.putObject(t, BLOCKER, this); // emulate LockSupport
                            // 在结点引用当前线程，以便配对线程到达后唤醒我
                            p.parked = t;              // minimize window
                            // 再次检查槽位，看看在阻塞前，有没有线程来交换数据
                            if (U.getObjectVolatile(a, j) == p)
                                U.park(false, ns);
                            p.parked = null;
                            U.putObject(t, BLOCKER, null);
                        }
                        // 前置条件： v == null && spins <=0 && U.getObjectVolatile(a, j) == p
                        // 当前这个槽位一直没有线程来交换数据，准备换个槽位试试
                        // 获取 j 位置的槽位，尝试 cas 更新 j 的槽位为 null
                        else if (U.getObjectVolatile(a, j) == p &&
                                 U.compareAndSwapObject(a, j, p, null)) { // 尝试缩减arena槽数组的大小
                            // 更新 bound
                            if (m != 0)                // try to shrink
                                // bound 初始值 1 0000000
                                // SEQ 的值是1 00000000， b + SEQ - 1 就是 512 - 1
                                // 3 00000000
                                U.compareAndSwapInt(this, BOUND, b, b + SEQ - 1);
                            p.item = null;
                            p.hash = h;
                            // 减小索引值 往"第一个"槽位的方向挪动
                            i = p.index >>>= 1;        // descend
                            if (Thread.interrupted())
                                return null;
                            if (timed && m == 0 && ns <= 0L)
                                return TIMED_OUT;
                            break;                     // expired; restart
                        }
                    }
                }
                // 占据槽位失败，先清空item,防止成功交换数据后，p.item还引用着item
                else
                    p.item = null;                     // clear offer
            }
            // i 不在有效范围，或者被其它线程抢先了
            // 可能是 q!=null且 cas 更改 q 为 null 失败了
            else {
                // 更新p.bound
                if (p.bound != b) {                    // stale; reset
                    // 新 bound ，重置collides
                    p.bound = b;
                    p.collides = 0;
                    // i如果达到了最大，那么就递减
                    i = (i != m || m == 0) ? m : m - 1;
                }
                else if ((c = p.collides) < m || m == FULL ||
                         !U.compareAndSwapInt(this, BOUND, b, b + SEQ + 1)) { // 在这里修改 b
                    // 更新冲突
                    p.collides = c + 1;
                    // i=0 那么就从m开始，否则递减i
                    i = (i == 0) ? m : i - 1;          // cyclically traverse
                }
                else
                    //递增，往后挪动
                    i = m + 1;                         // grow
                // 更新index
                p.index = i;
            }
        }
    }

    /**
     * Exchange function used until arenas enabled. See above for explanation.
     *
     * @param item the item to exchange
     * @param timed true if the wait is timed
     * @param ns if timed, the maximum wait time, else 0L
     * @return the other thread's item; or null if either the arena
     * was enabled or the thread was interrupted before completion; or
     * TIMED_OUT if timed and timed out
     */
    private final Object slotExchange(Object item, boolean timed, long ns) {
        // 获取当前线程的 Node 对象
        Node p = participant.get();
        Thread t = Thread.currentThread();
        if (t.isInterrupted()) // preserve interrupt status so caller can recheck
            return null;

        for (Node q;;) {
            // CASE1： slot != null 说明已经有线程先到并占用了 solt，这个数据是先到达的线程设置的
            if ((q = slot) != null) {
                // CAS 尝试让 slot 置为 null
                if (U.compareAndSwapObject(this, SLOT, q, null)) {
                    // 获取当前线程要交换的交换值
                    Object v = q.item;
                    // 设置交换值给匹配线程的 Node
                    q.match = item;
                    Thread w = q.parked;
                    if (w != null)
                        // 唤醒在该槽位等待的线程
                        U.unpark(w);
                    // 交换成功返回结果
                    return v;
                }
                // create arena on contention, but continue until slot null
                // 上面 CAS 设置 solt 值为 null 失败，说明出现竞争，需要改为多槽交换
                // CPU核数数多于1个, 且bound为0时创建arena数组，并将bound设置为SEQ大小
                if (NCPU > 1 && bound == 0 && U.compareAndSwapInt(this, BOUND, 0, SEQ))
                    // arena的大小为(FULL + 2) << ASHIFT，因为1 << ASHIFT 是用于避免伪共享的，
                    // 因此实际有效的Node 只有FULL + 2 个
                    arena = new Node[(FULL + 2) << ASHIFT];
            }
            // CASE2：前置条件 slot == null
            else if (arena != null)
                // 单槽交换中途出现了初始化arena的操作，需要重新直接路由到多槽交换(arenaExchange)
                return null; // caller must reroute to arenaExchange
            // CASE3：前置条件 slot == null && arena == null
            // 当前线程先到达，则占用此 solt
            else {
                p.item = item;
                // 占用 slot 槽位，占用成功则退出循环
                if (U.compareAndSwapObject(this, SLOT, null, p))
                    break;
                // 当前 cas 失败，自旋重试
                p.item = null;
            }
        }

        // await release
        // 执行到这, 说明当前线程先到达, 且已经占用了slot槽, 需要等待配对线程到达
        int h = p.hash;
        // 计算超时时间
        long end = timed ? System.nanoTime() + ns : 0L;
        int spins = (NCPU > 1) ? SPINS : 1;
        Object v;
        // p.match == null 说明对方线程还未提供它要交换的数据
        // 当前线程需要自旋等待，假如自旋足够次数后对方线程还未到达，需要阻塞当前线程
        while ((v = p.match) == null) {
            // 优化：自旋过程中随机让出 CPU
            if (spins > 0) {
                h ^= h << 1; h ^= h >>> 3; h ^= h << 10;
//                System.out.println("h值：" + h);
                if (h == 0)
                    h = SPINS | (int)t.getId();
                // (SPINS >>> 1) - 1 就是 01 11111111
                // 10 00000000
                // 01 11111111
                else if (h < 0 && (--spins & ((SPINS >>> 1) - 1)) == 0)
                    System.out.println("让出 CPU 资源...");
                    Thread.yield();
            }
            // 优化：说明配对线程已经到达，并把 slot 给改了, 但是交换操作还未执行完, 所以需要再自旋等待一会儿
            else if (slot != p)
                spins = SPINS;
            // 已经自旋很久了, 还是等不到配对, 此时才阻塞当前线程
            // 条件：当前线程未被中断 && arena == null && (不支持超时 || 支持超时但未超时)
            else if (!t.isInterrupted() && arena == null &&
                     (!timed || (ns = end - System.nanoTime()) > 0L)) {
                // 设置阻塞当前线程的 parkBlocker
                U.putObject(t, BLOCKER, this);
                p.parked = t;
                if (slot == p)
                    // 阻塞当前线程
                    System.out.println(Thread.currentThread().getName() + "被阻塞了...");
                    U.park(false, ns);
                // 被唤醒后，删除一些数据
                p.parked = null;
                U.putObject(t, BLOCKER, null);
            }
            // 超时或被中断, 给其他线程腾出slot
            else if (U.compareAndSwapObject(this, SLOT, p, null)) {
                v = timed && ns <= 0L && !t.isInterrupted() ? TIMED_OUT : null;
                break;
            }
        }
        U.putOrderedObject(p, MATCH, null);
        p.item = null;
        p.hash = h;
        return v;
    }

    /**
     * Creates a new Exchanger.
     */
    public Exchanger() {
        participant = new Participant();
    }

    /**
     * Waits for another thread to arrive at this exchange point (unless
     * the current thread is {@linkplain Thread#interrupt interrupted}),
     * and then transfers the given object to it, receiving its object
     * in return.
     *
     * <p>If another thread is already waiting at the exchange point then
     * it is resumed for thread scheduling purposes and receives the object
     * passed in by the current thread.  The current thread returns immediately,
     * receiving the object passed to the exchange by that other thread.
     *
     * <p>If no other thread is already waiting at the exchange then the
     * current thread is disabled for thread scheduling purposes and lies
     * dormant until one of two things happens:
     * <ul>
     * <li>Some other thread enters the exchange; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread.
     * </ul>
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * for the exchange,
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * @param x the object to exchange
     * @return the object provided by the other thread
     * @throws InterruptedException if the current thread was
     *         interrupted while waiting
     */
    @SuppressWarnings("unchecked")
    public V exchange(V x) throws InterruptedException {
        Object v;
        Object item = (x == null) ? NULL_ITEM : x; // translate null args
        /*
         * 决定数据的交换方式
         * 1. 单槽交换：arena == null
         * 2. 多槽交换：arena != null，或者单槽交换失败
         */
        if ((arena != null || (v = slotExchange(item, false, 0L)) == null)
                && ((Thread.interrupted() || /* disambiguates null return*/ (v = arenaExchange(item, false, 0L)) == null)))
            throw new InterruptedException();
        return (v == NULL_ITEM) ? null : (V)v;
    }

    /**
     * 测试方法后续删除
     *
     * @param x
     * @return
     * @throws InterruptedException
     */
    public V exchangeTest(V x) throws InterruptedException {
        Object v;
        Object item = (x == null) ? NULL_ITEM : x; // translate null args
        if (NCPU > 1 && bound == 0 && U.compareAndSwapInt(this, BOUND, 0, SEQ))
            // arena的大小为(FULL + 2) << ASHIFT，因为1 << ASHIFT 是用于避免伪共享的，
            // 因此实际有效的Node 只有FULL + 2 个
            arena = new Node[(FULL + 2) << ASHIFT];
        if (arena == null) {
            System.out.println("====");
        }
        v = arenaExchange(item, false, 0L);
        return (v == NULL_ITEM) ? null : (V)v;
    }

    /**
     * Waits for another thread to arrive at this exchange point (unless
     * the current thread is {@linkplain Thread#interrupt interrupted} or
     * the specified waiting time elapses), and then transfers the given
     * object to it, receiving its object in return.
     *
     * <p>If another thread is already waiting at the exchange point then
     * it is resumed for thread scheduling purposes and receives the object
     * passed in by the current thread.  The current thread returns immediately,
     * receiving the object passed to the exchange by that other thread.
     *
     * <p>If no other thread is already waiting at the exchange then the
     * current thread is disabled for thread scheduling purposes and lies
     * dormant until one of three things happens:
     * <ul>
     * <li>Some other thread enters the exchange; or
     * <li>Some other thread {@linkplain Thread#interrupt interrupts}
     * the current thread; or
     * <li>The specified waiting time elapses.
     * </ul>
     * <p>If the current thread:
     * <ul>
     * <li>has its interrupted status set on entry to this method; or
     * <li>is {@linkplain Thread#interrupt interrupted} while waiting
     * for the exchange,
     * </ul>
     * then {@link InterruptedException} is thrown and the current thread's
     * interrupted status is cleared.
     *
     * <p>If the specified waiting time elapses then {@link
     * TimeoutException} is thrown.  If the time is less than or equal
     * to zero, the method will not wait at all.
     *
     * @param x the object to exchange
     * @param timeout the maximum time to wait
     * @param unit the time unit of the {@code timeout} argument
     * @return the object provided by the other thread
     * @throws InterruptedException if the current thread was
     *         interrupted while waiting
     * @throws TimeoutException if the specified waiting time elapses
     *         before another thread enters the exchange
     */
    @SuppressWarnings("unchecked")
    public V exchange(V x, long timeout, TimeUnit unit)
        throws InterruptedException, TimeoutException {
        Object v;
        Object item = (x == null) ? NULL_ITEM : x;
        long ns = unit.toNanos(timeout);
        if ((arena != null || (v = slotExchange(item, true, ns)) == null)
                && ((Thread.interrupted() || (v = arenaExchange(item, true, ns)) == null)))
            throw new InterruptedException();
        if (v == TIMED_OUT)
            throw new TimeoutException();
        return (v == NULL_ITEM) ? null : (V)v;
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe U;
    private static final long BOUND;
    private static final long SLOT;
    private static final long MATCH;
    private static final long BLOCKER;
    private static final int ABASE;
    static {
        int s;
        try {
            U = sun.misc.Unsafe.getUnsafe();
            Class<?> ek = Exchanger.class;
            Class<?> nk = Node.class;
            Class<?> ak = Node[].class;
            Class<?> tk = Thread.class;
            BOUND = U.objectFieldOffset
                (ek.getDeclaredField("bound"));
            SLOT = U.objectFieldOffset
                (ek.getDeclaredField("slot"));
            MATCH = U.objectFieldOffset
                (nk.getDeclaredField("match"));
            BLOCKER = U.objectFieldOffset
                (tk.getDeclaredField("parkBlocker"));
            // 获取每个数组相邻位置的 地址间隔
            s = U.arrayIndexScale(ak);
            // ABASE absorbs padding in front of element 0
            // ABASE 元素 0 前面的填充
            ABASE = U.arrayBaseOffset(ak) + (1 << ASHIFT);

        } catch (Exception e) {
            throw new Error(e);
        }
        // 需要 s 是 2 的 n 次幂，且 s 需要小于等于 64
        if ((s & (s-1)) != 0 || s > (1 << ASHIFT))
            throw new Error("Unsupported array scale");
    }

}
