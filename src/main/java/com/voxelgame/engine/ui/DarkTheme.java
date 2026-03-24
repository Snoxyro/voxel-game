package com.voxelgame.engine.ui;

/**
 * The default built-in dark UI theme.
 */
public final class DarkTheme extends UiTheme {

    /**
     * Constructs the dark theme wrapping the given renderer.
     *
     * @param renderer the shared UiRenderer owned by ScreenManager
     */
    public DarkTheme(UiRenderer renderer) {
        super(renderer);

        // Backgrounds
        colBackground          = 0x0D0D1AFF;
        colPanel               = 0x1A1A2EFF;
        colPanelBorder         = 0x2A2A4EFF;

        // Standard buttons
        colButton              = 0x3A3A6EFF;
        colButtonHover         = 0x5A5A8EFF;
        colButtonDisabled      = 0x222233FF;
        colButtonText          = 0xFFFFFFFF;
        colButtonTextDisabled  = 0x666677FF;

        // Danger buttons (delete, destructive actions)
        colDangerButton        = 0x6E1A1AFF;
        colDangerButtonHover   = 0xAA3333FF;

        // Text
        colText                = 0xFFFFFFFF;
        colTextDim             = 0x888899FF;
        colTextTitle           = 0xFFD94DFF; // warm gold — matches original screens
        colTextWarn            = 0xFF5555FF;

        // Input fields
        colInputBg             = 0x0F0F1EFF;
        colInputBorder         = 0x4A4A6EFF;
        colInputBorderFocused  = 0x8888AAFF;
        colInputCaret          = 0xFFFFFFFF;
        colInputText           = 0xFFFFFFFF;

        // List items
        colListItem            = 0x1E1E30FF;
        colListItemHovered     = 0x2A2A40FF;
        colListItemSelected    = 0x1E3A1EFF; // green tint for selected world

        // Overlay dim (pause menu backdrop)
        colOverlayDim          = 0x00000099; // ~60% opaque black
    }
}