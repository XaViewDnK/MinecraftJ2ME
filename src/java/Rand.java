/**
 * java.util.Random без synchronized: тот же линейный конгруэнтный генератор и те же числа (спецификация Random),
 * но next/nextInt без блокировки и с прямым вызовом — на слабых VM спавн мобов и AI тянут его тысячи раз за тик.
 */
final class Rand extends java.util.Random {
    private static final long MUL = 0x5DEECE66DL, MASK = (1L << 48) - 1;
    private long s;

    Rand() {
        super();
    }

    Rand(long seed) {
        super(seed);
    }

    public void setSeed(long seed) {
        s = (seed ^ MUL) & MASK;
    }

    protected int next(int bits) {
        s = (s * MUL + 0xBL) & MASK;
        return (int) (s >>> (48 - bits));
    }

    public int nextInt(int n) {
        if (n <= 0) throw new IllegalArgumentException();
        if ((n & -n) == n) {
            s = (s * MUL + 0xBL) & MASK;
            return (int) ((n * (s >>> 17)) >> 31);
        }
        int bits, val;
        do {
            s = (s * MUL + 0xBL) & MASK;
            bits = (int) (s >>> 17);
            val = bits % n;
        } while (bits - val + (n - 1) < 0);
        return val;
    }

    /** nextInt(6) - nextInt(6) одним вызовом (те же числа) — шаг кластера MobSpawner, тысячи раз за тик */
    int d6() {
        long q = s;
        int bits, a, b;
        do {
            q = (q * MUL + 0xBL) & MASK;
            bits = (int) (q >>> 17);
            a = bits % 6;
        } while (bits - a + 5 < 0);
        do {
            q = (q * MUL + 0xBL) & MASK;
            bits = (int) (q >>> 17);
            b = bits % 6;
        } while (bits - b + 5 < 0);
        s = q;
        return a - b;
    }

    public float nextFloat() {
        s = (s * MUL + 0xBL) & MASK;
        return (int) (s >>> 24) / ((float) (1 << 24));
    }
}
