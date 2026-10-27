import java.util.Vector;
import javax.microedition.m3g.*;

/**
 * Коробочная модель моба. Координаты частей — в пикселях модели (1/16 блока), ось Y вниз, ноги на y=24
 * (так устроены развёртки текстур мобов 64x32). Каждая часть — отдельный VertexBuffer, крутится вокруг своей оси.
 */
public final class Model {
    public static final class Part {
        float px, py, pz;          // ось вращения (пиксели модели, Y вниз)
        public float xRot, yRot, zRot; // радианы
        short[] pos, uv; // локальные координаты (1/64 блока от оси) и развёртка
        public boolean visible = true;
        final Vector boxes = new Vector();
        final int texW, texH;

        Part(int texW, int texH) {
            this.texW = texW;
            this.texH = texH;
        }

        /** Коробка: смещение от оси (x,y,z), размеры w,h,d, развёртка с (u,v), раздутие g. */
        public Part box(float x, float y, float z, int w, int h, int d, int u, int v, float g) {
            boxes.addElement(new float[] { x, y, z, w, h, d, u, v, g });
            return this;
        }

        public Part box(float x, float y, float z, int w, int h, int d, int u, int v) {
            return box(x, y, z, w, h, d, u, v, 0);
        }

        void compile() {
            int n = boxes.size();
            int verts = n * 24;
            short[] pos = new short[verts * 3];
            short[] uv = new short[verts * 2];
            int k = 0;
            for (int i = 0; i < n; i++) {
                float[] b = (float[]) boxes.elementAt(i);
                float g = b[8];
                // перевод в Y вверх, единицы 1/4 пикселя модели
                float x0 = b[0] - g, x1 = b[0] + b[3] + g;
                float y0 = -(b[1] + b[4] + g), y1 = -(b[1] - g);
                float z0 = b[2] - g, z1 = b[2] + b[5] + g;
                int w = (int) b[3], h = (int) b[4], d = (int) b[5], u = (int) b[6], v = (int) b[7];
                // грани: верх, низ, -x, -z (перед), +x, +z (зад)
                k = quad(pos, uv, k, x0, y1, z1, x0, y1, z0, x1, y1, z0, x1, y1, z1, u + d, v, u + d + w, v + d);         // верх
                k = quad(pos, uv, k, x0, y0, z0, x0, y0, z1, x1, y0, z1, x1, y0, z0, u + d + w, v, u + d + w + w, v + d); // низ
                k = quad(pos, uv, k, x0, y1, z1, x0, y0, z1, x0, y0, z0, x0, y1, z0, u, v + d, u + d, v + d + h);         // -x
                k = quad(pos, uv, k, x0, y1, z0, x0, y0, z0, x1, y0, z0, x1, y1, z0, u + d, v + d, u + d + w, v + d + h); // -z перед
                k = quad(pos, uv, k, x1, y1, z0, x1, y0, z0, x1, y0, z1, x1, y1, z1, u + d + w, v + d, u + d + w + d, v + d + h); // +x
                k = quad(pos, uv, k, x1, y1, z1, x1, y0, z1, x0, y0, z1, x0, y1, z1, u + d + w + d, v + d, u + 2 * d + 2 * w, v + d + h); // +z зад
            }
            this.pos = pos;
            this.uv = uv;
        }

        private int quad(short[] pos, short[] uv, int k,
                         float ax, float ay, float az, float bx, float by, float bz,
                         float cx, float cy, float cz, float dx, float dy, float dz,
                         int u0, int v0, int u1, int v1) {
            // a=верх-лево, b=низ-лево, c=низ-право, d=верх-право (смотрим снаружи) -> полоса a,b,d,c
            float sv = (float) texW / texH; // v в тех же единицах, что u
            k = put(pos, uv, k, ax, ay, az, u0, v0 * sv);
            k = put(pos, uv, k, bx, by, bz, u0, v1 * sv);
            k = put(pos, uv, k, dx, dy, dz, u1, v0 * sv);
            k = put(pos, uv, k, cx, cy, cz, u1, v1 * sv);
            return k;
        }

        private int put(short[] pos, short[] uv, int k, float x, float y, float z, float u, float v) {
            pos[k * 3] = (short) (x * 4f);
            pos[k * 3 + 1] = (short) (y * 4f);
            pos[k * 3 + 2] = (short) (z * 4f);
            uv[k * 2] = (short) (u * 4f);
            uv[k * 2 + 1] = (short) (v * 4f);
            return k + 1;
        }
    }

    public final Vector parts = new Vector();
    final int texW, texH;

    public Model(int texW, int texH) {
        this.texW = texW;
        this.texH = texH;
    }

    /** Новая часть с осью (px,py,pz). */
    public Part part(float px, float py, float pz) {
        Part p = new Part(texW, texH);
        p.px = px;
        p.py = py;
        p.pz = pz;
        parts.addElement(p);
        return p;
    }

    // все части — в одном буфере: повороты частей считаются на процессоре, один вызов render на модель
    private VertexBuffer vb;
    private VertexArray pa, ta;
    private TriangleStripArray ib;
    private short[] mpos;
    private int verts;

    public Model compile() {
        verts = 0;
        for (int i = 0; i < parts.size(); i++) {
            Part p = (Part) parts.elementAt(i);
            p.compile();
            verts += p.pos.length / 3;
        }
        mpos = new short[verts * 3];
        short[] muv = new short[verts * 2];
        int k = 0;
        for (int i = 0; i < parts.size(); i++) {
            Part p = (Part) parts.elementAt(i);
            System.arraycopy(p.uv, 0, muv, k * 2, p.uv.length);
            k += p.pos.length / 3;
        }
        pa = new VertexArray(verts, 3, 2);
        if (MemDbg.on) MemDbg.va(pa, "Model.pa");
        ta = new VertexArray(verts, 2, 2);
        if (MemDbg.on) MemDbg.va(ta, "Model.ta");
        ta.set(0, verts, muv);
        vb = new VertexBuffer();
        vb.setPositions(pa, 1f / 64f, null); // 1/4 пикселя модели -> 1/64 блока
        vb.setTexCoords(0, ta, 1f / (texW * 4f), null);
        ib = MeshBuilder.strips(verts);
        return this;
    }

    /** Повторить последнюю отрисовку другим материалом (наложение цвета при уроне, вспышка крипера). */
    public void renderAgain(Graphics3D g, Transform base, Appearance app, int color) {
        if (batch != null && last != null) {
            Slot s = last;
            s.vb2.setDefaultColor(color);
            if (s.m2.getAppearance(0) != app) s.m2.setAppearance(0, app);
            s.m2.setTransform(base);
            s.used2 = true;
            if (!s.on2) {
                s.on2 = true;
                s.m2.setRenderingEnable(true);
            }
            return;
        }
        vb.setDefaultColor(color);
        g.render(vb, ib, app, base);
    }

    // ---- пакетная отрисовка: все модели кадра — одним Graphics3D.render(Group) ----

    /**
     * Слот модели в пакете: свои позиции и узлы Mesh (основной и повтор с другим материалом), общие развёртка
     * и индексы. Слоты не выбрасываются: в JL-Mod M3G-мусор опасен (см. DynMesh), а render() дорог (~0.3 мс).
     */
    private static final class Slot {
        VertexArray pa;
        VertexBuffer vb, vb2;
        Mesh m, m2;
        boolean on, on2, used2;
    }

    private static Group batch;
    private static final Vector models = new Vector();
    private final Vector slots = new Vector();
    private int used;
    private Slot last;

    /** Начать пакет: дальнейшие render/renderAgain только заполняют слоты. */
    static void beginBatch(Group g) {
        batch = g;
    }

    /** Нарисовать пакет одним вызовом и выключить слоты, не использованные в этом кадре. */
    static void endBatch(Graphics3D g) {
        Group grp = batch;
        batch = null;
        boolean any = false;
        for (int i = 0; i < models.size(); i++) if (((Model) models.elementAt(i)).used > 0) any = true;
        if (any) g.render(grp, null);
        for (int i = 0; i < models.size(); i++) {
            Model md = (Model) models.elementAt(i);
            for (int k = 0; k < md.slots.size(); k++) {
                Slot s = (Slot) md.slots.elementAt(k);
                if (k >= md.used && s.on) {
                    s.on = false;
                    s.m.setRenderingEnable(false);
                }
                if (!s.used2 && s.on2) {
                    s.on2 = false;
                    s.m2.setRenderingEnable(false);
                }
                s.used2 = false;
            }
            md.used = 0;
            md.last = null;
        }
    }

    private Slot slot(Appearance app) {
        if (used < slots.size()) return (Slot) slots.elementAt(used++);
        if (slots.size() == 0) models.addElement(this);
        Slot s = new Slot();
        s.pa = new VertexArray(verts, 3, 2);
        if (MemDbg.on) MemDbg.va(s.pa, "Model.pa");
        s.vb = new VertexBuffer();
        s.vb.setPositions(s.pa, 1f / 64f, null);
        s.vb.setTexCoords(0, ta, 1f / (texW * 4f), null);
        s.vb2 = new VertexBuffer();
        s.vb2.setPositions(s.pa, 1f / 64f, null);
        s.vb2.setTexCoords(0, ta, 1f / (texW * 4f), null);
        s.m = new Mesh(s.vb, ib, app);
        s.m.setRenderingEnable(false);
        s.m2 = new Mesh(s.vb2, ib, app);
        s.m2.setRenderingEnable(false);
        batch.addChild(s.m);
        batch.addChild(s.m2);
        slots.addElement(s);
        used++;
        return s;
    }

    /** Отрисовать модель. base — положение/поворот сущности (ноги на y=0). */
    public void render(Graphics3D g, Transform base, Appearance app, int color) {
        short[] out = mpos;
        int k = 0;
        for (int i = 0; i < parts.size(); i++) {
            Part p = (Part) parts.elementAt(i);
            short[] in = p.pos;
            int n = in.length;
            if (!p.visible) {
                for (int j = 0; j < n; j++) out[k + j] = 0;
                k += n;
                continue;
            }
            // T(ось) * Rz(-zRot) * Ry(-yRot) * Rx(-xRot), как прежде postRotate по частям
            float sx = Mth.sin(-p.xRot), cx = Mth.cos(-p.xRot);
            float sy = Mth.sin(-p.yRot), cy = Mth.cos(-p.yRot);
            float sz = Mth.sin(-p.zRot), cz = Mth.cos(-p.zRot);
            float ox = p.px * 4f, oy = (24f - p.py) * 4f, oz = p.pz * 4f;
            boolean rot = p.xRot != 0 || p.yRot != 0 || p.zRot != 0;
            for (int j = 0; j < n; j += 3) {
                float x = in[j], y = in[j + 1], z = in[j + 2];
                if (rot) {
                    float y1 = y * cx - z * sx, z1 = y * sx + z * cx;
                    float x2 = x * cy + z1 * sy, z2 = -x * sy + z1 * cy;
                    float x3 = x2 * cz - y1 * sz, y3 = x2 * sz + y1 * cz;
                    x = x3; y = y3; z = z2;
                }
                out[k + j] = (short) (x + ox + (x + ox >= 0 ? 0.5f : -0.5f));
                out[k + j + 1] = (short) (y + oy + (y + oy >= 0 ? 0.5f : -0.5f));
                out[k + j + 2] = (short) (z + oz + (z + oz >= 0 ? 0.5f : -0.5f));
            }
            k += n;
        }
        if (batch != null) {
            Slot s = slot(app);
            s.pa.set(0, verts, out);
            s.vb.setDefaultColor(color);
            if (s.m.getAppearance(0) != app) s.m.setAppearance(0, app);
            s.m.setTransform(base);
            if (!s.on) {
                s.on = true;
                s.m.setRenderingEnable(true);
            }
            last = s;
            return;
        }
        pa.set(0, verts, out);
        vb.setDefaultColor(color);
        g.render(vb, ib, app, base);
    }
}
