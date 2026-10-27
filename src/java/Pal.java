/**
 * Слой мира, сжатый в памяти (приём из MicroCraft): чанк 16x16x128 делится на 32 куска 16x16x4, и каждый кусок
 * хранится в самом коротком виде — одно значение на весь кусок, палитра + 1..7 бит на клетку (сколько нужно
 * для числа значений: 5..8 значений — 3 бита) или сырые значения (8 бит у блоков, 4 у тетрад data и света). Сырые массивы мира занимали 20 МБ — больше, чем весь хип E75 и Asha.
 *
 * Снаружи индексы как в файле MCPE: чанк ch = cx | cz << 4 и клетка i = (x << 11) | (z << 7) | y.
 * Массив куска самоописывающий: [0] — бит на клетку, [1] — занято мест палитры, [2..] — палитра, в конце клетки
 * (128 * бит байт) и байт запаса: клетка может пересекать границу байта, читаются два байта. Кусок подменяется одной записью ссылки — чтение из другого потока (сохранение в pauseApp)
 * не увидит полуготовый кусок.
 */
public final class Pal {
    public static final int NP = 32, CELLS = 1024;
    final int maxBits;
    private final byte[][] arr = new byte[256 * NP][];
    private final byte[] uni = new byte[256 * NP];
    private final int[] tmp = new int[CELLS];
    private final int[] stamp = new int[256], slot = new int[256];
    private final int[] pal = new int[256];
    private int st;
    private int[] exp;

    /** кусок выгруженного чанка (данные лежат только в chunks.dat): [0] = EVB, бит на клетку таким не бывает */
    static final int EVB = 99;
    static final byte[] EV = { (byte) EVB };

    /** подгрузка выгруженного чанка (Level) */
    interface Src {
        void fault(int ch);
    }

    Src src;
    /** отметки «чанк изменён после сохранения» (Level.unsaved) — ставятся при любой записи, в том числе света */
    boolean[] dirty;

    public Pal(int maxBits) {
        this.maxBits = maxBits;
    }

    private void load(int ch) {
        src.fault(ch);
    }

    /** чанк — воздух без подгрузки (файл не читается) */
    void blank(int ch) {
        for (int py = 0; py < NP; py++) fill0((ch << 5) | py, 0);
    }

    /** выгрузить чанк: куски заменяются меткой EV, чтение подгрузит их из файла */
    void evict(int ch) {
        for (int py = 0; py < NP; py++) arr[(ch << 5) | py] = EV;
    }

    /** кусок: чанк и четвёрка по высоте */
    static int key(int ch, int i) {
        return (ch << 5) | ((i & 127) >> 2);
    }

    /** клетка внутри куска: (x << 6) | (z << 2) | (y & 3) */
    static int cell(int i) {
        return ((i >> 5) & 0x3FC) | (i & 3);
    }

    /** индекс клетки чанка по куску py и клетке c */
    static int index(int py, int c) {
        return ((c & ~3) << 5) | (py << 2) | (c & 3);
    }

    public int get(int ch, int i) {
        int k = (ch << 5) | ((i & 127) >> 2);
        byte[] a = arr[k];
        if (a == null) return uni[k] & 255;
        int b = a[0];
        if (b == EVB) {
            load(ch);
            return get(ch, i);
        }
        int c = ((i >> 5) & 0x3FC) | (i & 3);
        int bp = c * b, o = a.length - 1 - (b << 7) + (bp >> 3);
        int v = (((a[o] & 255) | (a[o + 1] << 8)) >> (bp & 7)) & ((1 << b) - 1);
        return b == maxBits ? v : a[2 + v] & 255;
    }

    public void set(int ch, int i, int v) {
        int k = (ch << 5) | ((i & 127) >> 2);
        int c = ((i >> 5) & 0x3FC) | (i & 3);
        byte[] a = arr[k];
        if (a == EV) {
            load(ch);
            a = arr[k];
        }
        if (dirty != null) dirty[ch] = true;
        if (a == null) {
            int u = uni[k] & 255;
            if (u == v) return;
            // 1 бит: палитра [старое, новое]
            a = new byte[2 + 2 + 128 + 1];
            a[0] = 1;
            a[1] = 2;
            a[2] = (byte) u;
            a[3] = (byte) v;
            a[4 + (c >> 3)] = (byte) (1 << (c & 7));
            arr[k] = a;
            return;
        }
        int b = a[0], idx;
        if (b == maxBits) idx = v;
        else {
            int n = a[1] & 255;
            idx = -1;
            for (int p = 0; p < n; p++)
                if ((a[2 + p] & 255) == v) {
                    idx = p;
                    break;
                }
            if (idx < 0) {
                if (n < (1 << b)) {
                    a[2 + n] = (byte) v;
                    a[1] = (byte) (n + 1);
                    idx = n;
                } else {
                    // палитра полна: пересобрать кусок (заодно выкинуть неиспользуемые значения)
                    decode(k, tmp);
                    tmp[c] = v;
                    encode0(k, tmp);
                    return;
                }
            }
        }
        int bp = c * b, o = a.length - 1 - (b << 7) + (bp >> 3), sh = bp & 7;
        int w = ((a[o] & 255) | ((a[o + 1] & 255) << 8)) & ~(((1 << b) - 1) << sh) | (idx << sh);
        a[o] = (byte) w;
        a[o + 1] = (byte) (w >> 8);
    }

    /** весь слой — одно значение */
    public void clear(int v) {
        if (dirty != null) for (int ch = 0; ch < dirty.length; ch++) dirty[ch] = true;
        for (int k = 0; k < arr.length; k++) {
            uni[k] = (byte) v;
            arr[k] = null;
        }
    }

    public boolean uniform(int k) {
        byte[] a = arr[k];
        if (a == EV) {
            load(k >> 5);
            a = arr[k];
        }
        return a == null;
    }

    public int uniformValue(int k) {
        if (arr[k] == EV) load(k >> 5);
        return uni[k] & 255;
    }

    /** весь кусок k — значение v */
    public void fill(int k, int v) {
        if (arr[k] == EV) load(k >> 5);
        if (dirty != null) dirty[k >> 5] = true;
        uni[k] = (byte) v;
        arr[k] = null;
    }

    private void fill0(int k, int v) {
        uni[k] = (byte) v;
        arr[k] = null;
    }

    /** значения клеток куска (порядок cell) */
    public void decode(int k, int[] out) {
        byte[] a = arr[k];
        if (a == EV) {
            load(k >> 5);
            a = arr[k];
        }
        if (a == null) {
            int u = uni[k] & 255;
            for (int c = 0; c < CELLS; c++) out[c] = u;
            return;
        }
        int b = a[0], m = (1 << b) - 1, o = a.length - 1 - (b << 7);
        boolean raw = b == maxBits;
        for (int c = 0, bp = 0; c < CELLS; c++, bp += b) {
            int p = o + (bp >> 3);
            int v = (((a[p] & 255) | (a[p + 1] << 8)) >> (bp & 7)) & m;
            out[c] = raw ? v : a[2 + v] & 255;
        }
    }

    /** записать кусок самым коротким видом */
    public void encode(int k, int[] vals) {
        if (arr[k] == EV) load(k >> 5);
        if (dirty != null) dirty[k >> 5] = true;
        encode0(k, vals);
    }

    /** то же без подгрузки и отметки изменения (загрузка из файла) */
    private void encode0(int k, int[] vals) {
        int s = ++st, n = 0;
        if (s == 0) {
            for (int j = 0; j < 256; j++) stamp[j] = 0;
            s = st = 1;
        }
        for (int c = 0; c < CELLS; c++) {
            int v = vals[c];
            if (stamp[v] != s) {
                stamp[v] = s;
                slot[v] = n;
                pal[n++] = v;
            }
        }
        if (n == 1) {
            fill0(k, vals[0]);
            return;
        }
        int b = 1;
        while ((1 << b) < n) b++;
        boolean raw = b >= maxBits;
        if (raw) b = maxBits;
        int o = raw ? 2 : 2 + (1 << b);
        byte[] a = new byte[o + (b << 7) + 1];
        a[0] = (byte) b;
        if (!raw) {
            a[1] = (byte) n;
            for (int p = 0; p < n; p++) a[2 + p] = (byte) pal[p];
        }
        for (int c = 0, bp = 0; c < CELLS; c++, bp += b) {
            int v = (raw ? vals[c] : slot[vals[c]]) << (bp & 7);
            a[o + (bp >> 3)] |= (byte) v;
            a[o + (bp >> 3) + 1] |= (byte) (v >> 8);
        }
        arr[k] = a;
    }

    /** чанк из сырого массива файла MCPE: блоки — байт на клетку, тетрады — 16384 байта (чётная клетка — младшая) */
    public void importChunk(int ch, byte[] raw, boolean nibbles) {
        int[] t = tmp;
        for (int py = 0; py < NP; py++) {
            for (int c = 0; c < CELLS; c++) {
                int i = index(py, c);
                t[c] = nibbles ? (raw[i >> 1] >> ((i & 1) << 2)) & 15 : raw[i] & 255;
            }
            encode0((ch << 5) | py, t);
        }
    }

    /** чанк в сырой массив файла MCPE */
    public void exportChunk(int ch, byte[] raw, boolean nibbles) {
        // свой буфер (сохранение идёт и из потока pauseApp, tmp занят set); раньше — новый int[1024] на каждый чанк и слой: ~4 МБ мусора за сохранение
        int[] t = exp;
        if (t == null) exp = t = new int[CELLS];
        for (int py = 0; py < NP; py++) {
            decode((ch << 5) | py, t);
            for (int c = 0; c < CELLS; c++) {
                int i = index(py, c);
                if (!nibbles) raw[i] = (byte) t[c];
                else if ((i & 1) == 0) raw[i >> 1] = (byte) ((raw[i >> 1] & 0xF0) | t[c]);
                else raw[i >> 1] = (byte) ((raw[i >> 1] & 0x0F) | (t[c] << 4));
            }
        }
    }

    /** байт на куски (для отладки и замеров) */
    public int bytes() {
        int n = 0;
        for (int k = 0; k < arr.length; k++) if (arr[k] != null && arr[k] != EV) n += arr[k].length + 12;
        return n;
    }
}
