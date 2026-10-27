import javax.microedition.lcdui.Display;
import javax.microedition.midlet.MIDlet;

/** Точка входа: Minecraft - Pocket Edition 0.6.1 для J2ME (M3G). */
public class MinecraftMIDlet extends MIDlet {
    private MC mc;

    protected void startApp() {
        if (mc == null) {
            // сборка Link (отладка по Bluetooth): атрибут MCJ-Link в манифесте
            Link.start(getAppProperty("MCJ-Link"));
            if (MemDbg.on) {
                MemDbg.boot();
                MemDbg.mark("boot");
            }
            mc = new MC(this);
            Display.getDisplay(this).setCurrent(mc);
            mc.start();
        } else {
            Display.getDisplay(this).setCurrent(mc);
        }
    }

    protected void pauseApp() {
        if (mc != null && mc.level != null) {
            mc.saveNow();
            if (mc.screen == null) mc.setScreen(new PauseScreen());
        }
    }

    protected void destroyApp(boolean unconditional) {
        if (mc != null) {
            mc.stop();
            mc.saveNow();
        }
    }
}
