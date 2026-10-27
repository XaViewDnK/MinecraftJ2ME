import java.lang.ref.WeakReference;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.lcdui.*;
import javax.microedition.m3g.Image2D;

/**
 * Ресурсы и рисование интерфейса. Все координаты — в пикселях GUI, как в 0.6.1 (экран / Gui::GuiScale);
 * масштаб k дробный и крупнее оригинального (см. MC.guiScale). Картинки увеличиваются заранее под k.
 */
public final class R {
    public static float k = 2f;          // пикселей экрана на пиксель GUI
    public static int gw = 320, gh = 240; // размер экрана в пикселях GUI
    public static Nine nineBg, nineSlot, nineSlotMarker, nineFrame, ninePlayerBg;
    public static Tex guiAT, dots, gui, icons, items, touch, sheet, title, frame, worldThumb, sign, fontTex;
    /** terrain.png для иконок блоков (пиксели берутся тайлами по запросу) */
    public static Tex terrain;
    public static Image2D terrain2D;
    public static final int[] charW = new int[256];
    /** шрифт default8.png 128x128 */
    private static Pix font;
    private static final Hashtable texById = new Hashtable();
    private static int texIds;

    /**
     * Текстура интерфейса: пиксели в сжатом виде (Pix — байт на пиксель вместо четырёх у Image). Целых увеличенных
     * копий не держится: на экран идут только нужные куски, увеличенные под k (кэш в blit/stretchedPx).
     */
    public static final class Tex {
        public final int id, w, h;
        /** пиксели; у текстур из файла (path) или производных (atOf) выгружаются, когда не нужны (dropTextures) */
        private Pix pix;
        private final String path;
        /** производная от gui.png: alpha-test для хотбара */
        private final Tex atOf;
        private long used;
        /** нужна HUD в игре — по простою не выгружается (перезагрузка png 256x256 — всплеск ~0.5 МБ) */
        boolean hud;

        Tex(Pix pix) {
            this(pix, null, null);
        }

        private Tex(Pix pix, String path, Tex atOf) {
            this.pix = pix;
            this.path = path;
            this.atOf = atOf;
            this.w = pix.w;
            this.h = pix.h;
            id = ++texIds;
            used = System.currentTimeMillis();
            if (path != null || atOf != null) texs.addElement(new WeakReference(this));
        }

        Pix pix() {
            used = System.currentTimeMillis();
            if (pix == null) pix = path != null ? new Pix(img(path)) : alphaTested(atOf);
            return pix;
        }

        /** пиксели ARGB прямоугольника (x, y, rw, rh) */
        int[] region(int x, int y, int rw, int rh) {
            return pix().region(x, y, rw, rh);
        }
    }

    /** выгружаемые текстуры (слабые ссылки: экраны держат свои Tex сами) */
    private static final Vector texs = new Vector();

    public static Tex tex(String path) {
        return new Tex(new Pix(img(path)), path, null);
    }

    /** хотбар рисуется без смешивания, с alpha-test: полупрозрачное — непрозрачно, прозрачное — дырка */
    private static Pix alphaTested(Tex t) {
        int[] at = t.region(0, 0, t.w, t.h);
        for (int i = 0; i < at.length; i++) at[i] = (at[i] >>> 24) > 25 ? at[i] | 0xFF000000 : 0;
        return new Pix(at, t.w, t.h);
    }

    /**
     * Выгрузить пиксели текстур интерфейса (всех или не нужных дольше idleMs) и кэши кусков из них — перед созданием
     * и загрузкой мира (пик памяти) и в игре. Нужное снова загрузится из png при первом рисовании.
     */
    public static void dropTextures(long idleMs) {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (int i = texs.size() - 1; i >= 0; i--) {
            Tex t = (Tex) ((WeakReference) texs.elementAt(i)).get();
            if (t == null) texs.removeElementAt(i);
            else if (t.pix != null && now - t.used >= idleMs && (idleMs == 0 || !t.hud)) {
                t.pix = null;
                any = true;
            }
        }
        if (idleMs == 0) {
            blitCache.clear();
            stretchCache.clear();
            iconCache.clear();
            mapCache.clear();
            tileCache.clear();
        }
        if (any && MemDbg.on) Log.i("MEM textures dropped (idle " + idleMs + ")");
    }

    private static final Hashtable image2D = new Hashtable();

    /**
     * Общая текстура M3G по пути: одна Image2D на картинку (раньше items.png создавалась трижды — частицы, рука,
     * предметы на земле). Палитровая, если цветов не больше 256.
     */
    public static Image2D image2D(String path) {
        Image2D im = (Image2D) image2D.get(path);
        if (im == null) {
            if (path.equals("/terrain.png")) im = terrain2D;
            else if (path.equals("/gui/items.png")) im = items.pix().image2D();
            else if (path.equals("/font/default8.png")) im = font.image2D();
            else im = new Pix(img(path)).image2D();
            image2D.put(path, im);
        }
        return im;
    }

    public static void load() {
        terrain = tex("/terrain.png");
        terrain2D = terrain.pix().image2D();
        gui = tex("/gui/gui.png");
        guiAT = new Tex(alphaTested(gui), null, gui);
        terrain.hud = gui.hud = guiAT.hud = true;
        dots = tex("/gui/dots.png");
        icons = tex("/gui/icons.png");
        items = tex("/gui/items.png");
        icons.hud = items.hud = true;
        touch = tex("/gui/touchgui.png");
        sheet = tex("/gui/spritesheet.png");
        title = tex("/gui/title.png");
        frame = tex("/gui/itemframe.png");
        worldThumb = tex("/gui/default_world.png");
        sign = tex("/item/sign.png");
        // NinePatch-и экранов 0.6.1 из spritesheet.png
        nineBg = new Nine(sheet, 0, 0, 16, 16, 4, 4);
        nineSlot = new Nine(sheet, 0, 32, 8, 8, 3, 3);
        nineSlotMarker = new Nine(sheet, 10, 42, 16, 16, 5, 5);
        nineFrame = new Nine(sheet, 28, 42, 4, 4, 1, 1).exclude(4);
        ninePlayerBg = new Nine(sheet, 0, 20, 8, 8, 3, 3);
        Image f = img("/font/default8.png");
        int fw = f.getWidth(), fh = f.getHeight();
        int[] px = new int[fw * fh];
        f.getRGB(px, 0, fw, 0, 0, fw, fh);
        int cell = fw / 16;
        if (cell != 8) {
            int[] n = new int[128 * 128];
            for (int y = 0; y < 128; y++)
                for (int x = 0; x < 128; x++) n[y * 128 + x] = px[(y * cell / 8) * fw + x * cell / 8];
            px = n;
        }
        // Font::init: ширина = последний непустой столбец + 2, пробел — 4
        for (int c = 0; c < 256; c++) {
            int cx = (c & 15) * 8, cy = (c >> 4) * 8;
            int x = 7;
            for (; x >= 0; x--) {
                boolean empty = true;
                for (int y = 0; y < 8 && empty; y++) if ((px[(cy + y) * 128 + cx + x] >>> 24) > 0) empty = false;
                if (!empty) break;
            }
            if (c == ' ') x = 2;
            charW[c] = x + 2;
        }
        font = new Pix(px, 128, 128);
    }

    /** Новый масштаб: сбросить увеличенные копии. */
    public static void setScale(float scale, int screenW, int screenH) {
        if (scale != k) {
            stretchCache.clear();
            blitCache.clear();
            mapCache.clear();
            iconCache.clear();
            glyphCache.clear();
            dirtCache.clear();
        }
        k = scale;
        gw = (int) (screenW / k);
        gh = (int) (screenH / k);
    }

    public static Image img(String path) {
        try {
            Image im = Image.createImage(path);
            if (MemDbg.on) MemDbg.img(im, "R.img" + path);
            return im;
        } catch (Exception e) {
            Log.e("img " + path, e);
            return Image.createImage(1, 1);
        }
    }

    /** Ближайший сосед: тексел t занимает пиксели [round(t*k), round((t+1)*k)). */
    static Image scaleImage(int[] in, int w, int h, float kk) {
        int W = Math.max(1, (int) (w * kk + 0.5f)), H = Math.max(1, (int) (h * kk + 0.5f));
        int[] mx = map(w, W, kk), my = map(h, H, kk);
        int[] out = new int[W * H];
        for (int y = 0; y < H; y++) {
            int sy = my[y] * w, oy = y * W;
            for (int x = 0; x < W; x++) out[oy + x] = in[sy + mx[x]];
        }
        return Image.createRGBImage(out, W, H, true);
    }

    private static int[] map(int n, int N, float kk) {
        int[] m = new int[N];
        for (int t = 0; t < n; t++) {
            int a = (int) (t * kk + 0.5f), b = Math.min(N, (int) ((t + 1) * kk + 0.5f));
            for (int i = a; i < b; i++) m[i] = t;
        }
        for (int i = 1; i < N; i++) if (m[i] < m[i - 1]) m[i] = m[i - 1];
        return m;
    }

    /** Пиксель экрана для координаты GUI. */
    public static int px(float v) {
        return (int) Math.floor(v * k + 0.5f);
    }

    private static int b(int t) {
        return (int) (t * k + 0.5f);
    }

    // ---- картинки ----

    private static final Hashtable blitCache = new Hashtable(), mapCache = new Hashtable();

    /** Тексель для пикселя целой увеличенной картинки длиной n текселей при масштабе kk (как в scaleImage). */
    private static int[] scaleMap(int n, float kk) {
        Long key = new Long(((long) Float.floatToIntBits(kk) << 32) | n);
        int[] m = (int[]) mapCache.get(key);
        if (m == null) {
            m = map(n, Math.max(1, (int) (n * kk + 0.5f)), kk);
            mapCache.put(key, m);
        }
        return m;
    }

    /**
     * Кусок текстуры 1:1 (тексель = пиксель GUI). Пиксели те же, что у куска целой картинки, увеличенной под k,
     * но в хипе держится только сам кусок.
     */
    public static void blit(Graphics g, Tex t, float x, float y, int u, int v, int w, int h) {
        if (w <= 0 || h <= 0) return;
        int su = b(u), sv = b(v), sw = b(u + w) - su, sh = b(v + h) - sv;
        if (sw <= 0 || sh <= 0) return;
        Long key = new Long(((long) t.id << 52) | ((long) u << 43) | ((long) v << 34) | ((long) w << 25) | ((long) h << 16));
        Image im = (Image) blitCache.get(key);
        if (im == null) {
            int[] mx = scaleMap(t.w, k), my = scaleMap(t.h, k);
            int[] src = t.region(u, v, w, h);
            int[] out = new int[sw * sh];
            for (int yy = 0; yy < sh; yy++) {
                int ty = Math.min(h - 1, Math.max(0, my[Math.min(my.length - 1, sv + yy)] - v));
                for (int xx = 0; xx < sw; xx++) {
                    int tx = Math.min(w - 1, Math.max(0, mx[Math.min(mx.length - 1, su + xx)] - u));
                    out[yy * sw + xx] = src[ty * w + tx];
                }
            }
            im = Image.createRGBImage(out, sw, sh, true);
            if (MemDbg.on) MemDbg.img(im, "R.blit#" + t.id);
            if (blitCache.size() > 300) blitCache.clear();
            blitCache.put(key, im);
        }
        if (MemDbg.on) MemDbg.drawn(im);
        g.drawImage(im, px(x), px(y), Graphics.TOP | Graphics.LEFT);
    }

    private static final Hashtable stretchCache = new Hashtable();

    /** Кусок текстуры (u,v,uw,vh), растянутый в прямоугольник GUI (x,y,w,h). */
    public static void blit(Graphics g, Tex t, float x, float y, float w, float h, int u, int v, int uw, int vh) {
        int X = px(x), Y = px(y), W = px(x + w) - X, H = px(y + h) - Y;
        stretchedPx(g, t, X, Y, W, H, u, v, uw, vh, 255, 255);
    }

    /** То же с прозрачностью alpha и множителем цвета mul (glColor), 255 — без изменений. */
    public static void blit(Graphics g, Tex t, float x, float y, float w, float h, int u, int v, int uw, int vh, int alpha, int mul) {
        int X = px(x), Y = px(y), W = px(x + w) - X, H = px(y + h) - Y;
        stretchedPx(g, t, X, Y, W, H, u, v, uw, vh, alpha, mul);
    }

    /** Растянутый кусок в пикселях экрана; alpha — прозрачность, mul — множитель цвета (255 — без изменений). */
    public static void stretchedPx(Graphics g, Tex t, int X, int Y, int W, int H, int u, int v, int uw, int vh, int alpha, int mul) {
        if (W <= 0 || H <= 0 || uw <= 0 || vh <= 0) return;
        Long key = new Long(((long) t.id << 58) | ((long) u << 49) | ((long) v << 40) | ((long) uw << 31) | ((long) vh << 22)
                | ((long) W << 11) | H);
        Hashtable cache = stretchCache;
        if (alpha != 255 || mul != 255) {
            Integer ak = new Integer(alpha | (mul << 8));
            cache = (Hashtable) stretchCache.get(ak);
            if (cache == null) stretchCache.put(ak, cache = new Hashtable());
        }
        Image im = (Image) cache.get(key);
        if (im == null) {
            int[] out = new int[W * H];
            int[] src = t.region(u, v, uw, vh);
            boolean down = W < uw || H < vh;
            for (int yy = 0; yy < H; yy++) {
                int sy = yy * vh / H;
                for (int xx = 0; xx < W; xx++) {
                    // уменьшение — усреднение по области (как фильтрация картинок на Android), иначе ближайший тексель
                    int c = down ? average(src, uw, xx * uw / W, sy, (xx + 1) * uw / W, (yy + 1) * vh / H)
                            : src[sy * uw + xx * uw / W];
                    if (alpha < 255) c = ((((c >>> 24) * alpha) / 255) << 24) | (c & 0xFFFFFF);
                    if (mul < 255) c = (c & 0xFF000000) | ((((c >> 16) & 255) * mul / 255) << 16) | ((((c >> 8) & 255) * mul / 255) << 8) | ((c & 255) * mul / 255);
                    out[yy * W + xx] = c;
                }
            }
            im = Image.createRGBImage(out, W, H, true);
            if (MemDbg.on) MemDbg.img(im, "R.stretch");
            if (cache.size() > 600) cache.clear();
            cache.put(key, im);
        }
        if (MemDbg.on) MemDbg.drawn(im);
        g.drawImage(im, X, Y, Graphics.TOP | Graphics.LEFT);
    }

    /** Средний цвет текселей [x0,x1) x [y0,y1) куска шириной sw с учётом прозрачности. */
    private static int average(int[] px, int sw, int x0, int y0, int x1, int y1) {
        if (x1 <= x0) x1 = x0 + 1;
        if (y1 <= y0) y1 = y0 + 1;
        if (x1 - x0 == 1 && y1 - y0 == 1) return px[y0 * sw + x0];
        int a = 0, r = 0, gg = 0, b = 0, n = 0;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++) {
                int c = px[y * sw + x], ca = c >>> 24;
                a += ca;
                r += ((c >> 16) & 255) * ca;
                gg += ((c >> 8) & 255) * ca;
                b += (c & 255) * ca;
                n++;
            }
        if (a == 0) return 0;
        return ((a / n) << 24) | ((r / a) << 16) | ((gg / a) << 8) | (b / a);
    }

    // ---- заливки ----

    public static void fill(Graphics g, float x0, float y0, float x1, float y1, int argb) {
        int X0 = px(x0), Y0 = px(y0), X1 = px(x1), Y1 = px(y1);
        fillPx(g, X0, Y0, X1 - X0, Y1 - Y0, argb);
    }

    public static void fillPx(Graphics g, int x, int y, int w, int h, int argb) {
        int a = argb >>> 24;
        if (a == 0 || w <= 0 || h <= 0) return;
        if (a == 255) {
            g.setColor(argb & 0xFFFFFF);
            g.fillRect(x, y, w, h);
        } else MC.fillAlpha(g, x, y, w, h, argb);
    }

    /** GuiComponent::fillGradient: вертикальный градиент ARGB. */
    public static void gradient(Graphics g, float x0, float y0, float x1, float y1, int top, int bottom) {
        int X0 = px(x0), Y0 = px(y0), X1 = px(x1), Y1 = px(y1);
        int h = Y1 - Y0;
        if (h <= 0 || X1 <= X0) return;
        int steps = Math.min(h, 32);
        for (int i = 0; i < steps; i++) {
            int ya = Y0 + h * i / steps, yb = Y0 + h * (i + 1) / steps;
            int c = lerpColor(top, bottom, steps == 1 ? 0 : i * 255 / (steps - 1));
            fillPx(g, X0, ya, X1 - X0, yb - ya, c);
        }
    }

    static int lerpColor(int a, int b, int t) {
        int r = 0;
        for (int s = 0; s < 32; s += 8) {
            int ca = (a >>> s) & 255, cb = (b >>> s) & 255;
            r |= (ca + (cb - ca) * t / 255) << s;
        }
        return r;
    }

    private static final Hashtable dirtCache = new Hashtable();

    /** Screen::renderDirtBackground: gui/background.png, плитка 32 пикселя GUI, затемнение shade (0x40 — меню). */
    public static void dirt(Graphics g, float x0, float y0, float x1, float y1, int shade, float uOff) {
        Integer key = new Integer(shade);
        Image tile = (Image) dirtCache.get(key);
        int T = b(32);
        if (tile == null) {
            Image src = img("/gui/background.png");
            int[] p = new int[16 * 16];
            src.getRGB(p, 0, 16, 0, 0, 16, 16);
            int[] d = new int[32 * 32];
            for (int y = 0; y < 32; y++)
                for (int x = 0; x < 32; x++) {
                    int c = p[(y >> 1) * 16 + (x >> 1)];
                    int r = ((c >> 16) & 255) * shade / 255, gg = ((c >> 8) & 255) * shade / 255, bb = (c & 255) * shade / 255;
                    d[y * 32 + x] = 0xFF000000 | (r << 16) | (gg << 8) | bb;
                }
            tile = scaleImage(d, 32, 32, k);
            if (MemDbg.on) MemDbg.img(tile, "R.dirt");
            T = tile.getWidth();
            dirtCache.put(key, tile);
        }
        T = tile.getWidth();
        int X0 = px(x0), Y0 = px(y0), X1 = px(x1), Y1 = px(y1);
        int cx = g.getClipX(), cy = g.getClipY(), cw = g.getClipWidth(), ch = g.getClipHeight();
        g.clipRect(X0, Y0, X1 - X0, Y1 - Y0);
        int ox = px(uOff) % T;
        if (ox < 0) ox += T;
        for (int y = 0; y < Y1; y += T) {
            if (y + T <= Y0) continue;
            for (int x = -ox; x < X1; x += T) {
                if (x + T <= X0) continue;
                if (MemDbg.on) MemDbg.drawn(tile);
                g.drawImage(tile, x, y, Graphics.TOP | Graphics.LEFT);
            }
        }
        g.setClip(cx, cy, cw, ch);
    }

    // ---- NinePatch (gui/spritesheet.png и др.) ----

    /** NinePatchLayer 0.6.1: источник (sx,sy,sw,sh) с отрезками cx/cy, углы 1:1, края и середина растянуты. */
    public static final class Nine {
        final Tex t;
        final int sx, sy, sw, sh, cx, cy;
        int excluded;

        public Nine(Tex t, int sx, int sy, int sw, int sh, int cx, int cy) {
            this.t = t;
            this.sx = sx;
            this.sy = sy;
            this.sw = sw;
            this.sh = sh;
            this.cx = cx;
            this.cy = cy;
        }

        public Nine exclude(int part) {
            excluded |= 1 << part;
            return this;
        }

        public void draw(Graphics g, float x, float y, float w, float h) {
            float[] xs = { x, x + cx, x + w - cx, x + w };
            float[] ys = { y, y + cy, y + h - cy, y + h };
            int[] us = { sx, sx + cx, sx + sw - cx, sx + sw };
            int[] vs = { sy, sy + cy, sy + sh - cy, sy + sh };
            for (int q = 0; q < 9; q++) {
                if ((excluded & (1 << q)) != 0) continue;
                int xi = q % 3, yi = q / 3;
                int X0 = px(xs[xi]), X1 = px(xs[xi + 1]), Y0 = px(ys[yi]), Y1 = px(ys[yi + 1]);
                stretchedPx(g, t, X0, Y0, X1 - X0, Y1 - Y0, us[xi], vs[yi], us[xi + 1] - us[xi], vs[yi + 1] - vs[yi], 255, 255);
            }
        }
    }

    // ---- шрифт ----

    /**
     * Глифы шрифта по цвету и размеру: раньше на каждый цвет делалась копия всего листа 128x128 (увеличенная под k),
     * в хипе их было больше десятка. Глиф — те же пиксели, что у куска такой копии.
     */
    private static final Hashtable glyphCache = new Hashtable();

    private static Image glyph(int c, int color, float fs, int su, int sv, int sw, int sh, int[] m) {
        Long key = new Long(((long) Float.floatToIntBits(fs) << 32) | ((long) color << 8) | c);
        Image im = (Image) glyphCache.get(key);
        if (im == null) {
            int[] p = new int[sw * sh];
            int r = (color >> 16) & 255, gg = (color >> 8) & 255, bb = color & 255;
            for (int yy = 0; yy < sh; yy++) {
                int ty = m[Math.min(m.length - 1, sv + yy)];
                for (int xx = 0; xx < sw; xx++) {
                    int cc = font.get(m[Math.min(m.length - 1, su + xx)], ty);
                    if ((cc >>> 24) < 128) continue;
                    p[yy * sw + xx] = 0xFF000000 | ((((cc >> 16) & 255) * r / 255) << 16) | ((((cc >> 8) & 255) * gg / 255) << 8) | ((cc & 255) * bb / 255);
                }
            }
            im = Image.createRGBImage(p, sw, sh, true);
            if (MemDbg.on) MemDbg.img(im, "R.glyph");
            if (glyphCache.size() > 600) glyphCache.clear();
            glyphCache.put(key, im);
        }
        return im;
    }

    public static int width(String t) {
        int w = 0;
        for (int i = 0; i < t.length(); i++) {
            int c = t.charAt(i);
            if (c > 255) c = '?';
            w += charW[c];
        }
        return w;
    }

    public static void text(Graphics g, String t, float x, float y, int color) {
        text(g, t, x, y, color, 1f);
    }

    /** Font::draw; fs — размер тексела шрифта в пикселях GUI. */
    public static void text(Graphics g, String t, float x, float y, int color, float fs) {
        color &= 0xFFFFFF;
        float kk = k * fs;
        int[] m = scaleMap(128, kk);
        int Y = px(y);
        int adv = 0;
        for (int i = 0; i < t.length(); i++) {
            int c = t.charAt(i);
            if (c > 255) c = '?';
            int w = charW[c] - 1;
            if (c != 32 && w > 0) {
                int u = (c & 15) * 8, v = (c >> 4) * 8;
                int su = (int) (u * kk + 0.5f), sv = (int) (v * kk + 0.5f);
                int sw = (int) ((u + w) * kk + 0.5f) - su, sh = (int) ((v + 8) * kk + 0.5f) - sv;
                if (sw > 0 && sh > 0) {
                    Image gi = glyph(c, color, fs, su, sv, sw, sh, m);
                    if (MemDbg.on) MemDbg.drawn(gi);
                    g.drawImage(gi, px(x + adv * fs), Y, Graphics.TOP | Graphics.LEFT);
                }
            }
            adv += charW[c];
        }
    }

    /** Font::drawShadow: тень (color & 0xfcfcfc) >> 2 со сдвигом на тексел. */
    public static void shadow(Graphics g, String t, float x, float y, int color) {
        shadow(g, t, x, y, color, 1f);
    }

    public static void shadow(Graphics g, String t, float x, float y, int color, float fs) {
        text(g, t, x + fs, y + fs, (color & 0xFCFCFC) >> 2, fs);
        text(g, t, x, y, color, fs);
    }

    /** drawCenteredString. */
    public static void centered(Graphics g, String t, float cx, float y, int color) {
        shadow(g, t, cx - width(t) / 2, y, color);
    }

    /** Font::drawWordWrap: перенос по словам в ширину w; возвращает высоту. */
    public static int wrapped(Graphics g, String t, float x, float y, float w, int color, boolean shadow) {
        return wrapped(g, t, x, y, w, color, shadow, false);
    }

    /** Перенос по словам, каждая строка по центру ширины w (с тенью). */
    public static int wrappedCentered(Graphics g, String t, float x, float y, float w, int color) {
        return wrapped(g, t, x, y, w, color, true, true);
    }

    private static int wrapped(Graphics g, String t, float x, float y, float w, int color, boolean shadow, boolean center) {
        float yy = y;
        int start = 0;
        while (start < t.length()) {
            int end = start, lastSpace = -1;
            while (end < t.length() && t.charAt(end) != '\n') {
                if (t.charAt(end) == ' ') lastSpace = end;
                if (width(t.substring(start, end + 1)) > w) break;
                end++;
            }
            if (end < t.length() && t.charAt(end) != '\n' && lastSpace > start) end = lastSpace;
            if (end == start) end = start + 1;
            if (g != null) {
                String ln = t.substring(start, end).trim();
                float lx = center ? x + (w - width(ln)) / 2f : x;
                if (shadow) shadow(g, center ? ln : t.substring(start, end), lx, yy, color);
                else text(g, center ? ln : t.substring(start, end), lx, yy, color);
            }
            yy += 8;
            start = end;
            while (start < t.length() && (t.charAt(start) == ' ' || t.charAt(start) == '\n')) start++;
        }
        return (int) (yy - y);
    }

    // ---- предметы ----

    private static final Hashtable iconCache = new Hashtable();
    public static final int NORMAL = 0, DARK = 1, FADED = 2, GHOST = 3;

    /** ItemRenderer::renderGuiItem: иконка 16x16 пикселей GUI. */
    public static void item(Graphics g, int id, int aux, float x, float y) {
        item(g, id, aux, x, y, NORMAL);
    }

    public static void item(Graphics g, int id, int aux, float x, float y, int mode) {
        if (id <= 0) return;
        Integer key = new Integer(id | ((aux & 0xFFF) << 16) | (mode << 28));
        Image im = (Image) iconCache.get(key);
        if (im == null) {
            int N = b(16);
            int[] p;
            if (id >= 256) {
                int ic = Item.iconFor(id, aux);
                int u = (ic & 15) * 16, v = (ic >> 4) * 16;
                p = new int[N * N];
                int[] src = items.region(u, v, 16, 16);
                for (int yy = 0; yy < N; yy++)
                    for (int xx = 0; xx < N; xx++) p[yy * N + xx] = src[(yy * 16 / N) * 16 + xx * 16 / N];
            } else p = blockIcon(id, aux, N);
            for (int i = 0; i < p.length; i++) {
                int c = p[i];
                int a = c >>> 24;
                if (a == 0) continue;
                if (mode == DARK) c = (c & 0xFF000000) | ((c >> 2) & 0x3F3F3F);
                else if (mode == FADED) c = ((a * 0x60 / 255) << 24) | (c & 0xFFFFFF);
                else if (mode == GHOST) c = ((a * 0x33 / 255) << 24) | (c & 0xFFFFFF);
                p[i] = c;
            }
            im = Image.createRGBImage(p, N, N, true);
            if (MemDbg.on) MemDbg.img(im, "R.icon");
            if (iconCache.size() > 200) iconCache.clear();
            iconCache.put(key, im);
        }
        if (MemDbg.on) MemDbg.drawn(im);
        g.drawImage(im, px(x), px(y), Graphics.TOP | Graphics.LEFT);
    }

    /** ItemRenderer::renderGuiItemDecorations: полоска прочности. */
    public static void decorations(Graphics g, int id, int dmg, float x, float y) {
        if (id < 256 || Item.maxDamage[id] <= 0 || dmg <= 0) return;
        int max = Item.maxDamage[id];
        int p = (int) Math.floor(13.5f - dmg * 13.0f / max);
        int cc = (int) Math.floor(255.5f - dmg * 255.0f / max);
        if (cc < 0) cc = 0;
        if (cc > 255) cc = 255;
        int ca = (255 - cc) << 16 | cc << 8, cb = ((255 - cc) / 4) << 16 | (255 / 4) << 8;
        fill(g, x + 2, y + 13, x + 15, y + 14, 0xFF000000);
        fill(g, x + 2, y + 13, x + 14, y + 14, 0xFF000000 | cb);
        if (p > 0) fill(g, x + 2, y + 13, x + 2 + p, y + 14, 0xFF000000 | ca);
    }

    /** Gui::itemCountItoa. */
    public static String countText(int n) {
        return n >= 100 ? "99+" : String.valueOf(n);
    }

    /** Gui::renderSlotText: число предметов мелким шрифтом (2 пикселя экрана на тексел). */
    public static void slotText(Graphics g, int count, float x, float y) {
        if (count <= 1) return;
        shadow(g, countText(count), x, y, 0xCCCCCC, 2f / k);
    }

    private static final Hashtable tileCache = new Hashtable();

    /** пиксели тайла terrain.png (кэш нескольких последних) */
    private static int[] tile(int tex) {
        Integer key = new Integer(tex);
        int[] t = (int[]) tileCache.get(key);
        if (t == null) {
            t = terrain.region((tex & 15) * 16, (tex >> 4) * 16, 16, 16);
            if (tileCache.size() > 32) tileCache.clear();
            tileCache.put(key, t);
        }
        return t;
    }

    private static int sample(int tex, int u, int v) {
        if (u < 0) u = 0; if (u > 15) u = 15;
        if (v < 0) v = 0; if (v > 15) v = 15;
        return tile(tex & 255)[v * 16 + u];
    }

    private static int shade(int c, int k, int tint) {
        int r = ((c >> 16) & 255) * k / 255 * ((tint >> 16) & 255) / 255;
        int g = ((c >> 8) & 255) * k / 255 * ((tint >> 8) & 255) / 255;
        int b = (c & 255) * k / 255 * (tint & 255) / 255;
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** Коробки формы блока для иконки (x0,y0,z0,x1,y1,z1) — как TileRenderer::renderGuiTile 0.6.1. */
    private static float[] guiBoxes(int sh) {
        float w = 2 / 16f, v = 1 / 16f, r = 3 / 16f;
        switch (sh) {
            case Tile.S_SLAB: return new float[] { 0, 0, 0, 1, 0.5f, 1 };
            case Tile.S_SNOW: return new float[] { 0, 0, 0, 1, 0.125f, 1 };
            case Tile.S_TRAPDOOR: return new float[] { 0, 0.5f - r / 2, 0, 1, 0.5f + r / 2, 1 };
            case Tile.S_CACTUS: return new float[] { v, 0, v, 1 - v, 1, 1 - v };
            case Tile.S_STAIRS: return new float[] { 0, 0, 0, 1, 1, 0.5f, 0, 0, 0.5f, 1, 0.5f, 1 };
            case Tile.S_FENCE: return new float[] {
                    0.5f - w, 0, 0, 0.5f + w, 1, w * 2,
                    0.5f - w, 0, 1 - w * 2, 0.5f + w, 1, 1,
                    0.5f - v, 1 - v * 3, -v * 2, 0.5f + v, 1 - v, 1 + v * 2,
                    0.5f - v, 0.5f - v * 3, -v * 2, 0.5f + v, 0.5f - v, 1 + v * 2 };
            case Tile.S_GATE: return new float[] {
                    0.5f - v, 0.3f, 0, 0.5f + v, 1, v * 2,
                    0.5f - v, 0.3f, 1 - v * 2, 0.5f + v, 1, 1,
                    0.5f - v, 0.5f, 0, 0.5f + v, 1 - v, 1 };
        }
        return new float[] { 0, 0, 0, 1, 1, 1 };
    }

    private static int[] blockIcon(int id, int aux, int N) {
        int[] out = new int[N * N];
        int sh = Tile.shape[id];
        int tint = 0xFFFFFF;
        if (Tile.tint[id] == Tile.T_FOLIAGE) tint = id == Tile.LEAVES && (aux & 3) == 1 ? 0x619961 : (id == Tile.LEAVES && (aux & 3) == 2 ? 0x80A755 : 0x48B518);
        if (Tile.tint[id] == Tile.T_GRASS) tint = 0x339933;
        boolean flat = sh == Tile.S_CROSS || sh == Tile.S_TORCH || sh == Tile.S_LADDER || sh == Tile.S_CROPS || sh == Tile.S_LIQUID
                || sh == Tile.S_DOOR || sh == Tile.S_PANE || id == Tile.SIGN;
        if (flat) {
            int t = Tile.getTex(id, 2, aux);
            if (id == Tile.WOOD_DOOR) t = 81 - 16 + 16;
            for (int y = 0; y < N; y++)
                for (int x = 0; x < N; x++) {
                    int c = sample(t, x * 16 / N, y * 16 / N);
                    if ((c >>> 24) < 128) continue;
                    out[y * N + x] = shade(c, 255, tint) | 0xFF000000;
                }
            return out;
        }
        float[] boxes = guiBoxes(sh);
        int topTex = Tile.getTex(id, 1, aux), sideS = Tile.getTex(id, 3, aux), sideE = Tile.getTex(id, 5, aux);
        int topTint = tint, sideTint = tint;
        if (id == Tile.GRASS) { topTex = 204; topTint = 0xFFFFFF; sideTint = 0xFFFFFF; }
        if (id == Tile.FURNACE || id == Tile.CHEST) { sideS = Tile.getTex(id, 3, 3); }
        // изометрия: экран u = (X - Z) * a, v = top + (X + Z) * b + (1 - Y) * h; луч к зрителю (1, 2b/h, 1)
        float a = N * 0.48f, b = a * 0.5f, h = N * 0.54f;
        float top = (N - (2 * b + h)) / 2f;
        float cx = N / 2f, dy = 2 * b / h;
        for (int y = 0; y < N; y++)
            for (int x = 0; x < N; x++) {
                float u = (x + 0.5f - cx) / a, w = y + 0.5f - top;
                // точка луча при Z = 0
                float X0 = u, Y0 = 1f - (w - u * b) / h;
                float bestT = -1e9f;
                int c = 0;
                for (int k = 0; k < boxes.length; k += 6) {
                    float t0 = -1e9f, t1 = 1e9f;
                    float lo, hi;
                    lo = boxes[k] - X0; hi = boxes[k + 3] - X0;
                    if (lo > t0) t0 = lo; if (hi < t1) t1 = hi;
                    lo = (boxes[k + 1] - Y0) / dy; hi = (boxes[k + 4] - Y0) / dy;
                    if (lo > t0) t0 = lo; if (hi < t1) t1 = hi;
                    lo = boxes[k + 2]; hi = boxes[k + 5];
                    if (lo > t0) t0 = lo; if (hi < t1) t1 = hi;
                    if (t0 >= t1 || t1 <= bestT) continue;
                    // грань, через которую луч выходит к зрителю
                    float HX = X0 + t1, HY = Y0 + t1 * dy, HZ = t1;
                    int col;
                    if (Math.abs(HY - boxes[k + 4]) < 1e-4f) {
                        col = sample(topTex, (int) Math.floor(HX * 16), (int) Math.floor(HZ * 16));
                        if ((col >>> 24) < 128) continue;
                        col = shade(col, 255, topTint);
                    } else if (Math.abs(HZ - boxes[k + 5]) < 1e-4f) {
                        col = sample(sideS, (int) Math.floor(HX * 16), (int) Math.floor((1f - HY) * 16));
                        if ((col >>> 24) < 128) continue;
                        col = shade(col, 204, sideTint);
                    } else {
                        col = sample(sideE, (int) Math.floor((1f - HZ) * 16), (int) Math.floor((1f - HY) * 16));
                        if ((col >>> 24) < 128) continue;
                        col = shade(col, 153, sideTint);
                    }
                    bestT = t1;
                    c = col;
                }
                if (c != 0) out[y * N + x] = c | 0xFF000000;
            }
        return out;
    }
}
