package com.voxelgame.engine.ui;

/**
 * The built-in light UI theme.
 */
public final class LightTheme extends UiTheme {

    /**
     * Constructs the light theme wrapping the given renderer.
     *
     * @param renderer the shared UiRenderer owned by ScreenManager
     */
    public LightTheme(UiRenderer renderer) {
        super(renderer);

        // Backgrounds
        colBackground            = 0xEEEEE8FF;
        colPanel                 = 0xD8D8CCFF;
        colPanelBorder           = 0xAAAAAAFF;

        // Standard buttons
        colButton                = 0x8888AAFF;
        colButtonHover           = 0xAAAACCFF;
        colButtonDisabled        = 0xBBBBBBFF;
        colButtonText            = 0x111111FF;
        colButtonTextDisabled    = 0x888888FF;

        // Danger buttons
        colDangerButton          = 0xCC5555FF;
        colDangerButtonHover     = 0xEE7777FF;

        // Text
        colText                  = 0x111111FF;
        colTextDim               = 0x666666FF;
        colTextTitle             = 0x1A1A8EFF;
        colTextWarn              = 0xCC2222FF;

        // Input fields
        colInputBg               = 0xFFFFFFFF;
        colInputBorder           = 0x999999FF;
        colInputBorderFocused    = 0x3333AAFF;
        colInputCaret            = 0x111111FF;
        colInputText             = 0x111111FF;

        // List items
        colListItem              = 0xE0E0D8FF;
        colListItemHovered       = 0xCCCCDDFF;
        colListItemSelected      = 0xBBDDBBFF;

        // Overlay dim (pause menu backdrop)
        colOverlayDim            = 0x00000066;
    }
}