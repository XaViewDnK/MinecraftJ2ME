import javax.microedition.m3g.*;

/**
 * Частицы как ParticleEngine 0.6.1: три атласа (particles.png, terrain.png, gui/items.png), билборды по взгляду игрока.
 * Виды: TerrainParticle (крошки блоков), BreakingItemParticle (крошки еды), Smoke, Explode, Flame, Lava, Bubble,
 * Crit, RedDust. Дальше 16 блоков от игрока не создаются (LevelRenderer::addParticle).
 */
public final class Particles {
    static final int MAX = 600;
    static final int T_MISC = 0, T_TERRAIN = 1, T_ITEM = 2;
    static final int K_BASE = 0, K_SMOKE = 1, K_EXPLODE = 2, K_FLAME = 3, K_LAVA = 4, K_BUBBLE = 5, K_CRIT = 6, K_REDDUST = 7;
    final float[] x = new float[MAX], y = new float[MAX], z = new float[MAX];
    final float[] xo = new float[MAX], yo = new float[MAX], zo = new float[MAX];
    final float[] xd = new float[MAX], yd = new float[MAX], zd = new float[MAX];
    final float[] size = new float[MAX], oSize = new float[MAX], grav = new float[MAX], uo = new float[MAX], vo = new float[MAX];
    final float[] rc = new float[MAX], gc = new float[MAX], bc = new float[MAX], bbw = new float[MAX];
    final int[] age = new int[MAX], life = new int[MAX], tex = new int[MAX];
    final byte[] atlas = new byte[MAX], kind = new byte[MAX];
    final boolean[] ground = new boolean[MAX], noPhys = new boolean[MAX];
    int count;
    Level level;
    final Rand r = new Rand();
    final MeshBuilder[] mb = { new MeshBuilder(), new MeshBuilder(), new MeshBuilder() };
    final Appearance[] app = new Appearance[3];
    final Transform tr = new Transform();
    /** постоянные буферы вершин по атласам (без M3G-мусора, см. DynMesh) */
    private final DynMesh[] dyn = { new DynMesh(true), new DynMesh(true), new DynMesh(true) };
    private final float[] bias = new float[3];
    float camX, camY, camZ;

    public void setLevel(Level l, LevelRenderer lr) {
        level = l;
        count = 0;
        if (app[0] == null) {
            app[T_MISC] = app(new Texture2D(R.image2D("/particles.png")));
            app[T_ITEM] = app(new Texture2D(R.image2D("/gui/items.png")));
        }
        if (app[T_TERRAIN] == null || app[T_TERRAIN].getTexture(0) != lr.terrainTex) app[T_TERRAIN] = app(lr.terrainTex);
        for (int i = 0; i < 3; i++) if (app[i].getFog() != lr.fog) app[i].setFog(lr.fog);
    }

    private static Appearance app(Texture2D t) {
        t.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        t.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        t.setBlending(Texture2D.FUNC_MODULATE);
        Appearance a = new Appearance();
        a.setTexture(0, t);
        CompositingMode cm = new CompositingMode();
        cm.setAlphaThreshold(0.5f);
        a.setCompositingMode(cm);
        PolygonMode pm = new PolygonMode();
        pm.setCulling(PolygonMode.CULL_NONE);
        a.setPolygonMode(pm);
        return a;
    }

    private float rnd() {
        return r.nextFloat();
    }

    /** Particle::Particle: случайный разлёт, размер, время жизни. */
    private int add(float px, float py, float pz, float xa, float ya, float za) {
        if (count >= MAX) return -1;
        Player p = level.player;
        if (p != null) {
            float dx = p.x - px, dy = p.y + Player.EYE - py, dz = p.z - pz;
            if (dx * dx + dy * dy + dz * dz > 16 * 16) return -1;
        }
        int i = count++;
        x[i] = xo[i] = px; y[i] = yo[i] = py; z[i] = zo[i] = pz;
        float vx = xa + (rnd() * 2 - 1) * 0.4f, vy = ya + (rnd() * 2 - 1) * 0.4f, vz = za + (rnd() * 2 - 1) * 0.4f;
        float speed = (rnd() + rnd() + 1) * 0.15f;
        float dd = Mth.sqrt(vx * vx + vy * vy + vz * vz);
        float mul = dd > 0 ? 0.4f * speed / dd : 0;
        xd[i] = vx * mul; yd[i] = vy * mul + 0.1f; zd[i] = vz * mul;
        uo[i] = rnd() * 3; vo[i] = rnd() * 3;
        size[i] = oSize[i] = (rnd() * 0.5f + 0.5f) * 2;
        life[i] = (int) (4f / (rnd() * 0.9f + 0.1f));
        age[i] = 0; tex[i] = 0; grav[i] = 0; bbw[i] = 0.2f;
        rc[i] = gc[i] = bc[i] = 1;
        atlas[i] = T_MISC; kind[i] = K_BASE;
        ground[i] = false; noPhys[i] = false;
        return i;
    }

    /** TerrainParticle: цвет 0.6 * Tile::getColor (у травы без тонировки), размер / 2. */
    private int terrain(float px, float py, float pz, float xa, float ya, float za, int id, int data, int bx, int bz) {
        int i = add(px, py, pz, xa, ya, za);
        if (i < 0) return i;
        atlas[i] = T_TERRAIN;
        tex[i] = Tile.getTex(id, 2, data);
        grav[i] = 1;
        size[i] /= 2;
        int c = 0xFFFFFF;
        if (id != Tile.GRASS) {
            if (Tile.tint[id] == Tile.T_FOLIAGE) c = id == Tile.LEAVES && (data & 3) == 1 ? 0x619961 : (id == Tile.LEAVES && (data & 3) == 2 ? 0x80A755 : 0x48B518);
            else if (Tile.tint[id] == Tile.T_GRASS) c = id == Tile.TALLGRASS && data == 0 ? 0xFFFFFF : 0x339933;
        }
        rc[i] = 0.6f * ((c >> 16) & 255) / 255f;
        gc[i] = 0.6f * ((c >> 8) & 255) / 255f;
        bc[i] = 0.6f * (c & 255) / 255f;
        return i;
    }

    /** ParticleEngine::destroy: 3x2x3 крошки. */
    public void destroy(int bx, int by, int bz, int id, int data) {
        if (id == 0) return;
        for (int xx = 0; xx < 3; xx++)
            for (int yy = 1; yy < 3; yy++)
                for (int zz = 0; zz < 3; zz++) {
                    float xp = bx + (xx + 0.5f) / 3, yp = by + (yy + 0.5f) / 3, zp = bz + (zz + 0.5f) / 3;
                    terrain(xp, yp, zp, 2 * (xp - bx - 0.5f), 2 * (yp - by - 0.5f), 2 * (zp - bz - 0.5f), id, data, bx, bz);
                }
    }

    /** ParticleEngine::crack: крошка с грани по форме блока, setPower(0.2), scale(0.6). */
    public void crack(int bx, int by, int bz, int face, int id, int data, float[] shape) {
        if (id == 0) return;
        float rr = 0.1f;
        float x0 = shape[0], y0 = shape[1], z0 = shape[2], x1 = shape[3], y1 = shape[4], z1 = shape[5];
        float xp = bx + rnd() * ((x1 - x0) - rr * 2) + rr + x0;
        float yp = by + rnd() * ((y1 - y0) - rr * 2) + rr + y0;
        float zp = bz + rnd() * ((z1 - z0) - rr * 2) + rr + z0;
        switch (face) {
            case 0: yp = by + y0 - rr; break;
            case 1: yp = by + y1 + rr; break;
            case 2: zp = bz + z0 - rr; break;
            case 3: zp = bz + z1 + rr; break;
            case 4: xp = bx + x0 - rr; break;
            case 5: xp = bx + x1 + rr; break;
        }
        int i = terrain(xp, yp, zp, 0, 0, 0, id, data, bx, bz);
        if (i < 0) return;
        xd[i] *= 0.2f; yd[i] = (yd[i] - 0.1f) * 0.2f + 0.1f; zd[i] *= 0.2f;
        bbw[i] = 0.2f * 0.6f;
        size[i] *= 0.6f;
    }

    /** BreakingItemParticle (iconcrack): крошки иконки предмета. */
    public void iconCrack(float px, float py, float pz, float xa, float ya, float za, int item) {
        int i = add(px, py, pz, 0, 0, 0);
        if (i < 0) return;
        xd[i] = xd[i] * 0.1f + xa; yd[i] = yd[i] * 0.1f + ya; zd[i] = zd[i] * 0.1f + za;
        atlas[i] = (byte) (item < 256 ? T_TERRAIN : T_ITEM);
        tex[i] = ItemMeshes.icon(item, 0);
        grav[i] = 1;
        size[i] /= 2;
    }

    /** SmokeParticle (scale 2.5 — largesmoke). */
    public void smoke(float px, float py, float pz, float xa, float ya, float za, float scale) {
        int i = add(px, py, pz, 0, 0, 0);
        if (i < 0) return;
        xd[i] = xd[i] * 0.1f + xa; yd[i] = yd[i] * 0.1f + ya; zd[i] = zd[i] * 0.1f + za;
        rc[i] = gc[i] = bc[i] = Mth.random() * 0.5f;
        size[i] *= 0.75f * scale;
        oSize[i] = size[i];
        life[i] = (int) (scale * 8f / (Mth.random() * 0.8f + 0.2f));
        kind[i] = K_SMOKE;
    }

    public void explode(float px, float py, float pz, float xa, float ya, float za) {
        int i = add(px, py, pz, xa, ya, za);
        if (i < 0) return;
        xd[i] = xa + (Mth.random() * 2 - 1) * 0.05f;
        yd[i] = ya + (Mth.random() * 2 - 1) * 0.05f;
        zd[i] = za + (Mth.random() * 2 - 1) * 0.05f;
        rc[i] = gc[i] = bc[i] = rnd() * 0.3f + 0.7f;
        size[i] = rnd() * rnd() * 6 + 1;
        life[i] = (int) (16f / (rnd() * 0.8f + 0.2f)) + 2;
        kind[i] = K_EXPLODE;
    }

    public void flame(float px, float py, float pz, float xa, float ya, float za) {
        int i = add(px, py, pz, xa, ya, za);
        if (i < 0) return;
        xd[i] = xd[i] * 0.01f + xa; yd[i] = yd[i] * 0.01f + ya; zd[i] = zd[i] * 0.01f + za;
        x[i] = xo[i] = px + (rnd() - rnd()) * 0.05f;
        y[i] = yo[i] = py + (rnd() - rnd()) * 0.05f;
        z[i] = zo[i] = pz + (rnd() - rnd()) * 0.05f;
        oSize[i] = size[i];
        life[i] = (int) (8f / (Mth.random() * 0.8f + 0.2f)) + 4;
        noPhys[i] = true;
        tex[i] = 48;
        kind[i] = K_FLAME;
    }

    public void lava(float px, float py, float pz) {
        int i = add(px, py, pz, 0, 0, 0);
        if (i < 0) return;
        xd[i] *= 0.8f; zd[i] *= 0.8f;
        yd[i] = rnd() * 0.4f + 0.05f;
        size[i] *= rnd() * 2 + 0.2f;
        oSize[i] = size[i];
        life[i] = (int) (16 / (Mth.random() * 0.8f + 0.2f));
        tex[i] = 49;
        kind[i] = K_LAVA;
    }

    public void bubble(float px, float py, float pz, float xa, float ya, float za) {
        int i = add(px, py, pz, xa, ya, za);
        if (i < 0) return;
        tex[i] = 32;
        bbw[i] = 0.02f;
        size[i] *= rnd() * 0.6f + 0.2f;
        xd[i] = xa * 0.2f + (Mth.random() * 2 - 1) * 0.02f;
        yd[i] = ya * 0.2f + (Mth.random() * 2 - 1) * 0.02f;
        zd[i] = za * 0.2f + (Mth.random() * 2 - 1) * 0.02f;
        life[i] = (int) (8 / (Mth.random() * 0.8f + 0.2f));
        kind[i] = K_BUBBLE;
    }

    public void crit(float px, float py, float pz, float xa, float ya, float za) {
        int i = add(px, py, pz, 0, 0, 0);
        if (i < 0) return;
        xd[i] = xd[i] * 0.1f + xa * 0.4f; yd[i] = yd[i] * 0.1f + ya * 0.4f; zd[i] = zd[i] * 0.1f + za * 0.4f;
        rc[i] = gc[i] = bc[i] = Mth.random() * 0.3f + 0.6f;
        size[i] *= 0.75f;
        oSize[i] = size[i];
        life[i] = (int) (6 / (Mth.random() * 0.8f + 0.6f));
        tex[i] = 65;
        kind[i] = K_CRIT;
        tickOne(i);
    }

    /** RedStoneOreTile::poofParticles */
    public void redstonePoof(int bx, int by, int bz) {
        float rr = 1 / 16f;
        for (int i = 0; i < 6; i++) {
            float xx = bx + rnd(), yy = by + rnd(), zz = bz + rnd();
            if (i == 0 && !level.isSolidBlockingTile(bx, by + 1, bz)) yy = by + 1 + rr;
            if (i == 1 && !level.isSolidBlockingTile(bx, by - 1, bz)) yy = by - rr;
            if (i == 2 && !level.isSolidBlockingTile(bx, by, bz + 1)) zz = bz + 1 + rr;
            if (i == 3 && !level.isSolidBlockingTile(bx, by, bz - 1)) zz = bz - rr;
            if (i == 4 && !level.isSolidBlockingTile(bx + 1, by, bz)) xx = bx + 1 + rr;
            if (i == 5 && !level.isSolidBlockingTile(bx - 1, by, bz)) xx = bx - rr;
            if (xx < bx || xx > bx + 1 || yy < 0 || yy > by + 1 || zz < bz || zz > bz + 1) redDust(xx, yy, zz);
        }
    }

    public void largeSmoke(float px, float py, float pz) {
        smoke(px, py, pz, 0, 0, 0, 2.5f);
    }

    /** snowballpoof: BreakingItemParticle с иконкой снежка */
    public void snowballPoof(float px, float py, float pz) {
        int i = add(px, py, pz, 0, 0, 0);
        if (i < 0) return;
        atlas[i] = T_ITEM;
        tex[i] = ItemMeshes.icon(332, 0);
        grav[i] = 1;
        size[i] /= 2;
    }

    public void redDust(float px, float py, float pz) {
        int i = add(px, py, pz, 0, 0, 0);
        if (i < 0) return;
        xd[i] *= 0.1f; yd[i] *= 0.1f; zd[i] *= 0.1f;
        float brr = Mth.random() * 0.4f + 0.6f;
        rc[i] = (Mth.random() * 0.2f + 0.8f) * brr;
        gc[i] = 0;
        bc[i] = 0;
        size[i] *= 0.75f;
        oSize[i] = size[i];
        life[i] = (int) (8 / (Mth.random() * 0.8f + 0.2f));
        kind[i] = K_REDDUST;
    }

    // ---- движение ----

    private boolean blocked(float px, float py, float pz) {
        int t = level.getTile(Mth.floor(px), Mth.floor(py), Mth.floor(pz));
        return t != 0 && Tile.opaque[t];
    }

    /** Entity::move для маленькой коробки: по осям, упор в твёрдые кубы. */
    private void move(int i, float dx, float dy, float dz) {
        if (noPhys[i]) {
            x[i] += dx; y[i] += dy; z[i] += dz;
            return;
        }
        float h = bbw[i] / 2;
        float ny = y[i] + dy;
        if (blocked(x[i], dy < 0 ? ny - h : ny + h, z[i])) {
            ground[i] = dy < 0;
            yd[i] = 0;
        } else {
            y[i] = ny;
            ground[i] = false;
        }
        float nx = x[i] + dx;
        if (blocked(dx < 0 ? nx - h : nx + h, y[i], z[i])) xd[i] = 0;
        else x[i] = nx;
        float nz = z[i] + dz;
        if (blocked(x[i], y[i], dz < 0 ? nz - h : nz + h)) zd[i] = 0;
        else z[i] = nz;
    }

    /** Particle::tick и переопределения видов; false — частица умерла. */
    private boolean tickOne(int i) {
        xo[i] = x[i]; yo[i] = y[i]; zo[i] = z[i];
        switch (kind[i]) {
            case K_SMOKE:
            case K_REDDUST: {
                if (age[i]++ >= life[i]) return false;
                tex[i] = 7 - age[i] * 8 / life[i];
                if (kind[i] == K_SMOKE) yd[i] += 0.004f;
                float oy = y[i];
                move(i, xd[i], yd[i], zd[i]);
                if (y[i] == oy) { xd[i] *= 1.1f; zd[i] *= 1.1f; }
                xd[i] *= 0.96f; yd[i] *= 0.96f; zd[i] *= 0.96f;
                break;
            }
            case K_EXPLODE:
                if (age[i]++ >= life[i]) return false;
                tex[i] = 7 - age[i] * 8 / life[i];
                yd[i] += 0.004f;
                move(i, xd[i], yd[i], zd[i]);
                xd[i] *= 0.9f; yd[i] *= 0.9f; zd[i] *= 0.9f;
                break;
            case K_FLAME:
                if (age[i]++ >= life[i]) return false;
                move(i, xd[i], yd[i], zd[i]);
                xd[i] *= 0.96f; yd[i] *= 0.96f; zd[i] *= 0.96f;
                break;
            case K_LAVA:
                if (age[i]++ >= life[i]) return false;
                if (rnd() > age[i] / (float) life[i]) smoke(x[i], y[i], z[i], xd[i], yd[i], zd[i], 1);
                yd[i] -= 0.03f;
                move(i, xd[i], yd[i], zd[i]);
                xd[i] *= 0.999f; yd[i] *= 0.999f; zd[i] *= 0.999f;
                break;
            case K_BUBBLE:
                yd[i] += 0.002f;
                move(i, xd[i], yd[i], zd[i]);
                xd[i] *= 0.85f; yd[i] *= 0.85f; zd[i] *= 0.85f;
                if (!Tile.isWater(level.getTile(Mth.floor(x[i]), Mth.floor(y[i]), Mth.floor(z[i])))) return false;
                if (life[i]-- <= 0) return false;
                return true;
            case K_CRIT:
                if (age[i]++ >= life[i]) return false;
                move(i, xd[i], yd[i], zd[i]);
                gc[i] *= 0.96f; bc[i] *= 0.9f;
                xd[i] *= 0.7f; yd[i] *= 0.7f; zd[i] *= 0.7f;
                yd[i] -= 0.02f;
                break;
            default:
                if (age[i]++ >= life[i]) return false;
                yd[i] -= 0.04f * grav[i];
                move(i, xd[i], yd[i], zd[i]);
                xd[i] *= 0.98f; yd[i] *= 0.98f; zd[i] *= 0.98f;
                break;
        }
        if (ground[i]) { xd[i] *= 0.7f; zd[i] *= 0.7f; }
        return true;
    }

    public void tick() {
        for (int i = 0; i < count; i++) {
            if (!tickOne(i)) {
                count--;
                copy(count, i);
                i--;
            }
        }
    }

    private void copy(int f, int t) {
        x[t] = x[f]; y[t] = y[f]; z[t] = z[f]; xo[t] = xo[f]; yo[t] = yo[f]; zo[t] = zo[f];
        xd[t] = xd[f]; yd[t] = yd[f]; zd[t] = zd[f];
        size[t] = size[f]; oSize[t] = oSize[f]; grav[t] = grav[f]; uo[t] = uo[f]; vo[t] = vo[f];
        rc[t] = rc[f]; gc[t] = gc[f]; bc[t] = bc[f]; bbw[t] = bbw[f];
        age[t] = age[f]; life[t] = life[f]; tex[t] = tex[f];
        atlas[t] = atlas[f]; kind[t] = kind[f]; ground[t] = ground[f]; noPhys[t] = noPhys[f];
    }

    /**
     * ParticleEngine::render: yRot/xRot — поворот взгляда; позиции относительно камеры (1/256 блока),
     * развёртка в 1/1024 атласа.
     */
    public void render(Graphics3D g, float a, float yRot, float xRot, float cx, float cy, float cz) {
        if (count == 0) return;
        for (int k = 0; k < 3; k++) mb[k].clear();
        float xa = Mth.cos(yRot * Mth.DEG), za = Mth.sin(yRot * Mth.DEG);
        float xs = Mth.sin(xRot * Mth.DEG);
        float xa2 = -za * xs, za2 = xa * xs, ya = Mth.cos(xRot * Mth.DEG);
        for (int i = 0; i < count; i++) {
            float px = xo[i] + (x[i] - xo[i]) * a, py = yo[i] + (y[i] - yo[i]) * a, pz = zo[i] + (z[i] - zo[i]) * a;
            float s = size[i];
            float br;
            switch (kind[i]) {
                case K_SMOKE: case K_REDDUST: case K_CRIT: {
                    float l = Mth.clamp((age[i] + a) / life[i] * 32, 0, 1);
                    s = oSize[i] * l;
                    break;
                }
                case K_FLAME: {
                    float q = (age[i] + a) / life[i];
                    s = oSize[i] * (1 - q * q * 0.5f);
                    break;
                }
                case K_LAVA: {
                    float q = (age[i] + a) / life[i];
                    s = oSize[i] * (1 - q * q);
                    break;
                }
            }
            if (kind[i] == K_FLAME || kind[i] == K_LAVA) br = 1;
            else br = LevelRenderer.BRF[level.getRawBrightness(Mth.floor(px), Mth.floor(py), Mth.floor(pz))];
            int col = 0xFF000000 | ((int) (Mth.clamp(rc[i] * br, 0, 1) * 255) << 16) | ((int) (Mth.clamp(gc[i] * br, 0, 1) * 255) << 8)
                    | (int) (Mth.clamp(bc[i] * br, 0, 1) * 255);
            float rr = 0.1f * s;
            int t = tex[i];
            int u0, v0, u1, v1;
            if (atlas[i] == T_MISC) {
                u0 = (t & 15) * 64; v0 = (t >> 4) * 64;
                u1 = u0 + 63; v1 = v0 + 63;
                // Particle::render: u зеркально (u1 слева)
                int tmp = u0; u0 = u1; u1 = tmp;
            } else {
                u0 = (t & 15) * 64 + (int) (uo[i] * 16); v0 = (t >> 4) * 64 + (int) (vo[i] * 16);
                u1 = u0 + 16; v1 = v0 + 16;
            }
            float qx = (px - cx) * 256, qy = (py - cy) * 256, qz = (pz - cz) * 256;
            float ax = (xa * rr) * 256, ay = (ya * rr) * 256, az = (za * rr) * 256, bx = (xa2 * rr) * 256, bz = (za2 * rr) * 256;
            mb[atlas[i]].quad((int) (qx - ax - bx), (int) (qy - ay), (int) (qz - az - bz), u0, v1, col,
                    (int) (qx + ax - bx), (int) (qy - ay), (int) (qz + az - bz), u1, v1, col,
                    (int) (qx + ax + bx), (int) (qy + ay), (int) (qz + az + bz), u1, v0, col,
                    (int) (qx - ax + bx), (int) (qy + ay), (int) (qz - az + bz), u0, v0, col);
        }
        tr.setIdentity();
        for (int k = 0; k < 3; k++) {
            MeshBuilder m = mb[k];
            if (m.verts == 0) continue;
            bias[0] = cx; bias[1] = cy; bias[2] = cz;
            VertexBuffer vb = dyn[k].load(m, 1f / 256f, bias, 1f / 1024f);
            g.render(vb, DynMesh.ib(m.verts), app[k], tr);
        }
    }

    // ---- Level::animateTick: 100 случайных блоков в радиусе 16 за тик ----

    public void animateTick(int px, int py, int pz) {
        for (int n = 0; n < 100; n++) {
            int bx = px + r.nextInt(16) - r.nextInt(16), by = py + r.nextInt(16) - r.nextInt(16), bz = pz + r.nextInt(16) - r.nextInt(16);
            int t = level.getTile(bx, by, bz);
            if (t == 0) continue;
            switch (t) {
                case Tile.TORCH: {
                    // TorchTile::animateTick
                    int d = level.getData(bx, by, bz);
                    float xx = bx + 0.5f, yy = by + 0.7f, zz = bz + 0.5f, h = 0.22f, rr = 0.27f;
                    if (d == 1) { xx -= rr; yy += h; }
                    else if (d == 2) { xx += rr; yy += h; }
                    else if (d == 3) { zz -= rr; yy += h; }
                    else if (d == 4) { zz += rr; yy += h; }
                    smoke(xx, yy, zz, 0, 0, 0, 1);
                    flame(xx, yy, zz, 0, 0, 0);
                    break;
                }
                case Tile.FURNACE_LIT: {
                    // FurnaceTile::animateTick
                    int d = level.getData(bx, by, bz);
                    float xx = bx + 0.5f, yy = by + rnd() * 6 / 16f, zz = bz + 0.5f, rr = 0.52f, ss = rnd() * 0.6f - 0.3f;
                    if (d == 4) { xx -= rr; zz += ss; }
                    else if (d == 5) { xx += rr; zz += ss; }
                    else if (d == 2) { xx += ss; zz -= rr; }
                    else if (d == 3) { xx += ss; zz += rr; }
                    else break;
                    smoke(xx, yy, zz, 0, 0, 0, 1);
                    flame(xx, yy, zz, 0, 0, 0);
                    break;
                }
                case Tile.LAVA:
                case Tile.STILL_LAVA: {
                    // LiquidTile::animateTick: над открытой лавой — капли
                    int above = level.getTile(bx, by + 1, bz);
                    if (above == 0 && r.nextInt(100) == 0) {
                        int d = level.getData(bx, by, bz);
                        float h = 1 - ((d >= 8 ? 0 : d) + 1) / 9f;
                        lava(bx + rnd(), by + h, bz + rnd());
                    }
                    break;
                }
                case Tile.REDSTONE_ORE_LIT:
                    redstonePoof(bx, by, bz);
                    break;
            }
        }
    }
}
