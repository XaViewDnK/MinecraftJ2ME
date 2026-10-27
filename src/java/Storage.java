import java.io.*;
import java.util.Enumeration;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;

/**
 * Миры в формате MCPE 0.6.1 (StorageVersion 3):
 *  level.dat   — int версия, int длина, NBT (LE);
 *  chunks.dat  — таблица 1024 int смещений (сектор<<8 | число секторов), сектора по 4096 байт,
 *                в секторе: int длина+4, блоки 32768, data/sky/block по 16384, updateMap 256;
 *  entities.dat — "ENT\0", int 1, int длина, NBT {Entities, TileEntities}.
 */
public final class Storage {
    public static String base; // file:///.../games/com.mojang/minecraftWorlds/
    public static String error;
    static final int CHUNK_BYTES = 32768 + 16384 * 3 + 256;
    static final int SECTORS_PER_CHUNK = (CHUNK_BYTES + 4) / 4096 + 1;

    public static final class WorldInfo {
        public String folder, name;
        public long lastPlayed;
        public int gameType;
        public long seed;
    }

    public static boolean init() {
        if (base != null) return true;
        try {
            String root = null;
            String prop = System.getProperty("fileconn.dir.private");
            Enumeration e = FileSystemRegistry.listRoots();
            Vector roots = new Vector();
            while (e.hasMoreElements()) roots.addElement(e.nextElement());
            for (int i = 0; i < roots.size(); i++) {
                String r = (String) roots.elementAt(i);
                Log.i("root: " + r);
                if (root == null) root = r;
            }
            if (root == null) {
                error = "no file roots" + (prop != null ? " (" + prop + ")" : "");
                return false;
            }
            String b = "file:///" + root;
            if (!b.endsWith("/")) b += "/";
            mkdirs(b, "games/com.mojang/minecraftWorlds/");
            base = b + "games/com.mojang/minecraftWorlds/";
            Log.i("worlds dir: " + base);
            return true;
        } catch (Throwable t) {
            error = t.toString();
            Log.e("storage init", t);
            return false;
        }
    }

    private static void mkdirs(String root, String path) throws IOException {
        String cur = root;
        int p = 0;
        while (p < path.length()) {
            int q = path.indexOf('/', p);
            if (q < 0) q = path.length();
            cur += path.substring(p, q) + "/";
            FileConnection fc = (FileConnection) Connector.open(cur);
            try {
                if (!fc.exists()) fc.mkdir();
            } finally {
                fc.close();
            }
            p = q + 1;
        }
    }

    public static Vector listWorlds() {
        Vector out = new Vector();
        if (!init()) return out;
        try {
            FileConnection fc = (FileConnection) Connector.open(base);
            Vector names = new Vector();
            try {
                Enumeration e = fc.list();
                while (e.hasMoreElements()) {
                    String n = (String) e.nextElement();
                    if (n.endsWith("/")) names.addElement(n.substring(0, n.length() - 1));
                }
            } finally {
                fc.close();
            }
            for (int i = 0; i < names.size(); i++) {
                String folder = (String) names.elementAt(i);
                if (folder.startsWith("_") || !isOldFormat(folder)) continue;
                byte[] dat = readFile(base + folder + "/level.dat");
                if (dat == null) dat = readFile(base + folder + "/level.dat_old");
                if (dat == null || dat.length < 9 || le(dat, 0) > 3) continue;
                WorldInfo w = new WorldInfo();
                w.folder = folder;
                w.name = folder;
                try {
                    Nbt.Compound t = Nbt.read(dat, 8);
                    if (t != null) {
                        if (t.getInt("StorageVersion") > 3) continue;
                        w.name = t.getString("LevelName");
                        w.lastPlayed = t.getLong("LastPlayed");
                        w.gameType = t.getInt("GameType");
                        w.seed = t.getLong("RandomSeed");
                    }
                } catch (Throwable ex) {
                    Log.e("level.dat " + folder, ex);
                }
                // новые — первыми
                int k = 0;
                while (k < out.size() && ((WorldInfo) out.elementAt(k)).lastPlayed >= w.lastPlayed) k++;
                out.insertElementAt(w, k);
            }
        } catch (Throwable t) {
            Log.e("listWorlds", t);
        }
        return out;
    }

    /** Мир формата 0.6.x: нет папки db/ (её создают версии на LevelDB). */
    public static boolean isOldFormat(String folder) {
        try {
            FileConnection fc = (FileConnection) Connector.open(base + folder + "/db/", Connector.READ);
            try { return !fc.exists(); } finally { fc.close(); }
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean exists(String folder) {
        try {
            FileConnection fc = (FileConnection) Connector.open(base + folder + "/");
            try { return fc.exists(); } finally { fc.close(); }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Имя папки из названия мира (как в MCPE: недопустимые символы заменяются, при совпадении добавляется '-'). */
    public static String makeFolderName(String name) {
        // SelectWorldScreen 0.6.1: ILLEGAL_FILE_CHARACTERS удаляются, пустое имя — "no_name"
        StringBuffer sb = new StringBuffer();
        String bad = "/\n\r\t\0\f`?*\\<>|\":";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (bad.indexOf(c) < 0) sb.append(c);
        }
        String f = sb.toString();
        if (f.length() == 0) f = "no_name";
        init();
        while (exists(f)) f += "-";
        return f;
    }

    public static byte[] readFile(String url) {
        try {
            FileConnection fc = (FileConnection) Connector.open(url, Connector.READ);
            try {
                if (!fc.exists()) return null;
                int size = (int) fc.fileSize();
                byte[] b = new byte[size];
                InputStream in = fc.openInputStream();
                try {
                    int p = 0;
                    while (p < size) {
                        int r = in.read(b, p, size - p);
                        if (r < 0) break;
                        p += r;
                    }
                } finally {
                    in.close();
                }
                return b;
            } finally {
                fc.close();
            }
        } catch (Throwable t) {
            oom(t);
            return null;
        }
    }

    /** Нехватка памяти — не ошибка файла: наверх, MC снизит дальность и повторит шаг. */
    static void oom(Throwable t) {
        if (t instanceof OutOfMemoryError) throw (OutOfMemoryError) t;
    }

    static void writeFile(String url, byte[][] parts) throws IOException {
        FileConnection fc = (FileConnection) Connector.open(url, Connector.READ_WRITE);
        try {
            if (!fc.exists()) fc.create();
            else fc.truncate(0);
            OutputStream out = fc.openOutputStream();
            try {
                for (int i = 0; i < parts.length; i++) out.write(parts[i]);
                out.flush();
            } finally {
                out.close();
            }
        } finally {
            fc.close();
        }
    }

    static byte[] le(int v) {
        return new byte[] { (byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24) };
    }

    static int le(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8) | ((b[p + 2] & 255) << 16) | (b[p + 3] << 24);
    }

    // ---- сохранение ----

    public static void saveLevelDat(Level l, Player p) throws IOException {
        // поля, которых мы не знаем, переносим из загруженного level.dat как есть
        Nbt.Compound t = l.levelTag != null ? l.levelTag : new Nbt.Compound();
        l.levelTag = t;
        t.putLong("RandomSeed", l.seed);
        t.putInt("GameType", l.gameType);
        t.putInt("SpawnX", l.spawnX);
        t.putInt("SpawnY", l.spawnY);
        t.putInt("SpawnZ", l.spawnZ);
        t.putLong("Time", l.time);
        t.putLong("SizeOnDisk", 0);
        t.putLong("LastPlayed", System.currentTimeMillis() / 1000L);
        t.putString("LevelName", l.name);
        t.putInt("StorageVersion", 3);
        t.putInt("Platform", 2);
        if (!t.contains("dayCycleStopTime")) t.putLong("dayCycleStopTime", -1);
        if (!t.contains("spawnMobs")) t.putBoolean("spawnMobs", l.gameType == 0);
        if (p != null) l.playerTag = p.saveTo(l.playerTag);
        if (l.playerTag != null) t.putCompound("Player", l.playerTag);
        byte[] nbt = Nbt.write(t);
        String dir = base + l.folder + "/";
        writeFile(dir + "level.dat", new byte[][] { le(3), le(nbt.length), nbt });
    }

    public static void saveChunks(Level l) throws IOException {
        // под замком мира: подгрузка и выгрузка чанков не меняют набор выгруженных посреди записи
        synchronized (l) {
            saveChunks0(l);
        }
    }

    private static void saveChunks0(Level l) throws IOException {
        String dir = base + l.folder + "/";
        // выгруженные чанки есть только в старом файле: новый пишется рядом (chunks.tmp) и подменяет старый
        boolean copy = l.goneCount > 0 && l.chunkFile != null;
        ChunkIn old = copy ? new ChunkIn(l.chunkFile) : null;
        byte[] header = new byte[4096];
        int[] order = new int[256];
        for (int i = 0; i < 256; i++) {
            int cx = i & 15, cz = i >> 4;
            int sector = 1 + i * SECTORS_PER_CHUNK;
            int off = (sector << 8) | SECTORS_PER_CHUNK;
            int hi = (cx + cz * 32) * 4;
            header[hi] = (byte) off;
            header[hi + 1] = (byte) (off >> 8);
            header[hi + 2] = (byte) (off >> 16);
            header[hi + 3] = (byte) (off >> 24);
            order[i] = i;
        }
        FileConnection fc = (FileConnection) Connector.open(dir + (copy ? "chunks.tmp" : "chunks.dat"), Connector.READ_WRITE);
        try {
            if (!fc.exists()) fc.create();
            else fc.truncate(0);
            OutputStream out = fc.openOutputStream();
            try {
                out.write(header);
                byte[] pad = new byte[SECTORS_PER_CHUNK * 4096 - CHUNK_BYTES - 4];
                byte[] len = le(CHUNK_BYTES + 4);
                // слои мира сжаты (Pal): каждый чанк разворачивается в один буфер по очереди
                byte[] raw = new byte[32768];
                for (int i = 0; i < 256; i++) {
                    out.write(len);
                    if (l.gone[i]) {
                        // выгруженный чанк не менялся с прошлого сохранения — слои как есть из старого файла
                        int left = old.open(l.chunkOff[i]), got = readPart(old.in, raw, 32768, left);
                        out.write(raw, 0, 32768);
                        for (int k = 0; k < 3; k++) {
                            got += readPart(old.in, raw, 16384, left - got);
                            out.write(raw, 0, 16384);
                        }
                        old.pos += got;
                    } else {
                        l.blocks.exportChunk(i, raw, false);
                        out.write(raw, 0, 32768);
                        l.data.exportChunk(i, raw, true);
                        out.write(raw, 0, 16384);
                        l.sky.exportChunk(i, raw, true);
                        out.write(raw, 0, 16384);
                        l.blk.exportChunk(i, raw, true);
                        out.write(raw, 0, 16384);
                    }
                    out.write(l.um(i));
                    out.write(pad);
                    l.unsaved[i] = false;
                }
                out.flush();
            } finally {
                out.close();
            }
            if (copy) {
                old.close();
                FileConnection f = (FileConnection) Connector.open(dir + "chunks.dat", Connector.READ_WRITE);
                try {
                    if (f.exists()) f.delete();
                } finally {
                    f.close();
                }
                fc.rename("chunks.dat");
            }
        } finally {
            if (old != null) old.close();
            fc.close();
        }
        // теперь выгруженные чанки подгружаются из нового файла (раскладка: чанк i — сектор 1 + i * SECTORS_PER_CHUNK)
        l.chunkFile = dir + "chunks.dat";
        for (int i = 0; i < 256; i++) l.chunkOff[i] = (1 + i * SECTORS_PER_CHUNK) * 4096;
    }

    /** Чтение чанков chunks.dat по смещениям: вперёд — пропуском, назад — заново открыв файл. */
    static final class ChunkIn {
        final String url;
        FileConnection fc;
        InputStream in;
        long pos;

        ChunkIn(String url) {
            this.url = url;
        }

        /** встать на чанк по смещению off (байт), прочитать длину; вернуть байт данных чанка */
        int open(int off) throws IOException {
            if (in == null || off < pos) {
                close();
                fc = (FileConnection) Connector.open(url, Connector.READ);
                in = fc.openInputStream();
                pos = 0;
            }
            skipFully(in, off - pos);
            byte[] lenb = new byte[4];
            readFully(in, lenb, 0, 4);
            pos = off + 4;
            return le(lenb, 0) - 4;
        }

        void close() {
            try {
                if (in != null) in.close();
            } catch (IOException e) {
            }
            try {
                if (fc != null) fc.close();
            } catch (IOException e) {
            }
            in = null;
            fc = null;
        }
    }

    /** Подгрузка выгруженного чанка (Level.fault): слои из chunks.dat, updateMap в памяти не выгружается. */
    static void readChunk(Level l, int ch) throws IOException {
        ChunkIn ci = new ChunkIn(l.chunkFile);
        try {
            int left = ci.open(l.chunkOff[ch]);
            byte[] b = new byte[32768];
            left -= readPart(ci.in, b, 32768, left);
            l.blocks.importChunk(ch, b, false);
            left -= readPart(ci.in, b, 16384, left);
            l.data.importChunk(ch, b, true);
            left -= readPart(ci.in, b, 16384, left);
            l.sky.importChunk(ch, b, true);
            readPart(ci.in, b, 16384, left);
            l.blk.importChunk(ch, b, true);
        } finally {
            ci.close();
        }
    }

    public static void saveEntities(Level l) throws IOException {
        Nbt.Compound t = l.entitiesTag != null ? l.entitiesTag : new Nbt.Compound();
        l.entitiesTag = t;
        Nbt.NList ents = new Nbt.NList(Nbt.COMPOUND);
        for (int i = 0; i < l.entities.size(); i++) {
            Entity e = (Entity) l.entities.elementAt(i);
            if (e.removed || e instanceof Player || e instanceof RemotePlayer) continue;
            if (e instanceof Mob && ((Mob) e).health <= 0) continue;
            Nbt.Compound et = new Nbt.Compound();
            e.save(et);
            ents.add(et);
        }
        for (int i = 0; i < l.rawEntities.size(); i++) ents.add(l.rawEntities.elementAt(i));
        Nbt.NList tes = new Nbt.NList(Nbt.COMPOUND);
        for (java.util.Enumeration en = l.tileEntities.elements(); en.hasMoreElements(); ) {
            tes.add(((TileEntity) en.nextElement()).save());
        }
        t.putList("Entities", ents);
        t.putList("TileEntities", tes);
        byte[] nbt = Nbt.write(t);
        dropLists(t);
        String dir = base + l.folder + "/";
        writeFile(dir + "entities.dat", new byte[][] { new byte[] { 'E', 'N', 'T', 0 }, le(1), le(nbt.length), nbt });
    }

    /**
     * entitiesTag держится ради прочих ключей entities.dat; списки сущностей в нём — копия того, что уже в мире
     * (до 70 КБ), — заменить пустыми (ключи и их порядок те же, при сохранении списки пишутся заново).
     */
    private static void dropLists(Nbt.Compound t) {
        if (t.map.containsKey("Entities")) t.putList("Entities", new Nbt.NList(Nbt.COMPOUND));
        if (t.map.containsKey("TileEntities")) t.putList("TileEntities", new Nbt.NList(Nbt.COMPOUND));
    }

    /** Разобрать entities.dat в сущности мира. */
    static void loadEntities(Level l) {
        Nbt.Compound t = l.entitiesTag;
        if (t == null) return;
        Nbt.NList ents = t.getList("Entities");
        if (ents != null) {
            for (int i = 0; i < ents.size(); i++) {
                Object o = ents.get(i);
                if (!(o instanceof Nbt.Compound)) continue;
                Nbt.Compound et = (Nbt.Compound) o;
                Entity e = Mobs.create(et.getInt("id"), l);
                if (e == null) {
                    l.rawEntities.addElement(et);
                    continue;
                }
                try {
                    e.load(et);
                    if (!e.removed) l.addEntity(e);
                } catch (Throwable ex) {
                    oom(ex);
                    Log.e("entity " + et.getInt("id"), ex);
                    l.rawEntities.addElement(et);
                }
            }
        }
        Nbt.NList tes = t.getList("TileEntities");
        if (tes != null) {
            for (int i = 0; i < tes.size(); i++) {
                Object o = tes.get(i);
                if (!(o instanceof Nbt.Compound)) continue;
                TileEntity te = TileEntity.load((Nbt.Compound) o);
                if (Level.inside(te.x, te.y, te.z)) l.setTileEntity(te);
            }
        }
        dropLists(t);
    }

    public static void saveAll(Level l, Player p) {
        try {
            mkdirs(base, l.folder + "/");
            saveLevelDat(l, p);
            saveChunks(l);
            saveEntities(l);
            Log.i("saved world " + l.folder);
        } catch (Throwable t) {
            oom(t);
            Log.e("save", t);
        }
    }

    public static boolean anyUnsaved(Level l) {
        for (int i = 0; i < 256; i++) if (l.unsaved[i]) return true;
        return false;
    }

    // ---- загрузка ----

    public static Level load(String folder) {
        String dir = base + folder + "/";
        byte[] dat = readFile(dir + "level.dat");
        if (dat == null) dat = readFile(dir + "level.dat_old");
        if (dat == null || dat.length < 9) return null;
        int version = le(dat, 0);
        if (version > 3 || !isOldFormat(folder)) return null;
        Level l = new Level();
        l.folder = folder;
        if (version >= 2) {
            Nbt.Compound t = Nbt.read(dat, 8);
            if (t != null) {
                l.levelTag = t;
                l.seed = t.getLong("RandomSeed");
                l.gameType = t.getInt("GameType");
                l.spawnX = t.getInt("SpawnX");
                l.spawnY = t.getInt("SpawnY");
                l.spawnZ = t.getInt("SpawnZ");
                l.time = t.getLong("Time");
                l.name = t.getString("LevelName");
                l.storageVersion = t.getInt("StorageVersion");
                l.lastPlayed = t.getLong("LastPlayed");
                l.playerTag = t.getCompound("Player");
            }
        } else {
            l.storageVersion = 1;
        }
        boolean hasLight = l.storageVersion >= 1;
        recoverTmp(dir);
        // мир со светом в файле грузится по чанкам: дальние от игрока сразу выгружаются (в памяти не весь мир)
        boolean[] keep = null;
        l.chunkFile = dir + "chunks.dat";
        if (hasLight && l.canEvict()) {
            float px = l.spawnX, pz = l.spawnZ;
            Nbt.NList pos = l.playerTag != null ? l.playerTag.getList("Pos") : null;
            if (pos != null && pos.size() >= 3) {
                px = pos.getFloat(0);
                pz = pos.getFloat(2);
            }
            keep = l.keepSet(px, pz);
        }
        try {
            FileConnection fc = (FileConnection) Connector.open(dir + "chunks.dat", Connector.READ);
            try {
                if (fc.exists()) {
                    InputStream in = fc.openInputStream();
                    try {
                        readChunks(l, in, hasLight, keep);
                    } finally {
                        in.close();
                    }
                }
            } finally {
                fc.close();
            }
        } catch (Throwable t) {
            oom(t);
            Log.e("chunks.dat", t);
        }
        byte[] ent = readFile(dir + "entities.dat");
        if (ent != null && ent.length > 12) {
            try {
                l.entitiesTag = Nbt.read(ent, 12);
                loadEntities(l);
            } catch (Throwable t) {
                oom(t);
                Log.e("entities.dat", t);
            }
        }
        // при загрузке по чанкам высоты посчитаны при чтении
        if (!hasLight) l.recalcAllLight();
        else if (keep == null) l.recalcHeightmap();
        return l;
    }

    /** запись chunks.dat прервалась между удалением старого и переименованием нового — взять новый */
    private static void recoverTmp(String dir) {
        try {
            FileConnection d = (FileConnection) Connector.open(dir + "chunks.dat", Connector.READ);
            boolean have;
            try {
                have = d.exists();
            } finally {
                d.close();
            }
            FileConnection t = (FileConnection) Connector.open(dir + "chunks.tmp", Connector.READ_WRITE);
            try {
                if (t.exists()) {
                    if (have) t.delete();
                    else t.rename("chunks.dat");
                }
            } finally {
                t.close();
            }
        } catch (Throwable e) {
            Log.e("chunks.tmp", e);
        }
    }

    private static void readChunks(Level l, InputStream in, boolean hasLight, boolean[] keep) throws IOException {
        byte[] header = new byte[4096];
        readFully(in, header, 0, 4096);
        // чанки по возрастанию сектора, чтобы читать поток последовательно
        int[] sect = new int[256], idx = new int[256];
        int n = 0;
        for (int cz = 0; cz < 16; cz++)
            for (int cx = 0; cx < 16; cx++) {
                int off = le(header, (cx + cz * 32) * 4);
                if (off == 0) continue;
                sect[n] = off >>> 8;
                idx[n] = cx | (cz << 4);
                l.chunkOff[cx | (cz << 4)] = (off >>> 8) * 4096;
                n++;
            }
        for (int i = 1; i < n; i++) {
            int s = sect[i], k = idx[i], j = i - 1;
            while (j >= 0 && sect[j] > s) { sect[j + 1] = sect[j]; idx[j + 1] = idx[j]; j--; }
            sect[j + 1] = s; idx[j + 1] = k;
        }
        long pos = 4096;
        byte[] lenb = new byte[4];
        byte[] b = new byte[32768], d = new byte[16384], um = new byte[256];
        for (int i = 0; i < n; i++) {
            long target = (long) sect[i] * 4096L;
            if (target < pos) continue;
            skipFully(in, target - pos);
            pos = target;
            readFully(in, lenb, 0, 4);
            pos += 4;
            int len = le(lenb, 0) - 4;
            int c = idx[i];
            int got = 0;
            got += readPart(in, b, 32768, len - got);
            got += readPart(in, d, 16384, len - got);
            fixBlocks(l, c, b, d);
            l.blocks.importChunk(c, b, false);
            l.data.importChunk(c, d, true);
            if (hasLight) {
                got += readPart(in, b, 16384, len - got);
                l.sky.importChunk(c, b, true);
                got += readPart(in, b, 16384, len - got);
                l.blk.importChunk(c, b, true);
            }
            got += readPart(in, um, 256, len - got);
            for (int k = 0; k < 256; k++)
                if (um[k] != 0) {
                    byte[] u = l.umw(c);
                    System.arraycopy(um, 0, u, 0, 256);
                    break;
                }
            pos += got;
            if (keep != null) {
                l.calcChunkHeights(c);
                if (!keep[c] && !l.unsaved[c]) l.evictChunk(c);
            }
        }
    }

    /** замена незнакомых блоков на «Update!» и старой шерсти (версия 1) */
    private static void fixBlocks(Level l, int c, byte[] b, byte[] d) {
        for (int i = 0; i < 32768; i++) {
            int id = b[i] & 255;
            if (l.storageVersion == 1 && id >= 101 && id <= 115) {
                b[i] = (byte) Tile.WOOL;
                Level.setNibble(d, i, 15 - (id - 101));
                l.unsaved[c] = true;
                continue;
            }
            if (id != 0 && !Tile.exists[id]) {
                b[i] = (byte) Tile.UPDATE1;
                l.unsaved[c] = true;
            }
        }
    }

    /** n байт куска (остаток — нули, как у недописанного чанка) */
    private static int readPart(InputStream in, byte[] dst, int n, int left) throws IOException {
        int r = Math.min(n, Math.max(0, left));
        readFully(in, dst, 0, r);
        for (int i = r; i < n; i++) dst[i] = 0;
        return r;
    }

    private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int r = in.read(b, off, len);
            if (r < 0) throw new EOFException();
            off += r;
            len -= r;
        }
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        byte[] tmp = null;
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (tmp == null) tmp = new byte[4096];
                int r = in.read(tmp, 0, (int) Math.min(4096, n));
                if (r < 0) throw new EOFException();
                s = r;
            }
            n -= s;
        }
    }

    public static boolean deleteWorld(String folder) {
        try {
            String dir = base + folder + "/";
            FileConnection fc = (FileConnection) Connector.open(dir);
            try {
                Enumeration e = fc.list("*", true);
                Vector files = new Vector();
                while (e.hasMoreElements()) files.addElement(e.nextElement());
                for (int i = 0; i < files.size(); i++) {
                    FileConnection f = (FileConnection) Connector.open(dir + files.elementAt(i));
                    try { f.delete(); } finally { f.close(); }
                }
                fc.delete();
            } finally {
                fc.close();
            }
            return true;
        } catch (Throwable t) {
            Log.e("delete", t);
            return false;
        }
    }
}
