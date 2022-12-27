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
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.*;
import java.util.Spliterator;
import java.util.Spliterators;

/**
 * A {@linkplain BlockingQueue blocking queue} in which each insert
 * operation must wait for a corresponding remove operation by another
 * thread, and vice versa.  A synchronous queue does not have any
 * internal capacity, not even a capacity of one.  You cannot
 * {@code peek} at a synchronous queue because an element is only
 * present when you try to remove it; you cannot insert an element
 * (using any method) unless another thread is trying to remove it;
 * you cannot iterate as there is nothing to iterate.  The
 * <em>head</em> of the queue is the element that the first queued
 * inserting thread is trying to add to the queue; if there is no such
 * queued thread then no element is available for removal and
 * {@code poll()} will return {@code null}.  For purposes of other
 * {@code Collection} methods (for example {@code contains}), a
 * {@code SynchronousQueue} acts as an empty collection.  This queue
 * does not permit {@code null} elements.
 *
 * <p>Synchronous queues are similar to rendezvous channels used in
 * CSP and Ada. They are well suited for handoff designs, in which an
 * object running in one thread must sync up with an object running
 * in another thread in order to hand it some information, event, or
 * task.
 *
 * <p>This class supports an optional fairness policy for ordering
 * waiting producer and consumer threads.  By default, this ordering
 * is not guaranteed. However, a queue constructed with fairness set
 * to {@code true} grants threads access in FIFO order.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @since 1.5
 * @author Doug Lea and Bill Scherer and Michael Scott
 * @param <E> the type of elements held in this collection
 */

/*
 * 阻塞队列
 * 插入操作需要等待对应的另一个线程移除操作
 * 没有容量这一说法，压根不存储元素
 *
 * 无法 peek 元素，因为元素只会在移除的时候出现
 *
 * <em>队列的head</em>是第一个排队插入线程试图添加到队列中的元素；
 * 如果没有这样的排队线程，则没有元素可供删除，｛@code poll（）｝将返回｛@codenull｝。
 * 对于其他｛@code Collection｝方法（例如｛@ccode contains｝），｛@codeSynchronousQueue｝充当空集合。
 * 此队列不允许｛@code null｝元素。
 *
 * 支持公平和非公平模式
 */
public class SynchronousQueue<E> extends AbstractQueue<E>
    implements BlockingQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -3223113410248163686L;

    /*
     *  dual stack and dual queue 算法
     *
     * Lifo stack 非公平模式
     * Fifo queue 公平模式
     * Fifo通常在争用情况下支持较高的吞吐量，但Lifo在普通应用程序中保持较高的线程位置。
     *
     * 双队列（类似栈）是指在任何给定的时间都保存“data”（由put操作提供）或“request”（表示take操作的槽）或为空的队列。
     * “fulfill”的调用（即，从保存数据的队列中request的调用，反之亦然）使互补节点出列。
     * 这些队列最有趣的特点是，任何操作都可以确定队列处于哪种模式，并在不需要锁的情况下进行相应的操作。
     *
     * queue and stack 继承一个抽象的 Transferer 类，该类定义了一个方法 transfer 做 take 和 put 操作。
     * 队列和堆栈数据结构在概念上有很多相似之处，但具体细节很少。为了简单起见，它们保持不同，以便以后可以单独进化。
     *
     *
     * 1.原始算法使用位标记指针，但这里的算法在节点中使用模式位，这导致了许多进一步的调整。
     * 2.SynchronousQueues必须阻止等待完成的线程。
     * 3.支持通过超时和中断取消，包括从列表中清除已取消的节点/线程，以避免垃圾保留和内存耗尽。
     *
     * 阻塞主要是使用LockSupport park/unpark来完成的，但似乎是下一个要完成的节点会先旋转一点（仅在多处理器上）。
     * 在非常繁忙的同步队列上，旋转可以显著提高吞吐量。而在不太繁忙的情况下，旋转的数量很小，不会引起注意。
     *
     * 在队列和堆栈中以不同的方式进行清理。
     * 对于队列，当节点被取消时，我们几乎总是可以在O（1）时间内立即删除节点（一致性检查的模重试）。
     * 但如果它可能被固定为当前尾部，则必须等待后续取消。
     * 对于堆栈，我们需要一个潜在的O（n）遍历，以确保我们可以删除节点，但这可以与访问堆栈的其他线程同时运行。
     *
     * 虽然垃圾收集处理了大多数节点回收问题，否则这些问题会使非阻塞算法复杂化，
     * 但要注意“忘记”对数据、其他节点和线程的引用，这些引用可能会被阻塞线程长期保留。
     * 如果设置为null会与主算法发生冲突，则可以通过将节点的链接更改为现在指向节点本身来实现。
     * 这对于Stack节点来说并不常见（因为被阻塞的线程不会挂在旧的头指针上），
     * 但是必须积极地忘记队列节点中的引用，以避免任何节点自到达以来所引用的所有内容的可达性。
     */

    /*
     * This class implements extensions of the dual stack and dual
     * queue algorithms described in "Nonblocking Concurrent Objects
     * with Condition Synchronization", by W. N. Scherer III and
     * M. L. Scott.  18th Annual Conf. on Distributed Computing,
     * Oct. 2004 (see also
     * http://www.cs.rochester.edu/u/scott/synchronization/pseudocode/duals.html).
     * The (Lifo) stack is used for non-fair mode, and the (Fifo)
     * queue for fair mode. The performance of the two is generally
     * similar. Fifo usually supports higher throughput under
     * contention but Lifo maintains higher thread locality in common
     * applications.
     *
     * A dual queue (and similarly stack) is one that at any given
     * time either holds "data" -- items provided by put operations,
     * or "requests" -- slots representing take operations, or is
     * empty. A call to "fulfill" (i.e., a call requesting an item
     * from a queue holding data or vice versa) dequeues a
     * complementary node.  The most interesting feature of these
     * queues is that any operation can figure out which mode the
     * queue is in, and act accordingly without needing locks.
     *
     * Both the queue and stack extend abstract class Transferer
     * defining the single method transfer that does a put or a
     * take. These are unified into a single method because in dual
     * data structures, the put and take operations are symmetrical,
     * so nearly all code can be combined. The resulting transfer
     * methods are on the long side, but are easier to follow than
     * they would be if broken up into nearly-duplicated parts.
     *
     * The queue and stack data structures share many conceptual
     * similarities but very few concrete details. For simplicity,
     * they are kept distinct so that they can later evolve
     * separately.
     *
     * The algorithms here differ from the versions in the above paper
     * in extending them for use in synchronous queues, as well as
     * dealing with cancellation. The main differences include:
     *
     *  1. The original algorithms used bit-marked pointers, but
     *     the ones here use mode bits in nodes, leading to a number
     *     of further adaptations.
     *  2. SynchronousQueues must block threads waiting to become
     *     fulfilled.
     *  3. Support for cancellation via timeout and interrupts,
     *     including cleaning out cancelled nodes/threads
     *     from lists to avoid garbage retention and memory depletion.
     *
     * Blocking is mainly accomplished using LockSupport park/unpark,
     * except that nodes that appear to be the next ones to become
     * fulfilled first spin a bit (on multiprocessors only). On very
     * busy synchronous queues, spinning can dramatically improve
     * throughput. And on less busy ones, the amount of spinning is
     * small enough not to be noticeable.
     *
     * Cleaning is done in different ways in queues vs stacks.  For
     * queues, we can almost always remove a node immediately in O(1)
     * time (modulo retries for consistency checks) when it is
     * cancelled. But if it may be pinned as the current tail, it must
     * wait until some subsequent cancellation. For stacks, we need a
     * potentially O(n) traversal to be sure that we can remove the
     * node, but this can run concurrently with other threads
     * accessing the stack.
     *
     * While garbage collection takes care of most node reclamation
     * issues that otherwise complicate nonblocking algorithms, care
     * is taken to "forget" references to data, other nodes, and
     * threads that might be held on to long-term by blocked
     * threads. In cases where setting to null would otherwise
     * conflict with main algorithms, this is done by changing a
     * node's link to now point to the node itself. This doesn't arise
     * much for Stack nodes (because blocked threads do not hang on to
     * old head pointers), but references in Queue nodes must be
     * aggressively forgotten to avoid reachability of everything any
     * node has ever referred to since arrival.
     */

    /**
     * Shared internal API for dual stacks and queues.
     */
    // 提供队列和栈共享的 API
    abstract static class Transferer<E> {
        /**
         * Performs a put or take.
         *
         * @param e if non-null, the item to be handed to a consumer;
         *          if null, requests that transfer return an item
         *          offered by producer.
         * @param timed if this operation should timeout
         * @param nanos the timeout, in nanoseconds
         * @return if non-null, the item provided or received; if null,
         *         the operation failed due to timeout or interrupt --
         *         the caller can distinguish which of these occurred
         *         by checking Thread.interrupted.
         */
        /*
         * put 或者 take
         * @param e     如果是非空，item 表示要提交给消费者
         *              如果是空，表示需要从生产者那里获取一个 item
         * @param timed 是否允许超时
         * @param nanos 超时时间
         * @return      返回非空，表示 item 是获取到了或者发送成功了
         *              返回空，表示操作失败，可能是超时或者被中断，
         *              调用方可以通过检查Thread.interrupted来区分发生了哪些情况。
         */
        abstract E transfer(E e, boolean timed, long nanos);
    }

    /** The number of CPUs, for spin control */
    // CPU 的核心数，用作自旋操作
    static final int NCPUS = Runtime.getRuntime().availableProcessors();

    /**
     * The number of times to spin before blocking in timed waits.
     * The value is empirically derived -- it works well across a
     * variety of processors and OSes. Empirically, the best value
     * seems not to vary with number of CPUs (beyond 2) so is just
     * a constant.
     */
    /*
     * 计时等待
     *
     * 阻塞线程前，最大自旋的次数，
     * 该值是根据经验得出的，它在各种处理器和操作系统上运行良好。
     * 根据经验，最佳值似乎不随CPU的数量而变化（超过2个），因此只是一个常数。
     */
    static final int maxTimedSpins = (NCPUS < 2) ? 0 : 32;

    /**
     * The number of times to spin before blocking in untimed waits.
     * This is greater than timed value because untimed waits spin
     * faster since they don't need to check times on each spin.
     */
    /*
     * 无限等待
     * 阻塞线程前，最大自旋的次数，
     * 该值是根据经验得出的，它在各种处理器和操作系统上运行良好。
     * 根据经验，最佳值似乎不随CPU的数量而变化（超过2个），因此只是一个常数。
     */
    static final int maxUntimedSpins = maxTimedSpins * 16;

    /**
     * The number of nanoseconds for which it is faster to spin
     * rather than to use timed park. A rough estimate suffices.
     */
    // 旋转比使用定时停车更快的纳秒数。粗略估计就足够了。
    static final long spinForTimeoutThreshold = 1000L;

    /** Dual stack */
    // 栈
    static final class TransferStack<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual stack algorithm, differing,
         * among other ways, by using "covering" nodes rather than
         * bit-marked pointers: Fulfilling operations push on marker
         * nodes (with FULFILLING bit set in mode) to reserve a spot
         * to match a waiting node.
         */

        /* Modes for SNodes, ORed together in node fields */
        /** Node represents an unfulfilled consumer */
        static final int REQUEST    = 0;    // 00 未配对的消费者
        /** Node represents an unfulfilled producer */
        static final int DATA       = 1;    // 01 未配对的生产者
        /** Node is fulfilling another unfulfilled DATA or REQUEST */
        /*
         * 表示 Node 类型为匹配中的类型
         * 假设栈顶元素是 REQUEST-Node 类型，当前请求类型是 DATA 的话，入栈会修改类型为 FULFILLING
         * 假设栈顶元素是 DATA-Node 类型，当前请求类型是 REQUEST 的话，入栈会修改类型为 FULFILLING
         */
        static final int FULFILLING = 2;    // 10 配对成功的消费者/生产者

        /** Returns true if m has fulfilling bit set. */
        // 判断某个节点是否匹配成功
        static boolean isFulfilling(int m) { return (m & FULFILLING) != 0; }

        /** Node class for TransferStacks. */
        // TransferStacks 栈节点对象
        static final class SNode {
            volatile SNode next;        // next node in stack           栈中的指向下一个栈帧
            volatile SNode match;       // the node matched to this     与当前结点配对的结点
            // 假设当前node 对应的线程，自旋期间未匹配成功，那么 node 对应的线程需要挂起，挂起前 waiter 保存对应的线程引用，方便匹配后唤醒
            volatile Thread waiter;     // to control park/unpark       当前结点对应的线程
            // data 不是空的话表示当前是 DATA请求，入队
            // data 是空的话表示当前是 REQUEST 请求，出队
            Object item;                // data; or null for REQUESTs   实际数据或null
            int mode;                   // 结点类型，DATA,REQUEST,FULFILLING
            // Note: item and mode fields don't need to be volatile
            // since they are always written before, and read after,
            // other volatile/atomic operations.

            SNode(Object item) {
                this.item = item;
            }

            // CAS 方式设置 Node 对象的 next 字段
            // 将当前 node 对象的 next 字段由 cmp 设置为 val
            boolean casNext(SNode cmp, SNode val) {
                // 为什么要判断 cmp == next 呢？
                // 因为 CAS 在平台执行时，同一时刻只能有一个 CAS 指令被执行
                // 在 java 层面多一个 cmp == next 判断，可以提升一些性能，假如 cmp != next 了，就没必要去 cas 了
                return cmp == next &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }

            /**
             * Tries to match node s to this node, if so, waking up thread.
             * Fulfillers call tryMatch to identify their waiters.
             * Waiters block until they have been matched.
             *
             * @param s the node to match
             * @return true if successfully matched to s
             */
            /*
             * 尝试匹配，调用该方法的对象是栈顶节点的 next 对象
             * @param s
             * @return 返回true 表示匹配成功
             */
            boolean tryMatch(SNode s) {
                // match == null 说明当前 Node 还未匹配，然后 CAS 尝试将 match 设置为 s
                if (match == null &&
                    UNSAFE.compareAndSwapObject(this, matchOffset, null, s)) {
                    // 当前 NOde 如果自旋结束后，会调用 park 方法挂起，挂起之前会将挂起的线程存到 waiter 字段里
                    Thread w = waiter;
                    if (w != null) {    // waiters need at most one unpark
                        waiter = null;
                        // 唤醒
                        LockSupport.unpark(w);
                    }
                    return true;
                }
                return match == s;
            }

            /**
             * Tries to cancel a wait by matching node to itself.
             */
            /*
             * 尝试取消，取消状态的 match 是 this
             */
            void tryCancel() {
                // match 字段，保留当前 Node 对象本身，表示这个 Node 是取消状态
                // 取消状态的 Node，最终会被强制移除出栈
                UNSAFE.compareAndSwapObject(this, matchOffset, null, this);
            }

            /*
             * match 字段，保留当前 Node 对象本身，表示这个 Node 是取消状态，最终会被强制移除出栈
             */
            boolean isCancelled() {
                return match == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long matchOffset;
            private static final long nextOffset;

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = SNode.class;
                    matchOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("match"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        /** The head (top) of the stack */
        // 栈顶指针，top
        volatile SNode head;

        // 将栈顶节点由 h 设置为 nh
        boolean casHead(SNode h, SNode nh) {
            // 先判断 h == head，如果不相等就不 cAS 了
            return h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh);
        }

        /**
         * Creates or resets fields of a node. Called only from transfer
         * where the node to push on stack is lazily created and
         * reused when possible to help reduce intervals between reads
         * and CASes of head and to avoid surges of garbage when CASes
         * to push nodes fail due to contention.
         */
        /**
         * 创建或者重置一个 SNode 的属性
         *
         * @param s     Snode的引用，当是 null 的时候，会创建一个新的 SNode
         * @param e     Snode 的 item 字段
         * @param next  snode 的 next 节点，也就是 s 的下一个栈帧
         * @param mode  模式
         */
        static SNode snode(SNode s, Object e, SNode next, int mode) {
            if (s == null) s = new SNode(e);
            s.mode = mode;
            s.next = next;
            return s;
        }

        /**
         * Puts or takes an item.
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /*
             * Basic algorithm is to loop trying one of three actions:
             *
             * 1. If apparently empty or already containing nodes of same
             *    mode, try to push node on stack and wait for a match,
             *    returning it, or null if cancelled.
             *
             * 2. If apparently containing node of complementary mode,
             *    try to push a fulfilling node on to stack, match
             *    with corresponding waiting node, pop both from
             *    stack, and return matched item. The matching or
             *    unlinking might not actually be necessary because of
             *    other threads performing action 3:
             *
             * 3. If top of stack already holds another fulfilling node,
             *    help it out by doing its match and/or pop
             *    operations, and then continue. The code for helping
             *    is essentially the same as for fulfilling, except
             *    that it doesn't return the item.
             */

            /*
             * 基本算法是循环尝试三个动作中的一个
             * 1、如果明显为空或已经包含相同模式的节点，请尝试在堆栈上推送节点并等待匹配，返回匹配，如果取消则返回null。
             * 2、如果显然包含互补模式的节点，则尝试将一个完成节点推到堆栈上，匹配相应的等待节点，从堆栈中弹出两个节点，并返回匹配的项。由于其他线程执行操作3:
             * 3、如果堆栈顶部已经有另一个实现节点，请通过执行匹配和/或弹出操作来帮助它，然后继续。帮助的代码基本上与实现的代码相同，只是它不返回项目。
             */


            // 包装当前线程的 SNode
            SNode s = null; // constructed/reused as needed
            /*
             * 获取模式
             * REQUEST 表示当前线程是消费者（出队），需要一个 item
             * DATA 表示当前线程是生产者（入队），生产一个 item
             */
            int mode = (e == null) ? REQUEST : DATA;

            for (;;) {
                SNode h = head;
                // CASE1: 栈为空 或 栈顶结点的模式与当前mode相同，都需要做入栈操作
                if (h == null || h.mode == mode) {  // empty or same-mode
                    // CASE1.1: timed 成立表示当前是支持超时的，nanos <= 0 表示已经等待超时了
                    if (timed && nanos <= 0) {      // can't wait
                        // 条件成立表示栈顶元素是取消状态，需要弹出取消状态的节点
                        if (h != null && h.isCancelled())
                            casHead(h, h.next);     // pop cancelled node
                        else
                            return null;
                    }
                    // CASE1.1 前置条件是栈顶元素是 null 或者当前模式与栈顶元素模式一样
                    // 且 timed 是 false 或者支持超时但是超时时间未到
                    // s.next = h，将 s 设置为新的 top 节点，入栈操作
                    else if (casHead(h, s = snode(s, e, h, mode))) {
                        // 走到这里说明入栈成功， 阻塞当前调用线程，等待被匹配
                        SNode m = awaitFulfill(s, timed, nanos);
                        // 1. 正常情况是匹配到值了，就是匹配到的节点
                        // 2. 异常情况就是被取消了，match 是 this，指向当前节点
                        if (m == s) {               // wait was cancelled
                            // 将取消的节点移除栈，直接移除，并不是 pop
                            clean(s);
                            return null;
                        }
                        // 此时m为配对结点
                        // (h = head) != null 表示栈顶不为 null
                        // h.next == s 说明栈顶的下一个元素是当前线程封装的节点 s，说明匹配的两个节点还未出栈
                        if ((h = head) != null && h.next == s)
                            // 出栈匹配的 两个节点
                            casHead(h, s.next);     // help s's fulfiller
                        // 入队DATA线程就是  提交的值, 出队线程返回配对结点的值
                        return (E) ((mode == REQUEST) ? m.item : s.item);
                    }
                    // 执行到此处说明入栈失败(多个线程同时入栈导致CAS操作head失败),则进入下一次自旋继续执行
                }
                // CASE2: 前置条件，栈顶结点的模式与当前mode不相同，栈顶结点还未配对成功
                // 条件：栈顶元素的模式不是 FULFILLING，进入该分支去匹配
                else if (!isFulfilling(h.mode)) { // try to fulfill
                    // case2.1: 元素取消情况（因中断或超时）的处理
                    if (h.isCancelled())            // already cancelled
                        // 移除取消节点
                        casHead(h, h.next);         // pop and retry
                    // case2.2: 将当前结点压入栈中，将当前节点的 mode 改为 FULFILLING|mode
                    else if (casHead(h, s=snode(s, e, h, FULFILLING|mode))) {
                        // 自旋直到匹配成功或者 waiter 没了
                        for (;;) { // loop until matched or waiters disappear
                            SNode m = s.next;       // m is s's match
                            // m == null 什么时候成立？
                            // 当 s.next 节点超时或者外部线程中断唤醒后，会执行 clean 操作将自己清理出站
                            // 此时站在匹配者线程来看，s.next 是有可能是 null 的
                            if (m == null) {        // all waiters are gone
                                // 将整个栈清空，因为 s.next 都是 null 了，只有 s 一个节点
                                casHead(s, null);   // pop fulfill node
                                s = null;           // use new node next time
                                break;              // restart main loop
                            }
                            SNode mn = m.next;
                            if (m.tryMatch(s)) {
                                // 假如匹配成功，就将 m 和 s 一起弹出栈，将 mn 设置为新的栈顶
                                casHead(s, mn);     // pop both s and m
                                return (E) ((mode == REQUEST) ? m.item : s.item);
                            } else                  // lost match
                                // 假如匹配失败，说明 m 可能出现了超时了等情况，帮助将 m 移出栈
                                s.casNext(m, mn);   // help unlink
                        }
                    }
                }
                // CASE3：栈顶模式是 FULFILLING，表示栈顶和栈顶的 next 节点正在匹配
                // 当前请求需要做协助工作
                else {                            // help a fulfiller
                    SNode m = h.next;               // m is h's match
                    if (m == null)                  // waiter is gone
                        casHead(h, null);           // pop fulfilling node
                    else {
                        SNode mn = m.next;
                        if (m.tryMatch(h))          // help match
                            // 假如匹配成功，就将 m 和 s 一起弹出栈，将 mn 设置为新的栈顶
                            casHead(h, mn);         // pop both h and m
                        else                        // lost match
                            // 假如匹配失败，说明 m 可能出现了超时了等情况，帮助将 m 移出栈
                            h.casNext(m, mn);       // help unlink
                    }
                }
            }
        }

        /**
         * Spins/blocks until node s is matched by a fulfill operation.
         *
         * @param s the waiting node
         * @param timed true if timed wait
         * @param nanos timeout value
         * @return matched node, or s if cancelled
         */
        /**
         * 自旋或者阻塞直到匹配到了数据
         *
         * @param s     当前线程封装的 Node
         * @param timed 是否支持超时，true 表示支持
         * @param nanos 超时时间
         * @return
         */
        SNode awaitFulfill(SNode s, boolean timed, long nanos) {
            /*
             * 当一个节点/线程将要阻塞时，它设置其 waiter 字段，然后在实际 park 前至少再次检查一次状态，
             * 从而覆盖了 ras 与fulfiller，注意到 waiter 是非空的，因此应该被唤醒。
             *
             * 当被出现在调用点的堆栈顶端的节点调用时，对park的调用之前会有旋转，
             * 以避免在生产者和消费者非常接近的时间到达时发生阻塞。这种情况只会在多处理器上发生。
             *
             * 从主循环返回的检查顺序反映了  中断优先于正常返回的事实，正常返回优先于超时。
             * （因此，在超时时，在放弃之前会进行最后一次匹配检查。）除了来自无计时SynchronousQueue的调用。
             * ｛poll/offer｝不检查中断，根本不等待，因此被困在传输方法中，而不是调用awaitFulfill。
             */
            /*
             * When a node/thread is about to block, it sets its waiter
             * field and then rechecks state at least one more time
             * before actually parking, thus covering race vs
             * fulfiller noticing that waiter is non-null so should be
             * woken.
             *
             * When invoked by nodes that appear at the point of call
             * to be at the head of the stack, calls to park are
             * preceded by spins to avoid blocking when producers and
             * consumers are arriving very close in time.  This can
             * happen enough to bother only on multiprocessors.
             *
             * The order of checks for returning out of main loop
             * reflects fact that interrupts have precedence over
             * normal returns, which have precedence over
             * timeouts. (So, on timeout, one last check for match is
             * done before giving up.) Except that calls from untimed
             * SynchronousQueue.{poll/offer} don't check interrupts
             * and don't wait at all, so are trapped in transfer
             * method rather than calling awaitFulfill.
             */
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            Thread w = Thread.currentThread();
            // 当前请求线程在下面的 for 自旋中的自旋次数，自选次数后还未匹配成功则 park 线程
            int spins = (shouldSpin(s) ?
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);
            // 自旋检查逻辑 1.是否超时 2.是否中断 3.是否匹配成功
            for (;;) {
                if (w.isInterrupted())
                    // 收到中断信息，取消
                    s.tryCancel();
                // s.match 两种情况
                // 1. 正常情况是匹配到值了，就是匹配到的节点
                // 2. 异常情况就是被取消了，match 是 this，指向当前节点
                SNode m = s.match;
                if (m != null)
                    // 匹配到值了，获取取消了，直接返回
                    return m;
                if (timed) {
                    nanos = deadline - System.nanoTime();
                    if (nanos <= 0L) {
                        // 超时了，取消
                        s.tryCancel();
                        // 继续循环，会在上面返回 return
                        continue;
                    }
                }
                if (spins > 0)
                    // 自旋次数-1
                    spins = shouldSpin(s) ? (spins-1) : 0;
                // 说明自旋次数完了，保存当前线程对象到 waiter 中
                else if (s.waiter == null)
                    s.waiter = w; // establish waiter so can park next iter
                else if (!timed)
                    // 无限挂起，等待唤醒
                    LockSupport.park(this);
                else if (nanos > spinForTimeoutThreshold)
                    // 超时挂起
                    LockSupport.parkNanos(this, nanos);
            }
        }

        /**
         * Returns true if node s is at head or there is an active
         * fulfiller.
         */
        boolean shouldSpin(SNode s) {
            SNode h = head;
            // h == s 表示当前线程封装的节点 s 就是栈顶节点，需要自旋
            // h !=s && h == null，？ 没懂
            // h !=s && h != nul && isFulfilling(h.mode) 前提是当前元素不是栈顶元素， 并且当前栈顶正在匹配中，
            // 这时候栈顶下面 next 的所有元素都允许自旋检查
            return (h == s || h == null || isFulfilling(h.mode));
        }

        /**
         * Unlinks s from the stack.
         */
        // 调这个方法时，入参 s 已经是取消的节点
        void clean(SNode s) {
            // 清空数据域
            s.item = null;   // forget item
            // 释放线程引用
            s.waiter = null; // forget thread

            /*
             * At worst we may need to traverse entire stack to unlink
             * s. If there are multiple concurrent calls to clean, we
             * might not see s if another thread has already removed
             * it. But we can stop when we see any node known to
             * follow s. We use s.next unless it too is cancelled, in
             * which case we try the node one past. We don't check any
             * further because we don't want to doubly traverse just to
             * find sentinel.
             */

            // 定位取消节点的截止位置
            SNode past = s.next;
            if (past != null && past.isCancelled())
                // 假如 s 的 next 节点也是取消状态，那么就继续向下 next
                past = past.next;

            // Absorb cancelled nodes at head
            SNode p;
            // 从栈顶开始向下检查，将栈顶开始向下连续的取消状态的节点全部清理出去，直到碰到 past 为止
            while ((p = head) != null && p != past && p.isCancelled())
                casHead(p, p.next);

            // Unsplice embedded nodes
            // 说明 past 节点之前还有取消状态的节点没有清除
            while (p != null && p != past) {
                SNode n = p.next;
                if (n != null && n.isCancelled())
                    // 跳过取消状态的节点，unlink
                    p.casNext(n, n.next);
                else
                    p = n;
            }
        }

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferStack.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /** Dual Queue */
    // 双端队列
    static final class TransferQueue<E> extends Transferer<E> {
        /*
         * This extends Scherer-Scott dual queue algorithm, differing,
         * among other ways, by using modes within nodes rather than
         * marked pointers. The algorithm is a little simpler than
         * that for stacks because fulfillers do not need explicit
         * nodes, and matching is done by CAS'ing QNode.item field
         * from non-null to null (for put) or vice versa (for take).
         */

        /** Node class for TransferQueue. */
        // 双端队列的节点
        static final class QNode {
            // 指向当前节点的下一个节点，链表
            volatile QNode next;          // next node in queue
            // 数据
            // DATA 时表示的是存储的元素
            // REQUEST 时 item 是 null
            volatile Object item;         // CAS'ed to or from null
            // 当 Node 对应的线程未匹配到节点时，对应的线程会挂起，挂起之前将当前线程的引用赋值到 waiter
            volatile Thread waiter;       // to control park/unpark
            // true 表示当前 Node 是 DATA 类型，false 表示当前 Node 是 REQUEST 类型
            final boolean isData;

            QNode(Object item, boolean isData) {
                this.item = item;
                this.isData = isData;
            }

            // 修改 next 引用，从 cmp 改为 val
            boolean casNext(QNode cmp, QNode val) {
                return next == cmp &&
                    UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
            }

            // 修改数据域，从 cmp 改为 val
            boolean casItem(Object cmp, Object val) {
                return item == cmp &&
                    UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
            }

            /**
             * Tries to cancel by CAS'ing ref to this as item.
             */
            // 尝试取消当前 Node，取消状态的节点的 item 字段指向自己 this
            void tryCancel(Object cmp) {
                UNSAFE.compareAndSwapObject(this, itemOffset, cmp, this);
            }

            // 判断当前节点是否是取消状态
            boolean isCancelled() {
                return item == this;
            }

            /**
             * Returns true if this node is known to be off the queue
             * because its next pointer has been forgotten due to
             * an advanceHead operation.
             */
            // 判断当前节点是否不在队列，true 表示不在，next 指向自己时说明节点已经出队
            boolean isOffList() {
                return next == this;
            }

            // Unsafe mechanics
            private static final sun.misc.Unsafe UNSAFE;
            private static final long itemOffset;
            private static final long nextOffset;

            static {
                try {
                    UNSAFE = sun.misc.Unsafe.getUnsafe();
                    Class<?> k = QNode.class;
                    itemOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("item"));
                    nextOffset = UNSAFE.objectFieldOffset
                        (k.getDeclaredField("next"));
                } catch (Exception e) {
                    throw new Error(e);
                }
            }
        }

        /** Head of queue */
        // 指向队列的 dummy 节点，虚假节点，next 才是真正的头节点
        transient volatile QNode head;
        /** Tail of queue */
        // 指向队列的尾结点
        transient volatile QNode tail;
        /**
         * Reference to a cancelled node that might not yet have been
         * unlinked from queue because it was the last inserted node
         * when it was cancelled.
         */
        /*
         * 被清理节点的前驱节点
         * 因为入队操作是两步完成的，
         * 1. t.next = newNode
         * 2. tail = newNode
         * 队尾出队是一种特殊的情况，需要特殊处理
         */
        transient volatile QNode cleanMe;

        TransferQueue() {
            // 初始化一个 dummy 节点
            QNode h = new QNode(null, false); // initialize to dummy node.
            head = h;
            tail = h;
        }

        /**
         * Tries to cas nh as new head; if successful, unlink
         * old head's next node to avoid garbage retention.
         */
        // 设置头指针，老的头节点出队
        void advanceHead(QNode h, QNode nh) {
            if (h == head &&
                UNSAFE.compareAndSwapObject(this, headOffset, h, nh))
                // 老的头节点的 next 指向自己，gc
                h.next = h; // forget old next
        }

        /**
         * Tries to cas nt as new tail.
         */
        // 设置队尾节点
        void advanceTail(QNode t, QNode nt) {
            if (tail == t)
                UNSAFE.compareAndSwapObject(this, tailOffset, t, nt);
        }

        /**
         * Tries to CAS cleanMe slot.
         */
        boolean casCleanMe(QNode cmp, QNode val) {
            return cleanMe == cmp &&
                UNSAFE.compareAndSwapObject(this, cleanMeOffset, cmp, val);
        }

        /**
         * Puts or takes an item.
         */
        @SuppressWarnings("unchecked")
        E transfer(E e, boolean timed, long nanos) {
            /* Basic algorithm is to loop trying to take either of
             * two actions:
             *
             * 1. If queue apparently empty or holding same-mode nodes,
             *    try to add node to queue of waiters, wait to be
             *    fulfilled (or cancelled) and return matching item.
             *
             * 2. If queue apparently contains waiting items, and this
             *    call is of complementary mode, try to fulfill by CAS'ing
             *    item field of waiting node and dequeuing it, and then
             *    returning matching item.
             *
             * In each case, along the way, check for and try to help
             * advance head and tail on behalf of other stalled/slow
             * threads.
             *
             * The loop starts off with a null check guarding against
             * seeing uninitialized head or tail values. This never
             * happens in current SynchronousQueue, but could if
             * callers held non-volatile/final ref to the
             * transferer. The check is here anyway because it places
             * null checks at top of loop, which is usually faster
             * than having them implicitly interspersed.
             */

            // s 指向当前请求对应的 Node
            QNode s = null; // constructed/reused as needed
            // true 表示当前请求是一个 DATA，否则就是 REQUEST
            boolean isData = (e != null);

            for (;;) {
                QNode t = tail;
                QNode h = head;
                if (t == null || h == null)         // saw uninitialized value
                    continue;                       // spin

                // CASE1 h==t 说明head和 tail 指向 dummy节点，空队列，当前请求 Node需要入队
                // t.isData == isData 模式相同
                if (h == t || t.isData == isData) { // empty or same-mode
                    // 获取当前队尾的 next 指针，tNext
                    QNode tn = t.next;
                    if (t != tail)                  // inconsistent read
                        // 出现并发了，当前线程在入队之前，其他线程可能已经入队了，改变了 tail 引用
                        // 继续自旋判断
                        continue;
                    if (tn != null) {               // lagging tail
                        // 说明已经有线程完成入队了，但是只完成了入队的第一步 t.next = newNode，
                        // 第二步更新 tail 指针执行尾结点的操作还未完成当前线程需要协助更新 tail 指针
                        advanceTail(t, tn);
                        // 线程继续自旋判断
                        continue;
                    }
                    // 条件成立说明调用 transfer 方法的调用方是 offer() 这种无参的这种方法进来的，这种方法不支持阻塞等待
                    if (timed && nanos <= 0)        // can't wait
                        // 检查没有匹配到，直接返回 null
                        return null;
                    // 条件成立说明当前请求尚未创建请求 Node
                    if (s == null)
                        s = new QNode(e, isData);
                    // 第一步：尝试入队，将 t.next 改为 s
                    if (!t.casNext(null, s))        // failed to link in
                        continue;

                    // 走到这里说明上一步的 casNext 成功了
                    // 第二步：更新队尾tail 指针指向 s
                    advanceTail(t, s);              // swing tail and wait
                    // 当前节点等待匹配
                    Object x = awaitFulfill(s, e, timed, nanos);
                    // x == s，说明当前节点已经取消了
                    if (x == s) {                   // wait was cancelled
                        // 清理 s 节点出队
                        clean(t, s);
                        return null;
                    }


                    // 走到这里说明当前 Node 匹配成功

                    // awaitFulfill 这里两种情况，1自旋中匹配到元素了，2 阻塞被唤醒了
                    // 阻塞被唤醒时，s 一定是出队了的，因为是先出队再唤醒的
                    // 下面的操作是处理自旋时匹配到元素的情况，这是还未出队
                    if (!s.isOffList()) {           // not already unlinked
                        // 条件成立，说明当前 Node 仍然在队列中
                        // 需要做匹配成功后的出队逻辑
                        // t 是 s 的前驱，这里就是将 t 出队
                        advanceHead(t, s);          // unlink if head
                        // x != null && item != this，因为 s 节点已经出队了，所以需要把它的 item 域赋值给自己，表示他是取消状态
                        if (x != null)              // and forget fields
                            s.item = s;
                        // 置空
                        s.waiter = null;
                    }
                    return (x != null) ? (E)x : e;

                }
                // 队尾节点与当前请求的节点是互补节点，也就是说模式不同咯，需要匹配
                else {                            // complementary-mode
                    // h.next 是真正的队首节点
                    QNode m = h.next;               // node to fulfill
                    // t != tail 说明出现并发了，其他线程已经修改 tail 指针了
                    // t == tail && m == null 其他线程先一步匹配走了 head.next 节点
                    // t == tail && m != null && h != head 说明其他线程先异步匹配走 head.next 了
                    if (t != tail || m == null || h != head)
                        continue;                   // inconsistent read

                    Object x = m.item;
                    // true 表示当前请求是一个 DATA，否则就是 REQUEST
                    //
                    // 1、假设 isData == true，当前节点是 DATA，那么当前匹配的节点 m 表示的是 REQUEST 请求，
                    // 既然是 request 那么他的 item 就是 null，    true == (x != null)
                    // 2、假设 isData == false，当前节点就是 REQUESt，那么当前匹配的节点 m 表示的是 DATA
                    // 既然是 data，那么他的 item 一般就不是 null,    false == (x != null)
                    // 小结  isData == (x != null) 一般不会成立

                    // 条件 2：条件成立，说明m节点已经是取消状态了
                    // 条件 3：!m.casItem(x, e)，前提条件是 m 不是取消状态
                    // 1、假设当前 REQUEST，那么 m 就是 DATA，这里就是将 request 的 item(null) 给data 的 item，清空操作
                    // 2、假如当前 DATA，那么 m 就是 REQUEST，这里就是将 data 的 item 给 request 的 item
                    if (isData == (x != null) ||    // m already fulfilled
                        x == m ||                   // m cancelled  取消了
                        !m.casItem(x, e)) {         // lost CAS     尝试将 item 由 x 改为 e
                        // 走到这里说明替换失败，说明出现并发了
                        advanceHead(h, m);          // dequeue and retry
                        continue;
                    }

                    // 走到这里说明匹配完成了，
                    // 将真正的头节点出队
                    advanceHead(h, m);              // successfully fulfilled
                    // 唤醒阻塞的线程
                    LockSupport.unpark(m.waiter);
                    return (x != null) ? (E)x : e;
                }
            }
        }

        /**
         * Spins/blocks until node s is fulfilled.
         *
         * @param s the waiting node
         * @param e the comparison value for checking match
         * @param timed true if timed wait
         * @param nanos timeout value
         * @return matched item, or s if cancelled
         */
        Object awaitFulfill(QNode s, E e, boolean timed, long nanos) {
            /* Same idea as TransferStack.awaitFulfill */
            // 计算等待的截止时间
            final long deadline = timed ? System.nanoTime() + nanos : 0L;
            Thread w = Thread.currentThread();
            int spins = ((head.next == s) ?
                         (timed ? maxTimedSpins : maxUntimedSpins) : 0);
            // 开始自旋，检查状态等待匹配，挂起线程，检查是否被中断和超时
            for (;;) {
                if (w.isInterrupted())
                    // 线程被中断了，需要尝试将节点改为取消状态
                    s.tryCancel(e);
                Object x = s.item;
                // item 的情况
                // Snode 是 DATA 时，
                // 1、item 表示请求要传递的数据
                // 2、item == this 是，当前 SNOde 取消了
                // 3、item == null 时，表示已经有匹配节点了，已经把数据拿走了
                // SNode 是 REQUEST 时
                // 1、item == null，正常状态，当前请求仍然未匹配到对应的DATA请求
                // 2、item == this 当前 Snode 对应的线程，取消状态
                // 3、 item != null && item != this，表示当前 REQUEST 已经匹配到一个 DATA 节点了

                // 条件成立的情况
                // 1、DATA 模式时，item == this，item == null
                // 2、REQUEST 模式时，item == this，item != null && item != this
                if (x != e)
                    return x;
                // 判断是否指定超时
                if (timed) {
                    nanos = deadline - System.nanoTime();
                    if (nanos <= 0L) {
                        s.tryCancel(e);
                        continue;
                    }
                }
                if (spins > 0)
                    // 自旋次数自减
                    --spins;
                // 自旋完后，假如 waiter 为 null，尝试将 waiter 设置为当前线程
                else if (s.waiter == null)
                    s.waiter = w;
                else if (!timed)
                    // 无限等待
                    LockSupport.park(this);
                else if (nanos > spinForTimeoutThreshold)
                    // 计时等待
                    LockSupport.parkNanos(this, nanos);
            }
        }

        /**
         * Gets rid of cancelled node s with original predecessor pred.
         */
        // 可以画图方便理解
        void clean(QNode pred, QNode s) {
            s.waiter = null; // forget thread
            /*
             * At any given time, exactly one node on list cannot be
             * deleted -- the last inserted node. To accommodate this,
             * if we cannot delete s, we save its predecessor as
             * "cleanMe", deleting the previously saved version
             * first. At least one of node s or the node previously
             * saved can always be deleted, so this always terminates.
             */
            while (pred.next == s) { // Return early if already unlinked
                QNode h = head;
                QNode hn = h.next;   // Absorb cancelled first node as head
                // 从队首开始遍历，出队取消的节点
                if (hn != null && hn.isCancelled()) {
                    advanceHead(h, hn);
                    continue;
                }
                QNode t = tail;      // Ensure consistent read for tail
                if (t == h)
                    return;
                QNode tn = t.next;
                if (t != tail)
                    continue;
                if (tn != null) {
                    advanceTail(t, tn);
                    continue;
                }
                if (s != t) {        // If not tail, try to unsplice
                    // 中间节点出队
                    QNode sn = s.next;
                    if (sn == s || pred.casNext(s, sn))
                        return;
                }

                // 处理队尾节点出队，cleanMe表示要出队的tail节点的前驱
                // 处理队尾节点特殊，是因为并发情况下，tail 后面又入队了好多节点，假如直接出队把别的新入队节点都删了
                // 所以要保存一个前驱 cleanMe 节点，可以看到 unlink 一个中间节点了，这样计算后面有新节点入队也不会删除它们了
                QNode dp = cleanMe;
                if (dp != null) {    // Try unlinking previous cancelled node
                    QNode d = dp.next;
                    QNode dn;
                    if (d == null ||               // d is gone or
                        d == dp ||                 // d is off list or
                        !d.isCancelled() ||        // d not cancelled or
                        (d != t &&                 // d not tail and
                         (dn = d.next) != null &&  //   has successor
                         dn != d &&                //   that is on list
                         dp.casNext(d, dn)))       // d unspliced 修改下一个指针，这里就是 unlink cleanMe.next 节点了
                        casCleanMe(dp, null);
                    if (dp == pred)
                        return;      // s is already saved node
                } else if (casCleanMe(null, pred)) // 给 cleanMe 赋值
                    return;          // Postpone cleaning s
            }
        }

        private static final sun.misc.Unsafe UNSAFE;
        private static final long headOffset;
        private static final long tailOffset;
        private static final long cleanMeOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = TransferQueue.class;
                headOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("head"));
                tailOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("tail"));
                cleanMeOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("cleanMe"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /**
     * The transferer. Set only in constructor, but cannot be declared
     * as final without further complicating serialization.  Since
     * this is accessed only at most once per public method, there
     * isn't a noticeable performance penalty for using volatile
     * instead of final here.
     */
    private transient volatile Transferer<E> transferer;

    /**
     * Creates a {@code SynchronousQueue} with nonfair access policy.
     */
    public SynchronousQueue() {
        this(false);
    }

    /**
     * Creates a {@code SynchronousQueue} with the specified fairness policy.
     *
     * @param fair if true, waiting threads contend in FIFO order for
     *        access; otherwise the order is unspecified.
     */
    public SynchronousQueue(boolean fair) {
        transferer = fair ? new TransferQueue<E>() : new TransferStack<E>();
    }

    /**
     * Adds the specified element to this queue, waiting if necessary for
     * another thread to receive it.
     *
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        if (transferer.transfer(e, false, 0) == null) {
            Thread.interrupted();
            throw new InterruptedException();
        }
    }

    /**
     * Inserts the specified element into this queue, waiting if necessary
     * up to the specified wait time for another thread to receive it.
     *
     * @return {@code true} if successful, or {@code false} if the
     *         specified waiting time elapses before a consumer appears
     * @throws InterruptedException {@inheritDoc}
     * @throws NullPointerException {@inheritDoc}
     */
    public boolean offer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {
        if (e == null) throw new NullPointerException();
        if (transferer.transfer(e, true, unit.toNanos(timeout)) != null)
            return true;
        if (!Thread.interrupted())
            return false;
        throw new InterruptedException();
    }

    /**
     * Inserts the specified element into this queue, if another thread is
     * waiting to receive it.
     *
     * @param e the element to add
     * @return {@code true} if the element was added to this queue, else
     *         {@code false}
     * @throws NullPointerException if the specified element is null
     */
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        return transferer.transfer(e, true, 0) != null;
    }

    /**
     * Retrieves and removes the head of this queue, waiting if necessary
     * for another thread to insert it.
     *
     * @return the head of this queue
     * @throws InterruptedException {@inheritDoc}
     */
    public E take() throws InterruptedException {
        E e = transferer.transfer(null, false, 0);
        if (e != null)
            return e;
        Thread.interrupted();
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, waiting
     * if necessary up to the specified wait time, for another thread
     * to insert it.
     *
     * @return the head of this queue, or {@code null} if the
     *         specified waiting time elapses before an element is present
     * @throws InterruptedException {@inheritDoc}
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E e = transferer.transfer(null, true, unit.toNanos(timeout));
        if (e != null || !Thread.interrupted())
            return e;
        throw new InterruptedException();
    }

    /**
     * Retrieves and removes the head of this queue, if another thread
     * is currently making an element available.
     *
     * @return the head of this queue, or {@code null} if no
     *         element is available
     */
    public E poll() {
        return transferer.transfer(null, true, 0);
    }

    /**
     * Always returns {@code true}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return {@code true}
     */
    public boolean isEmpty() {
        return true;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int size() {
        return 0;
    }

    /**
     * Always returns zero.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @return zero
     */
    public int remainingCapacity() {
        return 0;
    }

    /**
     * Does nothing.
     * A {@code SynchronousQueue} has no internal capacity.
     */
    public void clear() {
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element
     * @return {@code false}
     */
    public boolean contains(Object o) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param o the element to remove
     * @return {@code false}
     */
    public boolean remove(Object o) {
        return false;
    }

    /**
     * Returns {@code false} unless the given collection is empty.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false} unless given collection is empty
     */
    public boolean containsAll(Collection<?> c) {
        return c.isEmpty();
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean removeAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code false}.
     * A {@code SynchronousQueue} has no internal capacity.
     *
     * @param c the collection
     * @return {@code false}
     */
    public boolean retainAll(Collection<?> c) {
        return false;
    }

    /**
     * Always returns {@code null}.
     * A {@code SynchronousQueue} does not return elements
     * unless actively waited on.
     *
     * @return {@code null}
     */
    public E peek() {
        return null;
    }

    /**
     * Returns an empty iterator in which {@code hasNext} always returns
     * {@code false}.
     *
     * @return an empty iterator
     */
    public Iterator<E> iterator() {
        return Collections.emptyIterator();
    }

    /**
     * Returns an empty spliterator in which calls to
     * {@link java.util.Spliterator#trySplit()} always return {@code null}.
     *
     * @return an empty spliterator
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return Spliterators.emptySpliterator();
    }

    /**
     * Returns a zero-length array.
     * @return a zero-length array
     */
    public Object[] toArray() {
        return new Object[0];
    }

    /**
     * Sets the zeroeth element of the specified array to {@code null}
     * (if the array has non-zero length) and returns it.
     *
     * @param a the array
     * @return the specified array
     * @throws NullPointerException if the specified array is null
     */
    public <T> T[] toArray(T[] a) {
        if (a.length > 0)
            a[0] = null;
        return a;
    }

    /**
     * @throws UnsupportedOperationException {@inheritDoc}
     * @throws ClassCastException            {@inheritDoc}
     * @throws NullPointerException          {@inheritDoc}
     * @throws IllegalArgumentException      {@inheritDoc}
     */
    public int drainTo(Collection<? super E> c) {
        if (c == null)
            throw new NullPointerException();
        if (c == this)
            throw new IllegalArgumentException();
        int n = 0;
        for (E e; (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
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
        int n = 0;
        for (E e; n < maxElements && (e = poll()) != null;) {
            c.add(e);
            ++n;
        }
        return n;
    }

    /*
     * To cope with serialization strategy in the 1.5 version of
     * SynchronousQueue, we declare some unused classes and fields
     * that exist solely to enable serializability across versions.
     * These fields are never used, so are initialized only if this
     * object is ever serialized or deserialized.
     */

    @SuppressWarnings("serial")
    static class WaitQueue implements java.io.Serializable { }
    static class LifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3633113410248163686L;
    }
    static class FifoWaitQueue extends WaitQueue {
        private static final long serialVersionUID = -3623113410248163686L;
    }
    private ReentrantLock qlock;
    private WaitQueue waitingProducers;
    private WaitQueue waitingConsumers;

    /**
     * Saves this queue to a stream (that is, serializes it).
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        boolean fair = transferer instanceof TransferQueue;
        if (fair) {
            qlock = new ReentrantLock(true);
            waitingProducers = new FifoWaitQueue();
            waitingConsumers = new FifoWaitQueue();
        }
        else {
            qlock = new ReentrantLock();
            waitingProducers = new LifoWaitQueue();
            waitingConsumers = new LifoWaitQueue();
        }
        s.defaultWriteObject();
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
        s.defaultReadObject();
        if (waitingProducers instanceof FifoWaitQueue)
            transferer = new TransferQueue<E>();
        else
            transferer = new TransferStack<E>();
    }

    // Unsafe mechanics
    static long objectFieldOffset(sun.misc.Unsafe UNSAFE,
                                  String field, Class<?> klazz) {
        try {
            return UNSAFE.objectFieldOffset(klazz.getDeclaredField(field));
        } catch (NoSuchFieldException e) {
            // Convert Exception to corresponding Error
            NoSuchFieldError error = new NoSuchFieldError(field);
            error.initCause(e);
            throw error;
        }
    }

}
