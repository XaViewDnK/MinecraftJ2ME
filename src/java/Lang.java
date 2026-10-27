import java.io.InputStream;
import java.util.Hashtable;

/**
 * Строки из lang/en_US.lang (ключ=значение). В хипе держится сам файл (байты) и индекс по хешу ключа — строки
 * создаются только для запрошенных ключей (раньше Hashtable всех ~1500 строк занимал 117 КБ при файле 37 КБ).
 */
public final class Lang {
    private static byte[] data;
    /** по строкам файла: хеш ключа (как String.hashCode), начало строки, позиция '=' — отсортированы по хешу */
    private static int[] hash, start, eqPos;
    private static int count;
    private static final Hashtable cache = new Hashtable();
    private static final String NONE = new String("");

    public static void load() {
        try {
            InputStream in = Lang.class.getResourceAsStream("/lang/en_US.lang");
            if (in == null) return;
            byte[] buf = new byte[40 * 1024];
            int n = 0, r;
            while ((r = in.read(buf, n, buf.length - n)) > 0) {
                n += r;
                if (n == buf.length) {
                    byte[] b = new byte[buf.length + 16 * 1024];
                    System.arraycopy(buf, 0, b, 0, n);
                    buf = b;
                }
            }
            in.close();
            if (n != buf.length) {
                byte[] b = new byte[n];
                System.arraycopy(buf, 0, b, 0, n);
                buf = b;
            }
            data = buf;
            int lines = 0;
            for (int i = 0; i < n; i++) if (buf[i] == '\n') lines++;
            lines++;
            hash = new int[lines];
            start = new int[lines];
            eqPos = new int[lines];
            int p = 0, c = 0;
            while (p < n) {
                int e = p;
                while (e < n && buf[e] != '\n') e++;
                if (buf[p] != '#') {
                    int h = 0, q = p;
                    while (q < e && buf[q] != '=') h = 31 * h + (buf[q++] & 255);
                    if (q < e && q > p) {
                        hash[c] = h;
                        start[c] = p;
                        eqPos[c] = q;
                        c++;
                    }
                }
                p = e + 1;
            }
            count = c;
            // сортировка вставками по шагам (Шелл) — три массива вместе
            for (int gap = c / 2; gap > 0; gap /= 2)
                for (int i = gap; i < c; i++) {
                    int h = hash[i], st = start[i], eq = eqPos[i], j = i;
                    for (; j >= gap && hash[j - gap] > h; j -= gap) {
                        hash[j] = hash[j - gap];
                        start[j] = start[j - gap];
                        eqPos[j] = eqPos[j - gap];
                    }
                    hash[j] = h;
                    start[j] = st;
                    eqPos[j] = eq;
                }
        } catch (Throwable t) {
            Log.e("lang", t);
        }
    }

    /** значение ключа или null; последнее вхождение ключа в файле — как у Hashtable.put */
    private static String lookup(String key) {
        Object v = cache.get(key);
        if (v != null) return v == NONE ? null : (String) v;
        String res = null;
        if (data != null) {
            int h = key.hashCode(), lo = 0, hi = count - 1;
            while (lo < hi) {
                int m = (lo + hi) >>> 1;
                if (hash[m] < h) lo = m + 1;
                else hi = m;
            }
            int best = -1;
            for (int i = lo; i < count && hash[i] == h; i++) {
                int st = start[i], len = eqPos[i] - st;
                if (len != key.length()) continue;
                int k = 0;
                while (k < len && (data[st + k] & 255) == key.charAt(k)) k++;
                if (k == len && st > best) best = st;
            }
            if (best >= 0) {
                int i = best;
                while (data[i] != '=') i++;
                int e = ++i;
                while (e < data.length && data[e] != '\n') e++;
                if (e > i && data[e - 1] == '\r') e--;
                try {
                    res = new String(data, i, e - i, "UTF-8");
                } catch (Throwable t) {
                    res = new String(data, i, e - i);
                }
            }
        }
        cache.put(key, res == null ? NONE : res);
        return res;
    }

    public static String get(String key) {
        String v = lookup(key);
        return v != null ? v : key;
    }

    public static String get(String key, String def) {
        String v = lookup(key);
        return v != null ? v : def;
    }

    static final String[] COLOR_DESCS = { "black", "red", "green", "brown", "blue", "purple", "cyan", "silver", "gray", "pink",
            "lime", "yellow", "lightBlue", "magenta", "orange", "white" };
    static final String[] SLAB_NAMES = { "stone", "sand", "wood", "cobble", "brick", "smoothStoneBrick" };

    /** ItemInstance::getDescriptionId 0.6.1 (шерсть, краситель, плиты — с суффиксом по aux). */
    public static String descriptionId(int id, int aux) {
        String n = id < 256 ? Tile.name[id] : Item.name[id];
        if (n == null) return "";
        String k = (id < 256 ? "tile." : "item.") + n;
        if (id == Tile.WOOL) k += "." + COLOR_DESCS[~aux & 15];
        else if (id == 351) k += "." + COLOR_DESCS[aux < 0 ? 0 : (aux > 15 ? 15 : aux)];
        else if (id == Tile.SLAB) k += "." + SLAB_NAMES[aux < 0 || aux >= 6 ? 0 : aux];
        return k;
    }

    /** Название предмета/блока. */
    public static String itemName(int id, int aux) {
        if (id <= 0) return "";
        String k = descriptionId(id, aux);
        String v = lookup(k + ".name");
        if (v != null) return v;
        v = lookup((id < 256 ? "tile." : "item.") + (id < 256 ? Tile.name[id] : Item.name[id]) + ".name");
        return v != null ? v : k;
    }

    /** I18n::getDescriptionString 0.6.1. */
    public static String description(int id, int aux) {
        if (id == Tile.WOOL) return get(aux != 0 ? "desc.wool" : "desc.woolstring");
        if (id == Tile.FENCE_GATE) return get("desc.fence");
        if (id == Tile.SLAB) return get("desc.slab");
        String desc = descriptionId(id, aux);
        String s = desc.toLowerCase();
        if (s.startsWith("tile.")) s = "desc." + s.substring(5);
        else if (s.startsWith("item.")) s = "desc." + s.substring(5);
        String v = lookup(s);
        if (v != null) return v;
        String[] mats = { "wood", "iron", "stone", "diamond", "gold", "brick", "emerald", "lapis", "cloth" };
        for (int i = 0; i < mats.length; i++) {
            int p;
            while ((p = s.indexOf(mats[i])) >= 0) s = s.substring(0, p) + s.substring(p + mats[i].length());
        }
        v = lookup(s);
        if (v != null) return v;
        if (desc.equals("tile.workbench")) {
            v = lookup("desc.craftingtable");
            if (v != null) return v;
        }
        return desc + " : couldn't find desc";
    }
}
