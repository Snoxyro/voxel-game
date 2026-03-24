package com.voxelgame.game;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Persistent user settings backed by a {@code settings.properties} file.
 *
 * <p>Owned by {@code GameLoop} and passed via constructor injection to every
 * screen or system that needs it. Never a static singleton — Phase 7 mod
 * settings will use separate {@code GameSettings} instances pointing to
 * different property files.
 *
 * <h3>Save format</h3>
 * Plain Java {@link Properties} file. Keybindings are delegated to
 * {@link KeyBindings} which writes {@code keybind.ACTION_NAME=code} entries
 * into the same file.
 *
 * <h3>Atomic save</h3>
 * Settings are written to a {@code .tmp} file first, then atomically renamed
 * over the real file. A crash mid-write leaves the old file intact.
 */
public class GameSettings {

    private static final Logger LOGGER = Logger.getLogger(GameSettings.class.getName());

    // ── Property keys ─────────────────────────────────────────────────────────

    private static final String KEY_USERNAME        = "username";
    private static final String KEY_RENDER_DISTANCE = "renderDistance";
    private static final String KEY_FOV             = "fov";
    private static final String KEY_MOUSE_SENS      = "mouseSensitivity";
    private static final String KEY_THEME           = "theme";
    private static final String KEY_VSYNC           = "vsync";
    private static final String KEY_WINDOW_MODE     = "windowMode";

    // ── Defaults ──────────────────────────────────────────────────────────────

    public static final String  DEFAULT_USERNAME        = "Player";
    public static final int     DEFAULT_RENDER_DISTANCE = 8;
    public static final int     DEFAULT_FOV             = 70;
    public static final float   DEFAULT_MOUSE_SENS      = 1.0f;
    public static final String  DEFAULT_THEME           = "dark";
    public static final boolean DEFAULT_VSYNC           = true;
    public static final WindowMode DEFAULT_WINDOW_MODE  = WindowMode.WINDOWED;

    // ── Window mode enum ──────────────────────────────────────────────────────

    /**
     * Display mode options for the game window.
     * Stored as lowercase name in the properties file.
     */
    public enum WindowMode {
        WINDOWED    ("Windowed"),
        FULLSCREEN  ("Fullscreen"),
        BORDERLESS  ("Borderless");

        /** Human-readable label for use in the settings UI. */
        public final String displayName;

        WindowMode(String displayName) {
            this.displayName = displayName;
        }

        /**
         * Parses a string from the properties file, falling back to
         * {@link GameSettings#DEFAULT_WINDOW_MODE} on any unknown value.
         *
         * @param value the stored string
         * @return the matching mode, or the default if unrecognised
         */
        public static WindowMode fromString(String value) {
            for (WindowMode m : values()) {
                if (m.name().equalsIgnoreCase(value)) return m;
            }
            return DEFAULT_WINDOW_MODE;
        }
    }

    // ── Live fields ───────────────────────────────────────────────────────────

    private String     username;
    private int        renderDistance;
    private int        fov;
    private float      mouseSensitivity;
    private String     theme;
    private boolean    vsync;
    private WindowMode windowMode;

    /** Keybindings — serialized into the same properties file. */
    private final KeyBindings keyBindings = new KeyBindings();

    /** Path this instance loads from and saves to. */
    private final Path settingsPath;

    // ── Construction ──────────────────────────────────────────────────────────

    /**
     * Creates a {@code GameSettings} instance pointed at the given file path.
     * Does not load from disk — call {@link #load()} explicitly after construction.
     *
     * @param settingsPath path to the {@code .properties} file
     */
    public GameSettings(Path settingsPath) {
        this.settingsPath = settingsPath;
        applyDefaults();
    }

    /**
     * Fills all fields with compiled-in default values.
     * Called at construction and used as fallback during load.
     */
    private void applyDefaults() {
        username        = DEFAULT_USERNAME;
        renderDistance  = DEFAULT_RENDER_DISTANCE;
        fov             = DEFAULT_FOV;
        mouseSensitivity = DEFAULT_MOUSE_SENS;
        theme           = DEFAULT_THEME;
        vsync           = DEFAULT_VSYNC;
        windowMode      = DEFAULT_WINDOW_MODE;
        keyBindings.resetToDefaults();
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Loads settings from disk. Missing or corrupt entries fall back to defaults
     * — a partial file is always safe. If the file does not exist, all defaults
     * are kept and no error is thrown.
     */
    public void load() {
        if (!Files.exists(settingsPath)) {
            LOGGER.info("No settings file found at " + settingsPath + " — using defaults.");
            return;
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(settingsPath)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warning("Failed to read settings file: " + e.getMessage() + " — using defaults.");
            return;
        }

        username        = props.getProperty(KEY_USERNAME, DEFAULT_USERNAME).trim();
        renderDistance  = parseInt(props, KEY_RENDER_DISTANCE, DEFAULT_RENDER_DISTANCE, 2, 32);
        fov             = parseInt(props, KEY_FOV, DEFAULT_FOV, 50, 110);
        mouseSensitivity = parseFloat(props, KEY_MOUSE_SENS, DEFAULT_MOUSE_SENS, 0.01f, 2.0f);
        theme           = props.getProperty(KEY_THEME, DEFAULT_THEME).trim();
        vsync           = parseBoolean(props, KEY_VSYNC, DEFAULT_VSYNC);
        windowMode      = WindowMode.fromString(props.getProperty(KEY_WINDOW_MODE, DEFAULT_WINDOW_MODE.name()));

        keyBindings.loadFrom(props);

        LOGGER.info("Settings loaded from " + settingsPath);
    }

    /**
     * Saves all current settings to disk atomically. Writes to a {@code .tmp}
     * file first, then renames over the real file so a crash mid-write cannot
     * corrupt the saved state.
     */
    public void save() {
        try {
            save(settingsPath);
        } catch (IOException e) {
            LOGGER.warning("Failed to save settings: " + e.getMessage());
        }
    }

    /**
     * Saves all current settings to the provided path atomically. Writes to a
     * {@code .tmp} file first, then renames over the real file so a crash
     * mid-write cannot corrupt the saved state.
     *
     * @param targetPath output path for the settings file
     * @throws IOException if writing the file fails
     */
    public void save(Path targetPath) throws IOException {
        Properties props = new Properties();

        props.setProperty(KEY_USERNAME,        username);
        props.setProperty(KEY_RENDER_DISTANCE, String.valueOf(renderDistance));
        props.setProperty(KEY_FOV,             String.valueOf(fov));
        props.setProperty(KEY_MOUSE_SENS,      String.valueOf(mouseSensitivity));
        props.setProperty(KEY_THEME,           theme);
        props.setProperty(KEY_VSYNC,           String.valueOf(vsync));
        props.setProperty(KEY_WINDOW_MODE,     windowMode.name());

        keyBindings.saveTo(props);

        Path tmp = targetPath.resolveSibling(targetPath.getFileName() + ".tmp");
        Path parent = targetPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (OutputStream out = Files.newOutputStream(tmp)) {
            props.store(out, "Voxel Game Settings");
        }
        Files.move(tmp, targetPath, StandardCopyOption.REPLACE_EXISTING,
                                      StandardCopyOption.ATOMIC_MOVE);
        LOGGER.info("Settings saved to " + targetPath);
    }

    // ── Parse helpers ─────────────────────────────────────────────────────────

    private int parseInt(Properties p, String key, int def, int min, int max) {
        try {
            int v = Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private float parseFloat(Properties p, String key, float def, float min, float max) {
        try {
            float v = Float.parseFloat(p.getProperty(key, String.valueOf(def)).trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private boolean parseBoolean(Properties p, String key, boolean def) {
        String val = p.getProperty(key);
        if (val == null) return def;
        return Boolean.parseBoolean(val.trim());
    }

    // ── Getters and setters ───────────────────────────────────────────────────

    /** @return the player's display name used in multiplayer */
    public String getUsername() { return username; }

    /** @param username the new display name; should be validated before calling */
    public void setUsername(String username) { this.username = username; }

    /** @return horizontal chunk render distance (2–32) */
    public int getRenderDistance() { return renderDistance; }

    /** @param renderDistance new render distance, clamped to [2, 32] */
    public void setRenderDistance(int renderDistance) {
        this.renderDistance = Math.max(2, Math.min(32, renderDistance));
    }

    /** @return vertical field of view in degrees (50–110) */
    public int getFov() { return fov; }

    /** @param fov new FOV in degrees, clamped to [50, 110] */
    public void setFov(int fov) {
        this.fov = Math.max(50, Math.min(110, fov));
    }

    /** @return mouse look sensitivity multiplier (0.01–2.0) */
    public float getMouseSensitivity() { return mouseSensitivity; }

    /** @param mouseSensitivity new sensitivity, clamped to [0.01, 2.0] */
    public void setMouseSensitivity(float mouseSensitivity) {
        this.mouseSensitivity = Math.max(0.01f, Math.min(2.0f, mouseSensitivity));
    }

    /** @return theme identifier string — {@code "dark"} or {@code "light"} */
    public String getTheme() { return theme; }

    /** @param theme theme identifier — {@code "dark"} or {@code "light"} */
    public void setTheme(String theme) { this.theme = theme; }

    /** @return true if vertical sync is enabled */
    public boolean isVsync() { return vsync; }

    /** @param vsync true to enable VSync */
    public void setVsync(boolean vsync) { this.vsync = vsync; }

    /** @return the current window display mode */
    public WindowMode getWindowMode() { return windowMode; }

    /** @param windowMode the desired window display mode */
    public void setWindowMode(WindowMode windowMode) { this.windowMode = windowMode; }

    /** @return the keybinding map — modify directly via {@link KeyBindings} methods */
    public KeyBindings getKeyBindings() { return keyBindings; }

    /** @return the path this instance reads from and writes to */
    public Path getSettingsPath() { return settingsPath; }
}