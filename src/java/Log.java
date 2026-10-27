/** Лог в System.out (JL-Mod выводит его в logcat). */
public final class Log {
    public static String last;

    public static void i(String s) {
        System.out.println("MCJ: " + s);
        Link.send(s);
    }

    public static void e(String s, Throwable t) {
        last = s + ": " + t;
        System.out.println("MCJ ERROR: " + last);
        Link.send("ERROR " + last);
        t.printStackTrace();
    }
}
