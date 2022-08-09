package test.java.util;

import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayDeque;

public class ArrayDequeTest {

    @Test
    public void testcCalculateSize() throws Exception {
        Class<ArrayDeque> clazz = ArrayDeque.class;
        ArrayDeque arrayDeque = clazz.newInstance();
        Method method = clazz.getDeclaredMethod("calculateSize", int.class);
        method.setAccessible(true);
        Object result = method.invoke(arrayDeque, 32);
        MatcherAssert.assertThat(result, Matchers.equalTo(64));
    }

    @Test
    public void testAddFirst() {
//        ArrayDeque<Integer> arrayDeque = new ArrayDeque<>();
//        arrayDeque.add(1);
//        System.out.println(arrayDeque);


//        int i = (-5) & 7;
//        System.out.println(i);

        String canonicalName = double.class.getCanonicalName();
        String canonicalName1 = Double.class.getCanonicalName();
        System.out.println(canonicalName);
        System.out.println(canonicalName1);

    }
}
