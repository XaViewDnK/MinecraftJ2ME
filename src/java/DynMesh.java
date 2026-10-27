import javax.microedition.m3g.*;

/**
 * Постоянный буфер вершин для геометрии, которая меняется каждый кадр (частицы, рамка выделения, трещины).
 *
 * В JL-Mod блокировка ядра M3G пустая: конструкторы объектов работают в потоке игры, а финализация мусорных
 * объектов — в потоке M3G, и при совпадении процесс падает (SIGSEGV в m3gDelChildObject). Поэтому M3G-объекты
 * во время игры не должны становиться мусором: массивы растут с запасом, старые хранятся в KEEP.
 */
final class DynMesh {
    /** вытесненные при росте массивы — живут до конца программы */
    static final java.util.Vector KEEP = new java.util.Vector();

    final VertexBuffer vb = new VertexBuffer();
    private VertexArray pa, ca, ta;
    private int cap;
    private final boolean colors;

    DynMesh(boolean colors) {
        this.colors = colors;
    }

    /** Загрузить вершины m; позиции — scale и bias, развёртка — uvScale. Рисовать с ib(m.verts). */
    VertexBuffer load(MeshBuilder m, float scale, float[] bias, float uvScale) {
        int n = m.verts;
        if (n > cap) {
            if (pa != null) {
                // у буфера все массивы обязаны быть одной длины — сначала отвязать старые
                vb.setColors(null);
                vb.setTexCoords(0, null, 1f, null);
                vb.setPositions(null, 1f, null);
                KEEP.addElement(pa);
                KEEP.addElement(ta);
                if (ca != null) KEEP.addElement(ca);
            }
            int c = Math.max(64, cap);
            while (c < n) c *= 2;
            cap = c;
            pa = new VertexArray(c, 3, 2);
            if (MemDbg.on) MemDbg.va(pa, "DynMesh.pa");
            ta = new VertexArray(c, 2, 2);
            if (MemDbg.on) MemDbg.va(ta, "DynMesh.ta");
            if (colors) {
                ca = new VertexArray(c, 4, 1);
                if (MemDbg.on) MemDbg.va(ca, "DynMesh.ca");
            }
        }
        pa.set(0, n, m.pos);
        ta.set(0, n, m.uv);
        if (colors) ca.set(0, n, m.col);
        vb.setPositions(pa, scale, bias);
        vb.setTexCoords(0, ta, uvScale, null);
        if (colors) vb.setColors(ca);
        return vb;
    }

    static TriangleStripArray ib(int verts) {
        return MeshBuilder.strips(verts);
    }
}
