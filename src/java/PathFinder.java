/** Поиск пути A* — PathFinder / Node / BinaryHeap / Path 0.6.1 (без открывания дверей, вода не избегается). */
public final class PathFinder {

    public static final class Node {
        int x, y, z, hash;
        int heapIdx = -1;
        float g, h, f;
        Node cameFrom;
        boolean closed;
        /** distanceTo(цель поиска), посчитанное один раз (td < 0 — ещё нет) */
        float td;

        /** узел из пула на новую клетку */
        void reset(int x, int y, int z, int hash) {
            this.x = x; this.y = y; this.z = z;
            this.hash = hash;
            heapIdx = -1;
            g = h = f = 0;
            cameFrom = null;
            closed = false;
            td = -1;
        }

        static int createHash(int x, int y, int z) {
            return (y & 0xff) | ((x & 0x7fff) << 8) | ((z & 0x7fff) << 24) | (x < 0 ? 0x80000000 : 0) | (z < 0 ? 0x8000 : 0);
        }

        float distanceTo(Node o) {
            float xd = o.x - x, yd = o.y - y, zd = o.z - z;
            return Mth.sqrt(xd * xd + yd * yd + zd * zd);
        }
    }

    public static final class Path {
        int[] nx, ny, nz;
        int length, index;

        public boolean isEmpty() {
            return length == 0;
        }

        public boolean isDone() {
            return index >= length;
        }

        public void next() {
            index++;
        }

        public void destroy() {
            length = index = 0;
        }

        /** Path::getPos: центр клетки с учётом ширины моба */
        float posX(Entity e) {
            return nx[index] + (int) (e.bbW + 1) * 0.5f;
        }

        float posY() {
            return ny[index];
        }

        float posZ(Entity e) {
            return nz[index] + (int) (e.bbW + 1) * 0.5f;
        }
    }

    static final int TYPE_FENCE = 1, TYPE_LAVA = 2, TYPE_WATER = 3, TYPE_BLOCKED = 4, TYPE_OPEN = 5;
    private static final int MAX_NODES = 2048;

    private final Level level;
    /**
     * Узлы поиска: открытая адресация по Node.createHash (совпадение хеша — тот же узел, как в оригинале);
     * запись живая, пока её метка равна stamp текущего поиска. Узлы берутся из пула и переживают поиски.
     */
    private int stamp;
    private int[] nKey = new int[4096], nStamp = new int[4096];
    private Node[] nVal = new Node[4096];
    private Node[] pool = new Node[512];
    /** isFree клеток объёма моба за поиск (мир во время поиска не меняется) */
    private int[] fKey = new int[8192], fStamp = new int[8192];
    private byte[] fVal = new byte[8192];
    private int fCount;
    private Node target;
    private Node[] heap = new Node[1024];
    private int heapSize;
    private final Node[] neighbors = new Node[4];
    private int sizeX, sizeY, sizeZ;
    private int created;

    public PathFinder(Level l) {
        level = l;
    }

    /** Level::findPath к сущности (точка — низ её рамки). */
    public void findPath(Path path, Mob from, Entity to, float maxDist) {
        findPath(path, from, to.x, to.y, to.z, maxDist);
    }

    /** Level::findPath к блоку (центр клетки). */
    public void findPath(Path path, Mob from, int x, int y, int z, float maxDist) {
        findPath(path, from, x + 0.5f, y + 0.5f, z + 0.5f, maxDist);
    }

    private void findPath(Path path, Mob e, float xt, float yt, float zt, float maxDist) {
        Prof.paths++;
        long pt0 = Prof.on ? Prof.t() : 0;
        findPath0(path, e, xt, yt, zt, maxDist);
        if (Prof.on) Prof.pathMs += Prof.t() - pt0;
    }

    private void findPath0(Path path, Mob e, float xt, float yt, float zt, float maxDist) {
        if (++stamp == 0) {
            for (int i = 0; i < nStamp.length; i++) nStamp[i] = 0;
            for (int i = 0; i < fStamp.length; i++) fStamp[i] = 0;
            stamp = 1;
        }
        heapSize = 0;
        created = 0;
        fCount = 0;
        target = null;
        int startY;
        float hw = e.bbW / 2;
        if (e.inWater) {
            startY = (int) e.y;
            int t = level.getTile(Mth.floor(e.x), startY, Mth.floor(e.z));
            while (Tile.isWater(t)) {
                ++startY;
                t = level.getTile(Mth.floor(e.x), startY, Mth.floor(e.z));
            }
        } else startY = Mth.floor(e.y + 0.5f);
        Node from = node(Mth.floor(e.x - hw), startY, Mth.floor(e.z - hw));
        int xx0 = Mth.floor(xt - hw), yy0 = Mth.floor(yt), zz0 = Mth.floor(zt - hw);
        Node to = null;
        if (level.getTile(xx0, yy0 - 1, zz0) != 0) to = node(xx0, yy0, zz0);
        else {
            int xx1 = Mth.floor(xt + hw), zz1 = Mth.floor(zt + hw);
            search:
            for (int xx = xx0; xx <= xx1; ++xx)
                for (int zz = zz0; zz <= zz1; ++zz)
                    if (level.getTile(xx, yy0 - 1, zz) != 0) {
                        to = node(xx, yy0, zz);
                        break search;
                    }
            if (to == null) {
                int yy = yy0;
                while (level.getTile(xx0, yy - 1, zz0) == 0 && yy > 0) --yy;
                to = node(xx0, yy, zz0);
            }
        }
        sizeX = sizeZ = Mth.floor(e.bbW + 1);
        sizeY = Mth.floor(e.bbH + 1);
        // isFree кешируется уже с размером моба; узлы до этого места (from, to) его не вызывали
        target = to;
        search(path, from, to, maxDist);
        for (int i = 0; i < heapSize; i++) heap[i] = null;
        // большой поиск не держит узлы в хипе: в пуле остаются первые 512
        for (int i = 512; i < created; i++) pool[i] = null;
    }

    private void search(Path path, Node from, Node to, float maxDist) {
        from.g = 0;
        from.h = dist(from);
        from.f = from.h;
        heapSize = 0;
        insert(from);
        Node closest = from;
        while (heapSize > 0) {
            Node x = pop();
            if (x == to) {
                reconstruct(path, to);
                return;
            }
            if (dist(x) < dist(closest)) closest = x;
            x.closed = true;
            int n = neighbors(x, to, maxDist);
            for (int i = 0; i < n; i++) {
                Node y = neighbors[i];
                if (y.closed) continue;
                float tg = x.g + x.distanceTo(y);
                if (y.heapIdx < 0 || tg < y.g) {
                    y.cameFrom = x;
                    y.g = tg;
                    y.h = dist(y);
                    if (y.heapIdx >= 0) changeCost(y, y.g + y.h);
                    else {
                        y.f = y.g + y.h;
                        insert(y);
                    }
                }
            }
        }
        if (closest == from) return;
        reconstruct(path, closest);
    }

    private int neighbors(Node pos, Node target, float maxDist) {
        int p = 0;
        int jump = isFree(pos.x, pos.y + 1, pos.z) == TYPE_OPEN ? 1 : 0;
        Node n = node(pos.x, pos.y, pos.z + 1, jump);
        Node w = node(pos.x - 1, pos.y, pos.z, jump);
        Node e = node(pos.x + 1, pos.y, pos.z, jump);
        Node s = node(pos.x, pos.y, pos.z - 1, jump);
        if (n != null && !n.closed && dist(n) < maxDist) neighbors[p++] = n;
        if (w != null && !w.closed && dist(w) < maxDist) neighbors[p++] = w;
        if (e != null && !e.closed && dist(e) < maxDist) neighbors[p++] = e;
        if (s != null && !s.closed && dist(s) < maxDist) neighbors[p++] = s;
        return p;
    }

    /** PathFinder::getNode с прыжком на 1 и падением не больше 3 блоков. */
    private Node node(int x, int y, int z, int jump) {
        Node best = null;
        int type = isFree(x, y, z);
        if (type == TYPE_OPEN) best = node(x, y, z);
        if (best == null && jump > 0 && type != TYPE_FENCE && isFree(x, y + jump, z) == TYPE_OPEN) {
            best = node(x, y + jump, z);
            y += jump;
        }
        if (best != null) {
            int drop = 0, cost = 0;
            while (y > 0) {
                cost = isFree(x, y - 1, z);
                if (cost != TYPE_OPEN) break;
                if (++drop >= 4) return null;
                if (--y > 0) best = node(x, y, z);
            }
            if (cost == TYPE_LAVA) return null;
        }
        return best;
    }

    /** Node::distanceTo(цель) — то же число, посчитанное раз на узел */
    private float dist(Node n) {
        if (n.td < 0) n.td = n.distanceTo(target);
        return n.td;
    }

    private static int mix(int h) {
        h *= 0x9E3779B1;
        return h ^ (h >>> 15);
    }

    private Node node(int x, int y, int z) {
        int key = Node.createHash(x, y, z);
        int m = nKey.length - 1, i = mix(key) & m;
        while (nStamp[i] == stamp) {
            if (nKey[i] == key) return nVal[i];
            i = (i + 1) & m;
        }
        if ((created + 1) * 2 > nKey.length) {
            growNodes();
            return node(x, y, z);
        }
        if (created == pool.length) {
            Node[] np = new Node[pool.length * 2];
            System.arraycopy(pool, 0, np, 0, created);
            pool = np;
        }
        Node n = pool[created];
        if (n == null) pool[created] = n = new Node();
        n.reset(x, y, z, key);
        created++;
        nKey[i] = key;
        nStamp[i] = stamp;
        nVal[i] = n;
        return n;
    }

    private void growNodes() {
        int[] ok = nKey, os = nStamp;
        Node[] ov = nVal;
        int len = ok.length * 2, m = len - 1;
        nKey = new int[len];
        nStamp = new int[len];
        nVal = new Node[len];
        for (int j = 0; j < ok.length; j++) {
            if (os[j] != stamp) continue;
            int i = mix(ok[j]) & m;
            while (nStamp[i] == stamp) i = (i + 1) & m;
            nKey[i] = ok[j];
            nStamp[i] = stamp;
            nVal[i] = ov[j];
        }
    }

    /** PathFinder::isFree для объёма моба (с кешем на поиск) */
    private int isFree(int x, int y, int z) {
        if (target == null || x < -256 || x >= 768 || z < -256 || z >= 768 || y < -64 || y >= 192) return isFree0(x, y, z);
        int key = (x + 256) | ((z + 256) << 10) | ((y + 64) << 20);
        int m = fKey.length - 1, i = mix(key) & m;
        while (fStamp[i] == stamp) {
            if (fKey[i] == key) return fVal[i];
            i = (i + 1) & m;
        }
        int r = isFree0(x, y, z);
        if ((fCount + 1) * 2 > fKey.length) return r;
        fKey[i] = key;
        fStamp[i] = stamp;
        fVal[i] = (byte) r;
        fCount++;
        return r;
    }

    private int isFree0(int x, int y, int z) {
        for (int xx = x; xx < x + sizeX; xx++)
            for (int yy = y; yy < y + sizeY; yy++)
                for (int zz = z; zz < z + sizeZ; zz++) {
                    int t = level.getTile(xx, yy, zz);
                    if (t <= 0) continue;
                    if (t == Tile.IRON_DOOR || t == Tile.WOOD_DOOR) {
                        if ((TileLogic.doorComposite(level, xx, yy, zz) & 4) == 0) return TYPE_BLOCKED;
                        continue;
                    }
                    if (t == Tile.FENCE || t == Tile.FENCE_GATE) return TYPE_FENCE;
                    int m = Tile.mat[t];
                    if (Tile.mBlocksMotion(m)) return TYPE_BLOCKED;
                    if (m == Tile.MT_LAVA) return TYPE_LAVA;
                }
        return TYPE_OPEN;
    }

    private void reconstruct(Path path, Node to) {
        int count = 1;
        Node n = to;
        while (n.cameFrom != null) { count++; n = n.cameFrom; }
        path.nx = new int[count];
        path.ny = new int[count];
        path.nz = new int[count];
        path.length = count;
        path.index = 0;
        n = to;
        while (n != null) {
            --count;
            path.nx[count] = n.x;
            path.ny[count] = n.y;
            path.nz[count] = n.z;
            n = n.cameFrom;
        }
    }

    // ---- BinaryHeap ----

    private void insert(Node node) {
        if (heapSize == heap.length) {
            Node[] nh = new Node[heap.length * 2];
            System.arraycopy(heap, 0, nh, 0, heapSize);
            heap = nh;
        }
        heap[heapSize] = node;
        node.heapIdx = heapSize;
        upHeap(heapSize++);
    }

    private Node pop() {
        Node p = heap[0];
        heap[0] = heap[--heapSize];
        heap[heapSize] = null;
        if (heapSize > 0) downHeap(0);
        p.heapIdx = -1;
        return p;
    }

    private void changeCost(Node node, float cost) {
        float old = node.f;
        node.f = cost;
        if (cost < old) upHeap(node.heapIdx);
        else downHeap(node.heapIdx);
    }

    private void upHeap(int idx) {
        Node node = heap[idx];
        float cost = node.f;
        while (idx > 0) {
            int pi = (idx - 1) >> 1;
            Node parent = heap[pi];
            if (cost < parent.f) {
                heap[idx] = parent;
                parent.heapIdx = idx;
                idx = pi;
            } else break;
        }
        heap[idx] = node;
        node.heapIdx = idx;
    }

    private void downHeap(int idx) {
        Node node = heap[idx];
        float cost = node.f;
        while (true) {
            int li = 1 + (idx << 1), ri = li + 1;
            if (li >= heapSize) break;
            Node ln = heap[li];
            float lc = ln.f;
            Node rn = ri < heapSize ? heap[ri] : null;
            float rc = rn != null ? rn.f : Float.MAX_VALUE;
            if (lc < rc) {
                if (lc < cost) {
                    heap[idx] = ln;
                    ln.heapIdx = idx;
                    idx = li;
                } else break;
            } else {
                if (rc < cost) {
                    heap[idx] = rn;
                    rn.heapIdx = idx;
                    idx = ri;
                } else break;
            }
        }
        heap[idx] = node;
        node.heapIdx = idx;
    }
}
