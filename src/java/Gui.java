import java.util.Random;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Интерфейс в игре 0.6.1: Gui (хотбар, сердца, броня, пузыри, название предмета, сообщения, сон) и
 * TouchscreenInput_TestFps (крестовина, кнопка паузы). Хотбар — в пикселях GUI, крестовина — в пикселях экрана.
 */
public final class Gui {
    private final MC mc;
    /** Слотов в хотбаре вместе с «...» (Gui::_numSlots: от 6 до 8). */
    public int numSlots = 8;
    private int W, H;

    // ---- крестовина (пиксели экрана) ----
    static final int N = 0, S = 1, WEST = 2, E = 3, C = 4;
    float bw, baseX, baseY;
    private final float[][] area = new float[5][]; // x0, y0, x1, y1
    private float[] upLeft, upRight;
    private final boolean[] down = new boolean[5];
    boolean forward, northJump, allowHeightChange, pressedJump, changingHeight, renderFlight;
    private int jumpTriggerTime;
    float pauseSize, pauseX, pauseY;
    /**
     * Custom: размер при ползунке 50% — от короткой стороны экрана, чтобы на сенсоре 3-3.5" было удобно:
     * кнопка крестовины 16% (~8 мм, как pref 0.15 в TouchscreenInput оригинала), пауза 11% (~7 мм).
     */
    static final float DPAD_BTN = 0.16f, PAUSE_FR = 0.11f;

    /** Сторона кнопки паузы при ползунке 50%. */
    static float defaultPause(int w, int h) {
        return Math.min(w, h) * PAUSE_FR;
    }

    // ---- Gui ----
    float itemNameTime = 2;
    private int flashSlot = -1;
    private long flashStart;
    private int tickCount;
    private final Random rnd = new Random();
    private final Vector messages = new Vector(); // {String, int[] ticks}

    public Gui(MC mc) {
        this.mc = mc;
    }

    /** Gui::onConfigChanged + TouchscreenInput::onConfigChanged для экрана W x H пикселей. */
    public void layout(int w, int h) {
        W = w;
        H = h;
        float k = R.k;
        int maxDim = Math.max(w, h), minDim = Math.min(w, h);
        float pref = Math.min(maxDim * 0.11f, minDim * 0.45f / 3f);
        // крестовина сбоку от хотбара, если помещается; иначе (узкий экран) — над хотбаром
        int n = 6;
        float side = (w - (20 * n + 2) * k) / 2f - 8 - 2 * k;
        boolean beside = side / 3f >= pref * 0.7f;
        bw = beside ? Math.min(pref, side / 3f) : pref;
        if (beside) {
            while (n < 8) {
                float s2 = (w - (20 * (n + 1) + 2) * k) / 2f - 8 - 2 * k;
                if (s2 < 3 * bw) break;
                n++;
            }
        } else {
            while (n < 8 && (20 * (n + 1) + 2) * k <= w - 16) n++;
        }
        // Custom: размер крестовины — от экрана и ползунка; число слотов хотбара — по исходной, как в оригинале.
        // Крестовина без своего места, не влезающая сбоку от хотбара, встаёт над ним.
        float sideNow = (w - (20 * n + 2) * k) / 2f - 8 - 2 * k;
        bw = minDim * DPAD_BTN * Options.ctrlSize / 50f;
        bw = Math.min(bw, Math.min(w, h - 32 * k) / 3f);
        if (beside && 3 * bw > sideNow) beside = false;
        numSlots = n;
        baseX = Options.lefty ? w - 8 - 3 * bw : 8;
        baseY = h - 8 - 3 * bw - (beside ? 0 : 24 * k);
        // Custom: место крестовины, перетащенное на экране настройки (центр в тысячных долях экрана)
        if (Options.dpadX >= 0 && Options.dpadY >= 0) {
            baseX = Mth.clamp(Options.dpadX * w / 1000f - 1.5f * bw, 0, w - 3 * bw);
            baseY = Mth.clamp(Options.dpadY * h / 1000f - 1.5f * bw, 0, h - 3 * bw);
        }
        float x0 = baseX, y0 = baseY;
        area[N] = rect(x0 + bw, y0);
        upLeft = rect(x0, y0);
        upRight = rect(x0 + 2 * bw, y0);
        area[C] = rect(x0 + bw, y0 + bw);
        area[S] = rect(x0 + bw, y0 + 2 * bw);
        area[WEST] = rect(x0, y0 + bw);
        area[E] = rect(x0 + 2 * bw, y0 + bw);
        // кнопка паузы (как на iOS: 18 пикселей GUI, полупрозрачная)
        pauseSize = defaultPause(w, h) * Options.ctrlSize / 50f;
        if (Options.pauseX >= 0 && Options.pauseY >= 0) {
            pauseX = Mth.clamp(Options.pauseX * w / 1000f - pauseSize / 2, 0, w - pauseSize);
            pauseY = Mth.clamp(Options.pauseY * h / 1000f - pauseSize / 2, 0, h - pauseSize);
        } else {
            pauseX = w - 4 - pauseSize;
            pauseY = 4;
        }
    }

    private float[] rect(float x, float y) {
        return new float[] { x, y, x + bw, y + bw };
    }

    // ---- геометрия хотбара (пиксели GUI) ----

    int slotX(int slot) {
        return R.gw / 2 - numSlots * 10 + slot * 20;
    }

    int ySlot() {
        return R.gh - 16 - 3;
    }

    /** Gui::getSlotIdAt по пикселям экрана. */
    public int slotAt(int px, int py) {
        int x = (int) (px / R.k), y = (int) (py / R.k);
        if (y < ySlot() || y > R.gh) return -1;
        int xRel = x - (2 + R.gw / 2 - numSlots * 10);
        if (xRel < 0) return -1;
        int slot = xRel / 20;
        return slot < numSlots ? slot : -1;
    }

    public boolean pauseAt(int px, int py) {
        if (!mc.touchUI) return false;
        return px >= pauseX - 8 && px < pauseX + pauseSize + 8 && py >= pauseY - 8 && py < pauseY + pauseSize + 8;
    }

    /** Область крестовины под точкой или -1. */
    public int areaAt(int x, int y) {
        for (int i = 0; i < 5; i++) if (in(area[i], x, y)) return i;
        return -1;
    }

    public boolean inDpad(int x, int y) {
        if (!mc.touchUI) return false;
        return x >= baseX && y >= baseY && x < baseX + 3 * bw && y < baseY + 3 * bw;
    }

    private static boolean in(float[] r, float x, float y) {
        return x >= r[0] && y >= r[1] && x < r[2] && y < r[3];
    }

    public void selectSlot(int slot) {
        mc.player.inv.selected = slot;
        itemNameTime = 0;
    }

    public void flashSlot(int slot) {
        flashSlot = slot;
        flashStart = System.currentTimeMillis();
    }

    /** Gui::displayClientMessage. */
    public void message(String s) {
        messages.insertElementAt(new Object[] { s, new int[1] }, 0);
        if (messages.size() > 50) messages.removeElementAt(messages.size() - 1);
    }

    private Object[] notice;

    /** Уведомление игры (снизу, как сообщения): заменяет своё прошлое, а не копится в чате. */
    public void notice(String s) {
        if (notice != null) messages.removeElement(notice);
        notice = new Object[] { s, new int[1] };
        messages.insertElementAt(notice, 0);
    }

    public void tick() {
        tickCount++;
        if (itemNameTime < 2) itemNameTime += 1f / 20;
        for (int i = 0; i < messages.size(); i++) ((int[]) ((Object[]) messages.elementAt(i))[1])[0]++;
    }

    // ---- TouchscreenInput_TestFps::tick ----

    public void releaseAll() {
        for (int i = 0; i < 5; i++) down[i] = false;
        forward = northJump = allowHeightChange = pressedJump = changingHeight = false;
    }

    /** n пальцев на крестовине (xs/ys — их точки, just — коснулся в этом тике), как перебор Multitouch в оригинале. */
    public void tickInput(Player p, int n, int[] xs, int[] ys, boolean[] just) {
        float xa = 0, ya = 0;
        boolean jumping = false, heldJump = false, tmpForward = false, tmpNorthJump = false;
        for (int i = 0; i < 5; i++) down[i] = false;
        float bx0 = baseX, by0 = baseY, bx1 = baseX + 3 * bw, by1 = baseY + 3 * bw;
        for (int i = 0; i < n; i++) {
            int x = xs[i], y = ys[i];
            boolean justPressed = just[i];
            if (x >= bx0 && y >= by0 && x < bx1 && y < by1 && forward && !changingHeight) {
                float angle = Mth.PI + Mth.atan2(y - (by0 + by1) / 2, x - (bx0 + bx1) / 2);
                ya = Mth.sin(angle);
                xa = Mth.cos(angle);
                tmpForward = true;
            }
            int a = areaAt(x, y);
            if (a < 0) continue;
            boolean set = false;
            if (justPressed) allowHeightChange = a == C;
            if (a == C) {
                set = true;
                heldJump = true;
                if (p.inWater) jumping = true;
                else if (justPressed) jumping = true;
                else if (forward && !p.flying) {
                    a = N;
                    tmpNorthJump = true;
                    ya += 1;
                }
            }
            if (a == N) {
                set = true;
                if (p.inWater) jumping = true;
                else if (!changingHeight) tmpForward = true;
                ya += 1;
            } else if (a == S && !forward) {
                set = true;
                ya -= 1;
            } else if (a == WEST && !forward) {
                set = true;
                xa += 1;
            } else if (a == E && !forward) {
                set = true;
                xa -= 1;
            }
            down[a] = set;
        }
        // клавиши (KeyboardInput / XperiaPlayInput) складываются с сенсором: джойстик — ходьба, центр/3 — прыжок;
        // в полёте прыжок — вверх, прыжок + назад или * — вниз (XperiaPlayInput: wantUp = jumping && UP)
        boolean[] k = mc.act;
        float kya = (k[MC.A_FWD] ? 1 : 0) - (k[MC.A_BACK] ? 1 : 0), kxa = (k[MC.A_LEFT] ? 1 : 0) - (k[MC.A_RIGHT] ? 1 : 0);
        boolean kJump = k[MC.A_JUMP] || k[MC.A_JUMP2];
        boolean kUp = p.flying && kJump && !k[MC.A_BACK], kDown = p.flying && ((kJump && k[MC.A_BACK]) || k[MC.A_SNEAK]);
        if (kUp || kDown) kya = 0;
        if (kJump) jumping = true;
        forward = tmpForward;
        if (tmpNorthJump) {
            if (!northJump) jumping = true;
            northJump = true;
        } else northJump = false;
        changingHeight = false;
        p.wantUp = down[N] && allowHeightChange && (pressedJump || p.wantUp);
        p.wantDown = down[S] && allowHeightChange && (pressedJump || p.wantDown);
        p.wantUp |= kUp;
        p.wantDown |= kDown;
        xa = Mth.clamp(xa + kxa, -1, 1);
        ya = Mth.clamp(ya + kya, -1, 1);
        if (p.flying && (p.wantUp || p.wantDown || (heldJump && !forward))) {
            changingHeight = true;
            ya = 0;
        }
        renderFlight = p.flying;
        pressedJump = heldJump;
        // LocalPlayer::aiStep: двойной прыжок — полёт
        jumpTriggerTime--;
        if (p.mayfly && jumping && !p.jumping) {
            if (jumpTriggerTime <= 0) jumpTriggerTime = 7;
            else {
                p.flying = !p.flying;
                if (p.flying) p.yd = 0;
                jumpTriggerTime = 0;
            }
        }
        p.moveForward = ya;
        p.moveStrafe = xa;
        p.jumping = jumping;
    }

    // ---- отрисовка ----

    public void render(Graphics g, float a) {
        Player p = mc.player;
        Inventory inv = p.inv;
        int gw = R.gw, gh = R.gh;
        if (!inv.creative) {
            renderHearts(g, p);
            renderBubbles(g, p);
        }
        if (p.sleeping && p.sleepTimer > 0) {
            float amount = (float) p.sleepTimer / Player.SLEEP_DURATION;
            if (amount > 1) amount = 1f - (float) (p.sleepTimer - Player.SLEEP_DURATION) / Player.WAKE_UP_DURATION;
            R.fill(g, 0, 0, gw, gh, ((int) (220f * amount) << 24) | 0x101020);
        }
        renderToolBar(g, inv);
        renderChat(g);
        if (itemNameTime < 1f) {
            int id = inv.selId();
            if (id != 0) {
                String name = Lang.itemName(id, inv.selAux());
                R.shadow(g, name, gw / 2 - R.width(name) / 2, ySlot() - 22, 0xFFFFFF);
            }
        }
        // крестовина и пауза — только в сенсорном режиме
        if (mc.screen == null && mc.touchUI) renderControls(g);
    }

    /** Пустая рамка хотбара (превью на экране настройки управления). */
    void renderToolBarFrame(Graphics g) {
        int xBase = slotX(0), yBase = gh() - 22, slotsWidth = 20 * numSlots;
        R.blit(g, R.guiAT, xBase, yBase, 0, 0, slotsWidth, 22);
        R.blit(g, R.guiAT, xBase + slotsWidth, yBase, 180, 0, 2, 22);
    }

    private void renderToolBar(Graphics g, Inventory inv) {
        int xBase = slotX(0), yBase = gh() - 22, ySlot = ySlot();
        int slotsWidth = 20 * numSlots;
        R.blit(g, R.guiAT, xBase, yBase, 0, 0, slotsWidth, 22);
        R.blit(g, R.guiAT, xBase + slotsWidth, yBase, 180, 0, 2, 22);
        R.blit(g, R.guiAT, xBase - 1 + 20 * inv.selected, yBase - 1, 0, 22, 24, 22);
        if (flashSlot >= 0) {
            float since = (System.currentTimeMillis() - flashStart) / 1000f;
            if (since > 0.2f) flashSlot = -1;
            else {
                int x = slotX(flashSlot) + 2;
                int alpha = (int) (0x51 - 0x50 * Mth.cos(10 * 6.28f * since));
                R.fill(g, x, ySlot, x + 16, ySlot + 16, (alpha << 24) | 0xFFFFFF);
            }
        }
        float x = xBase + 3;
        for (int i = 0; i < numSlots - 1; i++) {
            R.item(g, inv.slotId(i), inv.slotAux(i), x, ySlot);
            x += 20;
        }
        // «...»: в оригинале этот квад рисуется уже с текстурой gui_blocks.png (484,504 28x8)
        R.blit(g, R.dots, R.gw / 2 + 10 * numSlots - 20 + 4, ySlot + 6, 14, 4, 0, 0, 28, 8);
        x = xBase + 3;
        for (int i = 0; i < numSlots - 1; i++) {
            if (!inv.creative) R.decorations(g, inv.slotId(i), inv.slotAux(i), x, ySlot);
            x += 20;
        }
        if (!inv.creative) {
            x = xBase + 3;
            for (int i = 0; i < numSlots - 1; i++) {
                if (inv.slotId(i) != 0) R.slotText(g, inv.slotCount(i), x, ySlot + 1 / R.k);
                x += 20;
            }
        }
    }

    private int gh() {
        return R.gh;
    }

    /** Gui::renderHearts 0.6.1: сердца и броня в левом верхнем углу. */
    private void renderHearts(Graphics g, Player p) {
        boolean blink = p.invulnerableTime >= 10 && (p.invulnerableTime / 3) % 2 == 1;
        int h = p.health, oh = p.lastHealth;
        rnd.setSeed(tickCount * 312871L);
        int armor = p.armorValue();
        for (int i = 0; i < 10; i++) {
            int yo = 2 + hudTop();
            int ip2 = i + i + 1;
            if (armor > 0) {
                int xo = 2 + 80 + i * 8 + 4;
                if (ip2 < armor) R.blit(g, R.icons, xo, yo, 16 + 2 * 9, 9, 9, 9);
                else if (ip2 == armor) R.blit(g, R.icons, xo, yo, 16 + 4 * 9, 9, 9, 9);
                else R.blit(g, R.icons, xo, yo, 16, 9, 9, 9);
            }
            int xo = 2 + i * 8;
            if (h <= 4) yo += rnd.nextInt(2) - 1;
            R.blit(g, R.icons, xo, yo, 16 + (blink ? 9 : 0), 0, 9, 9);
            if (blink) {
                if (ip2 < oh) R.blit(g, R.icons, xo, yo, 16 + 6 * 9, 0, 9, 9);
                else if (ip2 == oh) R.blit(g, R.icons, xo, yo, 16 + 7 * 9, 0, 9, 9);
            }
            if (ip2 < h) R.blit(g, R.icons, xo, yo, 16 + 4 * 9, 0, 9, 9);
            else if (ip2 == h) R.blit(g, R.icons, xo, yo, 16 + 5 * 9, 0, 9, 9);
        }
    }

    /** Custom: под счётчиком кадров в левом верхнем углу сердца и пузыри сдвигаются вниз. */
    static int hudTop() {
        return Options.showFps ? 10 : 0;
    }

    private void renderBubbles(Graphics g, Player p) {
        if (!p.isUnderWater()) return;
        int count = (int) Math.ceil((p.air - 2) * 10.0 / 300), extra = (int) Math.ceil(p.air * 10.0 / 300) - count;
        for (int i = 0; i < count + extra; i++) R.blit(g, R.icons, i * 8 + 2, 12 + hudTop(), i < count ? 16 : 25, 18, 9, 9);
    }

    /** Gui::renderChatMessages: снизу слева, гаснут через 10 секунд. */
    private void renderChat(Graphics g) {
        int baseY = R.gh - 48;
        for (int i = 0; i < messages.size() && i < 10; i++) {
            Object[] m = (Object[]) messages.elementAt(i);
            int ticks = ((int[]) m[1])[0];
            if (ticks >= 200) continue;
            float t = 1 - ticks / 200f;
            t = t * 10;
            if (t > 1) t = 1;
            t = t * t;
            int alpha = (int) (255 * t);
            if (alpha <= 0) continue;
            int y = baseY - i * 9;
            R.fill(g, 2, y - 1, 2 + R.gw, y + 8, (alpha / 2) << 24);
            if (alpha > 64) R.shadow(g, (String) m[0], 2, y, 0xFFFFFF);
        }
    }

    /** TouchscreenInput_TestFps::rebuild: кнопки крестовины полупрозрачные (gui.png 0,107 по 26x26). */
    void renderControls(Graphics g) {
        final int REL = 0, PRESSED = 1, DISCREET = 2;
        boolean north = !changingHeight && (northJump || forward);
        int u = 0, v = 107, sz = 26;
        tile(g, area[WEST], u + sz, v, north || changingHeight ? DISCREET : (down[WEST] ? PRESSED : REL));
        tile(g, area[E], u + sz * 3, v, north || changingHeight ? DISCREET : (down[E] ? PRESSED : REL));
        int st = down[N] ? PRESSED : REL;
        if (changingHeight) tile(g, area[N], u + sz * 2, v + sz, st);
        else tile(g, area[N], u, v, st);
        if (north) {
            tile(g, upLeft, u, v + sz, REL);
            tile(g, upRight, u + sz, v + sz, REL);
        }
        st = north ? DISCREET : (down[S] ? PRESSED : REL);
        if (changingHeight) tile(g, area[S], u + sz * 3, v + sz, st);
        else tile(g, area[S], u + sz * 2, v, st);
        st = renderFlight && north ? DISCREET : (down[C] ? PRESSED : REL);
        if (renderFlight) tile(g, area[C], u + sz * 4, v + sz, st);
        else tile(g, area[C], u + sz * 4, v, st);
        // пауза (iOS-версия 0.6.1: на Android её заменяет кнопка «Назад»)
        int ps = (int) pauseSize;
        boolean pd = mc.pointerDown(MC.P_PAUSE);
        R.stretchedPx(g, R.gui, (int) pauseX, (int) pauseY, ps, ps, 200, 64, 18, 18, 80, pd ? 192 : 255);
    }

    private void tile(Graphics g, float[] r, int u, int v, int state) {
        int alpha = state == 2 ? 32 : 128, mul = state == 1 ? 192 : 255;
        int x0 = (int) r[0], y0 = (int) r[1];
        R.stretchedPx(g, R.gui, x0, y0, (int) r[2] - x0, (int) r[3] - y0, u, v, 26, 26, alpha, mul);
    }

    // ---- круг-отклик касания (Gui::renderProgressIndicator) ----

    private final Image[] rings = new Image[17], discs = new Image[11];
    private int ringR;

    /**
     * Gui::renderToolBar (сенсор): тонкое кольцо радиусом 40 единиц GUI (на телефоне ~0.13 высоты экрана), альфа —
     * TouchInputHolder::alpha: 0.8a над целью, иначе не больше 0.4; при копании — диск 0.5..1 радиуса по прогрессу.
     */
    public void renderFeedback(Graphics g, int x, int y, float alpha, boolean hit, float progress) {
        int r = Math.max(8, (int) (mc.H * 0.129f));
        if (ringR != r) {
            ringR = r;
            for (int i = 0; i < rings.length; i++) rings[i] = null;
            for (int i = 0; i < discs.length; i++) discs[i] = null;
        }
        alpha = Mth.clamp(alpha, 0, 1);
        float ra = progress > 0 ? 0.8f * alpha : (hit ? 0.8f * alpha : Math.min(0.4f, alpha * 0.4f));
        int ai = (int) (ra * 16 + 0.5f);
        if (ai > 0) {
            if (rings[ai] == null) rings[ai] = circle(r, 0.95f, ((ai * 255 / 16) << 24) | 0xFFFFFF);
            if (MemDbg.on) MemDbg.drawn(rings[ai]);
            g.drawImage(rings[ai], x - r, y - r, Graphics.TOP | Graphics.LEFT);
        }
        if (progress > 0) {
            int st = Math.min(10, (int) (progress * 10));
            if (discs[st] == null) discs[st] = circle(Math.max(1, (int) (r * 0.95f * (0.5f + st * 0.05f))), 0f, 0x80FFFFFF);
            Image d = discs[st];
            if (MemDbg.on) MemDbg.drawn(d);
            g.drawImage(d, x - d.getWidth() / 2, y - d.getHeight() / 2, Graphics.TOP | Graphics.LEFT);
        }
    }

    private static Image circle(int r, float inner, int argb) {
        int d = r * 2;
        int[] p = new int[d * d];
        float r2 = r * r, i2 = (r * inner) * (r * inner);
        for (int y = 0; y < d; y++)
            for (int x = 0; x < d; x++) {
                float dx = x + 0.5f - r, dy = y + 0.5f - r, q = dx * dx + dy * dy;
                if (q <= r2 && q >= i2) p[y * d + x] = argb;
            }
        Image im = Image.createRGBImage(p, d, d, true);
        if (MemDbg.on) MemDbg.img(im, "Gui.circle");
        return im;
    }
}
