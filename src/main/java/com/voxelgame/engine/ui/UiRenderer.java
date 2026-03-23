package com.voxelgame.engine.ui;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL45.*;

/**
 * Batched 2D quad renderer for UI.
 *
 * <p>Quads are accumulated on the CPU and uploaded in one draw call per batch.
 * A batch is flushed when full, when the active texture changes, or at {@link #end()}.
 */
public final class UiRenderer {

    private static final int MAX_QUADS = 4096;
    private static final int VERTICES_PER_QUAD = 4;
    private static final int INDICES_PER_QUAD = 6;
    private static final int FLOATS_PER_VERTEX = 8;
    private static final int FLOATS_PER_QUAD = VERTICES_PER_QUAD * FLOATS_PER_VERTEX;
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    private static final int POS_OFFSET_BYTES = 0;
    private static final int UV_OFFSET_BYTES = 2 * Float.BYTES;
    private static final int COLOR_OFFSET_BYTES = 4 * Float.BYTES;

    private static final int WHITE_TEXTURE_SIZE = 1;

    private final UiShader shader;
    private final GlyphAtlas glyphAtlas;

    private final int whiteTexture;
    private final int vao;
    private final int vbo;
    private final int ebo;

    private final float[] vertices;
    private final FloatBuffer vertexBuffer;

    private int quadCount;
    private int activeTexture;

    /**
     * Creates VAO/VBO/EBO state, allocates CPU/GPU batch buffers, and builds a 1x1 white texture.
     *
     * @param shader UI shader wrapper used by this renderer
     * @param glyphAtlas glyph atlas used for text rendering
     */
    public UiRenderer(UiShader shader, GlyphAtlas glyphAtlas) {
        this.shader = shader;
        this.glyphAtlas = glyphAtlas;

        this.vertices = new float[MAX_QUADS * FLOATS_PER_QUAD];
        this.vertexBuffer = MemoryUtil.memAllocFloat(MAX_QUADS * FLOATS_PER_QUAD);

        this.vao = glGenVertexArrays();
        this.vbo = glGenBuffers();
        this.ebo = glGenBuffers();

        glBindVertexArray(vao);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) vertices.length * Float.BYTES, GL_DYNAMIC_DRAW);

        int[] indices = new int[MAX_QUADS * INDICES_PER_QUAD];
        for (int i = 0; i < MAX_QUADS; i++) {
            int v = i * VERTICES_PER_QUAD;
            int idx = i * INDICES_PER_QUAD;
            indices[idx] = v;
            indices[idx + 1] = v + 1;
            indices[idx + 2] = v + 2;
            indices[idx + 3] = v + 2;
            indices[idx + 4] = v + 3;
            indices[idx + 5] = v;
        }

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, STRIDE_BYTES, POS_OFFSET_BYTES);

        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, STRIDE_BYTES, UV_OFFSET_BYTES);

        glEnableVertexAttribArray(2);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, STRIDE_BYTES, COLOR_OFFSET_BYTES);

        glBindVertexArray(0);

        this.whiteTexture = createWhiteTexture();
        this.quadCount = 0;
        this.activeTexture = whiteTexture;
    }

    /**
     * Starts a UI frame and configures blend/depth state for 2D rendering.
     *
     * @param screenWidth framebuffer width in pixels
     * @param screenHeight framebuffer height in pixels
     */
    public void begin(int screenWidth, int screenHeight) {
        shader.bind();
        shader.setProjection(screenWidth, screenHeight);

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        quadCount = 0;
        activeTexture = whiteTexture;
    }

    /**
     * Ends a UI frame, uploads and draws any pending quads, and restores GL state.
     */
    public void end() {
        flush();

        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glDisable(GL_BLEND);

        shader.unbind();
    }

    /**
     * Draws a solid-colored rectangle.
     *
     * @param x left position in pixels
     * @param y top position in pixels
     * @param w width in pixels
     * @param h height in pixels
     * @param r red channel [0,1]
     * @param g green channel [0,1]
     * @param b blue channel [0,1]
     * @param a alpha channel [0,1]
     */
    public void drawRect(float x, float y, float w, float h, float r, float g, float b, float a) {
        switchTexture(whiteTexture);
        ensureCapacity();
        emitQuad(x, y, w, h, 0.0f, 0.0f, 1.0f, 1.0f, r, g, b, a);
    }

    /**
     * Draws ASCII text using the glyph atlas at the given top-left position.
     *
     * @param x left position in pixels
     * @param y top position in pixels
     * @param text text to render
     * @param r red channel [0,1]
     * @param g green channel [0,1]
     * @param b blue channel [0,1]
     * @param a alpha channel [0,1]
     */
    public void drawText(float x, float y, String text, float r, float g, float b, float a) {
        switchTexture(glyphAtlas.textureId());

        float cursorX = x;
        float glyphWidth = GlyphAtlas.CELL_W;
        float glyphHeight = GlyphAtlas.CELL_H;

        for (int i = 0; i < text.length(); i++) {
            switchTexture(glyphAtlas.textureId());
            ensureCapacity();

            char c = text.charAt(i);
            float uMin = glyphAtlas.uMin(c);
            float vMin = glyphAtlas.vMin(c);
            float uMax = glyphAtlas.uMax(c);
            float vMax = glyphAtlas.vMax(c);

            emitQuad(cursorX, y, glyphWidth, glyphHeight, uMin, vMin, uMax, vMax, r, g, b, a);
            cursorX += glyphAtlas.charWidth(c);
        }
    }

    /**
     * Frees all GL resources used by the renderer and releases direct buffers.
     */
    public void cleanup() {
        glDeleteVertexArrays(vao);
        glDeleteBuffers(vbo);
        glDeleteBuffers(ebo);
        glDeleteTextures(whiteTexture);
        MemoryUtil.memFree(vertexBuffer);
        glyphAtlas.cleanup();
    }

    private int createWhiteTexture() {
        int texture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texture);

        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        pixel.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF);
        pixel.flip();

        glTexImage2D(
            GL_TEXTURE_2D,
            0,
            GL_RGBA,
            WHITE_TEXTURE_SIZE,
            WHITE_TEXTURE_SIZE,
            0,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            pixel
        );
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(pixel);

        return texture;
    }

    private void flush() {
        if (quadCount == 0) {
            return;
        }

        int floatCount = quadCount * FLOATS_PER_QUAD;
        vertexBuffer.put(vertices, 0, floatCount);
        vertexBuffer.flip();

        glBindVertexArray(vao);
        glBindTextureUnit(0, activeTexture);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);

        glDrawElements(GL_TRIANGLES, quadCount * INDICES_PER_QUAD, GL_UNSIGNED_INT, 0);

        glBindVertexArray(0);

        quadCount = 0;
        vertexBuffer.clear();
    }

    private void switchTexture(int textureId) {
        if (textureId != activeTexture) {
            flush();
            activeTexture = textureId;
        }
    }

    private void ensureCapacity() {
        if (quadCount >= MAX_QUADS) {
            flush();
        }
    }

    private void emitQuad(
        float x,
        float y,
        float w,
        float h,
        float u0,
        float v0,
        float u1,
        float v1,
        float r,
        float g,
        float b,
        float a
    ) {
        int base = quadCount * FLOATS_PER_QUAD;

        putVertex(base, x, y, u0, v0, r, g, b, a);
        putVertex(base + FLOATS_PER_VERTEX, x + w, y, u1, v0, r, g, b, a);
        putVertex(base + FLOATS_PER_VERTEX * 2, x + w, y + h, u1, v1, r, g, b, a);
        putVertex(base + FLOATS_PER_VERTEX * 3, x, y + h, u0, v1, r, g, b, a);

        quadCount++;
    }

    private void putVertex(int offset, float x, float y, float u, float v, float r, float g, float b, float a) {
        vertices[offset] = x;
        vertices[offset + 1] = y;
        vertices[offset + 2] = u;
        vertices[offset + 3] = v;
        vertices[offset + 4] = r;
        vertices[offset + 5] = g;
        vertices[offset + 6] = b;
        vertices[offset + 7] = a;
    }
}
