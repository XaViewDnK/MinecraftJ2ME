/** Отладочный профиль (свойство mcj.debug): суммы времени по разделам кадра и тика, раз в 5 с — строка в лог. */
final class Prof {
    static boolean on = Link.prop("mcj.debug") != null;

    // кадр
    static final int F_UPD = 0, F_SKY = 1, F_WORLD = 2, F_ENT = 3, F_SIGN = 4, F_PART = 5, F_HAND = 6, F_REL = 7,
            F_HUD = 8, F_FLUSH = 9, F_SCREEN = 10,
    // тик
            T_PLAYER = 11, T_PEND = 12, T_RAND = 13, T_ENT = 14, T_AI = 15, T_LIQ = 16, T_PART = 17, T_SPAWN = 18,
            T_SAVE = 19, T_OTHER = 20,
    // перестройка мешей
            M_TESS = 21, M_COMB = 22, M_LIGHT = 23, N = 24;
    static final String[] NAMES = { "upd", "sky", "world", "ent", "sign", "part", "hand", "rel", "hud", "flush", "scr",
            "pl", "pend", "rand", "ent", "ai", "liq", "part", "spawn", "save", "oth", "tess", "comb", "light" };
    static final long[] acc = new long[N];
    /**
     * отладка: mcj.exp / Link «exp» = буквы. Кадр: f без flush, o без OVERWRITE, h без HUD, k без неба, e без сущностей,
     * n без мира, d без текста отладки, s логи SPIKE, i мир по частям render(vb..) вместо render(Node).
     * HUD: 1 без сердец, 2 без хотбара, 3 без предметов хотбара, 5 без строки связи и fps, 8 HUD до releaseTarget,
     * 6 + непрозрачная плашка 450x55, 7 + полупрозрачная картинка 450x55, 9 + та же с альфой 0/255, a без прицела.
     * Тик мобов (меняет игру, только для замера): B без baseTick, T без travel, P без pushEntities.
     * Мир: m без склейки граней (после «rebuild»), z части от ближних столбцов (render по одной), v 3D в пол-экрана.
     * Вершины (после Link «rebuild»): c цвет мира RGBA, C мир без цвета, b позиции short, u развёртка short.
     */
    static String exp = Link.prop("mcj.exp") == null ? "" : Link.prop("mcj.exp");
    static boolean x(char c) { return exp.indexOf(c) >= 0; }
    /** части «sky» и «world» кадра: привязка M3G, очистка, небо (мс за отчёт) */
    static long bindMs, clearMs, skyMs;
    static int calls, bytesVerts, shortVerts, meshes;
    /** «exp G»: квады твёрдого мира при склейке столбцов, из них целые грани, и прямоугольников после слияния */
    static int gQuads, gFull, gRects;
    /** тики сущностей за отчёт по typeId: мс и число тиков; поиск пути — мс */
    static final long[] typeMs = new long[128];
    static final int[] typeN = new int[128];
    static long pathMs;
    /** тики внутри кадра (MC.runTicks до releaseTarget), мс за отчёт — вычесть из «world»-части hand */
    static long tickInFrameMs;
    /** по тайлу: целые грани, прямоугольники без предела, 16x1, 4x4, 8x8 */
    static final int[][] gTile = new int[256][5];
    /** MobSpawner за отчёт: начала кластеров в воздухе, попытки позиций, вызовы biomeAt (и их мс), созданные мобы */
    static int spStarts, spTries, spBiome, spMobs;
    static long spBiomeMs;
    static long drawVerts, padVerts;
    /** регрессия времени вызова render() по числу вершин: мс = a + b * вершины */
    static double sx, sy, sxx, sxy;

    static long firstCall;
    static int frameCalls;

    static void call(int verts, long t0) {
        long ms = System.currentTimeMillis() - t0;
        if (frameCalls++ == 0) { firstCall += ms; return; }
        calls++;
        sx += verts; sy += ms; sxx += (double) verts * verts; sxy += (double) verts * ms;
    }
    static int frames, ticks, rebuilds, combines, gcs, hitches, paths;
    static long frameMs, maxFrame, maxTick, lastFree, start;

    static long t() {
        return System.currentTimeMillis();
    }

    static void add(int k, long t0) {
        acc[k] += System.currentTimeMillis() - t0;
    }

    /** Конец кадра: длительность от начала цикла, учёт сборок мусора по скачку свободной памяти. */
    static void frame(long ms) {
        frameCalls = 0;
        frames++;
        frameMs += ms;
        if (ms > maxFrame) maxFrame = ms;
        if (ms >= 100) hitches++;
        long free = Runtime.getRuntime().freeMemory();
        if (free > lastFree + 512 * 1024) gcs++;
        lastFree = free;
    }

    static void tick(long ms) {
        ticks++;
        if (ms > maxTick) maxTick = ms;
    }

    static void report(MC mc) {
        long now = t();
        if (start == 0) { start = now; return; }
        if (now - start < 5000) return;
        float sec = (now - start) / 1000f;
        StringBuffer sb = new StringBuffer();
        sb.append("PROF fps ").append((int) (frames / sec * 10) / 10f).append(" frame ").append(frames > 0 ? frameMs / frames : 0)
                .append(" max ").append(maxFrame).append(" hitch ").append(hitches).append(" |F");
        for (int k = F_UPD; k <= F_SCREEN; k++) sb.append(' ').append(NAMES[k]).append(' ').append(per(acc[k], frames));
        sb.append(" (bind ").append(per(bindMs, frames)).append(" clear ").append(per(clearMs, frames)).append(" skyr ").append(per(skyMs, frames)).append(')');
        bindMs = clearMs = skyMs = 0;
        sb.append(" first ").append(per(firstCall, frames)).append(" calls ").append(frames > 0 ? calls / frames : 0);
        firstCall = 0;
        sb.append(" dv ").append(frames > 0 ? drawVerts / frames : 0).append(" meshes ").append(frames > 0 ? meshes / frames : 0);
        drawVerts = 0;
        meshes = 0;
        if (calls > 2) {
            double n = calls, b = (n * sxy - sx * sy) / (n * sxx - sx * sx), a = (sy - b * sx) / n;
            sb.append(" verts ").append((int) (sx / Math.max(1, frames))).append(" call ").append((int) (a * 1000)).append("us+")
                    .append((int) (b * 1e6)).append("us/kv");
        }
        sx = sy = sxx = sxy = 0;
        sb.append(" bv ").append(bytesVerts).append(" sv ").append(shortVerts).append(" pad ").append(padVerts);
        sb.append(" |T n ").append(ticks).append(" max ").append(maxTick);
        for (int k = T_PLAYER; k <= T_OTHER; k++) sb.append(' ').append(NAMES[k]).append(' ').append(per(acc[k], ticks));
        if (gQuads > 0) sb.append(" |G q ").append(gQuads).append(" full ").append(gFull).append(" rects ").append(gRects);
        if (gQuads > 0) {
            for (int t = 0; t < 256; t++) if (gTile[t][0] > 0) {
                sb.append(" t").append(t);
                for (int j = 0; j < 5; j++) sb.append(j == 0 ? ':' : '/').append(gTile[t][j]);
                for (int j = 0; j < 5; j++) gTile[t][j] = 0;
            }
        }
        gQuads = gFull = gRects = 0;
        sb.append(" |K ").append(per(tickInFrameMs, frames));
        tickInFrameMs = 0;
        sb.append(" |E path ").append(pathMs).append("ms");
        pathMs = 0;
        for (int t = 0; t < 128; t++) if (typeN[t] > 0) {
            sb.append(' ').append(t).append(':').append(typeN[t]).append('/').append(typeMs[t]);
            typeN[t] = 0;
            typeMs[t] = 0;
        }
        sb.append(" |S st ").append(spStarts).append(" try ").append(spTries).append(" bio ").append(spBiome).append('/').append(spBiomeMs)
                .append("ms mob ").append(spMobs);
        spStarts = spTries = spBiome = spMobs = 0;
        spBiomeMs = 0;
        sb.append(" |M rb ").append(rebuilds).append(" cb ").append(combines);
        for (int k = M_TESS; k <= M_LIGHT; k++) sb.append(' ').append(NAMES[k]).append(' ').append(acc[k]);
        Runtime r = Runtime.getRuntime();
        sb.append(" |mem ").append((r.totalMemory() - r.freeMemory()) >> 10).append('/').append(r.totalMemory() >> 10)
                .append("K gc ").append(gcs).append(" paths ").append(paths);
        r.gc();
        sb.append(" gcmem ").append((r.totalMemory() - r.freeMemory()) >> 10).append('K');
        if (mc.level != null) sb.append(" ent ").append(mc.level.entities.size()).append(" secs ").append(mc.lr != null ? mc.lr.lastDrawn : 0)
                .append(" pos ").append((int) mc.player.x).append(',').append((int) mc.player.y).append(',').append((int) mc.player.z);
        sb.append(" |H3 ").append(Hud3D.on || Hud3D.quads > 0 ? Hud3D.calls + "/" + Hud3D.quads : "off");
        Log.i(sb.toString());
        for (int k = 0; k < N; k++) acc[k] = 0;
        frames = calls = ticks = rebuilds = combines = gcs = hitches = paths = 0;
        frameMs = maxFrame = maxTick = 0;
        start = now;
    }

    /** среднее на кадр/тик с одной десятой */
    private static String per(long total, int n) {
        if (n == 0) return "0";
        long v = total * 10 / n;
        return (v / 10) + "." + (v % 10);
    }
}
