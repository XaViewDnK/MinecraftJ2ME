import javax.microedition.m3g.*;

/**
 * Рука и предмет в руке (ItemInHandRenderer 0.6.1) поверх мира: своя проекция, буфер глубины очищается,
 * тумана нет. Покачивание и толчок от урона — GameRenderer::bobView/bobHurt.
 */
public final class HandRenderer {
    private final Camera cam = new Camera();
    private final Transform camT = new Transform(), t = new Transform();
    private final Background depthOnly = new Background();
    private final Appearance terrainApp, itemsApp, charApp;
    private VertexBuffer armVb, fxVb;
    private TriangleStripArray fxIb;
    private Appearance fxApp;
    private final short[] fxUv = new short[8];
    private TriangleStripArray armIb;

    /** ItemInHandRenderer::height — предмет опускается и поднимается при смене/использовании */
    float height, oHeight;
    int itemId, itemAux;

    /** Новый мир: как у только что созданного рендерера. */
    void reset() {
        height = oHeight = 0;
        itemId = itemAux = 0;
    }

    public HandRenderer(Texture2D terrain) {
        depthOnly.setColorClearEnable(false);
        depthOnly.setDepthClearEnable(true);
        terrainApp = app(terrain);
        itemsApp = app(tex("/gui/items.png"));
        charApp = app(tex("/mob/char.png"));
        buildArm();
    }

    private static Texture2D tex(String path) {
        Texture2D t = new Texture2D(R.image2D(path));
        t.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        t.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        t.setBlending(Texture2D.FUNC_MODULATE);
        return t;
    }

    private static Appearance app(Texture2D t) {
        Appearance a = new Appearance();
        a.setTexture(0, t);
        CompositingMode cm = new CompositingMode();
        cm.setAlphaThreshold(0.5f);
        a.setCompositingMode(cm);
        PolygonMode pm = new PolygonMode();
        pm.setCulling(PolygonMode.CULL_NONE);
        pm.setPerspectiveCorrectionEnable(true);
        a.setPolygonMode(pm);
        return a;
    }

    /** HumanoidModel::arm0: addBox(-3, -2, -2, 4, 12, 4), развёртка (40, 16) в char.png 64x32; пиксели модели, Y вниз. */
    private void buildArm() {
        MeshBuilder b = new MeshBuilder();
        ItemMeshes.modelBox(b, -3, -2, -2, 4, 12, 4, 40, 16);
        int n = b.verts;
        VertexArray pa = new VertexArray(n, 3, 2);
        if (MemDbg.on) MemDbg.va(pa, "HandRenderer.pa");
        pa.set(0, n, b.pos);
        // v в char.png (высота 32) — в тех же единицах, что u (ширина 64)
        short[] uv = new short[n * 2];
        for (int i = 0; i < n; i++) {
            uv[i * 2] = b.uv[i * 2];
            uv[i * 2 + 1] = (short) (b.uv[i * 2 + 1] * 2);
        }
        VertexArray ta = new VertexArray(n, 2, 2);
        if (MemDbg.on) MemDbg.va(ta, "HandRenderer.ta");
        ta.set(0, n, uv);
        armVb = new VertexBuffer();
        armVb.setPositions(pa, 1f / 16f, null);
        armVb.setTexCoords(0, ta, 1f / 64f, null);
        armIb = MeshBuilder.strips(n);
    }

    /** ItemInHandRenderer::tick */
    public void tick(Player p) {
        oHeight = height;
        itemId = p.inv.selCount() > 0 ? p.inv.selId() : 0;
        itemAux = p.inv.selAux();
        float dd = 1 - height;
        if (dd < -0.4f) dd = -0.4f;
        if (dd > 0.4f) dd = 0.4f;
        height += dd;
    }

    /** ItemInHandRenderer::itemUsed */
    public void itemUsed() {
        height = 0;
    }

    /** GameRenderer::bobHurt (смерть: наклон до 40°, урон: встряска по hurtDir) — в матрицу вида. */
    static void bobHurt(Transform m, Player p, float a) {
        if (p.health <= 0) {
            float dur = p.deathTime + a;
            m.postRotate(40 - 40 * 200 / (dur + 200), 0, 0, 1);
        }
        if (p.hurtTime <= 0) return;
        float hurt = (p.hurtTime - a) / 10f;
        hurt = Mth.sin(hurt * hurt * hurt * hurt * Mth.PI);
        m.postRotate(-p.hurtDir, 0, 1, 0);
        m.postRotate(-hurt * 14, 0, 0, 1);
        m.postRotate(p.hurtDir, 0, 1, 0);
    }

    /** GameRenderer::bobView — в матрицу вида. */
    static void bobView(Transform m, Player p, float a) {
        float wda = p.walkDist - p.walkDistO;
        float b = -(p.walkDist + wda * a);
        float bob = p.bobO + (p.bob - p.bobO) * a;
        float tilt = p.tiltO + (p.tilt - p.tiltO) * a;
        m.postTranslate(Mth.sin(b * Mth.PI) * bob * 0.5f, -Math.abs(Mth.cos(b * Mth.PI) * bob), 0);
        m.postRotate(Mth.sin(b * Mth.PI) * bob * 3, 0, 0, 1);
        m.postRotate(Math.abs(Mth.cos(b * Mth.PI - 0.2f) * bob) * 5, 1, 0, 0);
        m.postRotate(tilt, 1, 0, 0);
    }

    /** GameRenderer::renderItemInHand + ItemInHandRenderer::render. br — яркость у игрока (0..1). */
    public void render(Graphics3D g, Player p, float a, float fov, float aspect, float far, float br) {
        g.clear(depthOnly);
        cam.setPerspective(fov, aspect, 0.05f, far);
        camT.setIdentity();
        g.setCamera(cam, camT);
        float h = oHeight + (height - oHeight) * a;
        int c = (int) (br * 255f);
        int col = 0xFF000000 | (c << 16) | (c << 8) | c;
        // ItemInHandRenderer::render: при еде и натяжении лука взмаха нет
        boolean using = itemId > 0 && p.isUsingItem() && p.useItemDuration > 0;
        boolean eat = using && itemId >= 256 && Item.food[itemId] > 0, bow = using && itemId == 261;
        float swing = eat || bow ? 0 : p.getAttackAnim(a);
        float sq = Mth.sqrt(swing);
        float swing3 = Mth.sin(swing * swing * Mth.PI), swing1 = Mth.sin(swing * Mth.PI), swing2 = Mth.sin(sq * Mth.PI);
        float d = 0.8f;
        t.setIdentity();
        bobHurt(t, p, a);
        bobView(t, p, a);
        if (itemId > 0) {
            if (eat) {
                // UseAnim::eat: подносит ко рту и покачивает
                float tt = p.useItemDuration - a + 1;
                float prog = 1 - tt / Player.EAT_DURATION;
                float is = 1 - prog;
                is = is * is * is;
                is = is * is * is;
                is = is * is * is;
                float iss = 1 - is;
                t.postTranslate(0, Math.abs(Mth.cos(tt / 4 * Mth.PI) * 0.1f) * (prog > 0.2f ? 1 : 0), 0);
                t.postTranslate(iss * 0.6f, -iss * 0.5f, 0);
                t.postRotate(iss * 90, 0, 1, 0);
                t.postRotate(iss * 10, 1, 0, 0);
                t.postRotate(iss * 30, 0, 0, 1);
            } else if (!using) t.postTranslate(-swing2 * 0.4f, Mth.sin(sq * Mth.PI * 2) * 0.2f, -swing1 * 0.2f);
            t.postTranslate(0.7f * d, -0.65f * d - (1 - h) * 0.6f, -0.9f * d);
            t.postRotate(45, 0, 1, 0);
            t.postRotate(-swing3 * 20, 0, 1, 0);
            t.postRotate(-swing2 * 20, 0, 0, 1);
            t.postRotate(-swing2 * 80, 1, 0, 0);
            t.postScale(0.4f, 0.4f, 0.4f);
            int icon = -1;
            if (bow) {
                // UseAnim::bow: лук перед собой, тетива натягивается (растяжение по z), иконка по времени натяжения
                t.postRotate(-18, 0, 0, 1);
                t.postRotate(-12, 0, 1, 0);
                t.postRotate(-8, 1, 0, 0);
                t.postTranslate(-0.9f, 0.2f, 0);
                float held = Player.BOW_DURATION - (p.useItemDuration - a + 1);
                float pow = held / 20f;
                pow = (pow * pow + pow * 2) / 3;
                if (pow > 1) pow = 1;
                if (pow > 0.1f) t.postTranslate(0, Mth.sin((held - 0.1f) * 1.3f) * 0.01f * (pow - 0.1f), 0);
                t.postTranslate(0, 0, pow * 0.1f);
                t.postRotate(-45 - 290, 0, 0, 1);
                t.postRotate(-50, 0, 1, 0);
                t.postTranslate(0, 0.5f, 0);
                t.postScale(1, 1, 1 + pow * 0.2f);
                t.postTranslate(0, -0.5f, 0);
                t.postRotate(50, 0, 1, 0);
                t.postRotate(45 + 290, 0, 0, 1);
                // Player::getItemInHandIcon
                int ticks = p.getTicksUsingItem();
                icon = ticks >= 18 ? 5 + 8 * 16 : (ticks > 40 / 3 ? 5 + 7 * 16 : (ticks > 0 ? 5 + 6 * 16 : -1));
            }
            if (ItemMeshes.canRender(itemId)) {
                ItemMeshes.Mesh m = ItemMeshes.block(itemId, itemAux);
                m.vb.setDefaultColor(col);
                g.render(m.vb, m.ib, terrainApp, t);
            } else {
                ItemMeshes.Mesh m = icon >= 0 ? ItemMeshes.flatIcon(icon, false) : ItemMeshes.flat(itemId, itemAux);
                t.postTranslate(0, -0.3f, 0);
                t.postScale(1.5f, 1.5f, 1.5f);
                t.postRotate(50, 0, 1, 0);
                t.postRotate(45 + 290, 0, 0, 1);
                t.postTranslate(-15 / 16f, -1 / 16f, 0);
                m.vb.setDefaultColor(col);
                g.render(m.vb, m.ib, m.terrain ? terrainApp : itemsApp, t);
            }
        } else {
            t.postTranslate(-swing2 * 0.3f, Mth.sin(sq * Mth.PI * 2) * 0.4f, -swing1 * 0.4f);
            t.postTranslate(0.8f * d, -0.75f * d - (1 - h) * 0.6f, -0.9f * d);
            t.postRotate(45, 0, 1, 0);
            t.postRotate(swing2 * 70, 0, 1, 0);
            t.postRotate(-swing3 * 20, 0, 0, 1);
            t.postTranslate(-1.0f, 3.6f, 3.5f);
            t.postRotate(120, 0, 0, 1);
            t.postRotate(180 + 20, 1, 0, 0);
            t.postRotate(-90 - 45, 0, 1, 0);
            t.postTranslate(5.6f, 0, 0);
            // HumanoidMobRenderer::renderHand: arm0 в (-5, 2, 0), zRot 0.1 (покачивание bcos при bob = 0)
            t.postTranslate(-5 / 16f, 2 / 16f, 0);
            t.postRotate(0.1f * 57.29578f, 0, 0, 1);
            armVb.setDefaultColor(col);
            g.render(armVb, armIb, charApp, t);
        }
    }

    /**
     * ItemInHandRenderer::renderScreenEffect: горящий игрок — два языка огня (Tile::fire, альфа 0.9), голова в
     * непрозрачном блоке — его текстура на весь экран (яркость 0.1, альфа 0.5). Вода — без наложения.
     */
    public void renderScreenEffect(Graphics3D g, Player p, Level level) {
        int xt = Mth.floor(p.x), yt = Mth.floor(p.y + Player.EYE + 0.12f), zt = Mth.floor(p.z);
        int in = level.getTile(xt, yt, zt);
        boolean wall = Tile.opaque[in];
        boolean fire = p.fire > 0;
        if (!wall && !fire) return;
        if (fxVb == null) {
            short[] pos = { -1, -1, 0, 1, -1, 0, -1, 1, 0, 1, 1, 0 };
            VertexArray pa = new VertexArray(4, 3, 2);
            if (MemDbg.on) MemDbg.va(pa, "HandRenderer.pa");
            pa.set(0, 4, pos);
            fxVb = new VertexBuffer();
            fxVb.setPositions(pa, 1f, null);
            fxIb = new TriangleStripArray(0, new int[] { 4 });
            if (MemDbg.on) MemDbg.ib(fxIb, "HandRenderer.fxIb");
            fxApp = new Appearance();
            fxApp.setTexture(0, terrainApp.getTexture(0));
            CompositingMode cm = new CompositingMode();
            cm.setBlending(CompositingMode.ALPHA);
            cm.setDepthTestEnable(false);
            cm.setDepthWriteEnable(false);
            fxApp.setCompositingMode(cm);
            PolygonMode pm = new PolygonMode();
            pm.setCulling(PolygonMode.CULL_NONE);
            fxApp.setPolygonMode(pm);
        }
        if (fire) {
            for (int i = 0; i < 2; i++) {
                int tex = 31 + i * 16;
                // x0..x1 = -0.5..0.5, y = -0.5..0.5, z = -0.5; u зеркально
                setFx((tex & 15) * 16, (tex >> 4) * 16, 0, 16, 0, 16, true);
                t.setIdentity();
                t.postTranslate(-(i * 2 - 1) * 0.24f, -0.3f, 0);
                t.postRotate((i * 2 - 1) * 10f, 0, 1, 0);
                t.postTranslate(0, 0, -0.5f);
                t.postScale(0.5f, 0.5f, 1f);
                fxVb.setDefaultColor(0xE6FFFFFF);
                g.render(fxVb, fxIb, fxApp, t);
            }
        }
        if (wall) {
            // как в оригинале: u0 = (tex % 16) / 256 — берётся угол атласа у (tex%16, tex/16), а не сама иконка
            int tx = Tile.getTex(in, 2, 0);
            setFx(tx & 15, tx >> 4, -2, 18, -2, 18, true);
            t.setIdentity();
            t.postTranslate(0, 0, -0.5f);
            fxVb.setDefaultColor(0x801A1A1A);
            g.render(fxVb, fxIb, fxApp, t);
        }
    }

    /** Развёртка квадрата от тексела (tu, tv): вершины (-1,-1) (1,-1) (-1,1) (1,1); u — зеркально. */
    private VertexArray fxTa;

    private void setFx(int tu, int tv, int a0, int a1, int b0, int b1, boolean flipU) {
        int ul = tu + (flipU ? a1 : a0), ur = tu + (flipU ? a0 : a1);
        int vt = tv + b0, vb = tv + b1;
        fxUv[0] = (short) ul; fxUv[1] = (short) vb;
        fxUv[2] = (short) ur; fxUv[3] = (short) vb;
        fxUv[4] = (short) ul; fxUv[5] = (short) vt;
        fxUv[6] = (short) ur; fxUv[7] = (short) vt;
        // один массив на все вызовы: M3G-объекты не должны становиться мусором (см. DynMesh)
        if (fxTa == null) {
            fxTa = new VertexArray(4, 2, 2);
            if (MemDbg.on) MemDbg.va(fxTa, "HandRenderer.fxTa");
        }
        fxTa.set(0, 4, fxUv);
        fxVb.setTexCoords(0, fxTa, 1f / 256f, null);
    }
}
