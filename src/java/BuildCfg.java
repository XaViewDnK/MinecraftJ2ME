/**
 * Константы варианта сборки IDE. Это файл варианта Debug; в Release его подменяет
 * src/variants/Release/BuildCfg.java (IDE-Logic берёт исходники варианта вместо одноимённых из src/java).
 */
final class BuildCfg {
    /** Отладка: связь MCJ Link, свойства mcj.* (бенчмарк, mcj.create/world/wsum, mcj.mem…). */
    static final boolean DEBUG = true;
}
