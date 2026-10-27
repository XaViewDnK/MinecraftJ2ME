import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.StreamConnection;
import javax.microedition.io.StreamConnectionNotifier;

/**
 * Отладочная связь по Bluetooth — только в сборке Link (атрибут MCJ-Link в манифесте, см. ~/mcjlink): игра —
 * сервер RFCOMM, Android-приложение McjLink подключается к ней и пробрасывает строки в Termux.
 * Наружу — всё, что пишет Log; внутрь — «set ключ значение» (свойства mcj.* вместо System.getProperty: на
 * телефоне их не задать), «go» (хост готов — грузиться дальше), остальное — команды игре (MC.linkCmd).
 * Строки, записанные до подключения, копятся (до 1000) и уходят первыми.
 */
final class Link implements Runnable {
    static final String URL = "btspp://localhost:4D434A4C696E6B000000000000000001;name=MCJLink;authenticate=false;encrypt=false";
    static boolean on;
    private static final Hashtable props = new Hashtable();
    private static final Vector out = new Vector(), cmds = new Vector();
    private static final Object lock = new Object();
    private static boolean connected, go;
    /** состояние связи для экрана (MC.render): что видит человек у телефона */
    static volatile String state = "link: starting";

    static void start(String attr) {
        on = BuildCfg.DEBUG && attr != null;
        if (on) new Thread(new Link()).start();
    }

    /** Свойство отладки: присланное хостом, иначе системное. */
    static String prop(String k) {
        if (!BuildCfg.DEBUG) return null;
        Object v = props.get(k);
        return v != null ? (String) v : System.getProperty(k);
    }

    static void put(String k, String v) {
        if (v == null) props.remove(k);
        else props.put(k, v);
    }

    /** Начало загрузки игры: дать хосту прислать свойства — ms на подключение, после него до 15 с на «go». */
    static void awaitGo(long ms) {
        if (!on) return;
        long t0 = System.currentTimeMillis();
        synchronized (lock) {
            while (!go) {
                long left = (connected ? 15000 : ms) - (System.currentTimeMillis() - t0);
                if (left <= 0) break;
                try {
                    lock.wait(Math.min(left, 250));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    static void send(String s) {
        if (!on) return;
        synchronized (lock) {
            if (out.size() >= 1000) out.removeElementAt(0);
            out.addElement(s);
            lock.notifyAll();
        }
    }

    /** Следующая команда игре (поток игры) или null. */
    static String poll() {
        synchronized (lock) {
            if (cmds.size() == 0) return null;
            String s = (String) cmds.elementAt(0);
            cmds.removeElementAt(0);
            return s;
        }
    }

    public void run() {
        StreamConnectionNotifier n = null;
        while (true) {
            StreamConnection c = null;
            try {
                if (n == null) n = (StreamConnectionNotifier) Connector.open(URL);
                state = "link: waiting";
                c = n.acceptAndOpen();
                state = "link: connected";
                serve(c);
                state = "link: closed";
            } catch (Throwable t) {
                System.out.println("MCJ: link " + t);
                state = "link: " + t;
                if (c == null && n != null) {
                    try { n.close(); } catch (Throwable e) { }
                    n = null;
                }
                sleep(c == null ? 3000 : 300);
            } finally {
                if (c != null) try { c.close(); } catch (Throwable e) { }
                synchronized (lock) {
                    connected = false;
                }
            }
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { }
    }

    private void serve(StreamConnection c) throws Exception {
        final InputStream in = c.openInputStream();
        OutputStream os = c.openOutputStream();
        // жив ли этот сеанс — своё значение на соединение: поток чтения прошлого сеанса не должен закрыть новый
        final boolean[] alive = { true };
        synchronized (lock) {
            connected = true;
            lock.notifyAll();
        }
        // чтение — своим потоком: строки до '\n'
        new Thread() {
            public void run() {
                byte[] b = new byte[512];
                int n = 0;
                try {
                    for (int r; (r = in.read()) >= 0;) {
                        if (r == '\n') {
                            handle(new String(b, 0, n, "UTF-8").trim());
                            n = 0;
                        } else if (r != '\r') {
                            if (n == b.length) {
                                byte[] nb = new byte[n * 2];
                                System.arraycopy(b, 0, nb, 0, n);
                                b = nb;
                            }
                            b[n++] = (byte) r;
                        }
                    }
                } catch (Throwable t) {
                }
                synchronized (lock) {
                    alive[0] = false;
                    lock.notifyAll();
                }
            }
        }.start();
        send("LINK connected");
        while (true) {
            String s;
            synchronized (lock) {
                while (alive[0] && out.size() == 0) lock.wait(1000);
                if (!alive[0]) break;
                s = (String) out.elementAt(0);
                out.removeElementAt(0);
            }
            os.write(s.getBytes("UTF-8"));
            os.write('\n');
            os.flush();
        }
        // поток чтения выйдет на закрытом потоке
        try { in.close(); } catch (Throwable e) { }
        try { os.close(); } catch (Throwable e) { }
    }

    private static void handle(String s) {
        if (s.length() == 0) return;
        if (s.startsWith("set ")) {
            int p = s.indexOf(' ', 4);
            if (p < 0) props.put(s.substring(4), "");
            else props.put(s.substring(4, p), s.substring(p + 1));
            return;
        }
        if (s.startsWith("unset ")) {
            props.remove(s.substring(6));
            return;
        }
        synchronized (lock) {
            if (s.equals("go")) go = true;
            else if (s.equals("ping")) out.addElement("pong");
            else cmds.addElement(s);
            lock.notifyAll();
        }
    }
}
