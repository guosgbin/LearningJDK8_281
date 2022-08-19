package test.java.util.concurrent.atomic;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 *
 */
public class AtomicReferenceFieldUpdaterTest {

    @Test
    public void test() {
//        AtomicReferenceFieldUpdater<Person,String> updater = AtomicReferenceFieldUpdater.newUpdater(Person.class, String.class, "name");
        AtomicReferenceFieldUpdater<Person,Integer> updater = AtomicReferenceFieldUpdater.newUpdater(Person.class, Integer.class, "money");
//        AtomicReferenceFieldUpdater<Person,Integer> updater = AtomicReferenceFieldUpdater.newUpdater(Person.class, Integer.class, "tall");
//        AtomicReferenceFieldUpdater<Person,Integer> updater = AtomicReferenceFieldUpdater.newUpdater(Person.class, Integer.class, "sex");

        Person person = new Person("特朗普", 100, 1);

        System.out.println(updater.get(person));

        updater.getAndUpdate(person, (money) -> money + 10);

        System.out.println(updater.get(person));
    }

    @Test
    public void test1() {
        Double joinGroupTime = 1660098512000.0;
        Instant jointime = Instant.ofEpochMilli(joinGroupTime.longValue());
        Instant now = Instant.now();

        long l = Duration.between(jointime, now).toDays() + 1;
        System.out.println(l);
    }


}

class Person {
    // 下面的描述都是针对 AtomicReferenceFieldUpdaterTest 来说的，简说为 test 了

    // 因为是 private 修饰的，这个字段 test 类无法访问
    private String name;
    // 这个是 public 修饰，且是 volatile
    public volatile Integer money;
    // 不能是 static 修饰
    public static volatile Integer tall = 150;
    // 必须有 volatile 修饰
    public final Integer sex;

    public Person(String name, Integer money, Integer sex) {
        this.name = name;
        this.money = money;
        this.sex = sex;
    }
}