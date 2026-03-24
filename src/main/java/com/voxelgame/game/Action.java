package com.voxelgame.game;

/**
 * Enumeration of all player-bindable actions.
 *
 * <p>Each entry maps to exactly one GLFW key or mouse button code stored in
 * {@link KeyBindings}. {@code GameLoop} reads from {@link KeyBindings} instead
 * of hardcoding GLFW constants — rebinding any action here takes effect
 * everywhere automatically.
 *
 * <p>The {@code displayName} field is the human-readable label shown in the
 * settings screen keybind tab.
 */
public enum Action {

    // --- Movement ---
    MOVE_FORWARD   ("Move Forward"),
    MOVE_BACKWARD  ("Move Backward"),
    MOVE_LEFT      ("Strafe Left"),
    MOVE_RIGHT     ("Strafe Right"),
    JUMP           ("Jump"),

    // --- Interaction ---
    BREAK_BLOCK    ("Break Block"),
    PLACE_BLOCK    ("Place Block"),

    // --- Misc ---
    TOGGLE_FREECAM ("Toggle Freecam"),

    // --- Communication (stub — chat not yet implemented) ---
    OPEN_CHAT      ("Open Chat");

    /** Label shown in the keybinds settings tab. */
    public final String displayName;

    Action(String displayName) {
        this.displayName = displayName;
    }
}