package test.java.lang;


import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;

public class StringTest {

    /**
     * 测试长度
     *
     * https://juejin.cn/post/6844904036873814023
     */
    @Test
    public void testLength() {
        String b = "𝄞";
        // 这个就是音符字符的UTF-16编码
        String c = "\uD834\uDD1E";

        // b 的长度是 2
        assertThat(b.length(), Matchers.equalTo(2));

        int count = b.codePointCount(0, b.length());
        // count 是 1
        assertThat(count, Matchers.equalTo(1));
    }
}
