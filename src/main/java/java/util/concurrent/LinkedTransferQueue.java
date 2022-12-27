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

import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * An unbounded {@link TransferQueue} based on linked nodes.
 * This queue orders elements FIFO (first-in-first-out) with respect
 * to any given producer.  The <em>head</em> of the queue is that
 * element that has been on the queue the longest time for some
 * producer.  The <em>tail</em> of the queue is that element that has
 * been on the queue the shortest time for some producer.
 *
 * <p>Beware that, unlike in most collections, the {@code size} method
 * is <em>NOT</em> a constant-time operation. Because of the
 * asynchronous nature of these queues, determining the current number
 * of elements requires a traversal of the elements, and so may report
 * inaccurate results if this collection is modified during traversal.
 * Additionally, the bulk operations {@code addAll},
 * {@code removeAll}, {@code retainAll}, {@code containsAll},
 * {@code equals}, and {@code toArray} are <em>not</em> guaranteed
 * to be performed atomically. For example, an iterator operating
 * concurrently with an {@code addAll} operation might view only some
 * of the added elements.
 *
 * <p>This class and its iterator implement all of the
 * <em>optional</em> methods of the {@link Collection} and {@link
 * Iterator} interfaces.
 *
 * <p>Memory consistency effects: As with other concurrent
 * collections, actions in a thread prior to placing an object into a
 * {@code LinkedTransferQueue}
 * <a href="package-summary.html#MemoryVisibility"><i>happen-before</i></a>
 * actions subsequent to the access or removal of that element from
 * the {@code LinkedTransferQueue} in another thread.
 *
 * <p>This class is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @since 1.7
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 */

/*
 * 无界的阻塞队列，元素 FIFO
 *
 * 和其他集合不同的是，size 方法不是常量级别的操作
 * 因为这些队列的异步特性，确定当前集合元素的个数需要遍历集合，
 * size 方法返回的可能是个错误值，因为在遍历集合的时候集合可能被修改了
 *
 * 大量数据操作的方法不保证原子性 addAll removeAll retainAll containsAll equals toArray
 */
public class LinkedTransferQueue<E> extends AbstractQueue<E>
    implements TransferQueue<E>, java.io.Serializable {
    private static final long serialVersionUID = -3223113410248163686L;
    /**
     * 双端队列，node 可能表示 data 或者 request
     * 当一个线程尝试去入队一个 data 节点，但是遇到了一个 request 节点，这是会匹配，然后移除他们。反之亦然（入队 request）
     *
     * 阻塞的双端队列，将没有匹配到的节点阻塞，直到其他线程提供了一个可以匹配节点
     * https://www.cs.rochester.edu/u/scott/papers/2009_Scherer_CACM_SSQ.pdf
     * 另外，将不匹配数据排入队列的线程也会阻塞。根据调用方的指示，双传输队列支持所有这些模式。
     *
     * https://www.cs.rochester.edu/u/scott/papers/1996_PODC_queues.pdf
     * 它维护两个指针字段
     * “head”，指向一个（匹配的）节点，该节点依次指向第一个实际（不匹配的）队列节点（如果为空，则为空）；
     * 以及指向队列上最后一个节点的“tail”（如果为空，则返回null）。
     *
     *   head                tail
     *     |                   |
     *     v                   v
     *     M -> U -> U -> U -> U
     *
     *
     * 在双队列中，每个节点必须原子地保持其匹配状态。
     * 虽然还有其他可能的变体，但我们在这里将其实现为：
     * 对于数据模式节点，匹配需要在匹配时将“item”字段从非空数据值转换为空，
     * 对于请求节点，则需要将“item”字段从空转换为数据
     * 价值（请注意，这种类型队列的线性化特性很容易验证——元素通过链接可用，而通过匹配不可用。）与普通M&S队列相比，双队列的这种特性需要每个enq/deq对额外一个成功的原子操作。
     * 但它也支持队列维护机制的低成本变体。（这种想法的变体甚至适用于支持删除内部元素的非双重队列，例如j.u.c.ConcurrentLinkedQueue。）
     *
     * for a data-mode node, matching entails CASing an
     * "item" field from a non-null data value to null upon match, and
     * vice-versa for request nodes, CASing from null to a data value.
     *
     *
     * 一旦匹配了一个节点，它的匹配状态就再也不会改变了。
     * 因此，我们可以安排它们的链接列表包含零个或多个匹配节点的前缀，后跟零个或更多个不匹配节点的后缀。
     * （注意，我们允许前缀和后缀都为零长度，这反过来意味着我们不使用伪标头。）
     * 如果我们不考虑时间或空间效率，我们可以通过从指针遍历到初始节点来正确执行入队和出队操作；
     * 在匹配时对第一个不匹配节点的项进行CASing，在追加时对尾随节点的下一个字段进行CASing。
     * （加上最初空的一些特殊外壳）。虽然这本身是一个糟糕的想法，但它的好处是不需要对头/尾字段进行任何原子更新。
     */

    /*
     * *** Overview of Dual Queues with Slack ***
     *
     * Dual Queues, introduced by Scherer and Scott
     * (http://www.cs.rice.edu/~wns1/papers/2004-DISC-DDS.pdf) are
     * (linked) queues in which nodes may represent either data or
     * requests.  When a thread tries to enqueue a data node, but
     * encounters a request node, it instead "matches" and removes it;
     * and vice versa for enqueuing requests. Blocking Dual Queues
     * arrange that threads enqueuing unmatched requests block until
     * other threads provide the match. Dual Synchronous Queues (see
     * Scherer, Lea, & Scott
     * http://www.cs.rochester.edu/u/scott/papers/2009_Scherer_CACM_SSQ.pdf)
     * additionally arrange that threads enqueuing unmatched data also
     * block.  Dual Transfer Queues support all of these modes, as
     * dictated by callers.
     *
     * A FIFO dual queue may be implemented using a variation of the
     * Michael & Scott (M&S) lock-free queue algorithm
     * (http://www.cs.rochester.edu/u/scott/papers/1996_PODC_queues.pdf).
     * It maintains two pointer fields, "head", pointing to a
     * (matched) node that in turn points to the first actual
     * (unmatched) queue node (or null if empty); and "tail" that
     * points to the last node on the queue (or again null if
     * empty). For example, here is a possible queue with four data
     * elements:
     *
     *  head                tail
     *    |                   |
     *    v                   v
     *    M -> U -> U -> U -> U
     *
     * The M&S queue algorithm is known to be prone to scalability and
     * overhead limitations when maintaining (via CAS) these head and
     * tail pointers. This has led to the development of
     * contention-reducing variants such as elimination arrays (see
     * Moir et al http://portal.acm.org/citation.cfm?id=1074013) and
     * optimistic back pointers (see Ladan-Mozes & Shavit
     * http://people.csail.mit.edu/edya/publications/OptimisticFIFOQueue-journal.pdf).
     * However, the nature of dual queues enables a simpler tactic for
     * improving M&S-style implementations when dual-ness is needed.
     *
     * In a dual queue, each node must atomically maintain its match
     * status. While there are other possible variants, we implement
     * this here as: for a data-mode node, matching entails CASing an
     * "item" field from a non-null data value to null upon match, and
     * vice-versa for request nodes, CASing from null to a data
     * value. (Note that the linearization properties of this style of
     * queue are easy to verify -- elements are made available by
     * linking, and unavailable by matching.) Compared to plain M&S
     * queues, this property of dual queues requires one additional
     * successful atomic operation per enq/deq pair. But it also
     * enables lower cost variants of queue maintenance mechanics. (A
     * variation of this idea applies even for non-dual queues that
     * support deletion of interior elements, such as
     * j.u.c.ConcurrentLinkedQueue.)
     *
     * Once a node is matched, its match status can never again
     * change.  We may thus arrange that the linked list of them
     * contain a prefix of zero or more matched nodes, followed by a
     * suffix of zero or more unmatched nodes. (Note that we allow
     * both the prefix and suffix to be zero length, which in turn
     * means that we do not use a dummy header.)  If we were not
     * concerned with either time or space efficiency, we could
     * correctly perform enqueue and dequeue operations by traversing
     * from a pointer to the initial node; CASing the item of the
     * first unmatched node on match and CASing the next field of the
     * trailing node on appends. (Plus some special-casing when
     * initially empty).  While this would be a terrible idea in
     * itself, it does have the benefit of not requiring ANY atomic
     * updates on head/tail fields.
     *
     * We introduce here an approach that lies between the extremes of
     * never versus always updating queue (head and tail) pointers.
     * This offers a tradeoff between sometimes requiring extra
     * traversal steps to locate the first and/or last unmatched
     * nodes, versus the reduced overhead and contention of fewer
     * updates to queue pointers. For example, a possible snapshot of
     * a queue is:
     *
     * 我们在这里介绍了一种介于从不更新和始终更新队列（头和尾）指针之间的方法。
     * 这在有时需要额外的遍历步骤来定位第一个和/或最后一个不匹配的节点与减少开销和队列指针更新的争用之间提供了一种折衷。例如，队列的可能快照是：
     *
     *  head           tail
     *    |              |
     *    v              v
     *    M -> M -> U -> U -> U -> U
     *
     * The best value for this "slack" (the targeted maximum distance
     * between the value of "head" and the first unmatched node, and
     * similarly for "tail") is an empirical matter. We have found
     * that using very small constants in the range of 1-3 work best
     * over a range of platforms. Larger values introduce increasing
     * costs of cache misses and risks of long traversal chains, while
     * smaller values increase CAS contention and overhead.
     *
     * 这个“松弛”的最佳值（“头部”值与第一个不匹配节点之间的目标最大距离，“尾部”也是如此）是一个经验问题。
     * 我们发现，使用1-3范围内的非常小的常数在一系列平台上效果最好。
     * 较大的值会增加缓存未命中的成本和长遍历链的风险，而较小的值则会增加CAS争用和开销。
     *
     * Dual queues with slack differ from plain M&S dual queues by
     * virtue of only sometimes updating head or tail pointers when
     * matching, appending, or even traversing nodes; in order to
     * maintain a targeted slack.  The idea of "sometimes" may be
     * operationalized in several ways. The simplest is to use a
     * per-operation counter incremented on each traversal step, and
     * to try (via CAS) to update the associated queue pointer
     * whenever the count exceeds a threshold. Another, that requires
     * more overhead, is to use random number generators to update
     * with a given probability per traversal step.
     *
     * 具有松弛的双队列与普通M&S双队列的不同之处在于，
     * 在匹配、附加或甚至遍历节点时，有时只更新头部或尾部指针；以便保持目标松弛。
     * “有时”的想法可以通过几种方式实现。
     * 最简单的方法是在每个遍历步骤上使用递增的per-operation计数器，
     * 并在计数超过阈值时尝试（通过CAS）更新相关的队列指针。
     * 另一个需要更多开销的方法是使用随机数生成器以每个遍历步骤的给定概率进行更新。
     *
     * In any strategy along these lines, because CASes updating
     * fields may fail, the actual slack may exceed targeted
     * slack. However, they may be retried at any time to maintain
     * targets.  Even when using very small slack values, this
     * approach works well for dual queues because it allows all
     * operations up to the point of matching or appending an item
     * (hence potentially allowing progress by another thread) to be
     * read-only, thus not introducing any further contention. As
     * described below, we implement this by performing slack
     * maintenance retries only after these points.
     *
     * As an accompaniment to such techniques, traversal overhead can
     * be further reduced without increasing contention of head
     * pointer updates: Threads may sometimes shortcut the "next" link
     * path from the current "head" node to be closer to the currently
     * known first unmatched node, and similarly for tail. Again, this
     * may be triggered with using thresholds or randomization.
     *
     * These ideas must be further extended to avoid unbounded amounts
     * of costly-to-reclaim garbage caused by the sequential "next"
     * links of nodes starting at old forgotten head nodes: As first
     * described in detail by Boehm
     * (http://portal.acm.org/citation.cfm?doid=503272.503282) if a GC
     * delays noticing that any arbitrarily old node has become
     * garbage, all newer dead nodes will also be unreclaimed.
     * (Similar issues arise in non-GC environments.)  To cope with
     * this in our implementation, upon CASing to advance the head
     * pointer, we set the "next" link of the previous head to point
     * only to itself; thus limiting the length of connected dead lists.
     * (We also take similar care to wipe out possibly garbage
     * retaining values held in other Node fields.)  However, doing so
     * adds some further complexity to traversal: If any "next"
     * pointer links to itself, it indicates that the current thread
     * has lagged behind a head-update, and so the traversal must
     * continue from the "head".  Traversals trying to find the
     * current tail starting from "tail" may also encounter
     * self-links, in which case they also continue at "head".
     *
     * It is tempting in slack-based scheme to not even use CAS for
     * updates (similarly to Ladan-Mozes & Shavit). However, this
     * cannot be done for head updates under the above link-forgetting
     * mechanics because an update may leave head at a detached node.
     * And while direct writes are possible for tail updates, they
     * increase the risk of long retraversals, and hence long garbage
     * chains, which can be much more costly than is worthwhile
     * considering that the cost difference of performing a CAS vs
     * write is smaller when they are not triggered on each operation
     * (especially considering that writes and CASes equally require
     * additional GC bookkeeping ("write barriers") that are sometimes
     * more costly than the writes themselves because of contention).
     *
     * *** Overview of implementation ***
     *
     * We use a threshold-based approach to updates, with a slack
     * threshold of two -- that is, we update head/tail when the
     * current pointer appears to be two or more steps away from the
     * first/last node. The slack value is hard-wired: a path greater
     * than one is naturally implemented by checking equality of
     * traversal pointers except when the list has only one element,
     * in which case we keep slack threshold at one. Avoiding tracking
     * explicit counts across method calls slightly simplifies an
     * already-messy implementation. Using randomization would
     * probably work better if there were a low-quality dirt-cheap
     * per-thread one available, but even ThreadLocalRandom is too
     * heavy for these purposes.
     *
     * 我们使用基于阈值的方法进行更新，松弛阈值为2——也就是说，
     * 当当前指针看起来距离第一个/最后一个节点有两步或更多步时，我们更新头/尾。
     * 松弛值是硬连线的：大于1的路径自然是通过检查遍历指针的相等性来实现的，除非列表只有一个元素，
     * 在这种情况下，我们将松弛阈值保持为1。
     * 避免在方法调用之间跟踪显式计数稍微简化了一个已经很混乱的实现。
     * 如果每个线程都有一个低质量、低成本的线程，那么使用随机化可能会更好，但对于这些目的来说，甚至ThreadLocalRandom也太重了。
     *
     * With such a small slack threshold value, it is not worthwhile
     * to augment this with path short-circuiting (i.e., unsplicing
     * interior nodes) except in the case of cancellation/removal (see
     * below).
     *
     * 对于如此小的松弛阈值，除了在取消/移除的情况下（见下文），不值得通过路径短路（即，未拼接的内部节点）来增强这一点。
     *
     * We allow both the head and tail fields to be null before any
     * nodes are enqueued; initializing upon first append.  This
     * simplifies some other logic, as well as providing more
     * efficient explicit control paths instead of letting JVMs insert
     * implicit NullPointerExceptions when they are null.  While not
     * currently fully implemented, we also leave open the possibility
     * of re-nulling these fields when empty (which is complicated to
     * arrange, for little benefit.)
     *
     * 我们允许头和尾字段在任何节点排队之前都为空；
     * 在第一次附加时初始化。
     * 这简化了一些其他逻辑，并提供了更有效的显式控制路径，而不是让JVM在空时插入隐式NullPointerExceptions。
     * 虽然目前还没有完全实现，但我们也保留了在这些字段为空时重新置零的可能性（这很复杂，没有什么好处）
     *
     * All enqueue/dequeue operations are handled by the single method
     * "xfer" with parameters indicating whether to act as some form
     * of offer, put, poll, take, or transfer (each possibly with
     * timeout). The relative complexity of using one monolithic
     * method outweighs the code bulk and maintenance problems of
     * using separate methods for each case.
     *
     * 所有入队/出队操作都由一个方法“xfer”处理，参数指示是否作为某种形式的提供、放置、轮询、获取或传输（每个操作都可能有超时）。
     * 使用一个整体方法的相对复杂性超过了对每种情况使用单独方法的代码量和维护问题。
     *
     * Operation consists of up to three phases. The first is
     * implemented within method xfer, the second in tryAppend, and
     * the third in method awaitMatch.
     *
     * 操作最多包括三个阶段。第一个在xfer方法中实现，第二个在tryAppend中实现，而第三个在awaitMatch方法中实现。
     *
     * 1. Try to match an existing node
     *
     *    Starting at head, skip already-matched nodes until finding
     *    an unmatched node of opposite mode, if one exists, in which
     *    case matching it and returning, also if necessary updating
     *    head to one past the matched node (or the node itself if the
     *    list has no other unmatched nodes). If the CAS misses, then
     *    a loop retries advancing head by two steps until either
     *    success or the slack is at most two. By requiring that each
     *    attempt advances head by two (if applicable), we ensure that
     *    the slack does not grow without bound. Traversals also check
     *    if the initial head is now off-list, in which case they
     *    start at the new head.
     *
     *    If no candidates are found and the call was untimed
     *    poll/offer, (argument "how" is NOW) return.
     *
     * 从头部开始，跳过已经匹配的节点，直到找到相反模式的不匹配节点（如果存在），
     * 在这种情况下，匹配它并返回，如果需要，也可以将头部更新为超过匹配节点的节点（如果列表中没有其他不匹配的节点）。
     * 如果CAS未命中，则循环将尝试向前推进头部两步，直到成功或松弛最多为两步。
     * 通过要求每一次尝试向前推进两步（如果适用），我们确保了松弛不会毫无限制地增长。
     * 遍历还检查初始标头是否已从列表中删除，在这种情况下，它们从新标头开始。
     * 如果找不到候选人，且电话未计时，则返回（参数“how”是NOW）。
     *
     * 2. Try to append a new node (method tryAppend)
     *
     *    Starting at current tail pointer, find the actual last node
     *    and try to append a new node (or if head was null, establish
     *    the first node). Nodes can be appended only if their
     *    predecessors are either already matched or are of the same
     *    mode. If we detect otherwise, then a new node with opposite
     *    mode must have been appended during traversal, so we must
     *    restart at phase 1. The traversal and update steps are
     *    otherwise similar to phase 1: Retrying upon CAS misses and
     *    checking for staleness.  In particular, if a self-link is
     *    encountered, then we can safely jump to a node on the list
     *    by continuing the traversal at current head.
     *
     *    On successful append, if the call was ASYNC, return.
     *
     * 从当前尾部指针开始，找到实际的最后一个节点，并尝试附加一个新节点（或者如果头为空，则建立第一个节点）。
     * 只有当节点的前一个节点已经匹配或处于相同模式时，才能附加节点。
     * 如果检测到其他情况，则必须在遍历过程中附加具有相反模式的新节点，因此必须在第1阶段重新启动。
     * 遍历和更新步骤与第1阶段类似：在CAS未命中时重试并检查过时性。
     * 特别是，如果遇到自链接，那么我们可以通过在当前头部继续遍历来安全地跳转到列表上的节点。
     *
     * 3. Await match or cancellation (method awaitMatch)
     *
     *    Wait for another thread to match node; instead cancelling if
     *    the current thread was interrupted or the wait timed out. On
     *    multiprocessors, we use front-of-queue spinning: If a node
     *    appears to be the first unmatched node in the queue, it
     *    spins a bit before blocking. In either case, before blocking
     *    it tries to unsplice any nodes between the current "head"
     *    and the first unmatched node.
     *
     * 等待另一个线程匹配节点；如果当前线程被中断或等待超时，则取消。
     * 在多处理器上，我们使用队列前旋转：如果一个节点看起来是队列中第一个不匹配的节点，
     * 它会在阻塞之前旋转一点。在任何一种情况下，在阻塞之前，它都会尝试解开当前“头部”和第一个不匹配节点之间的任何节点。
     *
     *    Front-of-queue spinning vastly improves performance of
     *    heavily contended queues. And so long as it is relatively
     *    brief and "quiet", spinning does not much impact performance
     *    of less-contended queues.  During spins threads check their
     *    interrupt status and generate a thread-local random number
     *    to decide to occasionally perform a Thread.yield. While
     *    yield has underdefined specs, we assume that it might help,
     *    and will not hurt, in limiting impact of spinning on busy
     *    systems.  We also use smaller (1/2) spins for nodes that are
     *    not known to be front but whose predecessors have not
     *    blocked -- these "chained" spins avoid artifacts of
     *    front-of-queue rules which otherwise lead to alternating
     *    nodes spinning vs blocking. Further, front threads that
     *    represent phase changes (from data to request node or vice
     *    versa) compared to their predecessors receive additional
     *    chained spins, reflecting longer paths typically required to
     *    unblock threads during phase changes.
     *
     * 队列前端旋转大大提高了严重争用队列的性能。
     * 只要它相对简短和“安静”，旋转不会对竞争较少的队列的性能产生太大影响。
     * 在旋转期间，线程检查其中断状态并生成线程本地随机数，以决定偶尔执行thread.yield。
     * 虽然成品率未定义规格，但我们认为这可能有助于限制旋转对繁忙系统的影响，但不会造成损害。
     * 我们还使用较小的（1/2）旋转来处理那些不知道是前端但其前一个未被阻止的节点——这些“链式”旋转避免了队列前端规则的伪影，否则会导致节点旋转与阻塞交替。
     * 此外，与前一个线程相比，表示相位变化（从数据到请求节点，反之亦然）的前线程会接收额外的链式旋转，这反映了在相位变化期间通常需要更长的路径来解锁线程。
     *
     *
     * ** Unlinking removed interior nodes **
     *
     * In addition to minimizing garbage retention via self-linking
     * described above, we also unlink removed interior nodes. These
     * may arise due to timed out or interrupted waits, or calls to
     * remove(x) or Iterator.remove.  Normally, given a node that was
     * at one time known to be the predecessor of some node s that is
     * to be removed, we can unsplice s by CASing the next field of
     * its predecessor if it still points to s (otherwise s must
     * already have been removed or is now offlist). But there are two
     * situations in which we cannot guarantee to make node s
     * unreachable in this way: (1) If s is the trailing node of list
     * (i.e., with null next), then it is pinned as the target node
     * for appends, so can only be removed later after other nodes are
     * appended. (2) We cannot necessarily unlink s given a
     * predecessor node that is matched (including the case of being
     * cancelled): the predecessor may already be unspliced, in which
     * case some previous reachable node may still point to s.
     * (For further explanation see Herlihy & Shavit "The Art of
     * Multiprocessor Programming" chapter 9).  Although, in both
     * cases, we can rule out the need for further action if either s
     * or its predecessor are (or can be made to be) at, or fall off
     * from, the head of list.
     *
     * 除了通过上述自链接最小化垃圾保留，我们还取消了删除的内部节点的链接。
     * 这些可能是由于超时或中断的等待，或调用remove（x）或Iterator.remove而导致的。
     * 通常，给定一个节点曾经是要删除的某些节点的前身，如果它仍然指向s，
     * 我们可以通过CASing其前身的下一个字段来解开s（否则s必须已经被删除或现在处于脱机状态）。
     * 但有两种情况下，我们无法保证以这种方式使节点s不可访问：
     * （1）如果s是列表的尾部节点（即，下一个为空），那么它将被固定为附加的目标节点，因此只能在附加其他节点之后再移除。
     * （2） 给定匹配的前置节点（包括被取消的情况），我们不一定要取消链接：前置节点可能已经被取消了，在这种情况下，某个先前可到达的节点可能仍然指向s。
     * 尽管，在这两种情况下，如果s或其前身处于（或可以被设定为）榜首或从榜首跌落，我们可以排除采取进一步行动的必要性。
     *
     * Without taking these into account, it would be possible for an
     * unbounded number of supposedly removed nodes to remain
     * reachable.  Situations leading to such buildup are uncommon but
     * can occur in practice; for example when a series of short timed
     * calls to poll repeatedly time out but never otherwise fall off
     * the list because of an untimed call to take at the front of the
     * queue.
     *
     * 如果不考虑这些因素，可能会有无限数量的假定已删除的节点保持可访问。
     * 导致这种堆积的情况并不常见，但在实践中可能会发生；
     * 例如，当一系列用于轮询的短时间呼叫重复超时，但由于队列前面有一个未计时的呼叫而从未从列表中消失时。
     *
     * When these cases arise, rather than always retraversing the
     * entire list to find an actual predecessor to unlink (which
     * won't help for case (1) anyway), we record a conservative
     * estimate of possible unsplice failures (in "sweepVotes").
     * We trigger a full sweep when the estimate exceeds a threshold
     * ("SWEEP_THRESHOLD") indicating the maximum number of estimated
     * removal failures to tolerate before sweeping through, unlinking
     * cancelled nodes that were not unlinked upon initial removal.
     * We perform sweeps by the thread hitting threshold (rather than
     * background threads or by spreading work to other threads)
     * because in the main contexts in which removal occurs, the
     * caller is already timed-out, cancelled, or performing a
     * potentially O(n) operation (e.g. remove(x)), none of which are
     * time-critical enough to warrant the overhead that alternatives
     * would impose on other threads.
     *
     * 当出现这些情况时，我们不是总是重新转换整个列表以找到要取消链接的实际前任（这对情况（1）无论如何都没有帮助），
     * 而是记录可能的未复制失败的保守估计（在“sweepVotes”中）。当估计值超过阈值（“sweep_THRESHOD”）时，
     * 我们触发完全扫描，该阈值指示在扫描之前要容忍的估计删除失败的最大数量，并取消初始删除时未取消链接的已取消节点的链接。
     * 我们根据线程命中阈值执行扫描（而不是后台线程或通过将工作扩展到其他线程），
     * 因为在移除发生的主要上下文中，调用方已经超时、取消或执行了可能的O（n）操作（例如，移除（x）），
     * 这些操作都不是时间关键的，足以保证替代方案会对其他线程造成的开销。
     *
     * Because the sweepVotes estimate is conservative, and because
     * nodes become unlinked "naturally" as they fall off the head of
     * the queue, and because we allow votes to accumulate even while
     * sweeps are in progress, there are typically significantly fewer
     * such nodes than estimated.  Choice of a threshold value
     * balances the likelihood of wasted effort and contention, versus
     * providing a worst-case bound on retention of interior nodes in
     * quiescent queues. The value defined below was chosen
     * empirically to balance these under various timeout scenarios.
     *
     * 由于sweepVotes的估计是保守的，并且由于节点从队列的头部脱落时会“自然”地断开链接，
     * 并且由于我们允许在扫描进行时累积投票，因此此类节点通常比估计的要少得多。
     * 阈值的选择平衡了浪费精力和争用的可能性，而不是在静态队列中保留内部节点时提供最坏情况限制。
     * 根据经验选择以下定义的值，以在各种超时情况下平衡这些值。
     *
     * Note that we cannot self-link unlinked interior nodes during
     * sweeps. However, the associated garbage chains terminate when
     * some successor ultimately falls off the head of the list and is
     * self-linked.
     * 请注意，我们无法在扫描期间自链接未链接的内部节点。然而，当某个继承者最终从列表的头上掉下来并自链接时，关联的垃圾链就会终止。
     */

    /** True if on multiprocessor */
    // true 表示当前机器是多核心处理器
    private static final boolean MP =
        Runtime.getRuntime().availableProcessors() > 1;

    /**
     * The number of times to spin (with randomly interspersed calls
     * to Thread.yield) on multiprocessor before blocking when a node
     * is apparently the first waiter in the queue.  See above for
     * explanation. Must be a power of two. The value is empirically
     * derived -- it works pretty well across a variety of processors,
     * numbers of CPUs, and OSes.
     */
    /*
     * 多核心的处理器时，当一个节点是队列的第一个 waiter，在阻塞前需要自旋一定的次数，随机的调用 Thread.yield
     * 必须是 2 的 n 次幂，
     * FRONT_SPINS 是一个经验值，它在各种处理器、大量CPU和操作系统上都能很好地工作。
     */
    private static final int FRONT_SPINS   = 1 << 7;

    /**
     * The number of times to spin before blocking when a node is
     * preceded by another node that is apparently spinning.  Also
     * serves as an increment to FRONT_SPINS on phase changes, and as
     * base average frequency for yielding during spins. Must be a
     * power of two.
     */
    /*
     * 当一个节点前面有另一个明显正在旋转的节点时，在阻塞之前自旋的次数。
     * 还可作为相位变化时 FRONT_SPINS 的增量，并作为旋转期间 yielding 的基本平均频率。
     * 必须是 2 的 n 次幂
     */
    private static final int CHAINED_SPINS = FRONT_SPINS >>> 1;

    /**
     * The maximum number of estimated removal failures (sweepVotes)
     * to tolerate before sweeping through the queue unlinking
     * cancelled nodes that were not unlinked upon initial
     * removal. See above for explanation. The value must be at least
     * two to avoid useless sweeps when removing trailing nodes.
     */
    /*
     * 在扫过队列之前要容忍的最大估计删除失败数（sweepVotes），
     * 取消了初始删除时未取消链接的已取消节点的链接。
     * 请参见上面的解释。该值必须至少为2，以避免在删除尾随节点时进行无用的扫描。
     */
    static final int SWEEP_THRESHOLD = 32;

    /**
     * Queue nodes. Uses Object, not E, for items to allow forgetting
     * them after use.  Relies heavily on Unsafe mechanics to minimize
     * unnecessary ordering constraints: Writes that are intrinsically
     * ordered wrt other accesses or CASes use simple relaxed forms.
     */
    /*
     * 队列的节点，使用 Object 而不是泛型 E，
     * 严重依赖于 Unsafe 机制来最小化不必要的排序约束：对其他访问或CAS进行内在排序的写入使用简单的宽松形式。
     */
    static final class Node {
        // false 表示请求 Node，就是消费者啦，REQUEST
        // true 表示生产者，DATA
        final boolean isData;   // false if this is a request node
        // 生产者的数据，假如是消费者那就是匹配到的数据
        volatile Object item;   // initially non-null if isData; CASed to match
        // 节点的后驱
        volatile Node next;
        // 不为 null 时，表示阻塞的线程
        volatile Thread waiter; // null until waiting

        // CAS methods for fields
        // 将当前节点的 next 指针由 cmp 设置为 val
        final boolean casNext(Node cmp, Node val) {
            return UNSAFE.compareAndSwapObject(this, nextOffset, cmp, val);
        }

        // 将当前 item 的值由 cmp 设置为 val
        final boolean casItem(Object cmp, Object val) {
            // assert cmp == null || cmp.getClass() != Node.class;
            return UNSAFE.compareAndSwapObject(this, itemOffset, cmp, val);
        }

        /**
         * Constructs a new node.  Uses relaxed write because item can
         * only be seen after publication via casNext.
         */
        Node(Object item, boolean isData) {
            // 设置 item 值
            UNSAFE.putObject(this, itemOffset, item); // relaxed write
            this.isData = isData;
        }

        /**
         * Links node to itself to avoid garbage retention.  Called
         * only after CASing head field, so uses relaxed write.
         */
        // 将 next 指针指向自己，也就是从队列中移除该节点，避免垃圾滞留，仅在 CAS head字段后调用
        final void forgetNext() {
            UNSAFE.putObject(this, nextOffset, this);
        }

        /**
         * Sets item to self and waiter to null, to avoid garbage
         * retention after matching or cancelling. Uses relaxed writes
         * because order is already constrained in the only calling
         * contexts: item is forgotten only after volatile/atomic
         * mechanics that extract items.  Similarly, clearing waiter
         * follows either CAS or return from park (if ever parked;
         * else we don't care).
         */
        /*
         * 将 item 指向自己，并把 waiter 设置为 null，这样做是为了避免在匹配完成或者取消后的垃圾滞留
         * 仅仅在某个节点被匹配或者被取消之后调用
         */
        final void forgetContents() {
            UNSAFE.putObject(this, itemOffset, this);
            UNSAFE.putObject(this, waiterOffset, null);
        }

        /**
         * Returns true if this node has been matched, including the
         * case of artificial matches due to cancellation.
         */
        // 校验是否匹配
        // isMatched 是位于 Node 中的方法，如果调用结点被匹配或者被取消，那么返回 true，否则返回 false。
        // 返回 true 表示当前节点已经匹配成功了或者被取消了。
        final boolean isMatched() {
            Object x = item;
            // x == this 取消了
            // (x == null) == isData 已经匹配完成了
            return (x == this) || ((x == null) == isData);
        }

        /**
         * Returns true if this is an unmatched request node.
         */
        // 返回 true 表示当前节点是未匹配成功的 RequestNode，消费节点
        final boolean isUnmatchedRequest() {
            return !isData && item == null;
        }

        /**
         * Returns true if a node with the given mode cannot be
         * appended to this node because this node is unmatched and
         * has opposite data mode.
         */
        /*
         * 如果具有给定模式的节点无法附加到此节点，因为此节点不匹配且具有相反的数据模式，则返回true。
         *
         * 返回 true 表示不能
         */
        final boolean cannotPrecede(boolean haveData) {
            boolean d = isData;
            Object x;
            /*
             * d != haveData        表示要追加的节点和当前节点的模式不一样。request 和 data
             * (x = item) != this   item != this 表示当前节点未匹配
             * (x != null) == d     item != null 说明未匹配
             */
            return d != haveData && (x = item) != this && (x != null) == d;
        }

        /**
         * Tries to artificially match a data node -- used by remove.
         */
        // 尝试人工匹配一个节点
        final boolean tryMatchData() {
            // assert isData;
            Object x = item;
            if (x != null && x != this && casItem(x, null)) {
                LockSupport.unpark(waiter);
                return true;
            }
            return false;
        }

        private static final long serialVersionUID = -3375979862319811754L;

        // Unsafe mechanics
        private static final sun.misc.Unsafe UNSAFE;
        private static final long itemOffset;
        private static final long nextOffset;
        private static final long waiterOffset;
        static {
            try {
                UNSAFE = sun.misc.Unsafe.getUnsafe();
                Class<?> k = Node.class;
                itemOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("item"));
                nextOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("next"));
                waiterOffset = UNSAFE.objectFieldOffset
                    (k.getDeclaredField("waiter"));
            } catch (Exception e) {
                throw new Error(e);
            }
        }
    }

    /** head of the queue; null until first enqueue */
    // 指向队列头节点的指针，在第一次入队前是 null
    transient volatile Node head;

    /** tail of the queue; null until first append */
    // 指向队列尾结点的指针，在第一次入队前是 null
    private transient volatile Node tail;

    /** The number of apparent failures to unsplice removed nodes */
    private transient volatile int sweepVotes;

    // CAS methods for fields
    private boolean casTail(Node cmp, Node val) {
        return UNSAFE.compareAndSwapObject(this, tailOffset, cmp, val);
    }

    private boolean casHead(Node cmp, Node val) {
        return UNSAFE.compareAndSwapObject(this, headOffset, cmp, val);
    }

    private boolean casSweepVotes(int cmp, int val) {
        return UNSAFE.compareAndSwapInt(this, sweepVotesOffset, cmp, val);
    }

    /*
     * Possible values for "how" argument in xfer method.
     */
    // 立刻操作，不会阻塞线程
    private static final int NOW   = 0; // for untimed poll, tryTransfer
    // 异步，不会阻塞线程
    private static final int ASYNC = 1; // for offer, put, add
    // 同步操作，会阻塞线程
    private static final int SYNC  = 2; // for transfer, take
    // 同步操作，会阻塞线程，指定超时时间
    private static final int TIMED = 3; // for timed poll, tryTransfer

    @SuppressWarnings("unchecked")
    static <E> E cast(Object item) {
        // assert item == null || item.getClass() != Node.class;
        return (E) item;
    }

    /**
     * Implements all queuing methods. See above for explanation.
     *
     * @param e the item or null for take
     * @param haveData true if this is a put, else a take
     * @param how NOW, ASYNC, SYNC, or TIMED
     * @param nanos timeout in nanosecs, used only if mode is TIMED
     * @return an item if matched, else e
     * @throws NullPointerException if haveData mode but e is null
     */
    /*
     * 实现了所有排队的方法
     *
     * 在循环中遍历队列，主动查找并且尝试匹配对应的结点，匹配成功之后即可返回，
     * 这一步中，不同的方法根据isData属性去识别可能匹配的结点，
     * 只有不同的isData请求才能匹配，即只有入队、传递方法和出队方法匹配；
     *
     * 如果没有找到能够匹配的结点，不同的方法根据自己传入的how参数走不同的逻辑，
     * 可能会直接返回（NOW），或者添加结点再返回（ASYNC），或者等待被后来的请求匹配（AYNC、TIMED），
     * 直到被匹配成功或者取消等待或者超时，随后返回！
     *
     * 如果该请求是入队、传递方法，那么应该返回null，如果是出队方法，则返回的值就代表出队的元素。
     *
     * 匹配成功之后，被匹配的结点不一定会出队，head的指向不一定会改变。
     * 如果匹配的结点就是head结点，那么什么都不管，否则将会尝试出队操作，但是也不一定，
     * 如果发生了CAS修改head 的冲突，那么会判断如果结点数量小于2，或者被匹配和取消的结点数量小于2，
     * 同样也直接返回。这个2就是“松弛度”，实际上不必每次匹配成功都要求CAS的更新head的指向，
     * 这样减少了线程竞争，提升并发效率，而如果松弛度大于等于2，则可能会增加查找没有被匹配的结点的时间，
     * 这个松弛度的取值是一个“经验值"，详细解释见源码。
     *
     * @param e         入队时 item 表示入队的数据，出队时 null
     * @param haveData  true 表示入队，false 表示出队
     * @param how       模式,NOW, ASYNC, SYNC, TIMED。
     * @param nanos     纳秒的超时时间，仅在 TIMED 模式使用
     * @return
     */
    private E xfer(E e, boolean haveData, int how, long nanos) {
        if (haveData && (e == null))          // 假如有 DATA，但是没有元素值，则抛出空指针
            throw new NullPointerException();
        Node s = null;                        // the node to append, if needed

        // 外部自旋
        retry:
        for (;;) {                            // restart on append race

            // 从 head 遍历找到一个可匹配的节点。假如没找到，说明队列中的元素都是当前 xfer 的模式，那就入队
            // p 是遍历链表的指针
            for (Node h = head, p = h; p != null;) { // find & match first node
                boolean isData = p.isData;
                Object item = p.item;
                /*
                 * item != p 表示 p 节点尚未取消，也没有匹配（或者匹配的流程还没走完），
                 * 因为 p 在匹配完成或者取消后会将它的 item 指向自己
                 * (item != null) == isData， 表示这个节点还未匹配
                 * CASE1:这个就是判断 p 是否已经匹配了,true 表示未匹配
                 */
                if (item != p && (item != null) == isData) { // unmatched
                    if (isData == haveData)   // can't match
                        // 模式相同，无法匹配，当前操作需要去入队
                        break;
                    /*
                     * CAS 将 p 的 item 改为当前 xfer 的 e，交换数据
                     * 如果CAS失败，那么表示匹配失败，可能是被取消了，或者被其他请求抢先匹配了
                     */
                    if (p.casItem(item, e)) { // match
                        /*
                         * 走到这里说明匹配成功，需要做匹配后的操作
                         * 这个循环的操作是尝试查找并移除已经被匹配/取消的节点，以及更新 head 引用的指向
                         * 假如队列中只有一个元素，那么就不更新 head 指针，否则每次更新 head 向后移两位
                         * 只有在更新head 指针成功后才去移除已经匹配的节点，这就是"松弛度"
                         */
                        for (Node q = p; q != h;) {
                            Node n = q.next;  // update by 2 unless singleton
                            /*
                             * 假如 head 还是指向 h，就是说没有被别的线程修改，此时需要将 head 改为指向 q 或者 n
                             * 移除元素，三元运算是为了判断移除一个还是两个，一般是移除两个
                             */
                            if (head == h && casHead(h, n == null ? q : n)) {
                                h.forgetNext(); // 原来的 head 可以出队了
                                break;
                            }                 // advance and retry
                            /*
                             * 到这里说明上面的 head != null，或者 cas 失败了，也就是 head 指针被别的线程给改了
                             * 有可能此时h被其他请求移除了，有可能此时h和p都被其他请求帮助移除了
                             * 此时 h 指向最新的 head，q 是 head.next
                             * h == null 新的头是 null
                             * h != null && q == null 说明就一个元素
                             * h != null && q != null && !q.isMatched() 说明 head.next 节点未匹配
                             */
                            if ((h = head)   == null ||
                                (q = h.next) == null || !q.isMatched())
                                // 即如果结点数量小于2，或者被匹配和取消的结点数量小于2，那么break跳出第二层内循环
                                // 继续循环的条件那就是节点的数量不小于 2，至少有两个元素已经匹配了，那么需要继续循环移除元素
                                break;        // unless slack < 2
                        }
                        // 走出上面的 for 循环 说明"松弛度"已经控制好了
                        // 唤醒可能阻塞的线程
                        LockSupport.unpark(p.waiter);
                        // 这是所有的入队、传递、出队方法在主动匹配成功之后的共同唯一出口
                        return LinkedTransferQueue.<E>cast(item);
                    }
                }
                // 当前节点匹配失败，遍历链表继续后移查找
                Node n = p.next;
                // 如果 p 已经出队则使用 head 再去遍历
                p = (p != n) ? n : (h = head); // Use head if p offlist
            }

            /*
             * 可能是队列为空，也可能是遍历完队列后还是没有可以匹配的节点，做入队操作
             * 假如是 NOW 模式则立即返回，此时数据也丢了，并没有入队，直接返回
             * 假如不是 NOW 模式则需要创建节点入队了
             */
            if (how != NOW) {                 // No matches available
                // 创建一个新的 Node
                if (s == null)
                    s = new Node(e, haveData);
                /*
                 * 调用 tryAppend 方法尝试将 s 节点追加到队列尾部，返回值如下：
                 * 1、null，追加失败
                 * 2、s 的前驱
                 * 3、this 自身，表示 s 没有前驱
                 */
                Node pred = tryAppend(s, haveData);
                if (pred == null)
                    continue retry;           // lost race vs opposite mode， 竞争失败重新自旋重试
                // 到这里说明追加节点成功
                if (how != ASYNC) // ASYNC 模式不走下面的等待方法，直接下面 return 了
                    /*
                     * 不是异步的情况，就要去等待了
                     * 在awaitMatch方法中等待直到被后续的请求匹配或者被取消
                     * 1.如果匹配成功，返回匹配的请求的 item 值；
                     * 2.假如因为超时或者中断被取消等待，返回当前节点的 e（item）
                     */
                    return awaitMatch(s, pred, e, (how == TIMED), nanos);
            }
            return e; // not waiting
        }
    }

    /**
     * Tries to append node s as tail.
     *
     * @param s the node to append
     * @param haveData true if appending in data mode
     * @return null on failure due to losing race with append in
     * different mode, else s's predecessor, or s itself if no
     * predecessor
     */
    /*
     * 尝试追加 node，作为队尾，并更新 tail 引用的指向
     * @param s
     * @param haveData
     * @return          null，表示因为在不同模式的竞争下失败
     *                  s 的前驱
     *                  this 自身，表示 s 没有前驱
     */
    private Node tryAppend(Node s, boolean haveData) {
        // 自旋，初始值 t=p=tail
        for (Node t = tail, p = t;;) {        // move p to last node and append
            // n保存临时后继，u保存临时tail
            Node n, u;                        // temps for reads of next & tail
            // CASE1: 条件成立，说明队列尚未初始化
            // 如果 p == null，那么说明 尾结点的引用是 null，或者被移除出队了，此时将 p 指针指向 head，表示从头开始查找
            if (p == null && (p = head) == null) {
                // 将 s 设置为 head 节点
                if (casHead(null, s))
                    return s;                 // initialize
            }
            // 走到这里说明 p 不是 null
            // CASE2: 校验 p 节点能否作为 s 节点的前驱
            else if (p.cannotPrecede(haveData))
                // 如果不能追加，那么返回null
                return null;                  // lost race vs opposite mode
            // 获取p的后继n，如果n不为null，说明此时又新追加了结点到队列，
            // 或者tail不是真正的队尾，或者还没有遍历完整个队列（重头开始的情况），需要向后推进
            // CASE3: 判断 p 的 next 节点是否存在
            else if ((n = p.next) != null)    // not last; keep traversing
                /*
                 * 如果p不等于t（最开始队列只有一个结点或者循环了超过一次），u设置为tail，并且t不等于u，表明最新tail有变，那么t设置为u，同时p设置为t，相当于重新重tail开始循环
                 * 否则，如果p不等于n，即p没有出队列，那么p设置为n，向后推进继续下一次循环；否则表示p被移除了队列，p设置为null，相当于重新重head开始循环
                 */
                p = p != t && t != (u = tail) ? (t = u) : // stale tail
                    (p != n) ? n : null;      // restart if off list
            // CASE4: 尝试将 p 的 next 指针由 null 改为 s，假如失败了，就 p = p.next，就是入队
            /*
             * 到这里，p的后继为null，作为最后一个结点，开始追加，尝试CAS的将p的后继从null设置为s
             * 返回true表示成功，返回false表示失败
             */
            else if (!p.casNext(null, s))
                p = p.next;                   // re-read on CAS failure
            /*如果CAS成功，那么尝试改变tail的指向*/
            // CASE5: 处理松弛操作
            else {
                /*
                 * 如果p不等于t，表示循环了不止一次，此时tail可能并没有指向真正的尾结点，那么尝试更新tail
                 * 这里就像上面的匹配成功之后改变head引用指向的情况，并不是每一次添加了结点都需要改变tail的引用指向，
                 * 如果添加的结点就位于tail之后，那么就不需要改变tail的指向
                 *
                 * 根据head和tail的特性：head可能不是真正的队列头，tail可能不是真正的不是队尾，据此我们可以想象到一下情况:
                 * head指向一个结点，tail指向null；head和tail都指向同一个结点；head和tail指向不同的结点；tail指向的结点甚至是head指向的结点的前驱…………还有很多
                 */
                if (p != t) {                 // update if slack now >= 2
                    //如果tail此时不等于t，或者tail等于t，但是尝试CAS的将tail指向s失败
                    //那么 t指向tail，如果t不为null
                    //那么 s指向t的后继，如果s不为null
                    //那么 s指向s的后继，如果s不为null
                    //那么 如果s不等于t
                    //以上情况都满足，将会一直循环，直到：本线程CAS改变tail成功，或者tail后面的结点小于等于两个，或者tail被移除队列了
                    // 有两个 next，表示松弛度两个，在松弛度大于等于 2 的时候更新 tail
                    while ((tail != t || !casTail(t, s)) &&
                           (t = tail)   != null &&
                           (s = t.next) != null && // advance and retry
                           (s = s.next) != null && s != t);
                }
                return p;
            }
        }
    }

    /**
     * Spins/yields/blocks until node s is matched or caller gives up.
     *
     * @param s the waiting node
     * @param pred the predecessor of s, or s itself if it has no
     * predecessor, or null if unknown (the null case does not occur
     * in any current calls but may in possible future extensions)
     * @param e the comparison value for checking match
     * @param timed if true, wait only until timeout elapses
     * @param nanos timeout in nanosecs, used only if timed is true
     * @return matched item, or e if unmatched on interrupt or timeout
     */
    /*
     * Spins/yields/blocks
     * 自旋/让步/阻塞 直到节点匹配到了数据，或者调用方放弃了
     *
     * @param s     等待匹配的节点
     * @param pred  s 的前驱，如果是 s 自身表示 s 没有前驱，  如果未知，则为null（null情况不会出现在任何当前调用中，但可能出现在未来的扩展中）
     * @param e     用于检查匹配的比较值
     * @param timed 是否支持超时
     * @param nanos 超时时间
     * @return
     */
    private E awaitMatch(Node s, Node pred, E e, boolean timed, long nanos) {
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        Thread w = Thread.currentThread();
        int spins = -1; // initialized after first item and cancel checks
        ThreadLocalRandom randomYields = null; // bound if needed

        // 自旋，等待被匹配或者取消 Spins/yields/blocks
        for (;;) {
            Object item = s.item;
            // item != e，说明 item 已经和对方交换了
            if (item != e) {                  // matched
                // assert item != s;
                // 表示匹配完成
                s.forgetContents();           // avoid garbage
                return LinkedTransferQueue.<E>cast(item);
            }
            // (被中断 || 超时) && (s 的 item 指向自己)
            if ((w.isInterrupted() || (timed && nanos <= 0))
                    && s.casItem(e, s)) {        // cancel
                // 取消节点移除
                unsplice(pred, s);
                return e;
            }

            if (spins < 0) {                  // establish spins at/near front
                // 获取自旋次数
                if ((spins = spinsFor(pred, s.isData)) > 0)
                    randomYields = ThreadLocalRandom.current();
            }
            else if (spins > 0) {             // spin
                // 自旋次数减 1
                --spins;
                if (randomYields.nextInt(CHAINED_SPINS) == 0)
                    Thread.yield();           // occasionally yield
            }
            else if (s.waiter == null) {
                s.waiter = w;                 // request unpark then recheck
            }
            else if (timed) {
                nanos = deadline - System.nanoTime();
                if (nanos > 0L)
                    // 计时等待
                    LockSupport.parkNanos(this, nanos);
            }
            else {
                // 无限等待
                LockSupport.park(this);
            }
        }
    }

    /**
     * Returns spin/yield value for a node with given predecessor and
     * data mode. See above for explanation.
     */
    private static int spinsFor(Node pred, boolean haveData) {
        // MP 为 true 表示是多核处理器
        if (MP && pred != null) {
            // 如果 pred 的 isData 属性和当前请求的 hashData 属性不一致，且前驱结点被匹配或者取消
            if (pred.isData != haveData)      // phase change
                return FRONT_SPINS + CHAINED_SPINS;
            // 如果前驱结点的isData属性和当前请求的hashData属性一致，并且前驱结点被匹配或者取消
            if (pred.isMatched())             // probably at front
                return FRONT_SPINS;
            //到这里，表示前驱结点的isData属性和当前请求的hashData属性一致，并且前驱结点没有被匹配或者取消
            //如果前驱结点的waiter等于null
            if (pred.waiter == null)          // pred apparently spinning
                return CHAINED_SPINS;
        }
        return 0;
    }

    /* -------------- Traversal methods -------------- */

    /**
     * Returns the successor of p, or the head node if p.next has been
     * linked to self, which will only be true if traversing with a
     * stale pointer that is now off the list.
     */
    final Node succ(Node p) {
        Node next = p.next;
        return (p == next) ? head : next;
    }

    /**
     * Returns the first unmatched node of the given mode, or null if
     * none.  Used by methods isEmpty, hasWaitingConsumer.
     */
    private Node firstOfMode(boolean isData) {
        for (Node p = head; p != null; p = succ(p)) {
            if (!p.isMatched())
                return (p.isData == isData) ? p : null;
        }
        return null;
    }

    /**
     * Version of firstOfMode used by Spliterator. Callers must
     * recheck if the returned node's item field is null or
     * self-linked before using.
     */
    final Node firstDataNode() {
        for (Node p = head; p != null;) {
            Object item = p.item;
            if (p.isData) {
                if (item != null && item != p)
                    return p;
            }
            else if (item == null)
                break;
            if (p == (p = p.next))
                p = head;
        }
        return null;
    }

    /**
     * Returns the item in the first unmatched node with isData; or
     * null if none.  Used by peek.
     */
    // 返回第一个未匹配的节点
    private E firstDataItem() {
        for (Node p = head; p != null; p = succ(p)) {
            Object item = p.item;
            if (p.isData) {
                if (item != null && item != p)
                    return LinkedTransferQueue.<E>cast(item);
            }
            else if (item == null)
                return null;
        }
        return null;
    }

    /**
     * Traverses and counts unmatched nodes of the given mode.
     * Used by methods size and getWaitingConsumerCount.
     */
    private int countOfMode(boolean data) {
        int count = 0;
        for (Node p = head; p != null; ) {
            if (!p.isMatched()) {
                if (p.isData != data)
                    return 0;
                if (++count == Integer.MAX_VALUE) // saturated
                    break;
            }
            Node n = p.next;
            if (n != p)
                p = n;
            else {
                count = 0;
                p = head;
            }
        }
        return count;
    }

    final class Itr implements Iterator<E> {
        private Node nextNode;   // next node to return item for
        private E nextItem;      // the corresponding item
        private Node lastRet;    // last returned node, to support remove
        private Node lastPred;   // predecessor to unlink lastRet

        /**
         * Moves to next node after prev, or first node if prev null.
         */
        private void advance(Node prev) {
            /*
             * To track and avoid buildup of deleted nodes in the face
             * of calls to both Queue.remove and Itr.remove, we must
             * include variants of unsplice and sweep upon each
             * advance: Upon Itr.remove, we may need to catch up links
             * from lastPred, and upon other removes, we might need to
             * skip ahead from stale nodes and unsplice deleted ones
             * found while advancing.
             */

            Node r, b; // reset lastPred upon possible deletion of lastRet
            if ((r = lastRet) != null && !r.isMatched())
                lastPred = r;    // next lastPred is old lastRet
            else if ((b = lastPred) == null || b.isMatched())
                lastPred = null; // at start of list
            else {
                Node s, n;       // help with removal of lastPred.next
                while ((s = b.next) != null &&
                       s != b && s.isMatched() &&
                       (n = s.next) != null && n != s)
                    b.casNext(s, n);
            }

            this.lastRet = prev;

            for (Node p = prev, s, n;;) {
                s = (p == null) ? head : p.next;
                if (s == null)
                    break;
                else if (s == p) {
                    p = null;
                    continue;
                }
                Object item = s.item;
                if (s.isData) {
                    if (item != null && item != s) {
                        nextItem = LinkedTransferQueue.<E>cast(item);
                        nextNode = s;
                        return;
                    }
                }
                else if (item == null)
                    break;
                // assert s.isMatched();
                if (p == null)
                    p = s;
                else if ((n = s.next) == null)
                    break;
                else if (s == n)
                    p = null;
                else
                    p.casNext(s, n);
            }
            nextNode = null;
            nextItem = null;
        }

        Itr() {
            advance(null);
        }

        public final boolean hasNext() {
            return nextNode != null;
        }

        public final E next() {
            Node p = nextNode;
            if (p == null) throw new NoSuchElementException();
            E e = nextItem;
            advance(p);
            return e;
        }

        public final void remove() {
            final Node lastRet = this.lastRet;
            if (lastRet == null)
                throw new IllegalStateException();
            this.lastRet = null;
            if (lastRet.tryMatchData())
                unsplice(lastPred, lastRet);
        }
    }

    /** A customized variant of Spliterators.IteratorSpliterator */
    static final class LTQSpliterator<E> implements Spliterator<E> {
        static final int MAX_BATCH = 1 << 25;  // max batch array size;
        final LinkedTransferQueue<E> queue;
        Node current;    // current node; null until initialized
        int batch;          // batch size for splits
        boolean exhausted;  // true when no more nodes
        LTQSpliterator(LinkedTransferQueue<E> queue) {
            this.queue = queue;
        }

        public Spliterator<E> trySplit() {
            Node p;
            final LinkedTransferQueue<E> q = this.queue;
            int b = batch;
            int n = (b <= 0) ? 1 : (b >= MAX_BATCH) ? MAX_BATCH : b + 1;
            if (!exhausted &&
                ((p = current) != null || (p = q.firstDataNode()) != null) &&
                p.next != null) {
                Object[] a = new Object[n];
                int i = 0;
                do {
                    Object e = p.item;
                    if (e != p && (a[i] = e) != null)
                        ++i;
                    if (p == (p = p.next))
                        p = q.firstDataNode();
                } while (p != null && i < n && p.isData);
                if ((current = p) == null)
                    exhausted = true;
                if (i > 0) {
                    batch = i;
                    return Spliterators.spliterator
                        (a, 0, i, Spliterator.ORDERED | Spliterator.NONNULL |
                         Spliterator.CONCURRENT);
                }
            }
            return null;
        }

        @SuppressWarnings("unchecked")
        public void forEachRemaining(Consumer<? super E> action) {
            Node p;
            if (action == null) throw new NullPointerException();
            final LinkedTransferQueue<E> q = this.queue;
            if (!exhausted &&
                ((p = current) != null || (p = q.firstDataNode()) != null)) {
                exhausted = true;
                do {
                    Object e = p.item;
                    if (e != null && e != p)
                        action.accept((E)e);
                    if (p == (p = p.next))
                        p = q.firstDataNode();
                } while (p != null && p.isData);
            }
        }

        @SuppressWarnings("unchecked")
        public boolean tryAdvance(Consumer<? super E> action) {
            Node p;
            if (action == null) throw new NullPointerException();
            final LinkedTransferQueue<E> q = this.queue;
            if (!exhausted &&
                ((p = current) != null || (p = q.firstDataNode()) != null)) {
                Object e;
                do {
                    if ((e = p.item) == p)
                        e = null;
                    if (p == (p = p.next))
                        p = q.firstDataNode();
                } while (e == null && p != null && p.isData);
                if ((current = p) == null)
                    exhausted = true;
                if (e != null) {
                    action.accept((E)e);
                    return true;
                }
            }
            return false;
        }

        public long estimateSize() { return Long.MAX_VALUE; }

        public int characteristics() {
            return Spliterator.ORDERED | Spliterator.NONNULL |
                Spliterator.CONCURRENT;
        }
    }

    /**
     * Returns a {@link Spliterator} over the elements in this queue.
     *
     * <p>The returned spliterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * <p>The {@code Spliterator} reports {@link Spliterator#CONCURRENT},
     * {@link Spliterator#ORDERED}, and {@link Spliterator#NONNULL}.
     *
     * @implNote
     * The {@code Spliterator} implements {@code trySplit} to permit limited
     * parallelism.
     *
     * @return a {@code Spliterator} over the elements in this queue
     * @since 1.8
     */
    public Spliterator<E> spliterator() {
        return new LTQSpliterator<E>(this);
    }

    /* -------------- Removal methods -------------- */

    /**
     * Unsplices (now or later) the given deleted/cancelled node with
     * the given predecessor.
     *
     * @param pred a node that was at one time known to be the
     * predecessor of s, or null or s itself if s is/was at head
     * @param s the node to be unspliced
     */
    final void unsplice(Node pred, Node s) {
        s.forgetContents(); // forget unneeded fields
        /*
         * See above for rationale. Briefly: if pred still points to
         * s, try to unlink s.  If s cannot be unlinked, because it is
         * trailing node or pred might be unlinked, and neither pred
         * nor s are head or offlist, add to sweepVotes, and if enough
         * votes have accumulated, sweep.
         */
        if (pred != null && pred != s && pred.next == s) {
            Node n = s.next;
            if (n == null ||
                (n != s && pred.casNext(s, n) && pred.isMatched())) {
                for (;;) {               // check if at, or could be, head
                    Node h = head;
                    if (h == pred || h == s || h == null)
                        return;          // at head or list empty
                    if (!h.isMatched())
                        break;
                    Node hn = h.next;
                    if (hn == null)
                        return;          // now empty
                    if (hn != h && casHead(h, hn))
                        h.forgetNext();  // advance head
                }
                if (pred.next != pred && s.next != s) { // recheck if offlist
                    for (;;) {           // sweep now if enough votes
                        int v = sweepVotes;
                        if (v < SWEEP_THRESHOLD) {
                            if (casSweepVotes(v, v + 1))
                                break;
                        }
                        else if (casSweepVotes(v, 0)) {
                            sweep();
                            break;
                        }
                    }
                }
            }
        }
    }

    /**
     * Unlinks matched (typically cancelled) nodes encountered in a
     * traversal from head.
     */
    private void sweep() {
        for (Node p = head, s, n; p != null && (s = p.next) != null; ) {
            if (!s.isMatched())
                // Unmatched nodes are never self-linked
                p = s;
            else if ((n = s.next) == null) // trailing node is pinned
                break;
            else if (s == n)    // stale
                // No need to also check for p == s, since that implies s == n
                p = head;
            else
                p.casNext(s, n);
        }
    }

    /**
     * Main implementation of remove(Object)
     */
    private boolean findAndRemove(Object e) {
        if (e != null) {
            for (Node pred = null, p = head; p != null; ) {
                Object item = p.item;
                if (p.isData) {
                    if (item != null && item != p && e.equals(item) &&
                        p.tryMatchData()) {
                        unsplice(pred, p);
                        return true;
                    }
                }
                else if (item == null)
                    break;
                pred = p;
                if ((p = p.next) == pred) { // stale
                    pred = null;
                    p = head;
                }
            }
        }
        return false;
    }

    /**
     * Creates an initially empty {@code LinkedTransferQueue}.
     */
    public LinkedTransferQueue() {
    }

    /**
     * Creates a {@code LinkedTransferQueue}
     * initially containing the elements of the given collection,
     * added in traversal order of the collection's iterator.
     *
     * @param c the collection of elements to initially contain
     * @throws NullPointerException if the specified collection or any
     *         of its elements are null
     */
    public LinkedTransferQueue(Collection<? extends E> c) {
        this();
        addAll(c);
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never block.
     *
     * @throws NullPointerException if the specified element is null
     */
    /*
     * 插入元素到队列尾部
     * 因为是无界队列，所以该操作用于不会阻塞
     */
    public void put(E e) {
        xfer(e, true, ASYNC, 0);
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never block or
     * return {@code false}.
     *
     * @return {@code true} (as specified by
     *  {@link java.util.concurrent.BlockingQueue#offer(Object,long,TimeUnit)
     *  BlockingQueue.offer})
     * @throws NullPointerException if the specified element is null
     */
    /*
     * 插入元素到队列尾部
     * 因为是无界队列，所以该操作用于不会阻塞
     */
    public boolean offer(E e, long timeout, TimeUnit unit) {
        xfer(e, true, ASYNC, 0);
        return true;
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never return {@code false}.
     *
     * @return {@code true} (as specified by {@link Queue#offer})
     * @throws NullPointerException if the specified element is null
     */
    /*
     * 插入元素到队列尾部
     * 因为是无界队列，所以该操作用于不会阻塞
     */
    public boolean offer(E e) {
        xfer(e, true, ASYNC, 0);
        return true;
    }

    /**
     * Inserts the specified element at the tail of this queue.
     * As the queue is unbounded, this method will never throw
     * {@link IllegalStateException} or return {@code false}.
     *
     * @return {@code true} (as specified by {@link Collection#add})
     * @throws NullPointerException if the specified element is null
     */
    /*
     * 插入元素到队列尾部
     * 因为是无界队列，所以该操作用于不会阻塞
     */
    public boolean add(E e) {
        xfer(e, true, ASYNC, 0);
        return true;
    }

    /**
     * Transfers the element to a waiting consumer immediately, if possible.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * otherwise returning {@code false} without enqueuing the element.
     *
     * @throws NullPointerException if the specified element is null
     */
    /*
     * 假如有消费者在等待，立刻传递元素到已经在等待的消费者
     * 假如没有消费者在等待，则返回 false，不进行入队操作
     */
    public boolean tryTransfer(E e) {
        return xfer(e, true, NOW, 0) == null;
    }

    /**
     * Transfers the element to a consumer, waiting if necessary to do so.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * else inserts the specified element at the tail of this queue
     * and waits until the element is received by a consumer.
     *
     * @throws NullPointerException if the specified element is null
     */
    /*
     * 假如有消费者在等待，立刻传递元素到已经在等待的消费者
     * 假如没有消费者在等待，当前生产者需要等待一个消费者接收
     */
    public void transfer(E e) throws InterruptedException {
        if (xfer(e, true, SYNC, 0) != null) {
            Thread.interrupted(); // failure possible only due to interrupt
            throw new InterruptedException();
        }
    }

    /**
     * Transfers the element to a consumer if it is possible to do so
     * before the timeout elapses.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * else inserts the specified element at the tail of this queue
     * and waits until the element is received by a consumer,
     * returning {@code false} if the specified wait time elapses
     * before the element can be transferred.
     *
     * @throws NullPointerException if the specified element is null
     */
    /*
     * 假如有消费者在等待，立刻传递元素到已经在等待的消费者
     * 假如没有消费者在等待，等待指定时间后，假如还没有消费者在等待，则返回 false，不进行入队操作
     */
    public boolean tryTransfer(E e, long timeout, TimeUnit unit)
        throws InterruptedException {
        if (xfer(e, true, TIMED, unit.toNanos(timeout)) == null)
            return true;
        if (!Thread.interrupted())
            return false;
        throw new InterruptedException();
    }

    /*
     * 消费者
     * 阻塞等待获取元素
     */
    public E take() throws InterruptedException {
        E e = xfer(null, false, SYNC, 0);
        if (e != null)
            return e;
        Thread.interrupted();
        throw new InterruptedException();
    }

    /*
     * 消费者
     * 阻塞等待获取元素，指定超时时间
     */
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        E e = xfer(null, false, TIMED, unit.toNanos(timeout));
        if (e != null || !Thread.interrupted())
            return e;
        throw new InterruptedException();
    }

    /*
     * 消费者
     * 获取元素
     */
    public E poll() {
        return xfer(null, false, NOW, 0);
    }

    /**
     * @throws NullPointerException     {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
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
     * @throws NullPointerException     {@inheritDoc}
     * @throws IllegalArgumentException {@inheritDoc}
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

    /**
     * Returns an iterator over the elements in this queue in proper sequence.
     * The elements will be returned in order from first (head) to last (tail).
     *
     * <p>The returned iterator is
     * <a href="package-summary.html#Weakly"><i>weakly consistent</i></a>.
     *
     * @return an iterator over the elements in this queue in proper sequence
     */
    public Iterator<E> iterator() {
        return new Itr();
    }

    public E peek() {
        return firstDataItem();
    }

    /**
     * Returns {@code true} if this queue contains no elements.
     *
     * @return {@code true} if this queue contains no elements
     */
    public boolean isEmpty() {
        for (Node p = head; p != null; p = succ(p)) {
            if (!p.isMatched())
                return !p.isData;
        }
        return true;
    }

    public boolean hasWaitingConsumer() {
        return firstOfMode(false) != null;
    }

    /**
     * Returns the number of elements in this queue.  If this queue
     * contains more than {@code Integer.MAX_VALUE} elements, returns
     * {@code Integer.MAX_VALUE}.
     *
     * <p>Beware that, unlike in most collections, this method is
     * <em>NOT</em> a constant-time operation. Because of the
     * asynchronous nature of these queues, determining the current
     * number of elements requires an O(n) traversal.
     *
     * @return the number of elements in this queue
     */
    public int size() {
        return countOfMode(true);
    }

    public int getWaitingConsumerCount() {
        return countOfMode(false);
    }

    /**
     * Removes a single instance of the specified element from this queue,
     * if it is present.  More formally, removes an element {@code e} such
     * that {@code o.equals(e)}, if this queue contains one or more such
     * elements.
     * Returns {@code true} if this queue contained the specified element
     * (or equivalently, if this queue changed as a result of the call).
     *
     * @param o element to be removed from this queue, if present
     * @return {@code true} if this queue changed as a result of the call
     */
    public boolean remove(Object o) {
        return findAndRemove(o);
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
        if (o == null) return false;
        for (Node p = head; p != null; p = succ(p)) {
            Object item = p.item;
            if (p.isData) {
                if (item != null && item != p && o.equals(item))
                    return true;
            }
            else if (item == null)
                break;
        }
        return false;
    }

    /**
     * Always returns {@code Integer.MAX_VALUE} because a
     * {@code LinkedTransferQueue} is not capacity constrained.
     *
     * @return {@code Integer.MAX_VALUE} (as specified by
     *         {@link java.util.concurrent.BlockingQueue#remainingCapacity()
     *         BlockingQueue.remainingCapacity})
     */
    public int remainingCapacity() {
        return Integer.MAX_VALUE;
    }

    /**
     * Saves this queue to a stream (that is, serializes it).
     *
     * @param s the stream
     * @throws java.io.IOException if an I/O error occurs
     * @serialData All of the elements (each an {@code E}) in
     * the proper order, followed by a null
     */
    private void writeObject(java.io.ObjectOutputStream s)
        throws java.io.IOException {
        s.defaultWriteObject();
        for (E e : this)
            s.writeObject(e);
        // Use trailing null as sentinel
        s.writeObject(null);
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
        for (;;) {
            @SuppressWarnings("unchecked")
            E item = (E) s.readObject();
            if (item == null)
                break;
            else
                offer(item);
        }
    }

    // Unsafe mechanics

    private static final sun.misc.Unsafe UNSAFE;
    private static final long headOffset;
    private static final long tailOffset;
    private static final long sweepVotesOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = LinkedTransferQueue.class;
            headOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("head"));
            tailOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("tail"));
            sweepVotesOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("sweepVotes"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }
}
