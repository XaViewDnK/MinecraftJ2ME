import javax.microedition.m3g.*;

/**
 * Небо 0.6.1: цвет очистки/тумана (GameRenderer::setupClearColor/setupFog) и при «красивой» графике —
 * плоскость неба и облака (GameRenderer::prepareAndRenderClouds, LevelRenderer::renderSky/renderClouds).
 * Солнца, луны и звёзд в 0.6.1 нет.
 */
public final class Sky {
    /** итоговый цвет тумана и фона (0..1) */
    public float fr, fg, fb;
    /** цвет плоскости неба (Level::getSkyColor) */
    float sr, sg, sb;

    private final Camera cam = new Camera();
    private final Transform t = new Transform();
    private final Fog skyFog = new Fog(), cloudFog = new Fog();
    private final Appearance skyApp = new Appearance(), cloudApp = new Appearance();
    private VertexBuffer skyVb, cloudVb;
    private TriangleStripArray skyIb, cloudIb;
    private final float[] bias = new float[2];

    public Sky() {
        // LevelRenderer::generateSky: сетка 128x128 на высоте 16 над камерой
        MeshBuilder m = new MeshBuilder();
        int s = 128, d = 256 / s + 2;
        for (int xx = -s * d; xx <= s * d; xx += s)
            for (int zz = -s * d; zz <= s * d; zz += s)
                m.quad(xx, 16, zz, 0, 0, -1, xx, 16, zz + s, 0, 0, -1, xx + s, 16, zz + s, 0, 0, -1, xx + s, 16, zz, 0, 0, -1);
        skyVb = buildPos(m, false);
        skyIb = MeshBuilder.strips(m.verts);
        // LevelRenderer::renderClouds: клетки 32, от -256 до 256, развёртка 1/2048 блока
        m.clear();
        int c = 32, n = 256 / c;
        for (int xx = -c * n; xx < c * n; xx += c)
            for (int zz = -c * n; zz < c * n; zz += c)
                m.quad(xx, 0, zz, xx, zz, -1, xx, 0, zz + c, xx, zz + c, -1, xx + c, 0, zz + c, xx + c, zz + c, -1, xx + c, 0, zz, xx + c, zz, -1);
        cloudVb = buildPos(m, true);
        cloudIb = MeshBuilder.strips(m.verts);

        skyFog.setMode(Fog.LINEAR);
        cloudFog.setMode(Fog.LINEAR);
        skyApp.setFog(skyFog);
        skyApp.setCompositingMode(noDepth(false));
        skyApp.setPolygonMode(noCull());
        Texture2D tex = new Texture2D(R.image2D("/environment/clouds.png"));
        tex.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        tex.setWrapping(Texture2D.WRAP_REPEAT, Texture2D.WRAP_REPEAT);
        tex.setBlending(Texture2D.FUNC_MODULATE);
        cloudApp.setTexture(0, tex);
        cloudApp.setFog(cloudFog);
        cloudApp.setCompositingMode(noDepth(true));
        cloudApp.setPolygonMode(noCull());
    }

    private static VertexBuffer buildPos(MeshBuilder m, boolean uv) {
        int v = m.verts;
        VertexArray pa = new VertexArray(v, 3, 2);
        if (MemDbg.on) MemDbg.va(pa, "Sky.pa");
        pa.set(0, v, m.pos);
        VertexBuffer b = new VertexBuffer();
        b.setPositions(pa, 1f, null);
        if (uv) {
            VertexArray ta = new VertexArray(v, 2, 2);
            if (MemDbg.on) MemDbg.va(ta, "Sky.ta");
            ta.set(0, v, m.uv);
            b.setTexCoords(0, ta, 1f / 2048f, null);
        }
        return b;
    }

    private static CompositingMode noDepth(boolean blend) {
        CompositingMode cm = new CompositingMode();
        cm.setBlending(blend ? CompositingMode.ALPHA : CompositingMode.REPLACE);
        cm.setDepthTestEnable(false);
        cm.setDepthWriteEnable(false);
        return cm;
    }

    private static PolygonMode noCull() {
        PolygonMode pm = new PolygonMode();
        pm.setCulling(PolygonMode.CULL_NONE);
        pm.setShading(PolygonMode.SHADE_FLAT);
        return pm;
    }

    private static float clamp(float v, float hi) {
        return v < 0 ? 0 : (v > hi ? hi : v);
    }

    /**
     * GameRenderer::setupClearColor. viewDistance — 2 (обычная) или 3 (низкое качество), fogBr — сглаженная яркость
     * у игрока (GameRenderer::tick), liquid: 0 нет, 1 вода, 2 лава.
     */
    public void update(Level level, float a, int viewDistance, float fogBr, int liquid) {
        float td = level.getTimeOfDay(a);
        float c = Mth.cos(td * Mth.PI * 2f) * 2f + 0.5f;
        float br = clamp(c, 0.75f);
        sr = 0x30 / 255f * br;
        sg = 0x70 / 255f * br;
        sb = br;
        br = clamp(c, 1f);
        fr = 0x80 / 255f * (br * 0.94f + 0.06f);
        fg = 0xDA / 255f * (br * 0.94f + 0.06f);
        fb = br * 0.91f + 0.09f;
        float w = viewDistance >= 3 ? 0 : 1f - Mth.pow(1f / (4 - viewDistance), 0.25f);
        fr += (sr - fr) * w;
        fg += (sg - fg) * w;
        fb += (sb - fb) * w;
        if (liquid == 1) { fr = 0.02f; fg = 0.02f; fb = 0.2f; }
        else if (liquid == 2) { fr = 0.6f; fg = 0.1f; fb = 0f; }
        fr *= fogBr;
        fg *= fogBr;
        fb *= fogBr;
    }

    public int rgb() {
        return rgb(fr, fg, fb);
    }

    static int rgb(float r, float g, float b) {
        return ((int) (clamp(r, 1f) * 255f) << 16) | ((int) (clamp(g, 1f) * 255f) << 8) | (int) (clamp(b, 1f) * 255f);
    }

    /**
     * GameRenderer::prepareAndRenderClouds: своя проекция (ближняя 2, дальняя rd*512), плоскость неба с туманом
     * 0.2..0.75 rd, облака на высоте 128.33 с туманом 4.2 rd * 0.6..1.
     */
    public void render(Graphics3D g, Transform camT, float fov, float aspect, float rd, float ex, float ey, float ez,
                       float px, float pz, float time, float cloudBr) {
        cam.setPerspective(fov, aspect, 2f, rd * 512f);
        g.setCamera(cam, camT);
        int fog = rgb();
        skyFog.setColor(fog);
        skyFog.setLinear(rd * 0.2f, rd * 0.75f);
        skyVb.setDefaultColor(0xFF000000 | rgb(sr, sg, sb));
        t.setIdentity();
        t.postTranslate(ex, ey, ez);
        g.render(skyVb, skyIb, skyApp, t);
        // Level::getCloudColor, альфа 0.8; облака плывут по x со скоростью 0.03 блока за тик
        float r = cloudBr * 0.9f + 0.1f, b = cloudBr * 0.85f + 0.15f;
        cloudVb.setDefaultColor(0xCC000000 | rgb(r, r, b));
        cloudFog.setColor(fog);
        cloudFog.setLinear(rd * 4.2f * 0.6f, rd * 4.2f);
        float xo = px + time * 0.03f, zo = pz;
        xo -= Mth.floor(xo / 2048f) * 2048f;
        zo -= Mth.floor(zo / 2048f) * 2048f;
        bias[0] = xo / 2048f;
        bias[1] = zo / 2048f;
        cloudVb.setTexCoords(0, cloudVb.getTexCoords(0, null), 1f / 2048f, bias);
        t.setIdentity();
        t.postTranslate(ex, 128.33f, ez);
        g.render(cloudVb, cloudIb, cloudApp, t);
    }
}
