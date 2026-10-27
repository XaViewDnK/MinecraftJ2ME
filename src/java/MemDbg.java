import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Image2D;
import javax.microedition.m3g.IndexBuffer;
import javax.microedition.m3g.VertexArray;

/**
 * Отладка памяти (свойство mcj.mem, через запятую): только для замеров в J2ME Loader, в обычной игре выключена.
 *  hprof     — на каждой отметке дамп хипа ART (android.os.Debug) в кэш J2ME Loader: /data/data/ru.playsoftware.j2meloader/cache/mcjmem_*.hprof
 *  img       — на отметках пиксели живых Image (+ Image2D в момент создания) в c:/mcjmem/ для разбора в PNG;
 *  limit=<K> — жёсткий лимит «хипа телефона» в КБ: всё сверх (наша Java-часть + картинки и M3G по их размеру) — OutOfMemoryError;
 *  only=<a+b> — дампы только на этих отметках.
 * Отметки (mark) пишут в лог строку MEM: живой Java-хип игры после GC, нативные картинки/M3G по видам, пик между отметками.
 * Файл c:/mcjmem/req (текст — имя отметки) — отметка с дампом по запросу снаружи.
 */
final class MemDbg {
    static final String opt = BuildCfg.DEBUG ? System.getProperty("mcj.mem") : null;
    static final boolean on = opt != null;
    static final int IMG = 0, I2D = 1, VA = 2, IB = 3, KINDS = 4;
    static final String[] KIND = { "img", "i2d", "va", "ib" };
    static final String DIR = "file:///c:/mcjmem/";
    static final String HPROF = "/data/data/ru.playsoftware.j2meloader/cache/mcjmem_";

    static final class E {
        WeakReference ref;
        int kind, bytes, w, h, draws, id;
        String tag;
        byte[] shadow;
    }

    private static final Hashtable reg = new Hashtable();
    private static final Vector all = new Vector();
    private static long base, ballastBytes, limit, peak, peakAll, maxMem;
    private static Vector ballast;
    private static int seq, ids;
    private static String phase = "boot";
    private static final long[] natBytes = new long[KINDS];
    private static final int[] natCount = new int[KINDS];

    static boolean has(String k) {
        return on && val(k) != null;
    }

    static String val(String k) {
        int p = ("," + opt + ",").indexOf("," + k);
        if (p < 0) return null;
        String s = (opt + ",").substring(p + k.length());
        if (s.startsWith("=")) s = s.substring(1);
        else if (!s.startsWith(",")) return null;
        return s.substring(0, s.indexOf(','));
    }

    /** Начало мидлета: база хипа загрузчика, балласт под лимит, поток пика и запросов. */
    static void boot() {
        if (!on || ballast != null) return;
        Runtime r = Runtime.getRuntime();
        r.gc();
        base = r.totalMemory() - r.freeMemory();
        ballast = new Vector();
        String l = val("limit");
        if (l != null) {
            limit = Long.parseLong(l) * 1024;
            // в CLDC нет maxMemory: предел хипа — размер хипа, когда он забит до OutOfMemoryError
            try {
                while (true) ballast.addElement(new byte[1 << 20]);
            } catch (OutOfMemoryError e) {
            }
            maxMem = r.totalMemory();
            ballast.removeAllElements();
            r.gc();
            // ART отказывает, когда после сборки свободно меньше 1% всего хипа, — этот 1% тоже вычесть из балласта
            long need = maxMem - base - limit - maxMem / 100 - 256 * 1024;
            while (need > 0) {
                int n = (int) Math.min(need, 1 << 20);
                ballast.addElement(new byte[n]);
                ballastBytes += n;
                need -= n;
            }
            r.gc();
            base = r.totalMemory() - r.freeMemory() - ballastBytes;
        }
        Log.i("MEM boot base " + (base >> 10) + "K max " + (maxMem >> 10) + "K ballast " + (ballastBytes >> 10) + "K limit " + (limit >> 10) + "K");
        Thread t = new Thread() {
            public void run() {
                int n = 0;
                while (true) {
                    try {
                        Thread.sleep(5);
                        long u = used();
                        if (u > peak) peak = u;
                        if (u > peakAll) peakAll = u;
                        if (++n % 200 == 0) {
                            sweep();
                            String q = readReq();
                            if (q != null) mark(q.length() == 0 ? "req" : q, true);
                        }
                    } catch (Throwable e) {
                    }
                }
            }
        };
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    /** Режим limit= — натив в хипе тенями (эмуляция телефона). */
    static boolean limited() {
        return limit > 0;
    }

    /** Убрать тени собранных объектов (после gc при нехватке памяти) и собрать их. */
    static void sweepNow() {
        sweep();
        Runtime.getRuntime().gc();
    }

    /** Занято сейчас (с мусором): Java-хип игры + нативные картинки и M3G. */
    static long used() {
        Runtime r = Runtime.getRuntime();
        // в режиме лимита натив уже лежит в хипе тенями (shadow)
        return r.totalMemory() - r.freeMemory() - ballastBytes - base + (limit > 0 ? 0 : natAll());
    }

    private static long natAll() {
        long s = 0;
        for (int i = 0; i < KINDS; i++) s += natBytes[i];
        return s;
    }

    static void mark(String name) {
        mark(name, false);
    }

    static synchronized void mark(String name, boolean forceDump) {
        if (!on) return;
        sweep();
        Runtime r = Runtime.getRuntime();
        r.gc();
        sweep();
        long java = r.totalMemory() - r.freeMemory() - ballastBytes - base - (limit > 0 ? natAll() : 0);
        seq++;
        StringBuffer sb = new StringBuffer();
        sb.append("MEM ").append(seq).append(' ').append(name).append(" java ").append(java >> 10).append("K nat ").append(natAll() >> 10).append('K');
        for (int i = 0; i < KINDS; i++) sb.append(' ').append(KIND[i]).append(' ').append(natCount[i]).append('/').append(natBytes[i] >> 10).append('K');
        sb.append(" = ").append((java + natAll()) >> 10).append("K, peak ").append(phase).append("..").append(name).append(' ').append(peak >> 10)
                .append("K, peakAll ").append(peakAll >> 10).append("K free ").append(r.freeMemory() >> 10).append('K');
        Log.i(sb.toString());
        peak = 0;
        phase = name;
        String only = val("only");
        boolean dump = forceDump || only == null || ("+" + only + "+").indexOf("+" + name + "+") >= 0;
        if (!dump) return;
        String pre = (seq < 10 ? "0" : "") + seq + "_" + name;
        writeList(pre, sb.toString(), java);
        if (has("img")) dumpImages(pre);
        if (has("hprof") || forceDump) hprof(HPROF + pre + ".hprof");
    }

    private static void hprof(String path) {
        try {
            MemHprof.path = path;
            ((Runnable) Class.forName("MemHprof").newInstance()).run();
            Log.i("MEM hprof " + path);
        } catch (Throwable t) {
            Log.e("MEM hprof", t);
        }
    }

    // ---- реестр нативных объектов ----

    static void img(Image im, String tag) {
        if (im != null) add(im, IMG, tag, im.getWidth() * im.getHeight() * 4, im.getWidth(), im.getHeight());
    }

    /** Image2D: bytes — размер пикселей (с палитрой); pixels/pal — для дампа в режиме img (может быть null). */
    static void i2d(Image2D im, String tag, int bytes, byte[] pixels, byte[] pal) {
        if (im == null) return;
        E e = add(im, I2D, tag, bytes, im.getWidth(), im.getHeight());
        if (has("img") && pixels != null) {
            try {
                OutputStream o = open("i2d_" + e.id + ".bin");
                writeInt(o, im.getWidth());
                writeInt(o, im.getHeight());
                writeInt(o, pal == null ? 0 : pal.length);
                if (pal != null) o.write(pal);
                o.write(pixels);
                o.close();
            } catch (Throwable t) {
                Log.e("MEM i2d", t);
            }
        }
    }

    static void va(VertexArray v, String tag) {
        if (v != null) add(v, VA, tag, v.getVertexCount() * v.getComponentCount() * v.getComponentType(), v.getVertexCount(), v.getComponentCount());
    }

    static void ib(IndexBuffer b, String tag) {
        if (b != null) add(b, IB, tag, b.getIndexCount() * 2, b.getIndexCount(), 0);
    }

    private static synchronized E add(Object o, int kind, String tag, int bytes, int w, int h) {
        sweep();
        E e = new E();
        e.ref = new WeakReference(o);
        e.kind = kind;
        e.tag = tag;
        e.bytes = bytes;
        e.w = w;
        e.h = h;
        e.id = ++ids;
        if (limit > 0) e.shadow = new byte[bytes];
        reg.put(new Integer(System.identityHashCode(o)), e);
        all.addElement(e);
        natBytes[kind] += bytes;
        natCount[kind]++;
        return e;
    }

    /** картинка нарисована (отметка used/unused) */
    static void drawn(Image im) {
        E e = (E) reg.get(new Integer(System.identityHashCode(im)));
        if (e != null) e.draws++;
    }

    private static synchronized void sweep() {
        for (int i = all.size() - 1; i >= 0; i--) {
            E e = (E) all.elementAt(i);
            if (e.ref.get() == null) {
                all.removeElementAt(i);
                natBytes[e.kind] -= e.bytes;
                natCount[e.kind]--;
                e.shadow = null;
                Integer k = new Integer(0);
                for (Enumeration en = reg.keys(); en.hasMoreElements();) {
                    Object key = en.nextElement();
                    if (reg.get(key) == e) { k = (Integer) key; break; }
                }
                if (reg.get(k) == e) reg.remove(k);
            }
        }
    }

    // ---- файлы ----

    private static void writeList(String pre, String head, long java) {
        try {
            StringBuffer sb = new StringBuffer(head).append('\n');
            for (int i = 0; i < all.size(); i++) {
                E e = (E) all.elementAt(i);
                if (e.ref.get() == null) continue;
                sb.append(KIND[e.kind]).append('\t').append(e.id).append('\t').append(e.tag).append('\t').append(e.w).append('\t').append(e.h)
                        .append('\t').append(e.bytes).append('\t').append(e.draws).append('\n');
            }
            OutputStream o = open(pre + ".txt");
            o.write(sb.toString().getBytes());
            o.close();
        } catch (Throwable t) {
            Log.e("MEM list", t);
        }
    }

    private static void dumpImages(String pre) {
        try {
            OutputStream o = open(pre + ".img");
            for (int i = 0; i < all.size(); i++) {
                E e = (E) all.elementAt(i);
                Object im = e.ref.get();
                if (e.kind != IMG || im == null) continue;
                Image m = (Image) im;
                int[] px = new int[e.w * e.h];
                m.getRGB(px, 0, e.w, 0, 0, e.w, e.h);
                writeInt(o, e.id);
                writeInt(o, e.w);
                writeInt(o, e.h);
                byte[] b = new byte[px.length * 4];
                for (int j = 0; j < px.length; j++) {
                    int c = px[j];
                    b[j * 4] = (byte) (c >> 24);
                    b[j * 4 + 1] = (byte) (c >> 16);
                    b[j * 4 + 2] = (byte) (c >> 8);
                    b[j * 4 + 3] = (byte) c;
                }
                o.write(b);
            }
            o.close();
        } catch (Throwable t) {
            Log.e("MEM img", t);
        }
    }

    private static void writeInt(OutputStream o, int v) throws java.io.IOException {
        o.write(v >> 24);
        o.write(v >> 16);
        o.write(v >> 8);
        o.write(v);
    }

    private static OutputStream open(String name) throws java.io.IOException {
        FileConnection d = (FileConnection) Connector.open(DIR);
        if (!d.exists()) d.mkdir();
        d.close();
        FileConnection fc = (FileConnection) Connector.open(DIR + name);
        if (fc.exists()) fc.delete();
        fc.create();
        return fc.openOutputStream();
    }

    private static String readReq() {
        try {
            FileConnection fc = (FileConnection) Connector.open(DIR + "req");
            try {
                if (!fc.exists()) return null;
                java.io.InputStream in = fc.openInputStream();
                StringBuffer sb = new StringBuffer();
                int c;
                while ((c = in.read()) > ' ') sb.append((char) c);
                in.close();
                fc.delete();
                return sb.toString();
            } finally {
                fc.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
