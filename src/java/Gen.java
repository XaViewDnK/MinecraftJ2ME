/**
 * Генератор мира — порт RandomLevelSource релиза 0.6.1 (мир 16x16 чанков).
 * Совпадает с оригиналом: генератор случайных чисел (Mersenne Twister, 32-битные long ARM), шумы ImprovedNoise/PerlinNoise
 * вместе с их особенностями, BiomeSource, prepareHeights/buildSurfaces, порядок postProcess как в ChunkCache
 * при Minecraft::prepareLevel, все фичи postProcess, мгновенное течение источников (instaTick), снег по температуре,
 * животные при генерации (выживание) и Level::setInitialSpawn. Пещер в 0.6.1 нет (вызов caveFeature вырезан).
 * Упрощено: свет при генерации (для цветов и грибов) считается приближённо, предметы от смытых растений не выпадают.
 */
public final class Gen {
    public interface Progress {
        void progress(String stage, int percent);
    }

    // ================= генератор случайных чисел оригинала =================

    /** util/Random.h: Mersenne Twister mt19937, long = 32 бита. */
    static final class Rnd {
        private final int[] mt = new int[624];
        private int mti = 625;

        Rnd(int seed) {
            setSeed(seed);
        }

        void setSeed(int s) {
            mt[0] = s;
            for (int i = 1; i < 624; i++) mt[i] = 1812433253 * (mt[i - 1] ^ (mt[i - 1] >>> 30)) + i;
            mti = 624;
        }

        int next() {
            if (mti >= 624) {
                int kk, y;
                for (kk = 0; kk < 624 - 397; kk++) {
                    y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                    mt[kk] = mt[kk + 397] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
                }
                for (; kk < 623; kk++) {
                    y = (mt[kk] & 0x80000000) | (mt[kk + 1] & 0x7fffffff);
                    mt[kk] = mt[kk + (397 - 624)] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
                }
                y = (mt[623] & 0x80000000) | (mt[0] & 0x7fffffff);
                mt[623] = mt[396] ^ (y >>> 1) ^ ((y & 1) != 0 ? 0x9908b0df : 0);
                mti = 0;
            }
            int y = mt[mti++];
            y ^= y >>> 11;
            y ^= (y << 7) & 0x9d2c5680;
            y ^= (y << 15) & 0xefc60000;
            y ^= y >>> 18;
            return y;
        }

        int nextInt() {
            return next() >>> 1;
        }

        int nextInt(int n) {
            return (int) ((next() & 0xFFFFFFFFL) % n);
        }

        float nextFloat() {
            return (float) ((next() & 0xFFFFFFFFL) * (1.0 / 4294967296.0));
        }
    }

    // ================= шумы =================

    static final class INoise {
        final int[] p = new int[512];
        final float xo, yo, zo;

        INoise(Rnd r) {
            xo = r.nextFloat() * 256f;
            yo = r.nextFloat() * 256f;
            zo = r.nextFloat() * 256f;
            for (int i = 0; i < 256; i++) p[i] = i;
            for (int i = 0; i < 256; i++) {
                int j = r.nextInt(256 - i) + i;
                int t = p[i]; p[i] = p[j]; p[j] = t;
                p[i + 256] = p[i];
            }
        }

        static float lerp(float t, float a, float b) {
            return a + t * (b - a);
        }

        static float grad(int hash, float x, float y, float z) {
            int h = hash & 15;
            float u = h < 8 ? x : y, v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }

        static float grad2(int hash, float x, float z) {
            int h = hash & 15;
            float u = (1 - ((h & 8) >> 3)) * x, v = h < 4 ? 0 : (h == 12 || h == 14 ? x : z);
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }

        float noise(float x0, float y0, float z0) {
            float x = x0 + xo, y = y0 + yo, z = z0 + zo;
            int xf = (int) x, yf = (int) y, zf = (int) z;
            if (x < xf) xf--;
            if (y < yf) yf--;
            if (z < zf) zf--;
            int X = xf & 255, Y = yf & 255, Z = zf & 255;
            x -= xf; y -= yf; z -= zf;
            float u = x * x * x * (x * (x * 6 - 15) + 10), v = y * y * y * (y * (y * 6 - 15) + 10), w = z * z * z * (z * (z * 6 - 15) + 10);
            int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z, B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
            return lerp(w, lerp(v, lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z)),
                            lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z))),
                    lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)),
                            lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))));
        }

        void add(float[] buf, float _x, float _y, float _z, int xSize, int ySize, int zSize, float xs, float ys, float zs, float pow) {
            int[] p = this.p;
            if (ySize == 1) {
                int pp = 0;
                float scale = 1.0f / pow;
                for (int xx = 0; xx < xSize; xx++) {
                    float x = (_x + xx) * xs + xo;
                    int xf = (int) x;
                    if (x < xf) xf--;
                    int X = xf & 255;
                    x -= xf;
                    float u = x * x * x * (x * (x * 6 - 15) + 10);
                    for (int zz = 0; zz < zSize; zz++) {
                        float z = (_z + zz) * zs + zo;
                        int zf = (int) z;
                        if (z < zf) zf--;
                        int Z = zf & 255;
                        z -= zf;
                        float w = z * z * z * (z * (z * 6 - 15) + 10);
                        int A = p[X], AA = p[A] + Z, B = p[X + 1], BA = p[B] + Z;
                        float vv0 = lerp(u, grad2(p[AA], x, z), grad(p[BA], x - 1, 0, z));
                        float vv2 = lerp(u, grad(p[AA + 1], x, 0, z - 1), grad(p[BA + 1], x - 1, 0, z - 1));
                        buf[pp++] += lerp(w, vv0, vv2) * scale;
                    }
                }
                return;
            }
            int pp = 0;
            float scale = 1 / pow;
            int yOld = -1;
            float vv0 = 0, vv1 = 0, vv2 = 0, vv3 = 0;
            for (int xx = 0; xx < xSize; xx++) {
                float x = (_x + xx) * xs + xo;
                int xf = (int) x;
                if (x < xf) xf--;
                int X = xf & 255;
                x -= xf;
                float u = x * x * x * (x * (x * 6 - 15) + 10);
                for (int zz = 0; zz < zSize; zz++) {
                    float z = (_z + zz) * zs + zo;
                    int zf = (int) z;
                    if (z < zf) zf--;
                    int Z = zf & 255;
                    z -= zf;
                    float w = z * z * z * (z * (z * 6 - 15) + 10);
                    for (int yy = 0; yy < ySize; yy++) {
                        float y = (_y + yy) * ys + yo;
                        int yf = (int) y;
                        if (y < yf) yf--;
                        int Y = yf & 255;
                        y -= yf;
                        float v = y * y * y * (y * (y * 6 - 15) + 10);
                        if (yy == 0 || Y != yOld) {
                            yOld = Y;
                            int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z, B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
                            vv0 = lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z));
                            vv1 = lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z));
                            vv2 = lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1));
                            vv3 = lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1));
                        }
                        float v0 = lerp(v, vv0, vv1), v1 = lerp(v, vv2, vv3);
                        buf[pp++] += lerp(w, v0, v1) * scale;
                    }
                }
            }
        }
    }

    static final class PNoise {
        final INoise[] n;

        PNoise(Rnd r, int levels) {
            n = new INoise[levels];
            for (int i = 0; i < levels; i++) n[i] = new INoise(r);
        }

        float getValue(float x, float y) {
            float value = 0, pow = 1;
            for (int i = 0; i < n.length; i++) {
                value += n[i].noise(x * pow, y * pow, 0) / pow;
                pow /= 2;
            }
            return value;
        }

        float[] region(float[] buf, float x, float y, float z, int xs, int ys, int zs, float xScale, float yScale, float zScale) {
            int size = xs * ys * zs;
            if (buf == null || buf.length < size) buf = new float[size];
            for (int i = 0; i < size; i++) buf[i] = 0;
            float pow = 1;
            for (int i = 0; i < n.length; i++) {
                n[i].add(buf, x, y, z, xs, ys, zs, xScale * pow, yScale * pow, zScale * pow, pow);
                pow /= 2;
            }
            return buf;
        }

        float[] region2(float[] buf, int x, int z, int xs, int zs, float xScale, float zScale) {
            return region(buf, x, 10.0f, z, xs, 1, zs, xScale, 1, zScale);
        }
    }

    // Mth::sin/cos 0.6.1: таблица из 65536 float, _sin[i] = sin(i / _sinScale) — та же, что у Mth
    private static final float SIN_SCALE = 65536.0f / (2.0f * Mth.PI);

    static float msin(float x) {
        return Mth.sinI((int) (x * SIN_SCALE) & 65535);
    }

    static float mcos(float x) {
        return Mth.sinI((int) (x * SIN_SCALE + 65536 / 4) & 65535);
    }

    // ================= биомы =================

    public static final int B_RAINFOREST = 0, B_SWAMP = 1, B_SEASONAL = 2, B_FOREST = 3, B_SAVANNA = 4, B_SHRUB = 5,
            B_TAIGA = 6, B_DESERT = 7, B_PLAINS = 8, B_ICE_DESERT = 9, B_TUNDRA = 10;
    private static final byte[] BIOME_MAP = new byte[64 * 64];

    static {
        for (int a = 0; a < 64; a++)
            for (int b = 0; b < 64; b++) BIOME_MAP[a + b * 64] = (byte) biomeFor(a / 63.0f, b / 63.0f);
    }

    private static int biomeFor(float temperature, float downfall) {
        downfall *= temperature;
        if (temperature < 0.10f) return B_TUNDRA;
        if (downfall < 0.20f) return temperature < 0.50f ? B_TUNDRA : (temperature < 0.95f ? B_SAVANNA : B_DESERT);
        if (downfall > 0.5f && temperature < 0.7f) return B_SWAMP;
        if (temperature < 0.50f) return B_TAIGA;
        if (temperature < 0.97f) return downfall < 0.35f ? B_SHRUB : B_FOREST;
        return downfall < 0.45f ? B_PLAINS : (downfall < 0.90f ? B_SEASONAL : B_RAINFOREST);
    }

    /** BiomeSource 0.6.1 (PerlinSimplexNoise = PerlinNoise). */
    static final class BiomeSource {
        final PNoise temperatureMap, downfallMap, noiseMap;
        float[] temperatures = new float[256], downfalls, noises;
        final int[] biomes = new int[256];
        static final float TEMP_SCALE = 2f / 80.0f, DOWN_SCALE = 2f / 40.0f, NOISE_SCALE = 1 / 4.0f;

        BiomeSource(int seed) {
            temperatureMap = new PNoise(new Rnd(seed * 9871), 4);
            downfallMap = new PNoise(new Rnd(seed * 39811), 4);
            noiseMap = new PNoise(new Rnd(seed * 543321), 2);
        }

        int getBiome(int x, int z) {
            getBiomeBlock(x, z, 1, 1);
            return biomes[0];
        }

        void getBiomeBlock(int x, int z, int w, int h) {
            temperatures = temperatureMap.region2(temperatures, x, z, w, w, TEMP_SCALE, TEMP_SCALE);
            downfalls = downfallMap.region2(downfalls, x, z, w, w, DOWN_SCALE, DOWN_SCALE);
            noises = noiseMap.region2(noises, x, z, w, w, NOISE_SCALE, NOISE_SCALE);
            int pp = 0;
            for (int yy = 0; yy < w; yy++)
                for (int xx = 0; xx < h; xx++) {
                    float noise = noises[pp] * 1.1f + 0.5f;
                    float split2 = 0.01f, split1 = 1 - split2;
                    float temperature = (temperatures[pp] * 0.15f + 0.7f) * split1 + noise * split2;
                    split2 = 0.002f;
                    split1 = 1 - split2;
                    float downfall = (downfalls[pp] * 0.15f + 0.5f) * split1 + noise * split2;
                    temperature = 1 - ((1 - temperature) * (1 - temperature));
                    if (temperature < 0) temperature = 0;
                    if (downfall < 0) downfall = 0;
                    if (temperature > 1) temperature = 1;
                    if (downfall > 1) downfall = 1;
                    temperatures[pp] = temperature;
                    downfalls[pp] = downfall;
                    biomes[pp++] = BIOME_MAP[(int) (temperature * 63) + (int) (downfall * 63) * 64];
                }
        }

        float[] getTemperatureBlock(int x, int z, int w, int h) {
            temperatures = temperatureMap.region2(temperatures, x, z, w, h, TEMP_SCALE, TEMP_SCALE);
            noises = noiseMap.region2(noises, x, z, w, h, NOISE_SCALE, NOISE_SCALE);
            int pp = 0;
            for (int yy = 0; yy < w; yy++)
                for (int xx = 0; xx < h; xx++) {
                    float noise = noises[pp] * 1.1f + 0.5f;
                    float split2 = 0.01f, split1 = 1 - split2;
                    float temperature = (temperatures[pp] * 0.15f + 0.7f) * split1 + noise * split2;
                    temperature = 1 - ((1 - temperature) * (1 - temperature));
                    if (temperature < 0) temperature = 0;
                    if (temperature > 1) temperature = 1;
                    temperatures[pp] = temperature;
                    pp++;
                }
            return temperatures;
        }
    }

    private static BiomeSource cachedBiomes;
    private static int cachedSeed;

    /** Биом в точке мира (для спавна мобов). */
    public static synchronized int biomeAt(long seed, int x, int z) {
        if (cachedBiomes == null || cachedSeed != (int) seed) {
            cachedBiomes = new BiomeSource((int) seed);
            cachedSeed = (int) seed;
        }
        return cachedBiomes.getBiome(x, z);
    }

    // ================= свойства блоков 0.6.1 для генерации =================

    static final int ROCK = Tile.STONE;
    /** Tile::solid (isSolidRender при init — листва тоже) */
    static final boolean[] SOLID = new boolean[256];
    /** Tile::lightBlock: непрозрачные 255, листва 1, вода/лёд 3, лава 255 */
    static final int[] LIGHT_BLOCK = new int[256];

    static {
        int[] solid = { 1, 2, 3, 4, 5, 7, 12, 13, 14, 15, 16, 17, 18, 19, 21, 22, 24, 35, 41, 42, 43, 45, 46, 47, 48, 49, 56, 57,
                58, 61, 62, 73, 74, 80, 82, 87, 89, 95, 98, 103, 112, 155, 245, 246, 247, 248, 249 };
        for (int i = 0; i < solid.length; i++) {
            SOLID[solid[i]] = true;
            LIGHT_BLOCK[solid[i]] = 255;
        }
        LIGHT_BLOCK[18] = 1;
        LIGHT_BLOCK[8] = LIGHT_BLOCK[9] = 3;
        LIGHT_BLOCK[10] = LIGHT_BLOCK[11] = 255;
        LIGHT_BLOCK[79] = 3;
        LIGHT_BLOCK[30] = 1;
    }

    static boolean isWater(int t) {
        return t == 8 || t == 9;
    }

    static boolean isLava(int t) {
        return t == 10 || t == 11;
    }

    /** Material::isSolid — всё, кроме воздуха, жидкостей и «украшений» (растения, факел, слой снега). */
    static boolean matSolid(int t) {
        if (t == 0 || isWater(t) || isLava(t)) return false;
        switch (t) {
            case 6: case 31: case 37: case 38: case 39: case 40: case 50: case 59: case 78: case 83: case 105: case 30:
                return false;
        }
        return true;
    }

    /** Material::blocksMotion (паутина — нет) */
    static boolean blocksMotion(int t) {
        return matSolid(t);
    }

    // ================= состояние =================

    private final Level l;
    private final int seed;
    private final boolean live;
    final Rnd random;
    final Rnd levelRandom = new Rnd(1);
    private BiomeSource bs;
    private PNoise lperlin1, lperlin2, perlin1, perlin2, perlin3, scaleNoise, depthNoise, forestNoise;
    private float[] buffer, pnr, ar, br, sr, dr;
    private final float[] sandBuf = new float[256], gravelBuf = new float[256], depthBuf = new float[256];
    private final short[] hm;
    private final short[] freshStamp;
    private int step = 1;
    private boolean instaTick, noNeighborUpdate;
    private boolean spawnMobs;
    /** отложенные тики (гравий руд, «разбуженная» вода) — в оригинале срабатывают в первые тики игры */
    private int[][] heavy = new int[16][];
    /** страница очереди — 4096 позиций; страницы не копируются при росте (раньше int[] удваивался: 1 МБ -> 2 МБ с копией) */
    private static final int HEAVY_SHIFT = 12;
    private byte[] chunkBuf = new byte[32768];
    private int heavyN;

    public Gen(Level l, long seed) {
        this.l = l;
        this.seed = (int) seed;
        live = false;
        random = new Rnd(this.seed);
        lperlin1 = new PNoise(random, 16);
        lperlin2 = new PNoise(random, 16);
        perlin1 = new PNoise(random, 8);
        perlin2 = new PNoise(random, 4);
        perlin3 = new PNoise(random, 4);
        scaleNoise = new PNoise(random, 10);
        depthNoise = new PNoise(random, 16);
        forestNoise = new PNoise(random, 8);
        bs = new BiomeSource(this.seed);
        hm = new short[256 * 256];
        freshStamp = new short[256 * 256];
        spawnMobs = l.gameType == 0;
    }

    /** Для роста саженцев в живом мире: блоки ставятся с обновлениями. */
    private Gen(Level l, int rseed) {
        this.l = l;
        this.seed = 0;
        live = true;
        random = new Rnd(rseed);
        hm = null;
        freshStamp = null;
    }

    // ================= доступ к миру =================

    int getTile(int x, int y, int z) {
        if (y < 0 || y >= 128) return 0;
        if ((x & ~255) != 0 || (z & ~255) != 0) return Tile.INVISIBLE_BEDROCK;
        return l.blocks.get((x >> 4) | ((z >> 4) << 4), ((x & 15) << 11) | ((z & 15) << 7) | y);
    }

    int getData(int x, int y, int z) {
        if (y < 0 || y >= 128 || (x & ~255) != 0 || (z & ~255) != 0) return 0;
        return l.getData(x, y, z);
    }

    boolean isEmpty(int x, int y, int z) {
        return getTile(x, y, z) == 0;
    }

    int getHeightmap(int x, int z) {
        if ((x & ~255) != 0 || (z & ~255) != 0) return 0;
        return hm[x | (z << 8)];
    }

    private void recalcHeight(int x, int yStart, int z) {
        int i = x | (z << 8);
        int yOld = hm[i], y = yOld;
        if (yStart > yOld) y = yStart;
        Pal b = l.blocks;
        int c = (x >> 4) | ((z >> 4) << 4), p = ((x & 15) << 11) | ((z & 15) << 7);
        while (y > 0 && LIGHT_BLOCK[b.get(c, p + y - 1)] == 0) y--;
        if (y == yOld) return;
        hm[i] = (short) y;
        freshStamp[i] = (short) step;
    }

    /** LevelChunk::setTile / setTileAndData (+ onPlace), без обновления соседей. */
    boolean setNoUpdate(int x, int y, int z, int id, int data, boolean withData) {
        if (y < 0 || y >= 128) return false;
        if ((x & ~255) != 0 || (z & ~255) != 0) return true;
        if (live) {
            if (!withData && l.getTile(x, y, z) == id) return false;
            return l.setTileAndData(x, y, z, id, data) || true;
        }
        int c = (x >> 4) | ((z >> 4) << 4), i = ((x & 15) << 11) | ((z & 15) << 7) | y;
        int old = l.blocks.get(c, i);
        if (withData) {
            if (old == id && l.data.get(c, i) == data) return false;
        } else if (old == id) return false;
        l.blocks.set(c, i, id);
        l.data.set(c, i, withData ? data : 0);
        int col = x | (z << 8);
        if (LIGHT_BLOCK[id] != 0) {
            if (y >= hm[col]) recalcHeight(x, y + 1, z);
        } else if (y == hm[col] - 1) recalcHeight(x, y, z);
        if (id != 0) onPlace(x, y, z, id);
        return true;
    }

    /** Level::setTile/setTileAndData — с обновлением соседей. */
    boolean set(int x, int y, int z, int id, int data, boolean withData) {
        if (setNoUpdate(x, y, z, id, data, withData)) {
            if (!live) updateNeighborsAt(x, y, z);
            return true;
        }
        return false;
    }

    void setData(int x, int y, int z, int d) {
        if (y < 0 || y >= 128 || (x & ~255) != 0 || (z & ~255) != 0) return;
        if (l.getData(x, y, z) != d) {
            int c = (x >> 4) | ((z >> 4) << 4), i = ((x & 15) << 11) | ((z & 15) << 7) | y;
            l.data.set(c, i, d);
            updateNeighborsAt(x, y, z);
        }
    }

    private void updateNeighborsAt(int x, int y, int z) {
        neighborChanged(x - 1, y, z);
        neighborChanged(x + 1, y, z);
        neighborChanged(x, y - 1, z);
        neighborChanged(x, y + 1, z);
        neighborChanged(x, y, z - 1);
        neighborChanged(x, y, z + 1);
    }

    private void neighborChanged(int x, int y, int z) {
        if (noNeighborUpdate) return;
        int t = getTile(x, y, z);
        switch (t) {
            case 8: case 10:
                updateLiquid(x, y, z, t);
                break;
            case 9: case 11:
                updateLiquid(x, y, z, t);
                if (getTile(x, y, z) == t) {
                    // LiquidTileStatic::setDynamic
                    int d = getData(x, y, z);
                    noNeighborUpdate = true;
                    setNoUpdate(x, y, z, t - 1, d, true);
                    addTick(x, y, z, t - 1);
                    noNeighborUpdate = false;
                }
                break;
            case 12: case 13:
                addTick(x, y, z, t);
                break;
            case 6: case 37: case 38: case 39: case 40:
                if (!canSurvive(t, x, y, z)) set(x, y, z, 0, 0, false);
                break;
            case 83:
                if (!reedsMayPlace(x, y, z)) set(x, y, z, 0, 0, false);
                break;
            case 81:
                if (!cactusCanSurvive(x, y, z)) set(x, y, z, 0, 0, false);
                break;
            case 78: {
                int b = getTile(x, y - 1, z);
                if (b == 0 || !SOLID[b] || b == Tile.LEAVES || !blocksMotion(b)) set(x, y, z, 0, 0, false);
                break;
            }
        }
    }

    private void onPlace(int x, int y, int z, int id) {
        switch (id) {
            case 8: case 10:
                updateLiquid(x, y, z, id);
                if (getTile(x, y, z) == id) addTick(x, y, z, id);
                break;
            case 9: case 11:
                updateLiquid(x, y, z, id);
                break;
            case 12: case 13:
                addTick(x, y, z, id);
                break;
        }
    }

    /** Level::addToTickNextTick: при instaTick — тик сразу, иначе (для гравия) — в конце генерации. */
    private void addTick(int x, int y, int z, int id) {
        if (live) return;
        if (instaTick) {
            if (getTile(x, y, z) == id && id > 0) tick(x, y, z, id, levelRandom);
            return;
        }
        if (id == 12 || id == 13 || id == 8 || id == 10) {
            int pg = heavyN >> HEAVY_SHIFT;
            if (pg == heavy.length) {
                int[][] n = new int[pg * 2][];
                System.arraycopy(heavy, 0, n, 0, pg);
                heavy = n;
            }
            if (heavy[pg] == null) heavy[pg] = new int[1 << HEAVY_SHIFT];
            // x, y, z по 10 бит со знаком в одном int (координаты мира 0..255, y 0..127)
            heavy[pg][heavyN++ & ((1 << HEAVY_SHIFT) - 1)] = ((x & 1023) << 20) | ((y & 1023) << 10) | (z & 1023);
        }
    }

    private void tick(int x, int y, int z, int id, Rnd r) {
        if (id == 8 || id == 10) liquidTick(x, y, z, id, r);
        else if (id == 12 || id == 13) checkSlide(x, y, z, id);
    }

    // ---- HeavyTile ----

    private boolean heavyFree(int x, int y, int z) {
        int t = getTile(x, y, z);
        return t == 0 || isWater(t) || isLava(t);
    }

    private void checkSlide(int x, int y, int z, int id) {
        if (heavyFree(x, y - 1, z) && y >= 0) {
            set(x, y, z, 0, 0, false);
            while (heavyFree(x, y - 1, z) && y > 0) y--;
            if (y > 0) set(x, y, z, id, 0, false);
        }
    }

    // ---- жидкости (LiquidTile / LiquidTileDynamic 0.6.1) ----

    // поля объекта тайла общие на все рекурсивные тики — как в оригинале
    private final int[] maxCount = new int[2];
    private final boolean[][] spreadRes = new boolean[2][4];
    private final int[][] spreadDist = new int[2][4];

    private static boolean sameMat(int t, int id) {
        return id <= 9 ? isWater(t) : isLava(t);
    }

    private int getDepth(int x, int y, int z, int id) {
        if (!sameMat(getTile(x, y, z), id)) return -1;
        return getData(x, y, z);
    }

    private void updateLiquid(int x, int y, int z, int id) {
        if (getTile(x, y, z) != id) return;
        if (isLava(id)) {
            if (isWater(getTile(x, y, z - 1)) || isWater(getTile(x, y, z + 1)) || isWater(getTile(x - 1, y, z))
                    || isWater(getTile(x + 1, y, z)) || isWater(getTile(x, y + 1, z))) {
                int data = getData(x, y, z);
                if (data == 0) set(x, y, z, Tile.OBSIDIAN, 0, false);
                else if (data <= 4) set(x, y, z, Tile.COBBLE, 0, false);
            }
        }
    }

    private int getHighest(int x, int y, int z, int current, int id) {
        int d = getDepth(x, y, z, id);
        if (d < 0) return current;
        if (d == 0) maxCount[id == 8 ? 0 : 1]++;
        if (d >= 8) d = 0;
        return current < 0 || d < current ? d : current;
    }

    private boolean isWaterBlocking(int x, int y, int z) {
        int t = getTile(x, y, z);
        if (t == 0) return false;
        if (t == Tile.WOOD_DOOR || t == Tile.SIGN || t == Tile.LADDER || t == Tile.REEDS) return true;
        return blocksMotion(t) || matSolid(t);
    }

    private boolean canSpreadTo(int x, int y, int z, int id) {
        int t = getTile(x, y, z);
        if (sameMat(t, id)) return false;
        if (isLava(t)) return false;
        return !isWaterBlocking(x, y, z);
    }

    private boolean isSolidBlocking(int x, int y, int z) {
        int t = getTile(x, y, z);
        // Material::isSolidBlocking (не neverBuildable) && куб
        return SOLID[t] && t != Tile.LEAVES && t != Tile.GLASS && t != Tile.ICE && t != Tile.TNT;
    }

    private void liquidTick(int x, int y, int z, int id, Rnd random) {
        int m = id == 8 ? 0 : 1;
        int depth = getDepth(x, y, z, id);
        int dropOff = isLava(id) ? 2 : 1;
        boolean becomeStatic = true;
        if (depth > 0) {
            int highest = -100;
            maxCount[m] = 0;
            highest = getHighest(x - 1, y, z, highest, id);
            highest = getHighest(x + 1, y, z, highest, id);
            highest = getHighest(x, y, z - 1, highest, id);
            highest = getHighest(x, y, z + 1, highest, id);
            int newDepth = highest + dropOff;
            if (newDepth >= 8 || highest < 0) newDepth = -1;
            if (getDepth(x, y + 1, z, id) >= 0) {
                int above = getDepth(x, y + 1, z, id);
                newDepth = above >= 8 ? above : above + 8;
            }
            if (maxCount[m] >= 2 && id == 8) {
                if (isSolidBlocking(x, y - 1, z)) newDepth = 0;
                else if (isWater(getTile(x, y - 1, z)) && getData(x, y, z) == 0) newDepth = 0;
            }
            if (isLava(id) && depth < 8 && newDepth < 8 && newDepth > depth) {
                if (random.nextInt(4) != 0) {
                    newDepth = depth;
                    becomeStatic = false;
                }
            }
            if (newDepth != depth) {
                depth = newDepth;
                if (depth < 0) set(x, y, z, 0, 0, false);
                else {
                    setData(x, y, z, depth);
                    addTick(x, y, z, id);
                    updateNeighborsAt(x, y, z);
                }
            } else if (becomeStatic) setNoUpdate(x, y, z, id + 1, getData(x, y, z), true);
        } else setNoUpdate(x, y, z, id + 1, getData(x, y, z), true);
        if (canSpreadTo(x, y - 1, z, id)) {
            set(x, y - 1, z, id, depth >= 8 ? depth : depth + 8, true);
        } else if (depth >= 0 && (depth == 0 || isWaterBlocking(x, y - 1, z))) {
            boolean[] spreads = getSpread(x, y, z, id);
            int neighbor = depth + dropOff;
            if (depth >= 8) neighbor = 1;
            if (neighbor >= 8) return;
            if (spreads[0]) trySpreadTo(x - 1, y, z, neighbor, id);
            if (spreads[1]) trySpreadTo(x + 1, y, z, neighbor, id);
            if (spreads[2]) trySpreadTo(x, y, z - 1, neighbor, id);
            if (spreads[3]) trySpreadTo(x, y, z + 1, neighbor, id);
        }
    }

    private void trySpreadTo(int x, int y, int z, int neighbor, int id) {
        if (canSpreadTo(x, y, z, id)) set(x, y, z, id, neighbor, true);
    }

    private boolean[] getSpread(int x, int y, int z, int id) {
        int m = id == 8 ? 0 : 1;
        int[] dist = spreadDist[m];
        boolean[] result = spreadRes[m];
        for (int d = 0; d < 4; d++) {
            dist[d] = 1000;
            int xx = x, zz = z;
            if (d == 0) xx--;
            if (d == 1) xx++;
            if (d == 2) zz--;
            if (d == 3) zz++;
            if (isWaterBlocking(xx, y, zz)) continue;
            if (sameMat(getTile(xx, y, zz), id) && getData(xx, y, zz) == 0) continue;
            if (!isWaterBlocking(xx, y - 1, zz)) dist[d] = 0;
            else dist[d] = slopeDistance(xx, y, zz, 1, d, id);
        }
        int lowest = dist[0];
        for (int d = 1; d < 4; d++) if (dist[d] < lowest) lowest = dist[d];
        for (int d = 0; d < 4; d++) result[d] = dist[d] == lowest;
        return result;
    }

    private int slopeDistance(int x, int y, int z, int pass, int from, int id) {
        int lowest = 1000;
        for (int d = 0; d < 4; d++) {
            if ((d == 0 && from == 1) || (d == 1 && from == 0) || (d == 2 && from == 3) || (d == 3 && from == 2)) continue;
            int xx = x, zz = z;
            if (d == 0) xx--;
            if (d == 1) xx++;
            if (d == 2) zz--;
            if (d == 3) zz++;
            if (isWaterBlocking(xx, y, zz)) continue;
            if (sameMat(getTile(xx, y, zz), id) && getData(xx, y, zz) == 0) continue;
            if (!isWaterBlocking(xx, y - 1, zz)) return pass;
            if (pass < 4) {
                int v = slopeDistance(xx, y, zz, pass + 1, d, id);
                if (v < lowest) lowest = v;
            }
        }
        return lowest;
    }

    // ---- свет при генерации (приближённо) ----

    private boolean canSeeSky(int x, int y, int z) {
        if ((x & ~255) != 0 || (z & ~255) != 0) return false;
        return y >= hm[x | (z << 8)];
    }

    /** Небесный свет: свежие столбцы — как LevelChunk::recalcHeight, остальные — как после распространения света. */
    private int brightness(int x, int y, int z) {
        if ((x & ~255) != 0 || (z & ~255) != 0) return 7;
        if (y < 0) return 0;
        if (y >= 128) return 15;
        int col = x | (z << 8);
        int h = hm[col];
        if (y >= h) return 15;
        if (freshStamp[col] == step) {
            int br = 15;
            for (int yy = h - 1; yy >= y; yy--) {
                int b = LIGHT_BLOCK[getTile(x, yy, z)];
                br -= b == 0 ? 1 : b;
                if (br <= 0) return 0;
            }
            return br;
        }
        int br = 15;
        for (int yy = h - 1; yy >= y && br > 0; yy--) br -= LIGHT_BLOCK[getTile(x, yy, z)];
        if (br < 0) br = 0;
        for (int d = 1; d <= 3 && 15 - d > br; d++)
            for (int dx = -d; dx <= d; dx++) {
                int dz = d - Math.abs(dx);
                if (canSeeSky(x + dx, y, z + dz) || canSeeSky(x + dx, y, z - dz)) return 15 - d;
            }
        return br;
    }

    boolean canSurvive(int id, int x, int y, int z) {
        int below = getTile(x, y - 1, z);
        if (id == Tile.BROWN_MUSHROOM || id == Tile.RED_MUSHROOM) {
            if (y < 0 || y >= 128) return false;
            return brightness(x, y, z) < 13 && SOLID[below];
        }
        return (brightness(x, y, z) >= 8 || canSeeSky(x, y, z)) && (below == Tile.GRASS || below == Tile.DIRT || below == Tile.FARMLAND);
    }

    private boolean reedsMayPlace(int x, int y, int z) {
        int below = getTile(x, y - 1, z);
        if (below == Tile.REEDS) return true;
        if (below != Tile.GRASS && below != Tile.DIRT && below != Tile.SAND) return false;
        return isWater(getTile(x - 1, y - 1, z)) || isWater(getTile(x + 1, y - 1, z)) || isWater(getTile(x, y - 1, z - 1)) || isWater(getTile(x, y - 1, z + 1));
    }

    private boolean cactusCanSurvive(int x, int y, int z) {
        if (matSolid(getTile(x - 1, y, z)) || matSolid(getTile(x + 1, y, z)) || matSolid(getTile(x, y, z - 1)) || matSolid(getTile(x, y, z + 1))) return false;
        int below = getTile(x, y - 1, z);
        return below == Tile.CACTUS || below == Tile.SAND;
    }

    // ================= генерация =================

    public void generate(Progress pr) {
        if (pr != null) pr.progress("Generating level", 0);
        // Minecraft::prepareLevel: чанки по x, внутри по z; после создания — postProcess по правилам ChunkCache
        boolean[] created = new boolean[256], populated = new boolean[256];
        int done = 0;
        for (int X = 0; X < 16; X++) {
            for (int Z = 0; Z < 16; Z++) {
                step++;
                createChunk(X, Z);
                created[X | (Z << 4)] = true;
                if (!populated[X | (Z << 4)] && has(created, X + 1, Z + 1) && has(created, X, Z + 1) && has(created, X + 1, Z))
                    post(created, populated, X, Z);
                if (has(created, X - 1, Z) && !pop(populated, X - 1, Z) && has(created, X - 1, Z + 1) && has(created, X, Z + 1))
                    post(created, populated, X - 1, Z);
                if (has(created, X, Z - 1) && !pop(populated, X, Z - 1) && has(created, X + 1, Z - 1) && has(created, X + 1, Z))
                    post(created, populated, X, Z - 1);
                if (has(created, X - 1, Z - 1) && !pop(populated, X - 1, Z - 1) && has(created, X, Z - 1) && has(created, X - 1, Z))
                    post(created, populated, X - 1, Z - 1);
                done++;
                if (pr != null && (done & 7) == 0) pr.progress("Generating level", done * 90 / 256);
            }
        }
        // отложенные тики гравия руд (в оригинале — в первые тики игры)
        step++;
        int n = heavyN;
        instaTick = true;
        for (int i = 0; i < n; i++) {
            int v = heavy[i >> HEAVY_SHIFT][i & ((1 << HEAVY_SHIFT) - 1)];
            int x = v << 2 >> 22, y = v << 12 >> 22, z = v << 22 >> 22, t = getTile(x, y, z);
            if (t == 12 || t == 13) checkSlide(x, y, z, t);
            else if (t == 8 || t == 10) liquidTick(x, y, z, t, levelRandom);
        }
        instaTick = false;
        heavy = null;
        chunkBuf = null;
        if (pr != null) pr.progress("Building terrain", 92);
        l.recalcAllLight();
        setInitialSpawn();
        if (pr != null) pr.progress("Building terrain", 100);
    }

    private static boolean fits(int x, int z) {
        return x >= 0 && z >= 0 && x < 16 && z < 16;
    }

    private static boolean has(boolean[] created, int x, int z) {
        return !fits(x, z) || created[x | (z << 4)];
    }

    private static boolean pop(boolean[] populated, int x, int z) {
        return fits(x, z) && populated[x | (z << 4)];
    }

    private void post(boolean[] created, boolean[] populated, int x, int z) {
        if (!fits(x, z)) return;
        if (populated[x | (z << 4)]) return;
        populated[x | (z << 4)] = true;
        postProcess(x, z);
    }

    // ---- RandomLevelSource::getChunk ----

    private void createChunk(int xOffs, int zOffs) {
        random.setSeed(xOffs * 341872712 + zOffs * 132899541);
        // чанк собирается в сыром буфере и потом сжимается в мир
        byte[] blocks = chunkBuf;
        for (int i = 0; i < blocks.length; i++) blocks[i] = 0;
        bs.getBiomeBlock(xOffs * 16, zOffs * 16, 16, 16);
        prepareHeights(xOffs, zOffs, blocks, bs.temperatures);
        buildSurfaces(xOffs, zOffs, blocks, bs.biomes);
        // recalcHeightmap
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int y = 127, p = x << 11 | z << 7;
                while (y > 0 && LIGHT_BLOCK[blocks[p + y - 1] & 255] == 0) y--;
                hm[(xOffs * 16 + x) | ((zOffs * 16 + z) << 8)] = (short) y;
            }
        l.blocks.importChunk(xOffs | (zOffs << 4), blocks, false);
    }

    private void prepareHeights(int xOffs, int zOffs, byte[] blocks, float[] temperatures) {
        int xChunks = 4, waterHeight = 64;
        int xSize = 5, ySize = 17, zSize = 5;
        buffer = getHeights(buffer, xOffs * xChunks, 0, zOffs * xChunks, xSize, ySize, zSize);
        for (int xc = 0; xc < xChunks; xc++)
            for (int zc = 0; zc < xChunks; zc++)
                for (int yc = 0; yc < 16; yc++) {
                    float yStep = 1 / 8f;
                    float s0 = buffer[((xc) * zSize + (zc)) * ySize + (yc)];
                    float s1 = buffer[((xc) * zSize + (zc + 1)) * ySize + (yc)];
                    float s2 = buffer[((xc + 1) * zSize + (zc)) * ySize + (yc)];
                    float s3 = buffer[((xc + 1) * zSize + (zc + 1)) * ySize + (yc)];
                    float s0a = (buffer[((xc) * zSize + (zc)) * ySize + (yc + 1)] - s0) * yStep;
                    float s1a = (buffer[((xc) * zSize + (zc + 1)) * ySize + (yc + 1)] - s1) * yStep;
                    float s2a = (buffer[((xc + 1) * zSize + (zc)) * ySize + (yc + 1)] - s2) * yStep;
                    float s3a = (buffer[((xc + 1) * zSize + (zc + 1)) * ySize + (yc + 1)] - s3) * yStep;
                    for (int y = 0; y < 8; y++) {
                        float xStep = 1 / 4f;
                        float _s0 = s0, _s1 = s1;
                        float _s0a = (s2 - s0) * xStep, _s1a = (s3 - s1) * xStep;
                        for (int x = 0; x < 4; x++) {
                            int offs = (x + xc * 4) << 11 | (zc * 4) << 7 | (yc * 8 + y);
                            float zStep = 1 / 4f;
                            float val = _s0, vala = (_s1 - _s0) * zStep;
                            for (int z = 0; z < 4; z++) {
                                float temp = temperatures[(xc * 4 + x) * 16 + (zc * 4 + z)];
                                int tileId = 0;
                                if (yc * 8 + y < waterHeight) {
                                    if (temp < 0.5f && yc * 8 + y >= waterHeight - 1) tileId = Tile.ICE;
                                    else tileId = Tile.STILL_WATER;
                                }
                                if (val > 0) tileId = ROCK;
                                blocks[offs] = (byte) tileId;
                                offs += 128;
                                val += vala;
                            }
                            _s0 += _s0a;
                            _s1 += _s1a;
                        }
                        s0 += s0a;
                        s1 += s1a;
                        s2 += s2a;
                        s3 += s3a;
                    }
                }
    }

    private float[] getHeights(float[] buf, int x, int y, int z, int xSize, int ySize, int zSize) {
        if (buf == null) buf = new float[xSize * ySize * zSize];
        float s = 1 * 684.412f, hs = 1 * 684.412f;
        float[] temperatures = bs.temperatures, downfalls = bs.downfalls;
        sr = scaleNoise.region2(sr, x, z, xSize, zSize, 1.121f, 1.121f);
        dr = depthNoise.region2(dr, x, z, xSize, zSize, 200.0f, 200.0f);
        pnr = perlin1.region(pnr, x, y, z, xSize, ySize, zSize, s / 80.0f, hs / 160.0f, s / 80.0f);
        ar = lperlin1.region(ar, x, y, z, xSize, ySize, zSize, s, hs, s);
        br = lperlin2.region(br, x, y, z, xSize, ySize, zSize, s, hs, s);
        int p = 0, pp = 0;
        int wScale = 16 / xSize;
        for (int xx = 0; xx < xSize; xx++) {
            int xp = xx * wScale + wScale / 2;
            for (int zz = 0; zz < zSize; zz++) {
                int zp = zz * wScale + wScale / 2;
                float temperature = temperatures[xp * 16 + zp];
                float downfall = downfalls[xp * 16 + zp] * temperature;
                float dd = 1 - downfall;
                dd = dd * dd;
                dd = dd * dd;
                dd = 1 - dd;
                float scale = (sr[pp] + 256.0f) / 512;
                scale *= dd;
                if (scale > 1) scale = 1;
                float depth = dr[pp] / 8000.0f;
                if (depth < 0) depth = -depth * 0.3f;
                depth = depth * 3.0f - 2.0f;
                if (depth < 0) {
                    depth = depth / 2;
                    if (depth < -1) depth = -1;
                    depth = depth / 1.4f;
                    depth /= 2;
                    scale = 0;
                } else {
                    if (depth > 1) depth = 1;
                    depth = depth / 8;
                }
                if (scale < 0) scale = 0;
                scale = scale + 0.5f;
                depth = depth * ySize / 16;
                float yCenter = ySize / 2.0f + depth * 4;
                pp++;
                for (int yy = 0; yy < ySize; yy++) {
                    float val;
                    float yOffs = (yy - yCenter) * 12 / scale;
                    if (yOffs < 0) yOffs *= 4;
                    float bb = ar[p] / 512, cc = br[p] / 512;
                    float v = (pnr[p] / 10 + 1) / 2;
                    if (v < 0) val = bb;
                    else if (v > 1) val = cc;
                    else val = bb + (cc - bb) * v;
                    val -= yOffs;
                    if (yy > ySize - 4) {
                        float slide = (yy - (ySize - 4)) / (4 - 1.0f);
                        val = val * (1 - slide) + -10 * slide;
                    }
                    buf[p] = val;
                    p++;
                }
            }
        }
        return buf;
    }

    private void buildSurfaces(int xOffs, int zOffs, byte[] blocks, int[] biomes) {
        int waterHeight = 64;
        float s = 1 / 32.0f;
        perlin2.region(sandBuf, xOffs * 16, zOffs * 16, 0, 16, 16, 1, s, s, 1);
        perlin2.region(gravelBuf, xOffs * 16, 109.01340f, zOffs * 16, 16, 1, 16, s, 1, s);
        perlin3.region(depthBuf, xOffs * 16, zOffs * 16, 0, 16, 16, 1, s * 2, s * 2, s * 2);
        Rnd random = this.random;
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int b = biomes[x + z * 16];
                boolean sand = (sandBuf[x + z * 16] + random.nextFloat() * 0.2f) > 0;
                boolean gravel = (gravelBuf[x + z * 16] + random.nextFloat() * 0.2f) > 3;
                int runDepth = (int) (depthBuf[x + z * 16] / 3 + 3 + random.nextFloat() * 0.25f);
                int run = -1;
                int bTop = b == B_DESERT || b == B_ICE_DESERT ? Tile.SAND : Tile.GRASS;
                int bMat = b == B_DESERT || b == B_ICE_DESERT ? Tile.SAND : Tile.DIRT;
                int top = bTop, material = bMat;
                for (int y = 127; y >= 0; y--) {
                    int offs = (z * 16 + x) * 128 + y;
                    if (y <= random.nextInt(5)) {
                        blocks[offs] = (byte) Tile.BEDROCK;
                        continue;
                    }
                    int old = blocks[offs] & 255;
                    if (old == 0) run = -1;
                    else if (old == ROCK) {
                        if (run == -1) {
                            if (runDepth <= 0) {
                                top = 0;
                                material = ROCK;
                            } else if (y >= waterHeight - 4 && y <= waterHeight + 1) {
                                top = bTop;
                                material = bMat;
                                if (gravel) {
                                    top = 0;
                                    material = Tile.GRAVEL;
                                }
                                if (sand) {
                                    top = Tile.SAND;
                                    material = Tile.SAND;
                                }
                            }
                            if (y < waterHeight && top == 0) top = Tile.STILL_WATER;
                            run = runDepth;
                            blocks[offs] = (byte) (y >= waterHeight - 1 ? top : material);
                        } else if (run > 0) {
                            run--;
                            blocks[offs] = (byte) material;
                            if (run == 0 && material == Tile.SAND) {
                                run = random.nextInt(4);
                                material = Tile.SANDSTONE;
                            }
                        }
                    }
                }
            }
    }

    // ---- RandomLevelSource::postProcess ----

    private void postProcess(int xt, int zt) {
        int xo = xt * 16, zo = zt * 16;
        int biome = bs.getBiome(xo + 16, zo + 16);
        Rnd random = this.random;
        random.setSeed(seed);
        int xScale = random.nextInt() / 2 * 2 + 1;
        int zScale = random.nextInt() / 2 * 2 + 1;
        random.setSeed((xt * xScale + zt * zScale) ^ seed);

        for (int i = 0; i < 10; i++) {
            int x = xo + random.nextInt(16), y = random.nextInt(128), z = zo + random.nextInt(16);
            clay(x, y, z, 32);
        }
        ores(xo, zo, 20, 128, Tile.DIRT, 32);
        ores(xo, zo, 10, 128, Tile.GRAVEL, 32);
        ores(xo, zo, 20, 128, Tile.COAL_ORE, 16);
        ores(xo, zo, 20, 64, Tile.IRON_ORE, 8);
        ores(xo, zo, 2, 32, Tile.GOLD_ORE, 8);
        ores(xo, zo, 8, 16, Tile.REDSTONE_ORE, 7);
        ores(xo, zo, 1, 16, Tile.DIAMOND_ORE, 7);
        {
            int x = xo + random.nextInt(16), y = random.nextInt(16) + random.nextInt(16), z = zo + random.nextInt(16);
            ore(x, y, z, Tile.LAPIS_ORE, 6);
        }

        float ss = 0.5f;
        int oFor = (int) ((forestNoise.getValue(xo * ss, zo * ss) / 8 + random.nextFloat() * 4 + 4) / 3);
        int forests = 0;
        if (random.nextInt(10) == 0) forests += 1;
        if (biome == B_FOREST) forests += oFor + 2;
        if (biome == B_RAINFOREST) forests += oFor + 2;
        if (biome == B_SEASONAL) forests += oFor + 1;
        if (biome == B_TAIGA) forests += oFor + 1;
        if (biome == B_DESERT) forests -= 20;
        if (biome == B_TUNDRA) forests -= 20;
        if (biome == B_PLAINS) forests -= 20;
        for (int i = 0; i < forests; i++) {
            int x = xo + random.nextInt(16) + 8, z = zo + random.nextInt(16) + 8;
            int y = getHeightmap(x, z);
            int type = treeFeature(biome);
            placeTree(type, x, y, z);
        }

        for (int i = 0; i < 2; i++) {
            int x = xo + random.nextInt(16) + 8, y = random.nextInt(128), z = zo + random.nextInt(16) + 8;
            flowers(x, y, z, Tile.FLOWER);
        }
        if (random.nextInt(2) == 0) {
            int x = xo + random.nextInt(16) + 8, y = random.nextInt(128), z = zo + random.nextInt(16) + 8;
            flowers(x, y, z, Tile.ROSE);
        }
        if (random.nextInt(4) == 0) {
            int x = xo + random.nextInt(16) + 8, y = random.nextInt(128), z = zo + random.nextInt(16) + 8;
            flowers(x, y, z, Tile.BROWN_MUSHROOM);
        }
        if (random.nextInt(8) == 0) {
            int x = xo + random.nextInt(16) + 8, y = random.nextInt(128), z = zo + random.nextInt(16) + 8;
            flowers(x, y, z, Tile.RED_MUSHROOM);
        }
        for (int i = 0; i < 10; i++) {
            int x = xo + random.nextInt(16) + 8, y = random.nextInt(128), z = zo + random.nextInt(16) + 8;
            reeds(x, y, z);
        }
        int cacti = biome == B_DESERT ? 5 : 0;
        for (int i = 0; i < cacti; i++) {
            int x = xo + random.nextInt(16) + 8, y = random.nextInt(128), z = zo + random.nextInt(16) + 8;
            cactus(x, y, z);
        }
        for (int i = 0; i < 50; i++) {
            int x = xo + random.nextInt(16) + 8, y = random.nextInt(random.nextInt(120) + 8), z = zo + random.nextInt(16) + 8;
            spring(x, y, z, Tile.WATER);
        }
        for (int i = 0; i < 20; i++) {
            int x = xo + random.nextInt(16) + 8, y = random.nextInt(random.nextInt(random.nextInt(112) + 8) + 8), z = zo + random.nextInt(16) + 8;
            spring(x, y, z, Tile.LAVA);
        }

        if (spawnMobs) spawnMobs(biome, xo + 8, zo + 8);

        float[] temperatures = bs.getTemperatureBlock(xo + 8, zo + 8, 16, 16);
        for (int x = xo + 8; x < xo + 8 + 16; x++)
            for (int z = zo + 8; z < zo + 8 + 16; z++) {
                int xp = x - (xo + 8), zp = z - (zo + 8);
                int y = getTopSolidBlock(x, z);
                float temp = temperatures[xp * 16 + zp] - (y - 64) / 64.0f * 0.3f;
                if (temp < 0.5f && y > 0 && y < 128 && isEmpty(x, y, z)) {
                    int below = getTile(x, y - 1, z);
                    if (blocksMotion(below) && below != Tile.ICE) set(x, y, z, Tile.SNOW_LAYER, 0, false);
                }
            }
    }

    private int getTopSolidBlock(int x, int z) {
        int y = 127;
        while (blocksMotion(getTile(x, y, z)) && y > 0) y--;
        if ((x & ~255) != 0 || (z & ~255) != 0) return -1;
        while (y > 0) {
            int t = getTile(x, y, z);
            if (t == 0 || !blocksMotion(t) || t == Tile.LEAVES) y--;
            else return y + 1;
        }
        return -1;
    }

    private void ores(int xo, int zo, int count, int maxY, int id, int size) {
        for (int i = 0; i < count; i++) {
            int x = xo + random.nextInt(16), y = random.nextInt(maxY), z = zo + random.nextInt(16);
            ore(x, y, z, id, size);
        }
    }

    /** OreFeature (Mth::sin/cos — табличные). */
    private void ore(int x, int y, int z, int tile, int count) {
        Rnd random = this.random;
        float dir = random.nextFloat() * Mth.PI;
        float x0 = x + 8 + msin(dir) * count / 8, x1 = x + 8 - msin(dir) * count / 8;
        float z0 = z + 8 + mcos(dir) * count / 8, z1 = z + 8 - mcos(dir) * count / 8;
        float y0 = y + random.nextInt(3) + 2, y1 = y + random.nextInt(3) + 2;
        for (int D = 0; D <= count; D++) {
            float d = D;
            float xx = x0 + (x1 - x0) * d / count, yy = y0 + (y1 - y0) * d / count, zz = z0 + (z1 - z0) * d / count;
            float ss = random.nextFloat() * count / 16;
            float r = (msin(d * Mth.PI / count) + 1) * ss + 1;
            float hr = (msin(d * Mth.PI / count) + 1) * ss + 1;
            int xt0 = (int) (xx - r / 2), yt0 = (int) (yy - hr / 2), zt0 = (int) (zz - r / 2);
            int xt1 = (int) (xx + r / 2), yt1 = (int) (yy + hr / 2), zt1 = (int) (zz + r / 2);
            for (int x2 = xt0; x2 <= xt1; x2++) {
                float xd = ((x2 + 0.5f) - xx) / (r / 2);
                if (xd * xd >= 1) continue;
                for (int y2 = yt0; y2 <= yt1; y2++) {
                    float yd = ((y2 + 0.5f) - yy) / (hr / 2);
                    if (xd * xd + yd * yd >= 1) continue;
                    for (int z2 = zt0; z2 <= zt1; z2++) {
                        float zd = ((z2 + 0.5f) - zz) / (r / 2);
                        if (xd * xd + yd * yd + zd * zd < 1 && getTile(x2, y2, z2) == ROCK) setNoUpdate(x2, y2, z2, tile, 0, false);
                    }
                }
            }
        }
    }

    private void clay(int x, int y, int z, int count) {
        if (!isWater(getTile(x, y, z))) return;
        Rnd random = this.random;
        float dir = random.nextFloat() * Mth.PI;
        float sn = (float) Math.sin(dir), cs = (float) Math.cos(dir);
        float x0 = x + 8 + sn * count / 8, x1 = x + 8 - sn * count / 8;
        float z0 = z + 8 + cs * count / 8, z1 = z + 8 - cs * count / 8;
        float y0 = y + random.nextInt(3) + 2, y1 = y + random.nextInt(3) + 2;
        for (int d = 0; d <= count; d++) {
            float xx = x0 + (x1 - x0) * d / count, yy = y0 + (y1 - y0) * d / count, zz = z0 + (z1 - z0) * d / count;
            float ss = random.nextFloat() * (float) (count >> 4);
            float sd = (float) Math.sin(d * Mth.PI / count);
            float r = (sd + 1) * ss + 1, hr = (sd + 1) * ss + 1;
            for (int x2 = (int) (xx - r / 2); x2 <= (int) (xx + r * 0.5f); x2++)
                for (int y2 = (int) (yy - hr / 2); y2 <= (int) (yy + hr * 0.5f); y2++)
                    for (int z2 = (int) (zz - r / 2); z2 <= (int) (zz + r * 0.5f); z2++) {
                        float xd = ((x2 + 0.5f) - xx) / (r * 0.5f), yd = ((y2 + 0.5f) - yy) / (hr * 0.5f), zd = ((z2 + 0.5f) - zz) / (r * 0.5f);
                        if (xd * xd + yd * yd + zd * zd < 1 && getTile(x2, y2, z2) == Tile.SAND) setNoUpdate(x2, y2, z2, Tile.CLAY, 0, false);
                    }
        }
    }

    private void flowers(int x, int y, int z, int tile) {
        Rnd random = this.random;
        for (int i = 0; i < 64; i++) {
            int x2 = x + random.nextInt(8) - random.nextInt(8);
            int y2 = y + random.nextInt(4) - random.nextInt(4);
            int z2 = z + random.nextInt(8) - random.nextInt(8);
            if (isEmpty(x2, y2, z2) && canSurvive(tile, x2, y2, z2)) setNoUpdate(x2, y2, z2, tile, 0, false);
        }
    }

    private void reeds(int x, int y, int z) {
        Rnd random = this.random;
        for (int i = 0; i < 20; i++) {
            int x2 = x + random.nextInt(4) - random.nextInt(4), y2 = y, z2 = z + random.nextInt(4) - random.nextInt(4);
            if (isEmpty(x2, y2, z2)) {
                if (isWater(getTile(x2 - 1, y2 - 1, z2)) || isWater(getTile(x2 + 1, y2 - 1, z2))
                        || isWater(getTile(x2, y2 - 1, z2 - 1)) || isWater(getTile(x2, y2 - 1, z2 + 1))) {
                    int h = 2 + random.nextInt(random.nextInt(3) + 1);
                    for (int yy = 0; yy < h; yy++)
                        if (reedsMayPlace(x2, y2 + yy, z2)) setNoUpdate(x2, y2 + yy, z2, Tile.REEDS, 0, false);
                }
            }
        }
    }

    private void cactus(int x, int y, int z) {
        Rnd random = this.random;
        for (int i = 0; i < 10; i++) {
            int x2 = x + random.nextInt(8) - random.nextInt(8);
            int y2 = y + random.nextInt(4) - random.nextInt(4);
            int z2 = z + random.nextInt(8) - random.nextInt(8);
            if (isEmpty(x2, y2, z2)) {
                int h = 1 + random.nextInt(random.nextInt(3) + 1);
                for (int yy = 0; yy < h; yy++)
                    if (cactusCanSurvive(x2, y2 + yy, z2)) setNoUpdate(x2, y2 + yy, z2, Tile.CACTUS, 0, false);
            }
        }
    }

    private void spring(int x, int y, int z, int tile) {
        if (getTile(x, y + 1, z) != ROCK) return;
        if (getTile(x, y - 1, z) != ROCK) return;
        if (getTile(x, y, z) != 0 && getTile(x, y, z) != ROCK) return;
        int rockCount = 0;
        if (getTile(x - 1, y, z) == ROCK) rockCount++;
        if (getTile(x + 1, y, z) == ROCK) rockCount++;
        if (getTile(x, y, z - 1) == ROCK) rockCount++;
        if (getTile(x, y, z + 1) == ROCK) rockCount++;
        int holeCount = 0;
        if (isEmpty(x - 1, y, z)) holeCount++;
        if (isEmpty(x + 1, y, z)) holeCount++;
        if (isEmpty(x, y, z - 1)) holeCount++;
        if (isEmpty(x, y, z + 1)) holeCount++;
        if (rockCount == 3 && holeCount == 1) {
            set(x, y, z, tile, 0, false);
            instaTick = true;
            liquidTick(x, y, z, tile, random);
            instaTick = false;
        }
    }

    // ---- деревья ----

    /** Biome::getTreeFeature: 0 дуб (TreeFeature), 1 ель (Spruce), 2 берёза, 3 сосна (Pine). */
    private int treeFeature(int biome) {
        switch (biome) {
            case B_FOREST:
                if (random.nextInt(5) == 0) return 2;
                random.nextInt(3);
                return 0;
            case B_TAIGA:
                return random.nextInt(3) == 0 ? 3 : 1;
            case B_RAINFOREST:
                random.nextInt(3);
                return 0;
            default:
                random.nextInt(10);
                return 0;
        }
    }

    private void placeBlock(int x, int y, int z, int id, int data) {
        if (live) set(x, y, z, id, data, true);
        else setNoUpdate(x, y, z, id, data, true);
    }

    private boolean placeTree(int type, int x, int y, int z) {
        switch (type) {
            case 1: return spruce(x, y, z);
            case 2: return oakOrBirch(x, y, z, true);
            case 3: return pine(x, y, z);
        }
        return oakOrBirch(x, y, z, false);
    }

    /** TreeFeature / BirchFeature. */
    private boolean oakOrBirch(int x, int y, int z, boolean birch) {
        Rnd random = this.random;
        int treeHeight = random.nextInt(3) + (birch ? 5 : 4);
        boolean free = true;
        if (y < 1 || y + treeHeight + 1 > 128) return false;
        for (int yy = y; yy <= y + 1 + treeHeight; yy++) {
            int r = 1;
            if (yy == y) r = 0;
            if (yy >= y + 1 + treeHeight - 2) r = 2;
            for (int xx = x - r; xx <= x + r && free; xx++)
                for (int zz = z - r; zz <= z + r && free; zz++) {
                    if (yy >= 0 && yy < 128) {
                        int tt = getTile(xx, yy, zz);
                        if (tt != 0 && tt != Tile.LEAVES) free = false;
                    } else free = false;
                }
        }
        if (!free) return false;
        int belowTile = getTile(x, y - 1, z);
        if ((belowTile != Tile.GRASS && belowTile != Tile.DIRT) || y >= 128 - treeHeight - 1) return false;
        placeBlock(x, y - 1, z, Tile.DIRT, 0);
        for (int yy = y - 3 + treeHeight; yy <= y + treeHeight; yy++) {
            int yo = yy - (y + treeHeight);
            int offs = 1 - yo / 2;
            for (int xx = x - offs; xx <= x + offs; xx++) {
                int xo = xx - x;
                for (int zz = z - offs; zz <= z + offs; zz++) {
                    int zo = zz - z;
                    if (Math.abs(xo) == offs && Math.abs(zo) == offs && (random.nextInt(2) == 0 || yo == 0)) continue;
                    if (!SOLID[getTile(xx, yy, zz)]) placeBlock(xx, yy, zz, Tile.LEAVES, birch ? 2 : 0);
                }
            }
        }
        for (int hh = 0; hh < treeHeight; hh++) {
            int t = getTile(x, y + hh, z);
            if (t == 0 || t == Tile.LEAVES) placeBlock(x, y + hh, z, Tile.LOG, birch ? 2 : 0);
        }
        return true;
    }

    private boolean spaceFor(int x, int y, int z, int treeHeight, int lowH, int radius) {
        boolean free = true;
        for (int yy = y; yy <= y + 1 + treeHeight && free; yy++) {
            int r = (yy - y) < lowH ? 0 : radius;
            for (int xx = x - r; xx <= x + r && free; xx++)
                for (int zz = z - r; zz <= z + r && free; zz++) {
                    if (yy >= 0 && yy < 128) {
                        int tt = getTile(xx, yy, zz);
                        if (tt != 0 && tt != Tile.LEAVES) free = false;
                    } else free = false;
                }
        }
        return free;
    }

    /** PineFeature. */
    private boolean pine(int x, int y, int z) {
        Rnd random = this.random;
        int treeHeight = random.nextInt(5) + 7;
        int trunkHeight = treeHeight - random.nextInt(2) - 3;
        int topHeight = treeHeight - trunkHeight;
        int topRadius = 1 + random.nextInt(topHeight + 1);
        if (y < 1 || y + treeHeight + 1 > 128) return false;
        if (!spaceFor(x, y, z, treeHeight, trunkHeight, topRadius)) return false;
        int belowTile = getTile(x, y - 1, z);
        if ((belowTile != Tile.GRASS && belowTile != Tile.DIRT) || y >= 128 - treeHeight - 1) return false;
        placeBlock(x, y - 1, z, Tile.DIRT, 0);
        int currentRadius = 0;
        for (int yy = y + treeHeight; yy >= y + trunkHeight; yy--) {
            for (int xx = x - currentRadius; xx <= x + currentRadius; xx++)
                for (int zz = z - currentRadius; zz <= z + currentRadius; zz++) {
                    if (Math.abs(xx - x) == currentRadius && Math.abs(zz - z) == currentRadius && currentRadius > 0) continue;
                    if (!SOLID[getTile(xx, yy, zz)]) placeBlock(xx, yy, zz, Tile.LEAVES, 1);
                }
            if (currentRadius >= 1 && yy == y + trunkHeight + 1) currentRadius -= 1;
            else if (currentRadius < topRadius) currentRadius += 1;
        }
        for (int hh = 0; hh < treeHeight - 1; hh++) {
            int t = getTile(x, y + hh, z);
            if (t == 0 || t == Tile.LEAVES) placeBlock(x, y + hh, z, Tile.LOG, 1);
        }
        return true;
    }

    /** SpruceFeature. */
    private boolean spruce(int x, int y, int z) {
        Rnd random = this.random;
        int treeHeight = random.nextInt(4) + 6;
        int trunkHeight = 1 + random.nextInt(2);
        int topHeight = treeHeight - trunkHeight;
        int leafRadius = 2 + random.nextInt(2);
        if (y < 1 || y + treeHeight + 1 > 128) return false;
        if (!spaceFor(x, y, z, treeHeight, trunkHeight, leafRadius)) return false;
        int belowTile = getTile(x, y - 1, z);
        if ((belowTile != Tile.GRASS && belowTile != Tile.DIRT) || y >= 128 - treeHeight - 1) return false;
        placeBlock(x, y - 1, z, Tile.DIRT, 0);
        int currentRadius = random.nextInt(2);
        int maxRadius = 1, minRadius = 0;
        for (int heightPos = 0; heightPos <= topHeight; heightPos++) {
            int yy = y + treeHeight - heightPos;
            for (int xx = x - currentRadius; xx <= x + currentRadius; xx++)
                for (int zz = z - currentRadius; zz <= z + currentRadius; zz++) {
                    if (Math.abs(xx - x) == currentRadius && Math.abs(zz - z) == currentRadius && currentRadius > 0) continue;
                    if (!SOLID[getTile(xx, yy, zz)]) placeBlock(xx, yy, zz, Tile.LEAVES, 1);
                }
            if (currentRadius >= maxRadius) {
                currentRadius = minRadius;
                minRadius = 1;
                maxRadius += 1;
                if (maxRadius > leafRadius) maxRadius = leafRadius;
            } else currentRadius = currentRadius + 1;
        }
        int topOffset = random.nextInt(3);
        for (int hh = 0; hh < treeHeight - topOffset; hh++) {
            int t = getTile(x, y + hh, z);
            if (t == 0 || t == Tile.LEAVES) placeBlock(x, y + hh, z, Tile.LOG, 1);
        }
        return true;
    }

    /** Sapling::growTree: ель (data 1), берёза (2), иначе дуб; блоки ставятся с обновлением. */
    public static boolean growTree(Level l, int x, int y, int z, int type, java.util.Random r) {
        Gen g = new Gen(l, r.nextInt());
        int t = type == 1 ? 1 : (type == 2 ? 2 : 0);
        return g.placeTree(t, x, y, z);
    }

    // ---- животные при генерации (MobSpawner::postProcessSpawnMobs) ----

    private static final int[] CREATURE_ID = { Mobs.SHEEP, Mobs.PIG, Mobs.CHICKEN, Mobs.COW };
    private static final int[] CREATURE_W = { 12, 10, 10, 8 }, CREATURE_MIN = { 2, 1, 2, 2 }, CREATURE_MAX = { 3, 3, 4, 3 };

    private boolean spawnPositionOk(int x, int y, int z) {
        int t = getTile(x, y, z);
        return isSolidBlocking(x, y - 1, z) && !isSolidBlocking(x, y, z) && !isWater(t) && !isLava(t) && !isSolidBlocking(x, y + 1, z);
    }

    private void spawnMobs(int biome, int xo, int zo) {
        if (biome == B_DESERT || biome == B_ICE_DESERT) return;
        Rnd random = this.random;
        while (random.nextFloat() < 0.08f) {
            int sel = levelRandom.nextInt(40), type = 0;
            for (int i = 0; i < 4; i++) {
                sel -= CREATURE_W[i];
                if (sel < 0) { type = i; break; }
            }
            int count = CREATURE_MIN[type] + random.nextInt(1 + CREATURE_MAX[type] - CREATURE_MIN[type]);
            int x = xo + random.nextInt(16), z = zo + random.nextInt(16);
            int startX = x, startZ = z;
            for (int c = 0; c < count; c++) {
                boolean success = false;
                for (int attempts = 0; !success && attempts < 4; attempts++) {
                    int y = getTopSolidBlock(x, z);
                    if (spawnPositionOk(x, y, z)) {
                        Entity e = Mobs.create(CREATURE_ID[type], l);
                        e.setPos(x + 0.5f, y, z + 0.5f);
                        e.yRot = e.yRotO = random.nextFloat() * 360;
                        if (e instanceof Mobs.Sheep) ((Mobs.Sheep) e).color = sheepColor();
                        l.addEntity(e);
                        success = true;
                    }
                    x += random.nextInt(5) - random.nextInt(5);
                    z += random.nextInt(5) - random.nextInt(5);
                    while (x < xo || x >= xo + 16 || z < zo || z >= zo + 16) {
                        x = startX + random.nextInt(5) - random.nextInt(5);
                        z = startZ + random.nextInt(5) - random.nextInt(5);
                    }
                }
            }
        }
    }

    private int sheepColor() {
        int n = levelRandom.nextInt(100);
        if (n < 5) return 15;
        if (n < 10) return 7;
        if (n < 15) return 8;
        if (n < 18) return 12;
        if (levelRandom.nextInt(500) == 0) return 6;
        return 0;
    }

    // ---- Level::setInitialSpawn ----

    private int topTile(int x, int z) {
        int y = 63;
        while (!isEmpty(x, y + 1, z)) y++;
        return getTile(x, y, z);
    }

    private boolean isValidSpawn(int x, int z) {
        int t = topTile(x, z);
        if (t == Tile.INVISIBLE_BEDROCK || t == 0) return false;
        return SOLID[t] && t != Tile.LEAVES; // листва при «красивой графике» не isSolidRender
    }

    private void setInitialSpawn() {
        int xSpawn = 128, zSpawn = 128;
        int guard = 0;
        while (!isValidSpawn(xSpawn, zSpawn) && guard++ < 100000) {
            xSpawn += levelRandom.nextInt(32) - levelRandom.nextInt(32);
            zSpawn += levelRandom.nextInt(32) - levelRandom.nextInt(32);
            if (xSpawn < 4) xSpawn += 32;
            if (xSpawn >= 256 - 4) xSpawn -= 32;
            if (zSpawn < 4) zSpawn += 32;
            if (zSpawn >= 256 - 4) zSpawn -= 32;
        }
        l.spawnX = xSpawn;
        l.spawnY = 64;
        l.spawnZ = zSpawn;
    }
}
