**Проект MinecraftJ2ME_PE. Продолжаем оптимизацию среднего FPS. Замеряем удалённо на Nokia E6-00 по Bluetooth.**

Сначала прочитай память проекта: `project_target_device_k800i`, `project_e6_link`, `project_benchmark`, `feedback_default_settings_human_only`, `feedback_clean_debug_props`, `project_memory_heap`.

**Цель:** поднять средний FPS. Целевой телефон — Sony Ericsson K800i (ARM9 без FPU, программный M3G), его у меня нет. Проверяем на Nokia E6-00 (S60 5.3, Java 2.3.24, экран 640×480, M3G 1.1 `04_wk49`).

FPS на E6 до оптимизаций:

| Настройки | Средний FPS |
|---|---|
| High, 4 чанка | 5 |
| High, 1 чанк | 10 |
| Low, 1 чанк | 12 |

Стандартные настройки (High + 4 чанка) в `Options.java` не трогать.

**Связь с E6 уже работает:**
- Мост на этом телефоне — приложение McjLink (`/sdcard/IDE_Logic/McjLink`). Управление — утилита `~/mcjlink/e6`: `status`, `connect`, `send '…'`, `wait 'regex' сек`, `log N`, `push`. Лог E6 пишется в `/sdcard/Android/data/com.xaview.mcjlink/files/e6.log`.
- На E6 установлена и запущена отладочная сборка «MCJ Link», в главном меню, внизу слева `link: connected`.
- Команды игре: `bench N`, `create папка сид`, `load папка`, `quit`, `gfx 0..2`, `view 1..4`, `prof 0|1` (строки PROF раз в 5 с), `status`, `mem`, `down/up/tap код`, `sound 0|1`, `third 0|1`, `paint 0|1`.
- Команда `exp <буквы>` отключает части кадра: `f` — без flush, `o` — без OVERWRITE, `h` — без HUD, `k` — без неба, `e` — без сущностей, `n` — без мира, `s` — логи SPIKE.
- Для бенчмарка при текущих настройках (а не High + 4): `send 'set mcj.benchkeep 1'`.
- Новая сборка для E6: touch исходников → `ide_build` (открыт проект MinecraftJ2ME_PE) → `~/mcjlink/mkjar.py nopush` → `~/mcjlink/e6 push`. Дальше я ставлю jar на E6 сам — сборок поменьше, эксперименты лучше делать переключателями.

**Что уже измерено на E6:**
- В меню `flush` (`flushGraphics`) около 62 мс.
- В игре на каждом кадре: `flush` около 47 мс и `releaseTarget` около 16 мс.
- При движении перестройка мешей (`upd`) 30–55 мс.
- В тяжёлом бенче тик около 50 мс (сущности и AI).
- Бенчмарк High + 4 — 1,9 FPS.

**Уже сделано в коде:**
- подсказка OVERWRITE в `bindTarget` (`MC.bindWorld`);
- на Low выключена перспективная коррекция мира (`LevelRenderer.setPerspective`);
- при «Show FPS» показывается разбивка кадра `3d/hud/fl/tk/upd`;
- режим `paint` (кадр в `Canvas.paint` + `serviceRepaints` вместо буфера GameCanvas) — написан, на E6 ещё не проверен.

**Следующие шаги:**
1. Сравнить на E6 (`prof 1`, стоя в мире и в бенче): `paint 0` против `paint 1`, а также `exp o`, `exp k`, `exp e`, `exp h`, `exp f`. Понять, из чего складываются flush и releaseTarget.
2. Взяться за самое дорогое. Кандидаты: путь вывода кадра, перестройка мешей (tessellate на E6 очень медленная), тики сущностей.
3. Отдельно проверить: хеш бенча на E6 — `27d51c39`, в J2ME Loader — `6b0404a7`. Подозрение на `Math.sin` другой виртуальной машины. Тогда генерация мира на E6 может отличаться от MCPE 0.6.1 на других платформах. Сравнить миры одного сида с E6 и из J2ME Loader.
4. После изменений в J2ME Loader проверять, что хеш бенча остался 6b0404a7. Последние правки там ещё не прогонялись.

**После проверок в J2ME Loader** удалять `mcj.*` из `config.json` и тестовые миры memt/memc.
