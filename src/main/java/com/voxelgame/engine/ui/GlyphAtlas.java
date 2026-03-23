package com.voxelgame.engine.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11.*;

/**
 * Bakes all printable ASCII characters (codes 32–126) into a single OpenGL
 * texture at startup using Java's AWT font renderer.
 *
 * <p>Layout: characters are arranged in a 16-column grid, each cell
 * {@value #CELL_W}×{@value #CELL_H} pixels. The texture is RGBA so glyph
 * transparency is preserved — AWT renders each character white-on-transparent,
 * and {@link UiRenderer} tints it to whatever color is requested.
 *
 * <p>Swapping this for a pixel-art TTF later requires only changing
 * {@link #buildAtlas()} — all UV math and the renderer are untouched.
 */
public final class GlyphAtlas {

    /** First and last printable ASCII codepoints we bake. */
    private static final int FIRST_CHAR = 32;  // space
    private static final int LAST_CHAR  = 126; // tilde ~
    private static final int CHAR_COUNT = LAST_CHAR - FIRST_CHAR + 1;

    /** Pixel dimensions of each glyph cell in the atlas. */
    public static final int CELL_W = 16;
    public static final int CELL_H = 16;

    /** Number of columns in the atlas grid. */
    private static final int COLS = 16;
    private static final int ROWS = (CHAR_COUNT + COLS - 1) / COLS;

    private final int   textureId;
    private final int   atlasW;
    private final int   atlasH;

    /** Advance width (pixels) for each character, used by measureText(). */
    private final int[] charWidths = new int[CHAR_COUNT];

    /** UV bounds for each character in normalised [0,1] atlas space. */
    private final float[] uMin = new float[CHAR_COUNT];
    private final float[] uMax = new float[CHAR_COUNT];
    private final float[] vMin = new float[CHAR_COUNT];
    private final float[] vMax = new float[CHAR_COUNT];

    /**
     * Builds the glyph atlas and uploads it to the GPU.
     * Must be called on the main (GL) thread.
     */
    public GlyphAtlas() {
        atlasW    = COLS * CELL_W;
        atlasH    = ROWS * CELL_H;
        textureId = buildAtlas();
    }

    // -------------------------------------------------------------------------
    // Internal atlas construction
    // -------------------------------------------------------------------------

    private int buildAtlas() {
        // Step 1 — render all glyphs into a BufferedImage using AWT.
        // TYPE_INT_ARGB gives us a 32-bit ARGB pixel per slot, transparency works.
        BufferedImage img = new BufferedImage(atlasW, atlasH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D    g   = img.createGraphics();

        // Disable anti-aliasing for sharp, pixel-perfect glyphs.
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        // Monospaced ensures a consistent advance width — looks clean in a block game.
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.setColor(Color.WHITE); // renderer tints via vertex color; always bake white

        FontMetrics fm       = g.getFontMetrics();
        int         baseline = fm.getAscent(); // pixels from cell-top to text baseline

        for (int i = 0; i < CHAR_COUNT; i++) {
            char c   = (char) (FIRST_CHAR + i);
            int  col = i % COLS;
            int  row = i / COLS;
            int  cx  = col * CELL_W;
            int  cy  = row * CELL_H;

            g.drawString(String.valueOf(c), cx, cy + baseline);
            charWidths[i] = fm.charWidth(c);

            // Normalise cell bounds to [0,1] UV space.
            uMin[i] = (float) cx          / atlasW;
            uMax[i] = (float)(cx + CELL_W) / atlasW;
            vMin[i] = (float) cy          / atlasH;
            vMax[i] = (float)(cy + CELL_H) / atlasH;
        }
        g.dispose();

        // Step 2 — convert ARGB int[] → RGBA ByteBuffer.
        // OpenGL expects RGBA byte order; Java's ARGB packs channels differently.
        int[]      pixels = new int[atlasW * atlasH];
        img.getRGB(0, 0, atlasW, atlasH, pixels, 0, atlasW);

        ByteBuffer buf = MemoryUtil.memAlloc(atlasW * atlasH * 4);
        for (int px : pixels) {
            buf.put((byte)((px >> 16) & 0xFF)); // R  (bits 16-23 in ARGB)
            buf.put((byte)((px >>  8) & 0xFF)); // G  (bits  8-15)
            buf.put((byte)( px        & 0xFF)); // B  (bits  0- 7)
            buf.put((byte)((px >> 24) & 0xFF)); // A  (bits 24-31)
        }
        buf.flip();

        // Step 3 — upload to GPU as a plain GL_TEXTURE_2D.
        // GL_NEAREST keeps glyphs sharp (no bilinear blur on pixel fonts).
        int id = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, id);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, atlasW, atlasH,
                     0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);

        MemoryUtil.memFree(buf); // CPU buffer no longer needed after upload
        return id;
    }

    // -------------------------------------------------------------------------
    // Public accessors used by UiRenderer
    // -------------------------------------------------------------------------

    /**
     * Returns the advance width (pixels) for the given character.
     * Unmapped characters (outside ASCII 32–126) return a safe default.
     */
    public int charWidth(char c) {
        int i = c - FIRST_CHAR;
        return (i >= 0 && i < CHAR_COUNT) ? charWidths[i] : CELL_W / 2;
    }

    /** Returns the total pixel width of a string as rendered by this atlas. */
    public int measureText(String text) {
        int w = 0;
        for (int i = 0; i < text.length(); i++) w += charWidth(text.charAt(i));
        return w;
    }

    /** Returns the pixel height of a single line of text. */
    public int lineHeight() { return CELL_H; }

    /** UV accessors — clamp out-of-range characters to the first slot. */
    public float uMin(char c) { return uMin[idx(c)]; }
    public float uMax(char c) { return uMax[idx(c)]; }
    public float vMin(char c) { return vMin[idx(c)]; }
    public float vMax(char c) { return vMax[idx(c)]; }

    /** OpenGL texture ID for binding before a text draw. */
    public int textureId() { return textureId; }

    public void cleanup() { glDeleteTextures(textureId); }

    // -------------------------------------------------------------------------

    private int idx(char c) {
        int i = c - FIRST_CHAR;
        return Math.max(0, Math.min(CHAR_COUNT - 1, i));
    }
}