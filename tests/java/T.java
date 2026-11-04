import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

public class T {
    static int fails = 0;
    static int passes = 0;

    static void eq(Object exp, Object act, String msg) {
        if (exp == null ? act != null : !exp.equals(act)) {
            if (exp instanceof Object[] && act instanceof Object[] && Arrays.deepEquals((Object[]) exp, (Object[]) act)) return;
            throw new AssertionError(msg + ": expected <" + exp + "> but was <" + act + ">");
        }
    }

    static void yes(boolean c, String msg) {
        if (!c) throw new AssertionError(msg);
    }

    public static void main(String[] args) throws Exception {
        for (String cn : args) {
            Class<?> c = Class.forName(cn);
            Method[] ms = c.getDeclaredMethods();
            Arrays.sort(ms, (a, b) -> a.getName().compareTo(b.getName()));
            for (Method m : ms) {
                if (!m.getName().startsWith("test") || m.getParameterTypes().length != 0) continue;
                m.setAccessible(true);
                String name = c.getSimpleName() + "." + m.getName();
                try {
                    Object inst = Modifier.isStatic(m.getModifiers()) ? null : c.getDeclaredConstructor().newInstance();
                    m.invoke(inst);
                    System.out.println("PASS " + name);
                    passes++;
                } catch (Throwable t) {
                    Throwable r = t.getCause() != null ? t.getCause() : t;
                    System.out.println("FAIL " + name + ": " + r);
                    fails++;
                }
            }
        }
        System.out.println(passes + " passed, " + fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
