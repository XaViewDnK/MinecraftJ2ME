import java.util.Hashtable;
import javax.microedition.m3g.*;

/**
 * Сетки предметов как в 0.6.1: блок — TileRenderer::renderTile (белый, без затенения граней, куб 1x1 по центру),
 * плоский предмет в руке — объёмная пластина ItemInHandRenderer::renderItem (иконка + 64 полоски толщиной 1/16).
 * Цвет (яркость) задаётся цветом по умолчанию, массива цветов нет.
 */
public final class ItemMeshes {
    public static final class Mesh {
        public VertexBuffer vb;
        public TriangleStripArray ib;
        /** true — блок (terrain.png), false — плоская иконка */
        public boolean block;
        /** плоская иконка из terrain.png (id < 256), иначе gui/items.png */
        public boolean terrain;
    }

    private static final Hashtable blocks = new Hashtable(), flats = new Hashtable(), sprites = new Hashtable();
    private static boolean meshFast = true;

    /** TileRenderer::canRender: блок рисуется объёмом, иначе — иконкой. */
    public static boolean canRender(int id) {
        if (id <= 0 || id >= 256) return false;
        switch (Tile.shape[id]) {
            case Tile.S_CUBE: case Tile.S_SLAB: case Tile.S_SNOW: case Tile.S_FARM: case Tile.S_TRAPDOOR:
            case Tile.S_CACTUS: case Tile.S_STAIRS: case Tile.S_FENCE: case Tile.S_GATE:
                return true;
        }
        return false;
    }

    /** Item::getIcon / TileItem::getIcon (сторона 2). */
    public static int icon(int id, int aux) {
        if (id < 256) return id == Tile.WOOD_DOOR ? 81 : Tile.getTex(id, 2, aux);
        return Item.iconFor(id, aux);
    }

    /** Текстура стороны предмета-блока: трава и листва — «carried»-версии (Tile::grass_carried, leaves_carried). */
    private static int tex(int id, int f, int aux) {
        if (id == Tile.GRASS) return f == 1 ? 204 : 3;
        if (id == Tile.LEAVES) {
            int t = (aux & 3) == 1 ? 219 : 235;
            return Tile.fastLeaves ? t + 1 : t;
        }
        return Tile.getTex(id, f, aux);
    }

    /** Блок размером 1, центр в 0 (координаты в 1/32 блока, развёртка в 1/512 атласа). */
    public static Mesh block(int id, int aux) {
        if (meshFast != Tile.fastLeaves) {
            blocks.clear();
            meshFast = Tile.fastLeaves;
        }
        Integer key = new Integer(id | (aux << 16));
        Mesh m = (Mesh) blocks.get(key);
        if (m != null) return m;
        MeshBuilder b = new MeshBuilder();
        switch (Tile.shape[id]) {
            case Tile.S_SLAB: box(b, id, aux, 0, 0, 0, 32, 16, 32); break;
            case Tile.S_SNOW: box(b, id, aux, 0, 0, 0, 32, 4, 32); break;
            case Tile.S_FARM: box(b, id, aux, 0, 0, 0, 32, 30, 32); break;
            case Tile.S_TRAPDOOR: box(b, id, aux, 0, 13, 0, 32, 19, 32); break;
            case Tile.S_STAIRS:
                box(b, id, aux, 0, 0, 0, 32, 32, 16);
                box(b, id, aux, 0, 0, 16, 32, 16, 32);
                break;
            case Tile.S_FENCE:
                box(b, id, aux, 12, 0, 0, 20, 32, 8);
                box(b, id, aux, 12, 0, 24, 20, 32, 32);
                box(b, id, aux, 14, 26, -4, 18, 30, 36);
                box(b, id, aux, 14, 10, -4, 18, 14, 36);
                break;
            case Tile.S_GATE:
                box(b, id, aux, 14, 10, 0, 18, 32, 4);
                box(b, id, aux, 14, 10, 28, 18, 32, 32);
                box(b, id, aux, 14, 16, 4, 18, 30, 28);
                break;
            case Tile.S_CACTUS:
                // TileRenderer::renderTile SHAPE_CACTUS: бока утоплены на 1/16
                for (int f = 0; f < 6; f++) {
                    int x0 = 0, y0 = 0, z0 = 0, x1 = 32, y1 = 32, z1 = 32;
                    if (f == 2) z0 = z1 = 2;
                    if (f == 3) z0 = z1 = 30;
                    if (f == 4) x0 = x1 = 2;
                    if (f == 5) x0 = x1 = 30;
                    face(b, f, x0, y0, z0, x1, y1, z1, tex(id, f, aux));
                }
                break;
            default:
                box(b, id, aux, 0, 0, 0, 32, 32, 32);
        }
        m = new Mesh();
        m.block = true;
        m.vb = build(b, -0.5f, 1f / 32f, 1f / 512f);
        m.ib = MeshBuilder.strips(b.verts);
        blocks.put(key, m);
        return m;
    }

    private static void box(MeshBuilder b, int id, int aux, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int f = 0; f < 6; f++) face(b, f, x0, y0, z0, x1, y1, z1, tex(id, f, aux));
    }

    /** Грань как TileRenderer::renderFaceUp/renderNorth/... (единицы 1/32, развёртка — полутексели). */
    private static void face(MeshBuilder m, int f, int x0, int y0, int z0, int x1, int y1, int z1, int tex) {
        int tu = (tex & 15) * 32, tv = (tex >> 4) * 32;
        int c = -1;
        int ux0 = clampT(x0), ux1 = clampT(x1), uy0 = clampT(y0), uy1 = clampT(y1), uz0 = clampT(z0), uz1 = clampT(z1);
        switch (f) {
            case 1:
                m.quad(x0, y1, z0, tu + ux0, tv + uz0, c, x0, y1, z1, tu + ux0, tv + uz1, c,
                        x1, y1, z1, tu + ux1, tv + uz1, c, x1, y1, z0, tu + ux1, tv + uz0, c);
                break;
            case 0:
                m.quad(x0, y0, z0, tu + ux0, tv + uz0, c, x1, y0, z0, tu + ux1, tv + uz0, c,
                        x1, y0, z1, tu + ux1, tv + uz1, c, x0, y0, z1, tu + ux0, tv + uz1, c);
                break;
            case 2:
                m.quad(x1, y0, z0, tu + 32 - ux1, tv + 32 - uy0, c, x0, y0, z0, tu + 32 - ux0, tv + 32 - uy0, c,
                        x0, y1, z0, tu + 32 - ux0, tv + 32 - uy1, c, x1, y1, z0, tu + 32 - ux1, tv + 32 - uy1, c);
                break;
            case 3:
                m.quad(x0, y0, z1, tu + ux0, tv + 32 - uy0, c, x1, y0, z1, tu + ux1, tv + 32 - uy0, c,
                        x1, y1, z1, tu + ux1, tv + 32 - uy1, c, x0, y1, z1, tu + ux0, tv + 32 - uy1, c);
                break;
            case 4:
                m.quad(x0, y0, z0, tu + uz0, tv + 32 - uy0, c, x0, y0, z1, tu + uz1, tv + 32 - uy0, c,
                        x0, y1, z1, tu + uz1, tv + 32 - uy1, c, x0, y1, z0, tu + uz0, tv + 32 - uy1, c);
                break;
            default:
                m.quad(x1, y0, z1, tu + 32 - uz1, tv + 32 - uy0, c, x1, y0, z0, tu + 32 - uz0, tv + 32 - uy0, c,
                        x1, y1, z0, tu + 32 - uz0, tv + 32 - uy1, c, x1, y1, z1, tu + 32 - uz1, tv + 32 - uy1, c);
                break;
        }
    }

    /** Форма за пределами блока (перекладины забора) берёт текстуру целиком, как в TileRenderer. */
    private static int clampT(int v) {
        return v < 0 ? 0 : (v > 32 ? 32 : v);
    }

    /** Объёмная иконка для руки: квадрат 1x1 от (0,0) до (1,1) и толщиной 1/16 в сторону -z. */
    public static Mesh flat(int id, int aux) {
        return flatIcon(icon(id, aux), id < 256);
    }

    public static Mesh flatIcon(int icon, boolean terrain) {
        Integer key = new Integer(icon | (terrain ? 0x10000 : 0));
        Mesh m = (Mesh) flats.get(key);
        if (m != null) return m;
        MeshBuilder b = new MeshBuilder();
        // развёртка в 1/16 текселя: u0 — правый край иконки (15.99), u1 — левый
        int up = icon & 15, vp = icon >> 4;
        int u1 = up * 256, u0 = up * 256 + 255, v0 = vp * 256, v1 = vp * 256 + 255;
        int c = -1;
        b.quad(0, 0, 0, u0, v1, c, 16, 0, 0, u1, v1, c, 16, 16, 0, u1, v0, c, 0, 16, 0, u0, v0, c);
        b.quad(0, 16, -1, u0, v0, c, 16, 16, -1, u1, v0, c, 16, 0, -1, u1, v1, c, 0, 0, -1, u0, v1, c);
        for (int i = 0; i < 16; i++) {
            int uu = u0 + (u1 - u0) * i / 16 - 8;
            b.quad(i, 0, -1, uu, v1, c, i, 0, 0, uu, v1, c, i, 16, 0, uu, v0, c, i, 16, -1, uu, v0, c);
            b.quad(i + 1, 16, -1, uu, v0, c, i + 1, 16, 0, uu, v0, c, i + 1, 0, 0, uu, v1, c, i + 1, 0, -1, uu, v1, c);
            int vv = v1 + (v0 - v1) * i / 16 - 8;
            b.quad(0, i + 1, 0, u0, vv, c, 16, i + 1, 0, u1, vv, c, 16, i + 1, -1, u1, vv, c, 0, i + 1, -1, u0, vv, c);
            b.quad(16, i, 0, u1, vv, c, 0, i, 0, u0, vv, c, 0, i, -1, u0, vv, c, 16, i, -1, u1, vv, c);
        }
        m = new Mesh();
        m.terrain = terrain;
        m.vb = build(b, 0, 1f / 16f, 1f / 4096f);
        m.ib = MeshBuilder.strips(b.verts);
        flats.put(key, m);
        return m;
    }

    /** Плоская иконка лежащего предмета (ItemRenderer): квадрат 1x1 со смещением (-0.5, -0.25). */
    public static Mesh sprite(int id, int aux) {
        int icon = icon(id, aux);
        boolean terrain = id < 256;
        Integer key = new Integer(icon | (terrain ? 0x10000 : 0));
        Mesh m = (Mesh) sprites.get(key);
        if (m != null) return m;
        MeshBuilder b = new MeshBuilder();
        int tu = (icon & 15) * 16, tv = (icon >> 4) * 16, c = -1;
        b.quad(-8, -4, 0, tu, tv + 16, c, 8, -4, 0, tu + 16, tv + 16, c, 8, 12, 0, tu + 16, tv, c, -8, 12, 0, tu, tv, c);
        m = new Mesh();
        m.terrain = terrain;
        m.vb = build(b, 0, 1f / 16f, 1f / 256f);
        m.ib = MeshBuilder.strips(b.verts);
        sprites.put(key, m);
        return m;
    }

    private static VertexBuffer build(MeshBuilder b, float bias, float posScale, float uvScale) {
        int v = b.verts;
        VertexArray pa = new VertexArray(v, 3, 2);
        if (MemDbg.on) MemDbg.va(pa, "ItemMeshes.pa");
        pa.set(0, v, b.pos);
        VertexArray ta = new VertexArray(v, 2, 2);
        if (MemDbg.on) MemDbg.va(ta, "ItemMeshes.ta");
        ta.set(0, v, b.uv);
        VertexBuffer vb = new VertexBuffer();
        vb.setPositions(pa, posScale, new float[] { bias, bias, bias });
        vb.setTexCoords(0, ta, uvScale, null);
        return vb;
    }

    /**
     * ModelPart::addBox (Cube/TexturedQuad): коробка в пикселях модели (Y вниз), развёртка в текселях:
     * +x, -x, верх (-y), низ (+y), перед (-z), зад (+z); у квада вершины 0..3 берут (u2,v1) (u1,v1) (u1,v2) (u2,v2).
     */
    public static void modelBox(MeshBuilder b, int x, int y, int z, int w, int h, int d, int u, int v) {
        int x1 = x + w, y1 = y + h, z1 = z + d;
        int[][] V = { { x, y, z }, { x1, y, z }, { x1, y1, z }, { x, y1, z }, { x, y, z1 }, { x1, y, z1 }, { x1, y1, z1 }, { x, y1, z1 } };
        int[][] Q = { { 5, 1, 2, 6 }, { 0, 4, 7, 3 }, { 5, 4, 0, 1 }, { 2, 3, 7, 6 }, { 1, 0, 3, 2 }, { 4, 5, 6, 7 } };
        int[][] T = { { u + d + w, v + d, u + d + w + d, v + d + h }, { u, v + d, u + d, v + d + h },
                { u + d, v, u + d + w, v + d }, { u + d + w, v + d, u + d + w + w, v },
                { u + d, v + d, u + d + w, v + d + h }, { u + d + w + d, v + d, u + d + w + d + w, v + d + h } };
        for (int f = 0; f < 6; f++) {
            int[] q = Q[f], tc = T[f];
            int[] a = V[q[0]], bb = V[q[1]], c = V[q[2]], e = V[q[3]];
            b.quad(a[0], a[1], a[2], tc[2], tc[1], -1, bb[0], bb[1], bb[2], tc[0], tc[1], -1,
                    c[0], c[1], c[2], tc[0], tc[3], -1, e[0], e[1], e[2], tc[2], tc[3], -1);
        }
    }
}
