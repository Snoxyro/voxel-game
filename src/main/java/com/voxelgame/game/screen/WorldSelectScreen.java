package com.voxelgame.game.screen;

import com.voxelgame.engine.ui.GlyphAtlas;
import com.voxelgame.engine.ui.UiRenderer;
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

    // Panel
    private static final float PANEL_R  = 0.10f, PANEL_G  = 0.10f, PANEL_B  = 0.10f, PANEL_A  = 0.88f;

    // List item — unselected, selected (green tint), hovered
    @SuppressWarnings("unused")
    private static final float ITEM_R   = 0.18f, ITEM_G   = 0.18f, ITEM_B   = 0.18f, ITEM_A   = 1.0f;
    @SuppressWarnings("unused")
    private static final float ISEL_R   = 0.22f, ISEL_G   = 0.42f, ISEL_B   = 0.22f, ISEL_A   = 1.0f;
    @SuppressWarnings("unused")
    private static final float IHOV_R   = 0.23f, IHOV_G   = 0.23f, IHOV_B   = 0.23f, IHOV_A   = 1.0f;

    // Buttons
    private static final float BTN_R    = 0.25f, BTN_G    = 0.25f, BTN_B    = 0.25f, BTN_A    = 1.0f;
    @SuppressWarnings("unused")
    private static final float BHOV_R   = 0.40f, BHOV_G   = 0.40f, BHOV_B   = 0.40f, BHOV_A   = 1.0f;
    @SuppressWarnings("unused")
    private static final float BDIS_R   = 0.18f, BDIS_G   = 0.18f, BDIS_B   = 0.18f, BDIS_A   = 1.0f;

    // Text
    private static final float TXT_R    = 1.00f, TXT_G    = 1.00f, TXT_B    = 1.00f, TXT_A    = 1.0f;
    private static final float TITLE_R  = 1.00f, TITLE_G  = 0.85f, TITLE_B  = 0.30f, TITLE_A  = 1.0f;
    private static final float DIM_R    = 0.55f, DIM_G    = 0.55f, DIM_B    = 0.55f;
    private static final float WARN_R   = 1.00f, WARN_G   = 0.30f, WARN_B   = 0.30f;

    // Input field border / background
    private static final float IBORD_R  = 0.50f, IBORD_G  = 0.50f, IBORD_B  = 0.50f, IBORD_A  = 1.0f;
    private static final float IBG_R    = 0.12f, IBG_G    = 0.12f, IBG_B    = 0.12f, IBG_A    = 1.0f;

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

    /** Current mouse cursor position — polled each render frame. */
    private int mouseX, mouseY;

    /**
     * Cached single-character pixel width from the last render frame.
     * Set in renderCreate() via r.measureText("X") — guarantees the same
     * width used for rendering is used for mouse→caret calculations.
     * Defaults to GlyphAtlas.CELL_W until the first render frame fires.
     */
    private int charPixelWidth = GlyphAtlas.CELL_W;

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
    public void render(UiRenderer r, int sw, int sh) {
        double[] cx = {0}, cy = {0};
        GLFW.glfwGetCursorPos(GLFW.glfwGetCurrentContext(), cx, cy);
        mouseX = (int) cx[0];
        mouseY = (int) cy[0];

        int panelX = (sw - PANEL_W) / 2;
        int panelY = (sh - PANEL_H) / 2;

        // Background
        r.drawRect(panelX, panelY, PANEL_W, PANEL_H, PANEL_R, PANEL_G, PANEL_B, PANEL_A);

        // Title — centered in panel
        String title = "SELECT WORLD";
        int titleX = panelX + (PANEL_W - r.measureText(title)) / 2;
        r.drawText(titleX, panelY + 14, title, TITLE_R, TITLE_G, TITLE_B, TITLE_A);

        switch (mode) {
            case LIST           -> renderList(r, panelX, panelY);
            case CREATE_NEW     -> renderCreate(r, panelX, panelY);
            case CONFIRM_DELETE -> renderDelete(r, panelX, panelY);
            case LAUNCHING      -> renderLaunching(r, panelX, panelY);
        }
    }

    private void renderList(UiRenderer r, int panelX, int panelY) {
        int listX  = panelX + 16;
        int listW  = PANEL_W - 32;
        int listY0 = panelY + 52;

        if (worldNames.isEmpty()) {
            r.drawText(listX, listY0 + 10,
                "No worlds found. Click \"New World\" to create one.",
                DIM_R, DIM_G, DIM_B, 1.0f);
        } else {
            for (int i = 0; i < Math.min(worldNames.size(), MAX_ITEMS); i++) {
                int     itemY    = listY0 + i * (ITEM_H + ITEM_GAP);
                boolean selected = i == selectedIndex;
                boolean hovered  = !selected && hits(mouseX, mouseY, listX, itemY, listW, ITEM_H);

                float br, bg, bb;
                if      (selected) { br = ISEL_R; bg = ISEL_G; bb = ISEL_B; }
                else if (hovered)  { br = IHOV_R; bg = IHOV_G; bb = IHOV_B; }
                else               { br = ITEM_R; bg = ITEM_G; bb = ITEM_B; }
                r.drawRect(listX, itemY, listW, ITEM_H, br, bg, bb, 1.0f);
                r.drawText(listX + ITEM_PAD, itemY + ITEM_PAD,
                    worldNames.get(i), TXT_R, TXT_G, TXT_B, TXT_A);
            }
        }

        // 2×2 button grid — coordinates MUST match handleListClick exactly
        int btnW    = (PANEL_W - 32 - BTN_GAP) / 2;
        int btnRowX = panelX + 16;
        int row1Y   = panelY + PANEL_H - (BTN_H * 2) - BTN_GAP - 16;
        int row2Y   = row1Y + BTN_H + BTN_GAP;

        drawButton(r, "Play",      btnRowX,                  row1Y, btnW, BTN_H, selectedIndex >= 0);
        drawButton(r, "New World", btnRowX + btnW + BTN_GAP, row1Y, btnW, BTN_H, true);
        drawButton(r, "Delete",    btnRowX,                  row2Y, btnW, BTN_H, selectedIndex >= 0);
        drawButton(r, "Back",      btnRowX + btnW + BTN_GAP, row2Y, btnW, BTN_H, true);
    }

    private void renderCreate(UiRenderer r, int panelX, int panelY) {
        nameCaretPos = Math.min(nameCaretPos, newWorldName.length());
        seedCaretPos = Math.min(seedCaretPos, newWorldSeed.length());
        charPixelWidth = r.measureText("X");

        // Advance blink timer — getDeltaTime() not available here, so we approximate
        // using a fixed frame budget. At ~60 FPS each frame is ~16ms = 0.016s.
        // Good enough for a blink effect; no need for exact timing.
        caretBlinkTimer += 0.016f;
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
        r.drawText(fieldX, nameLabelY, "World Name:", TXT_R, TXT_G, TXT_B, TXT_A);

        float nameBr = seedFieldActive ? IBORD_R * 0.5f : IBORD_R;
        r.drawRect(fieldX - 1, nameFieldY - 1, fieldW + 2, fieldH + 2,
            nameBr, IBORD_G * (seedFieldActive ? 0.5f : 1f), IBORD_B * (seedFieldActive ? 0.5f : 1f), IBORD_A);
        r.drawRect(fieldX, nameFieldY, fieldW, fieldH, IBG_R, IBG_G, IBG_B, IBG_A);
        r.drawText(fieldX + 8, nameFieldY + 8, newWorldName, TXT_R, TXT_G, TXT_B, TXT_A);

        // Thin caret — 2px wide rect drawn between characters, not inside the string
        if (!seedFieldActive && caretVisible) {
            int caretX = fieldX + 8 + nameCaretPos * charPixelWidth;
            r.drawRect(caretX, nameFieldY + 4, 2, fieldH - 8, TXT_R, TXT_G, TXT_B, TXT_A);
        }

        // --- Seed ---
        int seedLabelY = nameFieldY + fieldH + 16;
        int seedFieldY = seedLabelY + 20;
        r.drawText(fieldX, seedLabelY,
            "Seed (optional — leave blank for random):", DIM_R, DIM_G, DIM_B, 1.0f);

        float seedBr = seedFieldActive ? IBORD_R : IBORD_R * 0.5f;
        r.drawRect(fieldX - 1, seedFieldY - 1, fieldW + 2, fieldH + 2,
            seedBr, IBORD_G * (seedFieldActive ? 1f : 0.5f), IBORD_B * (seedFieldActive ? 1f : 0.5f), IBORD_A);
        r.drawRect(fieldX, seedFieldY, fieldW, fieldH, IBG_R, IBG_G, IBG_B, IBG_A);
        r.drawText(fieldX + 8, seedFieldY + 8, newWorldSeed, TXT_R, TXT_G, TXT_B, TXT_A);

        if (seedFieldActive && caretVisible) {
            int caretX = fieldX + 8 + seedCaretPos * charPixelWidth;
            r.drawRect(caretX, seedFieldY + 4, 2, fieldH - 8, TXT_R, TXT_G, TXT_B, TXT_A);
        }

        r.drawText(fieldX, seedFieldY + fieldH + 6,
            "Numbers only. Negative numbers allowed.", DIM_R, DIM_G, DIM_B, 1.0f);

        // --- Buttons ---
        int twoW  = 2 * BTN_W + BTN_GAP;
        int btn1X = panelX + (PANEL_W - twoW) / 2;
        int btn2X = btn1X + BTN_W + BTN_GAP;
        int btnY  = panelY + PANEL_H - BTN_H - 16;
        drawButton(r, "Create", btn1X, btnY, BTN_W, BTN_H, !newWorldName.isEmpty());
        drawButton(r, "Cancel", btn2X, btnY, BTN_W, BTN_H, true);
    }

    private void renderDelete(UiRenderer r, int panelX, int panelY) {
        String name = selectedIndex >= 0 ? worldNames.get(selectedIndex) : "?";

        int midY = panelY + PANEL_H / 2 - 40;
        r.drawText(panelX + 16, midY, "Delete this world?", WARN_R, WARN_G, WARN_B, 1.0f);
        r.drawText(panelX + 16, midY + 24, name, TXT_R, TXT_G, TXT_B, TXT_A);
        r.drawText(panelX + 16, midY + 50, "This cannot be undone.", WARN_R, WARN_G, WARN_B, 1.0f);

        int twoW  = 2 * BTN_W + BTN_GAP;
        int btn1X = panelX + (PANEL_W - twoW) / 2;
        int btn2X = btn1X + BTN_W + BTN_GAP;
        int btnY  = panelY + PANEL_H - BTN_H - 16;

        drawButton(r, "Delete", btn1X, btnY, BTN_W, BTN_H, true);
        drawButton(r, "Cancel", btn2X, btnY, BTN_W, BTN_H, true);
    }

    private void renderLaunching(UiRenderer r, int panelX, int panelY) {
        String msg = "Starting world...";
        int msgX = panelX + (PANEL_W - msg.length() * 16) / 2;
        int msgY = panelY + PANEL_H / 2 - 8;
        r.drawText(msgX, msgY, msg, TXT_R, TXT_G, TXT_B, TXT_A);
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
            case LAUNCHING      -> { /* block all input while server starts */ }
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

    // -------------------------------------------------------------------------
    // Draw helpers
    // -------------------------------------------------------------------------

    /**
     * Draws a button rectangle. When disabled the button is greyed out and
     * the hover highlight is suppressed. Label is centered inside the button.
     *
     * @param enabled false draws the button as non-interactive
     */
    private void drawButton(UiRenderer r, String label, int x, int y, int w, int h, boolean enabled) {
        float br, bg, bb;
        if (!enabled) {
            br = BDIS_R; bg = BDIS_G; bb = BDIS_B;
        } else if (hits(mouseX, mouseY, x, y, w, h)) {
            br = BHOV_R; bg = BHOV_G; bb = BHOV_B;
        } else {
            br = BTN_R; bg = BTN_G; bb = BTN_B;
        }
        r.drawRect(x, y, w, h, br, bg, bb, BTN_A);

        float tr = enabled ? TXT_R : DIM_R;
        float tg = enabled ? TXT_G : DIM_G;
        float tb = enabled ? TXT_B : DIM_B;
        // r.measureText() gives the correct pixel width — no magic multipliers
        int tx = x + (w - r.measureText(label)) / 2;
        int ty = y + (h - GlyphAtlas.CELL_H) / 2;
        r.drawText(tx, ty, label, tr, tg, tb, TXT_A);
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
}