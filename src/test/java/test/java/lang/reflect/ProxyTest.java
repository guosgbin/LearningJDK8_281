package test.java.lang.reflect;

import sun.misc.ProxyGenerator;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public class ProxyTest {
    public static void main(String[] args) {
        Dog dog = new Dog();
        dog.eat();

        System.out.println("=========");

        Animal proxy = (Animal) Proxy.newProxyInstance(dog.getClass().getClassLoader(),
                dog.getClass().getInterfaces(),
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        System.out.println("狗吃饭前...");
                        Object result = method.invoke(dog, args);
                        System.out.println("狗吃完饭了...");
                        return result;
                    }
                });

        proxy.eat();

        generateClassFile(proxy.getClass(), proxy.getClass().getSimpleName());
    }

    public static void generateClassFile(Class clazz, String proxyName) {
        byte[] classFile = ProxyGenerator.generateProxyClass(proxyName, clazz.getInterfaces());
        FileOutputStream out  = null;
        try {
            out = new FileOutputStream("/Users/uxindylan/zDylanKwok/IDEAProject/Personal/LearningJDK8_281/src/test/java/test/java/lang/reflect" + proxyName + ".class");
            out.write(classFile);
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

    }


    interface Animal {
        void eat();
    }

    static class Dog implements Animal {

        @Override
        public void eat() {
            System.out.println("狗吃饭");
        }
    }
}
