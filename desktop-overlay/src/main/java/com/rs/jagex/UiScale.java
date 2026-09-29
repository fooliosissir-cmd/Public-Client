package com.rs.jagex;

import com.rs.Loader;
import com.rs.jagex.clans.settings.ChangeClanSetting;
import java.awt.*;
import java.util.prefs.Preferences;
import javax.swing.*;

/** Game-pixel scaling. AWT and the native rendering surface stay in physical pixels. */
public final class UiScale {
    private static final int[] CHOICES = {0, 100, 125, 150, 200, 250, 300, 400};
    private static volatile int selected = readPreference();
    private static volatile boolean changed;
    private static volatile Geometry active = new Geometry(765, 553, 765, 553);
    private static volatile boolean unavailable;
    private static AbstractRenderer unavailableRenderer;
    private static int appliedPercent;

    public record Geometry(int logicalWidth, int logicalHeight, int physicalWidth, int physicalHeight) {
        public Geometry {
            if (logicalWidth < 1 || logicalHeight < 1 || physicalWidth < 1 || physicalHeight < 1)
                throw new IllegalArgumentException("Positive display dimensions required");
        }
        public int toLogicalX(int x) { return (int)Math.floorDiv((long)x * logicalWidth, physicalWidth); }
        public int toLogicalY(int y) { return (int)Math.floorDiv((long)y * logicalHeight, physicalHeight); }
        public int toPhysicalX(int x) { return (int)Math.floorDiv((long)x * physicalWidth, logicalWidth); }
        public int toPhysicalY(int y) { return (int)Math.floorDiv((long)y * physicalHeight, logicalHeight); }
        public boolean scaled() { return logicalWidth != physicalWidth || logicalHeight != physicalHeight; }
    }

    public static int autoPercent(int width, int height) {
        if (width >= 7000 && height >= 3800) return 400;
        if (width >= 5000 && height >= 2700) return 250;
        if (width >= 3400 && height >= 1900) return 200;
        if (width >= 2400 && height >= 1300) return 150;
        return 100;
    }

    public static Geometry geometry(int width, int height, boolean fixed, int percent) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        if (percent == 0) percent = autoPercent(width, height);
        if (!valid(percent) || percent == 0) throw new IllegalArgumentException("Unsupported scale");
        double scale = percent / 100.0;
        // Both layouts keep enough logical room for this revision's native interface.
        // A deliberately tiny window can scale down, but never crops the fixed inventory/chat area.
        scale = Math.min(scale, Math.min(width / 765.0, height / 553.0));
        if (fixed) {
            return new Geometry(765, 553, Math.max(1, (int)Math.floor(765 * scale)), Math.max(1, (int)Math.floor(553 * scale)));
        }
        return new Geometry(Math.max(765, (int)Math.floor(width / scale)), Math.max(553, (int)Math.floor(height / scale)), width, height);
    }

    public static Geometry active() { return active; }
    static void useGeometry(Geometry geometry) { active = geometry; }
    public static int physicalWidth() { return active.physicalWidth(); }
    public static int physicalHeight() { return active.physicalHeight(); }
    public static int pointerX(int x) { return active.toLogicalX(x); }
    public static int pointerY(int y) { return active.toLogicalY(y); }

    private static boolean valid(int percent) {
        for (int choice : CHOICES) if (choice == percent) return true;
        return false;
    }
    private static int readPreference() {
        try {
            int value = Preferences.userRoot().node("burialgrounds/display").getInt("scalePercent", 0);
            return valid(value) ? value : 0;
        } catch (RuntimeException denied) { return 0; }
    }
    private static int requestedPercent() {
        if (unavailable) return 100;
        if (selected != 0) return selected;
        try {
            GraphicsConfiguration config = Loader.INSTANCE.clientFrame.getGraphicsConfiguration();
            Rectangle bounds = config.getBounds();
            return autoPercent(bounds.width, bounds.height);
        } catch (RuntimeException failure) { return autoPercent(SunIndexLoader.anInt434, Class107.anInt1082); }
    }

    public static Dimension initialWindowSize(GraphicsConfiguration config) {
        Rectangle bounds = config.getBounds();
        int percent = selected == 0 ? autoPercent(bounds.width, bounds.height) : selected;
        Geometry size = geometry(bounds.width - 80, bounds.height - 100, true, percent);
        return new Dimension(Math.max(774, size.physicalWidth()), Math.max(588, size.physicalHeight()));
    }

    public static int selectedPercent() { return selected; }

    /** Called by native in-game controls; actual layout changes run on the game thread. */
    public static void setPercent(int percent) {
        if (!valid(percent)) throw new IllegalArgumentException("Unsupported scale");
        selected = percent;
        unavailable = false;
        unavailableRenderer = null;
        try { Preferences.userRoot().node("burialgrounds/display").putInt("scalePercent", percent); }
        catch (RuntimeException failure) { System.err.println("Game scale preference could not be saved."); }
        changed = true;
    }

    public static String statusString() {
        if (unavailable) return "Game scale unavailable on this renderer; using 100%.";
        String choice = selected == 0 ? "Auto" : selected + "%";
        return "Game scale: " + choice + ". Enlarges the whole game and UI; higher scale lowers rendering resolution.";
    }

    static void pollPreference() {
        FullscreenSupport.pollCloseRequest();
        retryForRenderer(Renderers.CURRENT_RENDERER);
        if (selected == 0 && appliedPercent != requestedPercent()) changed = true;
        if (!changed) return;
        changed = false;
        if (Class158.getScreenMode() == 1) resizeFixedWindow();
        UID192.method7373(Class158.getScreenMode(), -1, -1);
    }

    static void resizeFixedWindow() {
        if (Loader.INSTANCE == null || Engine.fullScreenFrame != null) return;
        JFrame frame = Loader.INSTANCE.clientFrame;
        if (frame == null) return;
        EventQueue.invokeLater(() -> {
            Rectangle bounds = frame.getGraphicsConfiguration().getBounds();
            Insets screen = Toolkit.getDefaultToolkit().getScreenInsets(frame.getGraphicsConfiguration());
            Insets frameInsets = frame.getInsets();
            int menuHeight = frame.getJMenuBar() == null ? 0 : frame.getJMenuBar().getHeight();
            Geometry size = geometry(bounds.width - screen.left - screen.right - frameInsets.left - frameInsets.right,
                    bounds.height - screen.top - screen.bottom - frameInsets.top - frameInsets.bottom - menuHeight, true, requestedPercent());
            frame.setExtendedState(Frame.NORMAL);
            frame.getContentPane().setPreferredSize(new Dimension(size.physicalWidth(), size.physicalHeight()));
            frame.pack();
        });
    }

    static void applyLayout(boolean fixed, int width, int height) {
        appliedPercent = requestedPercent();
        Geometry geometry = geometry(width, height, fixed, appliedPercent);
        active = geometry;
        ChangeClanSetting.BASE_WINDOW_WIDTH = geometry.logicalWidth();
        Engine.BASE_WINDOW_HEIGHT = geometry.logicalHeight() * -1929118563;
        Engine.GAME_CANVAS_X = Math.max(0, (SunIndexLoader.anInt434 - geometry.physicalWidth()) / 2);
        Engine.GAME_CANVAS_Y = 0;
    }

    static int scaledLimit(int logicalLimit) {
        return logicalLimit * requestedPercent() / 100;
    }

    /** A software loading renderer must not permanently disable the user's later hardware renderer. */
    static boolean retryForRenderer(AbstractRenderer renderer) {
        if (!unavailable || renderer == null || renderer == unavailableRenderer
                || !(renderer instanceof OpenGLRenderer || renderer instanceof HardwareRenderer)) return false;
        unavailable = false;
        unavailableRenderer = null;
        changed = true;
        return true;
    }

    static void unavailable(AbstractRenderer renderer, Throwable cause) {
        if (unavailable && unavailableRenderer == renderer) return;
        unavailable = true;
        unavailableRenderer = renderer;
        changed = true;
        System.err.println("Game scaling unavailable on this renderer (" + cause.getClass().getSimpleName() + "). Keeping renderer at 100%.");
    }
}
