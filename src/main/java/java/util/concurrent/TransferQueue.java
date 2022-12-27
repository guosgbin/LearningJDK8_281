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

/**
 * A {@link BlockingQueue} in which producers may wait for consumers
 * to receive elements.  A {@code TransferQueue} may be useful for
 * example in message passing applications in which producers
 * sometimes (using method {@link #transfer}) await receipt of
 * elements by consumers invoking {@code take} or {@code poll}, while
 * at other times enqueue elements (via method {@code put}) without
 * waiting for receipt.
 * {@linkplain #tryTransfer(Object) Non-blocking} and
 * {@linkplain #tryTransfer(Object,long,TimeUnit) time-out} versions of
 * {@code tryTransfer} are also available.
 * A {@code TransferQueue} may also be queried, via {@link
 * #hasWaitingConsumer}, whether there are any threads waiting for
 * items, which is a converse analogy to a {@code peek} operation.
 *
 * <p>Like other blocking queues, a {@code TransferQueue} may be
 * capacity bounded.  If so, an attempted transfer operation may
 * initially block waiting for available space, and/or subsequently
 * block waiting for reception by a consumer.  Note that in a queue
 * with zero capacity, such as {@link SynchronousQueue}, {@code put}
 * and {@code transfer} are effectively synonymous.
 *
 * <p>This interface is a member of the
 * <a href="{@docRoot}/../technotes/guides/collections/index.html">
 * Java Collections Framework</a>.
 *
 * @since 1.7
 * @author Doug Lea
 * @param <E> the type of elements held in this collection
 */

/*
 * 一种阻塞队列
 * 生产者可能会等待消费者接收数据
 *
 * TransferQueue 可能在消息传递的应用程序中很有用
 * 其中生产者有时（使用方法｛@link#transfer｝）等待消费者调用｛@code take｝或｛@code poll｝接收元素，
 * 而在其他时候（通过方法｛@code put｝）对元素进行排队，而不等待接收。
 *
 * #tryTransfer(Object) 是不阻塞的
 * #tryTransfer(Object,long,TimeUnit) time-out 超时等待
 * 可以通过 #hasWaitingConsumer 方法查询是否有线程在等待消费，这和 peek 操作完全相反
 *
 * 和其他阻塞队列一样，TransferQueue可能会有容量限制。
 * 如果是这样，则尝试的 transfer 操作可能最初阻塞等待可用空间，随后阻塞等待消费者接收。
 * 注意，在 0 容量的队列中，例如｛@link SynchronousQueue｝，｛@code put｝和｛@code transfer｝实际上是同义词。
 */
public interface TransferQueue<E> extends BlockingQueue<E> {
    /**
     * Transfers the element to a waiting consumer immediately, if possible.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * otherwise returning {@code false} without enqueuing the element.
     *
     * @param e the element to transfer
     * @return {@code true} if the element was transferred, else
     *         {@code false}
     * @throws ClassCastException if the class of the specified element
     *         prevents it from being added to this queue
     * @throws NullPointerException if the specified element is null
     * @throws IllegalArgumentException if some property of the specified
     *         element prevents it from being added to this queue
     */
    /*
     * 假如有消费者在等待（take 方法或者 poll(long,TimeUnit)），立刻传递元素到已经在等待的消费者
     * 假如没有消费者在等待，则返回 false，不进行入队操作，也就意味着直接丢弃尝试入队的数据
     *
     * 感觉和 ReentrantLock#tryLock 有点像
     */
    boolean tryTransfer(E e);

    /**
     * Transfers the element to a consumer, waiting if necessary to do so.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * else waits until the element is received by a consumer.
     *
     * @param e the element to transfer
     * @throws InterruptedException if interrupted while waiting,
     *         in which case the element is not left enqueued
     * @throws ClassCastException if the class of the specified element
     *         prevents it from being added to this queue
     * @throws NullPointerException if the specified element is null
     * @throws IllegalArgumentException if some property of the specified
     *         element prevents it from being added to this queue
     */
    /*
     * 假如有消费者在等待（take 方法或者 poll(long,TimeUnit)），立刻传递元素到已经在等待的消费者
     * 假如没有消费者在等待，当前生产者需要等待直到一个消费者接收数据
     */
    void transfer(E e) throws InterruptedException;

    /**
     * Transfers the element to a consumer if it is possible to do so
     * before the timeout elapses.
     *
     * <p>More precisely, transfers the specified element immediately
     * if there exists a consumer already waiting to receive it (in
     * {@link #take} or timed {@link #poll(long,TimeUnit) poll}),
     * else waits until the element is received by a consumer,
     * returning {@code false} if the specified wait time elapses
     * before the element can be transferred.
     *
     * @param e the element to transfer
     * @param timeout how long to wait before giving up, in units of
     *        {@code unit}
     * @param unit a {@code TimeUnit} determining how to interpret the
     *        {@code timeout} parameter
     * @return {@code true} if successful, or {@code false} if
     *         the specified waiting time elapses before completion,
     *         in which case the element is not left enqueued
     * @throws InterruptedException if interrupted while waiting,
     *         in which case the element is not left enqueued
     * @throws ClassCastException if the class of the specified element
     *         prevents it from being added to this queue
     * @throws NullPointerException if the specified element is null
     * @throws IllegalArgumentException if some property of the specified
     *         element prevents it from being added to this queue
     */
    /*
     * 假如有消费者在等待，立刻传递元素到已经在等待的消费者
     * 假如没有消费者在等待，等待指定时间后，假如还没有消费者在等待，则返回 false，不进行入队操作
     *
     * 感觉和 ReentrantLock#tryLock 有点像
     */
    boolean tryTransfer(E e, long timeout, TimeUnit unit)
        throws InterruptedException;

    /**
     * Returns {@code true} if there is at least one consumer waiting
     * to receive an element via {@link #take} or
     * timed {@link #poll(long,TimeUnit) poll}.
     * The return value represents a momentary state of affairs.
     *
     * @return {@code true} if there is at least one waiting consumer
     */
    /*
     * 返回 true 表示至少有一个消费者在等待获取元素，一般是消费者线程调用 take 或者 poll 方法
     * 这个返回值表示某个时候瞬时的状态
     */
    boolean hasWaitingConsumer();

    /**
     * Returns an estimate of the number of consumers waiting to
     * receive elements via {@link #take} or timed
     * {@link #poll(long,TimeUnit) poll}.  The return value is an
     * approximation of a momentary state of affairs, that may be
     * inaccurate if consumers have completed or given up waiting.
     * The value may be useful for monitoring and heuristics, but
     * not for synchronization control.  Implementations of this
     * method are likely to be noticeably slower than those for
     * {@link #hasWaitingConsumer}.
     *
     * @return the number of consumers waiting to receive elements
     */
    /*
     * 获取有多少个消费者在 take 或者 poll 显示等待
     * 这是一个估计值（近似值）
     * 可能是错误估计，消费者可能已经完成获取元素或者放弃等待了。
     * 这个值可以在监控的时候有用，
     * 此方法的实现可能明显慢于｛@link#hasWaitingConsumer｝的实现。 ？ 这句话没懂
     */
    int getWaitingConsumerCount();
}
