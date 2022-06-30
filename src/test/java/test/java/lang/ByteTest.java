package test.java.lang;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import static org.hamcrest.MatcherAssert.assertThat;


public class ByteTest {

    /**
     * 测试 Byte 里的缓存
     *
     * b1 b2 b4 的是同一个对象
     */
    @Test
    public void testByteCache() {
        Byte b1 = 120;
        Byte b2 = 120;
        Byte b3 = 121;
        Byte b4 = 120;
    }

    /**
     * 测试转换为无符号 int
     */
    @Test
    public void testUnsignedByte() {
        Byte b1 = 120;
        Byte b2 = -120;

        int i1 = Byte.toUnsignedInt(b1);
        int i2 = Byte.toUnsignedInt(b2);

        assertThat(i1, Matchers.equalTo(120));
        assertThat(i2, Matchers.equalTo(-120 + 256));
    }

    /**
     * 测试转换为无符号 long
     */
    @Test
    public void testUnsignedLong() {
        Byte b1 = 120;
        Byte b2 = -120;

        long i1 = Byte.toUnsignedLong(b1);
        long i2 = Byte.toUnsignedLong(b2);

        assertThat(i1, Matchers.equalTo(120L));
        assertThat(i2, Matchers.equalTo(-120 + 256L));
    }
}
