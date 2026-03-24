package com.voxelgame.engine.ui;

/**
 * Abstract base class for all UI themes.
 *
 * <p>A theme owns a set of named color constants and a library of compound
 * draw helpers ({@link #drawButton}, {@link #drawPanel}, {@link #drawInputField},
 * etc.) that screens use instead of calling {@link UiRenderer} directly with raw
 * float colors. This means every screen automatically reflects a theme change —
 * screens never hardcode colors.
 *
 * <h3>Colors</h3>
 * All color fields are packed {@code 0xRRGGBBAA} ints. Subclasses set them in
 * their constructor. Fields are {@code protected} so subclasses can override
 * individual values for minor customisation without extending the full class.
 *
 * <h3>Custom draw behavior</h3>
 * Every draw helper is a regular instance method, so a subclass can override any
 * of them to change shape, animation, or structure — not just colors.
 *
 * <h3>Thread safety</h3>
 * UiTheme is not thread-safe. All calls must be made from the GL thread.
 *
 * <h3>Extending</h3>
 * To create a new theme:
 * <ol>
 *   <li>Extend {@code UiTheme}.</li>
 *   <li>Call {@code super(renderer)} in the constructor.</li>
 *   <li>Set all {@code col*} fields before the constructor returns.</li>
 *   <li>Override draw helpers if you need shape/layout changes, not just color.</li>
 * </ol>
 */
public abstract class UiTheme {

    // ── Color fields — all packed 0xRRGGBBAA ──────────────────────────────────
    // Subclasses assign these in their constructor.

    // Backgrounds
    /** Full-screen background fill for non-overlay menus. */
    protected int colBackground;
    /** Main panel background. */
    protected int colPanel;
    /** 1px border drawn around panels. */
    protected int colPanelBorder;

    // Standard buttons
    /** Button background in the default resting state. */
    protected int colButton;
    /** Button background when the cursor hovers over it. */
    protected int colButtonHover;
    /** Button background when the button is disabled/non-interactive. */
    protected int colButtonDisabled;
    /** Button label text color. */
    protected int colButtonText;
    /** Button label text color when the button is disabled. */
    protected int colButtonTextDisabled;

    // Danger (destructive) buttons — e.g. Delete
    /** Background of a destructive button in the default state. */
    protected int colDangerButton;
    /** Background of a destructive button on hover. */
    protected int colDangerButtonHover;

    // Text
    /** Default body text. */
    protected int colText;
    /** Secondary / dimmed text. */
    protected int colTextDim;
    /** Screen title text (usually an accent color). */
    protected int colTextTitle;
    /** Warning or error text. */
    protected int colTextWarn;

    // Input fields
    /** Input field background. */
    protected int colInputBg;
    /** Input field border when the field does not have focus. */
    protected int colInputBorder;
    /** Input field border when the field has keyboard focus. */
    protected int colInputBorderFocused;
    /** Blinking text caret color. */
    protected int colInputCaret;
    /** Text color inside input fields. */
    protected int colInputText;

    // List items (world list, etc.)
    /** List row background in the default state. */
    protected int colListItem;
    /** List row background on hover. */
    protected int colListItemHovered;
    /** List row background when the row is selected. */
    protected int colListItemSelected;

    // Overlay
    /**
     * Semi-transparent full-screen tint drawn by overlay screens (e.g. pause menu)
     * to dim the world visible behind the panel.
     */
    protected int colOverlayDim;

    // ── Internal constants ────────────────────────────────────────────────────

    /** Border thickness in pixels on input fields. */
    private static final int INPUT_BORDER_PX = 1;
    /** Left/right padding inside input fields and list items. */
    private static final int INNER_PAD = 6;
    /** Width of the blinking text caret in pixels. */
    private static final int CARET_W = 2;

    // ── Renderer reference ────────────────────────────────────────────────────

    private final UiRenderer renderer;

    /**
     * Constructs a theme wrapping the given renderer.
     * Subclasses must set all {@code col*} fields after calling this.
     *
     * @param renderer the shared UI renderer owned by {@link com.voxelgame.game.screen.ScreenManager}
     */
    protected UiTheme(UiRenderer renderer) {
        this.renderer = renderer;
    }

    // ── Frame lifecycle ───────────────────────────────────────────────────────

    /**
     * Begins a UI frame — sets up GL blend state and the orthographic projection.
     * Call once before any draw helpers. Delegates to {@link UiRenderer#begin}.
     */
    public void begin(int screenW, int screenH) {
        renderer.begin(screenW, screenH);
    }

    /**
     * Ends a UI frame, flushes any remaining quads, and restores GL state.
     * Delegates to {@link UiRenderer#end}.
     */
    public void end() {
        renderer.end();
    }

    // ── Compound draw helpers ─────────────────────────────────────────────────

    /**
     * Fills the entire screen with {@link #colBackground}.
     * Use at the start of full-screen (non-overlay) menus.
     */
    public void drawBackground(int screenW, int screenH) {
        renderer.drawRect(0, 0, screenW, screenH, colBackground);
    }

    /**
     * Draws a panel (1px border + filled background).
     *
     * @param x left edge in pixels
     * @param y top edge in pixels
     * @param w width in pixels
     * @param h height in pixels
     */
    public void drawPanel(float x, float y, float w, float h) {
        // Border is drawn as a 1px-larger rect behind the panel fill.
        renderer.drawRect(x - 1, y - 1, w + 2, h + 2, colPanelBorder);
        renderer.drawRect(x, y, w, h, colPanel);
    }

    /**
     * Draws a standard button. Label is centered inside.
     *
     * @param hovered true if the cursor is over this button
     */
    public void drawButton(float x, float y, float w, float h, String label, boolean hovered) {
        drawButton(x, y, w, h, label, hovered, true);
    }

    /**
     * Draws a button with an optional disabled state.
     * Disabled buttons use {@link #colButtonDisabled} and {@link #colButtonTextDisabled}.
     *
     * @param enabled false dims the button and suppresses the hover highlight
     */
    public void drawButton(float x, float y, float w, float h,
                           String label, boolean hovered, boolean enabled) {
        int bg  = !enabled ? colButtonDisabled  : hovered ? colButtonHover : colButton;
        int txt = !enabled ? colButtonTextDisabled : colButtonText;
        renderer.drawRect(x, y, w, h, bg);
        int tx = (int)(x + (w - renderer.measureText(label)) / 2f);
        int ty = (int)(y + (h - renderer.lineHeight()) / 2f);
        renderer.drawText(tx, ty, label, txt);
    }

    /**
     * Draws a destructive/danger button (e.g. Delete). Uses {@link #colDangerButton}
     * color family. Label text uses {@link #colButtonText} (same as standard buttons).
     */
    public void drawDangerButton(float x, float y, float w, float h,
                                 String label, boolean hovered) {
        int bg = hovered ? colDangerButtonHover : colDangerButton;
        renderer.drawRect(x, y, w, h, bg);
        int tx = (int)(x + (w - renderer.measureText(label)) / 2f);
        int ty = (int)(y + (h - renderer.lineHeight()) / 2f);
        renderer.drawText(tx, ty, label, colButtonText);
    }

    /**
     * Draws a text input field: border, background, text content, and optional caret.
     *
     * <p>The caret is a 2px-wide vertical rect drawn at the exact pixel position
     * after the character at {@code caretPos}. The blink cycle (caretVisible) is
     * managed by the screen — this method just draws or skips the caret rect.
     *
     * @param text         current string value of the field
     * @param caretPos     character index where the caret sits (0 = before first char)
     * @param focused      true if this field has keyboard focus
     * @param caretVisible true during the visible phase of the blink cycle
     */
    public void drawInputField(float x, float y, float w, float h,
                               String text, int caretPos,
                               boolean focused, boolean caretVisible) {
        int border = focused ? colInputBorderFocused : colInputBorder;
        // Outer border rect
        renderer.drawRect(x, y, w, h, border);
        // Inner background, inset by border thickness on all sides
        renderer.drawRect(x + INPUT_BORDER_PX, y + INPUT_BORDER_PX,
                          w - INPUT_BORDER_PX * 2, h - INPUT_BORDER_PX * 2,
                          colInputBg);
        float textX = x + INNER_PAD;
        float textY = y + (h - renderer.lineHeight()) / 2f;
        renderer.drawText(textX, textY, text, colInputText);

        if (focused && caretVisible) {
            // measureText on the substring gives exact pixel offset to caret position
            int caretX = (int)(textX + renderer.measureText(text.substring(0, caretPos)));
            renderer.drawRect(caretX, textY, CARET_W, renderer.lineHeight(), colInputCaret);
        }
    }

    /**
     * Draws a list item row: background (tinted by state) and left-aligned label.
     *
     * @param selected true if this row is the currently selected item
     * @param hovered  true if the cursor is over this row
     */
    public void drawListItem(float x, float y, float w, float h,
                             String label, boolean selected, boolean hovered) {
        int bg = selected ? colListItemSelected : hovered ? colListItemHovered : colListItem;
        renderer.drawRect(x, y, w, h, bg);
        renderer.drawText(x + INNER_PAD, y + (h - renderer.lineHeight()) / 2f, label, colText);
    }

    /**
     * Draws a screen title, centered horizontally around {@code centerX}.
     * Uses {@link #colTextTitle}.
     */
    public void drawTitle(float centerX, float y, String text) {
        renderer.drawCenteredText(centerX, y, text, colTextTitle);
    }

    /**
     * Draws a standard body label at the given position. Uses {@link #colText}.
     */
    public void drawLabel(float x, float y, String text) {
        renderer.drawText(x, y, text, colText);
    }

    /**
     * Draws a centered label. Uses {@link #colText}.
     */
    public void drawCenteredLabel(float centerX, float y, String text) {
        renderer.drawCenteredText(centerX, y, text, colText);
    }

    /**
     * Draws a dimmed secondary label. Uses {@link #colTextDim}.
     */
    public void drawDimLabel(float x, float y, String text) {
        renderer.drawText(x, y, text, colTextDim);
    }

    /**
     * Draws a warning or error label. Uses {@link #colTextWarn}.
     */
    public void drawWarnLabel(float x, float y, String text) {
        renderer.drawText(x, y, text, colTextWarn);
    }

    /**
     * Draws a semi-transparent full-screen tint for overlay screens.
     * Call before drawing the panel so the dim appears behind it.
     */
    public void drawOverlayDim(int screenW, int screenH) {
        renderer.drawRect(0, 0, screenW, screenH, colOverlayDim);
    }

    // ── Measurement forwarding ────────────────────────────────────────────────

    /**
     * Returns the rendered pixel width of {@code text}.
     * Forwards to {@link UiRenderer#measureText}.
     */
    public int measureText(String text) {
        return renderer.measureText(text);
    }

    /**
     * Returns the pixel height of one line of text.
     * Forwards to {@link UiRenderer#lineHeight}.
     */
    public int lineHeight() {
        return renderer.lineHeight();
    }

    /**
     * Returns the underlying renderer for any raw draw call not covered by the
     * theme helpers. Use sparingly — adding a helper to {@code UiTheme} is preferred
     * over bypassing it with raw renderer calls in screens.
     */
    public UiRenderer renderer() {
        return renderer;
    }
}