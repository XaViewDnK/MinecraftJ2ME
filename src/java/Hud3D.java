import javax.microedition.m3g.*;

/**
 * HUD в игре через M3G, до releaseTarget (как в MCPE: интерфейс — те же квады OpenGL). На ускорителе (Nokia E6)
 * каждая 2D-картинка поверх 3D дорога: вывод кадра +5..6 мс за HUD, а квады M3G почти даром. На программном M3G
 * (K800i) — наоборот, там HUD остаётся 2D.
 *
 * Пока {@link #on}, функции R (blit, stretchedPx, fillPx, text, item, blitRuns) не рисуют в Graphics, а копят квады
 * в порядке рисования. Нужные куски текстур (хотбар, сердца, иконки, глифы) по первому запросу кладутся полками
 * в один атлас 512x512 — весь HUD рисуется одним render (на E6 каждый вызов со сменой текстуры ~0.4 мс).
 * Где 2D рисует с отсечением по альфе (глифы, прицел, хотбар guiAT), альфа куска в атласе — 0 или 255.
 * M3G-объекты не становятся мусором (см. DynMesh).
 */
final class Hud3D {
    /** HUD этого кадра идёт в M3G */
    static boolean on;
    /** 0 — авто (M3G при аппаратном ускорении), 1 — всегда 2D, 2 — всегда M3G (Link «hud3d», свойство mcj.hud3d) */
    static int mode = -1;
    private static int hw = -1;

    /** TEST — альфа куска сводится к 0/255 (< 128 — дырка), BLEND — как есть */
    static final int TEST = 0, BLEND = 1;

    private static Graphics3D g3d;
    private static int W, H;
    private static final Camera cam = new Camera();
    private static final Transform ident = new Transform();

    private static int n;
    private static short[] pos = new short[64 * 3], uv = new short[64 * 2];
    private static byte[] col = new byte[64 * 4];
    private static final VertexBuffer vb = new VertexBuffer();
    private static VertexArray pa, ta, ca;
    private static int cap;
    private static Appearance app;
    static int calls, quads;

    /** HUD через M3G в этом кадре? */
    static boolean want() {
        if (mode < 0) {
            mode = 0;
            String p = Link.prop("mcj.hud3d");
            if (p != null) try {
                mode = Integer.parseInt(p.trim());
            } catch (NumberFormatException e) {
            }
        }
        if (mode == 1) return false;
        if (mode == 2) return true;
        if (hw < 0) {
            hw = 0;
            try {
                Object a = Graphics3D.getProperties().get("supportAntialiasing");
                if (a instanceof Boolean && ((Boolean) a).booleanValue()) hw = 1;
            } catch (Throwable t) {
            }
            Log.i("HUD3D auto " + (hw > 0 ? "m3g" : "2d"));
        }
        return hw > 0;
    }

    static void begin(Graphics3D g, int w, int h) {
        if (atlas == null) initAtlas();
        g3d = g;
        W = w;
        H = h;
        cam.setParallel(h, (float) w / h, 0.5f, 2f);
        g.setCamera(cam, ident);
        g.resetLights();
        n = 0;
        calls = quads = 0;
        on = true;
    }

    static void end() {
        try {
            if (n > 0) {
                load();
                g3d.render(vb, MeshBuilder.strips(n), app, ident);
                calls++;
            }
        } finally {
            on = false;
            g3d = null;
        }
    }

    private static void load() {
        if (n > cap) {
            if (pa != null) {
                // у буфера все массивы обязаны быть одной длины — сначала отвязать старые
                vb.setColors(null);
                vb.setTexCoords(0, null, 1f, null);
                vb.setPositions(null, 1f, null);
                DynMesh.KEEP.addElement(pa);
                DynMesh.KEEP.addElement(ta);
                DynMesh.KEEP.addElement(ca);
            }
            int k = Math.max(256, cap);
            while (k < n) k *= 2;
            cap = k;
            pa = new VertexArray(k, 3, 2);
            ta = new VertexArray(k, 2, 2);
            ca = new VertexArray(k, 4, 1);
        }
        pa.set(0, n, pos);
        ta.set(0, n, uv);
        ca.set(0, n, col);
        vb.setPositions(pa, 1f, null);
        vb.setTexCoords(0, ta, 1f / A, null);
        vb.setColors(ca);
    }

    // ---- квады ----

    /** кусок (u, v, uw, vh) текстуры t в прямоугольник экрана (X, Y, w, h), цвет ARGB умножается на тексели */
    static void quad(R.Tex t, int X, int Y, int w, int h, int u, int v, int uw, int vh, int argb, int blend) {
        if (w <= 0 || h <= 0 || uw <= 0 || vh <= 0 || t.id >= FONT) return;
        int s = region(t.id, u, v, uw, vh, blend, t, 0, 0);
        if (s < 0) return;
        int ax = s & 0xFFFF, ay = s >>> 16;
        put(X, Y, w, h, ax, ay, ax + uw, ay + vh, argb);
    }

    static void fill(int X, int Y, int w, int h, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        // белый кусок 2x2 в углу атласа: развёртка на стыке двух белых текселей
        put(X, Y, w, h, 1, 1, 1, 1, argb);
    }

    /** глиф шрифта (тексели u, v, uw x 8): прозрачное < 128 отбрасывается, цвет умножается */
    static void glyph(int X, int Y, int w, int h, int u, int v, int uw, int color) {
        int s = region(FONT, u, v, uw, 8, TEST, null, 0, 0);
        if (s < 0) return;
        int ax = s & 0xFFFF, ay = s >>> 16;
        put(X, Y, w, h, ax, ay, ax + uw, ay + 8, 0xFF000000 | color);
    }

    /** иконка блока (пиксели R.blockIconPx, N x N пикселей экрана); цвет умножается (DARK/FADED/GHOST) */
    static void blockIcon(int id, int aux, int N, int X, int Y, int argb) {
        if (N > 128) return;
        int s = region(BLOCK, id, aux & 511, N, N, BLEND, null, id, aux);
        if (s < 0) return;
        int ax = s & 0xFFFF, ay = s >>> 16;
        put(X, Y, N, N, ax, ay, ax + N, ay + N, argb);
    }

    /** иконка предмета (items.png, 16x16) в прямоугольник экрана N x N */
    static void itemIcon(int u, int v, int N, int X, int Y, int argb) {
        quad(R.items, X, Y, N, N, u, v, 16, 16, argb, BLEND);
    }

    private static void put(int X, int Y, int w, int h, int u0, int v0, int u1, int v1, int argb) {
        if ((n + 4) * 3 > pos.length) {
            short[] p = new short[pos.length * 2];
            System.arraycopy(pos, 0, p, 0, n * 3);
            pos = p;
            short[] q = new short[uv.length * 2];
            System.arraycopy(uv, 0, q, 0, n * 2);
            uv = q;
            byte[] c = new byte[col.length * 2];
            System.arraycopy(col, 0, c, 0, n * 4);
            col = c;
        }
        // экран: x вправо от центра, y вверх; полоса из 4 вершин — (x0,y0) (x1,y0) (x0,y1) (x1,y1)
        int x0 = X - W / 2, x1 = x0 + w, y0 = H / 2 - Y, y1 = y0 - h;
        vert(x0, y0, u0, v0, argb);
        vert(x1, y0, u1, v0, argb);
        vert(x0, y1, u0, v1, argb);
        vert(x1, y1, u1, v1, argb);
        quads++;
    }

    private static void vert(int x, int y, int u, int v, int argb) {
        int p = n * 3;
        pos[p] = (short) x;
        pos[p + 1] = (short) y;
        pos[p + 2] = -1;
        uv[n * 2] = (short) u;
        uv[n * 2 + 1] = (short) v;
        int c = n * 4;
        col[c] = (byte) (argb >> 16);
        col[c + 1] = (byte) (argb >> 8);
        col[c + 2] = (byte) argb;
        col[c + 3] = (byte) (argb >>> 24);
        n++;
    }

    // ---- атлас ----

    /** сторона атласа; «текстуры» глифов и иконок блоков — особые номера выше id R.Tex */
    private static final int A = 512, FONT = 65534, BLOCK = 65535;
    private static Image2D atlas;
    /** открытая адресация: ключ куска -> (y << 16) | x в атласе */
    private static long[] keys = new long[1024];
    private static int[] vals = new int[1024];
    private static int used;
    /** полки: текущая позиция и высота полки */
    private static int px, py, rowH;
    static int resets;

    private static void initAtlas() {
        atlas = new Image2D(Image2D.RGBA, A, A);
        Texture2D t = new Texture2D(atlas);
        t.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        t.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        t.setBlending(Texture2D.FUNC_MODULATE);
        app = new Appearance();
        app.setTexture(0, t);
        CompositingMode cm = new CompositingMode();
        cm.setBlending(CompositingMode.ALPHA);
        cm.setDepthTestEnable(false);
        cm.setDepthWriteEnable(false);
        app.setCompositingMode(cm);
        PolygonMode pm = new PolygonMode();
        pm.setCulling(PolygonMode.CULL_NONE);
        pm.setShading(PolygonMode.SHADE_FLAT);
        app.setPolygonMode(pm);
        byte[] white = new byte[2 * 2 * 4];
        for (int i = 0; i < white.length; i++) white[i] = (byte) 255;
        atlas.set(0, 0, 2, 2, white);
        clearAtlas();
    }

    private static void clearAtlas() {
        for (int i = 0; i < keys.length; i++) keys[i] = 0;
        used = 0;
        px = 2;
        py = 0;
        rowH = 2;
    }

    /** место куска в атласе ((y << 16) | x), при первом запросе — копия пикселей; -1 — не влезает */
    private static int region(int tex, int u, int v, int w, int h, int blend, R.Tex t, int id, int aux) {
        // 16 бит текстуры | 9 бит u, v, w, h | бит TEST
        long key = (1L << 62) | ((long) tex << 46) | ((long) u << 37) | ((long) v << 28) | ((long) w << 19) | ((long) h << 10)
                | (blend == TEST ? 1 : 0);
        int m = keys.length - 1;
        int i = (int) (key ^ (key >>> 21) ^ (key >>> 40)) * 0x9E3779B1 >>> 22 & m;
        for (long k; (k = keys[i]) != 0; i = (i + 1) & m) if (k == key) return vals[i];
        if (w > A || h > A) return -1;
        if (px + w > A) {
            py += rowH;
            px = 0;
            rowH = 0;
        }
        if (py + h > A || used * 2 > keys.length) {
            // атлас полон: начать заново (куски этого кадра, уже положенные в буфер, могут показать новое — один кадр)
            clearAtlas();
            resets++;
            if (px + w > A) {
                py += rowH;
                px = 0;
                rowH = 0;
            }
            i = (int) (key ^ (key >>> 21) ^ (key >>> 40)) * 0x9E3779B1 >>> 22 & m;
            while (keys[i] != 0) i = (i + 1) & m;
        }
        int x = px, y = py;
        px += w;
        if (h > rowH) rowH = h;
        int[] p = tex == BLOCK ? R.blockIconPx(id, aux, w) : (tex == FONT ? R.fontPix().region(u, v, w, h) : t.region(u, v, w, h));
        byte[] b = new byte[w * h * 4];
        boolean bin = blend == TEST;
        for (int j = 0, o = 0; j < w * h; j++, o += 4) {
            int c = p[j], a = c >>> 24;
            if (bin) a = a >= 128 ? 255 : 0;
            b[o] = (byte) (c >> 16);
            b[o + 1] = (byte) (c >> 8);
            b[o + 2] = (byte) c;
            b[o + 3] = (byte) a;
        }
        atlas.set(x, y, w, h, b);
        keys[i] = key;
        vals[i] = (y << 16) | x;
        used++;
        return vals[i];
    }
}
