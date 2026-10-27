/**
 * Таблица блоков. ID, data и номера текстур совпадают с MCPE 0.6.1 —
 * это нужно для совместимости миров и сети.
 * Стороны: 0 низ, 1 верх, 2 север(-z), 3 юг(+z), 4 запад(-x), 5 восток(+x).
 */
public final class Tile {
    public static final int N = 256;

    // формы отрисовки
    public static final int S_NONE = 0, S_CUBE = 1, S_CROSS = 2, S_LIQUID = 3, S_TORCH = 4,
            S_SLAB = 5, S_STAIRS = 6, S_FENCE = 7, S_CACTUS = 8, S_SNOW = 9, S_LADDER = 10,
            S_FARM = 11, S_DOOR = 12, S_PANE = 13, S_BED = 14, S_CROPS = 15, S_TRAPDOOR = 16,
            S_CHEST = 17, S_GATE = 18, S_INVISIBLE = 19, S_SIGN = 20;

    // слои
    public static final int L_OPAQUE = 0, L_CUTOUT = 1, L_BLEND = 2;

    // тонировка
    public static final int T_NONE = 0, T_GRASS = 1, T_FOLIAGE = 2;

    // материалы (упрощённо — для инструментов и звука)
    public static final int M_AIR = 0, M_STONE = 1, M_DIRT = 2, M_WOOD = 3, M_PLANT = 4,
            M_WATER = 5, M_LAVA = 6, M_SAND = 7, M_GLASS = 8, M_CLOTH = 9, M_METAL = 10,
            M_LEAVES = 11, M_SNOW = 12, M_ICE = 13, M_DECOR = 14;

    public static final boolean[] exists = new boolean[N];
    public static final String[] name = new String[N];
    public static final byte[] shape = new byte[N];
    public static final boolean[] opaque = new boolean[N];   // полный непрозрачный куб
    public static final boolean[] solid = new boolean[N];    // есть столкновение
    public static final byte[] lightBlock = new byte[N];
    public static final byte[] lightEmit = new byte[N];
    public static final float[] hardness = new float[N];
    public static final byte[] layer = new byte[N];
    public static final byte[] tint = new byte[N];
    public static final byte[] material = new byte[N];
    public static final boolean[] replaceable = new boolean[N];
    public static final boolean[] needsTool = new boolean[N]; // без кирки не добывается
    public static final int[] tex = new int[N * 6];

    public static final int AIR = 0, STONE = 1, GRASS = 2, DIRT = 3, COBBLE = 4, PLANKS = 5,
            SAPLING = 6, BEDROCK = 7, WATER = 8, STILL_WATER = 9, LAVA = 10, STILL_LAVA = 11,
            SAND = 12, GRAVEL = 13, GOLD_ORE = 14, IRON_ORE = 15, COAL_ORE = 16, LOG = 17,
            LEAVES = 18, GLASS = 20, LAPIS_ORE = 21, LAPIS_BLOCK = 22, SANDSTONE = 24, BED = 26,
            WEB = 30, TALLGRASS = 31, WOOL = 35, FLOWER = 37, ROSE = 38, BROWN_MUSHROOM = 39,
            RED_MUSHROOM = 40, GOLD_BLOCK = 41, IRON_BLOCK = 42, DOUBLE_SLAB = 43, SLAB = 44,
            BRICK = 45, TNT = 46, BOOKSHELF = 47, MOSSY = 48, OBSIDIAN = 49, TORCH = 50,
            WOOD_STAIRS = 53, CHEST = 54, DIAMOND_ORE = 56, DIAMOND_BLOCK = 57, WORKBENCH = 58,
            CROPS = 59, FARMLAND = 60, FURNACE = 61, FURNACE_LIT = 62, SIGN = 63, WOOD_DOOR = 64,
            LADDER = 65, STONE_STAIRS = 67, WALL_SIGN = 68, IRON_DOOR = 71, REDSTONE_ORE = 73,
            REDSTONE_ORE_LIT = 74, SNOW_LAYER = 78, ICE = 79, SNOW = 80, CACTUS = 81, CLAY = 82,
            REEDS = 83, FENCE = 85, NETHERRACK = 87, GLOWSTONE = 89, INVISIBLE_BEDROCK = 95,
            TRAPDOOR = 96, STONE_BRICK = 98, GLASS_PANE = 102, MELON = 103, MELON_STEM = 105,
            FENCE_GATE = 107, BRICK_STAIRS = 108, STONE_BRICK_STAIRS = 109, NETHER_BRICK = 112,
            NETHER_BRICK_STAIRS = 114, SANDSTONE_STAIRS = 128, QUARTZ = 155, QUARTZ_STAIRS = 156,
            STONECUTTER = 245, GLOWING_OBSIDIAN = 246, NETHER_REACTOR = 247, UPDATE1 = 248,
            UPDATE2 = 249, GRASS_CARRIED = 253, RESERVED6 = 255;

    // цвета шерсти: data -> текстура
    static final int[] WOOL_TEX = { 64, 210, 194, 178, 162, 146, 130, 114, 225, 209, 193, 177, 161, 145, 129, 113 };
    static final int[] SAPLING_TEX = { 15, 63, 79, 15 };
    static final int[] STONE_BRICK_TEX = { 54, 100, 101 };
    /** Direction::RELATIVE_DIRECTION_FACING */
    static final int[] BED_REL = { 0, 1, 3, 2, 5, 4, 0, 1, 5, 4, 2, 3, 0, 1, 2, 3, 4, 5, 0, 1, 4, 5, 3, 2 };
    static final int[] SANDSTONE_SIDE = { 192, 229, 230 };

    private static int cur;
    private static final Tile B = new Tile();

    static {
        reg(STONE, "stone", 1, 1.5f, M_STONE).tool();
        regGrass();
        reg(DIRT, "dirt", 2, 0.5f, M_DIRT);
        reg(COBBLE, "stonebrick", 16, 2.0f, M_STONE).tool();
        reg(PLANKS, "wood", 4, 2.0f, M_WOOD);
        reg(SAPLING, "sapling", 15, 0f, M_PLANT).cross();
        reg(BEDROCK, "bedrock", 17, -1f, M_STONE);
        liquid(WATER, "water", 205, M_WATER);
        liquid(STILL_WATER, "water", 205, M_WATER);
        liquid(LAVA, "lava", 237, M_LAVA);
        liquid(STILL_LAVA, "lava", 237, M_LAVA);
        lightEmit[LAVA] = lightEmit[STILL_LAVA] = 15;
        lightBlock[LAVA] = lightBlock[STILL_LAVA] = 15;
        reg(SAND, "sand", 18, 0.5f, M_SAND);
        reg(GRAVEL, "gravel", 19, 0.6f, M_SAND);
        reg(GOLD_ORE, "oreGold", 32, 3f, M_STONE).tool();
        reg(IRON_ORE, "oreIron", 33, 3f, M_STONE).tool();
        reg(COAL_ORE, "oreCoal", 34, 3f, M_STONE).tool();
        reg(LOG, "log", 20, 2f, M_WOOD).topBottom(21, 21);
        reg(LEAVES, "leaves", 52, 0.2f, M_LEAVES);
        layer[LEAVES] = L_CUTOUT; opaque[LEAVES] = false; lightBlock[LEAVES] = 1; tint[LEAVES] = T_FOLIAGE;
        reg(GLASS, "glass", 49, 0.3f, M_GLASS);
        layer[GLASS] = L_CUTOUT; opaque[GLASS] = false; lightBlock[GLASS] = 0;
        reg(LAPIS_ORE, "oreLapis", 160, 3f, M_STONE).tool();
        reg(LAPIS_BLOCK, "blockLapis", 144, 3f, M_STONE).tool();
        reg(SANDSTONE, "sandStone", 192, 0.8f, M_STONE).topBottom(176, 208).tool();
        reg(BED, "bed", 134, 0.2f, M_CLOTH).shape(S_BED);
        layer[BED] = L_CUTOUT;
        reg(WEB, "web", 11, 4f, M_DECOR).cross().tool();
        lightBlock[WEB] = 1;
        reg(TALLGRASS, "tallgrass", 39, 0f, M_PLANT).cross();
        tint[TALLGRASS] = T_GRASS; replaceable[TALLGRASS] = true;
        reg(WOOL, "cloth", 64, 0.8f, M_CLOTH);
        reg(FLOWER, "flower", 13, 0f, M_PLANT).cross();
        reg(ROSE, "rose", 12, 0f, M_PLANT).cross();
        reg(BROWN_MUSHROOM, "mushroom", 29, 0f, M_PLANT).cross();
        lightEmit[BROWN_MUSHROOM] = 1;
        reg(RED_MUSHROOM, "mushroom", 28, 0f, M_PLANT).cross();
        reg(GOLD_BLOCK, "blockGold", 23, 3f, M_METAL).tool();
        reg(IRON_BLOCK, "blockIron", 22, 5f, M_METAL).tool();
        reg(DOUBLE_SLAB, "stoneSlab", 5, 2f, M_STONE).topBottom(6, 6).tool();
        reg(SLAB, "stoneSlab", 5, 2f, M_STONE).topBottom(6, 6).shape(S_SLAB).tool();
        reg(BRICK, "brick", 7, 2f, M_STONE).tool();
        reg(TNT, "tnt", 8, 0f, M_PLANT).topBottom(9, 10);
        reg(BOOKSHELF, "bookshelf", 35, 1.5f, M_WOOD).topBottom(4, 4);
        reg(MOSSY, "stoneMoss", 36, 2f, M_STONE).tool();
        reg(OBSIDIAN, "obsidian", 37, 10f, M_STONE).tool();
        reg(TORCH, "torch", 80, 0f, M_DECOR).shape(S_TORCH);
        lightEmit[TORCH] = 14; layer[TORCH] = L_CUTOUT;
        stairs(WOOD_STAIRS, "stairsWood", PLANKS);
        reg(CHEST, "chest", 26, 2.5f, M_WOOD).topBottom(25, 25).shape(S_CHEST);
        reg(DIAMOND_ORE, "oreDiamond", 50, 3f, M_STONE).tool();
        reg(DIAMOND_BLOCK, "blockDiamond", 24, 5f, M_METAL).tool();
        reg(WORKBENCH, "workbench", 60, 2.5f, M_WOOD).topBottom(43, 4);
        tex[WORKBENCH * 6 + 2] = tex[WORKBENCH * 6 + 4] = 59;
        reg(CROPS, "crops", 88, 0f, M_PLANT).shape(S_CROPS);
        layer[CROPS] = L_CUTOUT;
        reg(FARMLAND, "farmland", 2, 0.6f, M_DIRT).topBottom(87, 2).shape(S_FARM);
        reg(FURNACE, "furnace", 45, 3.5f, M_STONE).topBottom(62, 62).tool();
        reg(FURNACE_LIT, "furnace", 45, 3.5f, M_STONE).topBottom(62, 62).tool();
        lightEmit[FURNACE_LIT] = 13;
        reg(SIGN, "sign", 4, 1f, M_WOOD).shape(S_SIGN);
        reg(WOOD_DOOR, "doorWood", 97, 3f, M_WOOD).shape(S_DOOR);
        reg(LADDER, "ladder", 83, 0.4f, M_DECOR).shape(S_LADDER);
        layer[LADDER] = L_CUTOUT;
        stairs(STONE_STAIRS, "stairsStone", COBBLE);
        reg(WALL_SIGN, "sign", 4, 1f, M_WOOD).shape(S_SIGN);
        reg(IRON_DOOR, "doorIron", 98, 5f, M_METAL).shape(S_DOOR).tool();
        reg(REDSTONE_ORE, "oreRedstone", 51, 3f, M_STONE).tool();
        reg(REDSTONE_ORE_LIT, "oreRedstone", 51, 3f, M_STONE).tool();
        lightEmit[REDSTONE_ORE_LIT] = 9;
        reg(SNOW_LAYER, "snow", 66, 0.1f, M_SNOW).shape(S_SNOW).tool();
        replaceable[SNOW_LAYER] = true;
        reg(ICE, "ice", 67, 0.5f, M_ICE);
        // IceTile 0.6.1 не переопределяет isSolidRender/getRenderLayer: непрозрачный блок слоя OPAQUE (текстура без альфы),
        // свет гасит на 3 (setLightBlock). Полупрозрачным он рисовал нижние грани и воду подо льдом поверх верха.
        lightBlock[ICE] = 3;
        reg(SNOW, "snow", 66, 0.2f, M_SNOW).tool();
        reg(CACTUS, "cactus", 70, 0.4f, M_PLANT).topBottom(69, 71).shape(S_CACTUS);
        layer[CACTUS] = L_CUTOUT;
        reg(CLAY, "clay", 72, 0.6f, M_DIRT);
        reg(REEDS, "reeds", 73, 0f, M_PLANT).cross();
        reg(FENCE, "fence", 4, 2f, M_WOOD).shape(S_FENCE);
        reg(NETHERRACK, "hellrock", 103, 0.4f, M_STONE).tool();
        reg(GLOWSTONE, "lightgem", 105, 0.3f, M_GLASS);
        lightEmit[GLOWSTONE] = 15;
        reg(INVISIBLE_BEDROCK, "invisibleBedrock", 0, -1f, M_STONE).shape(S_INVISIBLE);
        opaque[INVISIBLE_BEDROCK] = false; lightBlock[INVISIBLE_BEDROCK] = 0;
        reg(TRAPDOOR, "trapdoor", 84, 3f, M_WOOD).shape(S_TRAPDOOR);
        reg(STONE_BRICK, "stonebricksmooth", 54, 1.5f, M_STONE).tool();
        reg(GLASS_PANE, "thinGlass", 49, 0.3f, M_GLASS).shape(S_PANE);
        layer[GLASS_PANE] = L_CUTOUT;
        reg(MELON, "melon", 136, 1f, M_PLANT).topBottom(137, 137);
        reg(MELON_STEM, "pumpkinStem", 111, 0f, M_PLANT).cross();
        tint[MELON_STEM] = T_FOLIAGE;
        reg(FENCE_GATE, "fenceGate", 4, 2f, M_WOOD).shape(S_GATE);
        stairs(BRICK_STAIRS, "stairsBrick", BRICK);
        stairs(STONE_BRICK_STAIRS, "stairsStoneBrickSmooth", STONE_BRICK);
        reg(NETHER_BRICK, "netherBrick", 224, 2f, M_STONE).tool();
        stairs(NETHER_BRICK_STAIRS, "stairsNetherBrick", NETHER_BRICK);
        stairs(SANDSTONE_STAIRS, "stairsSandStone", SANDSTONE);
        reg(QUARTZ, "quartzBlock", 196, 0.8f, M_STONE).topBottom(198, 211).tool();
        stairs(QUARTZ_STAIRS, "stairsQuartz", QUARTZ);
        reg(STONECUTTER, "stonecutter", 168, 2.5f, M_STONE).topBottom(169, 62).tool();
        reg(GLOWING_OBSIDIAN, "glowingobsidian", 218, 10f, M_STONE).tool();
        lightEmit[GLOWING_OBSIDIAN] = 13;
        reg(NETHER_REACTOR, "netherreactor", 234, 3f, M_METAL).tool();
        reg(UPDATE1, "info_update", 252, 1f, M_DIRT);
        reg(UPDATE2, "info_update", 253, 1f, M_DIRT);
        reg(GRASS_CARRIED, "grass", 3, 0.6f, M_DIRT).topBottom(204, 2);
        reg(RESERVED6, "info_reserved6", 1, 1f, M_DIRT);
        replaceable[AIR] = true;
    }

    private static Tile reg(int id, String n, int t, float h, int mat) {
        cur = id;
        exists[id] = true;
        name[id] = n;
        shape[id] = S_CUBE;
        opaque[id] = true;
        solid[id] = true;
        lightBlock[id] = 15;
        hardness[id] = h;
        material[id] = (byte) mat;
        for (int f = 0; f < 6; f++) tex[id * 6 + f] = t;
        return B;
    }

    private static void regGrass() {
        reg(GRASS, "grass", 3, 0.6f, M_DIRT).topBottom(0, 2);
    }

    private static void liquid(int id, String n, int t, int mat) {
        reg(id, n, t, 100f, mat).shape(S_LIQUID);
        solid[id] = false;
        opaque[id] = false;
        lightBlock[id] = 3;
        layer[id] = L_BLEND;
        replaceable[id] = true;
        if (mat == M_LAVA) { layer[id] = L_OPAQUE; hardness[id] = id == LAVA ? 0f : 100f; }
    }

    private static void stairs(int id, String n, int base) {
        reg(id, n, tex[base * 6 + 2], hardness[base], material[base]).shape(S_STAIRS);
        for (int f = 0; f < 6; f++) tex[id * 6 + f] = tex[base * 6 + f];
        needsTool[id] = needsTool[base];
        lightBlock[id] = 0;
    }

    private Tile topBottom(int top, int bottom) {
        tex[cur * 6 + 1] = top;
        tex[cur * 6] = bottom;
        return this;
    }

    private Tile cross() {
        shape(S_CROSS);
        layer[cur] = L_CUTOUT;
        return this;
    }

    private Tile tool() {
        needsTool[cur] = true;
        return this;
    }

    private Tile shape(int s) {
        shape[cur] = (byte) s;
        if (s != S_CUBE) {
            opaque[cur] = false;
            lightBlock[cur] = 0;
            if (s == S_CROSS || s == S_TORCH || s == S_CROPS || s == S_SIGN || s == S_LADDER) solid[cur] = false;
        }
        return this;
    }

    // ---- Tile::SoundType 0.6.1 ----
    static final int SND_STONE = 0, SND_WOOD = 1, SND_GRAVEL = 2, SND_GRASS = 3, SND_METAL = 4, SND_GLASS = 5,
            SND_CLOTH = 6, SND_SAND = 7, SND_SILENT = 8;
    static final String[] SND_NAME = { "stone", "wood", "gravel", "grass", "stone", "stone", "cloth", "sand", "" };
    public static final byte[] sound = new byte[N];

    static {
        int[][] t = {
                { SND_GLASS, 20, 79, 89, 102 },
                { SND_GRAVEL, 3, 13, 60, 82, 248, 249 },
                { SND_GRASS, 2, 6, 18, 31, 37, 38, 39, 40, 46, 59, 83, 254 },
                { SND_METAL, 41, 42, 57, 71, 247 },
                { SND_CLOTH, 35, 78, 80, 81 },
                { SND_SAND, 12, 88 },
                { SND_WOOD, 5, 17, 47, 50, 51, 53, 54, 58, 63, 64, 65, 68, 85, 96, 103, 105, 107 },
                { SND_SILENT, 95 } };
        for (int i = 0; i < t.length; i++)
            for (int j = 1; j < t[i].length; j++) sound[t[i][j]] = (byte) t[i][0];
    }

    // ---- Material::initMaterials 0.6.1 и свойства Tile, от которых зависит логика мира ----
    public static final int MT_AIR = 0, MT_DIRT = 1, MT_WOOD = 2, MT_STONE = 3, MT_METAL = 4, MT_WATER = 5, MT_LAVA = 6,
            MT_LEAVES = 7, MT_PLANT = 8, MT_REPL_PLANT = 9, MT_CLOTH = 10, MT_FIRE = 11, MT_SAND = 12, MT_DECORATION = 13,
            MT_GLASS = 14, MT_EXPLOSIVE = 15, MT_ICE = 16, MT_TOPSNOW = 17, MT_SNOW = 18, MT_CACTUS = 19, MT_CLAY = 20,
            MT_VEGETABLE = 21, MT_WEB = 22;
    /** материал блока как в оригинале */
    public static final byte[] mat = new byte[N];
    /** Tile::solid — isSolidRender на момент init (листва тоже) */
    public static final boolean[] solidT = new boolean[N];
    /** Tile::isCubeShaped */
    public static final boolean[] cube = new boolean[N];
    /** поле explosionResistance (setDestroyTime: прочность*5, setExplodeable: v*3) */
    public static final float[] resist = new float[N];
    public static final float[] friction = new float[N];

    /** solidBlocking по id (материал и cube после инициализации не меняются) */
    private static final boolean[] solidBlockingT = new boolean[N];

    static {
        int[][] m = {
                { MT_DIRT, 2, 3, 60, 248, 249, 253, 255 },
                { MT_WOOD, 5, 17, 47, 53, 54, 58, 63, 64, 68, 85, 96, 107 },
                { MT_STONE, 1, 4, 7, 14, 15, 16, 21, 22, 24, 43, 44, 45, 48, 49, 56, 61, 62, 67, 73, 74, 87, 95, 98, 108, 109, 112, 114, 128, 155, 156, 245, 246 },
                { MT_METAL, 41, 42, 57, 71, 247 },
                { MT_WATER, 8, 9 }, { MT_LAVA, 10, 11 },
                { MT_LEAVES, 18, 254 },
                { MT_PLANT, 6, 37, 38, 39, 40, 59, 83, 105 },
                { MT_REPL_PLANT, 31 },
                { MT_CLOTH, 26, 35 },
                { MT_FIRE, 51 },
                { MT_SAND, 12, 13 },
                { MT_DECORATION, 50, 65 },
                { MT_GLASS, 20, 89, 102 },
                { MT_EXPLOSIVE, 46 },
                { MT_ICE, 79 }, { MT_TOPSNOW, 78 }, { MT_SNOW, 80 }, { MT_CACTUS, 81 }, { MT_CLAY, 82 },
                { MT_VEGETABLE, 103 }, { MT_WEB, 30 } };
        for (int i = 0; i < m.length; i++)
            for (int j = 1; j < m[i].length; j++) mat[m[i][j]] = (byte) m[i][0];
        int[] solid = { 1, 2, 3, 4, 5, 7, 12, 13, 14, 15, 16, 17, 18, 21, 22, 24, 35, 41, 42, 43, 45, 46, 47, 48, 49, 56, 57,
                58, 61, 62, 73, 74, 80, 82, 87, 89, 95, 98, 103, 112, 155, 245, 246, 247, 248, 249, 254, 255 };
        for (int i = 0; i < solid.length; i++) solidT[solid[i]] = true;
        int[] notCube = { 6, 8, 9, 10, 11, 26, 30, 31, 37, 38, 39, 40, 44, 50, 51, 53, 54, 59, 60, 63, 64, 65, 67, 68, 71, 78, 81,
                83, 85, 96, 102, 105, 107, 108, 109, 114, 128, 156 };
        for (int i = 0; i < N; i++) {
            cube[i] = exists[i];
            friction[i] = 0.6f;
            resist[i] = hardness[i] * 5;
        }
        for (int i = 0; i < notCube.length; i++) cube[notCube[i]] = false;
        friction[ICE] = 0.98f;
        // setExplodeable(v) -> v * 3
        int[][] ex = { { 10, 1, 4, 41, 42, 43, 44, 45, 48, 57, 98, 112 }, { 5, 5, 14, 15, 16, 21, 22, 56, 73, 74, 85, 107 },
                { 2000, 49, 246 }, { 6000000, 7, 95 } };
        for (int i = 0; i < ex.length; i++)
            for (int j = 1; j < ex[i].length; j++) resist[ex[i][j]] = ex[i][0] * 3;
        resist[LAVA] = 0;
        // StairTile: прочность и сопротивление как у основы
        int[][] st = { { WOOD_STAIRS, PLANKS }, { STONE_STAIRS, COBBLE }, { BRICK_STAIRS, BRICK }, { STONE_BRICK_STAIRS, STONE_BRICK },
                { NETHER_BRICK_STAIRS, NETHER_BRICK }, { SANDSTONE_STAIRS, SANDSTONE }, { QUARTZ_STAIRS, QUARTZ } };
        for (int i = 0; i < st.length; i++) resist[st[i][0]] = resist[st[i][1]];
        // FarmTile, StoneSlabTile: setLightBlock(255)
        lightBlock[FARMLAND] = 15;
        lightBlock[SLAB] = 15;
        for (int i = 1; i < N; i++) solidBlockingT[i] = mSolidBlocking(mat[i]) && cube[i];
    }


    public static boolean mSolid(int m) {
        return !(m == MT_AIR || m == MT_FIRE || m == MT_WATER || m == MT_LAVA || m == MT_PLANT || m == MT_REPL_PLANT
                || m == MT_DECORATION || m == MT_TOPSNOW);
    }

    public static boolean mBlocksMotion(int m) {
        return mSolid(m) && m != MT_WEB;
    }

    public static boolean mBlocksLight(int m) {
        return m == MT_WATER || m == MT_LAVA || mSolid(m);
    }

    public static boolean mLiquid(int m) {
        return m == MT_WATER || m == MT_LAVA;
    }

    /** Material::isSolidBlocking: не neverBuildable (листва, стекло, TNT, лёд, слой снега, кактус) и blocksMotion */
    public static boolean mSolidBlocking(int m) {
        if (m == MT_LEAVES || m == MT_GLASS || m == MT_EXPLOSIVE || m == MT_ICE || m == MT_TOPSNOW || m == MT_CACTUS) return false;
        return mBlocksMotion(m);
    }

    /** Material::isAlwaysDestroyable: камень, металл и снег без инструмента не выпадают */
    public static boolean mAlwaysDestroyable(int m) {
        return !(m == MT_STONE || m == MT_METAL || m == MT_TOPSNOW || m == MT_SNOW);
    }

    public static boolean mFlammable(int m) {
        return m == MT_WOOD || m == MT_LEAVES || m == MT_REPL_PLANT || m == MT_CLOTH || m == MT_EXPLOSIVE;
    }

    /** Tile::isSolidRender сейчас: листва непрозрачна при быстрой графике */
    public static boolean opaqueRender(int id) {
        return opaque[id] || ((id == LEAVES || id == 254) && fastLeaves);
    }

    /** Level::isSolidBlockingTile для id */
    public static boolean solidBlocking(int id) {
        return solidBlockingT[id];
    }

    /** Tile::getExplosionResistance */
    public static float explosionResistance(int id) {
        return resist[id] / 5f;
    }

    /** SoundType::getStepSound */
    public static String stepSound(int id) {
        int s = sound[id];
        return s == SND_SILENT ? null : "step." + SND_NAME[s];
    }

    /** SoundType::getBreakSound: стекло — random.glass, песок (Android) — step.gravel. */
    public static String breakSound(int id) {
        int s = sound[id];
        if (s == SND_GLASS) return "random.glass";
        if (s == SND_SAND) return "step.gravel";
        return stepSound(id);
    }

    /** SoundType::getVolume: трава 0.5, тишина 0, остальное 1. */
    public static float soundVolume(int id) {
        int s = sound[id];
        return s == SND_GRASS ? 0.5f : (s == SND_SILENT ? 0 : 1f);
    }

    /** Номер текстуры стороны с учётом data. */
    /** Быстрая графика (fancyGraphics = false, по умолчанию в 0.6.1): листва непрозрачная. */
    public static boolean fastLeaves = true;

    public static int getTex(int id, int face, int data) {
        switch (id) {
            case GRASS:
                return tex[id * 6 + face];
            case SAPLING:
                return SAPLING_TEX[data & 3];
            case LOG:
                if (face <= 1) return 21;
                if ((data & 3) == 1) return 116;
                if ((data & 3) == 2) return 117;
                return 20;
            case LEAVES:
                // LeafTile::setFancy: быстрая графика — непрозрачная текстура (+1)
                if ((data & 3) == 1) return fastLeaves ? 133 : 132;
                return fastLeaves ? 53 : 52;
            case WOOL:
                return WOOL_TEX[data & 15];
            case TALLGRASS:
                return (data == 2) ? 56 : (data == 0 ? 39 : 39);
            case SANDSTONE:
                if (face == 1) return 176;
                if (face == 0) return 208;
                return SANDSTONE_SIDE[data < 3 ? data : 0];
            case STONE_BRICK:
                return STONE_BRICK_TEX[data < 3 ? data : 0];
            case DOUBLE_SLAB:
            case SLAB:
                switch (data & 7) {
                    case 1: return face == 1 ? 176 : (face == 0 ? 208 : 192);
                    case 2: return 4;
                    case 3: return 16;
                    case 4: return 7;
                    case 5: return 54;
                    case 6: return face <= 1 ? 198 : 196;
                    default: return face <= 1 ? 6 : 5;
                }
            case FURNACE:
            case FURNACE_LIT:
                if (face <= 1) return 62;
                if (face == (data == 0 ? 3 : data)) return id == FURNACE ? 44 : 61;
                return 45;
            case CROPS:
                return 88 + (data & 7);
            case FARMLAND:
                if (face == 1) return data > 0 ? 86 : 87;
                return 2;
            case CHEST:
                if (face <= 1) return 25;
                if (face == (data == 0 ? 3 : data)) return 27;
                return 26;
            case QUARTZ:
                if (data == 1) return face <= 1 ? 213 : 214;
                if (data >= 2) return face <= 1 ? 198 : 197;
                return tex[id * 6 + face];
            case BED: {
                // BedTile::getTexture: низ — доски, остальное по стороне относительно направления кровати
                if (face == 0) return 4;
                int tf = BED_REL[(data & 3) * 6 + face];
                if ((data & 8) != 0) return tf == 2 ? 152 : (tf >= 4 ? 151 : 135);
                return tf == 3 ? 149 : (tf >= 4 ? 150 : 134);
            }
            case WOOD_DOOR:
                return (data & 8) != 0 ? 81 : 97;
            case IRON_DOOR:
                return (data & 8) != 0 ? 82 : 98;
        }
        return tex[id * 6 + face];
    }

    /** Сопротивление взрыву (как в MC: прочность*5 для большинства, у камня 30, обсидиана 6000). */
    public static float blastResistance(int id) {
        switch (id) {
            case OBSIDIAN: case GLOWING_OBSIDIAN: return 6000f;
            case STONE: case COBBLE: case BRICK: case STONE_BRICK: case MOSSY: case DOUBLE_SLAB: case SLAB:
            case STONE_STAIRS: case BRICK_STAIRS: case STONE_BRICK_STAIRS: case NETHER_BRICK: case NETHER_BRICK_STAIRS:
                return 30f;
            case WATER: case STILL_WATER: case LAVA: case STILL_LAVA: return 500f;
        }
        return hardness[id] * 5f;
    }

    /** Блок ломается мгновенно. */
    public static boolean instaBreak(int id) {
        return hardness[id] == 0f;
    }

    public static boolean isLiquid(int id) {
        return id >= WATER && id <= STILL_LAVA;
    }

    public static boolean isWater(int id) {
        return id == WATER || id == STILL_WATER;
    }

    public static boolean isLava(int id) {
        return id == LAVA || id == STILL_LAVA;
    }

    /** Что выпадает при разрушении (id предмета/блока, 0 — ничего). */
    public static int dropId(int id, int data) {
        switch (id) {
            case STONE: return COBBLE;
            case GRASS: case FARMLAND: case GRASS_CARRIED: return DIRT;
            case COAL_ORE: return 263;
            case DIAMOND_ORE: return 264;
            case REDSTONE_ORE: case REDSTONE_ORE_LIT: return 0;
            case LAPIS_ORE: return 351;
            case LEAVES: case TALLGRASS: case GLASS: case GLASS_PANE: case ICE:
            case STILL_WATER: case WATER: case LAVA: case STILL_LAVA: case BEDROCK:
            case INVISIBLE_BEDROCK: case DOUBLE_SLAB:
            case CROPS: case MELON_STEM:
                return id == DOUBLE_SLAB ? SLAB : 0;
            case GLOWSTONE: return 348;
            case CLAY: return 337;
            case SNOW: case SNOW_LAYER: return 332;
            case WEB: return 287;
            case REEDS: return 338;
            case WOOD_DOOR: return 324;
            case IRON_DOOR: return 330;
            case SIGN: case WALL_SIGN: return 323;
            case BOOKSHELF: return 340;
            case BED: return (data & 8) != 0 ? 0 : 355;
            case FURNACE_LIT: return FURNACE;
            case MELON: return 360;
        }
        return id;
    }
}
