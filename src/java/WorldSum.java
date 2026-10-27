/**
 * Отладка: сверка генерации мира между платформами (оригинал MCPE 0.6.1, J2ME Loader, E6).
 * Контрольные суммы FNV-1a блоков и data по чанкам — тот же алгоритм, что ~/wgen/sums.py по chunks.dat,
 * и суммы математики, на которой держится Gen (Math.sin таблицы, float-операции, шум).
 */
final class WorldSum {
    private static final int FNV0 = 0x811c9dc5;

    private static int fnv(int h, int b) {
        return (h ^ (b & 255)) * 0x01000193;
    }

    private static int fnv4(int h, int v) {
        h = fnv(h, v);
        h = fnv(h, v >> 8);
        h = fnv(h, v >> 16);
        return fnv(h, v >> 24);
    }

    /** Блоки сразу после генерации: строка WSUM с общими суммами и 16 строк WSUMC по 16 чанков (блоки). */
    static void log(Level l) {
        byte[] raw = new byte[32768];
        int bt = FNV0, dt = FNV0;
        StringBuffer sb = new StringBuffer();
        String[] rows = new String[16];
        for (int i = 0; i < 256; i++) {
            l.blocks.exportChunk(i, raw, false);
            int hb = FNV0;
            for (int k = 0; k < 32768; k++) hb = fnv(hb, raw[k]);
            l.data.exportChunk(i, raw, true);
            int hd = FNV0;
            for (int k = 0; k < 16384; k++) hd = fnv(hd, raw[k]);
            bt = fnv4(bt, hb);
            dt = fnv4(dt, hd);
            sb.append(hex(hb)).append(' ');
            if ((i & 15) == 15) {
                rows[i >> 4] = sb.toString();
                sb.setLength(0);
            }
        }
        Log.i("WSUM blocks " + hex(bt) + " data " + hex(dt) + " seed " + l.seed);
        for (int r = 0; r < 16; r++) Log.i("WSUMC " + r + " " + rows[r]);
    }

    /** Математика генератора: Math.sin (вся таблица _sin), float * и /, MT, шум Перлина. */
    static void math() {
        int hs = FNV0;
        for (int i = 0; i < 65536; i++) {
            int b = Float.floatToIntBits(Mth.sinI(i));
            hs = fnv4(hs, b);
        }
        // float: умножение, деление, сложение на «неудобных» значениях
        int hf = FNV0;
        Gen.Rnd r = new Gen.Rnd(20261046);
        for (int i = 0; i < 4096; i++) {
            float a = r.nextFloat() * 1000f - 500f, b = r.nextFloat() * 3f + 0.001f;
            hf = fnv4(hf, Float.floatToIntBits(a * b));
            hf = fnv4(hf, Float.floatToIntBits(a / b));
            hf = fnv4(hf, Float.floatToIntBits(a * b + a / 7f));
            hf = fnv4(hf, Float.floatToIntBits((a - b) * 684.412f / 512f));
            hf = fnv4(hf, (int) (a * 10430.378f) & 65535);
        }
        int hr = FNV0;
        for (int i = 0; i < 1024; i++) hr = fnv4(hr, r.next());
        // шум: PerlinNoise 16 октав на сетке
        Gen.PNoise p = new Gen.PNoise(new Gen.Rnd(20261046), 16);
        float[] buf = p.region(null, 3.5f, 10f, -7.25f, 5, 17, 5, 684.412f, 684.412f, 684.412f);
        int hn = FNV0;
        for (int i = 0; i < buf.length; i++) hn = fnv4(hn, Float.floatToIntBits(buf[i]));
        Log.i("WMATH sin " + hex(hs) + " float " + hex(hf) + " mt " + hex(hr) + " noise " + hex(hn));
    }

    static String hex(int v) {
        String s = Integer.toHexString(v);
        while (s.length() < 8) s = "0" + s;
        return s;
    }
}
