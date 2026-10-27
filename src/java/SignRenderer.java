import java.util.Enumeration;
import java.util.Hashtable;
import javax.microedition.m3g.*;

/**
 * Таблички как SignRenderer 0.6.1: SignModel (доска 24x12x2 и стойка 2x14x2, item/sign.png 64x32) в масштабе 16/24
 * и текст шрифтом default8.png чёрным, 4 строки по центру. Рисуются ближе 64 блоков.
 */
public final class SignRenderer {
    static final float SIZE = 16 / 24f;
    private final Appearance modelApp, textApp;
    private VertexBuffer standVb, wallVb;
    private TriangleStripArray standIb, wallIb;
    private final Hashtable texts = new Hashtable();
    private final Transform t = new Transform(), tt = new Transform();

    private static final class Text {
        String key;
        /** буфер текста (из запаса FREE — M3G-объекты не должны становиться мусором, см. DynMesh) */
        DynMesh dyn;
        VertexBuffer vb;
        TriangleStripArray ib;
    }

    private static final java.util.Vector FREE = new java.util.Vector();

    public SignRenderer(Fog fog) {
        modelApp = app(fog, "/item/sign.png", true);
        textApp = app(fog, "/font/default8.png", false);
        MeshBuilder b = new MeshBuilder();
        ItemMeshes.modelBox(b, -12, -14, -1, 24, 12, 2, 0, 0);
        wallVb = modelVb(b);
        wallIb = MeshBuilder.strips(b.verts);
        ItemMeshes.modelBox(b, -1, -2, -1, 2, 14, 2, 0, 14);
        standVb = modelVb(b);
        standIb = MeshBuilder.strips(b.verts);
    }

    private static Appearance app(Fog fog, String path, boolean depthWrite) {
        Texture2D tex = new Texture2D(R.image2D(path));
        tex.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        tex.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        tex.setBlending(Texture2D.FUNC_MODULATE);
        Appearance a = new Appearance();
        a.setTexture(0, tex);
        CompositingMode cm = new CompositingMode();
        cm.setAlphaThreshold(0.5f);
        cm.setDepthWriteEnable(depthWrite);
        a.setCompositingMode(cm);
        PolygonMode pm = new PolygonMode();
        pm.setCulling(PolygonMode.CULL_NONE);
        pm.setPerspectiveCorrectionEnable(true);
        a.setPolygonMode(pm);
        a.setFog(fog);
        return a;
    }

    /** Модель в пикселях (1/16), развёртка 64x32: v удвоена, чтобы масштаб был общий 1/64. */
    private static VertexBuffer modelVb(MeshBuilder b) {
        int n = b.verts;
        VertexArray pa = new VertexArray(n, 3, 2);
        if (MemDbg.on) MemDbg.va(pa, "SignRenderer.pa");
        pa.set(0, n, b.pos);
        short[] uv = new short[n * 2];
        for (int i = 0; i < n; i++) {
            uv[i * 2] = b.uv[i * 2];
            uv[i * 2 + 1] = (short) (b.uv[i * 2 + 1] * 2);
        }
        VertexArray ta = new VertexArray(n, 2, 2);
        if (MemDbg.on) MemDbg.va(ta, "SignRenderer.ta");
        ta.set(0, n, uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, 1f / 16f, null);
        vb.setTexCoords(0, ta, 1f / 64f, null);
        return vb;
    }

    /** Font::drawSlow: глифы 8x8 (7.99), шаг — ширина символа; строка i с y = -20 + 10 i, по центру. */
    private Text text(TileEntity te) {
        String key = te.text[0] + "\n" + te.text[1] + "\n" + te.text[2] + "\n" + te.text[3];
        Text tx = (Text) texts.get(te);
        if (tx != null && key.equals(tx.key)) return tx;
        if (tx == null) {
            tx = new Text();
            texts.put(te, tx);
        }
        tx.key = key;
        MeshBuilder b = new MeshBuilder();
        int c = -1;
        for (int i = 0; i < 4; i++) {
            String msg = te.text[i] == null ? "" : te.text[i];
            int x = -R.width(msg) / 2, y = -20 + 10 * i;
            for (int k = 0; k < msg.length(); k++) {
                int ch = msg.charAt(k);
                if (ch > 255) ch = '?';
                if (ch != ' ') {
                    // позиции в 1/8 пикселя шрифта, развёртка в 1/1024 (7.99 ~ 63/8)
                    int ix = (ch & 15) * 64, iy = (ch >> 4) * 64, s = 63;
                    int px = x * 8, py = y * 8;
                    b.quad(px, py + s, 0, ix, iy + s, c, px + s, py + s, 0, ix + s, iy + s, c,
                            px + s, py, 0, ix + s, iy, c, px, py, 0, ix, iy, c);
                }
                x += R.charW[ch];
            }
        }
        if (b.verts == 0) {
            tx.vb = null;
            return tx;
        }
        if (tx.dyn == null) {
            if (FREE.size() > 0) {
                tx.dyn = (DynMesh) FREE.elementAt(FREE.size() - 1);
                FREE.removeElementAt(FREE.size() - 1);
            } else tx.dyn = new DynMesh(false);
        }
        tx.vb = tx.dyn.load(b, 1f / 8f, null, 1f / 1024f);
        tx.vb.setDefaultColor(0xFF000000);
        tx.ib = DynMesh.ib(b.verts);
        return tx;
    }

    public void render(Graphics3D g, Level level, float camX, float camY, float camZ, Frustum fr) {
        for (Enumeration e = level.tileEntities.elements(); e.hasMoreElements(); ) {
            TileEntity te = (TileEntity) e.nextElement();
            if (te.text == null) continue;
            float dx = te.x + 0.5f - camX, dy = te.y + 0.5f - camY, dz = te.z + 0.5f - camZ;
            if (dx * dx + dy * dy + dz * dz >= 64 * 64) continue;
            // вне пирамиды видимости — не рисуем (каждый render() в JL-Mod дорог)
            if (fr != null && !fr.visible(te.x - 0.25f, te.y, te.z - 0.25f, te.x + 1.25f, te.y + 1.25f, te.z + 1.25f, 64)) continue;
            int id = level.getTile(te.x, te.y, te.z);
            if (id != Tile.SIGN && id != Tile.WALL_SIGN) continue;
            int d = level.getData(te.x, te.y, te.z);
            t.setIdentity();
            t.postTranslate(te.x + 0.5f, te.y + 0.75f * SIZE, te.z + 0.5f);
            if (id == Tile.SIGN) t.postRotate(-d * 360 / 16f, 0, 1, 0);
            else {
                float rot = d == 2 ? 180 : (d == 4 ? 90 : (d == 5 ? -90 : 0));
                t.postRotate(-rot, 0, 1, 0);
                t.postTranslate(0, -5 / 16f, -7 / 16f);
            }
            // TileEntityRenderDispatcher::render: яркость по свету в блоке таблички
            int br = LevelRenderer.BR[level.getRawBrightness(te.x, te.y, te.z)];
            int col = 0xFF000000 | (br << 16) | (br << 8) | br;
            tt.set(t);
            tt.postScale(SIZE, -SIZE, -SIZE);
            VertexBuffer vb = id == Tile.SIGN ? standVb : wallVb;
            vb.setDefaultColor(col);
            g.render(vb, id == Tile.SIGN ? standIb : wallIb, modelApp, tt);
            Text tx = text(te);
            if (tx.vb == null) continue;
            float s = 1 / 60f * SIZE;
            t.postTranslate(0, 0.5f * SIZE, 0.07f * SIZE);
            t.postScale(s, -s, s);
            g.render(tx.vb, tx.ib, textApp, t);
        }
    }

    /** Убрать кэш текста удалённых табличек. */
    public void forget(Level level) {
        for (Enumeration e = texts.keys(); e.hasMoreElements(); ) {
            Object k = e.nextElement();
            if (!level.tileEntities.contains(k)) {
                Text tx = (Text) texts.remove(k);
                if (tx != null && tx.dyn != null) FREE.addElement(tx.dyn);
            }
        }
    }
}
