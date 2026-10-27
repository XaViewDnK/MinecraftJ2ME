/** Отладка памяти: дамп хипа ART. Грузится только по имени из MemDbg — на телефонах без android.os.Debug не трогается. */
final class MemHprof implements Runnable {
    static String path;

    public void run() {
        try {
            android.os.Debug.dumpHprofData(path);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e.toString());
        }
    }
}
