import javax.microedition.lcdui.*;
import javax.microedition.lcdui.game.GameCanvas;
import javax.microedition.lcdui.game.Sprite;
import javax.microedition.m3g.*;

/** Главный холст: цикл игры, ввод, интерфейс в игре, отрисовка мира. */
public final class MC extends GameCanvas implements Runnable, CommandListener {
    public static MC instance;
    final MinecraftMIDlet midlet;
    public int W, H;
    volatile boolean running = true;
    Thread thread;

    public Screen screen;
    public Level level;
    public Player player;
    public LevelRenderer lr;
    public EntityRenderer er;
    public final Particles particles = new Particles();
    public Gui gui;

    Graphics3D g3d;
    final Camera camera = new Camera();
    final Transform camT = new Transform();
    final Background background = new Background();
    float partial;
    int fps, frames;
    long fpsTime;
    long ticks;
    boolean initDone;
    String loadingText = "";
    int loadingPct = -1;

    public MC(MinecraftMIDlet m) {
        super(false);
        midlet = m;
        instance = this;
        setFullScreenMode(true);
    }

    public void start() {
        thread = new Thread(this);
        thread.start();
    }

    // ---- ввод (с потока UI в очередь) ----

    private static final int EVQ = 256;
    private final int[] evq = new int[4 * EVQ];
    private int evCount;

    /** type: 0 касание, 1 сдвиг, 2 отпускание (id — номер пальца), 3/4 клавиша, 5 размер. */
    void pushEvent(int type, int x, int y, int id) {
        synchronized (evq) {
            if (evCount < EVQ) {
                evq[evCount * 4] = type;
                evq[evCount * 4 + 1] = x;
                evq[evCount * 4 + 2] = y;
                evq[evCount * 4 + 3] = id;
                evCount++;
            }
        }
    }

    /**
     * Мультитач. Nokia Asha — API com.nokia.mid.ui.multipointtouch (Multitouch.java; события холста тогда не нужны);
     * Symbian^3 и J2ME Loader — атрибут Nokia-UI-Enhancement: EnableMultiPointTouchEvents, номер пальца в свойстве
     * com.nokia.pointer.number внутри pointerPressed/Dragged/Released. Без поддержки — один палец с номером 0.
     */
    boolean mptListener;

    private static int pointerNumber() {
        String n = System.getProperty("com.nokia.pointer.number");
        if (n == null) return 0;
        try {
            return Integer.parseInt(n.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    protected void pointerPressed(int x, int y) { if (!mptListener) pushEvent(0, x, y, pointerNumber()); }
    protected void pointerDragged(int x, int y) { if (!mptListener) pushEvent(1, x, y, pointerNumber()); }
    protected void pointerReleased(int x, int y) { if (!mptListener) pushEvent(2, x, y, pointerNumber()); }
    protected void keyPressed(int k) { pushEvent(3, k, 0, 0); }
    protected void keyReleased(int k) { pushEvent(4, k, 0, 0); }

    protected void sizeChanged(int w, int h) {
        pushEvent(5, w, h, 0);
    }

    // ---- Custom: поворот экрана (у J2ME Loader нет API ориентации — кадр рисуется в буфер и выводится повёрнутым) ----

    /** размер холста устройства; W x H — логический размер кадра */
    int devW, devH;
    /** кадр повёрнут на 90° по часовой */
    boolean rotated;
    private Image rotBuf;
    private Graphics rotG;

    /** Пересчитать поворот по Options.orientation и размеру холста. */
    void applyOrientation(int dw, int dh) {
        devW = dw;
        devH = dh;
        int o = Options.orientation;
        boolean r = (o == 1 && dh > dw) || (o == 2 && dw > dh);
        int w = r ? dh : dw, h = r ? dw : dh;
        rotated = r;
        if (!r) {
            rotBuf = null;
            rotG = null;
        } else if (rotBuf == null || rotBuf.getWidth() != w || rotBuf.getHeight() != h) {
            rotBuf = null;
            rotBuf = Image.createImage(w, h);
            rotG = rotBuf.getGraphics();
            if (MemDbg.on) MemDbg.img(rotBuf, "MC.rotBuf");
        }
        applySize(w, h);
    }

    void applyOrientation() {
        applyOrientation(devW, devH);
    }

    /** Graphics кадра: холст или буфер поворота. */
    private Graphics frameG(Graphics g) {
        return rotated ? rotG : g;
    }

    /** Вывести кадр: буфер поворота — на холст с поворотом, затем flushGraphics. */
    private void present(Graphics g) {
        if (rotated && MemDbg.on) MemDbg.drawn(rotBuf);
        if (rotated) g.drawRegion(rotBuf, 0, 0, W, H, Sprite.TRANS_ROT90, 0, 0, Graphics.TOP | Graphics.LEFT);
        flushGraphics();
    }

    /** Масштаб интерфейса: крупнее оригинала (Minecraft::setSize даёт 2 при ширине 400..799). */
    static float guiScale(int w, int h) {
        float k = w >= h ? Math.min(w / 240f, h / 160f) : Math.min(w / 180f, h / 240f);
        k = (int) (k * 4) / 4f;
        return k < 1 ? 1 : k;
    }

    private void applySize(int w, int h) {
        W = w;
        H = h;
        if (!initDone) return;
        R.setScale(guiScale(W, H), W, H);
        gui.layout(W, H);
        if (screen != null) screen.setSize(R.gw, R.gh);
    }

    private final int[] evLocal = new int[4 * EVQ];

    private void processEvents() {
        int n;
        synchronized (evq) {
            n = evCount;
            System.arraycopy(evq, 0, evLocal, 0, n * 4);
            evCount = 0;
        }
        for (int i = 0; i < n; i++) {
            int t = evLocal[i * 4], x = evLocal[i * 4 + 1], y = evLocal[i * 4 + 2], id = evLocal[i * 4 + 3];
            if (t == 3) { keyDown(x); continue; }
            if (t == 4) { keyUp(x); continue; }
            if (t == 5) { applyOrientation(x, y); continue; }
            if (rotated) {
                // обратно к TRANS_ROT90: точка кадра (sx, sy) лежит на холсте в (H - 1 - sy, sx)
                int lx = y, ly = H - 1 - x;
                x = lx;
                y = ly;
            }
            // первое касание включает сенсорный режим до выхода из игры
            if (t == 0 && !touchUI) touchUI = true;
            int k = ptrIndex(id, t == 0);
            if (k < 0) continue;
            if (t == 0) {
                ptrX[k] = x;
                ptrY[k] = y;
                ptrJust[k] = true;
                // в меню работает один палец — первый; остальные игнорируются до его отпускания
                if (screen != null) {
                    ptrRole[k] = screenPointerDown() ? P_NONE : P_SCREEN;
                    if (ptrRole[k] == P_SCREEN) screen.pointerPressed(x, y);
                } else if (level != null) gamePress(k, x, y);
                continue;
            }
            int role = ptrRole[k];
            ptrX[k] = x;
            ptrY[k] = y;
            if (t == 2) ptrId[k] = -1;
            if (role == P_SCREEN && screen != null) {
                if (t == 1) screen.pointerDragged(x, y);
                else screen.pointerReleased(x, y);
            } else if (role != P_SCREEN && screen == null && level != null) {
                if (t == 1) gameDrag(role, x, y);
                else gameRelease(role, x, y);
            }
        }
    }

    // ---- пальцы (Multitouch.cpp): номер пальца -> точка и роль, назначенная по месту касания ----

    static final int P_NONE = 0, P_SCREEN = 1, P_DPAD = 2, P_WORLD = 3, P_PAUSE = 4, MAX_PTR = 10;
    final int[] ptrId = new int[MAX_PTR], ptrX = new int[MAX_PTR], ptrY = new int[MAX_PTR], ptrRole = new int[MAX_PTR];
    final boolean[] ptrJust = new boolean[MAX_PTR];
    { for (int i = 0; i < MAX_PTR; i++) ptrId[i] = -1; }

    /** Ячейка пальца id; при касании занимается свободная (повторное касание того же номера — сброс прежнего). */
    private int ptrIndex(int id, boolean press) {
        for (int i = 0; i < MAX_PTR; i++) if (ptrId[i] == id) {
            if (press && ptrRole[i] != P_NONE) {
                // потерянное отпускание: закрыть прежнюю роль, как будто палец подняли
                int r = ptrRole[i];
                ptrId[i] = -1;
                if (r == P_SCREEN && screen != null) screen.pointerReleased(ptrX[i], ptrY[i]);
                else if (screen == null && level != null) gameRelease(r, ptrX[i], ptrY[i]);
                ptrId[i] = id;
            }
            return i;
        }
        if (!press) return -1;
        for (int i = 0; i < MAX_PTR; i++) if (ptrId[i] < 0) {
            ptrId[i] = id;
            ptrRole[i] = P_NONE;
            return i;
        }
        return -1;
    }

    private boolean screenPointerDown() {
        for (int i = 0; i < MAX_PTR; i++) if (ptrId[i] >= 0 && ptrRole[i] == P_SCREEN) return true;
        return false;
    }

    /** Палец с ролью нажат (крестовина, пауза). */
    boolean pointerDown(int role) {
        for (int i = 0; i < MAX_PTR; i++) if (ptrId[i] >= 0 && ptrRole[i] == role) return true;
        return false;
    }

    // ---- клавиши ----

    /**
     * Сенсорный режим: при старте выключен (только клавиши, прицел в центре, без крестовины и паузы),
     * первое касание включает его до выхода из игры; клавиши работают в обоих режимах.
     */
    public boolean touchUI;

    /** Действия удерживаемых клавиш (KeyboardInput / XperiaPlayInput: ходьба, прыжок, копание, взгляд). */
    static final int A_FWD = 0, A_BACK = 1, A_LEFT = 2, A_RIGHT = 3, A_JUMP = 4, A_JUMP2 = 5, A_DESTROY = 6, A_USE = 7,
            A_LOOKUP = 8, A_LOOKDOWN = 9, A_TURNL = 10, A_TURNR = 11, A_SNEAK = 12, A_N = 13;
    final boolean[] act = new boolean[A_N];
    private final long[] actTime = new long[A_N];
    /** Приседание переключается клавишей * (как сенсорная кнопка приседания Xperia Play). */
    boolean keySneak;

    private int gameActionOf(int k) {
        if (k >= 0) return 0;
        // джойстик: стандартные коды и getGameAction (цифры сюда не попадают — у них своя роль)
        switch (k) {
            case -1: return UP;
            case -2: return DOWN;
            case -3: return LEFT;
            case -4: return RIGHT;
            case -5: return FIRE;
        }
        try {
            return getGameAction(k);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static boolean isBackKey(int k) {
        return k == -6 || k == -7 || k == -8 || k == 8 || k == 27;
    }

    /** Направление в меню: джойстик или 2/8/4/6. */
    private static int navDir(int k, int ga) {
        int q = qwerty(k);
        if (ga == UP || k == KEY_NUM2 || q == 'w' || q == 'i') return Screen.UP;
        if (ga == DOWN || k == KEY_NUM8 || q == 's' || q == 'k') return Screen.DOWN;
        if (ga == LEFT || k == KEY_NUM4 || q == 'a' || q == 'j') return Screen.LEFT;
        if (ga == RIGHT || k == KEY_NUM6 || q == 'd' || q == 'l') return Screen.RIGHT;
        return -1;
    }

    private static boolean isOk(int k, int ga) {
        return ga == FIRE || k == KEY_NUM5 || k == 10 || k == 13 || k == ' ';
    }

    /** Русская ЙЦУКЕН -> латинская буква на той же клавише (QWERTY-телефоны шлют символ текущего языка ввода). */
    private static final String RU = "йцукенгшщзфывапролдячсмить", RU_LAT = "qwertyuiopasdfghjklzxcvbnm";

    /**
     * QWERTY-клавиатура (Nokia E6, E72, Asha...): код клавиши — её символ. Свести к строчной латинской букве
     * по месту на клавиатуре (регистр и русская раскладка не важны); не буква — 0.
     */
    static int qwerty(int k) {
        if (k >= 'A' && k <= 'Z') return k + 32;
        if (k >= 'a' && k <= 'z') return k;
        if (k >= 0x410 && k <= 0x42F) k += 32;
        int i = RU.indexOf(k);
        return i >= 0 ? RU_LAT.charAt(i) : 0;
    }

    /** Удерживаемые действия по Keys (остальные — разовые, в keyDown). */
    private static final int[] KEY_ACT = { A_FWD, A_BACK, A_LEFT, A_RIGHT, A_JUMP, A_DESTROY, A_USE, A_LOOKUP, A_LOOKDOWN,
            A_TURNL, A_TURNR, A_SNEAK, -1, -1, -1, -1, -1 };

    /** Раскладка в игре — Keys (настраивается в Options > Custom > Keys). */
    private static int actionOf(int k, int ga) {
        int b = Keys.find(k, ga);
        return b < 0 ? -1 : KEY_ACT[b];
    }

    private void releaseKeys() {
        for (int i = 0; i < A_N; i++) act[i] = false;
        keyDigging = false;
    }

    private void keyDown(int k) {
        int ga = gameActionOf(k);
        if (debug) Log.i("key " + k + " ga " + ga);
        // текст (табличка): символы и стирание
        if (screen != null && screen.wantsText()) {
            if (k == 8 || k == -8 || k == 10 || k == 13 || k >= 32) {
                screen.keyChar(k == -8 ? 8 : (k == 13 ? 10 : k));
                return;
            }
        }
        if (screen != null && screen.keyRaw(k, ga)) return;
        if (screen != null) {
            // Screen::keyPressed: фокус по кнопкам, OK — нажать, софт-клавиши / * — назад
            if (isBackKey(k) || k == KEY_STAR) screen.back();
            else if (navDir(k, ga) >= 0) screen.keyNav(navDir(k, ga));
            else if (isOk(k, ga)) screen.keyOk(true);
            return;
        }
        if (level == null || player == null) return;
        Inventory inv = player.inv;
        int n = gui.numSlots - 1;
        switch (Keys.find(k, ga)) {
            case Keys.PREV: // Gui::handleKeyPressed: предыдущий / следующий слот (по кругу, как колесо мыши)
                gui.selectSlot((inv.selected - 1 + n) % n);
                return;
            case Keys.NEXT:
                gui.selectSlot((inv.selected + 1) % n);
                return;
            case Keys.INV: // SCREEN_BLOCKSELECTION
                setScreen(new InventoryScreen());
                return;
            case Keys.CRAFT: // KeyboardInput KEY_CRAFT: Player::startCrafting 2x2 (в творческом крафта нет)
                if (!inv.creative && !player.isDead()) setScreen(new CraftScreen(CraftScreen.HAND));
                return;
            case Keys.PAUSE:
                setScreen(new PauseScreen());
                return;
            case -1: // Minecraft::tick (клавиши): Menu/«Назад» — пауза (софт-клавиши — если не назначены)
                if (isBackKey(k)) {
                    setScreen(new PauseScreen());
                    return;
                }
        }
        int a = actionOf(k, ga);
        if (a < 0 || act[a]) return;
        act[a] = true;
        actTime[a] = System.currentTimeMillis();
        if (a == A_SNEAK && !player.flying) keySneak = !keySneak;
        if (player.isDead()) return;
        // Minecraft::tick: keyDestroy — BAI_REMOVE | BAI_ATTACK, keyUse — BAI_BUILD | BAI_INTERACT (по центру экрана)
        if (a == A_DESTROY) keyDestroyPress();
        else if (a == A_USE) keyUsePress();
    }

    private void keyUp(int k) {
        int ga = gameActionOf(k);
        if (screen != null) {
            boolean text = screen.wantsText() && (k == 8 || k == -8 || k == 10 || k == 13 || k >= 32);
            if (!text && isOk(k, ga)) screen.keyOk(false);
            return;
        }
        int a = actionOf(k, ga);
        if (a < 0) return;
        act[a] = false;
        if (a == A_DESTROY && keyDigging) {
            // Minecraft::handleMouseDown(не нажато): GameMode::stopDestroyBlock
            keyDigging = false;
            if (!holding) {
                breakProgress = 0;
                hasTarget = false;
            }
        }
    }

    /** Поворот камеры клавишами 4/6 и 2/8: плавно, с разгоном за 0.4 с, скорость — от чувствительности. */
    private long lastTurn;

    private void keyTurn() {
        long now = System.currentTimeMillis();
        float dt = Math.min(0.1f, (now - lastTurn) / 1000f);
        lastTurn = now;
        if (level == null || player == null || screen != null || player.isDead()) return;
        float yaw = turnF(A_TURNR, now) - turnF(A_TURNL, now), pitch = turnF(A_LOOKDOWN, now) - turnF(A_LOOKUP, now);
        if (yaw == 0 && pitch == 0) return;
        float s = Options.sensitivity() / 0.493f;
        player.yRot += yaw * 150f * s * dt;
        player.xRot += (Options.invertY ? -pitch : pitch) * 110f * s * dt;
        player.xRot = Mth.clamp(player.xRot, -90f, 90f);
    }

    private float turnF(int a, long now) {
        if (!act[a]) return 0;
        return Math.min(1f, 0.35f + (now - actTime[a]) / 400f);
    }

    // ---- цикл ----

    public void run() {
        Graphics g = getGraphics();
        devW = W = getWidth();
        devH = H = getHeight();
        gameThread = Thread.currentThread();
        try {
            drawLoading(g, "Loading...", -1);
            R.load();
            // настройки — до разметки интерфейса: от них зависят поворот, размер и место кнопок управления
            Options.load();
            applyOrientation(devW, devH);
            R.setScale(guiScale(W, H), W, H);
            gui = new Gui(this);
            gui.layout(W, H);
            Lang.load();
            Sound.init();
            Runtime rt = Runtime.getRuntime();
            rt.gc();
            Log.i("BOOT heap " + ((rt.totalMemory() - rt.freeMemory()) >> 10) + "K/" + (rt.totalMemory() >> 10) + "K");
            // Nokia Asha: Multipoint Touch API (класс грузится по имени — на других телефонах его нет)
            try {
                Class.forName("com.nokia.mid.ui.multipointtouch.MultipointTouch");
                Class.forName("Multitouch").newInstance();
                Log.i("multitouch: Nokia Multipoint Touch API");
            } catch (Throwable t) {
                mptListener = false;
            }
            g3d = Graphics3D.getInstance();
            Storage.init();
            initDone = true;
            setScreen(new StartMenuScreen());
            if (MemDbg.on) MemDbg.mark("menu");
            debugScreen();
        } catch (Throwable t) {
            Log.e("init", t);
        }
        long last = System.currentTimeMillis();
        long acc = 0;
        fpsTime = last;
        while (running) {
            try {
                long now = System.currentTimeMillis();
                acc += now - last;
                last = now;
                if (acc > 500) acc = 500;
                processEvents();
                Net.pump(this);
                keyTurn();
                int n = 0;
                long t0 = System.currentTimeMillis();
                while (acc >= 50 && n < 6) {
                    long tt = System.currentTimeMillis();
                    tick();
                    if (Prof.on) Prof.tick(System.currentTimeMillis() - tt);
                    if (Bench.run != null) Bench.run.tickTime(System.currentTimeMillis() - tt);
                    acc -= 50;
                    n++;
                }
                long t1 = System.currentTimeMillis();
                partial = acc / 50f;
                render(frameG(g));
                long t2 = System.currentTimeMillis();
                present(g);
                long t3 = System.currentTimeMillis();
                tTick = (tTick * 7 + (t1 - t0) * 8) / 8;
                tFlush = (tFlush * 7 + (t3 - t2) * 8) / 8;
                if (Bench.run != null && level != null) Bench.run.frame(t3);
                if (Prof.on) {
                    if (t3 - t0 >= 60) Log.i("SPIKE frame " + (t3 - t0) + ": ticks " + n + "x " + (t1 - t0) + " upd " + lastFrame[0] + " bind " + lastFrame[1]
                            + " world " + lastFrame[2] + " ent " + lastFrame[3] + " rel " + lastFrame[4] + " render " + (t2 - t1) + " flush " + (t3 - t2));
                    Prof.acc[Prof.F_FLUSH] += t3 - t2;
                    Prof.frame(t3 - t1);
                    Prof.report(this);
                }
                frames++;
                if (now - fpsTime >= 1000) {
                    fps = frames;
                    frames = 0;
                    fpsTime = now;
                }
                // без ограничения кадров: только уступить потоки ввода и звука; свёрнутая игра не крутит цикл впустую
                if (isShown()) Thread.yield();
                else Thread.sleep(50);
            } catch (Throwable t) {
                // и запись в лог, и снижение дальности сами могут не найти памяти — поток игры не терять
                try {
                    Log.e("loop", t);
                    lowerView(t, "loop");
                } catch (Throwable e) {
                }
                try { Thread.sleep(200); } catch (InterruptedException e) { }
            }
        }
    }

    public void setScreen(Screen s) {
        if (screen != null) screen.removed();
        screen = s;
        if (s != null) s.init(this, R.gw, R.gh);
        resetTouch();
        releaseKeys();
    }

    /** SoundEngine::playUI. */
    public void playUI(String name) {
        Sound.play(name, 1f);
    }

    private void tick() {
        ticks++;
        // Minecraft::update: raknetInstance->runEvents — сеть до тика мира
        Net.tick(this);
        if (screen != null) screen.tick();
        if (level == null) return;
        gui.tick();
        if (MemDbg.on && ++memTicks == 200) MemDbg.mark("game");
        // в сетевой игре мир не встаёт на паузу (в 0.6.1 пауза не останавливает мир вовсе)
        if (screen != null && screen.pausesGame() && !Net.keepsRunning()) return;
        long p0 = Prof.t();
        handleTouchTick();
        keyTick();
        player.tick();
        if (Bench.run != null) Bench.run.tick();
        if (hand != null) hand.tick(player);
        tickSleep();
        long p1 = Prof.t();
        level.tick();
        long p2 = Prof.t();
        level.tickEntities();
        long p3 = Prof.t();
        particles.tick();
        // Minecraft::tick: Level::animateTick вокруг игрока (дым и пламя факелов, печи, лава, руда)
        particles.animateTick(Mth.floor(player.x), Mth.floor(player.y + Player.EYE), Mth.floor(player.z));
        long p4 = Prof.t();
        tickTakes();
        tickFogBr();
        if (!level.isClientSide) MobSpawner.tick(level, player, level.random, ticks);
        long p5 = Prof.t();
        if (!level.isClientSide) pickupItems();
        if (player.isDead() && screen == null) {
            if (level.isClientSide) netDied();
            setScreen(new DeathScreen());
        }
        long p6 = Prof.t();
        if (ticks % 6000 == 0 && !level.isClientSide) saveNow();
        if (Prof.on) {
            long p7 = Prof.t();
            if (p7 - p0 >= 25) Log.i("SPIKE tick " + (p7 - p0) + ": pl " + (p1 - p0) + " lvl " + (p2 - p1) + " ent " + (p3 - p2) + " part " + (p4 - p3)
                    + " spawn " + (p5 - p4) + " oth " + (p6 - p5) + " save " + (p7 - p6) + " ents " + level.entities.size());
            Prof.acc[Prof.T_PLAYER] += p1 - p0;
            Prof.acc[Prof.T_ENT] += p3 - p2;
            Prof.acc[Prof.T_PART] += p4 - p3;
            Prof.acc[Prof.T_SPAWN] += p5 - p4;
            Prof.acc[Prof.T_OTHER] += p6 - p5;
            Prof.acc[Prof.T_SAVE] += p7 - p6;
        }
        if (createCooldown > 0) createCooldown--;
        if (missTime > 0) missTime--;
        if (signs != null && ticks % 200 == 0) signs.forget(level);
        // пиксели текстур интерфейса, не нужные 20 с (всё нужное уже в кэшах кусков), — выгрузить
        if (ticks % 400 == 0) R.dropTextures(20000);
        if (ticks % 200 == 100) level.evictFar();
    }

    // ---- мир ----

    public void createWorld(String name, long seed, int gameType) {
        // пик памяти — генерация: пиксели текстур интерфейса пока не нужны
        R.dropTextures(0);
        if (MemDbg.on) MemDbg.mark("create");
        Graphics g = getGraphics();
        long g0 = System.currentTimeMillis();
        Level l;
        // нехватка памяти — дальность ниже и генерация заново (недостроенный мир — мусор вместе с кадром genLevel)
        while (true) {
            try {
                l = genLevel(name, seed, gameType, g);
                break;
            } catch (OutOfMemoryError e) {
                if (!lowerView(e, "gen")) throw e;
            }
        }
        if (MemDbg.on) MemDbg.mark("gendone");
        Player p = new Player(l, gameType == 1);
        p.respawn();
        long g1 = System.currentTimeMillis();
        drawLoading(g, "Saving chunks", 100);
        if (Storage.init()) saveWorld(l, p);
        if (MemDbg.on) MemDbg.mark("saved");
        Log.i("GEN " + (g1 - g0) + " ms, save " + (System.currentTimeMillis() - g1) + " ms, " + memInfo(l));
        startGame(l, p);
    }

    private Level genLevel(String name, long seed, int gameType, final Graphics fg) {
        Level l = new Level();
        if (MemDbg.on) MemDbg.mark("level");
        l.name = name;
        l.seed = seed;
        l.gameType = gameType;
        l.folder = Storage.makeFolderName(name);
        l.time = 0;
        Gen gen = new Gen(l, seed);
        if (MemDbg.on) MemDbg.mark("gen0");
        gen.generate(new Gen.Progress() {
            public void progress(String stage, int pct) {
                if (MemDbg.on && (pct == 45 || pct == 92 || pct == 100)) MemDbg.mark("gen" + pct);
                drawLoading(fg, "Building terrain", pct);
            }
        });
        return l;
    }

    public void loadWorld(String folder) {
        R.dropTextures(0);
        Graphics g = getGraphics();
        drawLoading(g, "Building terrain", -1);
        long t0 = System.currentTimeMillis();
        Level l;
        while (true) {
            try {
                l = Storage.load(folder);
                break;
            } catch (OutOfMemoryError e) {
                if (!lowerView(e, "load")) throw e;
                drawLoading(g, "Building terrain", -1);
            }
        }
        loadMs = System.currentTimeMillis() - t0;
        if (MemDbg.on) MemDbg.mark("loaded");
        if (l == null) {
            Log.last = "Can't load " + folder;
            setScreen(new SelectWorldScreen());
            return;
        }
        Player p = new Player(l, l.gameType == 1);
        if (l.playerTag != null) {
            p.load(l.playerTag);
            if (p.y < 0 || p.y > 140 || !Level.inside(Mth.floor(p.x), 1, Mth.floor(p.z))) p.respawn();
            else p.resetPos();
        } else p.respawn();
        startGame(l, p);
    }

    /** Storage.saveAll; нехватка памяти — дальность ниже и запись заново, на минимуме — как раньше: без записи. */
    private void saveWorld(Level l, Player p) {
        while (true) {
            try {
                Storage.saveAll(l, p);
                return;
            } catch (OutOfMemoryError e) {
                if (!lowerView(e, "save")) {
                    Log.e("save", e);
                    return;
                }
            }
        }
    }

    // ---- нехватка памяти: дальность прорисовки ниже на чанк вместо вылета ----

    /** на сколько чанков дальность снижена из-за нехватки памяти (для уведомления; сброс — игрок сам сменил дальность) */
    int viewCut;
    private Thread gameThread;

    /**
     * OutOfMemoryError на шаге загрузки или в игре: дальность на чанк ниже (и в сохранённых настройках), лишние
     * меши — сборщику мусора вместе с пулом частей. true — дальность снижена, шаг стоит повторить; false — не
     * нехватка памяти или дальность уже минимальная (память всё равно подчищена).
     */
    boolean lowerView(Throwable t, String where) {
        if (!(t instanceof OutOfMemoryError) || Thread.currentThread() != gameThread) return false;
        if (lr != null) lr.oomCleanup();
        int v = Options.viewChunks;
        boolean lower = v > Options.VIEW_MIN;
        if (lower) {
            Options.viewChunks = v - 1;
            viewCut++;
            applyOptions();
            if (lr != null && player != null) lr.updateRange(player.x, player.z);
        }
        // мира нет (генерация, загрузка) — меши прошлого мира больше не нужны
        if (level == null) {
            LevelRenderer.release();
            particles.level = null;
        }
        LevelRenderer.trimPool();
        R.dropTextures(0);
        Runtime.getRuntime().gc();
        if (MemDbg.on) MemDbg.sweepNow();
        if (!lower) return false;
        // раз памяти не хватило — не хватит и в следующий раз: дальность в настройки (бенчмарк свои не пишет)
        if (!Bench.on()) {
            try {
                Options.save();
            } catch (Throwable e) {
                Log.e("options save", e);
            }
        }
        int cut = viewCut;
        Log.i("OOM at " + where + ": view " + Options.viewChunks + " chunks (-" + cut + ")");
        if (gui != null) gui.notice("Render distance reduced by " + cut + (cut == 1 ? " chunk" : " chunks"));
        return true;
    }

    long loadMs;

    static String[] split(String s, char c) {
        java.util.Vector v = new java.util.Vector();
        int p = 0;
        for (int q; (q = s.indexOf(c, p)) >= 0; p = q + 1) v.addElement(s.substring(p, q));
        v.addElement(s.substring(p));
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }
    private int memTicks;

    /** занятый хип и размер слоёв мира (КБ) — для замеров памяти */
    static String memInfo(Level l) {
        Runtime r = Runtime.getRuntime();
        r.gc();
        return "heap " + ((r.totalMemory() - r.freeMemory()) >> 10) + "K/" + (r.totalMemory() >> 10) + "K, world blocks "
                + (l.blocks.bytes() >> 10) + "K data " + (l.data.bytes() >> 10) + "K sky " + (l.sky.bytes() >> 10) + "K blk "
                + (l.blk.bytes() >> 10) + "K, chunks out " + l.goneCount + " loaded back " + l.faults;
    }

    // ---- клиент сетевой игры (ClientSideNetworkHandler -> Minecraft) ----

    private boolean netWaking, diedSent;

    /** StartGamePacket: мир по семени хоста, временный (MultiPlayerLevel), игрок на позиции от хоста. */
    void netStartGame(NetClient c, long seed, int gameType) {
        Graphics g = getGraphics();
        Level l = new Level();
        l.name = "_LastJoinedServer";
        l.seed = seed;
        l.gameType = gameType;
        l.folder = null;
        l.isClientSide = true;
        R.dropTextures(0);
        final Graphics fg = g;
        new Gen(l, seed).generate(new Gen.Progress() {
            public void progress(String stage, int pct) {
                drawLoading(fg, "Building terrain", pct);
            }
        });
        // ChunkCache::postProcess -> clearUpdateMap: своё поколение — без отметок изменений
        for (int i = 0; i < l.updateMap.length; i++) l.updateMap[i] = null;
        Player p = new Player(l, gameType == 1);
        l.player = p;
        l.spawnX = Mth.floor(c.sx);
        l.spawnY = Mth.floor(c.sy);
        l.spawnZ = Mth.floor(c.sz);
        player = p;
        c.levelGenerated(l);
        drawLoading(g, "Building terrain", -1);
    }

    void netChunkChanged(int cx, int cz, boolean playing) {
        if (!playing || level == null) return;
        level.recalcAllLight();
        if (lr != null) lr.lightChanged(cx * 16 - 1, 0, cz * 16 - 1, cx * 16 + 16, 127, cz * 16 + 16);
    }

    /** все чанки получены: свет и вход в мир */
    void netEnterGame(NetClient c) {
        Level l = c.level;
        drawLoading(getGraphics(), "Preparing", -1);
        l.recalcAllLight();
        diedSent = false;
        startGame(l, player);
        Log.i("NET joined, entities " + l.entities.size());
    }

    /** хост уложил нас (SetEntityData: флаг сна и кровать) */
    void netSleep(int x, int y, int z) {
        Player p = player;
        if (p == null || level == null || p.sleeping) return;
        int dir = level.getData(x, y, z) & 3;
        float xo = 0.5f, zo = 0.5f;
        if (dir == 0) zo = 0.9f;
        else if (dir == 2) zo = 0.1f;
        else if (dir == 1) xo = 0.1f;
        else xo = 0.9f;
        p.setPos(x + xo, y + 15f / 16f - 0.2f, z + zo);
        p.xd = p.yd = p.zd = 0;
        p.sleeping = true;
        p.sleepTimer = 0;
        p.bedX = x;
        p.bedY = y;
        p.bedZ = z;
        setScreen(new InBedScreen());
    }

    /** AnimatePacket(WAKE_UP) или флаг сна снят */
    void netWake() {
        if (player == null || !player.sleeping) return;
        netWaking = true;
        try {
            stopSleep(false);
        } finally {
            netWaking = false;
        }
    }

    /** LocalPlayer::die у клиента: вещи — хосту на выброс */
    private void netDied() {
        if (diedSent || Net.client == null) return;
        diedSent = true;
        Net.client.died();
        Inventory inv = player.inv;
        if (!inv.creative) for (int i = 0; i < Inventory.SLOTS; i++) { inv.id[i] = 0; inv.count[i] = 0; inv.aux[i] = 0; }
        for (int i = 0; i < 4; i++) { player.armor[i] = 0; player.armorDmg[i] = 0; }
    }

    /** Minecraft::respawnPlayer: RespawnPacket */
    void netRespawned() {
        diedSent = false;
        if (Net.client != null) Net.client.respawned();
        if (Net.server != null) Net.server.localRespawn();
    }

    /** PauseScreen «Quit and copy map»: RenameMPLevelScreen — мир хоста сохраняется под новым именем. */
    void quitAndCopyMap() {
        Net.stop();
        final Level l = level;
        final Player p = player;
        editText("Save world as", "", 64, new TextResult() {
            public void done(String text) {
                String name = text == null ? "" : text.trim();
                StringBuffer sb = new StringBuffer();
                for (int i = 0; i < name.length(); i++) if ("/\n\r\t\f`?*\\<>|\":".indexOf(name.charAt(i)) < 0) sb.append(name.charAt(i));
                name = sb.toString();
                if (text != null) {
                    if (name.length() == 0) name = "saved_world";
                    l.name = name;
                    l.folder = Storage.makeFolderName(name);
                    l.isClientSide = false;
                    for (int i = l.entities.size() - 1; i >= 0; i--) if (l.entities.elementAt(i) instanceof RemotePlayer) l.entities.removeElementAt(i);
                    if (Storage.init()) saveWorld(l, p);
                }
                level = null;
                player = null;
                lr = null;
                releaseWorld();
                setScreen(new StartMenuScreen());
            }
        });
    }

    void startGame(Level l, Player p) {
        long t0 = System.currentTimeMillis();
        l.clearPendingTicks();
        level = l;
        player = p;
        l.player = p;
        l.updateSkyDarken();
        // дальние сохранённые чанки — из памяти до постройки мешей (пик подготовки)
        l.evictFar();
        lr = new LevelRenderer(l, R.terrain2D);
        if (MemDbg.on) MemDbg.mark("lr");
        takes.removeAllElements();
        // рендереры сущностей, руки и табличек живут между мирами: их M3G-объекты не должны становиться мусором
        // (в JL-Mod финализация M3G-объектов не синхронизирована с потоком игры, см. DynMesh)
        if (hand != null) hand.reset();
        if (signs != null) signs.forget(l);
        if (er == null) er = new EntityRenderer(lr);
        particles.setLevel(l, lr);
        applyOptions();
        lr.updateRange(p.x, p.z);
        // построить ближние секции до входа в игру
        Graphics g = getGraphics();
        int total = lr.pendingCount();
        int guard = 0;
        while (lr.hasPending() && guard++ < 400) {
            try {
                lr.updateDirty(p.x, p.y + Player.EYE, p.z, 60);
            } catch (OutOfMemoryError e) {
                // на минимальной дальности — в игру с тем, что успело построиться (остальное достроится в игре)
                if (!lowerView(e, "prepare")) break;
                total = lr.pendingCount();
                continue;
            }
            int done = total - lr.pendingCount();
            drawLoading(g, "Preparing", -1);
            if (done > total * 3 / 4) break;
        }
        Log.i("LOAD read " + loadMs + " ms, prepare " + (System.currentTimeMillis() - t0) + " ms, left " + lr.pendingCount() + "/" + total
                + ", " + memInfo(l));
        loadMs = 0;
        // фон-«земля» экранов загрузки в игре не нужен до паузы
        R.dropDirt();
        if (MemDbg.on) MemDbg.mark("prepared");
        memTicks = 0;
        setScreen(null);
        if (p.loadSleeping) {
            p.loadSleeping = false;
            if (level.getTile(p.bedX, p.bedY, p.bedZ) == Tile.BED) startSleep(p.bedX, p.bedY, p.bedZ, true);
        }
        if (System.getProperty("mcj.test") != null) spawnTestMobs();
        // Minecraft::hostMultiplayer: открытый мир — сервер для сети
        if (!l.isClientSide && !Bench.on()) Net.host(this, l, p);
    }

    /** Отладка: mcj.world=<папка> — сразу войти в мир, mcj.screen=<имя> — открыть экран (проверка раскладок). */
    private void debugScreen() {
        String w = System.getProperty("mcj.world"), sc = System.getProperty("mcj.screen");
        // отладка: mcj.create=<папка>:<сид> — сразу сгенерировать мир выживания (замер генерации)
        String cr = System.getProperty("mcj.create");
        // отладка: mcj.bench=<прогонов> — бенчмарк (Bench)
        if (System.getProperty("mcj.bench") != null) {
            Bench.start(this);
            return;
        }
        if (cr != null && cr.indexOf(':') > 0) {
            // mcj.create=<папка>:<сид>[:1] — :1 творческий режим
            String[] f = split(cr, ':');
            createWorld(f[0], Long.parseLong(f[1]), f.length > 2 ? Integer.parseInt(f[2]) : 0);
            return;
        }
        if (sc == null && w == null) return;
        if (w != null) loadWorld(w);
        // отладка: mcj.pos=x,y,z,yaw,pitch — поставить игрока (осмотр места с багом)
        String ps = System.getProperty("mcj.pos");
        if (level != null && ps != null) {
            float[] v = new float[5];
            for (int i = 0, p = 0; i < 5 && p <= ps.length(); i++) {
                int e = ps.indexOf(',', p);
                if (e < 0) e = ps.length();
                if (e > p) v[i] = Float.parseFloat(ps.substring(p, e).trim());
                p = e + 1;
            }
            player.setPos(v[0], v[1], v[2]);
            player.yRot = player.yRotO = v[3];
            player.xRot = player.xRotO = v[4];
        }
        if (level != null && System.getProperty("mcj.time") != null) {
            // отладка: время суток (тики от начала дня)
            level.time = level.time - level.time % Level.TICKS_PER_DAY + Integer.parseInt(System.getProperty("mcj.time"));
            level.updateSkyDarken();
        }
        if (level != null && System.getProperty("mcj.night") != null) {
            level.time = level.time - level.time % Level.TICKS_PER_DAY + 14400;
            level.updateSkyDarken();
        }
        if (sc == null) return;
        Screen s = null;
        if (sc.equals("worlds")) s = new SelectWorldScreen();
        else if (sc.equals("create")) s = new CreateWorldScreen();
        else if (sc.equals("options")) s = new OptionsScreen();
        else if (sc.equals("join")) s = new JoinGameScreen();
        else if (sc.equals("bt")) s = new BtJoinScreen();
        else if (level != null) {
            if (sc.equals("pause")) s = new PauseScreen();
            else if (sc.equals("inv")) s = new InventoryScreen();
            else if (sc.equals("craft")) s = new CraftScreen(CraftScreen.WORKBENCH);
            else if (sc.equals("armor")) s = new ArmorScreen();
            else if (sc.equals("chest")) s = new ChestScreen(new TileEntity("Chest", 0, 0, 0));
            else if (sc.equals("furnace")) s = new FurnaceScreen(new TileEntity("Furnace", 0, 0, 0));
            else if (sc.equals("sign")) s = new TextEditScreen(new TileEntity("Sign", 0, 0, 0));
            else if (sc.equals("death")) s = new DeathScreen();
            else if (sc.equals("delete")) s = new ConfirmScreen("Are you sure you want to delete this world?", "'Test' will be lost forever!", "Delete", "Cancel") {
                protected void postResult(boolean ok) { mc().setScreen(null); }
            };
        }
        if (s != null) setScreen(s);
    }

    MC mc() {
        return this;
    }

    /** Отладка: по мобу каждого вида перед игроком. */
    private void spawnTestMobs() {
        int[] t = { Mobs.PIG, Mobs.COW, Mobs.SHEEP, Mobs.CHICKEN, Mobs.ZOMBIE, Mobs.SKELETON, Mobs.CREEPER, Mobs.SPIDER, Mobs.PIG_ZOMBIE };
        float fx = -Mth.sin(player.yRot * Mth.DEG), fz = Mth.cos(player.yRot * Mth.DEG);
        float rx = -fz, rz = fx;
        for (int i = 0; i < t.length; i++) {
            Mob m = (Mob) Mobs.create(t[i], level);
            float off = (i - 4) * 1.6f;
            float x = player.x + fx * 6 + rx * off, z = player.z + fz * 6 + rz * off;
            m.setPos(x, level.getTopSolid(Mth.floor(x), Mth.floor(z)), z);
            m.yRot = player.yRot + 180f;
            m.bodyRot = m.bodyRotO = m.yRot;
            boolean ai = "ai".equals(System.getProperty("mcj.test"));
            if (m instanceof Monster) ((Monster) m).noAi = !ai;
            if (m instanceof Animal) ((Animal) m).noAi = !ai;
            level.addEntity(m);
        }
        // отладка: в выживании — набор для проверки брони и крафта
        if (!player.inv.creative) {
            int[] kit = { Tile.PLANKS, Tile.COBBLE, 280, 265, 306, 307, 308, 309, 310, 299, Tile.CHEST, Tile.FURNACE, 323, Tile.WORKBENCH, 263, Tile.IRON_ORE, Tile.LOG };
            for (int i = 0; i < kit.length; i++) player.inv.add(kit[i], Item.isArmor(kit[i]) ? 1 : 32, kit[i] == 310 ? 200 : 0);
        }
        for (int i = 0; i < 4; i++) level.addEntity(new ItemEntity(level, player.x + fx * 3 + rx * (i - 1.5f), player.y + 1, player.z + fz * 3 + rz * (i - 1.5f), i == 0 ? Tile.GRASS : (i == 1 ? 264 : (i == 2 ? Tile.TORCH : 276)), 1, 0));
    }

    public void applyOptions() {
        if (lr != null) lr.renderDist = Options.renderDistance();
        Tile.fastLeaves = !Options.fancy();
    }

    public void saveNow() {
        if (level == null || level.folder == null) return;
        if (!Storage.init()) return;
        long t0 = System.currentTimeMillis();
        saveWorld(level, player);
        Log.i("SAVE " + (System.currentTimeMillis() - t0) + " ms");
    }

    public void quitToTitle() {
        boolean client = level != null && level.isClientSide;
        Net.stop();
        if (!client) saveNow();
        level = null;
        player = null;
        lr = null;
        releaseWorld();
        setScreen(new StartMenuScreen());
    }

    /** Мир закрыт: его меши — в пул, частицы его не держат (иначе прошлый мир живёт до входа в следующий). */
    private void releaseWorld() {
        LevelRenderer.release();
        particles.level = null;
    }

    private void pickupItems() {
        java.util.Vector v = level.entities;
        Player p = player;
        if (p.isDead()) return;
        for (int i = 0; i < v.size(); i++) {
            Object o = v.elementAt(i);
            if (!(o instanceof ItemEntity)) continue;
            ItemEntity e = (ItemEntity) o;
            // Player::aiStep: касание с рамкой игрока, расширенной на 1 по x и z
            float hw = p.bbW / 2 + 1 + e.bbW / 2;
            if (Math.abs(e.x - p.x) < hw && Math.abs(e.z - p.z) < hw && e.y + e.bbH > p.y && e.y < p.y + p.bbH) e.playerTouch(p);
        }
    }

    /** Анимации подбора (TakeAnimationParticle): копия предмета (1 шт.) и счётчик жизни. */
    private final java.util.Vector takes = new java.util.Vector();

    void takeAnim(ItemEntity e) {
        ItemEntity c = new ItemEntity(level, e.x, e.y + e.bbH / 2, e.z, e.item, 1, e.aux);
        c.setPos(e.x, e.y, e.z);
        c.xo = e.x; c.yo = e.y; c.zo = e.z;
        c.pickupDelay = 0; // здесь — номер тика анимации
        takes.addElement(c);
    }

    private void tickTakes() {
        for (int i = takes.size() - 1; i >= 0; i--) {
            ItemEntity c = (ItemEntity) takes.elementAt(i);
            if (++c.pickupDelay >= 3) takes.removeElementAt(i);
        }
    }

    public void playSound(String name, float volume) {
        if (name != null) Sound.play(name, volume);
    }

    /** LevelRenderer::playSound + SoundEngine::play: дальше 16 блоков не слышно, громкость * (1.1 - d/16). */
    public void playSoundAt(String name, float x, float y, float z, float volume) {
        if (name == null || player == null || volume <= 0) return;
        float dx = x - player.x, dy = y - (player.y + Player.EYE), dz = z - player.z;
        float d2 = dx * dx + dy * dy + dz * dz;
        float dd = volume > 1 ? 16 * volume : 16;
        if (d2 > dd * dd) return;
        float k = Mth.clamp(1.1f - Mth.sqrt(d2) / 16f, 0, 1);
        Sound.play(name, Math.min(1f, volume * k));
    }

    /** Звук блока в его центре: (громкость типа + 1) * mul (TileItem::useOn, GameMode::destroyBlock — /2). */
    void tileSound(String name, int id, int x, int y, int z, float mul) {
        playSoundAt(name, x + 0.5f, y + 0.5f, z + 0.5f, (Tile.soundVolume(id) + 1) * mul);
    }

    // ---- текстовый ввод ----

    public interface TextResult {
        void done(String text);
    }

    private TextBox textBox;
    private TextResult textResult;
    private final Command cmdOk = new Command("OK", Command.OK, 1);
    private final Command cmdCancel = new Command("Cancel", Command.CANCEL, 2);

    public void editText(String title, String value, int max, TextResult r) {
        textResult = r;
        textBox = new TextBox(title, value, max, TextField.ANY);
        textBox.addCommand(cmdOk);
        textBox.addCommand(cmdCancel);
        textBox.setCommandListener(this);
        Display.getDisplay(midlet).setCurrent(textBox);
    }

    public void commandAction(Command c, Displayable d) {
        if (d == textBox) {
            String v = c == cmdOk ? textBox.getString() : null;
            TextResult r = textResult;
            textBox = null;
            textResult = null;
            Display.getDisplay(midlet).setCurrent(this);
            if (r != null) r.done(v);
        }
    }

    // ---- касания в игре ----

    /** Палец обзора/постройки (TouchInputHolder: один, первый коснувшийся мира) — его точка и состояние. */
    int touchX, touchY, downX, downY;
    long downTime;
    boolean moved, holding, forceUse;
    /** TouchInputHolder::alpha и _canDestroy */
    float fbAlpha = -0.05f;
    boolean fbCanDestroy;
    int breakX, breakY, breakZ, breakFace;
    float breakProgress;
    int createCooldown, missTime, destroyTicks;
    boolean hasTarget;

    /** Палец на мире (поворот, копание, постройка). */
    boolean worldTouch() {
        return pointerDown(P_WORLD);
    }

    private void resetTouch() {
        // пальцы, оставшиеся на экране при смене экрана, больше ничего не делают до отпускания
        for (int i = 0; i < MAX_PTR; i++) ptrRole[i] = P_NONE;
        holding = false;
        moved = false;
        breakProgress = 0;
        hasTarget = false;
        if (gui != null) gui.releaseAll();
    }

    private void gamePress(int k, int x, int y) {
        if (gui.pauseAt(x, y)) { ptrRole[k] = P_PAUSE; return; }
        int hb = gui.slotAt(x, y);
        if (hb >= 0) {
            // Gui::handleClick: последний слот — выбор блоков
            if (hb == gui.numSlots - 1) {
                playUI("random.click");
                setScreen(new InventoryScreen());
            } else gui.selectSlot(hb);
            return;
        }
        if (gui.inDpad(x, y)) {
            ptrRole[k] = P_DPAD;
            return;
        }
        // второй палец на мире не поворачивает (TouchInputHolder берёт первый)
        if (worldTouch()) return;
        ptrRole[k] = P_WORLD;
        touchX = downX = x;
        touchY = downY = y;
        downTime = System.currentTimeMillis();
        moved = false;
        holding = false;
        updateSelect(x, y);
        // UnifiedTurnBuild: на ходу копать нельзя, но еду и лук использовать можно (canUseCarriedItemWhileMoving)
        int sel = player.inv.selId();
        forceUse = playerMoving() && (sel == 261 || (sel >= 256 && Item.food[sel] > 0));
        fbCanDestroy = !playerMoving() || forceUse;
    }

    int dragEvents;
    boolean debug = System.getProperty("mcj.debug") != null;

    private void gameDrag(int role, int x, int y) {
        dragEvents++;
        if (role != P_WORLD) return;
        int dx = x - touchX, dy = y - touchY;
        touchX = x;
        touchY = y;
        if (Math.abs(x - downX) > 8 * R.k || Math.abs(y - downY) > 8 * R.k) moved = true;
        // UnifiedTurnBuild: при удержании поворота нет, но натянутый лук целится пальцем
        if (holding && !(player.isUsingItem() && player.useItemId == 261)) return;
        // Options::sensitivity (0.3..0.9, по умолчанию ~0.49) поверх прежней скорости поворота
        float sens = 0.32f * Options.sensitivity() / 0.493f * 320f / Math.max(1, W / 2);
        player.yRot += dx * sens * 2f;
        player.xRot += (Options.invertY ? -dy : dy) * sens * 2f;
        player.xRot = Mth.clamp(player.xRot, -90f, 90f);
    }

    /** UnifiedTurnBuild::getSpeedSquared > 0.01 */
    private boolean playerMoving() {
        Player p = player;
        float dx = p.x - p.xo, dy = p.y - p.yo, dz = p.z - p.zo;
        return dx * dx + dy * dy + dz * dz > 0.01f;
    }

    private void gameRelease(int role, int x, int y) {
        if (role == P_WORLD) {
            touchX = x;
            touchY = y;
            updateSelect(x, y);
            // Minecraft::handleMouseDown(не нажато): отпустили — лук стреляет, еда отменяется
            if (player.isUsingItem()) player.releaseUsingItem();
            // TouchInputHolder: тап (быстрее 0.25 с без сдвига) — BAI_BUILD | BAI_ATTACK
            else if (!moved && !holding && System.currentTimeMillis() - downTime < 250) useAt(x, y);
            holding = false;
            breakProgress = 0;
            hasTarget = false;
        }
        if (role == P_PAUSE && gui.pauseAt(x, y)) {
            playUI("random.click");
            setScreen(new PauseScreen());
        }
    }

    // пальцы на крестовине для Gui.tickInput
    private final int[] dpX = new int[MAX_PTR], dpY = new int[MAX_PTR];
    private final boolean[] dpJust = new boolean[MAX_PTR];

    private void handleTouchTick() {
        Player p = player;
        p.sneaking = keySneak && !p.flying;
        if (p.flying) keySneak = false;
        int nd = 0;
        for (int i = 0; i < MAX_PTR; i++) {
            if (ptrId[i] >= 0 && ptrRole[i] == P_DPAD) {
                dpX[nd] = ptrX[i];
                dpY[nd] = ptrY[i];
                dpJust[nd++] = ptrJust[i];
            }
            ptrJust[i] = false;
        }
        gui.tickInput(p, nd, dpX, dpY, dpJust);
        // UnifiedTurnBuild: через 0.4 с без сдвига — удаление (если игрок не идёт), иначе — поворот
        if (worldTouch() && !moved && !holding && System.currentTimeMillis() - downTime >= 400) {
            if (!forceUse && playerMoving()) moved = true;
            else {
                holding = true;
                breakProgress = 0;
            }
        }
        if (holding) breakTick();
    }

    // ---- луч и взаимодействие ----

    private final float[] ray = new float[6];
    int hitX, hitY, hitZ, hitFace;
    float hitPX, hitPY, hitPZ;

    private void rayFor(int sx, int sy) {
        Player p = player;
        float fov = 70f * Mth.DEG;
        float t = (float) Math.tan(fov / 2);
        float aspect = (float) W / H;
        float vx = (2f * sx / W - 1f) * t * aspect, vy = (1f - 2f * sy / H) * t, vz = -1f;
        float b = -p.xRot * Mth.DEG, a = (180f - p.yRot) * Mth.DEG;
        float cb = Mth.cos(b), sb = Mth.sin(b);
        float y1 = vy * cb - vz * sb, z1 = vy * sb + vz * cb;
        float ca = Mth.cos(a), sa = Mth.sin(a);
        float x2 = vx * ca + z1 * sa, z2 = -vx * sa + z1 * ca;
        float len = Mth.sqrt(x2 * x2 + y1 * y1 + z2 * z2);
        ray[0] = p.x;
        ray[1] = p.y + Player.EYE;
        ray[2] = p.z;
        ray[3] = x2 / len;
        ray[4] = y1 / len;
        ray[5] = z2 / len;
    }

    /** Пересечение луча с блоками (DDA). */
    /**
     * GameRenderer::updateFreeformPickDirection: Level::clip на 6 блоков по лучу касания; если ничего и взгляд вниз
     * круче -0.7 — блок под ногами (до 2 вниз) с боковой гранью по направлению (помощь для постройки пола).
     */
    private boolean pick(int sx, int sy) {
        rayFor(sx, sy);
        float ox = ray[0], oy = ray[1], oz = ray[2], dx = ray[3], dy = ray[4], dz = ray[5];
        // по прицелу (клавиши) — GameRenderer::pick через player->pick(GameMode::getPickRange() = 5)
        float range = aimCenter ? 5f : Options.thirdPerson ? 12f : 6f;
        if (level.clip(ox, oy, oz, ox + dx * range, oy + dy * range, oz + dz * range, false, false)) {
            if (Options.thirdPerson && !aimCenter) {
                float ex = level.clipX - player.x, ey = level.clipY - (player.y + Player.EYE), ez = level.clipZ - player.z;
                if (ex * ex + ey * ey + ez * ez > range * range) return false;
            }
            setHit(level.clipFace);
            return true;
        }
        if (dy < -0.7f && level.clip(ox, oy, oz, ox, oy - 2f, oz, false, false)) {
            int f;
            if (Math.abs(dx) > Math.abs(dz)) f = dx < 0 ? 4 : 5;
            else f = dz < 0 ? 2 : 3;
            setHit(f);
            return true;
        }
        return false;
    }

    private void setHit(int f) {
        hitX = level.clipX; hitY = level.clipY; hitZ = level.clipZ;
        hitFace = f;
        hitPX = level.clipPX - hitX;
        hitPY = level.clipPY - hitY;
        hitPZ = level.clipPZ - hitZ;
    }

    private int boxFace;

    private float rayBox(float ox, float oy, float oz, float dx, float dy, float dz, float x0, float y0, float z0, float x1, float y1, float z1) {
        float tmin = -1e9f, tmax = 1e9f;
        int f = -1;
        float[] o = { ox, oy, oz }, d = { dx, dy, dz }, lo = { x0, y0, z0 }, hi = { x1, y1, z1 };
        int[] negF = { 4, 0, 2 }, posF = { 5, 1, 3 };
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-7f) {
                if (o[a] < lo[a] || o[a] > hi[a]) return -1;
                continue;
            }
            float t1 = (lo[a] - o[a]) / d[a], t2 = (hi[a] - o[a]) / d[a];
            int f1 = negF[a], f2 = posF[a];
            if (t1 > t2) { float tt = t1; t1 = t2; t2 = tt; int ff = f1; f1 = f2; f2 = ff; }
            if (t1 > tmin) { tmin = t1; f = f1; }
            if (t2 < tmax) tmax = t2;
            if (tmin > tmax) return -1;
        }
        if (tmax < 0) return -1;
        boxFace = f;
        return tmin < 0 ? 0 : tmin;
    }

    /** Форма выбора блока (Tile::updateShape) */
    private float[] selBox(int id, int x, int y, int z) {
        float[] o = new float[6];
        TileLogic.shape(level, id, x, y, z, o);
        return o;
    }

    /** Ближайший моб на луче касания (до distMax). */
    private Entity pickEntity(int sx, int sy, float distMax) {
        rayFor(sx, sy);
        Entity best = null;
        float bt = distMax;
        java.util.Vector v = level.entities;
        for (int i = 0; i < v.size(); i++) {
            Entity e = (Entity) v.elementAt(i);
            if (!e.isPickable()) continue;
            float t = e.rayHit(ray[0], ray[1], ray[2], ray[3], ray[4], ray[5]);
            if (t >= 0 && t < bt) { bt = t; best = e; }
        }
        return best;
    }

    private float blockHitDist() {
        float dx = hitX + hitPX - ray[0], dy = hitY + hitPY - ray[1], dz = hitZ + hitPZ - ray[2];
        return Mth.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** GameRenderer::pick: сущности — до точки попадания в блок, но в выживании не дальше 3, в творческом до 12. */
    private float entityReach(boolean blk) {
        if (player.inv.creative) return 12f;
        float d = blk ? blockHitDist() : 5f;
        return Math.min(d, 3f);
    }

    /** Удар/взаимодействие с мобом. true — касание ушло в моба. */
    private boolean interactEntity(int sx, int sy) {
        boolean blk = pick(sx, sy);
        float reach = entityReach(blk);
        Entity e = pickEntity(sx, sy, reach);
        if (e == null) return false;
        attackEntity(e);
        return true;
    }

    /** GameMode::attack (Player::attack). */
    private void attackEntity(Entity e) {
        Inventory inv = player.inv;
        int item = inv.selId();
        if (level.isClientSide && Net.client != null) Net.client.interact(Net.INTERACT_ATTACK, e);
        // тап по сущности — только атака (GameMode::attack); ножницы и краситель — удержанием
        if (e instanceof Painting) {
            e.hurt(1, player);
            return;
        }
        player.swing();
        // Player::attack: урон Item::getAttackDamage, затем hurtEnemy (меч 1, копатель 2) — у мобов и не в творческом
        int dmg = Item.attackDamage(item);
        if (dmg > 0) {
            e.hurt(dmg, player);
            if (e instanceof Mob && item >= 256 && !inv.creative) {
                int k = Item.toolKind[item];
                if (k == 4) inv.damageSelected(1);
                else if (k >= 1 && k <= 3) inv.damageSelected(2);
            }
        }
    }

    // ---- действия клавиш по прицелу (центр экрана) ----

    /** Луч из центра экрана (GameRenderer::pick без сенсора). */
    private boolean aimCenter;
    /** Клавиша копания удерживается и цель — блок (Minecraft::handleMouseDown). */
    boolean keyDigging;

    private boolean pickCenter() {
        aimCenter = true;
        try {
            return pick(W / 2, H / 2);
        } finally {
            aimCenter = false;
        }
    }

    /** Minecraft::handleBuildAction(BAI_REMOVE | BAI_ATTACK): взмах, удар по мобу, начало копания блока. */
    private void keyDestroyPress() {
        if (missTime > 0) return;
        player.swing();
        if (player.isUsingItem() || player.sleeping) return;
        boolean blk = pickCenter();
        Entity e = pickEntity(W / 2, H / 2, entityReach(blk));
        if (e != null) {
            attackEntity(e);
            return;
        }
        if (!blk) {
            if (!player.inv.creative) missTime = 10;
            return;
        }
        // GameMode::startDestroyBlock — в keyTick этого же тика (как handleMouseDown после handleBuildAction)
        keyDigging = true;
    }

    /** Minecraft::handleBuildAction(BAI_BUILD | BAI_INTERACT): моб — GameMode::interact, блок — useItemOn, иначе useItem. */
    private void keyUsePress() {
        if (player.isUsingItem() || player.sleeping) return;
        boolean blk = pickCenter();
        Entity e = pickEntity(W / 2, H / 2, entityReach(blk));
        if (e != null) {
            interactHold(e);
            useItem();
            return;
        }
        if (!blk) useItem();
        else useHit();
    }

    /** Каждый тик: продолжение копания (handleMouseDown) и отпускание используемого предмета. */
    private void keyTick() {
        Player p = player;
        if (p.isUsingItem()) {
            // Minecraft::handleMouseDown: ни копание, ни использование не нажаты — лук стреляет, еда отменяется
            if (!act[A_USE] && !act[A_DESTROY] && !worldTouch()) p.releaseUsingItem();
            return;
        }
        if (!act[A_DESTROY] || p.sleeping || holding) return;
        if (missTime > 0) return;
        boolean blk = pickCenter();
        Entity e = blk ? pickEntity(W / 2, H / 2, entityReach(true)) : null;
        if (!blk || e != null) {
            if (keyDigging) {
                hasTarget = false;
                breakProgress = 0;
            }
            keyDigging = false;
            return;
        }
        keyDigging = true;
        p.swing();
        digBlock();
    }

    private void useAt(int sx, int sy) {
        if (interactEntity(sx, sy)) return;
        if (!pick(sx, sy)) {
            useItem();
            return;
        }
        useHit();
    }

    /** Minecraft::handleBuildAction по блоку hitX..: GameMode::useItemOn, иначе GameMode::useItem. */
    private void useHit() {
        Inventory inv = player.inv;
        int x = hitX, y = hitY, z = hitZ, f = hitFace;
        int target = level.getTile(x, y, z);
        // GameMode::useItemOn: невидимая коренная порода; Tile::use; Item::useOn
        if (target == Tile.INVISIBLE_BEDROCK) return;
        // GameMode::useItemOn у клиента: UseItemPacket хосту до своего действия
        if (level.isClientSide && Net.client != null) Net.client.useItemOn(x, y, z, f, hitPX, hitPY, hitPZ);
        if (openBlock(x, y, z, target) || TileLogic.use(level, x, y, z, player)) {
            player.swing();
            return;
        }
        int item = inv.selId(), aux = inv.selAux();
        if (item == 0 || inv.selCount() <= 0) return;
        if (useItemOn(player, inv, item, aux, x, y, z, f, target, hitPY)) player.swing();
        else useItem();
    }

    /** Item::useOn для предметов 0.6.1; actor — свой игрок или игрок сети на хосте (inv — его, у сетевого — поддельный). */
    boolean useItemOn(Mob actor, Inventory inv, int item, int aux, int x, int y, int z, int f, int target, float clickY) {
        java.util.Random r = level.random;
        // HoeItem::useOn (условие как в оригинале: землю пашет при любой грани и любом блоке сверху)
        if (item >= 256 && Item.toolKind[item] == 5) {
            if ((f != 0 && level.getTile(x, y + 1, z) == 0 && target == Tile.GRASS) || target == Tile.DIRT) {
                tileSound(Tile.stepSound(Tile.FARMLAND), Tile.FARMLAND, x, y, z, 0.5f);
                inv.damageSelected(1);
                level.setTile(x, y, z, Tile.FARMLAND);
                if (target == Tile.GRASS && r.nextInt(8) == 0) {
                    float s = 0.7f;
                    level.addEntity(new ItemEntity(level, x + r.nextFloat() * s + 0.15f, y + r.nextFloat() * s + 0.75f, z + r.nextFloat() * s + 0.15f, 295, 1, 0));
                }
                return true;
            }
            return false;
        }
        // DyePowderItem::useOn: костная мука
        if (item == 351) return aux == 15 && boneMeal(inv, x, y, z, target);
        // HangingEntityItem::useOn: картина только на боковую грань, если помещается
        if (item == 321) {
            if (f < 2) return false;
            Painting pt = new Painting(level, x, y, z, Painting.FACING_DIRECTION[f]);
            if (pt.survives()) {
                level.addEntity(pt);
                inv.useSelected();
            }
            return true;
        }
        // DoorItem / BedItem: только сверху
        if (item == 324 || item == 330) {
            if (f != 1) return false;
            int id = item == 324 ? Tile.WOOD_DOOR : Tile.IRON_DOOR;
            if (y + 1 >= Level.HEIGHT - 1 || !level.isSolidBlockingTile(x, y, z) || !TileLogic.mayPlace(level, id, x, y + 1, z, f)
                    || !TileLogic.mayPlace(level, id, x, y + 2, z, f)) return false;
            int dir = Mth.floor((actor.yRot + 180) * 4 / 360f - 0.5f) & 3;
            TileLogic.placeDoor(level, x, y + 1, z, dir, id);
            inv.useSelected();
            return true;
        }
        if (item == 355) {
            if (f != 1) return false;
            y++;
            int dir = Mth.floor(actor.yRot * 4 / 360f + 0.5f) & 3;
            int xra = dir == 1 ? -1 : (dir == 3 ? 1 : 0), zra = dir == 0 ? 1 : (dir == 2 ? -1 : 0);
            if (level.isEmptyTile(x, y, z) && level.isEmptyTile(x + xra, y, z + zra) && level.isSolidBlockingTile(x, y - 1, z)
                    && level.isSolidBlockingTile(x + xra, y - 1, z + zra)) {
                level.setTileAndData(x, y, z, Tile.BED, dir);
                if (level.getTile(x, y, z) == Tile.BED) level.setTileAndData(x + xra, y, z + zra, Tile.BED, dir + 8);
                inv.useSelected();
                return true;
            }
            return false;
        }
        // SignItem::useOn: не на низ, сбоку — настенная
        if (item == 323) {
            if (f == 0 || !Tile.mSolid(level.getMaterial(x, y, z))) return false;
            x += TileLogic.fx(f); y += TileLogic.fy(f); z += TileLogic.fz(f);
            if (!TileLogic.mayPlace(level, Tile.SIGN, x, y, z, f)) return false;
            if (f == 1) level.setTileAndData(x, y, z, Tile.SIGN, Mth.floor((actor.yRot + 180) * 16 / 360f + 0.5f) & 15);
            else level.setTileAndData(x, y, z, Tile.WALL_SIGN, f);
            inv.useSelected();
            TileEntity te = level.getTileEntity(x, y, z);
            if (te == null && level.isClientSide) {
                // у клиента табличку создаёт сам SignTile (здесь — вручную), текст уходит хосту
                te = new TileEntity("Sign", x, y, z);
                level.setTileEntity(te);
            }
            if (te != null && actor == player) setScreen(new TextEditScreen(te));
            return true;
        }
        // SeedItem::useOn: только сверху на грядку
        if (item == 295 || item == 362) {
            if (f != 1 || target != Tile.FARMLAND || !level.isEmptyTile(x, y + 1, z)) return false;
            level.setTile(x, y + 1, z, item == 295 ? Tile.CROPS : Tile.MELON_STEM);
            inv.useSelected();
            return true;
        }
        int place = item < 256 ? item : Item.placesTile[item];
        if (place == 0) return false;
        // TileItem / TilePlanterItem::useOn
        if (target == Tile.SNOW_LAYER) f = 0;
        else {
            // StoneSlabTileItem: плита на плиту того же вида — двойная
            if (place == Tile.SLAB && target == Tile.SLAB) {
                int td = level.getData(x, y, z);
                boolean top = (td & 8) != 0;
                if ((td & 7) == aux && ((f == 1 && !top) || (f == 0 && top))) {
                    if (unobstructedBlock(x, y, z) && level.setTileAndData(x, y, z, Tile.DOUBLE_SLAB, aux)) {
                        tileSound(Tile.stepSound(Tile.DOUBLE_SLAB), Tile.DOUBLE_SLAB, x, y, z, 0.5f);
                        inv.useSelected();
                    }
                    return true;
                }
            }
            x += TileLogic.fx(f); y += TileLogic.fy(f); z += TileLogic.fz(f);
        }
        if (!TileLogic.levelMayPlace(level, place, x, y, z, false, f)) return false;
        if (item == 338) {
            // TilePlanterItem: без data и без звука
            if (level.setTile(x, y, z, place)) inv.useSelected();
            return true;
        }
        int itemVal = item < 256 ? levelDataFor(place, aux) : 0;
        int data = TileLogic.placedData(level, place, x, y, z, f, clickY, itemVal);
        if (level.setTileAndData(x, y, z, place, data)) {
            TileLogic.setPlacedBy(level, place, x, y, z, actor);
            tileSound(Tile.stepSound(place), place, x, y, z, 0.5f);
            inv.useSelected();
        }
        return true;
    }

    /** Level::isUnobstructed для полного блока */
    private boolean unobstructedBlock(int x, int y, int z) {
        java.util.Vector v = level.entities;
        for (int i = -1; i < v.size(); i++) {
            Entity e = i < 0 ? player : (Entity) v.elementAt(i);
            if (e == null || e.removed || !e.blocksBuilding()) continue;
            float hw = e.bbW / 2;
            if (e.x + hw > x && e.x - hw < x + 1 && e.y + e.bbH > y && e.y < y + 1 && e.z + hw > z && e.z - hw < z + 1) return false;
        }
        return true;
    }

    /** TileItem::getLevelDataForAuxValue: листва ставится с битом «поставлена игроком». */
    private static int levelDataFor(int id, int aux) {
        if (id == Tile.LEAVES) return (aux & 3) | 8;
        switch (id) {
            case Tile.LOG: case Tile.SAPLING: case Tile.WOOL: case Tile.SLAB: case Tile.SANDSTONE: case Tile.STONE_BRICK: case Tile.QUARTZ:
                return aux;
        }
        return 0;
    }

    /** Блоки с окнами: верстак, камнерез, сундук, печь; кровать. */
    private boolean openBlock(int x, int y, int z, int id) {
        if (level.isClientSide) {
            // у клиента окна сундука/печи открывает хост, кровать и TNT — тоже он (ChestTile/BedTile::use isClientSide)
            switch (id) {
                case Tile.CHEST: case Tile.FURNACE: case Tile.FURNACE_LIT: case Tile.TNT:
                    if (id == Tile.TNT && player.inv.selId() != 259) return false;
                    return true;
                case Tile.BED:
                    if (player.sleeping) stopSleep(false);
                    return true;
            }
        }
        switch (id) {
            // LocalPlayer::startCrafting/openFurnace/openContainer 0.6.1: в творческом окна не открываются
            case Tile.WORKBENCH:
                if (!player.inv.creative) setScreen(new CraftScreen(CraftScreen.WORKBENCH));
                return true;
            case Tile.STONECUTTER:
                if (!player.inv.creative) setScreen(new CraftScreen(CraftScreen.STONECUTTER));
                return true;
            case Tile.CHEST:
            case Tile.FURNACE:
            case Tile.FURNACE_LIT: {
                // ChestTile::use: над сундуком сплошной блок — не открывается
                if (id == Tile.CHEST && level.isSolidBlockingTile(x, y + 1, z)) return true;
                if (player.inv.creative) return true;
                TileEntity te = level.getTileEntity(x, y, z);
                if (te == null) {
                    te = new TileEntity(id == Tile.CHEST ? "Chest" : "Furnace", x, y, z);
                    level.setTileEntity(te);
                }
                if (te.isFurnace()) setScreen(new FurnaceScreen(te));
                else setScreen(new ChestScreen(te));
                return true;
            }
            case Tile.BED:
                useBed(x, y, z);
                return true;
            case Tile.TNT:
                // TntTile::use: огниво поджигает
                if (player.inv.selId() == 259) {
                    player.inv.damageSelected(1);
                    PrimedTnt t = new PrimedTnt(level, x + 0.5f, y + 0.01f, z + 0.5f);
                    level.addEntity(t);
                    playSoundAt("random.fuse", t.x, t.y, t.z, 1f);
                    level.setTile(x, y, z, 0);
                    return true;
                }
                return false;
        }
        return false;
    }

    // ---- кровать (BedTile::use, Player::startSleepInBed/stopSleepInBed, ServerLevel::tick) ----

    static final int[][] HEAD_DIR = { { 0, 1 }, { -1, 0 }, { 0, -1 }, { 1, 0 } };

    private void useBed(int x, int y, int z) {
        Player p = player;
        if (p.sleeping) {
            if (Math.abs(p.bedX - x) < 3 && Math.abs(p.bedY - y) < 3 && Math.abs(p.bedZ - z) < 3) stopSleep(false);
            return;
        }
        int data = level.getData(x, y, z);
        if ((data & 8) == 0) {
            int dir = data & 3;
            x += HEAD_DIR[dir][0];
            z += HEAD_DIR[dir][1];
            if (level.getTile(x, y, z) != Tile.BED) return;
            data = level.getData(x, y, z);
        }
        if ((data & 4) != 0) level.setData(x, y, z, data & ~4);
        if (startSleep(x, y, z, false)) level.setData(x, y, z, level.getData(x, y, z) | 4);
    }

    /** Player::startSleepInBed (+ LocalPlayer: экран «в кровати»). quiet — без сообщений (загрузка мира). */
    boolean startSleep(int x, int y, int z, boolean quiet) {
        Player p = player;
        if (p.isDead()) return false;
        if (Math.abs(p.x - x) > 3 || Math.abs(p.y + Player.EYE - y) > 4 || Math.abs(p.z - z) > 3) return false;
        if (level.isDay()) {
            if (!quiet) gui.message("You can only sleep at night");
            return false;
        }
        for (int i = 0; i < level.entities.size(); i++) {
            Object o = level.entities.elementAt(i);
            if (!(o instanceof Monster)) continue;
            Entity e = (Entity) o;
            if (Math.abs(e.x - x) < 8 && Math.abs(e.y - y) < 5 && Math.abs(e.z - z) < 8) {
                if (!quiet) gui.message("You may not rest now, there are monsters nearby");
                return false;
            }
        }
        int dir = level.getData(x, y, z) & 3;
        float xo = 0.5f, zo = 0.5f;
        if (dir == 0) zo = 0.9f;
        else if (dir == 2) zo = 0.1f;
        else if (dir == 1) xo = 0.1f;
        else xo = 0.9f;
        // Pos (глаза, heightOffset 0.2) = y + 15/16 — ноги на 0.2 ниже
        p.setPos(x + xo, y + 15f / 16f - 0.2f, z + zo);
        p.xd = p.yd = p.zd = 0;
        p.sleeping = true;
        p.sleepTimer = 0;
        p.bedX = x;
        p.bedY = y;
        p.bedZ = z;
        setScreen(new InBedScreen());
        return true;
    }

    /** Player::stopSleepInBed: встать рядом с кроватью, точка возрождения — там же. */
    void stopSleep(boolean forceful) {
        Player p = player;
        if (!p.sleeping) return;
        // LocalPlayer::stopSleepInBed у клиента: хосту PlayerAction(STOP_SLEEPING), встаём сами
        if (level.isClientSide && !netWaking && Net.client != null) Net.client.playerAction(Net.ACTION_STOP_SLEEPING);
        int bx = p.bedX, by = p.bedY, bz = p.bedZ;
        int sx = bx, sy = by, sz = bz;
        if (level.getTile(bx, by, bz) == Tile.BED) {
            level.setData(bx, by, bz, level.getData(bx, by, bz) & ~4);
            int dir = level.getData(bx, by, bz) & 3;
            search:
            for (int step = 0; step <= 1; step++) {
                int x0 = bx - HEAD_DIR[dir][0] * step - 1, z0 = bz - HEAD_DIR[dir][1] * step - 1;
                for (int xx = x0; xx <= x0 + 2; xx++)
                    for (int zz = z0; zz <= z0 + 2; zz++) {
                        int below = level.getTile(xx, by - 1, zz);
                        if (level.isSolidBlockingTile(xx, by - 1, zz) && level.isEmptyTile(xx, by, zz) && level.isEmptyTile(xx, by + 1, zz)) {
                            sx = xx; sy = by; sz = zz;
                            break search;
                        }
                    }
            }
        }
        p.setPos(sx + 0.5f, sy + 0.1f, sz + 0.5f);
        p.resetPos();
        p.sleeping = false;
        p.sleepTimer = forceful ? 0 : Player.SLEEP_DURATION;
        p.hasSpawn = true;
        p.spawnX = sx;
        p.spawnY = sy;
        p.spawnZ = sz;
        if (screen instanceof InBedScreen) setScreen(null);
    }

    /** ServerLevel::tick: все (один) спят достаточно — утро, проснуться с полным здоровьем. */
    private void tickSleep() {
        Player p = player;
        if (level.isClientSide) {
            if (p.sleeping && ++p.sleepTimer > Player.SLEEP_DURATION) p.sleepTimer = Player.SLEEP_DURATION;
            return;
        }
        if (p.sleeping && Net.server != null && Net.server.clientCount() > 0) {
            // ServerLevel::tick: утро, только когда спят все игроки
            if (level.getTile(p.bedX, p.bedY, p.bedZ) != Tile.BED) { stopSleep(true); return; }
            if (level.isDay()) { stopSleep(false); return; }
            if (Net.server.allPlayersSleeping()) {
                long t = level.time + Level.TICKS_PER_DAY;
                level.time = t - t % Level.TICKS_PER_DAY;
                level.updateSkyDarken();
                stopSleep(false);
                p.health = p.lastHealth = 20;
                Net.server.morning();
            }
            return;
        }
        if (p.sleeping) {
            if (level.getTile(p.bedX, p.bedY, p.bedZ) != Tile.BED) { stopSleep(true); return; }
            // Player::tick: рассвело — проснуться
            if (level.isDay()) { stopSleep(false); return; }
            if (p.sleepTimer >= Player.SLEEP_DURATION) {
                long t = level.time + Level.TICKS_PER_DAY;
                level.time = t - t % Level.TICKS_PER_DAY;
                level.updateSkyDarken();
                stopSleep(false);
                p.health = p.lastHealth = 20;
            }
        }
    }

    /** DyePowderItem::useOn (костная мука): саженец растёт, урожай созревает, трава — цветы. Мука тратится. */
    private boolean boneMeal(Inventory inv, int x, int y, int z, int target) {
        java.util.Random r = level.random;
        if (target == Tile.SAPLING) {
            TileLogic.growTree(level, x, y, z, r);
            inv.useSelected();
            return true;
        }
        if (target == Tile.MELON_STEM) {
            level.setData(x, y, z, 7);
            inv.useSelected();
            return true;
        }
        if (target == Tile.CROPS) {
            level.setData(x, y, z, 7);
            inv.useSelected();
            return false;
        }
        if (target != Tile.GRASS) return false;
        // 32 попытки случайного блуждания по траве: одуванчик 2/3, голубой цветок 1/3
        for (int j = 0; j < 32; j++) {
            int xx = x, yy = y + 1, zz = z;
            boolean ok = true;
            for (int i = 0; i < j / 16; i++) {
                xx += r.nextInt(3) - 1;
                yy += (r.nextInt(3) - 1) * r.nextInt(3) / 2;
                zz += r.nextInt(3) - 1;
                if (level.getTile(xx, yy - 1, zz) != Tile.GRASS || level.isSolidBlockingTile(xx, yy, zz)) { ok = false; break; }
            }
            if (ok && level.getTile(xx, yy, zz) == 0) level.setTile(xx, yy, zz, r.nextInt(3) != 0 ? Tile.FLOWER : Tile.ROSE);
        }
        inv.useSelected();
        return true;
    }

    private boolean intersectsPlayer(int x, int y, int z) {
        Player p = player;
        float hw = p.bbW / 2;
        return p.x + hw > x && p.x - hw < x + 1 && p.y + p.bbH > y && p.y < y + 1 && p.z + hw > z && p.z - hw < z + 1;
    }

    /**
     * Удержание (State_Destroy): каждый тик BAI_REMOVE | BAI_INTERACT (на ходу с едой/луком — только INTERACT).
     * Minecraft::handleBuildAction: взмах, по мобу — interact, по блоку — копать, затем useItem (еда, лук);
     * Minecraft::handleMouseDown: продолжение копания.
     */
    private void breakTick() {
        boolean remove = !forceUse;
        if (remove && missTime > 0) return;
        if (remove) player.swing();
        if (player.isUsingItem()) return;
        boolean blk = pick(touchX, touchY);
        Entity ent = pickEntity(touchX, touchY, entityReach(blk));
        if (!blk && ent == null) {
            hasTarget = false;
            breakProgress = 0;
            if (remove && !player.inv.creative) missTime = 10;
        } else if (ent != null) {
            hasTarget = false;
            interactHold(ent);
        }
        useItem();
        if (!remove || ent != null || !blk || player.isUsingItem()) return;
        digBlock();
    }

    /** GameMode::startDestroyBlock / continueDestroyBlock по блоку hitX..: прогресс, стук, крошки, разрушение. */
    private void digBlock() {
        // destroyDelay: SurvivalMode 5 тиков после блока, CreativeMode — 10 между блоками
        if (createCooldown > 0) return;
        // GameMode::startDestroyBlock: с луком в руках не копает
        if (player.inv.selId() == 261) return;
        Inventory inv = player.inv;
        boolean fresh = !hasTarget || hitX != breakX || hitY != breakY || hitZ != breakZ;
        if (fresh) {
            hasTarget = true;
            breakX = hitX; breakY = hitY; breakZ = hitZ; breakFace = hitFace;
            breakProgress = 0;
            destroyTicks = 0;
        }
        int id = level.getTile(breakX, breakY, breakZ);
        if (id == 0) return;
        if (inv.creative) {
            destroy(breakX, breakY, breakZ);
            createCooldown = 10;
            hasTarget = false;
            return;
        }
        int item = inv.selId();
        float prog = destroyProgress(item, id);
        if (fresh) {
            // SurvivalMode::startDestroyBlock: Tile::attack (двери, люки, редстоун), мгновенно ломаемые — сразу
            TileLogic.attack(level, breakX, breakY, breakZ, player);
            id = level.getTile(breakX, breakY, breakZ);
            if (id == 0) return;
            if (destroyProgress(item, id) >= 1) {
                survivalDestroy(breakX, breakY, breakZ);
                hasTarget = false;
                return;
            }
        }
        breakProgress += prog;
        // SurvivalMode::continueDestroyBlock: стук каждые 4 тика, (громкость + 1) / 8
        if ((++destroyTicks & 3) == 1) tileSound(Tile.stepSound(id), id, breakX, breakY, breakZ, 1 / 8f);
        // Minecraft::handleMouseDown: крошка с грани каждый тик
        float[] sh = new float[6];
        TileLogic.shape(level, id, breakX, breakY, breakZ, sh);
        particles.crack(breakX, breakY, breakZ, breakFace, id, level.getData(breakX, breakY, breakZ), sh);
        if (breakProgress >= 1f) {
            survivalDestroy(breakX, breakY, breakZ);
            breakProgress = 0;
            hasTarget = false;
            createCooldown = 5;
        }
    }

    /** Tile::getDestroyProgress: прочность < 0 — никогда; без подходящего инструмента 1 / прочность / 100. */
    private float destroyProgress(int item, int id) {
        float h = Tile.hardness[id];
        if (h < 0) return 0;
        if (!Item.canHarvest(item, id)) return 1 / h / 100f;
        return Item.destroySpeed(item, id) / h / 30f;
    }

    /** SurvivalMode::destroyBlock: износ инструмента (Item::mineBlock) и выпадение (Tile::playerDestroy). */
    private void survivalDestroy(int x, int y, int z) {
        int id = level.getTile(x, y, z), d = level.getData(x, y, z);
        int item = player.inv.selId();
        boolean couldDestroy = Item.canHarvest(item, id);
        boolean changed = destroy(x, y, z);
        if (item >= 256) {
            int k = Item.toolKind[item];
            if (k >= 1 && k <= 3) player.inv.damageSelected(1);
            else if (k == 4) player.inv.damageSelected(2);
            else if (k == 6 && (id == Tile.LEAVES || id == Tile.WEB)) player.inv.damageSelected(1);
        }
        if (changed && couldDestroy) TileLogic.playerDestroy(level, player, id, x, y, z, d);
    }

    /** Удержание на мобе (BAI_INTERACT): ножницы и краситель на овцу. */
    private void interactHold(Entity e) {
        if (level.isClientSide && Net.client != null) {
            Net.client.interact(Net.INTERACT_INTERACT, e);
            return;
        }
        interactWith(e, player.inv);
    }

    /** Player::interact: ножницы и краситель на овцу (свой игрок или игрок сети на хосте). */
    void interactWith(Entity e, Inventory inv) {
        int item = inv.selId();
        if (e instanceof Mobs.Sheep && item == 359) {
            if (((Mobs.Sheep) e).shear()) inv.damageSelected(1);
        } else if (e instanceof Mobs.Sheep && item == 351) {
            // DyePowderItem::interactEnemy
            Mobs.Sheep sh = (Mobs.Sheep) e;
            int c = 15 - (inv.selAux() & 15);
            if (!sh.sheared && sh.color != c) { sh.color = c; inv.useSelected(); }
        }
    }

    /** GameMode::useItem: FoodItem::use (если ранен), BowItem::use (если есть стрелы или творческий). */
    private void useItem() {
        Player p = player;
        Inventory inv = p.inv;
        int item = inv.selId();
        if (item < 256 || inv.selCount() <= 0 || p.isUsingItem()) return;
        // GameMode::useItem у клиента: UseItemPacket(грань 255); лук, еду и броски ведёт хост
        if (level.isClientSide && Net.client != null && (Item.food[item] > 0 || item == 261 || item == 332 || item == 344)) Net.client.useItem();
        if (Item.food[item] > 0) {
            if (!p.invulnerable && p.health < 20) p.startUsingItem(item, inv.selAux(), Player.EAT_DURATION);
        } else if (item == 261) {
            if (inv.creative || inv.hasResource(262)) p.startUsingItem(item, inv.selAux(), Player.BOW_DURATION);
        } else if (item == 332 || item == 344) {
            // SnowballItem/EggItem::use: бросок
            inv.useSelected();
            playSound("random.bow", 0.5f);
            level.addEntity(ThrownItem.fromPlayer(level, p, item == 332 ? 81 : 82));
        }
    }

    /** GameMode::destroyBlock: частицы, блок -> воздух, звук разрушения, вибрация. */
    private boolean destroy(int x, int y, int z) {
        int id = level.getTile(x, y, z);
        if (id == 0) return false;
        int d = level.getData(x, y, z);
        particles.destroy(x, y, z, id, d);
        boolean changed = level.setTile(x, y, z, 0);
        if (changed) {
            // GameMode::destroyBlock: RemoveBlockPacket (выпадение — у хоста)
            if (level.isClientSide && Net.client != null) Net.client.removeBlock(x, y, z);
            tileSound(Tile.breakSound(id), id, x, y, z, 0.5f);
            if (Options.vibration) Display.getDisplay(midlet).vibrate(24);
        }
        return changed;
    }

    // ---- отрисовка ----

    long tTick, tFlush, t3d, tHud; // *8, скользящее среднее

    private void render(Graphics g) {
        if (!initDone) return;
        if (level != null && (screen == null || screen.showsWorld())) {
            long a = System.currentTimeMillis();
            renderWorld(g);
            long b = System.currentTimeMillis();
            renderHud(g);
            long c = System.currentTimeMillis();
            if (Prof.on) Prof.acc[Prof.F_HUD] += c - b;
            t3d = (t3d * 7 + (b - a) * 8) / 8;
            tHud = (tHud * 7 + (c - b) * 8) / 8;
        }
        if (screen != null) {
            long a = Prof.t();
            screen.render(g);
            screen.renderFocus(g);
            if (Prof.on) Prof.add(Prof.F_SCREEN, a);
        }
        // Custom: счётчик кадров в левом верхнем углу (сердца под ним, Gui.hudTop)
        if (Options.showFps) R.shadow(g, fps + " fps", 2, 2, 0xFFFFFF);
    }

    Sky sky;
    HandRenderer hand;
    SignRenderer signs;
    private final Transform viewBob = new Transform();
    private final Frustum frustum = new Frustum();
    /** GameRenderer::fogBr — яркость у игрока, сглаженная по тикам */
    float fogBr = 1f, fogBrO = 1f;

    private void tickFogBr() {
        Player p = player;
        float brr = LevelRenderer.BRF[level.getRawBrightness(Mth.floor(p.x), Mth.floor(p.y + Player.EYE), Mth.floor(p.z))];
        float w = Options.low() ? 0 : 1f / 3f;
        fogBrO = fogBr;
        fogBr += (brr * (1 - w) + w - fogBr) * 0.1f;
    }

    long tUpd, tBind, tWorld, tEnt, tRel, q2, q3, q4;
    /** последний кадр: обновление мешей, привязка, мир, сущности и прочее, выгрузка (для записи рывков) */
    final long[] lastFrame = new long[5];

    private void renderWorld(Graphics g) {
        Player p = player;
        float a = partial;
        float ex = p.xo + (p.x - p.xo) * a, ey = p.getEyeY(a), ez = p.zo + (p.z - p.zo) * a;
        float yaw = p.yRot, pitch = p.xRot;
        int bedDir = -1;
        if (p.sleeping && level.getTile(Mth.floor(p.x), Mth.floor(p.y + 0.2f), Mth.floor(p.z)) == Tile.BED) {
            // GameRenderer::moveCameraToPlayer (спит): поворот взгляда отменяется, остаётся поворот кровати direction*90
            bedDir = level.getData(Mth.floor(p.x), Mth.floor(p.y + 0.2f), Mth.floor(p.z)) & 3;
            yaw = 180f + bedDir * 90f;
            pitch = 0;
        }
        long q0 = System.currentTimeMillis();
        lr.updateDirty(ex, ey, ez, 8);
        long q1 = System.currentTimeMillis();
        int liquid = p.isUnderLiquid(true) ? 1 : (p.isUnderLiquid(false) ? 2 : 0);
        if (sky == null) sky = new Sky();
        sky.update(level, a, Options.low() ? 3 : 2, fogBrO + (fogBr - fogBrO) * a, liquid);
        int fogCol = sky.rgb();
        lr.fog.setColor(fogCol);
        background.setColor(0xFF000000 | fogCol);
        // GameRenderer::setupFog: в воде/лаве экспоненциальный туман
        if (liquid != 0) {
            lr.fog.setMode(Fog.EXPONENTIAL);
            lr.fog.setDensity(liquid == 1 ? 0.1f : 2f);
        } else {
            lr.fog.setMode(Fog.LINEAR);
            lr.fog.setLinear(lr.renderDist * 0.6f, lr.renderDist);
        }
        try {
            g3d.bindTarget(g, true, 0);
            g3d.setViewport(0, 0, W, H);
            g3d.clear(background);
            q2 = System.currentTimeMillis();
            float fov = liquid == 1 ? 60f : 70f;
            if (p.health <= 0) fov /= (1 - 500f / (p.deathTime + a + 500f)) * 2f + 1f;
            camera.setPerspective(fov, (float) W / H, 0.05f, lr.renderDist + 24f);
            camT.setIdentity();
            camT.postTranslate(ex, ey, ez);
            camT.postRotate(180f - yaw, 0, 1, 0);
            camT.postRotate(-pitch, 1, 0, 0);
            float third = 0;
            if (Options.thirdPerson && !p.sleeping) {
                // GameRenderer::moveCameraToPlayer: thirdDistance 4, упор в блоки за спиной
                float cpp = Mth.cos(pitch * Mth.DEG);
                float bx = Mth.sin(yaw * Mth.DEG) * cpp, by = Mth.sin(pitch * Mth.DEG), bz = -Mth.cos(yaw * Mth.DEG) * cpp;
                third = 4f;
                for (float d = 0.1f; d <= 4f; d += 0.1f) {
                    int id = level.getTile(Mth.floor(ex + bx * d), Mth.floor(ey + by * d), Mth.floor(ez + bz * d));
                    if (id != 0 && Tile.solid[id]) { third = Math.max(0.1f, d - 0.3f); break; }
                }
                camT.postTranslate(0, 0, third);
            } else if (!p.sleeping) camT.postTranslate(0, 0, 0.1f);
            // GameRenderer::setupCamera: bobHurt и bobView стоят в матрице вида перед камерой
            viewBob.setIdentity();
            HandRenderer.bobHurt(viewBob, p, a);
            HandRenderer.bobView(viewBob, p, a);
            viewBob.invert();
            camT.postMultiply(viewBob);
            // Options::fancyGraphics: плоскость неба и облака
            if (Options.fancy()) sky.render(g3d, camT, fov, (float) W / H, lr.renderDist, ex, ey, ez,
                    p.xo + (p.x - p.xo) * a, p.zo + (p.z - p.zo) * a, ticks + a, level.getSkyBrightness(a));
            g3d.setCamera(camera, camT);
            g3d.resetLights();
            camX = ex; camY = ey; camZ = ez; camYaw = yaw; camPitch = pitch; camFov = fov; camThird = third;
            float cp = Mth.cos(pitch * Mth.DEG);
            float fx = -Mth.sin(yaw * Mth.DEG) * cp, fy = -Mth.sin(pitch * Mth.DEG), fz = Mth.cos(yaw * Mth.DEG) * cp;
            float aspect = (float) W / H;
            float tv = (float) Math.tan(35f * Mth.DEG);
            float cosDiag = 1f / Mth.sqrt(1f + tv * tv * (1f + aspect * aspect));
            frustum.set(camT, fov, aspect);
            lr.render(g3d, frustum);
            q3 = System.currentTimeMillis();
            er.playerRotY = yaw;
            long r0 = Prof.t();
            er.render(g3d, level, a, ex, ey, ez, frustum, lr.renderDist);
            if (third > 0 || p.sleeping) er.renderPlayer(g3d, p, a, level);
            for (int i = 0; i < takes.size(); i++) {
                ItemEntity c = (ItemEntity) takes.elementAt(i);
                er.renderTake(g3d, level, c, c.pickupDelay, a, p);
            }
            long r1 = Prof.t();
            if (signs == null) signs = new SignRenderer(lr.fog);
            signs.render(g3d, level, ex, ey, ez, frustum);
            lr.renderChests(g3d, frustum, ex, ez);
            long r2 = Prof.t();
            // правый и верхний векторы камеры (поворот Y на 180-yaw, затем X на -pitch)
            float sy = Mth.sin(yaw * Mth.DEG), cy = Mth.cos(yaw * Mth.DEG);
            float sp = Mth.sin(pitch * Mth.DEG), cpp = Mth.cos(pitch * Mth.DEG);
            particles.render(g3d, a, yaw, pitch, ex, ey, ez);
            // GameRenderer: terrain-water — после сущностей и частиц
            lr.renderBlend(g3d);
            long r3 = Prof.t();
            if (Prof.on) {
                Prof.acc[Prof.F_ENT] += r1 - r0;
                Prof.acc[Prof.F_SIGN] += r2 - r1;
                Prof.acc[Prof.F_PART] += r3 - r2;
            }
            // GameRenderer: выделение под пальцем (не под водой), затем трещины
            if (selValid && !p.isUnderWater()) renderHitSelect();
            if ((holding || keyDigging) && hasTarget && breakProgress > 0) renderCrack();
            // GameRenderer::renderItemInHand: рука — только от первого лица и не во сне; затем эффекты экрана
            if (third == 0 && !p.sleeping) {
                if (hand == null) hand = new HandRenderer(lr.terrainTex);
                float hfov = liquid == 1 ? 60f : 70f;
                if (p.health <= 0) hfov /= (1 - 500f / (p.deathTime + a + 500f)) * 2f + 1f;
                float hbr = LevelRenderer.BRF[level.getRawBrightness(Mth.floor(p.x), Mth.floor(p.y + Player.EYE), Mth.floor(p.z))];
                hand.render(g3d, p, a, hfov, aspect, lr.renderDist, hbr);
                hand.renderScreenEffect(g3d, p, level);
            }
            if (Prof.on) Prof.add(Prof.F_HAND, r3);
        } catch (Throwable t) {
            Log.e("render3d", t);
            try {
                lowerView(t, "render");
            } catch (Throwable e) {
                Log.e("lowerView", e);
            }
        } finally {
            q4 = System.currentTimeMillis();
            g3d.releaseTarget();
        }
        long q5 = System.currentTimeMillis();
        tUpd = (tUpd * 7 + (q1 - q0) * 8) / 8;
        tBind = (tBind * 7 + (q2 - q1) * 8) / 8;
        tWorld = (tWorld * 7 + (q3 - q2) * 8) / 8;
        tEnt = (tEnt * 7 + (q4 - q3) * 8) / 8;
        tRel = (tRel * 7 + (q5 - q4) * 8) / 8;
        if (Prof.on) {
            lastFrame[0] = q1 - q0; lastFrame[1] = q2 - q1; lastFrame[2] = q3 - q2; lastFrame[3] = q4 - q3; lastFrame[4] = q5 - q4;
            Prof.acc[Prof.F_UPD] += q1 - q0;
            Prof.acc[Prof.F_SKY] += q2 - q1 + (q3 - q2 > 0 ? 0 : 0);
            Prof.acc[Prof.F_WORLD] += q3 - q2;
            Prof.acc[Prof.F_REL] += q5 - q4;
        }
    }

    /** выделенный блок (mc->hitResult обновляется только при касании и отпускании, TouchInputHolder::allowPicking) */
    boolean selValid;
    int selX, selY, selZ;

    private void updateSelect(int x, int y) {
        selValid = pick(x, y);
        if (selValid) { selX = hitX; selY = hitY; selZ = hitZ; }
    }

    private Appearance selApp;
    private final MeshBuilder selMb = new MeshBuilder();
    private final float[] selShape = new float[6];

    /** LevelRenderer::renderHitSelect: блок ещё раз цветом 0.65 с умножением (DST_COLOR, SRC_COLOR) — светлее на 30%. */
    private void renderHitSelect() {
        int id = level.getTile(selX, selY, selZ);
        if (id == 0) id = Tile.STONE;
        if (selApp == null) {
            selApp = new Appearance();
            CompositingMode cm = new CompositingMode();
            cm.setBlending(CompositingMode.MODULATE_X2);
            cm.setDepthWriteEnable(false);
            cm.setDepthOffset(-1f, -1f);
            selApp.setCompositingMode(cm);
            PolygonMode pm = new PolygonMode();
            pm.setCulling(PolygonMode.CULL_BACK);
            selApp.setPolygonMode(pm);
        }
        float[] sh = selShape;
        TileLogic.shape(level, id, selX, selY, selZ, sh);
        int x0 = (int) (sh[0] * 16), y0 = (int) (sh[1] * 16), z0 = (int) (sh[2] * 16), x1 = (int) (sh[3] * 16 + 0.5f), y1 = (int) (sh[4] * 16 + 0.5f), z1 = (int) (sh[5] * 16 + 0.5f);
        MeshBuilder m = selMb;
        m.clear();
        int c = 0xFFA6A6A6;
        m.quad(x0, y1, z0, 0, 0, c, x0, y1, z1, 0, 0, c, x1, y1, z1, 0, 0, c, x1, y1, z0, 0, 0, c);
        m.quad(x0, y0, z0, 0, 0, c, x1, y0, z0, 0, 0, c, x1, y0, z1, 0, 0, c, x0, y0, z1, 0, 0, c);
        m.quad(x1, y0, z0, 0, 0, c, x0, y0, z0, 0, 0, c, x0, y1, z0, 0, 0, c, x1, y1, z0, 0, 0, c);
        m.quad(x0, y0, z1, 0, 0, c, x1, y0, z1, 0, 0, c, x1, y1, z1, 0, 0, c, x0, y1, z1, 0, 0, c);
        m.quad(x0, y0, z0, 0, 0, c, x0, y0, z1, 0, 0, c, x0, y1, z1, 0, 0, c, x0, y1, z0, 0, 0, c);
        m.quad(x1, y0, z1, 0, 0, c, x1, y0, z0, 0, 0, c, x1, y1, z0, 0, 0, c, x1, y1, z1, 0, 0, c);
        selPos[0] = selX; selPos[1] = selY; selPos[2] = selZ;
        VertexBuffer vb = selDyn.load(m, 1f / 16f, selPos, 1f / 256f);
        g3d.render(vb, DynMesh.ib(m.verts), selApp, lr.identity);
    }

    private final MeshBuilder crackMb = new MeshBuilder();
    /** постоянные буферы рамки выделения и трещин (без M3G-мусора, см. DynMesh) */
    private final DynMesh selDyn = new DynMesh(true), crackDyn = new DynMesh(true);
    private final float[] selPos = new float[3];

    private void renderCrack() {
        int stage = (int) (breakProgress * 10f);
        if (stage > 9) stage = 9;
        int tex = 240 + stage;
        int tu = (tex & 15) * 16, tv = (tex >> 4) * 16;
        MeshBuilder m = crackMb;
        m.clear();
        int c = 0xFFFFFFFF;
        int o = 0, e = 16;
        // слегка раздутый куб, чтобы не мерцал
        m.quad(o, e, o, tu, tv, c, o, e, e, tu, tv + 16, c, e, e, e, tu + 16, tv + 16, c, e, e, o, tu + 16, tv, c);
        m.quad(o, o, o, tu, tv, c, e, o, o, tu + 16, tv, c, e, o, e, tu + 16, tv + 16, c, o, o, e, tu, tv + 16, c);
        m.quad(e, o, o, tu, tv + 16, c, o, o, o, tu + 16, tv + 16, c, o, e, o, tu + 16, tv, c, e, e, o, tu, tv, c);
        m.quad(o, o, e, tu, tv + 16, c, e, o, e, tu + 16, tv + 16, c, e, e, e, tu + 16, tv, c, o, e, e, tu, tv, c);
        m.quad(o, o, o, tu, tv + 16, c, o, o, e, tu + 16, tv + 16, c, o, e, e, tu + 16, tv, c, o, e, o, tu, tv, c);
        m.quad(e, o, e, tu, tv + 16, c, e, o, o, tu + 16, tv + 16, c, e, e, o, tu + 16, tv, c, e, e, e, tu, tv, c);
        selPos[0] = breakX - 0.002f; selPos[1] = breakY - 0.002f; selPos[2] = breakZ - 0.002f;
        VertexBuffer vb = crackDyn.load(m, 1f / 16f, selPos, 1f / 256f);
        g3d.render(vb, DynMesh.ib(m.verts), lr.app[Tile.L_CUTOUT], lr.identity);
    }

    private float camX, camY, camZ, camYaw, camPitch, camFov = 70, camThird;

    /** PlayerRenderer::renderName: имя над головой игрока сети (проекция на экран, видно сквозь стены как в 0.6.1).
     *  В релизе 0.6.1 — только при adventureSettings.showNameTags (его никто не включает); ники как в 0.6.2 Beta/0.7.0
     *  включает флажок Custom «Show name tags». */
    private void renderNames(Graphics g) {
        java.util.Vector v = level.entities;
        float t = (float) Math.tan(camFov * Mth.DEG / 2), aspect = (float) W / H;
        float a = (180f - camYaw) * Mth.DEG, b = -camPitch * Mth.DEG;
        float ca = Mth.cos(a), sa = Mth.sin(a), cb = Mth.cos(b), sb = Mth.sin(b);
        // от третьего лица камера отодвинута назад по взгляду
        float cp = Mth.cos(camPitch * Mth.DEG);
        float ox = camX + Mth.sin(camYaw * Mth.DEG) * cp * camThird, oy = camY + Mth.sin(camPitch * Mth.DEG) * camThird, oz = camZ - Mth.cos(camYaw * Mth.DEG) * cp * camThird;
        for (int i = 0; i < v.size(); i++) {
            Object o = v.elementAt(i);
            if (!(o instanceof RemotePlayer)) continue;
            RemotePlayer p = (RemotePlayer) o;
            if (p.removed || p.name.length() == 0) continue;
            float px = p.xo + (p.x - p.xo) * partial, py = p.yo + (p.y - p.yo) * partial + (p.sleeping ? 0.6f : 2.3f), pz = p.zo + (p.z - p.zo) * partial;
            float dx = px - ox, dy = py - oy, dz = pz - oz;
            if (dx * dx + dy * dy + dz * dz > 64 * 64) continue;
            float vx = dx * ca - dz * sa, z1 = dx * sa + dz * ca;
            float vy = dy * cb + z1 * sb, vz = -dy * sb + z1 * cb;
            if (vz > -0.1f) continue;
            float sx = (vx / -vz) / (t * aspect), sy = (vy / -vz) / t;
            int gx = (int) ((sx + 1) / 2 * W / R.k), gy = (int) ((1 - sy) / 2 * H / R.k);
            int w = R.width(p.name);
            R.fill(g, gx - w / 2 - 1, gy - 1, gx + w / 2 + 1, gy + 8, 0x40000000);
            R.shadow(g, p.name, gx - w / 2, gy, 0xFFFFFF);
        }
    }

    private void renderHud(Graphics g) {
        if (level.showNameTags || Options.nameTags) renderNames(g);
        gui.render(g, partial);
        // TouchInputHolder::updateFeedbackProgressAlpha: кольцо проявляется за 0.4 с, при копании — до 1, потом гаснет
        boolean wt = worldTouch();
        if (wt && !moved && !holding) {
            float q = (System.currentTimeMillis() - downTime) / 400f;
            fbAlpha = fbCanDestroy ? q * q : -0.05f;
        } else if (wt && holding) fbAlpha = Math.min(1f, Math.max(0, fbAlpha) + 0.02f);
        else if (fbAlpha > 0) fbAlpha = Math.max(0, fbAlpha - 0.04f);
        int sel = player.inv.selId();
        // Gui::renderProgressIndicator: без сенсора — прицел в центре (icons.png 0,0 16x16)
        if (!touchUI) R.blit(g, R.icons, R.gw / 2f - 8, R.gh / 2f - 8, 0, 0, 16, 16);
        else if (sel == 261) {
            // с луком кольца нет; при натяжении — прицел в центре (Gui: bowEquipped && itemInUse)
            if (player.isUsingItem()) R.blit(g, R.icons, R.gw / 2f - 8, R.gh / 2f - 8, 0, 0, 16, 16);
        } else if (fbAlpha > 0) {
            float pr = holding && hasTarget ? breakProgress : 0;
            gui.renderFeedback(g, touchX, touchY, fbAlpha, hasTarget || pick(touchX, touchY), pr);
        }
        if (debug && !Prof.x('d')) R.shadow(g, fps + " fps", 2, 32, 0xFFFFFF);
        if (debug && !Prof.x('d')) R.shadow(g, "upd " + tUpd / 8 + " bind " + tBind / 8 + " world " + tWorld / 8 + " ent " + tEnt / 8 + " rel " + tRel / 8 + " secs " + lr.lastDrawn, 2, 52, 0xFFFF80);
        if (debug && !Prof.x('d')) R.shadow(g, (int) player.x + " " + (int) player.y + " " + (int) player.z + " yaw " + (int) player.yRot + " ent " + level.entities.size() + " 3d " + t3d / 8 + " hud " + tHud / 8 + " fl " + tFlush / 8 + " tk " + tTick / 8, 2, 42, 0xFFFF80);
    }

    /** ProgressScreen 0.6.1: земля, «Generating world», стадия и полоска процентов. */
    Graphics gfx() {
        return getGraphics();
    }

    void drawLoading(Graphics dev, String text, int pct) {
        Graphics g = frameG(dev);
        if (R.gui != null) {
            int w = R.gw, h = R.gh;
            R.dirt(g, 0, 0, w, h, 0x40, 0);
            if (pct >= 0) {
                int bw = 100, x = w / 2 - bw / 2, y = h / 2 + 16;
                R.fill(g, x, y, x + bw, y + 2, 0xFF808080);
                R.fill(g, x, y, x + pct, y + 2, 0xFF80FF80);
            }
            String title = "Generating world";
            R.shadow(g, title, (w - R.width(title)) / 2, h / 2 - 4 - 16, 0xFFFFFF);
            R.shadow(g, text, (w - R.width(text)) / 2, h / 2 - 4 + 8, 0xFFFFFF);
        } else {
            g.setColor(0x202020);
            g.fillRect(0, 0, W, H);
            g.setColor(0xFFFFFF);
            g.drawString(text, W / 2, H / 2, Graphics.TOP | Graphics.HCENTER);
        }
        present(dev);
    }

    private static int[] alphaRow = new int[0];
    private static int alphaColor;

    public static void fillAlpha(Graphics g, int x, int y, int w, int h, int argb) {
        if (w <= 0 || h <= 0) return;
        if (alphaRow.length < w || alphaColor != argb) {
            if (alphaRow.length < w) alphaRow = new int[w];
            for (int i = 0; i < alphaRow.length; i++) alphaRow[i] = argb;
            alphaColor = argb;
        }
        for (int yy = 0; yy < h; yy++) g.drawRGB(alphaRow, 0, w, x, y + yy, w, 1, true);
    }

    public void stop() {
        running = false;
    }
}
