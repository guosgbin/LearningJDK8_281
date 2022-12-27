package test.java.util.concurrent;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadLocalRandom;

public class LinkedTransferQueueTest {

    /**
     * 测试整个入队和出队的流程
     */
    @Test
    public void test01() {
        LinkedTransferQueue<String> queue = new LinkedTransferQueue<>();
        queue.put("我是第1个");
        queue.put("我是第2个");
        queue.put("我是第3个");
        queue.put("我是第4个");

        queue.poll();
        queue.poll();
    }
}
