package com.voxelgame.game.screen;

import com.voxelgame.engine.ui.UiTheme;
import com.voxelgame.game.Action;
import com.voxelgame.game.GameSettings;
import com.voxelgame.game.KeyBindings;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;

/**
 * Full-screen tabbed settings screen.
 *
 * <p>The screen edits local working copies of all settings values. Cancel discards
 * working copies, while Save writes all working values back to {@link GameSettings},
 * persists them to disk, then invokes the supplied save callback for live apply.
 */
public final class SettingsScreen implements Screen {

    private enum Tab { GAMEPLAY, GRAPHICS, DISPLAY, CONTROLS, KEYBINDS, SOUND }

    private static final int TAB_W = 140;
    private static final int TAB_H = 32;
    private static final int TAB_GAP = 8;

    private static final int ROW_H = 40;
    private static final int ROW_GAP = 8;

    private static final int SCROLL_STEP = 24;

    private static final float BRIGHTNESS_MIN = 0.0f;
    private static final float BRIGHTNESS_MAX = 0.3f;
    private static final float BRIGHTNESS_NUDGE_STEP = 0.01f;

    private static final int SLIDER_RENDER_DISTANCE = 0;
    private static final int SLIDER_FOV = 1;
    private static final int SLIDER_BRIGHTNESS = 2;
    private static final int SLIDER_MOUSE_SENS = 3;

    private final GameSettings settings;
    private final boolean sessionActive;
    private final Runnable onSave;
    private final Runnable onCancel;

    private Tab selectedTab = Tab.GAMEPLAY;

    private String workUsername;
    private int workRenderDistance;
    private int workFov;
    private float workingBrightnessFloor;
    private boolean workingAoEnabled;
    private float workMouseSens;
    private String workTheme;
    private boolean workVsync;
    private GameSettings.WindowMode workWindowMode;
    private final EnumMap<Action, Integer> workBindings;

    private int mouseX;
    private int mouseY;
    private int lastScreenW;
    @SuppressWarnings("unused")
    private int lastScreenH;

    private boolean usernameFocused;
    private int usernameCaretPos;
    private float caretBlinkTimer;

    private boolean sliderDragging;
    private int draggingSlider = -1;
    private int lastDragMouseX;
    private int focusedSlider = -1;  // -1 = none; uses SLIDER_* constants

    private Action listeningForAction;

    private int scrollOffset;
    private int maxScroll;

    private final EnumMap<Tab, Rect> tabRects = new EnumMap<>(Tab.class);
    private final EnumMap<Action, Rect> keybindButtonRects = new EnumMap<>(Action.class);

    private Rect saveButtonRect = Rect.EMPTY;
    private Rect cancelButtonRect = Rect.EMPTY;

    private Rect usernameFieldRect = Rect.EMPTY;
    private Rect renderDistanceSliderRect = Rect.EMPTY;
    private Rect fovSliderRect = Rect.EMPTY;
    private Rect brightnessSliderRect = Rect.EMPTY;
    private Rect aoRowRect = Rect.EMPTY;
    private Rect aoButtonRect = Rect.EMPTY;
    private Rect mouseSensSliderRect = Rect.EMPTY;
    private Rect vsyncButtonRect = Rect.EMPTY;
    private Rect themeButtonRect = Rect.EMPTY;
    private Rect windowModeButtonRect = Rect.EMPTY;

    /**
     * Creates a new settings screen with local working copies initialized from
     * the provided shared settings instance.
     */
    public SettingsScreen(GameSettings settings, boolean sessionActive, Runnable onSave, Runnable onCancel) {
        this.settings = settings;
        this.sessionActive = sessionActive;
        this.onSave = onSave;
        this.onCancel = onCancel;

        this.workUsername = settings.getUsername();
        this.workRenderDistance = settings.getRenderDistance();
        this.workFov = settings.getFov();
        this.workingBrightnessFloor = settings.getBrightnessFloor();
        this.workingAoEnabled = settings.isAoEnabled();
        this.workMouseSens = settings.getMouseSensitivity();
        this.workTheme = normalizeTheme(settings.getTheme());
        this.workVsync = settings.isVsync();
        this.workWindowMode = settings.getWindowMode();

        this.workBindings = new EnumMap<>(Action.class);
        var copied = settings.getKeyBindings().copy();
        for (Action action : Action.values()) {
            workBindings.put(action, copied.get(action));
        }

        this.usernameCaretPos = workUsername.length();
    }

    @Override
    public boolean isOverlay() {
        return false;
    }

    @Override
    public void onShow() {
        usernameFocused = false;
        caretBlinkTimer = 0f;
        sliderDragging = false;
        draggingSlider = -1;
        focusedSlider = -1;
        listeningForAction = null;
        scrollOffset = 0;
        maxScroll = 0;
    }

    @Override
    public void onHide() {
        sliderDragging = false;
        draggingSlider = -1;
    }

    @Override
    public void render(UiTheme theme, float deltaTime, int sw, int sh) {
        lastScreenW = sw;
        lastScreenH = sh;

        double[] cx = {0.0};
        double[] cy = {0.0};
        GLFW.glfwGetCursorPos(GLFW.glfwGetCurrentContext(), cx, cy);
        mouseX = (int) cx[0];
        mouseY = (int) cy[0];

        caretBlinkTimer += deltaTime;
        boolean caretVisible = (caretBlinkTimer % 1.0f) < 0.5f;

        if (sliderDragging) {
            updateDraggingSlider();
            if (GLFW.glfwGetMouseButton(GLFW.glfwGetCurrentContext(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
                sliderDragging = false;
                draggingSlider = -1;
            }
        }

        clearInteractionRects();

        theme.drawBackground(sw, sh);
        theme.drawTitle(sw / 2.0f, 20, "Settings");

        int tabBarY = 56;
        renderTabBar(theme, sw, tabBarY);

        int bodyX = Math.max(32, sw / 10);
        int bodyY = tabBarY + TAB_H + 14;
        int bodyW = Math.max(640, sw - bodyX * 2);
        int bottomBarH = 72;
        int bodyH = Math.max(220, sh - bodyY - bottomBarH - 18);

        theme.drawPanel(bodyX, bodyY, bodyW, bodyH);

        int contentX = bodyX + 12;
        int contentY = bodyY + 12 - scrollOffset;
        int contentW = bodyW - 24;
        int contentVisibleH = bodyH - 24;

        int totalContentH = renderCurrentTab(theme, contentX, contentY, contentW, contentVisibleH, caretVisible);
        maxScroll = Math.max(0, totalContentH - contentVisibleH);
        scrollOffset = clampInt(scrollOffset, 0, maxScroll);

        int bottomY = bodyY + bodyH + 10;
        renderBottomBar(theme, sw, bottomY);
    }

    @Override
    public void onMouseClick(int x, int y, int button) {
        mouseX = x;
        mouseY = y;

        if (listeningForAction != null) {
            workBindings.put(listeningForAction, KeyBindings.MOUSE_BUTTON_OFFSET + button);
            listeningForAction = null;
            return;
        }

        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;

        for (Tab tab : Tab.values()) {
            Rect tabRect = tabRects.get(tab);
            if (tabRect != null && tabRect.hits(x, y) && tab != selectedTab) {
                selectedTab = tab;
                scrollOffset = 0;
                usernameFocused = false;
                sliderDragging = false;
                draggingSlider = -1;
                listeningForAction = null;
                return;
            }
        }

        if (saveButtonRect.hits(x, y)) {
            doSave();
            return;
        }
        if (cancelButtonRect.hits(x, y)) {
            onCancel.run();
            return;
        }

        switch (selectedTab) {
            case GAMEPLAY  -> handleGameplayClick(x, y);
            case GRAPHICS  -> handleGraphicsClick(x, y);
            case DISPLAY   -> handleDisplayClick(x, y);
            case CONTROLS  -> handleControlsClick(x, y);
            case KEYBINDS  -> handleKeybindsClick(x, y);
            case SOUND     -> usernameFocused = false;
        }
    }

    @Override
    public void onKeyPress(int key, int mods) {
        if (listeningForAction != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                workBindings.put(listeningForAction, KeyBindings.UNBOUND);
            } else {
                workBindings.put(listeningForAction, key);
            }
            listeningForAction = null;
            return;
        }

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onCancel.run();
            return;
        }

        if (key == GLFW.GLFW_KEY_TAB) {
            boolean backwards = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
            cycleTab(backwards ? -1 : 1);
            return;
        }

        // Arrow key nudge for focused slider
        if (focusedSlider != -1) {
            if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT) {
                float delta = (key == GLFW.GLFW_KEY_RIGHT) ? 1f : -1f;
                switch (focusedSlider) {
                    case SLIDER_RENDER_DISTANCE ->
                        workRenderDistance = clampInt(workRenderDistance + (int) delta, 2, 32);
                    case SLIDER_FOV ->
                        workFov = clampInt(workFov + (int) delta, 50, 110);
                    case SLIDER_BRIGHTNESS ->
                        workingBrightnessFloor = clampFloat(
                            workingBrightnessFloor + delta * BRIGHTNESS_NUDGE_STEP,
                            BRIGHTNESS_MIN,
                            BRIGHTNESS_MAX);
                    case SLIDER_MOUSE_SENS ->
                        workMouseSens = clampFloat(workMouseSens + delta * 0.05f, 0.1f, 2.0f);
                }
                return;
            }
            // Any other key clears slider focus
            if (key != GLFW.GLFW_KEY_TAB) focusedSlider = -1;
        }

        if (usernameFocused && !sessionActive) {
            handleUsernameEditKey(key);
        }
    }

    @Override
    public void onCharTyped(char c) {
        if (!usernameFocused || sessionActive) return;
        if (c >= 32 && c < 127) {
            workUsername = workUsername.substring(0, usernameCaretPos)
                + c
                + workUsername.substring(usernameCaretPos);
            usernameCaretPos++;
        }
    }

    /**
     * Rich key callback variant for newer screen input pipelines.
     */
    public void onKey(long window, int key, int scancode, int action, int mods) {
        if (action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT) {
            onKeyPress(key, mods);
        }
    }

    /**
     * Rich mouse callback variant for newer screen input pipelines.
     */
    public void onMouseButton(long window, int button, int action, int mods, double mx, double my) {
        if (action == GLFW.GLFW_PRESS) {
            onMouseClick((int) mx, (int) my, button);
        }
        if (action == GLFW.GLFW_RELEASE && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            sliderDragging = false;
            draggingSlider = -1;
        }
    }

    /**
     * Rich char callback variant for newer screen input pipelines.
     */
    public void onChar(long window, int codepoint) {
        if (Character.isValidCodePoint(codepoint) && codepoint <= Character.MAX_VALUE) {
            onCharTyped((char) codepoint);
        }
    }

    /**
     * Scroll callback used by the keybinds/settings body region.
     */
    public void onScroll(long window, double dx, double dy) {
        scrollOffset -= (int) (dy * SCROLL_STEP);
        scrollOffset = clampInt(scrollOffset, 0, maxScroll);
    }

    private int renderCurrentTab(UiTheme theme, int x, int y, int w, int visibleH, boolean caretVisible) {
        return switch (selectedTab) {
            case GAMEPLAY -> renderGameplayTab(theme, x, y, w, visibleH, caretVisible);
            case GRAPHICS -> renderGraphicsTab(theme, x, y, w, visibleH);
            case DISPLAY -> renderDisplayTab(theme, x, y, w, visibleH);
            case CONTROLS -> renderControlsTab(theme, x, y, w, visibleH);
            case KEYBINDS -> renderKeybindsTab(theme, x, y, w, visibleH);
            case SOUND -> renderSoundTab(theme, x, y, w, visibleH);
        };
    }

    private int renderGameplayTab(UiTheme theme, int x, int y, int w, int visibleH, boolean caretVisible) {
        int rowY = y;

        boolean usernameDirty = !workUsername.equals(settings.getUsername());
        int usernameRowH = (sessionActive) ? ROW_H + theme.lineHeight() + 2 : ROW_H;
        drawRow(theme, x, rowY, w, usernameRowH, usernameDirty ? 0x5FA8FF33 : 0x00000000);
        drawRowLabel(theme, x, rowY, w, "Username");
        Rect usernameWidget = rowWidgetRect(x, rowY, w, ROW_H);
        usernameFieldRect = usernameWidget;
        theme.drawInputField(usernameWidget.x, usernameWidget.y, usernameWidget.w, usernameWidget.h,
            workUsername, usernameCaretPos, usernameFocused && !sessionActive, caretVisible && !sessionActive);
        if (sessionActive) {
            // Dim overlay over the input field to show it's locked
            theme.renderer().drawRect(usernameWidget.x, usernameWidget.y,
                usernameWidget.w, usernameWidget.h, 0x80808066);
            // Draw the lock note inside the label column, below the label text
            theme.drawDimLabel(x + 8,
                rowY + (ROW_H - theme.lineHeight()) / 2.0f + theme.lineHeight(),
                "(session active)");
        }
        rowY += usernameRowH + ROW_GAP;

        return Math.max(visibleH, rowY - y);
    }

    private int renderGraphicsTab(UiTheme theme, int x, int y, int w, int visibleH) {
        int rowY = y;

        boolean rdDirty = workRenderDistance != settings.getRenderDistance();
        drawRow(theme, x, rowY, w, ROW_H, rdDirty ? 0x5FA8FF33 : 0x00000000);
        drawRowLabel(theme, x, rowY, w, "Render Distance");
        renderDistanceSliderRect = rowWidgetRect(x, rowY, w, ROW_H);
        drawSlider(theme, renderDistanceSliderRect, workRenderDistance, 2f, 32f, String.valueOf(workRenderDistance), focusedSlider == SLIDER_RENDER_DISTANCE);
        rowY += ROW_H + ROW_GAP;

        boolean fovDirty = workFov != settings.getFov();
        drawRow(theme, x, rowY, w, ROW_H, fovDirty ? 0x5FA8FF33 : 0x00000000);
        drawRowLabel(theme, x, rowY, w, "Field of View");
        fovSliderRect = rowWidgetRect(x, rowY, w, ROW_H);
        drawSlider(theme, fovSliderRect, workFov, 50f, 110f, workFov + "°", focusedSlider == SLIDER_FOV);
        rowY += ROW_H + ROW_GAP;

        boolean brightnessDirty = workingBrightnessFloor != settings.getBrightnessFloor();
        drawRow(theme, x, rowY, w, ROW_H, brightnessDirty ? 0x5FA8FF33 : 0x00000000);
        drawRowLabel(theme, x, rowY, w, "Brightness");
        brightnessSliderRect = rowWidgetRect(x, rowY, w, ROW_H);
        int brightnessPercent = Math.round(workingBrightnessFloor / BRIGHTNESS_MAX * 100.0f);
        drawSlider(theme, brightnessSliderRect, workingBrightnessFloor, BRIGHTNESS_MIN, BRIGHTNESS_MAX,
            brightnessPercent + "%", focusedSlider == SLIDER_BRIGHTNESS);
        rowY += ROW_H + ROW_GAP;

        boolean aoDirty = workingAoEnabled != settings.isAoEnabled();
        drawRow(theme, x, rowY, w, ROW_H, aoDirty ? 0x5FA8FF33 : 0x00000000);
        drawRowLabel(theme, x, rowY, w, "Ambient Occlusion");
        aoRowRect = new Rect(x, rowY, w, ROW_H);
        aoButtonRect = rowWidgetRect(x, rowY, w, ROW_H);
        theme.drawButton(aoButtonRect.x, aoButtonRect.y, aoButtonRect.w, aoButtonRect.h,
            workingAoEnabled ? "ON" : "OFF", aoButtonRect.hits(mouseX, mouseY));
        rowY += ROW_H + ROW_GAP;

        boolean themeDirty = !normalizeTheme(settings.getTheme()).equals(workTheme);
        drawRow(theme, x, rowY, w, ROW_H, themeDirty ? 0x5FA8FF33 : 0x00000000);
        drawRowLabel(theme, x, rowY, w, "UI Theme");
        themeButtonRect = rowWidgetRect(x, rowY, w, ROW_H);
        String themeLabel = "light".equals(workTheme) ? "Light" : "Dark";
        theme.drawButton(themeButtonRect.x, themeButtonRect.y, themeButtonRect.w, themeButtonRect.h,
            themeLabel, themeButtonRect.hits(mouseX, mouseY));
        rowY += ROW_H + ROW_GAP;

        return Math.max(visibleH, rowY - y);
    }

    private int renderDisplayTab(UiTheme theme, int x, int y, int w, int visibleH) {
        int rowY = y;

        boolean vsyncDirty = workVsync != settings.isVsync();
        drawRow(theme, x, rowY, w, ROW_H, vsyncDirty ? 0x5FA8FF33 : 0x00000000);
        drawRowLabel(theme, x, rowY, w, "VSync");
        vsyncButtonRect = rowWidgetRect(x, rowY, w, ROW_H);
        theme.drawButton(vsyncButtonRect.x, vsyncButtonRect.y, vsyncButtonRect.w, vsyncButtonRect.h,
            workVsync ? "ON" : "OFF", vsyncButtonRect.hits(mouseX, mouseY));
        rowY += ROW_H + ROW_GAP;

        boolean modeDirty = workWindowMode != settings.getWindowMode();
        drawRow(theme, x, rowY, w, ROW_H, modeDirty ? 0x5FA8FF33 : 0x00000000);
        drawRowLabel(theme, x, rowY, w, "Window Mode");
        windowModeButtonRect = rowWidgetRect(x, rowY, w, ROW_H);
        theme.drawButton(windowModeButtonRect.x, windowModeButtonRect.y, windowModeButtonRect.w, windowModeButtonRect.h,
            workWindowMode.displayName, windowModeButtonRect.hits(mouseX, mouseY));
        rowY += ROW_H + ROW_GAP;

        return Math.max(visibleH, rowY - y);
    }

    private int renderControlsTab(UiTheme theme, int x, int y, int w, int visibleH) {
        int rowY = y;

        boolean sensDirty = Math.abs(workMouseSens - settings.getMouseSensitivity()) > 0.0001f;
        drawRow(theme, x, rowY, w, ROW_H, sensDirty ? 0x5FA8FF33 : 0x00000000);
        drawRowLabel(theme, x, rowY, w, "Mouse Sensitivity");
        mouseSensSliderRect = rowWidgetRect(x, rowY, w, ROW_H);
        drawSlider(theme, mouseSensSliderRect, workMouseSens, 0.1f, 2.0f,
            String.format(java.util.Locale.ROOT, "%.2f", workMouseSens),
            focusedSlider == SLIDER_MOUSE_SENS);
        rowY += ROW_H + ROW_GAP;

        return Math.max(visibleH, rowY - y);
    }

    private int renderKeybindsTab(UiTheme theme, int x, int y, int w, int visibleH) {
        int rowY = y;
        keybindButtonRects.clear();

        for (Action action : Action.values()) {
            int currentCode = workBindings.getOrDefault(action, KeyBindings.UNBOUND);
            int liveCode = settings.getKeyBindings().get(action);
            boolean dirty = currentCode != liveCode;
            Action conflict = findConflict(action, currentCode);

            int rowHeight = conflict == null ? ROW_H : ROW_H + theme.lineHeight() + 8;
            int tint = 0x00000000;
            if (dirty) tint = 0x5FA8FF33;
            if (conflict != null) tint = 0xFFE16A55;

            drawRow(theme, x, rowY, w, rowHeight, tint);
            drawRowLabel(theme, x, rowY, w, action.displayName);

            Rect widget = rowWidgetRect(x, rowY, w, ROW_H);
            keybindButtonRects.put(action, widget);

            String label = listeningForAction == action
                ? "Press any key..."
                : KeyBindings.displayName(currentCode);

            theme.drawButton(widget.x, widget.y, widget.w, widget.h, label,
                widget.hits(mouseX, mouseY));

            if (conflict != null) {
                theme.drawWarnLabel(x + 8,
                    rowY + ROW_H + 2,
                    "Conflicts with " + conflict.displayName);
            }

            rowY += rowHeight + ROW_GAP;
        }

        return Math.max(visibleH, rowY - y);
    }

    private int renderSoundTab(UiTheme theme, int x, int y, int w, int visibleH) {
        theme.drawCenteredLabel(x + w / 2.0f, y + Math.max(20, visibleH / 2.0f),
            "Sound settings coming soon.");
        return visibleH;
    }

    private void renderTabBar(UiTheme theme, int sw, int tabBarY) {
        int tabCount = Tab.values().length;
        int totalW = tabCount * TAB_W + (tabCount - 1) * TAB_GAP;
        int startX = (sw - totalW) / 2;

        for (int i = 0; i < tabCount; i++) {
            Tab tab = Tab.values()[i];
            int x = startX + i * (TAB_W + TAB_GAP);
            Rect rect = new Rect(x, tabBarY, TAB_W, TAB_H);
            tabRects.put(tab, rect);

            String label = prettifyTabName(tab);
            boolean hovered = rect.hits(mouseX, mouseY);
            if (tab == selectedTab) {
                theme.renderer().drawRect(rect.x - 1, rect.y - 1, rect.w + 2, rect.h + 2, 0xFFFFFFFF);
                theme.drawButton(rect.x, rect.y, rect.w, rect.h, label, true);
            } else {
                theme.drawButton(rect.x, rect.y, rect.w, rect.h, label, hovered);
            }
        }
    }

    private void renderBottomBar(UiTheme theme, int sw, int y) {
        int btnW = 170;
        int btnH = 40;
        int gap = 14;
        int totalW = btnW * 2 + gap;
        int startX = (sw - totalW) / 2;

        saveButtonRect = new Rect(startX, y, btnW, btnH);
        cancelButtonRect = new Rect(startX + btnW + gap, y, btnW, btnH);

        theme.drawButton(saveButtonRect.x, saveButtonRect.y, saveButtonRect.w, saveButtonRect.h,
            "Save", saveButtonRect.hits(mouseX, mouseY));
        theme.drawButton(cancelButtonRect.x, cancelButtonRect.y, cancelButtonRect.w, cancelButtonRect.h,
            "Cancel", cancelButtonRect.hits(mouseX, mouseY));
    }

    private void handleGameplayClick(int x, int y) {
        usernameFocused = usernameFieldRect.hits(x, y) && !sessionActive;
        if (usernameFocused) {
            int rel = x - (usernameFieldRect.x + 6);
            int charW = estimateCharWidth();
            usernameCaretPos = clampInt((rel + charW / 2) / charW, 0, workUsername.length());
        }
    }

    private void handleGraphicsClick(int x, int y) {
        usernameFocused = false;
        if (renderDistanceSliderRect.hits(x, y)) {
            focusedSlider = SLIDER_RENDER_DISTANCE;
            startSliderDrag(SLIDER_RENDER_DISTANCE);
            return;
        }
        if (fovSliderRect.hits(x, y)) {
            focusedSlider = SLIDER_FOV;
            startSliderDrag(SLIDER_FOV);
            return;
        }
        if (brightnessSliderRect.hits(x, y)) {
            focusedSlider = SLIDER_BRIGHTNESS;
            startSliderDrag(SLIDER_BRIGHTNESS);
            return;
        }
        focusedSlider = -1;
        if (aoRowRect.hits(x, y)) {
            workingAoEnabled = !workingAoEnabled;
            return;
        }
        if (themeButtonRect.hits(x, y)) {
            workTheme = "dark".equals(workTheme) ? "light" : "dark";
        }
    }

    private void handleControlsClick(int x, int y) {
        usernameFocused = false;
        if (mouseSensSliderRect.hits(x, y)) {
            focusedSlider = SLIDER_MOUSE_SENS;
            startSliderDrag(SLIDER_MOUSE_SENS);
            return;
        }
        focusedSlider = -1;
    }

    private void handleDisplayClick(int x, int y) {
        usernameFocused = false;
        if (vsyncButtonRect.hits(x, y)) { workVsync = !workVsync; return; }
        if (windowModeButtonRect.hits(x, y)) {
            GameSettings.WindowMode[] modes = GameSettings.WindowMode.values();
            workWindowMode = modes[(workWindowMode.ordinal() + 1) % modes.length];
        }
    }

    private void handleKeybindsClick(int x, int y) {
        usernameFocused = false;
        for (Action action : Action.values()) {
            Rect rect = keybindButtonRects.get(action);
            if (rect != null && rect.hits(x, y)) {
                listeningForAction = action;
                return;
            }
        }
    }

    private void handleUsernameEditKey(int key) {
        switch (key) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (usernameCaretPos > 0 && !workUsername.isEmpty()) {
                    workUsername = workUsername.substring(0, usernameCaretPos - 1)
                        + workUsername.substring(usernameCaretPos);
                    usernameCaretPos--;
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (usernameCaretPos < workUsername.length()) {
                    workUsername = workUsername.substring(0, usernameCaretPos)
                        + workUsername.substring(usernameCaretPos + 1);
                }
            }
            case GLFW.GLFW_KEY_LEFT -> usernameCaretPos = Math.max(0, usernameCaretPos - 1);
            case GLFW.GLFW_KEY_RIGHT -> usernameCaretPos = Math.min(workUsername.length(), usernameCaretPos + 1);
            case GLFW.GLFW_KEY_HOME -> usernameCaretPos = 0;
            case GLFW.GLFW_KEY_END -> usernameCaretPos = workUsername.length();
            default -> {
                // no-op
            }
        }
    }

    private void drawRow(UiTheme theme, int x, int y, int w, int h, int tint) {
        if (tint != 0) {
            theme.renderer().drawRect(x, y, w, h, tint);
        }
    }

    private void drawRowLabel(UiTheme theme, int x, int y, int w, String label) {
        int labelW = (int) (w * 0.70f);
        theme.drawLabel(x + 8, y + (ROW_H - theme.lineHeight()) / 2.0f, label);
        theme.renderer().drawRect(x + labelW, y + 8, 1, ROW_H - 16, 0xFFFFFF22);
    }

    private Rect rowWidgetRect(int x, int y, int w, int h) {
        int labelW = (int) (w * 0.70f);
        int widgetX = x + labelW + 12;
        int widgetW = Math.max(80, w - labelW - 20);
        return new Rect(widgetX, y + 4, widgetW, h - 8);
    }

    private void drawSlider(UiTheme theme, Rect area, float value, float min, float max,
                            String valueText, boolean focused) {
        int valueW = Math.max(36, theme.measureText(valueText) + 8);
        int trackX = area.x;
        int trackY = area.y + area.h / 2 - 3;
        int trackW = Math.max(10, area.w - valueW - 12);
        int trackH = 6;

        float t = clampFloat((value - min) / (max - min), 0f, 1f);
        int fillW = (int) (trackW * t);
        int thumbX = trackX + (int) (trackW * t) - 4;
        int thumbY = trackY - 4;

        // Focused highlight — draw a subtle border around the whole widget area
        if (focused) {
            theme.renderer().drawRect(area.x - 2, area.y - 2, area.w + 4, area.h + 4, 0x8888AACC);
        }

        theme.renderer().drawRect(trackX, trackY, trackW, trackH, 0xFFFFFF44);
        theme.renderer().drawRect(trackX, trackY, fillW, trackH, 0x7BC6FFCC);
        // Thumb is brighter when focused
        int thumbColor = focused ? 0xFFFFFFFF : 0xCCCCCCFF;
        theme.renderer().drawRect(thumbX, thumbY, 8, 14, thumbColor);

        theme.drawDimLabel(trackX + trackW + 8,
            area.y + (area.h - theme.lineHeight()) / 2.0f, valueText);
    }

    // Keep old signature as a delegate for any call sites that don't pass focused
    @SuppressWarnings("unused")
    private void drawSlider(UiTheme theme, Rect area, float value, float min, float max, String valueText) {
        drawSlider(theme, area, value, min, max, valueText, false);
    }

    private void startSliderDrag(int sliderIndex) {
        sliderDragging = true;
        draggingSlider = sliderIndex;
        lastDragMouseX = mouseX;
        updateDraggingSlider();
    }

    private void updateDraggingSlider() {
        switch (draggingSlider) {
            case SLIDER_RENDER_DISTANCE -> {
                if (renderDistanceSliderRect != null) {
                    workRenderDistance = (int) Math.round(sliderValueFromMouse(renderDistanceSliderRect, 2f, 32f));
                    workRenderDistance = clampInt(workRenderDistance, 2, 32);
                }
            }
            case SLIDER_FOV -> {
                if (fovSliderRect != null) {
                    workFov = (int) Math.round(sliderValueFromMouse(fovSliderRect, 50f, 110f));
                    workFov = clampInt(workFov, 50, 110);
                }
            }
            case SLIDER_MOUSE_SENS -> {
                if (mouseSensSliderRect != null) {
                    workMouseSens = sliderValueFromMouse(mouseSensSliderRect, 0.1f, 2.0f);
                    workMouseSens = clampFloat(workMouseSens, 0.1f, 2.0f);
                }
            }
            case SLIDER_BRIGHTNESS -> {
                if (brightnessSliderRect != null) {
                    int sliderWidth = sliderTrackWidth(brightnessSliderRect, "100%");
                    int dragDelta = mouseX - lastDragMouseX;
                    lastDragMouseX = mouseX;
                    float adjustment = (dragDelta / (float) sliderWidth) * BRIGHTNESS_MAX;
                    workingBrightnessFloor = clampFloat(
                        workingBrightnessFloor + adjustment,
                        BRIGHTNESS_MIN,
                        BRIGHTNESS_MAX);
                }
            }
            default -> {
                // no-op
            }
        }
    }

    private int sliderTrackWidth(Rect area, String maxValueText) {
        int valueW = Math.max(36, 8 + maxValueText.length() * estimateCharWidth());
        return Math.max(10, area.w - valueW - 12);
    }

    private float sliderValueFromMouse(Rect area, float min, float max) {
        String maxText = (max == 2.0f)
            ? String.format(java.util.Locale.ROOT, "%.2f", max)
            : String.valueOf((int) max);
        int trackW = sliderTrackWidth(area, maxText);
        float t = clampFloat((mouseX - area.x) / (float) trackW, 0f, 1f);
        return min + t * (max - min);
    }

    private void cycleTab(int delta) {
        Tab[] tabs = Tab.values();
        int idx = selectedTab.ordinal();
        idx = (idx + delta + tabs.length) % tabs.length;
        selectedTab = tabs[idx];
        scrollOffset = 0;
        sliderDragging = false;
        draggingSlider = -1;
        focusedSlider = -1;
        listeningForAction = null;
        usernameFocused = false;
    }

    private Action findConflict(Action source, int code) {
        if (code == KeyBindings.UNBOUND) return null;
        for (Action other : Action.values()) {
            if (other == source) continue;
            if (workBindings.getOrDefault(other, KeyBindings.UNBOUND) == code) {
                return other;
            }
        }
        return null;
    }

    private void doSave() {
        settings.setUsername(workUsername);
        settings.setRenderDistance(workRenderDistance);
        settings.setFov(workFov);
        settings.setBrightnessFloor(workingBrightnessFloor);
        settings.setAoEnabled(workingAoEnabled);
        settings.setMouseSensitivity(workMouseSens);
        settings.setTheme(workTheme);
        settings.setVsync(workVsync);
        settings.setWindowMode(workWindowMode);
        settings.getKeyBindings().setAll(workBindings);
        try {
            settings.save(Path.of("settings.properties"));
        } catch (IOException e) {
            System.err.println("[SettingsScreen] Failed to save settings: " + e.getMessage());
        }
        onSave.run();
    }

    private void clearInteractionRects() {
        tabRects.clear();
        keybindButtonRects.clear();
        saveButtonRect = Rect.EMPTY;
        cancelButtonRect = Rect.EMPTY;
        usernameFieldRect = Rect.EMPTY;
        renderDistanceSliderRect = Rect.EMPTY;
        fovSliderRect = Rect.EMPTY;
        brightnessSliderRect = Rect.EMPTY;
        aoRowRect = Rect.EMPTY;
        aoButtonRect = Rect.EMPTY;
        mouseSensSliderRect = Rect.EMPTY;
        vsyncButtonRect = Rect.EMPTY;
        themeButtonRect = Rect.EMPTY;
        windowModeButtonRect = Rect.EMPTY;
    }

    private String normalizeTheme(String theme) {
        return "light".equalsIgnoreCase(theme) ? "light" : "dark";
    }

    private String prettifyTabName(Tab tab) {
        return switch (tab) {
            case GAMEPLAY -> "Gameplay";
            case GRAPHICS -> "Graphics";
            case DISPLAY -> "Display";
            case CONTROLS -> "Controls";
            case KEYBINDS -> "Keybinds";
            case SOUND -> "Sound";
        };
    }

    private int estimateCharWidth() {
        return Math.max(6, lastScreenW / 180);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampFloat(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Rect(int x, int y, int w, int h) {
        static final Rect EMPTY = new Rect(-1, -1, 0, 0);

        boolean hits(int px, int py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }
}