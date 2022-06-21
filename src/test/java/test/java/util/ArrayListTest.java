package test.java.util;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static org.hamcrest.MatcherAssert.assertThat;

public class ArrayListTest {

    @Test
    public void testConstructor() {
        ArrayList<String> emptyParamlist = new ArrayList<>();
        ArrayList<String> initCapacitylist = new ArrayList<>(3);
    }

    /**
     * 空参构造函数会在第一次添加元素时 初始化容量为 10
     */
    @Test
    public void testAdd() throws Exception{
        ArrayList<String> emptyParamlist = new ArrayList<>();
        Field elementData = ArrayList.class.getDeclaredField("elementData");
        elementData.setAccessible(true);

        Object[] addBefore = (Object[]) elementData.get(emptyParamlist);
        assertThat(addBefore.length, Matchers.equalTo(0));

        emptyParamlist.add("你好");
        Object[] addAfter = (Object[]) elementData.get(emptyParamlist);
        assertThat(addAfter.length, Matchers.equalTo(10));
    }

    /**
     * 测试扩容
     */
    @Test
    public void testGrow() throws Exception {
        ArrayList<String> emptyParamlist = new ArrayList<>();
        Field elementData = ArrayList.class.getDeclaredField("elementData");
        elementData.setAccessible(true);

        for (int i = 0; i < 10; i++) {
            emptyParamlist.add("问问");
        }
        Object[] before = (Object[]) elementData.get(emptyParamlist);
        assertThat(before.length, Matchers.equalTo(10));

        // 触发扩容
        emptyParamlist.add("1");

        // 获取扩容后的数组长度
        Object[] after = (Object[]) elementData.get(emptyParamlist);
        assertThat(after.length, Matchers.equalTo(15));
    }
}
