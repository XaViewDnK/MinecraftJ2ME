import java.util.Random;

/**
 * Мир 256x128x256 из 16x16 чанков. Хранение внутри чанка — как в файле MCPE:
 * индекс (x<<11)|(z<<7)|y, полубайтовые массивы data/skyLight/blockLight (чётный индекс — младшая тетрада).
 */
public final class Level {
    public static final int SIZE = 256, HEIGHT = 128, CHUNKS = 16;
    public static final int SEA_LEVEL = 64;
    public static final int TICKS_PER_DAY = 19200;

    /** блоки, data, свет неба и блоков — сжатые слои (Pal), сырыми было бы 20 МБ */
    public final Pal blocks = new Pal(8), data = new Pal(4), sky = new Pal(4), blk = new Pal(4);
    public final byte[][] updateMap = new byte[CHUNKS * CHUNKS][];
    public final boolean[] unsaved = new boolean[CHUNKS * CHUNKS];
    /** y первого блока над непрозрачной для света частью столбца */
    public final byte[] height = new byte[SIZE * SIZE];

    public long seed;
    public String name = "world";
    public String folder;
    public int gameType; // 0 выживание, 1 творчество
    public int spawnX = 128, spawnY = 64, spawnZ = 128;
    public long time;
    public long lastPlayed;
    public int storageVersion = 3;
    public int skyDarken;
    /** AdventureSettings::showNameTags — по умолчанию выключено, включает только AdventureSettingsPacket. */
    public boolean showNameTags;
    public Nbt.Compound playerTag;
    public Nbt.Compound levelTag;
    public Nbt.Compound entitiesTag;

    public LevelRenderer renderer;
    /** хост сетевой игры (LevelListener) */
    public NetServer net;
    /** клиент сетевой игры (MultiPlayerLevel): без тиков блоков, сущности — только от хоста */
    public boolean isClientSide;
    /** клиент: сущность добавляет сеть (остальные добавления у клиента игнорируются, как !isClientSide в оригинале) */
    public static boolean netAdd;
    public final java.util.Vector entities = new java.util.Vector();
    /** сущности неизвестных нам типов из entities.dat — пишутся обратно без изменений */
    public final java.util.Vector rawEntities = new java.util.Vector();
    public final java.util.Hashtable tileEntities = new java.util.Hashtable();
    public Player player;
    public final Random random = new Random();
    private int randValue;

    public Level() {
        for (int i = 0; i < CHUNKS * CHUNKS; i++) {
            updateMap[i] = new byte[256];
        }
        randValue = random.nextInt();
    }

    /** Бенчмарк: фиксированные случайные числа мира. */
    void reseed(long s) {
        random.setSeed(s);
        randValue = random.nextInt();
    }

    public static boolean inside(int x, int y, int z) {
        return (x & ~255) == 0 && (z & ~255) == 0 && (y & ~127) == 0;
    }

    public int getTile(int x, int y, int z) {
        if ((x & ~255) != 0 || (z & ~255) != 0 || (y & ~127) != 0) return 0;
        return blocks.get((x >> 4) | ((z >> 4) << 4), ((x & 15) << 11) | ((z & 15) << 7) | y);
    }

    public int getData(int x, int y, int z) {
        if ((x & ~255) != 0 || (z & ~255) != 0 || (y & ~127) != 0) return 0;
        return data.get((x >> 4) | ((z >> 4) << 4), ((x & 15) << 11) | ((z & 15) << 7) | y);
    }

    public int getSky(int x, int y, int z) {
        if ((x & ~255) != 0 || (z & ~255) != 0) return 15;
        if (y >= HEIGHT) return 15;
        if (y < 0) return 0;
        return sky.get((x >> 4) | ((z >> 4) << 4), ((x & 15) << 11) | ((z & 15) << 7) | y);
    }

    public int getBlockLight(int x, int y, int z) {
        if ((x & ~255) != 0 || (z & ~255) != 0 || (y & ~127) != 0) return 0;
        return blk.get((x >> 4) | ((z >> 4) << 4), ((x & 15) << 11) | ((z & 15) << 7) | y);
    }

    /** Итоговая яркость 0..15 с учётом времени суток. */
    public int getRawBrightness(int x, int y, int z) {
        // Level::getRawBrightness(propagate): у плиты и грядки — максимум из соседей сверху и по бокам
        int id = getTile(x, y, z);
        if (id == Tile.SLAB || id == Tile.FARMLAND) {
            int br = rawBr(x, y + 1, z);
            br = Math.max(br, rawBr(x + 1, y, z));
            br = Math.max(br, rawBr(x - 1, y, z));
            br = Math.max(br, rawBr(x, y, z + 1));
            return Math.max(br, rawBr(x, y, z - 1));
        }
        return rawBr(x, y, z);
    }

    /**
     * Свет клетки до учёта времени суток: (небо << 4) | блоки; у плиты и грядки — максимум по соседям, как в
     * getRawBrightness. Яркость = max(блоки, небо - skyDarken) — так цвета мира пересчитываются при смене времени
     * суток без перестройки геометрии.
     */
    public int lightCode(int x, int y, int z) {
        int id = getTile(x, y, z);
        if (id == Tile.SLAB || id == Tile.FARMLAND) {
            int c = rawCode(x, y + 1, z);
            c = maxCode(c, rawCode(x + 1, y, z));
            c = maxCode(c, rawCode(x - 1, y, z));
            c = maxCode(c, rawCode(x, y, z + 1));
            return maxCode(c, rawCode(x, y, z - 1));
        }
        return rawCode(x, y, z);
    }

    private int rawCode(int x, int y, int z) {
        if (y < 0) return 0;
        return (getSky(x, y, z) << 4) | getBlockLight(x, y, z);
    }

    static int maxCode(int a, int b) {
        return Math.max(a & 0xF0, b & 0xF0) | Math.max(a & 15, b & 15);
    }

    /** Уровень 0..15 по коду света при текущем skyDarken (как rawBr). */
    public int levelOf(int code) {
        int s = (code >> 4) - skyDarken, b = code & 15;
        return b > s ? b : (s < 0 ? 0 : s);
    }

    private int rawBr(int x, int y, int z) {
        if (y < 0) return 0;
        int s = getSky(x, y, z) - skyDarken;
        int b = getBlockLight(x, y, z);
        return b > s ? b : (s < 0 ? 0 : s);
    }

    static void setNibble(byte[] arr, int i, int v) {
        int j = i >> 1;
        if ((i & 1) == 0) arr[j] = (byte) ((arr[j] & 0xF0) | (v & 15));
        else arr[j] = (byte) ((arr[j] & 0x0F) | ((v & 15) << 4));
    }

    static int getNibble(byte[] arr, int i) {
        int b = arr[i >> 1];
        return (i & 1) == 0 ? b & 15 : (b >> 4) & 15;
    }

    // ---- изменение блоков ----

    /** Запись без пересчёта света и уведомлений (генератор). */
    public void setTileRaw(int x, int y, int z, int id, int d) {
        if (!inside(x, y, z)) return;
        int c = (x >> 4) | ((z >> 4) << 4);
        int i = ((x & 15) << 11) | ((z & 15) << 7) | y;
        blocks.set(c, i, id);
        data.set(c, i, d);
    }

    /** Level::noNeighborUpdate */
    public boolean noNeighborUpdate;

    /** Level::setTile: data обнуляется, тот же id — ничего не делает. */
    public boolean setTile(int x, int y, int z, int id) {
        if (setTileNoUpdate(x, y, z, id)) {
            updateNeighborsAt(x, y, z, id);
            return true;
        }
        return false;
    }

    public boolean setTileAndData(int x, int y, int z, int id, int d) {
        if (setTileAndDataNoUpdate(x, y, z, id, d)) {
            updateNeighborsAt(x, y, z, id);
            return true;
        }
        return false;
    }

    public boolean setTileNoUpdate(int x, int y, int z, int id) {
        if (!inside(x, y, z) || getTile(x, y, z) == id) return false;
        return setRaw(x, y, z, id, 0);
    }

    public boolean setTileAndDataNoUpdate(int x, int y, int z, int id, int d) {
        if (!inside(x, y, z)) return false;
        if (getTile(x, y, z) == id && getData(x, y, z) == d) return false;
        return setRaw(x, y, z, id, d);
    }

    /** LevelChunk::setTileAndData: onRemove старого, свет, onPlace нового. */
    private boolean setRaw(int x, int y, int z, int id, int d) {
        int c = (x >> 4) | ((z >> 4) << 4);
        int i = ((x & 15) << 11) | ((z & 15) << 7) | y;
        int old = blocks.get(c, i);
        blocks.set(c, i, id);
        if (old != 0) TileLogic.onRemove(this, old, x, y, z);
        data.set(c, i, d);
        unsaved[c] = true;
        updateMap[c][(x & 15) | ((z & 15) << 4)] |= (byte) (1 << (y >> 4));
        if (Tile.lightBlock[old] != Tile.lightBlock[id] || Tile.lightEmit[old] != Tile.lightEmit[id]) {
            relightAt(x, y, z);
        }
        if (renderer != null) renderer.tileChanged(x, y, z);
        if (net != null) net.tileChanged(x, y, z);
        if (id != 0) TileLogic.onPlace(this, id, x, y, z);
        return true;
    }

    /** Level::setData: изменение — уведомить соседей. */
    public void setData(int x, int y, int z, int d) {
        if (setDataNoUpdate(x, y, z, d)) updateNeighborsAt(x, y, z, getTile(x, y, z));
    }

    public boolean setDataNoUpdate(int x, int y, int z, int d) {
        if (!inside(x, y, z)) return false;
        int c = (x >> 4) | ((z >> 4) << 4);
        int i = ((x & 15) << 11) | ((z & 15) << 7) | y;
        if (data.get(c, i) == d) return false;
        data.set(c, i, d);
        unsaved[c] = true;
        updateMap[c][(x & 15) | ((z & 15) << 4)] |= (byte) (1 << (y >> 4));
        if (renderer != null) renderer.tileChanged(x, y, z);
        if (net != null) net.tileChanged(x, y, z);
        return true;
    }

    /** Level::updateNeighborsAt: шесть соседей, type — id изменившегося блока. */
    public void updateNeighborsAt(int x, int y, int z, int type) {
        neighborChanged(x - 1, y, z, type);
        neighborChanged(x + 1, y, z, type);
        neighborChanged(x, y - 1, z, type);
        neighborChanged(x, y + 1, z, type);
        neighborChanged(x, y, z - 1, type);
        neighborChanged(x, y, z + 1, type);
    }

    public void neighborChanged(int x, int y, int z, int type) {
        if (noNeighborUpdate) return;
        int id = getTile(x, y, z);
        if (id != 0) TileLogic.neighborChanged(this, id, x, y, z, type);
    }

    public int getMaterial(int x, int y, int z) {
        return Tile.mat[getTile(x, y, z)];
    }

    /** Level::isSolidBlockingTile */
    public boolean isSolidBlockingTile(int x, int y, int z) {
        return Tile.solidBlocking(getTile(x, y, z));
    }

    public boolean isEmptyTile(int x, int y, int z) {
        return getTile(x, y, z) == 0;
    }

    /** LevelChunk::isSkyLit: выше карты высот */
    public boolean canSeeSky(int x, int y, int z) {
        if ((x & ~255) != 0 || (z & ~255) != 0) return false;
        if (y >= HEIGHT) return true;
        return y >= (height[x | (z << 8)] & 255);
    }

    public boolean isDay() {
        return skyDarken < 4;
    }

    /** Level::getBrightness: Dimension::brightnessRamp от getRawBrightness. */
    public float getBrightness(int x, int y, int z) {
        return LevelRenderer.BRF[getRawBrightness(x, y, z)];
    }

    // ---- отложенные тики (Level::addToTickNextTick / tickPendingTicks) ----

    private int[] tkPos = new int[256], tkId = new int[256];
    private long[] tkTime = new long[256];
    private int tkCount;
    static final int MAX_TICK_TILES_PER_TICK = 100;

    /** Порядок как у std::set<TickNextTickData>: по времени, затем по порядку добавления (дубли разрешены). */
    public void addToTickNextTick(int x, int y, int z, int id, int delay) {
        if (!inside(x, y, z) || id <= 0 || isClientSide) return;
        if (tkCount == tkPos.length) {
            int n = tkCount * 2;
            int[] a = new int[n], b = new int[n];
            long[] t = new long[n];
            System.arraycopy(tkPos, 0, a, 0, tkCount);
            System.arraycopy(tkId, 0, b, 0, tkCount);
            System.arraycopy(tkTime, 0, t, 0, tkCount);
            tkPos = a; tkId = b; tkTime = t;
        }
        long when = time + delay;
        int i = tkCount;
        while (i > 0 && tkTime[i - 1] > when) i--;
        System.arraycopy(tkPos, i, tkPos, i + 1, tkCount - i);
        System.arraycopy(tkId, i, tkId, i + 1, tkCount - i);
        System.arraycopy(tkTime, i, tkTime, i + 1, tkCount - i);
        tkPos[i] = x | (z << 8) | (y << 16);
        tkId[i] = id;
        tkTime[i] = when;
        tkCount++;
    }

    public void clearPendingTicks() {
        tkCount = 0;
    }

    private void tickPendingTicks() {
        int count = Math.min(tkCount, MAX_TICK_TILES_PER_TICK);
        for (int k = 0; k < count; k++) {
            if (tkCount == 0 || tkTime[0] > time) break;
            int p = tkPos[0], id = tkId[0];
            tkCount--;
            System.arraycopy(tkPos, 1, tkPos, 0, tkCount);
            System.arraycopy(tkId, 1, tkId, 0, tkCount);
            System.arraycopy(tkTime, 1, tkTime, 0, tkCount);
            int x = p & 255, z = (p >> 8) & 255, y = (p >> 16) & 127;
            if (getTile(x, y, z) == id) TileLogic.tick(this, id, x, y, z, random);
        }
    }

    public boolean isSolidBlocking(int x, int y, int z) {
        return Tile.opaque[getTile(x, y, z)];
    }

    public int getTopSolid(int x, int z) {
        for (int y = HEIGHT - 1; y > 0; y--) {
            int t = getTile(x, y, z);
            if (t != 0 && (Tile.solid[t] || Tile.isLiquid(t))) return y + 1;
        }
        return 0;
    }

    // ---- свет ----

    private static void setLight(Pal layer, int x, int y, int z, int v) {
        layer.set((x >> 4) | ((z >> 4) << 4), ((x & 15) << 11) | ((z & 15) << 7) | y, v);
    }

    private static int getLight(Pal layer, int x, int y, int z) {
        return layer.get((x >> 4) | ((z >> 4) << 4), ((x & 15) << 11) | ((z & 15) << 7) | y);
    }

    private int calcHeight(int x, int z) {
        int c = (x >> 4) | ((z >> 4) << 4);
        int base = ((x & 15) << 11) | ((z & 15) << 7);
        Pal b = blocks;
        int y = HEIGHT - 1;
        // пустые куски сверху пропускаются целиком
        while (y >= 3 && b.uniform((c << 5) | (y >> 2)) && Tile.lightBlock[b.uniformValue((c << 5) | (y >> 2))] == 0) y -= 4;
        while (y >= 0 && Tile.lightBlock[b.get(c, base | y)] == 0) y--;
        return y + 1;
    }

    // очереди растут по необходимости и сжимаются после полного пересчёта света
    private int[] queue = new int[1 << 12];
    private int qHead, qTail;
    private int[] remQ = new int[1 << 12];
    private int dMinX, dMinY, dMinZ, dMaxX, dMaxY, dMaxZ;

    private void push(int x, int y, int z, int lvl) {
        if (qTail - qHead >= queue.length - 1) {
            int[] n = new int[queue.length * 2];
            System.arraycopy(queue, qHead, n, 0, qTail - qHead);
            qTail -= qHead; qHead = 0; queue = n;
        } else if (qTail >= queue.length) {
            System.arraycopy(queue, qHead, queue, 0, qTail - qHead);
            qTail -= qHead; qHead = 0;
        }
        queue[qTail++] = x | (z << 8) | (y << 16) | (lvl << 24);
    }

    private void touched(int x, int y, int z) {
        if (x < dMinX) dMinX = x; if (x > dMaxX) dMaxX = x;
        if (y < dMinY) dMinY = y; if (y > dMaxY) dMaxY = y;
        if (z < dMinZ) dMinZ = z; if (z > dMaxZ) dMaxZ = z;
    }

    private static final int[] DX = { 0, 0, 0, 0, -1, 1 }, DY = { -1, 1, 0, 0, 0, 0 }, DZ = { 0, 0, -1, 1, 0, 0 };

    /** Распространение из очереди (увеличение света). */
    private void propagate(Pal layer, boolean track) {
        while (qHead < qTail) {
            int p = queue[qHead++];
            int x = p & 255, z = (p >> 8) & 255, y = (p >> 16) & 127;
            int l = getLight(layer, x, y, z);
            if (l <= 1) continue;
            for (int f = 0; f < 6; f++) {
                int nx = x + DX[f], ny = y + DY[f], nz = z + DZ[f];
                if ((nx & ~255) != 0 || (nz & ~255) != 0 || (ny & ~127) != 0) continue;
                int op = Tile.lightBlock[getTile(nx, ny, nz)];
                int nl = l - (op < 1 ? 1 : op);
                if (nl > getLight(layer, nx, ny, nz)) {
                    setLight(layer, nx, ny, nz, nl);
                    if (track) touched(nx, ny, nz);
                    push(nx, ny, nz, 0);
                }
            }
        }
        qHead = qTail = 0;
    }

    /** Удаление света с позиций (снятие источников), затем досвет от границы. */
    private void removeLight(Pal layer, boolean skyLayer, int[] pos, int count) {
        int rh = 0, rt = 0;
        int[] rq = remQ;
        for (int k = 0; k < count; k++) {
            int p = pos[k];
            int x = p & 255, z = (p >> 8) & 255, y = (p >> 16) & 127;
            int l = getLight(layer, x, y, z);
            setLight(layer, x, y, z, 0);
            touched(x, y, z);
            if (rt >= rq.length) { int[] n = new int[rq.length * 2]; System.arraycopy(rq, 0, n, 0, rt); rq = remQ = n; }
            rq[rt++] = x | (z << 8) | (y << 16) | (l << 24);
            // соседи, которые могут посветить внутрь
            for (int f = 0; f < 6; f++) {
                int nx = x + DX[f], ny = y + DY[f], nz = z + DZ[f];
                if ((nx & ~255) != 0 || (nz & ~255) != 0 || (ny & ~127) != 0) continue;
                push(nx, ny, nz, 0);
            }
        }
        while (rh < rt) {
            int p = rq[rh++];
            int x = p & 255, z = (p >> 8) & 255, y = (p >> 16) & 127, l = (p >>> 24) & 15;
            for (int f = 0; f < 6; f++) {
                int nx = x + DX[f], ny = y + DY[f], nz = z + DZ[f];
                if ((nx & ~255) != 0 || (nz & ~255) != 0 || (ny & ~127) != 0) continue;
                int nl = getLight(layer, nx, ny, nz);
                if (nl == 0) continue;
                boolean src = skyLayer ? (ny >= (height[nx | (nz << 8)] & 255)) : (Tile.lightEmit[getTile(nx, ny, nz)] > 0);
                if (nl < l && !src) {
                    setLight(layer, nx, ny, nz, 0);
                    touched(nx, ny, nz);
                    if (rt >= rq.length) { int[] n = new int[rq.length * 2]; System.arraycopy(rq, 0, n, 0, rt); rq = remQ = n; }
                    rq[rt++] = nx | (nz << 8) | (ny << 16) | (nl << 24);
                } else {
                    push(nx, ny, nz, 0);
                }
            }
        }
    }

    private final int[] tmpPos = new int[256];

    private void relightAt(int x, int y, int z) {
        dMinX = dMaxX = x; dMinY = dMaxY = y; dMinZ = dMaxZ = z;
        int col = x | (z << 8);
        int h0 = height[col] & 255;
        int h1 = calcHeight(x, z);
        height[col] = (byte) h1;

        // небесный свет
        int n = 0;
        if (h1 > h0) {
            for (int yy = h0; yy < h1; yy++) tmpPos[n++] = x | (z << 8) | (yy << 16);
        }
        if (y < h0 || y < h1) {
            boolean dup = false;
            for (int k = 0; k < n; k++) if (tmpPos[k] == (x | (z << 8) | (y << 16))) dup = true;
            if (!dup) tmpPos[n++] = x | (z << 8) | (y << 16);
        }
        qHead = qTail = 0;
        if (n > 0) removeLight(sky, true, tmpPos, n);
        if (h1 < h0) {
            for (int yy = h1; yy < h0; yy++) {
                setLight(sky, x, yy, z, 15);
                touched(x, yy, z);
                push(x, yy, z, 0);
            }
        }
        if (y >= h1) {
            setLight(sky, x, y, z, 15);
            push(x, y, z, 0);
        }
        propagate(sky, true);

        // свет блоков
        qHead = qTail = 0;
        tmpPos[0] = x | (z << 8) | (y << 16);
        removeLight(blk, false, tmpPos, 1);
        int em = Tile.lightEmit[getTile(x, y, z)];
        if (em > 0) {
            setLight(blk, x, y, z, em);
            push(x, y, z, 0);
        }
        // заново зажигаем источники, чей свет был снят
        propagate(blk, true);
        relightEmittersIn(dMinX, dMinY, dMinZ, dMaxX, dMaxY, dMaxZ);

        if (renderer != null) renderer.lightChanged(dMinX - 1, dMinY - 1, dMinZ - 1, dMaxX + 1, dMaxY + 1, dMaxZ + 1);
    }

    private void relightEmittersIn(int x0, int y0, int z0, int x1, int y1, int z1) {
        if ((x1 - x0) * (y1 - y0) * (z1 - z0) > 40000) return;
        qHead = qTail = 0;
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++) {
                    if (!inside(x, y, z)) continue;
                    int em = Tile.lightEmit[getTile(x, y, z)];
                    if (em > 0 && getLight(blk, x, y, z) < em) {
                        setLight(blk, x, y, z, em);
                        push(x, y, z, 0);
                    }
                }
        propagate(blk, true);
    }

    /**
     * Полный расчёт карты высот и света (после генерации или загрузки старого мира). Куски целиком над рельефом
     * чанка или целиком под ним заполняются одним значением, растекание идёт только от границ.
     */
    public void recalcAllLight() {
        recalcHeightmap();
        int[] vals = new int[Pal.CELLS];
        for (int c = 0; c < CHUNKS * CHUNKS; c++) {
            int cx = (c & 15) << 4, cz = (c >> 4) << 4, minH = 255, maxH = 0;
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++) {
                    int h = height[(cx + x) | ((cz + z) << 8)] & 255;
                    if (h < minH) minH = h;
                    if (h > maxH) maxH = h;
                }
            for (int py = 0; py < Pal.NP; py++) {
                int y0 = py << 2, k = (c << 5) | py;
                if (y0 >= maxH) sky.fill(k, 15);
                else if (y0 + 3 < minH) sky.fill(k, 0);
                else {
                    for (int cell = 0; cell < Pal.CELLS; cell++) {
                        int x = cell >> 6, z = (cell >> 2) & 15, y = y0 | (cell & 3);
                        vals[cell] = y >= (height[(cx + x) | ((cz + z) << 8)] & 255) ? 15 : 0;
                    }
                    sky.encode(k, vals);
                }
            }
        }
        qHead = qTail = 0;
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++) {
                int h = height[x | (z << 8)] & 255;
                // источники для бокового растекания: ячейки над своей высотой, но ниже высоты соседей
                int mh = h;
                if (x > 0) mh = Math.max(mh, height[(x - 1) | (z << 8)] & 255);
                if (x < 255) mh = Math.max(mh, height[(x + 1) | (z << 8)] & 255);
                if (z > 0) mh = Math.max(mh, height[x | ((z - 1) << 8)] & 255);
                if (z < 255) mh = Math.max(mh, height[x | ((z + 1) << 8)] & 255);
                for (int y = h; y < mh && y < HEIGHT; y++) push(x, y, z, 0);
                if (h > 0 && h < HEIGHT) push(x, h, z, 0);
            }
        propagate(sky, false);
        qHead = qTail = 0;
        blk.clear(0);
        for (int c = 0; c < CHUNKS * CHUNKS; c++) {
            int cx = (c & 15) << 4, cz = (c >> 4) << 4;
            for (int py = 0; py < Pal.NP; py++) {
                int k = (c << 5) | py;
                if (blocks.uniform(k) && Tile.lightEmit[blocks.uniformValue(k)] == 0) continue;
                blocks.decode(k, vals);
                for (int cell = 0; cell < Pal.CELLS; cell++) {
                    int em = Tile.lightEmit[vals[cell]];
                    if (em > 0) {
                        int x = cx + (cell >> 6), z = cz + ((cell >> 2) & 15), y = (py << 2) | (cell & 3);
                        setLight(blk, x, y, z, em);
                        push(x, y, z, 0);
                    }
                }
            }
        }
        propagate(blk, false);
        // большие очереди нужны только полному пересчёту
        queue = new int[1 << 12];
        remQ = new int[1 << 12];
    }

    public void recalcHeightmap() {
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++) height[x | (z << 8)] = (byte) calcHeight(x, z);
    }

    // ---- время ----

    public void updateSkyDarken() {
        float t = getTimeOfDay(1f);
        // Level::getSkyDarken 0.6.1: потолок 0.8 — ночью темнеет не больше чем на 8 уровней
        float c = 1f - ((float) Math.cos(t * Math.PI * 2) * 2f + 0.5f);
        if (c < 0) c = 0;
        if (c > 0.8f) c = 0.8f;
        int d = (int) (c * 11f);
        if (d != skyDarken) {
            if (Prof.on) Log.i("DARKEN " + skyDarken + " -> " + d + " at " + time % TICKS_PER_DAY);
            skyDarken = d;
            if (renderer != null) renderer.skyChanged();
        }
    }

    /** 0..1, 0 — полдень как в Minecraft (0.25 — закат). */
    public float getTimeOfDay(float a) {
        int dayTime = (int) (time % TICKS_PER_DAY);
        float td = (dayTime + a) / TICKS_PER_DAY - 0.25f;
        if (td < 0) td += 1;
        if (td > 1) td -= 1;
        float tdo = td;
        td = 1f - (float) ((Math.cos(td * Math.PI) + 1) / 2);
        td = tdo + (td - tdo) / 3f;
        return td;
    }

    /** Яркость неба 0..1 для цвета фона. */
    public float getSkyBrightness(float a) {
        float t = getTimeOfDay(a);
        float br = (float) Math.cos(t * Math.PI * 2) * 2f + 0.5f;
        if (br < 0) br = 0;
        if (br > 1) br = 1;
        return br;
    }

    // ---- тики ----

    public void addEntity(Entity e) {
        if (isClientSide && !netAdd) return;
        entities.addElement(e);
        if (net != null) net.entityAdded(e);
    }

    public Entity getEntity(int id) {
        for (int i = 0; i < entities.size(); i++) {
            Entity e = (Entity) entities.elementAt(i);
            if (e.entityId == id && !e.removed) return e;
        }
        return null;
    }

    /** квадрат расстояния до ближайшего живого игрока (свой и игроки сети) */
    public float nearestPlayerDist2(float x, float y, float z) {
        float best = Float.MAX_VALUE;
        if (player != null) {
            float dx = player.x - x, dy = origY(player) - y, dz = player.z - z;
            best = dx * dx + dy * dy + dz * dz;
        }
        for (int i = 0; i < entities.size(); i++) {
            Object o = entities.elementAt(i);
            if (!(o instanceof RemotePlayer)) continue;
            Entity p = (Entity) o;
            float dx = p.x - x, dy = origY(p) - y, dz = p.z - z;
            float d = dx * dx + dy * dy + dz * dz;
            if (d < best) best = d;
        }
        return best;
    }

    /** Level::getNearestPlayer(source, maxDist) */
    public Mob nearestPlayer(float x, float y, float z, float maxDist) {
        Mob best = null;
        float bd = maxDist < 0 ? Float.MAX_VALUE : maxDist * maxDist;
        if (player != null && !player.isDead()) {
            float dx = player.x - x, dy = origY(player) - y, dz = player.z - z;
            float d = dx * dx + dy * dy + dz * dz;
            if (d < bd) { bd = d; best = player; }
        }
        for (int i = 0; i < entities.size(); i++) {
            Object o = entities.elementAt(i);
            if (!(o instanceof RemotePlayer)) continue;
            RemotePlayer p = (RemotePlayer) o;
            if (p.removed || p.health <= 0) continue;
            float dx = p.x - x, dy = origY(p) - y, dz = p.z - z;
            float d = dx * dx + dy * dy + dz * dz;
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    public void tickEntities() {
        for (int i = 0; i < entities.size(); i++) {
            Entity e = (Entity) entities.elementAt(i);
            if (!e.removed) {
                // далёкие от игроков сущности замораживаем
                if (player == null || e instanceof RemotePlayer || nearestPlayerDist2(e.x, e.y, e.z) < 96 * 96) e.tick();
                // Mob::checkDespawn: дальних монстров оригинал убирает на первом же тике
                else if (e instanceof Monster) e.remove();
            }
            if (e.removed) {
                entities.removeElementAt(i);
                i--;
                if (net != null) net.entityRemoved(e);
            }
        }
        for (java.util.Enumeration en = tileEntities.elements(); en.hasMoreElements(); ) {
            TileEntity te = (TileEntity) en.nextElement();
            te.tick(this);
        }
    }

    public TileEntity getTileEntity(int x, int y, int z) {
        return (TileEntity) tileEntities.get(new Integer(x | (z << 8) | (y << 16)));
    }

    public void setTileEntity(TileEntity te) {
        tileEntities.put(new Integer(te.x | (te.z << 8) | (te.y << 16)), te);
    }

    public void removeTileEntity(int x, int y, int z) {
        tileEntities.remove(new Integer(x | (z << 8) | (y << 16)));
    }

    public int countType(int typeId) {
        int n = 0;
        for (int i = 0; i < entities.size(); i++) if (((Entity) entities.elementAt(i)).typeId() == typeId) n++;
        return n;
    }

    public int countEntities(boolean monsters) {
        int n = 0;
        for (int i = 0; i < entities.size(); i++) {
            Object e = entities.elementAt(i);
            if (monsters ? e instanceof Monster : e instanceof Animal) n++;
        }
        return n;
    }

    /** Позиция сущности как в оригинале (у игрока — глаза, у предмета/TNT/песка — центр). */
    static float origY(Entity e) {
        return e.y + e.savedYOffset();
    }

    /** Explosion::explode + finalizeExplosion 0.6.1. */
    public void explode(Entity source, float cx, float cy, float cz, float r) {
        int[] blow = new int[64];
        int nb = 0;
        java.util.Hashtable seen = new java.util.Hashtable();
        for (int i = 0; i < 16; i++)
            for (int j = 0; j < 16; j++)
                for (int k = 0; k < 16; k++) {
                    if (i != 0 && i != 15 && j != 0 && j != 15 && k != 0 && k != 15) continue;
                    float dx = i / 15f * 2f - 1f, dy = j / 15f * 2f - 1f, dz = k / 15f * 2f - 1f;
                    float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= d; dy /= d; dz /= d;
                    float pow = r * (0.7f + random.nextFloat() * 0.6f);
                    float px = cx, py = cy, pz = cz;
                    while (pow > 0) {
                        int bx = Mth.floor(px), by = Mth.floor(py), bz = Mth.floor(pz);
                        int t = getTile(bx, by, bz);
                        if (t > 0) pow -= (Tile.explosionResistance(t) + 0.3f) * 0.3f;
                        if (pow > 0) {
                            // std::set<TilePos>: сравнение по hashCode
                            Integer key = new Integer(bx * 8976890 + by * 981131 + bz);
                            if (!seen.containsKey(key)) {
                                seen.put(key, key);
                                if (nb * 3 + 3 > blow.length) {
                                    int[] n = new int[blow.length * 2];
                                    System.arraycopy(blow, 0, n, 0, nb * 3);
                                    blow = n;
                                }
                                blow[nb * 3] = bx; blow[nb * 3 + 1] = by; blow[nb * 3 + 2] = bz;
                                nb++;
                            }
                        }
                        px += dx * 0.3f; py += dy * 0.3f; pz += dz * 0.3f;
                        pow -= 0.3f * 0.75f;
                    }
                }
        // сортировка по hashCode (порядок обхода std::set)
        int[] hk = new int[nb];
        int[] ord = new int[nb];
        for (int i = 0; i < nb; i++) {
            hk[i] = blow[i * 3] * 8976890 + blow[i * 3 + 1] * 981131 + blow[i * 3 + 2];
            ord[i] = i;
        }
        for (int i = 1; i < nb; i++) {
            int o = ord[i], h = hk[o], j = i - 1;
            while (j >= 0 && hk[ord[j]] > h) { ord[j + 1] = ord[j]; j--; }
            ord[j + 1] = o;
        }
        // урон и отброс сущностей в кубе радиусом 2r
        float r2 = r * 2;
        float x0 = Mth.floor(cx - r2 - 1), x1 = Mth.floor(cx + r2 + 1), y0 = Mth.floor(cy - r2 - 1), y1 = Mth.floor(cy + r2 + 1);
        float z0 = Mth.floor(cz - r2 - 1), z1 = Mth.floor(cz + r2 + 1);
        Entity[] list = new Entity[entities.size() + 1];
        entities.copyInto(list);
        list[list.length - 1] = player;
        for (int i = 0; i < list.length; i++) {
            Entity e = list[i];
            if (e == null || e == source || e.removed) continue;
            float hw = e.bbW / 2;
            if (e.x + hw <= x0 || e.x - hw >= x1 || e.y + e.bbH <= y0 || e.y >= y1 || e.z + hw <= z0 || e.z - hw >= z1) continue;
            float ey = origY(e);
            float xa = e.x - cx, ya = ey - cy, za = e.z - cz;
            float dist = Mth.sqrt(xa * xa + ya * ya + za * za) / r2;
            if (dist > 1) continue;
            float ida = 1f / Mth.sqrt(xa * xa + ya * ya + za * za);
            xa *= ida; ya *= ida; za *= ida;
            float sp = seenPercent(cx, cy, cz, e);
            float pow = (1 - dist) * sp;
            e.hurt((int) ((pow * pow + pow) / 2 * 8 * r + 1), source);
            e.xd += xa * pow;
            e.yd += ya * pow;
            e.zd += za * pow;
        }
        if (net != null) {
            int[] sorted = new int[nb * 3];
            for (int jj = 0; jj < nb; jj++) {
                int q = ord[jj];
                sorted[jj * 3] = blow[q * 3]; sorted[jj * 3 + 1] = blow[q * 3 + 1]; sorted[jj * 3 + 2] = blow[q * 3 + 2];
            }
            net.explode(cx, cy, cz, r, sorted, nb);
        }
        // finalizeExplosion
        if (MC.instance != null) MC.instance.playSoundAt("random.explode", cx, cy, cz, 4f);
        for (int jj = 0; jj < nb; jj++) {
            int q = ord[jj];
            int bx = blow[q * 3], by = blow[q * 3 + 1], bz = blow[q * 3 + 2];
            int t = getTile(bx, by, bz);
            if ((jj & 7) == 0 && MC.instance != null) {
                float xa = bx + random.nextFloat(), ya = by + random.nextFloat(), za = bz + random.nextFloat();
                float xd = xa - cx, yd = ya - cy, zd = za - cz;
                float inv = 1f / Mth.sqrt(xd * xd + yd * yd + zd * zd);
                xd *= inv; yd *= inv; zd *= inv;
                float speed = 0.5f / (r / inv + 0.1f);
                speed *= random.nextFloat() * random.nextFloat() + 0.3f;
                xd *= speed; yd *= speed; zd *= speed;
                MC.instance.particles.explode((xa + cx) / 2, (ya + cy) / 2, (za + cz) / 2, xd, yd, zd);
                MC.instance.particles.smoke(xa, ya, za, xd, yd, zd, 1);
            }
            if (t > 0) {
                TileLogic.spawnResources(this, t, bx, by, bz, getData(bx, by, bz), 0.3f);
                if (setTileNoUpdate(bx, by, bz, 0)) updateNeighborsAt(bx, by, bz, 0);
                TileLogic.wasExploded(this, t, bx, by, bz);
            }
        }
    }

    /** Клиент: ExplodePacket — Explosion::finalizeExplosion (звук, частицы, блоки без выпадения). */
    public void finalizeExplosionNet(float cx, float cy, float cz, float r, int[] blow, int nb) {
        if (MC.instance != null) MC.instance.playSoundAt("random.explode", cx, cy, cz, 4f);
        for (int jj = 0; jj < nb; jj++) {
            int bx = blow[jj * 3], by = blow[jj * 3 + 1], bz = blow[jj * 3 + 2];
            if ((jj & 7) == 0 && MC.instance != null) {
                float xa = bx + random.nextFloat(), ya = by + random.nextFloat(), za = bz + random.nextFloat();
                float xd = xa - cx, yd = ya - cy, zd = za - cz;
                float inv = 1f / Mth.sqrt(xd * xd + yd * yd + zd * zd);
                xd *= inv; yd *= inv; zd *= inv;
                float speed = 0.5f / (r / inv + 0.1f);
                speed *= random.nextFloat() * random.nextFloat() + 0.3f;
                xd *= speed; yd *= speed; zd *= speed;
                MC.instance.particles.explode((xa + cx) / 2, (ya + cy) / 2, (za + cz) / 2, xd, yd, zd);
                MC.instance.particles.smoke(xa, ya, za, xd, yd, zd, 1);
            }
            if (getTile(bx, by, bz) > 0 && setTileNoUpdate(bx, by, bz, 0)) updateNeighborsAt(bx, by, bz, 0);
        }
    }

    /** Level::getSeenPercent: доля точек рамки сущности, видимых из центра взрыва. */
    private float seenPercent(float cx, float cy, float cz, Entity e) {
        float hw = e.bbW / 2;
        float bx0 = e.x - hw, by0 = e.y, bz0 = e.z - hw, bx1 = e.x + hw, by1 = e.y + e.bbH, bz1 = e.z + hw;
        float xs = 1f / ((bx1 - bx0) * 2 + 1), ys = 1f / ((by1 - by0) * 2 + 1), zs = 1f / ((bz1 - bz0) * 2 + 1);
        int hits = 0, count = 0;
        for (float xx = 0; xx <= 1; xx += xs)
            for (float yy = 0; yy <= 1; yy += ys)
                for (float zz = 0; zz <= 1; zz += zs) {
                    float x = bx0 + (bx1 - bx0) * xx, y = by0 + (by1 - by0) * yy, z = bz0 + (bz1 - bz0) * zz;
                    if (!clip(x, y, z, cx, cy, cz, false, false)) hits++;
                    count++;
                }
        return count == 0 ? 0 : hits / (float) count;
    }

    /** результат последнего clip: блок, грань, точка */
    public int clipX, clipY, clipZ, clipFace;
    public float clipPX, clipPY, clipPZ;
    private final float[] clipBox = new float[6];

    /** Level::clip: шаги по границам блоков от a к b (стартовый и конечный блок не проверяются, как в оригинале). */
    public boolean clip(float ax, float ay, float az, float bx, float by, float bz, boolean liquid, boolean solidOnly) {
        int xt1 = Mth.floor(bx), yt1 = Mth.floor(by), zt1 = Mth.floor(bz);
        int xt0 = Mth.floor(ax), yt0 = Mth.floor(ay), zt0 = Mth.floor(az);
        int iter = 200;
        while (iter-- >= 0) {
            if (xt0 == xt1 && yt0 == yt1 && zt0 == zt1) return false;
            float xc = 999, yc = 999, zc = 999;
            if (xt1 > xt0) xc = xt0 + 1f;
            if (xt1 < xt0) xc = xt0;
            if (yt1 > yt0) yc = yt0 + 1f;
            if (yt1 < yt0) yc = yt0;
            if (zt1 > zt0) zc = zt0 + 1f;
            if (zt1 < zt0) zc = zt0;
            float xdd = 999, ydd = 999, zdd = 999;
            float xd = bx - ax, yd = by - ay, zd = bz - az;
            if (xc != 999) xdd = (xc - ax) / xd;
            if (yc != 999) ydd = (yc - ay) / yd;
            if (zc != 999) zdd = (zc - az) / zd;
            int face;
            if (xdd < ydd && xdd < zdd) {
                face = xt1 > xt0 ? 4 : 5;
                ax = xc; ay += yd * xdd; az += zd * xdd;
            } else if (ydd < zdd) {
                face = yt1 > yt0 ? 0 : 1;
                ax += xd * ydd; ay = yc; az += zd * ydd;
            } else {
                face = zt1 > zt0 ? 2 : 3;
                ax += xd * zdd; ay += yd * zdd; az = zc;
            }
            xt0 = Mth.floor(ax); if (face == 5) xt0--;
            yt0 = Mth.floor(ay); if (face == 1) yt0--;
            zt0 = Mth.floor(az); if (face == 3) zt0--;
            int t = getTile(xt0, yt0, zt0);
            if (t <= 0) continue;
            if (solidOnly && !TileLogic.hasCollision(this, t, xt0, yt0, zt0)) continue;
            if (!TileLogic.mayPick(t, getData(xt0, yt0, zt0), liquid)) continue;
            if (xt0 < 0 || zt0 < 0 || xt0 >= SIZE || zt0 >= SIZE) continue;
            TileLogic.shape(this, t, xt0, yt0, zt0, clipBox);
            if (clipBoxHit(xt0, yt0, zt0, ax, ay, az, bx, by, bz)) return true;
        }
        return false;
    }

    /** Tile::clip по форме блока: ближайшая из шести граней. */
    private boolean clipBoxHit(int xt, int yt, int zt, float ax, float ay, float az, float bx, float by, float bz) {
        float[] s = clipBox;
        ax -= xt; ay -= yt; az -= zt; bx -= xt; by -= yt; bz -= zt;
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float best = 1e30f;
        int bf = -1;
        float hx = 0, hy = 0, hz = 0;
        for (int f = 0; f < 6; f++) {
            int axis = f < 2 ? 0 : (f < 4 ? 1 : 2);
            float plane = s[(f & 1) == 0 ? axis : axis + 3];
            float d = axis == 0 ? dx : (axis == 1 ? dy : dz);
            if (d * d < 1e-7f) continue;
            float o = axis == 0 ? ax : (axis == 1 ? ay : az);
            float tt = (plane - o) / d;
            if (tt < 0 || tt > 1) continue;
            float px = ax + dx * tt, py = ay + dy * tt, pz = az + dz * tt;
            if (axis != 0 && (px < s[0] || px > s[3])) continue;
            if (axis != 1 && (py < s[1] || py > s[4])) continue;
            if (axis != 2 && (pz < s[2] || pz > s[5])) continue;
            float d2 = (px - ax) * (px - ax) + (py - ay) * (py - ay) + (pz - az) * (pz - az);
            if (d2 < best) {
                best = d2;
                // порядок граней Tile::clip: x0 4, x1 5, y0 0, y1 1, z0 2, z1 3
                bf = axis == 0 ? (4 + (f & 1)) : (axis == 1 ? (f & 1) : (2 + (f & 1)));
                hx = px; hy = py; hz = pz;
            }
        }
        if (bf < 0) return false;
        clipX = xt; clipY = yt; clipZ = zt; clipFace = bf;
        clipPX = hx + xt; clipPY = hy + yt; clipPZ = hz + zt;
        return true;
    }

    /** Level::tick: время, отложенные тики, случайные тики чанков вокруг игрока. */
    public void tick() {
        time++;
        if (isClientSide) {
            // MultiPlayerLevel::tick: только время
            if ((time & 15) == 0) updateSkyDarken();
            return;
        }
        if ((time & 15) == 0) updateSkyDarken();
        long a = Prof.t();
        tickPendingTicks();
        long b = Prof.t();
        tickTiles();
        if (Prof.on) {
            Prof.acc[Prof.T_PEND] += b - a;
            Prof.add(Prof.T_RAND, b);
        }
    }

    /** Level::tickTiles: ромб чанков радиусом 4 вокруг игрока, по 20 случайных позиций, тикают Tile::shouldTick. */
    private static final int[] POLL = { -1, -4, 0, -4, 1, -4, -2, -3, -1, -3, 0, -3, 1, -3, 2, -3, -3, -2,
            -2, -2, -1, -2, 0, -2, 1, -2, 2, -2, 3, -2, -4, -1, -3, -1, -2, -1,
            -1, -1, 0, -1, 1, -1, 2, -1, 3, -1, 4, -1, -4, 0, -3, 0, -2, 0, -1, 0,
            0, 0, 1, 0, 2, 0, 3, 0, 4, 0, -4, 1, -3, 1, -2, 1, -1, 1, 0, 1, 1, 1,
            2, 1, 3, 1, 4, 1, -3, 2, -2, 2, -1, 2, 0, 2, 1, 2, 2, 2, 3, 2, -2, 3,
            -1, 3, 0, 3, 1, 3, 2, 3, -1, 4, 0, 4, 1, 4 };
    private final boolean[] polled = new boolean[CHUNKS * CHUNKS];

    private void tickTiles() {
        if (player == null) return;
        for (int c = 0; c < polled.length; c++) polled[c] = false;
        // Level::tickTiles: ромб вокруг каждого игрока
        for (int i = -1; i < entities.size(); i++) {
            Entity pl = i < 0 ? (Entity) player : (Entity) entities.elementAt(i);
            if (i >= 0 && !(pl instanceof RemotePlayer)) continue;
            int xx = Mth.floor(pl.x / 16), zz = Mth.floor(pl.z / 16);
            for (int k = 0; k < POLL.length; k += 2) {
                int xp = xx + POLL[k], zp = zz + POLL[k + 1];
                if (xp >= 0 && xp < CHUNKS && zp >= 0 && zp < CHUNKS) polled[xp | (zp << 4)] = true;
            }
        }
        // std::set<ChunkPos>: по x, затем по z
        for (int cx = 0; cx < CHUNKS; cx++)
            for (int cz = 0; cz < CHUNKS; cz++) {
                int c = cx | (cz << 4);
                if (!polled[c]) continue;
                for (int k = 0; k < 20; k++) {
                    randValue = randValue * 3 + 1013904223;
                    int val = randValue >> 2;
                    int x = val & 15, z = (val >> 8) & 15, y = (val >> 16) & 127;
                    int id = blocks.get(c, (x << 11) | (z << 7) | y);
                    if (TileLogic.ticking[id]) TileLogic.tick(this, id, cx * 16 + x, y, cz * 16 + z, random);
                }
            }
    }

    // ---- столкновения ----

    /** Level::getCubes для одной клетки: рамки Tile::addAABBs; за краем мира — невидимая стена. */
    public void addCollisionBoxes(int x, int y, int z, float[] out, int[] count) {
        if ((x & ~255) != 0 || (z & ~255) != 0) {
            if (y >= 0 && y < HEIGHT) addBox(out, count, x, y, z, x + 1, y + 1, z + 1);
            return;
        }
        TileLogic.addAABBs(this, x, y, z, out, count);
    }

    private static void addBox(float[] out, int[] count, float x0, float y0, float z0, float x1, float y1, float z1) {
        int n = count[0];
        if (n * 6 + 6 > out.length) return;
        out[n * 6] = x0; out[n * 6 + 1] = y0; out[n * 6 + 2] = z0;
        out[n * 6 + 3] = x1; out[n * 6 + 4] = y1; out[n * 6 + 5] = z1;
        count[0] = n + 1;
    }
}
