package test.java.util.concurrent.atomic;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public class AtomicIntegerFieldUpdaterTest {

    @Test
    public void test() throws InterruptedException {
        People people = new People();
        Random random = new Random();
        List<Thread> list = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            Thread t = new Thread(() -> {
                sleep(random.nextInt(100));
                people.addMoneyAtomic();
            });
            list.add(t);
            t.start();
        }

        for (Thread thread : list) {
            thread.join();
        }

        System.out.println(people.getMoney());
    }


    public void sleep(long time) {
        try {
            TimeUnit.MILLISECONDS.sleep(time);
        } catch (InterruptedException e) {
        }
    }

}

class People {
    private volatile int money;
    private static final AtomicIntegerFieldUpdater<People> MONEY_UPDATER =
            AtomicIntegerFieldUpdater.newUpdater(People.class, "money");

    public void addMoneyAtomic() {
        MONEY_UPDATER.addAndGet(this, 1);
    }

    public void addMoney() {
        money++;
    }

    public int getMoney() {
        return money;
    }
}