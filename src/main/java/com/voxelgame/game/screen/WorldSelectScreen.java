package com.voxelgame.game.screen;

import com.voxelgame.engine.ui.UiTheme;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * World selection screen. Lists saved worlds from the {@code worlds/} directory.
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li>{@code LIST} — browsing existing worlds</li>
 *   <li>{@code CREATE_NEW} — typing a name for a new world</li>
 *   <li>{@code CONFIRM_DELETE} — confirmation prompt before deleting</li>
 *   <li>{@code LAUNCHING} — world launch in progress, input blocked</li>
 * </ul>
 *
 * <p>A valid world directory is any subdirectory of {@code worlds/} that
 * contains a {@code world.dat} file. New worlds are created automatically
 * by the server when first launched — the directory does not need to exist.</p>
 */
public class WorldSelectScreen implements Screen {

    // -------------------------------------------------------------------------
    // Internal state machine
    // -------------------------------------------------------------------------

    private enum Mode { LIST, CREATE_NEW, CONFIRM_DELETE, LAUNCHING }

    // -------------------------------------------------------------------------
    // Layout constants — pixel-space, relative to panel origin
    // -------------------------------------------------------------------------

    private static final int PANEL_W   = 500;
    private static final int PANEL_H   = 450;

    /** Height of each world entry row in the list. */
    private static final int ITEM_H    = 38;
    /** Vertical gap between list items. */
    private static final int ITEM_GAP  = 4;
    /** Horizontal text padding inside a list item. */
    @SuppressWarnings("unused")
    private static final int ITEM_PAD  = 8;
    /** Maximum number of worlds displayed without scrolling. */
    private static final int MAX_ITEMS = 6;

    /** Height of action buttons at the bottom of the panel. */
    private static final int BTN_H     = 36;
    /** Horizontal gap between adjacent buttons. */
    private static final int BTN_GAP   = 8;
    /** Individual button width for the four-button bottom row. */
    private static final int BTN_W     = (PANEL_W - 32 - BTN_GAP) / 2; // ~230px each (Previously set to 100)

    /** Caret switches between visible and hidden every this many seconds. */
    private static final float CARET_BLINK_INTERVAL = 0.5f;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    @SuppressWarnings("unused")
    private final ScreenManager            screenManager;
    private final BiConsumer<String, Long> onWorldSelected; // worldName, seed (null = random)
    private final Runnable                 onBack;

    private Mode         mode          = Mode.LIST;
    private List<String> worldNames    = new ArrayList<>();
    private int          selectedIndex = -1;         // -1 = nothing selected
    private String       newWorldName  = "";         // text input buffer
    private String       newWorldSeed = "";
    private String       statusMessage = "";

    /** Current mouse cursor position — polled each render frame. */
    private int mouseX, mouseY;

    /**
     * Cached single-character pixel width from the last render frame.
        * Set in renderCreate() via theme.measureText("X") — guarantees the same
     * width used for rendering is used for mouse→caret calculations.
        * Defaults to 16px until the first render frame fires.
     */
    private int charPixelWidth = 16;

    /** Accumulated time in seconds for caret blink timing. */
    private float caretBlinkTimer  = 0f;
    /** Whether the caret is currently in its visible phase. */
    private boolean caretVisible   = true;

    private int nameCaretPos = 0; // caret position inside newWorldName
    private int seedCaretPos = 0; // caret position inside newWorldSeed
    private boolean      seedFieldActive = false;    // which field has keyboard focus
    
    /**
     * Creates the world selection screen.
     *
     * @param screenManager   the active screen manager
     * @param onWorldSelected callback invoked with the chosen world name when
     *                        the player confirms a selection or creates a new world;
     *                        the server is launched by the receiver
     * @param onBack          callback invoked when the player clicks Back
     */
    public WorldSelectScreen(ScreenManager screenManager, BiConsumer<String, Long> onWorldSelected, Runnable onBack) {
        this.screenManager   = screenManager;
        this.onWorldSelected = onWorldSelected;
        this.onBack          = onBack;
    }

    // -------------------------------------------------------------------------
    // Screen lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onShow() {
        mode            = Mode.LIST;
        newWorldName    = "";
        newWorldSeed    = "";
        statusMessage    = "";
        nameCaretPos    = 0;
        seedCaretPos    = 0;
        seedFieldActive = false;
        selectedIndex   = -1;
        caretBlinkTimer = 0f;
        caretVisible    = true;
        refreshWorldList();
    }

    @Override
    public void onHide() { /* nothing to tear down */ }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    @Override
    public void render(UiTheme theme, float deltaTime, int screenWidth, int screenHeight) {
        double[] cx = {0}, cy = {0};
        GLFW.glfwGetCursorPos(GLFW.glfwGetCurrentContext(), cx, cy);
        mouseX = (int) cx[0];
        mouseY = (int) cy[0];

        int panelX = (screenWidth - PANEL_W) / 2;
        int panelY = (screenHeight - PANEL_H) / 2;

        // Background
        theme.drawPanel(panelX, panelY, PANEL_W, PANEL_H);

        // Title — centered in panel
        String title = "SELECT WORLD";
        theme.drawTitle(panelX + PANEL_W / 2.0f, panelY + 14, title);

        switch (mode) {
            case LIST           -> renderList(theme, panelX, panelY);
            case CREATE_NEW     -> renderCreate(theme, deltaTime, panelX, panelY);
            case CONFIRM_DELETE -> renderDelete(theme, panelX, panelY);
            case LAUNCHING      -> renderLaunching(theme, panelX, panelY);
        }
    }

    private void renderList(UiTheme theme, int panelX, int panelY) {
        int listX  = panelX + 16;
        int listW  = PANEL_W - 32;
        int listY0 = panelY + 52;

        if (worldNames.isEmpty()) {
            theme.drawDimLabel(listX, listY0 + 10,
                "No worlds found. Click \"New World\" to create one.");
        } else {
            for (int i = 0; i < Math.min(worldNames.size(), MAX_ITEMS); i++) {
                int     itemY    = listY0 + i * (ITEM_H + ITEM_GAP);
                boolean selected = i == selectedIndex;
                boolean hovered  = !selected && hits(mouseX, mouseY, listX, itemY, listW, ITEM_H);
                theme.drawListItem(listX, itemY, listW, ITEM_H, worldNames.get(i), selected, hovered);
            }
        }

        // 2×2 button grid — coordinates MUST match handleListClick exactly
        int btnW    = (PANEL_W - 32 - BTN_GAP) / 2;
        int btnRowX = panelX + 16;
        int row1Y   = panelY + PANEL_H - (BTN_H * 2) - BTN_GAP - 16;
        int row2Y   = row1Y + BTN_H + BTN_GAP;

        theme.drawButton(btnRowX, row1Y, btnW, BTN_H, "Play",
            hits(mouseX, mouseY, btnRowX, row1Y, btnW, BTN_H), selectedIndex >= 0);
        theme.drawButton(btnRowX + btnW + BTN_GAP, row1Y, btnW, BTN_H, "New World",
            hits(mouseX, mouseY, btnRowX + btnW + BTN_GAP, row1Y, btnW, BTN_H), true);
        if (selectedIndex >= 0) {
            theme.drawDangerButton(btnRowX, row2Y, btnW, BTN_H, "Delete",
                hits(mouseX, mouseY, btnRowX, row2Y, btnW, BTN_H));
        } else {
            theme.drawButton(btnRowX, row2Y, btnW, BTN_H, "Delete", false, false);
        }
        theme.drawButton(btnRowX + btnW + BTN_GAP, row2Y, btnW, BTN_H, "Back",
            hits(mouseX, mouseY, btnRowX + btnW + BTN_GAP, row2Y, btnW, BTN_H));
    }

    private void renderCreate(UiTheme theme, float deltaTime, int panelX, int panelY) {
        nameCaretPos = Math.min(nameCaretPos, newWorldName.length());
        seedCaretPos = Math.min(seedCaretPos, newWorldSeed.length());
        charPixelWidth = theme.measureText("X");

        // Advance blink timer — getDeltaTime() not available here, so we approximate
        // using a fixed frame budget. At ~60 FPS each frame is ~16ms = 0.016s.
        // Good enough for a blink effect; no need for exact timing.
        caretBlinkTimer += deltaTime;
        if (caretBlinkTimer >= CARET_BLINK_INTERVAL) {
            caretBlinkTimer -= CARET_BLINK_INTERVAL;
            caretVisible = !caretVisible;
        }

        int fieldX = panelX + 16;
        int fieldW = PANEL_W - 32;
        int fieldH = 32;

        // --- World Name ---
        int nameLabelY = panelY + 60;
        int nameFieldY = nameLabelY + 20;
        theme.drawLabel(fieldX, nameLabelY, "World Name:");
        theme.drawInputField(fieldX, nameFieldY, fieldW, fieldH,
            newWorldName, nameCaretPos, !seedFieldActive, caretVisible);

        // --- Seed ---
        int seedLabelY = nameFieldY + fieldH + 16;
        int seedFieldY = seedLabelY + 20;
        theme.drawDimLabel(fieldX, seedLabelY,
            "Seed (optional — leave blank for random):");
        theme.drawInputField(fieldX, seedFieldY, fieldW, fieldH,
            newWorldSeed, seedCaretPos, seedFieldActive, caretVisible);

        theme.drawDimLabel(fieldX, seedFieldY + fieldH + 6,
            "Numbers only. Negative numbers allowed.");

        // --- Buttons ---
        int twoW  = 2 * BTN_W + BTN_GAP;
        int btn1X = panelX + (PANEL_W - twoW) / 2;
        int btn2X = btn1X + BTN_W + BTN_GAP;
        int btnY  = panelY + PANEL_H - BTN_H - 16;
        theme.drawButton(btn1X, btnY, BTN_W, BTN_H, "Create",
            hits(mouseX, mouseY, btn1X, btnY, BTN_W, BTN_H), !newWorldName.isEmpty());
        theme.drawButton(btn2X, btnY, BTN_W, BTN_H, "Cancel",
            hits(mouseX, mouseY, btn2X, btnY, BTN_W, BTN_H));
    }

    private void renderDelete(UiTheme theme, int panelX, int panelY) {
        String name = selectedIndex >= 0 ? worldNames.get(selectedIndex) : "?";

        int midY = panelY + PANEL_H / 2 - 40;
        theme.drawWarnLabel(panelX + 16, midY, "Delete this world?");
        theme.drawLabel(panelX + 16, midY + 24, name);
        theme.drawWarnLabel(panelX + 16, midY + 50, "This cannot be undone.");

        int twoW  = 2 * BTN_W + BTN_GAP;
        int btn1X = panelX + (PANEL_W - twoW) / 2;
        int btn2X = btn1X + BTN_W + BTN_GAP;
        int btnY  = panelY + PANEL_H - BTN_H - 16;

        theme.drawDangerButton(btn1X, btnY, BTN_W, BTN_H, "Delete",
            hits(mouseX, mouseY, btn1X, btnY, BTN_W, BTN_H));
        theme.drawButton(btn2X, btnY, BTN_W, BTN_H, "Cancel",
            hits(mouseX, mouseY, btn2X, btnY, BTN_W, BTN_H));
    }

    private void renderLaunching(UiTheme theme, int panelX, int panelY) {
        if (!statusMessage.isEmpty()) {
            // Launch failed — show error and a Back button
            theme.drawWarnLabel(panelX + 16, panelY + PANEL_H / 2 - 20, statusMessage);
            int btnX = panelX + (PANEL_W - 200) / 2;
            int btnY = panelY + PANEL_H / 2 + 10;
            theme.drawButton(btnX, btnY, 200, 36, "Back to Menu",
                hits(mouseX, mouseY, btnX, btnY, 200, 36), true);
            // Also handle the click in onMouseClick for the LAUNCHING mode
        } else {
            theme.drawDimLabel(panelX + 16, panelY + PANEL_H / 2, "Launching...");
        }
    }

    // -------------------------------------------------------------------------
    // Input
    // -------------------------------------------------------------------------

    @Override
    public void onMouseClick(int x, int y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
        int sw = getScreenWidth(), sh = getScreenHeight();
        int panelX = (sw - PANEL_W) / 2;
        int panelY = (sh - PANEL_H) / 2;

        switch (mode) {
            case LIST           -> handleListClick(x, y, panelX, panelY);
            case CREATE_NEW     -> handleCreateClick(x, y, panelX, panelY);
            case CONFIRM_DELETE -> handleDeleteClick(x, y, panelX, panelY);
            case LAUNCHING -> {
                if (!statusMessage.isEmpty()) {
                    int btnX = panelX + (PANEL_W - 200) / 2;
                    int btnY = panelY + PANEL_H / 2 + 10;
                    if (hits(x, y, btnX, btnY, 200, 36)) {
                        onBack.run();
                    }
                }
            }
        }
    }

    private void handleListClick(int x, int y, int panelX, int panelY) {
        int listX  = panelX + 16;
        int listW  = PANEL_W - 32;
        int listY0 = panelY + 52;

        // World list item clicks
        for (int i = 0; i < Math.min(worldNames.size(), MAX_ITEMS); i++) {
            int itemY = listY0 + i * (ITEM_H + ITEM_GAP);
            if (hits(x, y, listX, itemY, listW, ITEM_H)) {
                selectedIndex = i;
                return;
            }
        }

        // 2×2 button grid — must match renderList() exactly
        int btnW    = (PANEL_W - 32 - BTN_GAP) / 2;
        int btnRowX = panelX + 16;
        int row1Y   = panelY + PANEL_H - (BTN_H * 2) - BTN_GAP - 16;
        int row2Y   = row1Y + BTN_H + BTN_GAP;

        if (hits(x, y, btnRowX, row1Y, btnW, BTN_H)) {
            // Play
            if (selectedIndex >= 0) launchSelected();

        } else if (hits(x, y, btnRowX + btnW + BTN_GAP, row1Y, btnW, BTN_H)) {
            // New World — reset all input state before switching mode
            mode            = Mode.CREATE_NEW;
            newWorldName    = "";
            newWorldSeed    = "";
            nameCaretPos    = 0;
            seedCaretPos    = 0;
            seedFieldActive = false;

        } else if (hits(x, y, btnRowX, row2Y, btnW, BTN_H)) {
            // Delete
            if (selectedIndex >= 0) mode = Mode.CONFIRM_DELETE;

        } else if (hits(x, y, btnRowX + btnW + BTN_GAP, row2Y, btnW, BTN_H)) {
            // Back
            onBack.run();
        }
    }

    private void handleCreateClick(int x, int y, int panelX, int panelY) {
        int fieldX  = panelX + 16;
        int fieldW  = PANEL_W - 32;
        int fieldH  = 32;
        int nameFieldY = panelY + 60 + 20;           // nameLabelY=panelY+60, field is +20 below
        int seedFieldY = nameFieldY + fieldH + 16 + 20; // seedLabelY is +fieldH+16 below field

        // Click on name field — activate it and set caret from click position
        if (hits(x, y, fieldX, nameFieldY, fieldW, fieldH)) {
            seedFieldActive = false;
            int relX = x - fieldX - 8; // 8 = text left-padding in renderCreate
            nameCaretPos = Math.max(0, Math.min(newWorldName.length(), (relX + charPixelWidth / 2) / charPixelWidth));
            return;
        }

        // Click on seed field — activate it and set caret from click position
        if (hits(x, y, fieldX, seedFieldY, fieldW, fieldH)) {
            seedFieldActive = true;
            int relX = x - fieldX - 8;
            seedCaretPos = Math.max(0, Math.min(newWorldSeed.length(), (relX + charPixelWidth / 2) / charPixelWidth));
            return;
        }

        // Buttons
        int twoW  = 2 * BTN_W + BTN_GAP;
        int btn1X = panelX + (PANEL_W - twoW) / 2;
        int btn2X = btn1X + BTN_W + BTN_GAP;
        int btnY  = panelY + PANEL_H - BTN_H - 16;

        if (hits(x, y, btn1X, btnY, BTN_W, BTN_H) && !newWorldName.isEmpty()) {
            triggerLaunch(newWorldName);
        } else if (hits(x, y, btn2X, btnY, BTN_W, BTN_H)) {
            mode            = Mode.LIST;
            newWorldName    = "";
            newWorldSeed    = "";
            nameCaretPos    = 0;
            seedCaretPos    = 0;
            seedFieldActive = false;
        }
    }

    private void handleDeleteClick(int x, int y, int panelX, int panelY) {
        int twoW  = 2 * BTN_W + BTN_GAP;
        int btn1X = panelX + (PANEL_W - twoW) / 2;
        int btn2X = btn1X + BTN_W + BTN_GAP;
        int btnY  = panelY + PANEL_H - BTN_H - 16;

        if (hits(x, y, btn1X, btnY, BTN_W, BTN_H)) {
            deleteSelected();
        } else if (hits(x, y, btn2X, btnY, BTN_W, BTN_H)) {
            mode = Mode.LIST;
        }
    }

    @Override
    public void onKeyPress(int key, int mods) {
        switch (key) {

            case GLFW.GLFW_KEY_ESCAPE -> {
                if (mode == Mode.CREATE_NEW || mode == Mode.CONFIRM_DELETE)
                    mode = Mode.LIST;
                else if (mode == Mode.LIST)
                    onBack.run();
            }

            case GLFW.GLFW_KEY_TAB -> {
                if (mode == Mode.CREATE_NEW)
                    seedFieldActive = !seedFieldActive;
            }

            case GLFW.GLFW_KEY_ENTER -> {
                if (mode == Mode.CREATE_NEW && !newWorldName.isEmpty())
                    triggerLaunch(newWorldName);
                else if (mode == Mode.LIST && selectedIndex >= 0)
                    launchSelected();
            }

            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (mode != Mode.CREATE_NEW) break;
                if (!seedFieldActive && nameCaretPos > 0) {
                    newWorldName = newWorldName.substring(0, nameCaretPos - 1)
                                 + newWorldName.substring(nameCaretPos);
                    nameCaretPos--;
                } else if (seedFieldActive && seedCaretPos > 0) {
                    newWorldSeed = newWorldSeed.substring(0, seedCaretPos - 1)
                                 + newWorldSeed.substring(seedCaretPos);
                    seedCaretPos--;
                }
            }

            case GLFW.GLFW_KEY_DELETE -> {
                // Forward-delete: removes the character after the caret
                if (mode != Mode.CREATE_NEW) break;
                if (!seedFieldActive && nameCaretPos < newWorldName.length()) {
                    newWorldName = newWorldName.substring(0, nameCaretPos)
                                 + newWorldName.substring(nameCaretPos + 1);
                } else if (seedFieldActive && seedCaretPos < newWorldSeed.length()) {
                    newWorldSeed = newWorldSeed.substring(0, seedCaretPos)
                                 + newWorldSeed.substring(seedCaretPos + 1);
                }
            }

            case GLFW.GLFW_KEY_LEFT -> {
                if (mode != Mode.CREATE_NEW) break;
                if (!seedFieldActive) nameCaretPos = Math.max(0, nameCaretPos - 1);
                else                  seedCaretPos = Math.max(0, seedCaretPos - 1);
            }

            case GLFW.GLFW_KEY_RIGHT -> {
                if (mode != Mode.CREATE_NEW) break;
                if (!seedFieldActive) nameCaretPos = Math.min(newWorldName.length(), nameCaretPos + 1);
                else                  seedCaretPos = Math.min(newWorldSeed.length(), seedCaretPos + 1);
            }

            case GLFW.GLFW_KEY_HOME -> {
                if (mode != Mode.CREATE_NEW) break;
                if (!seedFieldActive) nameCaretPos = 0;
                else                  seedCaretPos = 0;
            }

            case GLFW.GLFW_KEY_END -> {
                if (mode != Mode.CREATE_NEW) break;
                if (!seedFieldActive) nameCaretPos = newWorldName.length();
                else                  seedCaretPos = newWorldSeed.length();
            }
        }
    }

    @Override
    public void onCharTyped(char c) {
        if (mode != Mode.CREATE_NEW) return;

        if (!seedFieldActive) {
            // Name field — letters, digits, hyphens, underscores; max 32 chars
            if ((Character.isLetterOrDigit(c) || c == '-' || c == '_')
                    && newWorldName.length() < 32) {
                // Insert at caret position — split the string at the caret, insert c, rejoin
                newWorldName = newWorldName.substring(0, nameCaretPos)
                             + c
                             + newWorldName.substring(nameCaretPos);
                nameCaretPos++;
            }
        } else {
            // Seed field — digits only, optional leading minus; max 20 chars
            boolean isMinus = (c == '-' && seedCaretPos == 0 && newWorldSeed.isEmpty());
            if ((Character.isDigit(c) || isMinus) && newWorldSeed.length() < 20) {
                newWorldSeed = newWorldSeed.substring(0, seedCaretPos)
                             + c
                             + newWorldSeed.substring(seedCaretPos);
                seedCaretPos++;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    /** Triggers launch of the currently selected world. */
    private void launchSelected() {
        if (selectedIndex < 0 || selectedIndex >= worldNames.size()) return;
        triggerLaunch(worldNames.get(selectedIndex));
    }

    /**
     * Transitions to LAUNCHING mode and fires the {@link #onWorldSelected} callback.
     * The world name is used directly as the subdirectory of {@code worlds/}.
     * If it does not exist, the server creates it automatically.
     *
     * @param worldName the world folder name (letters, digits, dashes, underscores)
     */
    private void triggerLaunch(String worldName) {
        mode = Mode.LAUNCHING;
        Long seed = null;
        if (!newWorldSeed.isEmpty()) {
            try {
                seed = Long.parseLong(newWorldSeed);
            } catch (NumberFormatException e) {
                // Malformed input — fall back to random
                System.err.println("[WorldSelectScreen] Invalid seed input '" + newWorldSeed + "' — using random");
            }
        }
        onWorldSelected.accept(worldName, seed);
    }

    /** Deletes the selected world directory and refreshes the list. */
    private void deleteSelected() {
        if (selectedIndex < 0 || selectedIndex >= worldNames.size()) return;
        Path worldPath = Path.of("worlds", worldNames.get(selectedIndex));
        try {
            deleteDirectoryRecursive(worldPath);
        } catch (IOException e) {
            System.err.println("[WorldSelectScreen] Delete failed: " + e.getMessage());
        }
        selectedIndex = -1;
        mode = Mode.LIST;
        refreshWorldList();
    }

    // -------------------------------------------------------------------------
    // Filesystem helpers
    // -------------------------------------------------------------------------

    /**
     * Scans {@code worlds/} for subdirectories that contain {@code world.dat}.
     * A directory without {@code world.dat} is not a valid world and is ignored.
     * Results are sorted alphabetically for a stable display order.
     */
    private void refreshWorldList() {
        worldNames.clear();
        Path worldsDir = Path.of("worlds");
        if (!Files.exists(worldsDir)) return;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(worldsDir)) {
            for (Path entry : stream) {
                // Only list directories that have been fully initialised by the server
                if (Files.isDirectory(entry) && Files.exists(entry.resolve("world.dat"))) {
                    worldNames.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {
            System.err.println("[WorldSelectScreen] Failed to scan worlds/: " + e.getMessage());
        }
        Collections.sort(worldNames);
    }

    /**
     * Recursively deletes {@code dir} and all its contents.
     * Uses a simple DFS rather than {@code Files.walk} to avoid unchecked stream exceptions.
     *
     * @param dir the root directory to delete
     * @throws IOException if any file or directory cannot be deleted
     */
    private void deleteDirectoryRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    deleteDirectoryRecursive(entry);
                }
            }
        }
        Files.delete(dir);
    }

    private boolean hits(int px, int py, int x, int y, int w, int h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    private int getScreenWidth() {
        int[] w = {0}, h = {0};
        GLFW.glfwGetFramebufferSize(GLFW.glfwGetCurrentContext(), w, h);
        return w[0];
    }

    private int getScreenHeight() {
        int[] w = {0}, h = {0};
        GLFW.glfwGetFramebufferSize(GLFW.glfwGetCurrentContext(), w, h);
        return h[0];
    }

    public void setStatusMessage(String message) {
        this.statusMessage = message;
    }
}