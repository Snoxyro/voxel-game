package com.voxelgame.game;

import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Properties;

/**
 * Stores the GLFW key or mouse button code bound to each {@link Action}.
 *
 * <h3>Key vs mouse button encoding</h3>
 * GLFW key codes and mouse button codes are separate integer namespaces.
 * To store both in a single {@code int} per action, mouse button codes are
 * offset by {@link #MOUSE_BUTTON_OFFSET} (1000) before storage. Use
 * {@link #isMouse(int)} to check whether a stored code is a mouse button,
 * and {@link #mouseCode(int)} / {@link #keyCode(int)} to encode/decode.
 *
 * <h3>Unbound actions</h3>
 * An unbound action stores {@link #UNBOUND} ({@code -1}). {@code GameLoop}
 * checks for {@code UNBOUND} before querying GLFW — an unbound action never
 * fires. Use {@link #unbind(Action)} to clear a binding.
 */
public class KeyBindings {

    /** Sentinel value meaning no key is bound to this action. */
    public static final int UNBOUND = -1;

    /**
     * Added to mouse button codes before storage so they cannot collide with
     * keyboard key codes. {@code GLFW_KEY_LAST} is 348 — any offset above
     * that is safe. We use 1000 for readability.
     */
    public static final int MOUSE_BUTTON_OFFSET = 1000;

    /** Default bindings — matches the hardcoded constants currently in GameLoop. */
    private static final EnumMap<Action, Integer> DEFAULTS = new EnumMap<>(Action.class);

    static {
        DEFAULTS.put(Action.MOVE_FORWARD,    GLFW.GLFW_KEY_W);
        DEFAULTS.put(Action.MOVE_BACKWARD,   GLFW.GLFW_KEY_S);
        DEFAULTS.put(Action.MOVE_LEFT,       GLFW.GLFW_KEY_A);
        DEFAULTS.put(Action.MOVE_RIGHT,      GLFW.GLFW_KEY_D);
        DEFAULTS.put(Action.JUMP,            GLFW.GLFW_KEY_SPACE);
        DEFAULTS.put(Action.BREAK_BLOCK,     mouseCode(GLFW.GLFW_MOUSE_BUTTON_LEFT));
        DEFAULTS.put(Action.PLACE_BLOCK,     mouseCode(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        DEFAULTS.put(Action.TOGGLE_FREECAM,  GLFW.GLFW_KEY_F);
        DEFAULTS.put(Action.OPEN_CHAT,       GLFW.GLFW_KEY_ENTER);
    }

    /** Live binding map — populated from defaults or loaded from properties. */
    private final EnumMap<Action, Integer> bindings = new EnumMap<>(Action.class);

    /**
     * Creates a {@code KeyBindings} instance populated with all default bindings.
     */
    public KeyBindings() {
        resetToDefaults();
    }

    // ── Encoding helpers ──────────────────────────────────────────────────────

    /**
     * Encodes a GLFW mouse button constant for storage in this map.
     * Example: {@code mouseCode(GLFW_MOUSE_BUTTON_LEFT)} → 1000.
     *
     * @param glfwMouseButton a {@code GLFW_MOUSE_BUTTON_*} constant
     * @return the encoded value to store or compare
     */
    public static int mouseCode(int glfwMouseButton) {
        return glfwMouseButton + MOUSE_BUTTON_OFFSET;
    }

    /**
     * Returns true if the stored code represents a mouse button rather than a key.
     *
     * @param code a value from {@link #get(Action)}
     * @return true if the code was encoded with {@link #mouseCode}
     */
    public static boolean isMouse(int code) {
        return code >= MOUSE_BUTTON_OFFSET;
    }

    /**
     * Decodes a stored mouse code back to the raw {@code GLFW_MOUSE_BUTTON_*} value.
     * Only call this after confirming {@link #isMouse(int)} is true.
     *
     * @param code an encoded mouse code
     * @return the original {@code GLFW_MOUSE_BUTTON_*} constant
     */
    public static int rawMouseButton(int code) {
        return code - MOUSE_BUTTON_OFFSET;
    }

    // ── Binding access ────────────────────────────────────────────────────────

    /**
     * Returns the stored code for the given action, or {@link #UNBOUND} if
     * the action has no binding.
     *
     * @param action the action to look up
     * @return stored code (key or encoded mouse button), or {@link #UNBOUND}
     */
    public int get(Action action) {
        return bindings.getOrDefault(action, UNBOUND);
    }

    /**
     * Binds an action to a raw keyboard key code.
     *
     * @param action  the action to bind
     * @param glfwKey a {@code GLFW_KEY_*} constant
     */
    public void bindKey(Action action, int glfwKey) {
        bindings.put(action, glfwKey);
    }

    /**
     * Binds an action to a mouse button.
     *
     * @param action          the action to bind
     * @param glfwMouseButton a {@code GLFW_MOUSE_BUTTON_*} constant
     */
    public void bindMouse(Action action, int glfwMouseButton) {
        bindings.put(action, mouseCode(glfwMouseButton));
    }

    /**
     * Removes the binding for an action. The action will never fire until
     * rebound.
     *
     * @param action the action to unbind
     */
    public void unbind(Action action) {
        bindings.put(action, UNBOUND);
    }

    /**
     * Resets all bindings to the compiled-in defaults.
     */
    public void resetToDefaults() {
        bindings.putAll(DEFAULTS);
    }

    /**
     * Returns the default code for the given action.
     *
     * @param action the action to query
     * @return the default code, or {@link #UNBOUND} if no default exists
     */
    public int getDefault(Action action) {
        return DEFAULTS.getOrDefault(action, UNBOUND);
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    /**
     * Writes all bindings into a {@link Properties} object under keys of the
     * form {@code keybind.ACTION_NAME} (e.g. {@code keybind.MOVE_FORWARD=87}).
     *
     * @param props the properties object to write into
     */
    public void saveTo(Properties props) {
        for (Action action : Action.values()) {
            props.setProperty("keybind." + action.name(), String.valueOf(get(action)));
        }
    }

    /**
     * Loads bindings from a {@link Properties} object. Any action whose key is
     * absent or unparseable is left at its current value (call
     * {@link #resetToDefaults()} first if you want a clean load).
     *
     * @param props the properties object to read from
     */
    public void loadFrom(Properties props) {
        for (Action action : Action.values()) {
            String val = props.getProperty("keybind." + action.name());
            if (val != null) {
                try {
                    bindings.put(action, Integer.parseInt(val));
                } catch (NumberFormatException e) {
                    // Corrupt entry — leave current value (default after resetToDefaults)
                }
            }
        }
    }

    /**
     * Returns a human-readable name for a stored code — used in the settings UI.
     * Examples: key W → "W", mouse left → "Mouse 1", unbound → "---".
     *
     * @param code a value from {@link #get(Action)}
     * @return display string
     */
    public static String displayName(int code) {
        if (code == UNBOUND) return "---";
        if (isMouse(code)) {
            return switch (rawMouseButton(code)) {
                case GLFW.GLFW_MOUSE_BUTTON_LEFT   -> "Mouse 1";
                case GLFW.GLFW_MOUSE_BUTTON_RIGHT  -> "Mouse 2";
                case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> "Mouse 3";
                default -> "Mouse " + (rawMouseButton(code) + 1);
            };
        }
        // GLFW doesn't provide a reliable human-readable name for all keys,
        // but glfwGetKeyName works for printable keys. Falls back to the
        // GLFW constant name for non-printable keys (arrows, F-keys, etc.)
        String name = GLFW.glfwGetKeyName(code, 0);
        if (name != null && !name.isBlank()) return name.toUpperCase();
        return switch (code) {
            case GLFW.GLFW_KEY_SPACE        -> "Space";
            case GLFW.GLFW_KEY_ENTER        -> "Enter";
            case GLFW.GLFW_KEY_TAB          -> "Tab";
            case GLFW.GLFW_KEY_BACKSPACE    -> "Backspace";
            case GLFW.GLFW_KEY_DELETE       -> "Delete";
            case GLFW.GLFW_KEY_ESCAPE       -> "Escape";
            case GLFW.GLFW_KEY_LEFT         -> "Left";
            case GLFW.GLFW_KEY_RIGHT        -> "Right";
            case GLFW.GLFW_KEY_UP           -> "Up";
            case GLFW.GLFW_KEY_DOWN         -> "Down";
            case GLFW.GLFW_KEY_LEFT_SHIFT,
                 GLFW.GLFW_KEY_RIGHT_SHIFT  -> "Shift";
            case GLFW.GLFW_KEY_LEFT_CONTROL,
                 GLFW.GLFW_KEY_RIGHT_CONTROL-> "Ctrl";
            case GLFW.GLFW_KEY_LEFT_ALT,
                 GLFW.GLFW_KEY_RIGHT_ALT    -> "Alt";
            default -> "Key " + code;
        };
    }
}