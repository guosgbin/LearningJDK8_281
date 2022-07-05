package test.java.lang;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

public class IntegerTest {

    /**
     * 测试 Integer 的 toString 转换进制的方法
     */
    @Test
    public void testToString() {
        String s1 = Integer.toString(200, 2);
        // 转换为 10 进制，会走快速方法
        String s2 = Integer.toString(432324234, 10);

        System.out.println(s1);
        System.out.println(s2);
    }

    /**
     * 测试转换进制
     */
    @Test
    public void testFormatUnsignedInt() {
        final char[] digits = {
                '0', '1', '2', '3', '4', '5',
                '6', '7', '8', '9', 'a', 'b',
                'c', 'd', 'e', 'f', 'g', 'h',
                'i', 'j', 'k', 'l', 'm', 'n',
                'o', 'p', 'q', 'r', 's', 't',
                'u', 'v', 'w', 'x', 'y', 'z'
        };
        // 表示转为 16 进制
        int shift = 4;
        int radix = 1 << shift;
        // 1111
        int mask = radix - 1;
        int val = 12345;
        System.out.println(Integer.toBinaryString(12345));
        StringBuilder sb = new StringBuilder();

        /*
         * 0011 0000 0011 1001
         *                1111
         * -------------------
         *                1001
         * 得到 1001 -> 去 digits 查，得到 9，所以最后一位就是 9
         *
         * 依次得到 0011 -> 3
         * 依次得到 0000 -> 0
         * 依次得到 0011 -> 3
         *
         * 最后得到 3039
         */
        while (val > 0) {
            char digit = digits[val & mask];
            sb.append(digit);
            val >>>= 4;
        }
        System.out.println(sb.reverse());
        System.out.println(Integer.toHexString(12345));
    }

    /**
     * 测试 Integer 的 numberOfLeadingZeros 方法
     *
     * @see Integer#numberOfLeadingZeros(int)
     */
    @Test
    public void testNumberOfLeadingZeros() {
        // 123
        // 00000000 00000000 00000000 01111011
        // 前面有 25 个 0
        int i = Integer.numberOfLeadingZeros(123);
        MatcherAssert.assertThat(i, Matchers.equalTo(25));
    }

    /**
     * 测试 Integer 的 numberOfLeadingZeros 方法
     *
     * @see Integer#numberOfTrailingZeros(int)
     */
    @Test
    public void testNumberOfTrailingZeros() {
        // 100
        // 00000000 00000000 00000000 01100100
        // 后面 2 个连续的 0
        int i = Integer.numberOfTrailingZeros(100);
        MatcherAssert.assertThat(i, Matchers.equalTo(2));
    }


    /**
     * 测试
     * 获取 i 的第一个 1 或者最后一个 1 的位表示的值
     *
     * @see Integer#highestOneBit(int)
     * @see Integer#lowestOneBit(int)
     */
    @Test
    public void testOneBit() {
        System.out.println(Integer.toBinaryString(100));

        int i = Integer.highestOneBit(111);
        System.out.println(i);

        int i1 = Integer.lowestOneBit(100);
        System.out.println(i1);
    }


    /**
     * i 二进制中有多少个 1
     * @see Integer#bitCount(int)
     */
    @Test
    public void testBitCount() {
        // 100
        // 00000000 00000000 00000000 01100100
        // 后面 2 个连续的 0
        int i = Integer.bitCount(100);
        MatcherAssert.assertThat(i, Matchers.equalTo(3));
    }

    /**
     * 移位操作
     *
     * @see Integer#rotateLeft(int, int)
     * @see Integer#rotateRight(int, int)
     */
    @Test
    public void testRotateLeftAndRight() {
        // 100
        // 00000000 00000000 00000000 01100100

        // 99
        // 00000000 00000000 00000000 001100011



        // 循环左移两位
        int left = Integer.rotateLeft(100, 2);
        // 循环右移两位
        int right = Integer.rotateRight(99, 2);

        MatcherAssert.assertThat(left, Matchers.equalTo(Integer.parseInt("110010000", 2)));
        System.out.println(Integer.toBinaryString(right));
    }
}
