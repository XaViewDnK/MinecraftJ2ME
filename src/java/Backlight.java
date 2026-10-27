import com.nokia.mid.ui.DeviceControl;

/**
 * Подсветка экрана не гаснет, пока игра на экране (как FLAG_KEEP_SCREEN_ON у MCPE на Android): Nokia UI API
 * DeviceControl. Создаётся по имени из MC (Class.forName), только если API есть на телефоне.
 */
public final class Backlight implements Runnable {
    public void run() {
        DeviceControl.setLights(0, 100);
    }
}
