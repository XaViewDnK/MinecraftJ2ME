import java.io.InputStream;
import java.util.Hashtable;
import java.util.Random;
import java.util.Vector;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.control.VolumeControl;

/**
 * Звуки из /snd/*.wav (сконвертированы из PCM-данных Android-версии).
 * Имя вида "step.stone" / "mob.zombiehurt" -> база "stone"/"zombiehurt", варианты base1..base4 выбираются случайно.
 * Плееры MMAPI создаются и запускаются в отдельном потоке: в JL-Mod createPlayer пишет временный файл и гоняет
 * FFprobe, а start/stop идут через Android MediaPlayer — в игровом потоке это давало рывки по 30-130 мс.
 */
public final class Sound implements Runnable {
    private static final Hashtable variants = new Hashtable(); // база -> Vector имён файлов
    private static final Hashtable players = new Hashtable();   // имя файла -> Player
    private static final Random rnd = new Random();

    static void seed(long s) {
        rnd.setSeed(s);
    }
    private static boolean ok;
    public static boolean enabled = true;

    public static void init() {
        try {
            InputStream in = Sound.class.getResourceAsStream("/snd/list.txt");
            if (in == null) return;
            StringBuffer sb = new StringBuffer();
            int c;
            while ((c = in.read()) >= 0) sb.append((char) c);
            in.close();
            String s = sb.toString();
            int p = 0;
            while (p < s.length()) {
                int e = s.indexOf('\n', p);
                if (e < 0) e = s.length();
                String name = s.substring(p, e).trim();
                p = e + 1;
                if (name.length() == 0) continue;
                int k = name.length();
                while (k > 0 && Character.isDigit(name.charAt(k - 1))) k--;
                String base = name.substring(0, k);
                Vector v = (Vector) variants.get(base);
                if (v == null) {
                    v = new Vector();
                    variants.put(base, v);
                }
                v.addElement(name);
            }
            ok = true;
            Thread th = new Thread(new Sound());
            th.setPriority(Thread.NORM_PRIORITY);
            th.start();
        } catch (Throwable t) {
            Log.e("sound init", t);
        }
    }

    // очередь запросов: имя файла и громкость
    private static final String[] qFile = new String[32];
    private static final float[] qVol = new float[32];
    private static int qHead, qCount;
    private static final Object lock = new Object();

    /** Частые звуки (шаги, копание, интерфейс) — плееры готовятся заранее, в фоне. */
    private static final String[] WARM = { "grass", "stone", "wood", "gravel", "sand", "cloth", "click", "pop" };

    public void run() {
        for (int i = 0; i < WARM.length; i++) {
            Vector v = (Vector) variants.get(WARM[i]);
            if (v == null) continue;
            for (int k = 0; k < v.size(); k++) {
                if (pending()) drain();
                player((String) v.elementAt(k));
            }
        }
        while (true) {
            synchronized (lock) {
                while (qCount == 0) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            drain();
        }
    }

    private static boolean pending() {
        synchronized (lock) {
            return qCount > 0;
        }
    }

    private static void drain() {
        while (true) {
            String file;
            float vol;
            synchronized (lock) {
                if (qCount == 0) return;
                file = qFile[qHead];
                vol = qVol[qHead];
                qFile[qHead] = null;
                qHead = (qHead + 1) % qFile.length;
                qCount--;
            }
            start(file, vol);
        }
    }

    private static Player player(String file) {
        Player p = (Player) players.get(file);
        if (p != null) return p;
        try {
            InputStream in = Sound.class.getResourceAsStream("/snd/" + file + ".wav");
            if (in == null) return null;
            p = Manager.createPlayer(in, "audio/x-wav");
            p.realize();
            p.prefetch();
            players.put(file, p);
        } catch (Throwable t) {
            Log.e("sound " + file, t);
            p = null;
        }
        return p;
    }

    private static void start(String file, float volume) {
        try {
            long t0 = System.currentTimeMillis();
            boolean fresh = !players.containsKey(file);
            Player p = player(file);
            if (p == null) return;
            long t1 = System.currentTimeMillis();
            if (p.getState() == Player.STARTED) p.stop();
            p.setMediaTime(0);
            VolumeControl vc = (VolumeControl) p.getControl("VolumeControl");
            if (vc != null) vc.setLevel((int) (Math.min(1f, volume) * 100));
            p.start();
            // mcj.debug: сколько ушло на создание плеера и на запуск (задержка звука)
            if (Prof.on) Log.i("SND " + file + (fresh ? " new " + (t1 - t0) : "") + " start " + (System.currentTimeMillis() - t1));
        } catch (Throwable t) {
            Log.e("sound " + file, t);
            players.remove(file);
        }
    }

    public static void play(String name, float volume) {
        if (!ok || !enabled || volume <= 0) return;
        String base = name.substring(name.lastIndexOf('.') + 1);
        Vector v = (Vector) variants.get(base);
        String file;
        if (v != null) file = (String) v.elementAt(rnd.nextInt(v.size()));
        else {
            // точное имя с цифрой на конце (random.pop2)
            int k = base.length();
            while (k > 0 && Character.isDigit(base.charAt(k - 1))) k--;
            Vector vv = (Vector) variants.get(base.substring(0, k));
            if (vv == null || !vv.contains(base)) return;
            file = base;
        }
        synchronized (lock) {
            // переполнение (поток звука занят) — самый старый запрос теряется
            if (qCount == qFile.length) {
                qHead = (qHead + 1) % qFile.length;
                qCount--;
            }
            int i = (qHead + qCount) % qFile.length;
            qFile[i] = file;
            qVol[i] = volume;
            qCount++;
            lock.notify();
        }
    }
}
