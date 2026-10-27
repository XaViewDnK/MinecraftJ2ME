/**
 * Отладочный бенчмарк (свойство mcj.bench=<число прогонов>, в интерфейсе запуска нет).
 * Всё фиксировано: мир по сиду, ночь, засеянные генераторы случайных чисел, путь камеры по тикам,
 * набор мобов, подрывы TNT и источник воды. Нагрузка с запасом над обычной игрой: стая мобов
 * поверх ночного спавна, взрывы, течение воды и быстрый облёт с перестройкой секций.
 * Итог — строка «BENCH» в лог и окно с результатом; хеш состояния в конце совпадает от прогона к прогону.
 */
final class Bench {
    static final long SEED = 20261046L;
    /** тики сценария: ходьба, арена (стоим, крутим камеру, взрывы), облёт */
    static final int WALK = 400, ARENA = 700, END = 1200;
    static final float CX = 128, CZ = 128, R_WALK = 48, R_FLY = 80;
    static final float WALK_SPEED = 0.216f, FLY_SPEED = 0.55f;

    static Bench run;
    static int left;
    static boolean calm, cmp;
    /** cmp: сумма времени и кадров прогонов calm [0] и полных [1] */
    static final long[] cmpMs = new long[2], cmpFrames = new long[2];

    final MC mc;
    int t;
    boolean done;
    float y, theta, flyTheta, arenaX, arenaZ, arenaTheta;
    // замеры
    int[] frameMs = new int[4096];
    int nFrames;
    long lastFrame, startMs;
    final long[] phaseMs = new long[3];
    final int[] phaseFrames = new int[3];
    long tickMs;
    int maxTick;
    // сохранённые настройки
    private final int oGfx, oView;
    private final boolean oPeaceful, oThird;

    private Bench(MC mc) {
        this.mc = mc;
        oGfx = Options.graphics; oView = Options.viewChunks; oPeaceful = Options.peaceful; oThird = Options.thirdPerson;
    }

    static boolean on() {
        return run != null;
    }

    /** Запуск из MC.debugScreen. */
    static void start(MC mc) {
        String v = System.getProperty("mcj.bench");
        if (v == null) return;
        // calm — тот же путь без добавленной нагрузки (мобы, TNT, вода): эталон «обычной игры» для калибровки
        // cmpN — N пар calm/полный поочерёдно, в конце отношение времени кадра (насколько бенчмарк тяжелее)
        calm = v.startsWith("calm");
        cmp = v.startsWith("cmp");
        if (calm || cmp) v = v.substring(calm ? 4 : 3);
        try { left = v.trim().length() == 0 ? 1 : Math.max(1, Integer.parseInt(v.trim())); } catch (Exception e) { left = 1; }
        if (cmp) left *= 2;
        next(mc);
    }

    private static void next(final MC mc) {
        left--;
        if (cmp) calm = (left & 1) != 0;
        Bench b = new Bench(mc);
        run = b;
        // настройки, влияющие на нагрузку, — самые тяжёлые: High и 4 чанка
        Options.graphics = Options.GFX_HIGH;
        Options.viewChunks = Options.VIEW_MAX;
        Options.peaceful = false;
        Options.thirdPerson = false;
        // все генераторы случайных чисел, влияющие на мир, — с фиксированного зерна
        Mth.seed(SEED);
        Sound.seed(SEED);
        mc.particles.r.setSeed(SEED);
        Level l = new Level();
        l.reseed(SEED);
        l.name = "bench";
        l.seed = SEED;
        l.gameType = 0;
        l.folder = null; // не сохраняется и не попадает в список миров
        l.time = 14400; // ночь: монстры не горят, спавн монстров идёт
        final javax.microedition.lcdui.Graphics g = mc.gfx();
        new Gen(l, SEED).generate(new Gen.Progress() {
            public void progress(String stage, int pct) {
                mc.drawLoading(g, "Benchmark", pct);
            }
        });
        // генерация могла тронуть общие генераторы — засеять ещё раз
        Mth.seed(SEED + 1);
        l.reseed(SEED + 2);
        Player p = new Player(l, false);
        p.invulnerable = true; // выживание (монстры охотятся, спавн работает), но без смерти
        b.theta = 0;
        float x = CX + R_WALK, z = CZ;
        b.y = l.getTopSolid(Mth.floor(x), Mth.floor(z));
        p.setPos(x, b.y, z);
        p.yRot = p.yRotO = 0;
        mc.startGame(l, p);
        mc.ticks = 0;
        mc.particles.r.setSeed(SEED + 3);
        b.spawnMobs();
        Log.i("BENCH start" + (calm ? " calm" : "") + " run " + (left + 1) + ", " + mc.W + "x" + mc.H + " dist " + Options.renderDistance() + " gfx " + Options.GRAPHICS_NAME[Options.graphics] + " sound " + Options.sound);
        b.startMs = b.lastFrame = System.currentTimeMillis();
    }

    // ---- сценарий ----

    private static final int[] PATH_MOBS = { Mobs.PIG, Mobs.ZOMBIE, Mobs.COW, Mobs.SKELETON, Mobs.SHEEP, Mobs.SPIDER,
            Mobs.CHICKEN, Mobs.ZOMBIE, Mobs.PIG, Mobs.CREEPER, Mobs.COW, Mobs.SKELETON };
    private static final int[] ARENA_MOBS = { Mobs.ZOMBIE, Mobs.ZOMBIE, Mobs.ZOMBIE, Mobs.ZOMBIE, Mobs.SKELETON, Mobs.SKELETON,
            Mobs.SKELETON, Mobs.SPIDER, Mobs.SPIDER, Mobs.CREEPER, Mobs.PIG_ZOMBIE, Mobs.SHEEP, Mobs.SHEEP, Mobs.CHICKEN };

    private void spawnMobs() {
        Level l = mc.level;
        float arc = WALK * WALK_SPEED / R_WALK;
        arenaTheta = arc;
        arenaX = CX + R_WALK * Mth.cos(arc);
        arenaZ = CZ + R_WALK * Mth.sin(arc);
        if (calm) return;
        // вдоль пути ходьбы, по обе стороны
        for (int i = 0; i < PATH_MOBS.length; i++) {
            float a = arc * (i + 1.5f) / (PATH_MOBS.length + 1);
            float r = R_WALK + ((i & 1) == 0 ? -4 : 4);
            addMob(l, PATH_MOBS[i], CX + r * Mth.cos(a), CZ + r * Mth.sin(a), i * 47);
        }
        // кольцо вокруг арены
        for (int i = 0; i < ARENA_MOBS.length; i++) {
            float a = i * Mth.PI * 2 / ARENA_MOBS.length, r = 7 + (i % 3) * 3;
            addMob(l, ARENA_MOBS[i], arenaX + r * Mth.cos(a), arenaZ + r * Mth.sin(a), i * 31);
        }
    }

    private static void addMob(Level l, int id, float x, float z, float yaw) {
        Mob m = (Mob) Mobs.create(id, l);
        m.setPos(x, l.getTopSolid(Mth.floor(x), Mth.floor(z)), z);
        m.yRot = yaw;
        m.bodyRot = m.bodyRotO = yaw;
        if (m instanceof Mobs.Sheep) ((Mobs.Sheep) m).color = Mobs.Sheep.randomColor(l.random);
        l.addEntity(m);
    }

    /** Тик сценария — из MC.tick сразу после player.tick(). */
    void tick() {
        if (done) return;
        Level l = mc.level;
        Player p = mc.player;
        float px, pz, yaw, pitch, ty, gr;
        if (t < WALK) {
            theta = t * WALK_SPEED / R_WALK;
            px = CX + R_WALK * Mth.cos(theta);
            pz = CZ + R_WALK * Mth.sin(theta);
            ty = gr = ground(l, px, pz);
            y += (ty - y) * 0.35f;
            yaw = theta / Mth.DEG + 35 * Mth.sin(t * Mth.PI * 2 / 160);
            pitch = 12 + 10 * Mth.sin(t / 25f);
        } else if (t < ARENA) {
            int k = t - WALK;
            px = arenaX;
            pz = arenaZ;
            ty = gr = ground(l, px, pz);
            y += (ty - y) * 0.35f;
            yaw = arenaTheta / Mth.DEG + 360f * k / (ARENA - WALK);
            pitch = 15 + 15 * Mth.sin(k / 30f);
            // 4 TNT кольцом, по одному каждые 20 тиков; взрывы с 480-го тика
            if (!calm && k < 80 && k % 20 == 0) {
                float a = k / 20 * Mth.PI / 2, tx = arenaX + 9 * Mth.cos(a), tz = arenaZ + 9 * Mth.sin(a);
                l.addEntity(new PrimedTnt(l, tx, l.getTopSolid(Mth.floor(tx), Mth.floor(tz)), tz));
            }
            // источник воды на возвышении — течёт до конца прогона
            if (!calm && k == 20) {
                int wx = Mth.floor(arenaX) + 5, wz = Mth.floor(arenaZ) - 4;
                l.setTile(wx, l.getTopSolid(wx, wz) + 2, wz, Tile.WATER);
            }
        } else {
            int k = t - ARENA;
            float r = R_WALK + (R_FLY - R_WALK) * Math.min(1f, k / 60f);
            flyTheta = arenaTheta + k * FLY_SPEED / R_FLY;
            px = CX + r * Mth.cos(flyTheta);
            pz = CZ + r * Mth.sin(flyTheta);
            gr = ground(l, px, pz);
            ty = gr + 12;
            y += (ty - y) * 0.1f;
            yaw = flyTheta / Mth.DEG;
            pitch = 25;
        }
        if (y < gr) y = gr;
        p.xo = p.x; p.yo = p.y; p.zo = p.z;
        p.x = px; p.y = y; p.z = pz;
        p.xd = p.yd = p.zd = 0;
        p.fallDistance = 0;
        p.yRotO = p.yRot; p.xRotO = p.xRot;
        p.yRot = yaw; p.xRot = pitch;
        p.health = 20;
        if (++t >= END) done = true;
    }

    /** высота земли с учётом соседних клеток — камера не проваливается в склоны */
    private static float ground(Level l, float x, float z) {
        int bx = Mth.floor(x), bz = Mth.floor(z), h = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) h = Math.max(h, l.getTopSolid(bx + dx, bz + dz));
        return h;
    }

    // ---- замеры ----

    void tickTime(long ms) {
        tickMs += ms;
        if (ms > maxTick) maxTick = (int) ms;
    }

    /** Конец кадра цикла MC.run. */
    void frame(long now) {
        int ms = (int) (now - lastFrame);
        lastFrame = now;
        if (nFrames == frameMs.length) {
            int[] n = new int[nFrames * 2];
            System.arraycopy(frameMs, 0, n, 0, nFrames);
            frameMs = n;
        }
        frameMs[nFrames++] = ms;
        int ph = t < WALK ? 0 : (t < ARENA ? 1 : 2);
        phaseMs[ph] += ms;
        phaseFrames[ph]++;
        if (done) finish();
    }

    private void finish() {
        long total = lastFrame - startMs;
        int[] s = new int[nFrames];
        System.arraycopy(frameMs, 0, s, 0, nFrames);
        sort(s);
        Level l = mc.level;
        String fps = fps1(nFrames, total);
        StringBuffer sb = new StringBuffer(calm ? "BENCH calm fps " : "BENCH fps ").append(fps)
                .append(" frame ").append(nFrames > 0 ? total / nFrames : 0)
                .append(" p50 ").append(pct(s, 50)).append(" p95 ").append(pct(s, 95)).append(" p99 ").append(pct(s, 99))
                .append(" max ").append(nFrames > 0 ? s[nFrames - 1] : 0)
                .append(" | walk ").append(fps1(phaseFrames[0], phaseMs[0]))
                .append(" arena ").append(fps1(phaseFrames[1], phaseMs[1]))
                .append(" fly ").append(fps1(phaseFrames[2], phaseMs[2]))
                .append(" | tick ").append(per(tickMs, END)).append(" max ").append(maxTick)
                .append(" | frames ").append(nFrames).append(" ms ").append(total)
                .append(" ent ").append(l.entities.size()).append(" mobs ").append(count(l, true)).append(" items ").append(count(l, false)).append(" hash ").append(Integer.toHexString(hash(l, mc.player)));
        Log.i(sb.toString());
        if (cmp) {
            cmpMs[calm ? 0 : 1] += total;
            cmpFrames[calm ? 0 : 1] += nFrames;
            if (left == 0 && cmpFrames[0] > 0 && cmpFrames[1] > 0) {
                double a = (double) cmpMs[0] / cmpFrames[0], c = (double) cmpMs[1] / cmpFrames[1];
                Log.i("BENCH cmp calm " + (int) (a * 100) / 100f + " ms, full " + (int) (c * 100) / 100f + " ms, heavier by "
                        + (int) ((c / a - 1) * 1000) / 10f + "%");
            }
        }
        final String line1 = "Benchmark: " + fps + " fps, frame " + (nFrames > 0 ? total / nFrames : 0) + " ms";
        final String line2 = "p95 " + pct(s, 95) + " ms, p99 " + pct(s, 99) + " ms, hash " + Integer.toHexString(hash(l, mc.player));
        Options.graphics = oGfx; Options.viewChunks = oView; Options.peaceful = oPeaceful; Options.thirdPerson = oThird;
        run = null;
        mc.level = null;
        mc.player = null;
        mc.lr = null;
        mc.applyOptions();
        if (left > 0) {
            next(mc);
            return;
        }
        mc.setScreen(new ConfirmScreen(line1, line2, "OK", "Quit") {
            protected void postResult(boolean ok) {
                mc.setScreen(new StartMenuScreen());
            }
        });
    }

    private static int count(Level l, boolean mobs) {
        int n = 0;
        for (int i = 0; i < l.entities.size(); i++) {
            Object e = l.entities.elementAt(i);
            if (mobs ? e instanceof Mob : e instanceof ItemEntity) n++;
        }
        return n;
    }

    /** Итоговое состояние: сущности и блоки вокруг пути — для проверки, что прогоны одинаковы. */
    private static int hash(Level l, Player p) {
        int h = l.entities.size();
        for (int i = 0; i < l.entities.size(); i++) {
            Entity e = (Entity) l.entities.elementAt(i);
            h = h * 31 + e.typeId();
            h = h * 31 + (int) (e.x * 16);
            h = h * 31 + (int) (e.y * 16);
            h = h * 31 + (int) (e.z * 16);
        }
        for (int x = 64; x < 192; x++)
            for (int z = 64; z < 192; z++) h = h * 31 + l.getTopSolid(x, z) * 256 + l.getTile(x, l.getTopSolid(x, z) - 1, z);
        return h;
    }

    private static void sort(int[] a) {
        // подсчётом: время кадра до 2 с
        int[] cnt = new int[2001];
        for (int i = 0; i < a.length; i++) cnt[Math.min(2000, Math.max(0, a[i]))]++;
        int k = 0;
        for (int v = 0; v <= 2000; v++) for (int c = cnt[v]; c > 0; c--) a[k++] = v;
    }

    private static int pct(int[] s, int p) {
        if (s.length == 0) return 0;
        return s[Math.min(s.length - 1, s.length * p / 100)];
    }

    private static String fps1(int frames, long ms) {
        if (ms <= 0) return "0";
        long v = frames * 10000L / ms;
        return (v / 10) + "." + (v % 10);
    }

    private static String per(long total, int n) {
        long v = total * 10 / Math.max(1, n);
        return (v / 10) + "." + (v % 10);
    }
}
