import javax.microedition.m3g.*;

/**
 * Сборка квадов в массивы M3G. Позиции — short в 1/16 блока, текстурные координаты — пиксели атласа 256x256,
 * цвета RGBA. Квад пишется как отдельная полоса из 4 вершин (a, b, d, c) — без индексного массива.
 */
public final class MeshBuilder {
    // начинаются с малого и растут (раньше каждый строитель сразу занимал ~60 КБ, частицам и рамкам столько не нужно)
    public short[] pos = new short[3 * 64];
    public byte[] col = new byte[4 * 64];
    public short[] uv = new short[2 * 64];
    public int verts;
    /** по квадам: затенение << 8 | код света и альфа << 24 | оттенок — для пересчёта цвета (LevelRenderer.recolor) */
    public short[] qLight = new short[16];
    public int[] qTint = new int[16];
    /** параметры цвета следующих квадов (задаёт LevelRenderer.color) */
    public short curLight;
    public int curTint;
    public static final int MAX_VERTS = 65532;

    public void clear() {
        verts = 0;
    }

    private void ensure(int n) {
        int need = verts + n;
        if (need * 3 <= pos.length) return;
        int cap = pos.length / 3;
        while (cap < need) cap *= 2;
        short[] p = new short[cap * 3];
        System.arraycopy(pos, 0, p, 0, verts * 3);
        pos = p;
        byte[] c = new byte[cap * 4];
        System.arraycopy(col, 0, c, 0, verts * 4);
        col = c;
        short[] u = new short[cap * 2];
        System.arraycopy(uv, 0, u, 0, verts * 2);
        uv = u;
    }

    private void v(int x, int y, int z, int u, int vv, int rgba) {
        int i = verts;
        pos[i * 3] = (short) x;
        pos[i * 3 + 1] = (short) y;
        pos[i * 3 + 2] = (short) z;
        col[i * 4] = (byte) (rgba >> 16);
        col[i * 4 + 1] = (byte) (rgba >> 8);
        col[i * 4 + 2] = (byte) rgba;
        col[i * 4 + 3] = (byte) (rgba >>> 24);
        uv[i * 2] = (short) u;
        uv[i * 2 + 1] = (short) vv;
        verts = i + 1;
    }

    /** Квад с вершинами a,b,c,d против часовой стрелки (если смотреть с лицевой стороны). */
    public void quad(int ax, int ay, int az, int au, int av, int ac,
                     int bx, int by, int bz, int bu, int bv, int bc,
                     int cx, int cy, int cz, int cu, int cv, int cc,
                     int dx, int dy, int dz, int du, int dv, int dc) {
        if (verts + 4 > MAX_VERTS) return;
        ensure(4);
        quadInfo(verts >> 2, curLight, curTint);
        v(ax, ay, az, au, av, ac);
        v(bx, by, bz, bu, bv, bc);
        v(dx, dy, dz, du, dv, dc);
        v(cx, cy, cz, cu, cv, cc);
    }

    /** Дописать n вершин из чужих массивов (с вершины from) со сдвигом позиций. */
    public void append(short[] p, byte[] c, short[] u, int from, int n, int dx, int dy, int dz) {
        ensure(n);
        int v = verts;
        for (int i = 0; i < n; i++) {
            int s3 = (from + i) * 3, d3 = (v + i) * 3;
            pos[d3] = (short) (p[s3] + dx);
            pos[d3 + 1] = (short) (p[s3 + 1] + dy);
            pos[d3 + 2] = (short) (p[s3 + 2] + dz);
        }
        System.arraycopy(c, from * 4, col, v * 4, n * 4);
        System.arraycopy(u, from * 2, uv, v * 2, n * 2);
        verts = v + n;
    }

    /** Дописать обратные стороны квадов, начиная с вершины from (двусторонние плоскости при отсечении граней). */
    public void backFaces(int from) {
        int end = verts;
        if (end + (end - from) > MAX_VERTS) return;
        ensure(end - from);
        for (int q = from; q < end; q += 4) {
            // полоса (v0 v1 v2 v3) -> (v1 v0 v3 v2): тот же квад с обратным обходом
            quadInfo(verts >> 2, qLight[q >> 2], qTint[q >> 2]);
            copy(q + 1);
            copy(q);
            copy(q + 3);
            copy(q + 2);
        }
    }

    private void quadInfo(int q, short light, int tint) {
        if (q >= qLight.length) {
            short[] l = new short[qLight.length * 2];
            System.arraycopy(qLight, 0, l, 0, qLight.length);
            qLight = l;
            int[] t = new int[qTint.length * 2];
            System.arraycopy(qTint, 0, t, 0, qTint.length);
            qTint = t;
        }
        qLight[q] = light;
        qTint[q] = tint;
    }

    private void copy(int i) {
        int j = verts;
        pos[j * 3] = pos[i * 3];
        pos[j * 3 + 1] = pos[i * 3 + 1];
        pos[j * 3 + 2] = pos[i * 3 + 2];
        col[j * 4] = col[i * 4];
        col[j * 4 + 1] = col[i * 4 + 1];
        col[j * 4 + 2] = col[i * 4 + 2];
        col[j * 4 + 3] = col[i * 4 + 3];
        uv[j * 2] = uv[i * 2];
        uv[j * 2 + 1] = uv[i * 2 + 1];
        verts = j + 1;
    }

    /** Собрать буфер вершин M3G (positions scale 1/16, bias = origin). */
    public VertexBuffer build(float ox, float oy, float oz) {
        if (verts == 0) return null;
        VertexArray pa = new VertexArray(verts, 3, 2);
        if (MemDbg.on) MemDbg.va(pa, "MeshBuilder.pa");
        pa.set(0, verts, pos);
        VertexArray ca = new VertexArray(verts, 4, 1);
        if (MemDbg.on) MemDbg.va(ca, "MeshBuilder.ca");
        ca.set(0, verts, col);
        VertexArray ta = new VertexArray(verts, 2, 2);
        if (MemDbg.on) MemDbg.va(ta, "MeshBuilder.ta");
        ta.set(0, verts, uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1f / 16f, new float[] { ox, oy, oz });
        vb.setColors(ca);
        vb.setTexCoords(0, ta, 1f / 256f, null);
        return vb;
    }

    private static int[] stripLens = new int[0];
    /** готовые TriangleStripArray по числу вершин: M3G-объекты не должны становиться мусором (см. DynMesh) */
    private static final java.util.Hashtable stripCache = new java.util.Hashtable();

    public static TriangleStripArray strips(int verts) {
        Integer key = new Integer(verts);
        TriangleStripArray t = (TriangleStripArray) stripCache.get(key);
        if (t != null) return t;
        int quads = verts / 4;
        if (stripLens.length < quads) {
            stripLens = new int[Math.max(quads, stripLens.length * 2)];
            for (int i = 0; i < stripLens.length; i++) stripLens[i] = 4;
        }
        int[] lens = new int[quads];
        System.arraycopy(stripLens, 0, lens, 0, quads);
        t = new TriangleStripArray(0, lens);
        if (MemDbg.on) MemDbg.ib(t, "MeshBuilder.t");
        stripCache.put(key, t);
        return t;
    }
}
