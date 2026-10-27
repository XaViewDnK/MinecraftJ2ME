/** Математика для CLDC 1.1 (нет atan2, pow и т.п.). */
public final class Mth {
    public static final float PI = 3.1415927f;
    public static final float DEG = PI / 180f;
    /**
     * Mth::_sin 0.6.1 (общая с генератором мира, Gen.msin): значение i — (float) Math.sin(i / scale), как в таблице
     * из 65536 float. Таблица занимала 256 КБ; теперь значения считаются по запросу и кэшируются (4096 ячеек по
     * long: номер | биты float) — те же самые биты, детерминизм не меняется.
     */
    private static final float SIN_SCALE = 65536.0f / (2.0f * PI);
    private static final long[] SINC = new long[4096];
    private static final Rand RND = new Rand();

    /** 0..1, замена Math.random() (её нет в CLDC). */
    public static float random() {
        return RND.nextFloat();
    }

    /** Бенчмарк: фиксированное зерно общего генератора. */
    static void seed(long s) {
        RND.setSeed(s);
    }

    /** Зерно для генератора отдельного объекта (моба) — от общего, чтобы засев общего фиксировал и его. */
    static long nextSeed() {
        return RND.nextLong();
    }

    /** элемент таблицы _sin (i — уже & 65535) */
    static float sinI(int i) {
        long c = SINC[i & 4095];
        if ((int) (c >>> 32) == (i | 0x10000)) return Float.intBitsToFloat((int) c);
        float v = (float) Math.sin(i / SIN_SCALE);
        SINC[i & 4095] = ((long) (i | 0x10000) << 32) | (Float.floatToIntBits(v) & 0xFFFFFFFFL);
        return v;
    }

    public static float sin(float a) {
        return sinI((int) (a * 10430.378f) & 65535);
    }

    public static float cos(float a) {
        return sinI((int) (a * 10430.378f + 16384f) & 65535);
    }

    public static int floor(float v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    public static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    public static float abs(float v) {
        return v < 0 ? -v : v;
    }

    public static float clamp(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }

    public static int clamp(int v, int a, int b) {
        return v < a ? a : (v > b ? b : v);
    }

    public static float sqrt(float v) {
        return (float) Math.sqrt(v);
    }

    /** atan2 в радианах. */
    public static float atan2(float y, float x) {
        if (x == 0f) {
            if (y > 0f) return PI / 2;
            if (y < 0f) return -PI / 2;
            return 0f;
        }
        float a;
        float z = y / x;
        if (abs(z) < 1f) {
            a = z / (1f + 0.28f * z * z);
            if (x < 0f) return y < 0f ? a - PI : a + PI;
            return a;
        }
        a = PI / 2 - z / (z * z + 0.28f);
        return y < 0f ? a - PI : a;
    }

    /** Замена Random.nextGaussian (нет в CLDC): сумма 12 равномерных минус 6, σ = 1. */
    public static float gaussian(java.util.Random r) {
        float s = 0;
        for (int i = 0; i < 12; i++) s += r.nextFloat();
        return s - 6f;
    }

    public static float wrapDegrees(float a) {
        a %= 360f;
        if (a >= 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    /** x^y для x > 0 (нет Math.pow в CLDC): exp(y * ln x). */
    public static float pow(float x, float y) {
        if (x <= 0) return 0;
        return (float) exp(y * ln(x));
    }

    static double ln(double x) {
        int e = 0;
        while (x > 2) { x /= 2; e++; }
        while (x < 0.5) { x *= 2; e--; }
        double z = (x - 1) / (x + 1), z2 = z * z, t = z, s = 0;
        for (int i = 1; i < 40; i += 2) { s += t / i; t *= z2; }
        return 2 * s + e * 0.6931471805599453;
    }

    static double exp(double x) {
        int n = (int) Math.floor(x / 0.6931471805599453);
        double r = x - n * 0.6931471805599453, t = 1, s = 1;
        for (int i = 1; i < 20; i++) { t *= r / i; s += t; }
        while (n > 0) { s *= 2; n--; }
        while (n < 0) { s /= 2; n++; }
        return s;
    }
}
