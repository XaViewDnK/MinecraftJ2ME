import javax.microedition.m3g.*;

/**
 * Отрисовка мира через M3G. Геометрия строится секциями 16x16x16 и склеивается в меши столбцов 16x128x16.
 *
 * Почему так (замеры 2026-10-13, JL-Mod 0.87.1, Adreno 725):
 * - каждый Graphics3D.render() в JL-Mod перебрасывается в поток M3G и обратно (~0.3 мс), поэтому меши столбцов
 *   лежат в двух узлах Group и весь мир рисуется двумя вызовами render(Node); видимость — setRenderingEnable;
 * - главная цена — разбор вершин драйвером GLES 1.x на CPU, поэтому меш столбца делится по направлению граней
 *   и части, повёрнутые от камеры, драйверу не отдаются (их всё равно отбросило бы отсечение граней);
 * - блокировка ядра M3G в JL-Mod пустая: создание объектов в потоке игры и финализация мусора в потоке M3G
 *   роняют процесс. Поэтому в J2ME Loader M3G-объекты мира не выбрасываются: части мешей живут в пуле по классам
 *   ёмкости (лишние вершины — вырожденные квады), материалы и узлы общие для всех миров.
 * Память (2026-10-14, жалобы с E75/Asha: хип 4..16 МБ): секции и меши держатся только для столбцов в пределах
 * дальности прорисовки, квады секций — в сжатом виде без цвета (SecBuf), цвет считается при склейке.
 * С 2026-10-14 (MemoryView: кэш секций 2.3 МБ, вершины M3G 4.4 МБ) квады секций держатся только у столбцов рядом
 * с игроком (там правки); у дальних после склейки забываются, и правка там перестраивает весь столбец. Смена
 * времени суток перекрашивает готовые части по коду света квада (Part.info, 2 байта на квад) без склейки.
 * Вершины частей: цвет твёрдого меша — RGB (альфа всегда 255), развёртка — байтами со сдвигом, если хватает.
 * Твёрдый меш = слои OPAQUE и ALPHATEST оригинала (оба с отсечением граней, второй с альфа-тестом — рисуются одним
 * проходом с альфа-тестом); полупрозрачный (вода, лёд) — после сущностей и частиц, от дальних к ближним.
 */
public final class LevelRenderer {
    static final int SX = 16, SY = 8, SZ = 16, NS = SX * SY * SZ;
    /** меши: твёрдый и полупрозрачный; MESH[слой тайла] */
    static final int NM = 2;
    static final int[] MESH = { 0, 0, 1 };
    /** группа = столбец чанков 16x16 */
    static final int GS = 0, GW = 16 >> GS, NG = GW * GW, GB = 16 << GS;
    /** вершин в одной части меша (меньше — меньше временные буферы склейки) */
    static final int PART_MAX = 8192;

    final Level level;
    /** сжатые квады секций (SecBuf): вершины, v вершин, квады */
    final int[][][] sP = new int[NM][NS][];
    final byte[][][] sLv = new byte[NM][NS][];
    final int[][][] sQ = new int[NM][NS][];
    final int[][] sVerts = new int[NM][NS];
    /** группы, которые надо склеить сразу, не дожидаясь остальных секций столбца (правки рядом с игроком) */
    final boolean[] grpUrgent = new boolean[NG];
    /** части мешей групп (по направлению граней и лимиту вершин) */
    final Part[][][] gpart = new Part[NM][NG][];
    /** текущее состояние видимости групп */
    private final boolean[][] shown = new boolean[NM][NG];
    private final int[] blendSlot = new int[NG];
    final boolean[] grpDirty = new boolean[NG];
    /** столбцы, которые надо перекрасить (сменилась темнота неба) */
    private final boolean[] grpRecolor = new boolean[NG];
    /** квады секции забыты (столбец далеко от игрока): перед склейкой секцию надо построить заново */
    final boolean[] lost = new boolean[NS];
    /** секция строится только ради кэша квадов (геометрия не менялась) — столбец не склеивать */
    private final boolean[] quiet = new boolean[NS];
    /** столбец игрока: кэш квадов держится в радиусе NEAR столбцов от него */
    private int nearX = -99, nearZ = -99;
    static final int NEAR = 1;
    final int[] grpY0 = new int[NG], grpY1 = new int[NG];
    /** столбцы в пределах дальности: только для них строятся секции и меши */
    final boolean[] inRange = new boolean[NG];
    private int rangeX = Integer.MIN_VALUE, rangeZ, rangeDist = -1;

    static int group(int sx, int sz) {
        return (sx >> GS) + (sz >> GS) * GW;
    }
    final boolean[] dirty = new boolean[NS];
    final boolean[] built = new boolean[NS];
    /** сундуки секции (x | z << 8 | y << 16) — рисуются отдельной моделью, см. renderChests */
    final int[][] sChest = new int[NS][];
    private int[] chestTmp = new int[16];
    private int nChestTmp;
    int dirtyCount;

    // ---- общие для всех миров M3G-объекты ----

    public static Texture2D terrainTex;
    public static final Fog fog = new Fog();
    public static final Appearance[] app = new Appearance[3];
    /** материалы мешей мира: твёрдый (отсечение граней + альфа-тест) и полупрозрачный */
    static final Appearance[] meshApp = new Appearance[NM];
    /** полупрозрачные меши по слою: дальние рисуются раньше (Appearance.setLayer 1..63) */
    private static final Appearance[] blendApp = new Appearance[63];
    /** узлы мешей: твёрдый мир и полупрозрачный */
    static final Group[] root = { new Group(), new Group() };
    static final Transform identity = new Transform();
    /** пул частей: [класс ёмкости * 2 + (байтовые позиции ? 1 : 0)] */
    private static java.util.Vector[] pool;
    /** вершин в пуле; вне J2ME Loader лишнее отдаётся сборщику мусора */
    private static int pooledVerts;
    /** запас частей в пуле на телефонах: 16K вершин ~ 128 КБ M3G (было 48K ~ 384 КБ) */
    private static final int POOL_LIMIT = 16 * 1024;
    private static boolean keepPool;
    /** классы ёмкости частей (кратны 4, шаг ~1/8) */
    private static int[] CAPS;
    private static LevelRenderer current;

    /** Часть меша: узел Mesh со своими массивами ёмкости cap (CAPS[cls]). */
    static final class Part {
        Mesh mesh;
        VertexBuffer vb;
        VertexArray pa, ca, ta;
        int cls, verts, dir, key, comps;
        boolean bytes, on;
        /** биас развёртки байтами */
        float[] uvBias;
        /** квады: код света | затенение << 8 | оттенок << 10 — для перекраски без склейки */
        short[] info;
        /** крайняя плоскость граней направления dir (в блоках мира) */
        float plane;
    }

    final SecBuf[] mb = { new SecBuf(), new SecBuf() };

    public int renderDist = 80;
    public int lastDrawn;
    /** Dimension::brightnessRamp: яркость по уровню света 0..15 (0.15..0.9), BR — в 0..255 */
    static final int[] BR = new int[16];
    static final float[] BRF = new float[16];
    static final int[] FACE_SHADE = { 128, 255, 204, 204, 153, 153 };

    static {
        for (int i = 0; i < 16; i++) {
            float v = 1f - i / 16f;
            float b = (1f - v) / (v * 3f + 1f) * 0.95f + 0.15f;
            BRF[i] = b;
            BR[i] = (int) (b * 255f);
        }
    }

    public LevelRenderer(Level level, Image2D terrain) {
        this.level = level;
        level.renderer = this;
        if (terrainTex == null) initShared(terrain);
        // меши прошлого мира — обратно в пул
        if (current != null && current != this) current.retireAll();
        current = this;
        for (int g = 0; g < NG; g++) blendSlot[g] = -1;
    }

    private static void initShared(Image2D terrain) {
        keepPool = BtNet.onLoader();
        terrainTex = new Texture2D(terrain);
        terrainTex.setFiltering(Texture2D.FILTER_BASE_LEVEL, Texture2D.FILTER_NEAREST);
        terrainTex.setWrapping(Texture2D.WRAP_CLAMP, Texture2D.WRAP_CLAMP);
        terrainTex.setBlending(Texture2D.FUNC_MODULATE);
        fog.setMode(Fog.LINEAR);
        for (int i = 0; i < 3; i++) {
            Appearance a = new Appearance();
            a.setTexture(0, terrainTex);
            CompositingMode cm = new CompositingMode();
            PolygonMode pm = new PolygonMode();
            pm.setShading(PolygonMode.SHADE_SMOOTH);
            pm.setPerspectiveCorrectionEnable(true);
            if (i == Tile.L_OPAQUE) {
                cm.setBlending(CompositingMode.REPLACE);
                pm.setCulling(PolygonMode.CULL_BACK);
            } else if (i == Tile.L_CUTOUT) {
                cm.setBlending(CompositingMode.REPLACE);
                cm.setAlphaThreshold(0.5f);
                pm.setCulling(PolygonMode.CULL_NONE);
            } else {
                cm.setBlending(CompositingMode.ALPHA);
                cm.setDepthWriteEnable(false);
                pm.setCulling(PolygonMode.CULL_NONE);
            }
            a.setCompositingMode(cm);
            a.setPolygonMode(pm);
            a.setFog(fog);
            app[i] = a;
        }
        // GameRenderer: terrain-0 и terrain-1 — оба с GL_CULL_FACE, второй с альфа-тестом
        Appearance solid = new Appearance();
        solid.setTexture(0, terrainTex);
        CompositingMode scm = new CompositingMode();
        scm.setBlending(CompositingMode.REPLACE);
        scm.setAlphaThreshold(0.5f);
        PolygonMode spm = new PolygonMode();
        spm.setShading(PolygonMode.SHADE_SMOOTH);
        spm.setPerspectiveCorrectionEnable(true);
        spm.setCulling(PolygonMode.CULL_BACK);
        solid.setCompositingMode(scm);
        solid.setPolygonMode(spm);
        solid.setFog(fog);
        meshApp[0] = solid;
        meshApp[1] = app[Tile.L_BLEND];
        Appearance b = app[Tile.L_BLEND];
        for (int i = 0; i < blendApp.length; i++) {
            Appearance a = new Appearance();
            a.setTexture(0, b.getTexture(0));
            a.setCompositingMode(b.getCompositingMode());
            a.setPolygonMode(b.getPolygonMode());
            a.setFog(fog);
            a.setLayer(i + 1);
            blendApp[i] = a;
        }
        int[] caps = new int[128];
        int n = 0, c = 16;
        while (c < PART_MAX) {
            caps[n++] = c;
            int next = (c + c / 8 + 3) & ~3;
            c = next < c + 16 ? c + 16 : next;
        }
        caps[n++] = PART_MAX;
        CAPS = new int[n];
        System.arraycopy(caps, 0, CAPS, 0, n);
        pool = new java.util.Vector[n * 8];
        for (int i = 0; i < pool.length; i++) pool[i] = new java.util.Vector();
    }

    /** Вернуть все части мешей в пул (смена мира). */
    private void retireAll() {
        for (int k = 0; k < NM; k++)
            for (int g = 0; g < NG; g++) {
                retire(k, gpart[k][g]);
                gpart[k][g] = null;
            }
    }

    private static void retire(int k, Part[] ps) {
        if (ps == null) return;
        for (int i = 0; i < ps.length; i++) {
            Part p = ps[i];
            root[k].removeChild(p.mesh);
            if (p.on) {
                p.on = false;
                p.mesh.setRenderingEnable(false);
            }
            // на телефонах лишние части — сборщику мусора (в J2ME Loader финализация M3G роняет процесс)
            if (!keepPool && pooledVerts + CAPS[p.cls] > POOL_LIMIT) continue;
            pooledVerts += CAPS[p.cls];
            pool[p.key].addElement(p);
        }
    }

    public void allDirty() {
        dirtyCount = 0;
        for (int i = 0; i < NS; i++) {
            dirty[i] = inRange[i & 255];
            quiet[i] = false;
            if (dirty[i]) dirtyCount++;
        }
    }

    /**
     * Столбцы в пределах дальности прорисовки (с запасом и гистерезисом): вошедшие строятся, ушедшие забываются
     * вместе с секциями и мешами — так память рендера не зависит от размера мира.
     */
    public void updateRange(float cx, float cz) {
        int bx = Mth.floor(cx), bz = Mth.floor(cz);
        if (rangeDist == renderDist && Math.abs(bx - rangeX) < 4 && Math.abs(bz - rangeZ) < 4) return;
        rangeX = bx;
        rangeZ = bz;
        rangeDist = renderDist;
        int enter = renderDist + 12, leave = renderDist + 28;
        for (int g = 0; g < NG; g++) {
            int x0 = (g % GW) * GB, z0 = (g / GW) * GB;
            int dx = Math.max(0, Math.max(x0 - bx, bx - x0 - GB)), dz = Math.max(0, Math.max(z0 - bz, bz - z0 - GB));
            int d2 = dx * dx + dz * dz;
            if (!inRange[g] && d2 <= enter * enter) {
                inRange[g] = true;
                for (int sy = 0; sy < SY; sy++) markDirty(g & 15, sy, g >> 4);
            } else if (inRange[g] && d2 > leave * leave) drop(g);
        }
    }

    /** Забыть столбец: меши в пул, секции — сборщику мусора. */
    private void drop(int g) {
        inRange[g] = false;
        for (int k = 0; k < NM; k++) {
            retire(k, gpart[k][g]);
            gpart[k][g] = null;
            shown[k][g] = false;
        }
        blendSlot[g] = -1;
        grpDirty[g] = false;
        grpRecolor[g] = false;
        grpUrgent[g] = false;
        for (int sy = 0; sy < SY; sy++) {
            int i = g | (sy << 8);
            if (dirty[i]) {
                dirty[i] = false;
                dirtyCount--;
            }
            built[i] = false;
            lost[i] = false;
            quiet[i] = false;
            sChest[i] = null;
            for (int k = 0; k < NM; k++) {
                sVerts[k][i] = 0;
                sP[k][i] = null;
                sLv[k][i] = null;
                sQ[k][i] = null;
            }
        }
    }

    /**
     * Сменилась темнота неба: оригинал перестраивает все освещённые небом чанки (skyColorChanged), здесь части
     * мешей только перекрашиваются по сохранённым кодам света — геометрия от времени суток не зависит.
     */
    public void skyChanged() {
        for (int g = 0; g < NG; g++) if (gpart[0][g] != null || gpart[1][g] != null) grpRecolor[g] = true;
    }

    private void markDirty(int sx, int sy, int sz) {
        if (sx < 0 || sy < 0 || sz < 0 || sx >= SX || sy >= SY || sz >= SZ) return;
        int i = sx + (sz << 4) + (sy << 8);
        if (!inRange[i & 255]) return;
        quiet[i] = false;
        setDirty(i);
        // квады остальных секций столбца забыты — без них столбец не склеить
        for (int y = 0, g = i & 255; y < SY; y++) {
            int j = g | (y << 8);
            if (lost[j]) {
                quiet[j] = false;
                setDirty(j);
            }
        }
    }

    private void setDirty(int i) {
        if (!dirty[i]) {
            dirty[i] = true;
            dirtyCount++;
        }
    }

    public void tileChanged(int x, int y, int z) {
        lightChanged(x - 1, y - 1, z - 1, x + 1, y + 1, z + 1);
    }

    /** Пометить секции в диапазоне (с запасом на соседей) — без перестройки сразу. */
    public void lightChanged(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int sx = x0 >> 4; sx <= (x1 >> 4); sx++)
            for (int sy = y0 >> 4; sy <= (y1 >> 4); sy++)
                for (int sz = z0 >> 4; sz <= (z1 >> 4); sz++) markUrgent(sx, sy, sz);
    }

    // изменения рядом с игроком перестраиваем раньше прочих
    final int[] urgent = new int[64];
    int urgentCount;

    private void markUrgent(int sx, int sy, int sz) {
        if (sx < 0 || sy < 0 || sz < 0 || sx >= SX || sy >= SY || sz >= SZ) return;
        int i = sx + (sz << 4) + (sy << 8);
        if (!inRange[i & 255]) return;
        boolean wasLost = lost[i];
        markDirty(sx, sy, sz);
        // столбец без кэша квадов (далеко от игрока) — в общую очередь, а не весь столбец за один кадр
        if (wasLost) return;
        for (int k = 0; k < urgentCount; k++) if (urgent[k] == i) return;
        if (urgentCount < urgent.length) urgent[urgentCount++] = i;
    }

    /** Перестроить часть грязных секций, ближайшие к камере — первыми. */
    public void updateDirty(float cx, float cy, float cz, int budgetMs) {
        long start = System.currentTimeMillis();
        updateRange(cx, cz);
        updateNear(Mth.floor(cx) >> 4, Mth.floor(cz) >> 4);
        while (urgentCount > 0) {
            int i = urgent[--urgentCount];
            if (dirty[i]) {
                rebuild(i);
                grpUrgent[group(i & 15, (i >> 4) & 15)] = true;
            }
        }
        if (dirtyCount <= 0) {
            combineDirty(start, budgetMs);
            return;
        }
        int pcx = Mth.floor(cx) >> 4, pcz = Mth.floor(cz) >> 4;
        int maxR = renderDist / 16 + 3;
        for (int r = 0; r <= maxR; r++) {
            for (int sy = 0; sy < SY; sy++)
                for (int sx = pcx - r; sx <= pcx + r; sx++)
                    for (int sz = pcz - r; sz <= pcz + r; sz++) {
                        if (Math.max(Math.abs(sx - pcx), Math.abs(sz - pcz)) != r) continue;
                        if (sx < 0 || sz < 0 || sx >= SX || sz >= SZ) continue;
                        int i = sx + (sz << 4) + (sy << 8);
                        if (!dirty[i]) continue;
                        rebuild(i);
                        if (System.currentTimeMillis() - start > budgetMs) {
                            combineDirty(start, budgetMs);
                            return;
                        }
                    }
        }
        combineDirty(start, budgetMs);
    }

    private void rebuild(int i) {
        if (dirty[i]) {
            dirty[i] = false;
            dirtyCount--;
        }
        built[i] = true;
        lost[i] = false;
        int sx = i & 15, sz = (i >> 4) & 15, sy = i >> 8;
        for (int k = 0; k < NM; k++) mb[k].clear();
        long t0 = Prof.t();
        nChestTmp = 0;
        tessellateSection(sx << 4, sy << 4, sz << 4);
        if (nChestTmp == 0) sChest[i] = null;
        else {
            sChest[i] = new int[nChestTmp];
            System.arraycopy(chestTmp, 0, sChest[i], 0, nChestTmp);
        }
        if (Prof.on) { Prof.add(Prof.M_TESS, t0); Prof.rebuilds++; }
        for (int k = 0; k < NM; k++) {
            SecBuf m = mb[k];
            int v = m.verts;
            sVerts[k][i] = v;
            if (v == 0) {
                sP[k][i] = null; sLv[k][i] = null; sQ[k][i] = null;
            } else {
                int[] p = new int[v];
                System.arraycopy(m.p, 0, p, 0, v);
                byte[] lv = new byte[v];
                System.arraycopy(m.lv, 0, lv, 0, v);
                int[] q = new int[v >> 2];
                System.arraycopy(m.q, 0, q, 0, v >> 2);
                sP[k][i] = p; sLv[k][i] = lv; sQ[k][i] = q;
            }
        }
        if (quiet[i]) quiet[i] = false;
        else grpDirty[group(sx, sz)] = true;
    }

    private boolean isNear(int g) {
        return Math.abs((g & 15) - nearX) <= NEAR && Math.abs((g >> 4) - nearZ) <= NEAR;
    }

    /**
     * Игрок сменил столбец: у ушедших далеко забыть квады секций, у подошедших — тихо построить заново (в общей
     * очереди), чтобы правки рядом с игроком перестраивали одну секцию, а не столбец.
     */
    private void updateNear(int px, int pz) {
        if (px == nearX && pz == nearZ) return;
        nearX = px;
        nearZ = pz;
        for (int g = 0; g < NG; g++) {
            if (!inRange[g]) continue;
            if (isNear(g)) {
                for (int sy = 0; sy < SY; sy++) {
                    int i = g | (sy << 8);
                    if (lost[i] && !dirty[i]) {
                        quiet[i] = true;
                        setDirty(i);
                    }
                }
            } else if (!grpDirty[g]) forget(g);
        }
    }

    /** Забыть квады секций столбца (меши остаются). */
    private void forget(int g) {
        // столбец ждёт перестройки — квады понадобятся при склейке, после неё он забудется сам
        for (int sy = 0; sy < SY; sy++) {
            int i = g | (sy << 8);
            if (dirty[i] && !quiet[i]) return;
        }
        for (int sy = 0; sy < SY; sy++) {
            int i = g | (sy << 8);
            if (dirty[i]) {
                dirty[i] = false;
                dirtyCount--;
                quiet[i] = false;
            }
            if (!built[i]) continue;
            boolean any = false;
            for (int k = 0; k < NM; k++) {
                if (sVerts[k][i] != 0) any = true;
                sP[k][i] = null;
                sLv[k][i] = null;
                sQ[k][i] = null;
            }
            lost[i] = any;
        }
    }

    // буферы склейки одной части: позиции в 1/16 от угла группы, цвета, развёртка
    private static short[] cPos;
    private static byte[] cCol;
    private static short[] cUv;
    private static byte[] bPos, bUv;
    /** квады части: код света | затенение << 8 | оттенок << 10 */
    private static short[] cInfo;
    private int cVerts;
    private final int[] lightRgb = new int[256 * 4];

    /**
     * Склеить секции группы в меши: квады раскладываются по направлению граней, каждая часть берётся из пула.
     * Позиции частей — байтами, если по всем осям хватает диапазона (единица по оси — блок или 1/16, масштаб
     * по осям задаёт преобразование узла Mesh), иначе short с шагом 1/16. Вид от этого не меняется.
     */
    private void combine(int g) {
        // квады, забытые у дальнего столбца, нужны сейчас (срочная правка) — построить
        for (int sy = 0; sy < SY; sy++) {
            int i = g | (sy << 8);
            if (lost[i] || (dirty[i] && sP[0][i] == null && sP[1][i] == null && (sVerts[0][i] | sVerts[1][i]) != 0)) rebuild(i);
        }
        grpDirty[g] = false;
        grpRecolor[g] = false;
        if (cPos == null) {
            cPos = new short[PART_MAX * 3];
            cCol = new byte[PART_MAX * 4];
            cUv = new short[PART_MAX * 2];
            bPos = new byte[PART_MAX * 3];
            bUv = new byte[PART_MAX * 2];
            cInfo = new short[PART_MAX / 4];
        }
        lightTable();
        int gx = (g % GW) << GS, gz = (g / GW) << GS;
        int y0 = 99, y1 = -1;
        for (int k = 0; k < NM; k++) {
            retire(k, gpart[k][g]);
            gpart[k][g] = null;
            shown[k][g] = false;
            if (k == 1) blendSlot[g] = -1;
            nParts = 0;
            for (int sy = 0; sy < SY; sy++) {
                if (sVerts[k][g | (sy << 8)] == 0) continue;
                if (sy < y0) y0 = sy;
                if (sy > y1) y1 = sy;
            }
            for (int b = 0; b < 7; b++) {
                cVerts = 0;
                int nc = k == 0 ? 3 : 4;
                for (int sy = 0; sy < SY; sy++) {
                    int i = g | (sy << 8);
                    int v = sVerts[k][i];
                    if (v == 0 || sP[k][i] == null) continue;
                    int[] p = sP[k][i], q = sQ[k][i];
                    byte[] lv = sLv[k][i];
                    int dy = sy * 256;
                    for (int qi = 0, n = v >> 2; qi < n; qi++) {
                        int info = q[qi];
                        if (((info >> 10) & 7) != b) continue;
                        if (cVerts + 4 > PART_MAX) flushPart(k, b, gx, gz);
                        int lt = lightRgb[((info & 255) << 2) | ((info >> 8) & 3)], tint = SecBuf.tints[(info >>> 21) & 255];
                        int tile = (info >>> 13) & 255, tu = (tile & 15) << 4, tv = tile & 0xF0;
                        byte cr = (byte) (((tint >> 16) & 255) * lt / 255), cg = (byte) (((tint >> 8) & 255) * lt / 255);
                        byte cb = (byte) ((tint & 255) * lt / 255), ca = (byte) (tint >>> 24);
                        cInfo[cVerts >> 2] = (short) ((info & 1023) | (((info >>> 21) & 63) << 10));
                        for (int j = qi << 2, e = j + 4; j < e; j++) {
                            int pp = p[j], d = cVerts;
                            cPos[d * 3] = (short) SecBuf.px(pp);
                            cPos[d * 3 + 1] = (short) (SecBuf.py(pp) + dy);
                            cPos[d * 3 + 2] = (short) SecBuf.pz(pp);
                            int o = d * nc;
                            cCol[o] = cr;
                            cCol[o + 1] = cg;
                            cCol[o + 2] = cb;
                            if (nc == 4) cCol[o + 3] = ca;
                            cUv[d * 2] = (short) (tu + (pp >>> 27));
                            cUv[d * 2 + 1] = (short) (tv + lv[j]);
                            cVerts = d + 1;
                        }
                    }
                }
                if (cVerts > 0) flushPart(k, b, gx, gz);
            }
            if (nParts == 0) continue;
            Part[] ps = new Part[nParts];
            for (int i = 0; i < nParts; i++) {
                ps[i] = parts[i];
                root[k].addChild(ps[i].mesh);
                parts[i] = null;
            }
            gpart[k][g] = ps;
        }
        grpY0[g] = y0 < 99 ? y0 * 16 : 0;
        grpY1[g] = y1 >= 0 ? y1 * 16 + 16 : 0;
        if (!isNear(g)) forget(g);
    }

    /** яркость по коду света при текущем skyDarken и затенению */
    private void lightTable() {
        for (int code = 0; code < 256; code++)
            for (int s = 0; s < 4; s++) lightRgb[(code << 2) | s] = BR[level.levelOf(code)] * SecBuf.SHADES[s] / 255;
    }

    /** Перекрасить части столбца по кодам света квадов (Part.info) — смена времени суток без склейки. */
    private void recolor(int g) {
        grpRecolor[g] = false;
        if (cCol == null) return;
        for (int k = 0; k < NM; k++) {
            Part[] ps = gpart[k][g];
            if (ps == null) continue;
            for (int pi = 0; pi < ps.length; pi++) {
                Part p = ps[pi];
                int nc = p.comps, nq = p.verts >> 2;
                short[] inf = p.info;
                for (int q = 0, o = 0; q < nq; q++) {
                    int info = inf[q];
                    int lt = lightRgb[((info & 255) << 2) | ((info >> 8) & 3)], tint = SecBuf.tints[(info >> 10) & 63];
                    byte cr = (byte) (((tint >> 16) & 255) * lt / 255), cg = (byte) (((tint >> 8) & 255) * lt / 255);
                    byte cb = (byte) ((tint & 255) * lt / 255), ca = (byte) (tint >>> 24);
                    for (int j = 0; j < 4; j++, o += nc) {
                        cCol[o] = cr;
                        cCol[o + 1] = cg;
                        cCol[o + 2] = cb;
                        if (nc == 4) cCol[o + 3] = ca;
                    }
                }
                p.ca.set(0, p.verts, cCol);
            }
        }
    }

    private void flushPart(int k, int b, int gx, int gz) {
        int n = cVerts;
        cVerts = 0;
        Part p = makePart(n, k, gx, gz);
        p.dir = b;
        p.plane = b < 6 ? plane(n, b, gx, gz) : 0;
        if (nParts == parts.length) {
            Part[] np = new Part[nParts * 2];
            System.arraycopy(parts, 0, np, 0, nParts);
            parts = np;
        }
        parts[nParts++] = p;
    }

    private Part[] parts = new Part[16];
    private int nParts;
    private final float[] bias = new float[3];
    private final int[] partUnit = new int[3], partMin = new int[3];

    /** Крайняя плоскость граней направления b (в блоках мира): «+» — наименьшая, «-» — наибольшая. */
    private static float plane(int n, int b, int gx, int gz) {
        int axis = b >> 1;
        boolean neg = (b & 1) != 0;
        int best = neg ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        short[] p = cPos;
        for (int j = axis, e = n * 3; j < e; j += 3) {
            int c = p[j];
            if (neg ? c > best : c < best) best = c;
        }
        float o = axis == 0 ? gx << 4 : (axis == 2 ? gz << 4 : 0);
        return best / 16f + o;
    }

    private static int capClass(int n) {
        int lo = 0, hi = CAPS.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >> 1;
            if (CAPS[mid] >= n) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /**
     * Часть из n вершин буферов склейки: из пула (или новая) класса ёмкости не меньше n; хвост до ёмкости
     * заполняется копиями последней вершины — вырожденные квады нулевой площади.
     */
    private Part makePart(int n, int k, int gx, int gz) {
        short[] p = cPos;
        int[] unit = partUnit, min = partMin;
        boolean bytes = true;
        for (int a = 0; a < 3; a++) {
            int or = 0, lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int j = a, e = n * 3; j < e; j += 3) {
                int c = p[j];
                or |= c;
                if (c < lo) lo = c;
                if (c > hi) hi = c;
            }
            int u = (or & 15) == 0 ? 16 : 1;
            unit[a] = u;
            min[a] = lo / u;
            if (hi / u - lo / u > 255) bytes = false;
        }
        // развёртка байтами со сдвигом, если по обеим осям хватает 256 значений
        short[] uv = cUv;
        int u0 = Integer.MAX_VALUE, u1 = Integer.MIN_VALUE, v0 = Integer.MAX_VALUE, v1 = Integer.MIN_VALUE;
        for (int j = 0, e = n * 2; j < e; j += 2) {
            int u = uv[j], v = uv[j + 1];
            if (u < u0) u0 = u;
            if (u > u1) u1 = u;
            if (v < v0) v0 = v;
            if (v > v1) v1 = v;
        }
        boolean uvBytes = u1 - u0 <= 255 && v1 - v0 <= 255;
        int nc = k == 0 ? 3 : 4;
        int cls = capClass(n), cap = CAPS[cls];
        int key = (((cls * 2 + (bytes ? 1 : 0)) * 2 + (uvBytes ? 1 : 0)) * 2) + k;
        java.util.Vector pl = pool[key];
        Part part;
        if (pl.size() > 0) {
            part = (Part) pl.elementAt(pl.size() - 1);
            pl.removeElementAt(pl.size() - 1);
            pooledVerts -= cap;
        } else {
            part = new Part();
            part.cls = cls;
            part.bytes = bytes;
            part.key = key;
            part.comps = nc;
            part.pa = new VertexArray(cap, 3, bytes ? 1 : 2);
            if (MemDbg.on) MemDbg.va(part.pa, "LevelRenderer.pa");
            part.ca = new VertexArray(cap, nc, 1);
            if (MemDbg.on) MemDbg.va(part.ca, "LevelRenderer.ca");
            part.ta = new VertexArray(cap, 2, uvBytes ? 1 : 2);
            if (MemDbg.on) MemDbg.va(part.ta, "LevelRenderer.ta");
            part.info = new short[cap >> 2];
            if (uvBytes) part.uvBias = new float[2];
            part.vb = new VertexBuffer();
            part.vb.setColors(part.ca);
            part.vb.setTexCoords(0, part.ta, 1f / 256f, null);
            part.vb.setPositions(part.pa, 1f, null);
            part.mesh = new Mesh(part.vb, MeshBuilder.strips(cap), meshApp[0]);
            part.mesh.setRenderingEnable(false);
        }
        part.verts = n;
        int last = n - 1;
        if (bytes) {
            for (int a = 0; a < 3; a++) {
                int u = unit[a], o = min[a] + 128;
                for (int j = a, e = n * 3; j < e; j += 3) bPos[j] = (byte) (p[j] / u - o);
                byte pad = (byte) (p[last * 3 + a] / u - o);
                for (int d = n * 3 + a; d < cap * 3; d += 3) bPos[d] = pad;
                bias[a] = o;
            }
            part.pa.set(0, cap, bPos);
        } else {
            for (int d = n * 3; d < cap * 3; d += 3) {
                p[d] = p[last * 3];
                p[d + 1] = p[last * 3 + 1];
                p[d + 2] = p[last * 3 + 2];
            }
            unit[0] = unit[1] = unit[2] = 1;
            bias[0] = bias[1] = bias[2] = 0;
            part.pa.set(0, cap, p);
        }
        for (int d = n * nc; d < cap * nc; d++) cCol[d] = 0;
        part.ca.set(0, cap, cCol);
        System.arraycopy(cInfo, 0, part.info, 0, n >> 2);
        if (uvBytes) {
            int bu = u0 + 128, bv = v0 + 128;
            byte[] b = bUv;
            for (int j = 0, e = n * 2; j < e; j += 2) {
                b[j] = (byte) (uv[j] - bu);
                b[j + 1] = (byte) (uv[j + 1] - bv);
            }
            for (int d = n * 2; d < cap * 2; d += 2) {
                b[d] = 0;
                b[d + 1] = 0;
            }
            part.ta.set(0, cap, b);
            part.uvBias[0] = bu / 256f;
            part.uvBias[1] = bv / 256f;
            part.vb.setTexCoords(0, part.ta, 1f / 256f, part.uvBias);
        } else {
            for (int d = n * 2; d < cap * 2; d++) cUv[d] = 0;
            part.ta.set(0, cap, cUv);
        }
        part.vb.setPositions(part.pa, 1f, bias);
        Appearance ap = k == 0 ? meshApp[0] : blendApp[0];
        if (part.mesh.getAppearance(0) != ap) part.mesh.setAppearance(0, ap);
        part.mesh.setTranslation(gx << 4, 0, gz << 4);
        part.mesh.setScale(unit[0] / 16f, unit[1] / 16f, unit[2] / 16f);
        if (Prof.on) {
            if (bytes) Prof.bytesVerts += n;
            else Prof.shortVerts += n;
            Prof.padVerts += cap - n;
        }
        return part;
    }

    private boolean columnReady(int g) {
        int c = g; // GS = 0: группа = столбец
        for (int sy = 0; sy < SY; sy++) {
            int i = c | (sy << 8);
            if (dirty[i]) return false;
        }
        return true;
    }

    /** Склеить готовые столбцы; срочные (правки рядом с игроком) — всегда, остальные — пока не вышел бюджет кадра. */
    private void combineDirty(long start, int budgetMs) {
        long t0 = Prof.t();
        int done = 0;
        boolean lt = false;
        for (int g = 0; g < NG; g++) {
            if (grpRecolor[g] && !grpDirty[g]) {
                if (done > 0 && System.currentTimeMillis() - start > budgetMs) continue;
                if (!lt) {
                    lightTable();
                    lt = true;
                }
                recolor(g);
                done++;
                continue;
            }
            if (!grpDirty[g]) continue;
            // столбец склеивается, когда пересчитаны все его секции (иначе волна перестройки склеивает его много раз);
            // за кадр — хотя бы один, дальше — пока не вышел бюджет
            if (!grpUrgent[g] && (!columnReady(g) || (done > 0 && System.currentTimeMillis() - start > budgetMs))) continue;
            done++;
            grpUrgent[g] = false;
            combine(g);
            Prof.combines++;
        }
        if (Prof.on) Prof.add(Prof.M_COMB, t0);
    }

    // ---- геометрия ----

    private int ox, oy, oz;
    private SecBuf cur;

    private void tessellateSection(int x0, int y0, int z0) {
        ox = x0; oy = y0; oz = z0;
        Level l = level;
        int c = (x0 >> 4) | ((z0 >> 4) << 4);
        // секция из четырёх пустых кусков слоя блоков — пропуск без обхода
        boolean any = false;
        for (int py = y0 >> 2; py < (y0 >> 2) + 4 && !any; py++) {
            int k = (c << 5) | py;
            if (!l.blocks.uniform(k) || l.blocks.uniformValue(k) != 0) any = true;
        }
        if (!any) return;
        Pal b = l.blocks;
        for (int y = y0; y < y0 + 16; y++)
            for (int z = z0; z < z0 + 16; z++)
                for (int x = x0; x < x0 + 16; x++) {
                    int id = b.get(c, ((x & 15) << 11) | ((z & 15) << 7) | y);
                    if (id == 0) continue;
                    tessellate(id, x, y, z);
                }
    }

    private void tessellate(int id, int x, int y, int z) {
        cur = mb[MESH[Tile.layer[id]]];
        int d = level.getData(x, y, z);
        switch (Tile.shape[id]) {
            case Tile.S_CUBE:
                cube(id, d, x, y, z);
                break;
            case Tile.S_CROSS:
                cross(id, d, x, y, z);
                break;
            case Tile.S_LIQUID:
                liquid(id, d, x, y, z);
                break;
            case Tile.S_TORCH:
                torch(id, d, x, y, z);
                break;
            case Tile.S_SLAB:
                if ((d & 8) != 0) box(id, d, x, y, z, 0, 8, 0, 16, 16, 16);
                else box(id, d, x, y, z, 0, 0, 0, 16, 8, 16);
                break;
            case Tile.S_SNOW:
                box(id, d, x, y, z, 0, 0, 0, 16, 2 * ((d & 7) + 1), 16);
                break;
            case Tile.S_FARM:
                box(id, d, x, y, z, 0, 0, 0, 16, 15, 16);
                break;
            case Tile.S_CACTUS:
                cactus(id, d, x, y, z);
                break;
            case Tile.S_STAIRS:
                stairs(id, d, x, y, z);
                break;
            case Tile.S_FENCE:
                fence(id, d, x, y, z);
                break;
            case Tile.S_LADDER:
                ladder(id, d, x, y, z);
                break;
            case Tile.S_DOOR: {
                float[] bb = new float[6];
                TileLogic.doorShape(TileLogic.doorComposite(level, x, y, z), bb);
                box(id, d, x, y, z, (int) (bb[0] * 16), 0, (int) (bb[2] * 16), (int) (bb[3] * 16), 16, (int) (bb[5] * 16));
                break;
            }
            case Tile.S_TRAPDOOR: {
                float[] bb = new float[6];
                TileLogic.trapdoorShape(d, bb);
                box(id, d, x, y, z, (int) (bb[0] * 16), (int) (bb[1] * 16), (int) (bb[2] * 16), (int) (bb[3] * 16), (int) (bb[4] * 16), (int) (bb[5] * 16));
                break;
            }
            case Tile.S_PANE:
                pane(id, d, x, y, z);
                break;
            case Tile.S_BED:
                bed(id, d, x, y, z);
                break;
            case Tile.S_CROPS:
                crops(id, d, x, y, z);
                break;
            case Tile.S_CHEST:
                // ChestTile: setShape(0.025, 0, 0.025, 0.975, 0.95, 0.975) — в сетку 1/16 не ложится, рисуется renderChests
                if (nChestTmp == chestTmp.length) {
                    int[] nc = new int[nChestTmp * 2];
                    System.arraycopy(chestTmp, 0, nc, 0, nChestTmp);
                    chestTmp = nc;
                }
                chestTmp[nChestTmp++] = x | (z << 8) | (y << 16);
                break;
            case Tile.S_GATE:
                gate(id, d, x, y, z);
                break;
            case Tile.S_SIGN:
                // таблички — модель с текстом (SignRenderer), в сетку мира не попадают
                break;
        }
    }

    /** Tile::getColor 0.6.1: трава 0x339933 (у блока травы — только верх), листва FoliageColor. */
    private int tintColor(int id, int d, int x, int z) {
        if (id == Tile.GRASS) return 0x339933;
        switch (Tile.tint[id]) {
            case Tile.T_GRASS:
                return id == Tile.TALLGRASS && d == 0 ? 0xFFFFFF : 0x339933;
            case Tile.T_FOLIAGE:
                if (id == Tile.LEAVES) {
                    if ((d & 3) == 1) return 0x619961;
                    if ((d & 3) == 2) return 0x80A755;
                }
                return 0x48B518;
        }
        return 0xFFFFFF;
    }

    /** Код света клетки (небо << 4 | блоки), см. Level.lightCode; 0xFF — полная яркость (факел, лава). */
    private int light(int x, int y, int z) {
        return level.lightCode(x, y, z);
    }

    static final int FULL = 0xFF;

    /**
     * Параметры цвета следующих квадов секции (код света, затенение, оттенок): сам цвет считается при склейке
     * столбца (combine), поэтому смена времени суток не требует перестройки геометрии.
     */
    private int color(int code, int shade, int tint, int alpha) {
        cur.curInfo = (code & 255) | (SecBuf.shadeIndex(shade) << 8) | (SecBuf.tintIndex((alpha << 24) | (tint & 0xFFFFFF)) << 21);
        return 0;
    }

    static int rgba(int br, int shade, int tint, int alpha) {
        int k = br * shade / 255;
        int r = ((tint >> 16) & 255) * k / 255;
        int g = ((tint >> 8) & 255) * k / 255;
        int b = (tint & 255) * k / 255;
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    /** Полный куб с отсечением граней. */
    private void cube(int id, int d, int x, int y, int z) {
        Level l = level;
        int tint = tintColor(id, d, x, z);
        for (int f = 0; f < 6; f++) {
            int nx = x + TileLogic.fx(f), ny = y + TileLogic.fy(f), nz = z + TileLogic.fz(f);
            if (ny < 0) continue;
            int n = l.getTile(nx, ny, nz);
            if (Tile.opaque[n] || (n == Tile.LEAVES && Tile.fastLeaves)) continue;
            if (n == id && !Tile.opaque[id] && (id != Tile.LEAVES || Tile.fastLeaves)) continue;
            if ((nx & ~255) != 0 || (nz & ~255) != 0) continue;
            int t = Tile.getTex(id, f, d);
            int ft = tint;
            if (id == Tile.GRASS) {
                if (f == 1) ft = tint;
                else ft = 0xFFFFFF;
                if (f >= 2) {
                    int above = l.getTile(x, y + 1, z);
                    if (above == Tile.SNOW_LAYER || above == Tile.SNOW) t = 68;
                }
            }
            int col = color(light(nx, ny, nz), FACE_SHADE[f], ft, 255);
            face(f, x, y, z, 0, 0, 0, 16, 16, 16, t, col);
        }
    }

    /** Грань коробки (координаты в 1/16 блока) с текстурой tex. */
    private void face(int f, int x, int y, int z, int x0, int y0, int z0, int x1, int y1, int z1, int tex, int col) {
        int bx = (x - ox) * 16, by = (y - oy) * 16, bz = (z - oz) * 16;
        int tu = (tex & 15) * 16, tv = (tex >> 4) * 16;
        int X0 = bx + x0, X1 = bx + x1, Y0 = by + y0, Y1 = by + y1, Z0 = bz + z0, Z1 = bz + z1;
        SecBuf m = cur;
        // TileRenderer::xFlipTexture: развёртка зеркально по u
        m.flipTu = flipU ? tu : -1;
        switch (f) {
            case 1:
                m.quad(X0, Y1, Z0, tu + x0, tv + z0, col,
                        X0, Y1, Z1, tu + x0, tv + z1, col,
                        X1, Y1, Z1, tu + x1, tv + z1, col,
                        X1, Y1, Z0, tu + x1, tv + z0, col);
                break;
            case 0:
                m.quad(X0, Y0, Z0, tu + x0, tv + z0, col,
                        X1, Y0, Z0, tu + x1, tv + z0, col,
                        X1, Y0, Z1, tu + x1, tv + z1, col,
                        X0, Y0, Z1, tu + x0, tv + z1, col);
                break;
            case 2:
                m.quad(X1, Y0, Z0, tu + 16 - x1, tv + 16 - y0, col,
                        X0, Y0, Z0, tu + 16 - x0, tv + 16 - y0, col,
                        X0, Y1, Z0, tu + 16 - x0, tv + 16 - y1, col,
                        X1, Y1, Z0, tu + 16 - x1, tv + 16 - y1, col);
                break;
            case 3:
                m.quad(X0, Y0, Z1, tu + x0, tv + 16 - y0, col,
                        X1, Y0, Z1, tu + x1, tv + 16 - y0, col,
                        X1, Y1, Z1, tu + x1, tv + 16 - y1, col,
                        X0, Y1, Z1, tu + x0, tv + 16 - y1, col);
                break;
            case 4:
                m.quad(X0, Y0, Z0, tu + z0, tv + 16 - y0, col,
                        X0, Y0, Z1, tu + z1, tv + 16 - y0, col,
                        X0, Y1, Z1, tu + z1, tv + 16 - y1, col,
                        X0, Y1, Z0, tu + z0, tv + 16 - y1, col);
                break;
            case 5:
                m.quad(X1, Y0, Z1, tu + 16 - z1, tv + 16 - y0, col,
                        X1, Y0, Z0, tu + 16 - z0, tv + 16 - y0, col,
                        X1, Y1, Z0, tu + 16 - z0, tv + 16 - y1, col,
                        X1, Y1, Z1, tu + 16 - z1, tv + 16 - y1, col);
                break;
        }
        m.flipTu = -1;
    }

    private boolean flipU;

    /** Коробка внутри блока; грани на границе блока отсекаются непрозрачными соседями. */
    private void box(int id, int d, int x, int y, int z, int x0, int y0, int z0, int x1, int y1, int z1) {
        Level l = level;
        int tint = tintColor(id, d, x, z);
        int own = light(x, y, z);
        for (int f = 0; f < 6; f++) {
            boolean onEdge;
            switch (f) {
                case 0: onEdge = y0 == 0; break;
                case 1: onEdge = y1 == 16; break;
                case 2: onEdge = z0 == 0; break;
                case 3: onEdge = z1 == 16; break;
                case 4: onEdge = x0 == 0; break;
                default: onEdge = x1 == 16; break;
            }
            int br = own;
            if (onEdge) {
                int nx = x + TileLogic.fx(f), ny = y + TileLogic.fy(f), nz = z + TileLogic.fz(f);
                if (Tile.opaque[l.getTile(nx, ny, nz)]) continue;
                br = light(nx, ny, nz);
            }
            br = Level.maxCode(br, own);
            face(f, x, y, z, x0, y0, z0, x1, y1, z1, Tile.getTex(id, f, d), color(br, FACE_SHADE[f], tint, 255));
        }
    }

    // Direction::DIRECTION_FACING / DIRECTION_OPPOSITE, сторона с зеркальной развёрткой (TileRenderer::tesselateBedInWorld)
    static final int[] DIR_FACING = { 3, 4, 2, 5 }, DIR_OPP = { 2, 3, 0, 1 }, BED_FLIP = { 5, 3, 4, 2 };

    /** TileRenderer::tesselateBedInWorld: высота 9/16, дно из досок на 3/16 (ножки — прозрачные места текстуры боков). */
    private void bed(int id, int d, int x, int y, int z) {
        Level l = level;
        int dir = d & 3;
        boolean head = (d & 8) != 0;
        int center = light(x, y, z);
        face(0, x, y, z, 0, 3, 0, 16, 3, 16, Tile.getTex(id, 0, d), color(center, 128, 0xFFFFFF, 255));
        // верх: развёртка повёрнута по направлению кровати
        int t = Tile.getTex(id, 1, d);
        int tu = (t & 15) * 16, tv = (t >> 4) * 16;
        int col = color(light(x, y + 1, z), 255, 0xFFFFFF, 255);
        int bx = (x - ox) * 16, by = (y - oy) * 16 + 9, bz = (z - oz) * 16;
        int[] uv = new int[8];
        for (int k = 0; k < 4; k++) {
            // углы как в face(1): (0,0) (0,16) (16,16) (16,0) по (x,z)
            int cx = k >= 2 ? 16 : 0, cz = k == 1 || k == 2 ? 16 : 0, u, v;
            switch (dir) {
                case 0: u = cz; v = cx; break;
                case 1: u = 16 - cx; v = cz; break;
                case 2: u = 16 - cz; v = 16 - cx; break;
                default: u = cx; v = 16 - cz; break;
            }
            uv[k * 2] = tu + u;
            uv[k * 2 + 1] = tv + v;
        }
        cur.quad(bx, by, bz, uv[0], uv[1], col, bx, by, bz + 16, uv[2], uv[3], col,
                bx + 16, by, bz + 16, uv[4], uv[5], col, bx + 16, by, bz, uv[6], uv[7], col);
        int skip = DIR_FACING[head ? DIR_OPP[dir] : dir];
        for (int f = 2; f < 6; f++) {
            if (f == skip) continue;
            int nx = x + TileLogic.fx(f), nz = z + TileLogic.fz(f);
            if (Tile.opaque[l.getTile(nx, y, nz)]) continue;
            flipU = f == BED_FLIP[dir];
            face(f, x, y, z, 0, 0, 0, 16, 9, 16, Tile.getTex(id, f, d), color(light(nx, y, nz), FACE_SHADE[f], 0xFFFFFF, 255));
            flipU = false;
        }
    }

    private void cross(int id, int d, int x, int y, int z) {
        int t = Tile.getTex(id, 0, d);
        if (id == Tile.MELON_STEM) t = 111;
        int col = color(light(x, y, z), 255, tintColor(id, d, x, z), 255);
        int bx = (x - ox) * 16, by = (y - oy) * 16, bz = (z - oz) * 16;
        int tu = (t & 15) * 16, tv = (t >> 4) * 16;
        int h = 16;
        if (id == Tile.MELON_STEM) h = 2 * ((d & 7) + 1);
        SecBuf m = cur;
        int from = m.verts;
        // TileRenderer::tesselateCrossTexture: диагонали от 0.05 до 0.95 блока (в сетке 1/16 — 1..15), обе стороны
        int a = 1, b = 15;
        m.quad(bx + a, by, bz + a, tu, tv + 16, col,
                bx + b, by, bz + b, tu + 16, tv + 16, col,
                bx + b, by + h, bz + b, tu + 16, tv + 16 - h, col,
                bx + a, by + h, bz + a, tu, tv + 16 - h, col);
        m.quad(bx + a, by, bz + b, tu, tv + 16, col,
                bx + b, by, bz + a, tu + 16, tv + 16, col,
                bx + b, by + h, bz + a, tu + 16, tv + 16 - h, col,
                bx + a, by + h, bz + b, tu, tv + 16 - h, col);
        m.backFaces(from);
    }

    private void crops(int id, int d, int x, int y, int z) {
        int t = Tile.getTex(id, 0, d);
        int col = color(light(x, y, z), 255, 0xFFFFFF, 255);
        int bx = (x - ox) * 16, by = (y - oy) * 16 - 1, bz = (z - oz) * 16;
        int tu = (t & 15) * 16, tv = (t >> 4) * 16;
        SecBuf m = cur;
        int from = m.verts;
        for (int k = 0; k < 2; k++) {
            int o = k == 0 ? 4 : 12;
            m.quad(bx + o, by, bz, tu, tv + 16, col, bx + o, by, bz + 16, tu + 16, tv + 16, col,
                    bx + o, by + 16, bz + 16, tu + 16, tv, col, bx + o, by + 16, bz, tu, tv, col);
            m.quad(bx, by, bz + o, tu, tv + 16, col, bx + 16, by, bz + o, tu + 16, tv + 16, col,
                    bx + 16, by + 16, bz + o, tu + 16, tv, col, bx, by + 16, bz + o, tu, tv, col);
        }
        m.backFaces(from);
    }

    private void torch(int id, int d, int x, int y, int z) {
        int t = Tile.getTex(id, 0, d);
        int col = color(FULL, 255, 0xFFFFFF, 255);
        int bx = (x - ox) * 16, by = (y - oy) * 16, bz = (z - oz) * 16;
        int tu = (t & 15) * 16, tv = (t >> 4) * 16;
        // наклон настенного факела
        int sx = 0, sz = 0, lift = 0;
        switch (d) {
            case 1: sx = -8; lift = 3; break;
            case 2: sx = 8; lift = 3; break;
            case 3: sz = -8; lift = 3; break;
            case 4: sz = 8; lift = 3; break;
        }
        int cx = bx + 8 + sx * 5 / 8, cz = bz + 8 + sz * 5 / 8;
        int y0 = by + lift, y1 = by + lift + 16;
        // верх смещён к центру блока
        int tx = sx * -3 / 8, tz = sz * -3 / 8;
        SecBuf m = cur;
        int from = m.verts;
        // 4 плоскости на ±1/16 от оси
        m.quad(cx - 8, y0, cz - 1, tu, tv + 16, col, cx + 8, y0, cz - 1, tu + 16, tv + 16, col,
                cx + 8 + tx, y1, cz - 1 + tz, tu + 16, tv, col, cx - 8 + tx, y1, cz - 1 + tz, tu, tv, col);
        m.quad(cx - 8, y0, cz + 1, tu, tv + 16, col, cx + 8, y0, cz + 1, tu + 16, tv + 16, col,
                cx + 8 + tx, y1, cz + 1 + tz, tu + 16, tv, col, cx - 8 + tx, y1, cz + 1 + tz, tu, tv, col);
        m.quad(cx - 1, y0, cz - 8, tu, tv + 16, col, cx - 1, y0, cz + 8, tu + 16, tv + 16, col,
                cx - 1 + tx, y1, cz + 8 + tz, tu + 16, tv, col, cx - 1 + tx, y1, cz - 8 + tz, tu, tv, col);
        m.quad(cx + 1, y0, cz - 8, tu, tv + 16, col, cx + 1, y0, cz + 8, tu + 16, tv + 16, col,
                cx + 1 + tx, y1, cz + 8 + tz, tu + 16, tv, col, cx + 1 + tx, y1, cz - 8 + tz, tu, tv, col);
        // верхушка 2x2 из центра текстуры
        int yt = by + lift + 10;
        int ttx = tx * 10 / 16, ttz = tz * 10 / 16;
        m.quad(cx - 1 + ttx, yt, cz - 1 + ttz, tu + 7, tv + 6, col, cx - 1 + ttx, yt, cz + 1 + ttz, tu + 7, tv + 8, col,
                cx + 1 + ttx, yt, cz + 1 + ttz, tu + 9, tv + 8, col, cx + 1 + ttx, yt, cz - 1 + ttz, tu + 9, tv + 6, col);
        m.backFaces(from);
    }

    private void cactus(int id, int d, int x, int y, int z) {
        Level l = level;
        int own = light(x, y, z);
        // боковые грани утоплены на 1/16, верх и низ — полные
        for (int f = 0; f < 6; f++) {
            int t = Tile.getTex(id, f, d);
            int col = color(own, FACE_SHADE[f], 0xFFFFFF, 255);
            if (f == 1 && l.getTile(x, y + 1, z) == id) continue;
            if (f == 0 && Tile.opaque[l.getTile(x, y - 1, z)]) continue;
            int x0 = 0, x1 = 16, z0 = 0, z1 = 16;
            if (f == 2) { z0 = 1; z1 = 1; }
            if (f == 3) { z0 = 15; z1 = 15; }
            if (f == 4) { x0 = 1; x1 = 1; }
            if (f == 5) { x0 = 15; x1 = 15; }
            face(f, x, y, z, x0, 0, z0, x1, 16, z1, t, col);
        }
    }

    private void liquid(int id, int d, int x, int y, int z) {
        Level l = level;
        boolean water = Tile.isWater(id);
        int alpha = water ? 0xB4 : 0xFF;
        int t = water ? 205 : 237;
        int above = l.getTile(x, y + 1, z);
        boolean sameAbove = Tile.isLiquid(above) && Tile.isWater(above) == water;
        int h00 = sameAbove ? 16 : cornerHeight(x, y, z, water);
        int h10 = sameAbove ? 16 : cornerHeight(x + 1, y, z, water);
        int h01 = sameAbove ? 16 : cornerHeight(x, y, z + 1, water);
        int h11 = sameAbove ? 16 : cornerHeight(x + 1, y, z + 1, water);
        int bx = (x - ox) * 16, by = (y - oy) * 16, bz = (z - oz) * 16;
        int tu = (t & 15) * 16, tv = (t >> 4) * 16;
        SecBuf m = cur;
        int own = water ? light(x, y, z) : FULL;
        if (!sameAbove) {
            int col = color(water ? light(x, y + 1, z) : FULL, 255, 0xFFFFFF, alpha);
            m.quad(bx, by + h00, bz, tu, tv, col,
                    bx, by + h01, bz + 16, tu, tv + 16, col,
                    bx + 16, by + h11, bz + 16, tu + 16, tv + 16, col,
                    bx + 16, by + h10, bz, tu + 16, tv, col);
        }
        for (int f = 0; f < 6; f++) {
            if (f == 1) continue;
            int nx = x + TileLogic.fx(f), ny = y + TileLogic.fy(f), nz = z + TileLogic.fz(f);
            if ((nx & ~255) != 0 || (nz & ~255) != 0 || ny < 0) continue;
            int n = l.getTile(nx, ny, nz);
            if (Tile.opaque[n] || (Tile.isLiquid(n) && Tile.isWater(n) == water)) continue;
            if (n == Tile.ICE && water) continue;
            int col = color(water ? light(nx, ny, nz) : own, FACE_SHADE[f], 0xFFFFFF, alpha);
            if (f == 0) {
                face(0, x, y, z, 0, 0, 0, 16, 16, 16, t, col);
                continue;
            }
            int ha, hb, ax, az, bx2, bz2;
            switch (f) {
                case 2: ha = h10; hb = h00; ax = 16; az = 0; bx2 = 0; bz2 = 0; break;
                case 3: ha = h01; hb = h11; ax = 0; az = 16; bx2 = 16; bz2 = 16; break;
                case 4: ha = h00; hb = h01; ax = 0; az = 0; bx2 = 0; bz2 = 16; break;
                default: ha = h11; hb = h10; ax = 16; az = 16; bx2 = 16; bz2 = 0; break;
            }
            m.quad(bx + ax, by, bz + az, tu, tv + 16, col,
                    bx + bx2, by, bz + bz2, tu + 16, tv + 16, col,
                    bx + bx2, by + hb, bz + bz2, tu + 16, tv + 16 - hb, col,
                    bx + ax, by + ha, bz + az, tu, tv + 16 - ha, col);
        }
    }

    /** Высота поверхности жидкости в углу (x,z) — среднее по 4 соседним клеткам, в 1/16. */
    private int cornerHeight(int x, int y, int z, boolean water) {
        Level l = level;
        int sum = 0, cnt = 0;
        for (int k = 0; k < 4; k++) {
            int xx = x - (k & 1), zz = z - (k >> 1);
            int t = l.getTile(xx, y + 1, zz);
            if (Tile.isLiquid(t) && Tile.isWater(t) == water) return 16;
            t = l.getTile(xx, y, zz);
            if (Tile.isLiquid(t) && Tile.isWater(t) == water) {
                int dd = l.getData(xx, y, zz);
                if (dd >= 8 || dd == 0) { sum += (dd == 0 ? 14 : 16) * 10; cnt += 10; }
                else { sum += (16 - (dd + 1) * 16 / 9); cnt++; }
            } else if (!Tile.mSolid(Tile.mat[t])) {
                sum += 0;
                cnt++;
            }
        }
        if (cnt == 0) return 14;
        int h = sum / cnt;
        return h < 1 ? 1 : h;
    }

    private final float[] stairBox = new float[6];

    /** TileRenderer::tesselateStairsInWorld: основа, ступень и угловая четвертинка (StairTile::setStepShape/setInnerPieceShape). */
    private void stairs(int id, int d, int x, int y, int z) {
        boolean up = (d & 4) != 0;
        if (up) box(id, d, x, y, z, 0, 8, 0, 16, 16, 16);
        else box(id, d, x, y, z, 0, 0, 0, 16, 8, 16);
        float[] s = stairBox;
        boolean inner = TileLogic.stairStepShape(level, x, y, z, s);
        stairPiece(id, d, x, y, z, s);
        if (inner && TileLogic.stairInnerShape(level, x, y, z, s)) stairPiece(id, d, x, y, z, s);
    }

    private void stairPiece(int id, int d, int x, int y, int z, float[] s) {
        box(id, d, x, y, z, (int) (s[0] * 16), (int) (s[1] * 16), (int) (s[2] * 16), (int) (s[3] * 16), (int) (s[4] * 16), (int) (s[5] * 16));
    }

    private boolean fenceConnects(int x, int y, int z) {
        return TileLogic.fenceConnects(level, x, y, z);
    }

    private void fence(int id, int d, int x, int y, int z) {
        box(id, d, x, y, z, 6, 0, 6, 10, 16, 10);
        boolean w = fenceConnects(x - 1, y, z), e = fenceConnects(x + 1, y, z);
        boolean n = fenceConnects(x, y, z - 1), s = fenceConnects(x, y, z + 1);
        for (int k = 0; k < 2; k++) {
            int y0 = k == 0 ? 12 : 6, y1 = y0 + 3;
            if (w || e) box(id, d, x, y, z, w ? 0 : 6, y0, 7, e ? 16 : 10, y1, 9);
            if (n || s) box(id, d, x, y, z, 7, y0, n ? 0 : 6, 9, y1, s ? 16 : 10);
        }
    }

    private void gate(int id, int d, int x, int y, int z) {
        boolean open = (d & 4) != 0;
        boolean alongX = (d & 1) == 0;
        if (alongX) {
            box(id, d, x, y, z, 0, 5, 7, 2, 16, 9);
            box(id, d, x, y, z, 14, 5, 7, 16, 16, 9);
            if (!open) {
                box(id, d, x, y, z, 2, 6, 7, 14, 9, 9);
                box(id, d, x, y, z, 2, 12, 7, 14, 15, 9);
            }
        } else {
            box(id, d, x, y, z, 7, 5, 0, 9, 16, 2);
            box(id, d, x, y, z, 7, 5, 14, 9, 16, 16);
            if (!open) {
                box(id, d, x, y, z, 7, 6, 2, 9, 9, 14);
                box(id, d, x, y, z, 7, 12, 2, 9, 15, 14);
            }
        }
    }

    private void pane(int id, int d, int x, int y, int z) {
        Level l = level;
        boolean w = paneConn(l.getTile(x - 1, y, z)), e = paneConn(l.getTile(x + 1, y, z));
        boolean n = paneConn(l.getTile(x, y, z - 1)), s = paneConn(l.getTile(x, y, z + 1));
        if (!w && !e && !n && !s) { w = e = n = s = true; }
        if (w || e) box(id, d, x, y, z, w ? 0 : 7, 0, 7, e ? 16 : 9, 16, 9);
        if (n || s) box(id, d, x, y, z, 7, 0, n ? 0 : 7, 9, 16, s ? 16 : 9);
    }

    private static boolean paneConn(int t) {
        return TileLogic.paneAttaches(t);
    }

    private void ladder(int id, int d, int x, int y, int z) {
        int f = d < 2 || d > 5 ? 2 : d;
        int t = Tile.getTex(id, 0, d);
        // TileRenderer::tesselateLadderInWorld: t.color(br, br, br) — без затенения граней
        int col = color(light(x, y, z), 255, 0xFFFFFF, 255);
        switch (f) {
            case 2: face(2, x, y, z, 0, 0, 15, 16, 16, 15, t, col); break;
            case 3: face(3, x, y, z, 0, 0, 1, 16, 16, 1, t, col); break;
            case 4: face(4, x, y, z, 15, 0, 0, 15, 16, 16, t, col); break;
            default: face(5, x, y, z, 1, 0, 0, 1, 16, 16, t, col); break;
        }
    }

    // ---- отрисовка ----

    private final int[] order = new int[NS];
    private final float[] dist = new float[NS];

    /**
     * Отрисовать видимые секции. (ex,ey,ez) — глаз, (fx,fy,fz) — направление взгляда, cosHalf — косинус половины угла обзора.
     */
    private int blendCount;
    private float camX, camY, camZ;

    /** Твёрдый мир: отсечение групп по пирамиде видимости и туману и граней, повёрнутых от камеры; один render(Node). */
    public int render(Graphics3D g3d, Frustum fr) {
        int n = 0, verts = 0, drawn = 0;
        float rd = renderDist;
        camX = fr.ax; camY = fr.ay; camZ = fr.az;
        for (int g = 0; g < NG; g++) {
            Part[] sp = gpart[0][g], bp = gpart[1][g];
            if (sp == null && bp == null) continue;
            float x0 = (g % GW) * GB, z0 = (g / GW) * GB;
            // за концом тумана (по глубине) ничего не видно — как дальняя плоскость оригинала
            if (!fr.visible(x0, grpY0[g], z0, x0 + GB, grpY1[g], z0 + GB, rd)) {
                hide(0, g);
                hide(1, g);
                continue;
            }
            drawn++;
            verts += show(0, g);
            if (bp != null) {
                float cx = x0 + GB / 2 - fr.ax, cy = (grpY0[g] + grpY1[g]) / 2 - fr.ay, cz = z0 + GB / 2 - fr.az;
                order[n] = g;
                dist[n] = cx * cx + cy * cy + cz * cz;
                n++;
            }
        }
        lastDrawn = drawn;
        blendCount = n;
        if (Prof.x('n')) return n;
        long t0 = Prof.t();
        g3d.render(root[0], null);
        if (Prof.on) {
            Prof.call(verts, t0);
            Prof.drawVerts += verts;
        }
        return n;
    }

    /** Полупрозрачные (вода, лёд): после сущностей и частиц, от дальних к ближним — слоями Appearance. */
    public void renderBlend(Graphics3D g3d) {
        int n = blendCount;
        if (n == 0 || Prof.x('n')) return;
        sortFarToNear(n);
        for (int q = 0; q < n; q++) {
            int g = order[q];
            int slot = Math.min(q, blendApp.length - 1);
            Part[] bp = gpart[1][g];
            if (blendSlot[g] != slot) {
                for (int i = 0; i < bp.length; i++) bp[i].mesh.setAppearance(0, blendApp[slot]);
                blendSlot[g] = slot;
            }
            show(1, g);
        }
        long t0 = Prof.t();
        g3d.render(root[1], null);
        if (Prof.on) Prof.call(0, t0);
    }

    /** Включить части группы, грани которых могут смотреть на камеру; вернуть число вершин. */
    private int show(int k, int g) {
        Part[] ps = gpart[k][g];
        if (ps == null) return 0;
        shown[k][g] = true;
        int verts = 0;
        for (int i = 0; i < ps.length; i++) {
            Part p = ps[i];
            boolean v;
            // полупрозрачные рисуются без отсечения граней — видны с обеих сторон
            if (k == 1) v = true;
            else switch (p.dir) {
                case 0: v = camX > p.plane; break;
                case 1: v = camX < p.plane; break;
                case 2: v = camY > p.plane; break;
                case 3: v = camY < p.plane; break;
                case 4: v = camZ > p.plane; break;
                case 5: v = camZ < p.plane; break;
                default: v = true;
            }
            if (p.on != v) {
                p.on = v;
                p.mesh.setRenderingEnable(v);
            }
            if (v) verts += CAPS[p.cls];
        }
        return verts;
    }

    private void hide(int k, int g) {
        if (!shown[k][g]) return;
        shown[k][g] = false;
        Part[] ps = gpart[k][g];
        if (ps == null) return;
        for (int i = 0; i < ps.length; i++) {
            Part p = ps[i];
            if (p.on) {
                p.on = false;
                p.mesh.setRenderingEnable(false);
            }
        }
    }

    private void sortFarToNear(int n) {
        for (int i = 1; i < n; i++) {
            float d = dist[i];
            int o = order[i];
            int j = i - 1;
            while (j >= 0 && dist[j] < d) {
                dist[j + 1] = dist[j];
                order[j + 1] = order[j];
                j--;
            }
            dist[j + 1] = d;
            order[j + 1] = o;
        }
    }

    public boolean hasPending() {
        return dirtyCount > 0;
    }

    public int pendingCount() {
        return dirtyCount;
    }

    // ---- сундуки (ChestTile 0.6.1: обычный блок формы 0.025..0.975 x 0..0.95, вне сетки 1/16) ----

    private static final int CU = 80; // единица модели — 1/80 блока: 0.025 = 2, 0.95 = 76
    private static VertexArray chestPos, chestCol;
    private static final VertexArray[] chestUv = new VertexArray[6];
    private static VertexBuffer chestVb;
    private static TriangleStripArray chestIb;
    private static final byte[] chestRgba = new byte[24 * 4];
    private final Transform chestT = new Transform();

    /** TileRenderer::tesselateBlockInWorld по форме сундука: развёртка по форме (как face, в 1/80). */
    private static void chestFace(MeshBuilder m, int f, int tex) {
        int x0 = 2, y0 = 0, z0 = 2, x1 = 78, y1 = 76, z1 = 78, S = CU;
        int tu = (tex & 15) * S, tv = (tex >> 4) * S, c = 0xFFFFFFFF;
        switch (f) {
            case 1: m.quad(x0, y1, z0, tu + x0, tv + z0, c, x0, y1, z1, tu + x0, tv + z1, c, x1, y1, z1, tu + x1, tv + z1, c, x1, y1, z0, tu + x1, tv + z0, c); break;
            case 0: m.quad(x0, y0, z0, tu + x0, tv + z0, c, x1, y0, z0, tu + x1, tv + z0, c, x1, y0, z1, tu + x1, tv + z1, c, x0, y0, z1, tu + x0, tv + z1, c); break;
            case 2: m.quad(x1, y0, z0, tu + S - x1, tv + S - y0, c, x0, y0, z0, tu + S - x0, tv + S - y0, c, x0, y1, z0, tu + S - x0, tv + S - y1, c, x1, y1, z0, tu + S - x1, tv + S - y1, c); break;
            case 3: m.quad(x0, y0, z1, tu + x0, tv + S - y0, c, x1, y0, z1, tu + x1, tv + S - y0, c, x1, y1, z1, tu + x1, tv + S - y1, c, x0, y1, z1, tu + x0, tv + S - y1, c); break;
            case 4: m.quad(x0, y0, z0, tu + z0, tv + S - y0, c, x0, y0, z1, tu + z1, tv + S - y0, c, x0, y1, z1, tu + z1, tv + S - y1, c, x0, y1, z0, tu + z0, tv + S - y1, c); break;
            default: m.quad(x1, y0, z1, tu + S - z1, tv + S - y0, c, x1, y0, z0, tu + S - z0, tv + S - y0, c, x1, y1, z0, tu + S - z0, tv + S - y1, c, x1, y1, z1, tu + S - z1, tv + S - y1, c); break;
        }
    }

    private static VertexArray chestUv(int d) {
        if (d < 0 || d > 5) d = 0;
        if (chestUv[d] != null) return chestUv[d];
        MeshBuilder m = new MeshBuilder();
        for (int f = 0; f < 6; f++) chestFace(m, f, Tile.getTex(Tile.CHEST, f, d));
        if (chestPos == null) {
            chestPos = new VertexArray(24, 3, 2);
            if (MemDbg.on) MemDbg.va(chestPos, "LevelRenderer.chestPos");
            chestPos.set(0, 24, m.pos);
            chestCol = new VertexArray(24, 4, 1);
            if (MemDbg.on) MemDbg.va(chestCol, "LevelRenderer.chestCol");
            chestVb = new VertexBuffer();
            chestVb.setPositions(chestPos, 1f / CU, null);
            chestVb.setColors(chestCol);
            chestIb = MeshBuilder.strips(24);
        }
        VertexArray ta = new VertexArray(24, 2, 2);
        if (MemDbg.on) MemDbg.va(ta, "LevelRenderer.chestUv");
        ta.set(0, 24, m.uv);
        chestUv[d] = ta;
        return ta;
    }

    /** Сундуки собранных секций ближе дальности прорисовки; грань — свет клетки сундука и затенение стороны. */
    public void renderChests(Graphics3D g, Frustum fr, float camX, float camZ) {
        float maxD = Math.min(64f, renderDist);
        for (int i = 0; i < NS; i++) {
            int[] cs = sChest[i];
            if (cs == null) continue;
            for (int k = 0; k < cs.length; k++) {
                int x = cs[k] & 255, z = (cs[k] >> 8) & 255, y = cs[k] >> 16;
                float dx = x + 0.5f - camX, dz = z + 0.5f - camZ;
                if (dx * dx + dz * dz > maxD * maxD) continue;
                if (!fr.visible(x, y, z, x + 1, y + 1, z + 1, maxD)) continue;
                if (level.getTile(x, y, z) != Tile.CHEST) continue;
                VertexArray ta = chestUv(level.getData(x, y, z));
                int br = BR[level.levelOf(light(x, y, z))];
                for (int f = 0, j = 0; f < 6; f++) {
                    int col = rgba(br, FACE_SHADE[f], 0xFFFFFF, 255);
                    for (int n = 0; n < 4; n++, j += 4) {
                        chestRgba[j] = (byte) (col >> 16);
                        chestRgba[j + 1] = (byte) (col >> 8);
                        chestRgba[j + 2] = (byte) col;
                        chestRgba[j + 3] = (byte) 255;
                    }
                }
                chestCol.set(0, 24, chestRgba);
                chestVb.setTexCoords(0, ta, 1f / (256 * CU / 16), null);
                chestT.setIdentity();
                chestT.postTranslate(x, y, z);
                g.render(chestVb, chestIb, meshApp[0], chestT);
            }
        }
    }
}
