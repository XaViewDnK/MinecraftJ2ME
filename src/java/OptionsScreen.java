import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/**
 * Настройки главного меню. На Android 0.6.1 это MainMenuOptionsActivity (preferences.xml): чёрный список
 * с категориями, флажками и ползунком «Sensitivity». Флажки графики оригинала (Fancy / Lower graphics quality)
 * заменены двумя ползунками прямо в строках списка: качество Low/Medium/High и дальность 1..4 чанка
 * (полоса — касанием, строка — следующее значение, клавиши влево/вправо). Рисуется системным шрифтом в пикселях экрана;
 * стрелка «назад» в заголовке заменяет системную кнопку «Назад».
 */
public final class OptionsScreen extends Screen {
    private static final int CAT = 0, CHECK = 1, EDIT = 2, SLIDER = 3;
    private static final String[][] ITEMS = {
            { "c", "Multiplayer" },
            { "e", "Username", "" },
            { "b", "Server visible by default", "" },
            { "c", "Graphics" },
            { "i", "Graphics", "" },
            { "i", "Render distance", "" },
            { "c", "Controls" },
            { "s", "Sensitivity", "" },
            { "b", "Invert Y-axis", "" },
            { "b", "Lefty", "" },
            { "c", "Feedback" },
            { "b", "Vibration", "Slight vibration when blocks are destroyed" },
            { "c", "Game" },
            { "b", "Peaceful mode", "No hostile mobs, heal automatically" },
            // Custom — отступление от оригинала ради удобства
            { "c", "Custom" },
            { "b", "Show FPS", "Frames per second in the top left corner" },
            { "b", "Auto jump", "Jump onto one-block obstacles automatically" },
            { "b", "Show name tags", "Player names above heads in multiplayer" },
            { "o", "Screen orientation", "" },
            { "a", "Touch controls", "Position and size of the D-Pad and the pause button" },
            { "k", "Keys", "Reassign keypad and QWERTY keys" },
    };
    private Font fTitle = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_LARGE);
    private final Font fSum = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    private final Font fCat = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_SMALL);
    private int W, H, barH, scroll, maxScroll, pad;
    private int[] itemY, itemH;
    private int downX, downY, downScroll, pressedItem = -1;
    private boolean dragging, backDown;
    // диалог ползунка
    private boolean slider;
    private int sliderValue, sliderDown = -1; // 0 полоса, 1 Cancel, 2 OK
    /** ползунок в строке, который сейчас тянут пальцем (-1 — нет) */
    private int dragItem = -1;

    protected void setupPositions() {
        W = mc.W;
        H = mc.H;
        pad = Math.max(6, fSum.getHeight() / 2);
        barH = fCat.getHeight() + pad + 4;
        // на узком экране (240x320) крупный шрифт не помещается рядом с флажком — средний, длинное переносится
        fTitle = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_LARGE);
        int widest = 0;
        for (int i = 0; i < ITEMS.length; i++) if (!ITEMS[i][0].equals("c")) widest = Math.max(widest, fTitle.stringWidth(ITEMS[i][1]));
        if (widest > textW()) fTitle = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_MEDIUM);
        itemY = new int[ITEMS.length];
        itemH = new int[ITEMS.length];
        titleLines = new String[ITEMS.length][];
        sumLines = new String[ITEMS.length][];
        int y = barH;
        for (int i = 0; i < ITEMS.length; i++) {
            itemY[i] = y;
            if (ITEMS[i][0].equals("c")) itemH[i] = fCat.getHeight() + 6;
            else {
                titleLines[i] = wrap(fTitle, ITEMS[i][1], textW());
                sumLines[i] = wrap(fSum, summary(i), textW());
                itemH[i] = fTitle.getHeight() * titleLines[i].length + 2 * pad + 4 + fSum.getHeight() * sumLines[i].length;
                if (ITEMS[i][0].equals("i")) itemH[i] += inBarH();
            }
            y += itemH[i];
        }
        maxScroll = Math.max(0, y - H);
        if (scrollToEnd) {
            scrollToEnd = false;
            scroll = maxScroll;
        }
        if (scroll > maxScroll) scroll = maxScroll;
        if (focusItem < 0) focusItem = nextItem(-1, 1);
    }

    private String[][] titleLines, sumLines;
    /** открыть прокрученным к концу (возврат из ControlsScreen к разделу Custom) */
    boolean scrollToEnd;

    /** Ширина текста пункта: от отступа слева до флажка. */
    private int textW() {
        return W - 2 * pad - checkSize() - 3 * pad;
    }

    /** Перенос по словам (слово длиннее строки режется). */
    private static String[] wrap(Font f, String t, int maxW) {
        java.util.Vector v = new java.util.Vector();
        int start = 0;
        while (start < t.length()) {
            int end = t.length();
            while (end > start + 1 && f.stringWidth(t.substring(start, end)) > maxW) {
                int sp = t.lastIndexOf(' ', end - 1);
                end = sp > start ? sp : end - 1;
            }
            v.addElement(t.substring(start, end).trim());
            start = end;
            while (start < t.length() && t.charAt(start) == ' ') start++;
        }
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }

    private int checkSize() {
        return fTitle.getHeight() + 4;
    }

    private String summary(int i) {
        if (i == 1) return "'" + Options.username + "'";
        if (i == 4 || i == 5) return inName(i) + " - " + (i == 4 ? GFX_SUMMARY[Options.graphics] : Options.viewChunks * 16 + " blocks");
        if (i == 18) return Options.ORIENTATION_NAME[Options.orientation];
        return ITEMS[i].length > 2 ? ITEMS[i][2] : "";
    }

    private static final String[] GFX_SUMMARY = { "Fast leaves, no sky and clouds, plain darker fog",
            "Fast leaves, no sky and clouds", "Fancy leaves, sky and clouds" };

    private boolean enabled(int i) {
        return true;
    }

    private boolean value(int i) {
        switch (i) {
            case 2: return Options.serverVisible;
            case 8: return Options.invertY;
            case 9: return Options.lefty;
            case 11: return Options.vibration;
            case 13: return Options.peaceful;
            case 15: return Options.showFps;
            case 16: return Options.autoJump;
            case 17: return Options.nameTags;
        }
        return false;
    }

    private void toggle(int i) {
        switch (i) {
            case 2: Options.serverVisible = !Options.serverVisible; break;
            case 8: Options.invertY = !Options.invertY; break;
            case 9: Options.lefty = !Options.lefty; break;
            case 11: Options.vibration = !Options.vibration; break;
            case 13: Options.peaceful = !Options.peaceful; break;
            case 15: Options.showFps = !Options.showFps; break;
            case 16: Options.autoJump = !Options.autoJump; break;
            case 17: Options.nameTags = !Options.nameTags; break;
        }
        Options.save();
    }

    public boolean renderGameBehind() {
        return false;
    }

    public void render(Graphics g) {
        g.setColor(0);
        g.fillRect(0, 0, W, H);
        for (int i = 0; i < ITEMS.length; i++) {
            int y = itemY[i] - scroll;
            if (y + itemH[i] < barH || y > H) continue;
            renderItem(g, i, y);
        }
        // заголовок окна
        g.setColor(0x9A9A9A);
        g.fillRect(0, 0, W, barH);
        g.setColor(0xB4B4B4);
        g.fillRect(0, 0, W, barH / 2);
        g.setFont(fCat);
        g.setColor(0xFFFFFF);
        int ax = pad, ay = barH / 2;
        // стрелка «назад»
        int as = barH / 3;
        g.setColor(backDown ? 0x606060 : 0xFFFFFF);
        g.fillTriangle(ax, ay, ax + as, ay - as, ax + as, ay + as);
        g.fillRect(ax + as - 1, ay - as / 4, as, as / 2 + 1);
        g.setColor(0xFFFFFF);
        // заголовок окна; на узком экране — с многоточием (как TextView ellipsize)
        String title = "Minecraft - Pocket Edition";
        int tw = W - (ax + 2 * as + pad) - pad;
        if (fCat.stringWidth(title) > tw) {
            while (title.length() > 1 && fCat.stringWidth(title + "...") > tw) title = title.substring(0, title.length() - 1);
            title = title.trim() + "...";
        }
        g.drawString(title, ax + 2 * as + pad, (barH - fCat.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
        if (slider) renderSlider(g);
    }

    private void renderItem(Graphics g, int i, int y) {
        String[] it = ITEMS[i];
        if (it[0].equals("c")) {
            g.setColor(0x262626);
            g.fillRect(0, y, W, itemH[i]);
            g.setColor(0x3A3A3A);
            g.drawLine(0, y, W, y);
            g.setFont(fCat);
            g.setColor(0xC8C8C8);
            g.drawString(it[1], pad, y + 3, Graphics.TOP | Graphics.LEFT);
            return;
        }
        boolean en = enabled(i);
        if (pressedItem == i && !dragging && en) {
            g.setColor(0x303030);
            g.fillRect(0, y, W, itemH[i]);
        } else if (keyFocus && focusItem == i) {
            // фокус клавиш (выделение строки списка Android)
            g.setColor(0x3A4A5A);
            g.fillRect(0, y, W, itemH[i]);
        }
        int tx = 2 * pad;
        g.setFont(fTitle);
        g.setColor(en ? 0xFFFFFF : 0x808080);
        int ty = y + pad + 2;
        String[] tl = titleLines[i], sl = sumLines[i];
        for (int k = 0; k < tl.length; k++) g.drawString(tl[k], tx, ty + k * fTitle.getHeight(), Graphics.TOP | Graphics.LEFT);
        ty += tl.length * fTitle.getHeight();
        g.setFont(fSum);
        g.setColor(en ? 0xB0B0B0 : 0x707070);
        for (int k = 0; k < sl.length; k++) g.drawString(sl[k], tx, ty + k * fSum.getHeight(), Graphics.TOP | Graphics.LEFT);
        if (it[0].equals("i")) renderInline(g, i, y);
        if (it[0].equals("b")) {
            int cs = checkSize(), cx = W - 2 * pad - cs, cy = y + (itemH[i] - cs) / 2;
            g.setColor(en ? 0xB0B0B0 : 0x505050);
            g.fillRoundRect(cx, cy, cs, cs, cs / 5, cs / 5);
            g.setColor(0x1A1A1A);
            g.fillRoundRect(cx + 2, cy + 2, cs - 4, cs - 4, cs / 6, cs / 6);
            if (value(i)) {
                g.setColor(en ? 0x8BC34A : 0x606060);
                int t = Math.max(2, cs / 7);
                for (int d = 0; d < t; d++) {
                    g.drawLine(cx + cs / 4, cy + cs / 2 + d, cx + cs * 5 / 12, cy + cs * 7 / 10 + d);
                    g.drawLine(cx + cs * 5 / 12, cy + cs * 7 / 10 + d, cx + cs * 3 / 4, cy + cs * 3 / 10 + d);
                }
            }
        }
        g.setColor(0x262626);
        g.drawLine(pad, y + itemH[i] - 1, W - pad, y + itemH[i] - 1);
    }

    // ---- диалог ползунка (SliderPreference) ----

    private int dlgX, dlgY, dlgW, dlgH, barX, barY, barW, btnY, btnH;

    private void sliderLayout() {
        dlgW = Math.min(W - 2 * pad, Math.max(W * 3 / 4, 240));
        int valH = fTitle.getHeight() * 3 / 2;
        btnH = fTitle.getHeight() + 2 * pad;
        dlgH = fTitle.getHeight() + 2 * pad + valH + 4 * pad + btnH + pad;
        dlgX = (W - dlgW) / 2;
        dlgY = (H - dlgH) / 2;
        barX = dlgX + 3 * pad;
        barW = dlgW - 6 * pad;
        barY = dlgY + fTitle.getHeight() + 2 * pad + valH + 2 * pad;
        btnY = dlgY + dlgH - btnH - pad / 2;
    }

    private void renderSlider(Graphics g) {
        MC.fillAlpha(g, 0, 0, W, H, 0x99000000);
        sliderLayout();
        g.setColor(0x2B2B2B);
        g.fillRoundRect(dlgX, dlgY, dlgW, dlgH, pad, pad);
        g.setFont(fTitle);
        g.setColor(0xFFFFFF);
        g.drawString("Sensitivity", dlgX + 2 * pad, dlgY + pad, Graphics.TOP | Graphics.LEFT);
        g.drawString(String.valueOf(sliderValue), W / 2, dlgY + fTitle.getHeight() + 2 * pad, Graphics.TOP | Graphics.HCENTER);
        g.setColor(0x606060);
        g.fillRect(barX, barY - 1, barW, 3);
        int tx = barX + barW * sliderValue / 100;
        g.setColor(0x8BC34A);
        g.fillRect(barX, barY - 1, tx - barX, 3);
        int r = pad;
        g.fillArc(tx - r, barY - r, 2 * r, 2 * r, 0, 360);
        g.setFont(fCat);
        g.setColor(sliderDown == 1 ? 0x606060 : 0x8BC34A);
        g.drawString("CANCEL", dlgX + dlgW - 3 * pad - fCat.stringWidth("OK") - fCat.stringWidth("CANCEL") - 2 * pad, btnY + (btnH - fCat.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
        g.setColor(sliderDown == 2 ? 0x606060 : 0x8BC34A);
        g.drawString("OK", dlgX + dlgW - 3 * pad - fCat.stringWidth("OK"), btnY + (btnH - fCat.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
    }

    private int sliderHit(int x, int y) {
        if (y >= barY - 3 * pad && y <= barY + 3 * pad && x >= barX - 2 * pad && x <= barX + barW + 2 * pad) return 0;
        if (y >= btnY && y < btnY + btnH) {
            int okX = dlgX + dlgW - 3 * pad - fCat.stringWidth("OK");
            if (x >= okX - pad) return 2;
            if (x >= dlgX + dlgW / 3) return 1;
        }
        return -1;
    }

    private void sliderSet(int x) {
        sliderValue = Mth.clamp((x - barX) * 100 / Math.max(1, barW), 0, 100);
    }

    private void sliderApply() {
        Options.sensitivitySlider = sliderValue;
        Options.save();
        slider = false;
    }

    // ---- ползунки в строке списка (Graphics, Render distance) ----

    private static int inMin(int i) {
        return i == 4 ? Options.GFX_LOW : Options.VIEW_MIN;
    }

    private static int inMax(int i) {
        return i == 4 ? Options.GFX_HIGH : Options.VIEW_MAX;
    }

    private static int inValue(int i) {
        return i == 4 ? Options.graphics : Options.viewChunks;
    }

    private static String inName(int i) {
        return i == 4 ? Options.GRAPHICS_NAME[Options.graphics] : Options.viewChunks + (Options.viewChunks == 1 ? " chunk" : " chunks");
    }

    /** высота полосы под текстом строки */
    private int inBarH() {
        return 4 * pad;
    }

    private int inBarX0() {
        return 3 * pad;
    }

    private int inBarX1() {
        return W - 3 * pad;
    }

    /** середина полосы строки i в координатах экрана (с учётом прокрутки) */
    private int inBarY(int i) {
        return itemY[i] - scroll + itemH[i] - pad - inBarH() / 2;
    }

    /** новое значение: применить сразу (дальность и листва — через applyOptions), подписи пересчитать */
    private void inSet(int i, int v) {
        v = Mth.clamp(v, inMin(i), inMax(i));
        if (v == inValue(i)) return;
        if (i == 4) Options.graphics = v;
        else {
            Options.viewChunks = v;
            // игрок сам выбрал дальность — счёт снижений из-за нехватки памяти заново
            mc.viewCut = 0;
        }
        mc.applyOptions();
        setupPositions();
    }

    private void inSetX(int i, int x) {
        int n = inMax(i) - inMin(i), w = Math.max(1, inBarX1() - inBarX0());
        inSet(i, inMin(i) + Mth.clamp(((x - inBarX0()) * n * 2 / w + 1) / 2, 0, n));
    }

    private void renderInline(Graphics g, int i, int y) {
        int x0 = inBarX0(), x1 = inBarX1(), by = y + itemH[i] - pad - inBarH() / 2;
        int lo = inMin(i), n = inMax(i) - lo, v = inValue(i) - lo;
        int tx = x0 + (x1 - x0) * v / n;
        g.setColor(0x606060);
        g.fillRect(x0, by - 1, x1 - x0, 3);
        for (int k = 0; k <= n; k++) {
            int sx = x0 + (x1 - x0) * k / n;
            g.setColor(k <= v ? 0x8BC34A : 0x909090);
            g.fillRect(sx - 1, by - pad / 2, 3, pad);
        }
        g.setColor(0x8BC34A);
        g.fillRect(x0, by - 1, tx - x0, 3);
        int rr = dragItem == i ? pad * 3 / 2 : pad;
        g.fillArc(tx - rr, by - rr, 2 * rr, 2 * rr, 0, 360);
    }

    // ---- касания (переводим пиксели GUI обратно в пиксели экрана) ----

    protected void mouseClicked(int gx, int gy) {
        int x = (int) (gx * R.k), y = (int) (gy * R.k);
        if (slider) {
            sliderDown = sliderHit(x, y);
            if (sliderDown == 0) sliderSet(x);
            return;
        }
        // полоса ползунка в строке: палец тянет значение, список не прокручивается
        for (int i = 0; i < ITEMS.length; i++) {
            if (!ITEMS[i][0].equals("i") || y < barH) continue;
            int by = inBarY(i);
            if (y >= by - inBarH() / 2 - pad && y <= by + inBarH() / 2 + pad && x >= inBarX0() - 2 * pad && x <= inBarX1() + 2 * pad) {
                dragItem = i;
                pressedItem = -1;
                inSetX(i, x);
                return;
            }
        }
        downX = x;
        downY = y;
        downScroll = scroll;
        dragging = false;
        backDown = y < barH && x < barH * 2;
        pressedItem = -1;
        if (!backDown && y >= barH) {
            for (int i = 0; i < ITEMS.length; i++)
                if (y + scroll >= itemY[i] && y + scroll < itemY[i] + itemH[i] && !ITEMS[i][0].equals("c")) pressedItem = i;
        }
    }

    protected void mouseDragged(int gx, int gy) {
        int x = (int) (gx * R.k), y = (int) (gy * R.k);
        if (slider) {
            if (sliderDown == 0) sliderSet(x);
            return;
        }
        if (dragItem >= 0) {
            inSetX(dragItem, x);
            return;
        }
        if (Math.abs(y - downY) > pad) dragging = true;
        if (dragging) scroll = Mth.clamp(downScroll - (y - downY), 0, maxScroll);
    }

    protected void mouseReleased(int gx, int gy) {
        int x = (int) (gx * R.k), y = (int) (gy * R.k);
        if (slider) {
            int d = sliderDown;
            sliderDown = -1;
            if (d > 0 && sliderHit(x, y) == d) {
                if (d == 2) sliderApply();
                slider = false;
            }
            return;
        }
        if (dragItem >= 0) {
            inSetX(dragItem, x);
            dragItem = -1;
            Options.save();
            return;
        }
        if (backDown) {
            backDown = false;
            if (y < barH && x < barH * 2) back();
            return;
        }
        int i = pressedItem;
        pressedItem = -1;
        if (dragging || i < 0 || !enabled(i)) return;
        if (y + scroll < itemY[i] || y + scroll >= itemY[i] + itemH[i]) return;
        activate(i);
    }

    // ---- клавиши: вверх/вниз по пунктам, OK — переключить; в ползунке влево/вправо, OK — сохранить ----

    private int focusItem = -1;

    private int nextItem(int from, int d) {
        for (int i = from + d; i >= 0 && i < ITEMS.length; i += d) if (!ITEMS[i][0].equals("c") && enabled(i)) return i;
        return -1;
    }

    public void keyNav(int dir) {
        if (slider) {
            if (dir == LEFT) sliderValue = Math.max(0, sliderValue - 5);
            if (dir == RIGHT) sliderValue = Math.min(100, sliderValue + 5);
            return;
        }
        // ползунок в строке: влево/вправо у выделенного пункта
        if (keyFocus && focusItem >= 0 && ITEMS[focusItem][0].equals("i") && (dir == LEFT || dir == RIGHT)) {
            inSet(focusItem, inValue(focusItem) + (dir == RIGHT ? 1 : -1));
            Options.save();
            return;
        }
        if (focusItem < 0) focusItem = nextItem(-1, 1);
        else if (keyFocus && (dir == UP || dir == DOWN)) {
            int n = nextItem(focusItem, dir == UP ? -1 : 1);
            if (n >= 0) focusItem = n;
            else if (dir == UP) scroll = 0;
        }
        keyFocus = true;
        // прокрутить к пункту (с заголовком категории над ним)
        int top = itemY[focusItem] - barH, bot = itemY[focusItem] + itemH[focusItem] - H;
        if (focusItem > 0 && ITEMS[focusItem - 1][0].equals("c")) top -= itemH[focusItem - 1];
        if (scroll > top) scroll = Math.max(0, top);
        if (scroll < bot) scroll = Math.min(maxScroll, bot);
    }

    public void keyOk(boolean down) {
        if (!down) return;
        if (slider) {
            sliderApply();
            return;
        }
        if (!keyFocus || focusItem < 0) {
            keyNav(DOWN);
            return;
        }
        activate(focusItem);
    }

    public void renderFocus(Graphics g) {
    }

    private void activate(int i) {
        if (ITEMS[i][0].equals("b")) toggle(i);
        else if (ITEMS[i][0].equals("o")) {
            Options.orientation = (Options.orientation + 1) % Options.ORIENTATION_NAME.length;
            Options.save();
            // смена размера кадра пересобирает разметку экрана (setSize -> setupPositions)
            mc.applyOrientation();
            setupPositions();
        } else if (ITEMS[i][0].equals("a")) {
            Options.save();
            mc.setScreen(new ControlsScreen());
        } else if (ITEMS[i][0].equals("k")) {
            Options.save();
            mc.setScreen(new KeysScreen());
        }
        else if (ITEMS[i][0].equals("s")) {
            sliderValue = Options.sensitivitySlider;
            slider = true;
        } else if (ITEMS[i][0].equals("i")) {
            // тап по строке / OK — следующее значение по кругу (кнопочные телефоны без влево/вправо)
            int v = inValue(i) + 1;
            inSet(i, v > inMax(i) ? inMin(i) : v);
            Options.save();
        } else if (ITEMS[i][0].equals("e")) {
            mc.editText("Enter your username", Options.username, 32, new MC.TextResult() {
                public void done(String text) {
                    if (text == null) return;
                    // EditTextAscii.sanitize + trim; пустое — прежнее имя
                    StringBuffer sb = new StringBuffer();
                    for (int k = 0; k < text.length(); k++) {
                        char c = text.charAt(k);
                        if (c >= 32 && c < 127) sb.append(c);
                    }
                    String s = sb.toString().trim();
                    if (s.length() > 0) Options.username = s;
                    Options.save();
                    setupPositions();
                }
            });
        }
    }

    public void back() {
        if (slider) {
            slider = false;
            return;
        }
        Options.save();
        mc.setScreen(new StartMenuScreen());
    }
}
