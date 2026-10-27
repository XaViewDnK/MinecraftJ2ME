import javax.microedition.lcdui.Image;
import javax.microedition.m3g.Image2D;

/**
 * Картинка ARGB в сжатом виде — байт-индекс на пиксель и палитра (замеры MemoryView 2026-10-14: LCDUI-картинки
 * держали 4.2 МБ хипа, текстуры M3G — 1.5 МБ, многие в двух-трёх копиях; Image всегда 4 байта на пиксель).
 * До 256 цветов на всю картинку — одна палитра, иначе — своя палитра у каждого блока 16x16 (в блоке не больше
 * 256 пикселей, значит, и цветов). Без потерь: пиксели те же, у полностью прозрачных обнуляется только RGB.
 *
 * Для M3G палитровая картинка отдаётся в Image2D как есть (индексы + палитра, вчетверо меньше RGBA).
 */
public final class Pix {
    public final int w, h;
    /** индексы пикселей, строка за строкой */
    private final byte[] idx;
    /** палитра: общая или подряд палитры блоков */
    private final int[] pal;
    /** смещение палитры блока в pal; null — палитра общая */
    private final int[] bOff;
    private final int bw;

    public Pix(Image im) {
        this(im, null, im.getWidth(), im.getHeight());
    }

    public Pix(int[] px, int w, int h) {
        this(null, px, w, h);
    }

    /** пиксели из картинки im полосами по 16 строк (без копии всей картинки в int[]) или из массива px */
    private Pix(Image im, int[] px, int w, int h) {
        this.w = w;
        this.h = h;
        bw = (w + 15) >> 4;
        idx = new byte[w * h];
        int[] strip = px != null ? null : new int[w * 16];
        // проход 1: цветов на всю картинку не больше 256?
        int[] keys = new int[1024], vals = new int[1024];
        boolean[] used = new boolean[1024];
        int n = 0;
        boolean global = true;
        for (int y0 = 0; y0 < h && global; y0 += 16) {
            int sh = Math.min(16, h - y0);
            int[] s = rows(im, px, strip, y0, sh);
            int base = px != null ? y0 * w : 0;
            for (int i = 0, e = w * sh; i < e; i++) {
                int c = norm(s[base + i]);
                int hsl = hash(c);
                while (used[hsl] && keys[hsl] != c) hsl = (hsl + 1) & 1023;
                if (used[hsl]) continue;
                if (n == 256) {
                    global = false;
                    break;
                }
                used[hsl] = true;
                keys[hsl] = c;
                vals[hsl] = n++;
            }
        }
        if (global) {
            pal = new int[n];
            for (int i = 0; i < 1024; i++) if (used[i]) pal[vals[i]] = keys[i];
            bOff = null;
            for (int y0 = 0; y0 < h; y0 += 16) {
                int sh = Math.min(16, h - y0);
                int[] s = rows(im, px, strip, y0, sh);
                int base = px != null ? y0 * w : 0;
                for (int i = 0, e = w * sh; i < e; i++) {
                    int c = norm(s[base + i]);
                    int hsl = hash(c);
                    while (keys[hsl] != c || !used[hsl]) hsl = (hsl + 1) & 1023;
                    idx[y0 * w + i] = (byte) vals[hsl];
                }
            }
            return;
        }
        // палитры блоков 16x16
        int bh = (h + 15) >> 4;
        bOff = new int[bw * bh];
        int[] p = new int[4096];
        int np = 0;
        for (int by = 0; by < bh; by++) {
            int y0 = by << 4, sh = Math.min(16, h - y0);
            int[] s = rows(im, px, strip, y0, sh);
            int base = px != null ? y0 * w : 0;
            for (int bx = 0; bx < bw; bx++) {
                for (int i = 0; i < 1024; i++) used[i] = false;
                int off = np, nb = 0;
                int x0 = bx << 4, sw = Math.min(16, w - x0);
                for (int yy = 0; yy < sh; yy++)
                    for (int xx = 0; xx < sw; xx++) {
                        int c = norm(s[base + yy * w + x0 + xx]);
                        int hsl = hash(c);
                        while (used[hsl] && keys[hsl] != c) hsl = (hsl + 1) & 1023;
                        if (!used[hsl]) {
                            used[hsl] = true;
                            keys[hsl] = c;
                            vals[hsl] = nb++;
                            if (np == p.length) {
                                int[] q = new int[p.length * 2];
                                System.arraycopy(p, 0, q, 0, np);
                                p = q;
                            }
                            p[np++] = c;
                        }
                        idx[(y0 + yy) * w + x0 + xx] = (byte) vals[hsl];
                    }
                bOff[by * bw + bx] = off;
            }
        }
        pal = new int[np];
        System.arraycopy(p, 0, pal, 0, np);
    }

    private static int[] rows(Image im, int[] px, int[] strip, int y0, int sh) {
        if (px != null) return px;
        im.getRGB(strip, 0, im.getWidth(), 0, y0, im.getWidth(), sh);
        return strip;
    }

    private static int norm(int c) {
        return (c >>> 24) == 0 ? 0 : c;
    }

    private static int hash(int c) {
        return ((c * 0x9E3779B1) >>> 22) & 1023;
    }

    /** пиксель ARGB */
    public int get(int x, int y) {
        int i = idx[y * w + x] & 255;
        return bOff == null ? pal[i] : pal[bOff[(y >> 4) * bw + (x >> 4)] + i];
    }

    /** пиксели ARGB прямоугольника (x, y, rw, rh) */
    public int[] region(int x, int y, int rw, int rh) {
        int[] p = new int[rw * rh];
        for (int yy = 0; yy < rh; yy++)
            for (int xx = 0; xx < rw; xx++) p[yy * rw + xx] = get(x + xx, y + yy);
        return p;
    }

    /** Текстура M3G: палитровая, если цветов не больше 256, иначе RGBA (временный буфер на время создания). */
    public Image2D image2D() {
        if (bOff == null) {
            byte[] pb = new byte[pal.length * 4];
            for (int i = 0; i < pal.length; i++) put(pb, i * 4, pal[i]);
            Image2D im = new Image2D(Image2D.RGBA, w, h, idx, pb);
            if (MemDbg.on) MemDbg.i2d(im, "Pix.pal" + pal.length, w * h + pb.length, idx, pb);
            return im;
        }
        byte[] b = new byte[w * h * 4];
        for (int y = 0, o = 0; y < h; y++)
            for (int x = 0; x < w; x++, o += 4) put(b, o, get(x, y));
        Image2D im = new Image2D(Image2D.RGBA, w, h, b);
        if (MemDbg.on) MemDbg.i2d(im, "Pix.rgba", b.length, b, null);
        return im;
    }

    private static void put(byte[] b, int o, int c) {
        b[o] = (byte) (c >> 16);
        b[o + 1] = (byte) (c >> 8);
        b[o + 2] = (byte) c;
        b[o + 3] = (byte) (c >>> 24);
    }

    /** байт в хипе (для замеров) */
    public int bytes() {
        return idx.length + pal.length * 4 + (bOff != null ? bOff.length * 4 : 0);
    }
}
