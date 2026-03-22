package com.voxelgame.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Manages the block texture array — a {@code GL_TEXTURE_2D_ARRAY} containing
 * one 16×16 RGBA tile per logical face type (grass top, grass side, dirt, stone).
 *
 * <p>Textures are generated procedurally at startup using deterministic per-pixel
 * noise, so no external image files are required. Every run produces identical
 * textures. Replace {@link #generateLayer} calls with STB image loads when proper
 * art assets are available — the rest of the system is unchanged.
 *
 * <h3>Why a texture array instead of an atlas?</h3>
 * With an atlas, tiling a texture across a greedy-merged quad requires UV math to
 * stay within the tile's sub-region — {@code GL_REPEAT} only wraps at the full
 * texture boundary, not within a tile. A {@code GL_TEXTURE_2D_ARRAY} gives each
 * tile its own layer with its own full [0,1] UV space. {@code GL_REPEAT} wraps
 * correctly, and merged quads tile for free by passing UV coordinates in tile
 * units (0→w, 0→h).
 *
 * <h3>Texture layers</h3>
 * The layer constants here are the single source of truth. {@link com.voxelgame.game.Block}
 * references them via {@code topTextureLayer()}, {@code sideTextureLayer()}, and
 * {@code bottomTextureLayer()}.
 */
public class TextureManager {

    /** Edge size of each tile in pixels. All tiles are square. */
    public static final int TILE_SIZE = 16;

    // Layer index constants — each maps to one 16×16 tile in the texture array.
    // Add new entries here when new block types are introduced.
    public static final int LAYER_GRASS_TOP  = 0;
    public static final int LAYER_GRASS_SIDE = 1;
    public static final int LAYER_DIRT       = 2;
    public static final int LAYER_STONE      = 3;

    private static final int LAYER_COUNT = 4;

    /** OpenGL handle to the GL_TEXTURE_2D_ARRAY. */
    private int textureArrayId;

    /**
     * Generates all tile textures and uploads them to a new {@code GL_TEXTURE_2D_ARRAY}.
     * Must be called on the main (OpenGL) thread after the GL context is created.
     */
    public void init() {
        textureArrayId = GL11.glGenTextures();
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArrayId);

        // Allocate array storage: TILE_SIZE × TILE_SIZE, LAYER_COUNT layers, RGBA8.
        // Null buffer = allocate without filling; each layer is uploaded separately below.
        GL12.glTexImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0, GL11.GL_RGBA8,
                TILE_SIZE, TILE_SIZE, LAYER_COUNT,
                0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);

        for (int layer = 0; layer < LAYER_COUNT; layer++) {
            ByteBuffer data = generateLayer(layer);
            GL12.glTexSubImage3D(GL30.GL_TEXTURE_2D_ARRAY, 0,
                    0, 0, layer,              // x, y, z offsets
                    TILE_SIZE, TILE_SIZE, 1,  // width, height, depth (one layer)
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, data);
            MemoryUtil.memFree(data);
        }

        // GL_REPEAT on S and T: greedy-merged quads pass UV in tile units (0→w, 0→h).
        // OpenGL wraps the coords automatically — each block face shows exactly one tile.
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

        // GL_NEAREST keeps the pixelated voxel look at all distances.
        // NEAREST_MIPMAP_NEAREST: sharp pixels, correct mip level = no shimmer at distance.
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
        GL11.glTexParameteri(GL30.GL_TEXTURE_2D_ARRAY, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

        GL30.glGenerateMipmap(GL30.GL_TEXTURE_2D_ARRAY);

        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, 0);
    }

    /**
     * Binds the texture array to texture unit 0.
     * Call this before issuing chunk draw calls each frame.
     */
    public void bind() {
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL30.GL_TEXTURE_2D_ARRAY, textureArrayId);
    }

    /**
     * Deletes the texture array from GPU memory. Call on shutdown.
     */
    public void cleanup() {
        GL11.glDeleteTextures(textureArrayId);
    }

    // -------------------------------------------------------------------------
    // Procedural tile generation
    // -------------------------------------------------------------------------

    /**
     * Generates one 16×16 RGBA tile as a direct {@code ByteBuffer}.
     * Caller must free the buffer after GPU upload.
     *
     * @param layer layer index ({@link #LAYER_GRASS_TOP} etc.)
     * @return 16 × 16 × 4 bytes, RGBA, ready for glTexSubImage3D
     */
    private ByteBuffer generateLayer(int layer) {
        ByteBuffer buf = MemoryUtil.memAlloc(TILE_SIZE * TILE_SIZE * 4);
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                int[] rgba = switch (layer) {
                    case LAYER_GRASS_TOP  -> grassTop(x, y);
                    case LAYER_GRASS_SIDE -> grassSide(x, y);
                    case LAYER_DIRT       -> dirt(x, y);
                    case LAYER_STONE      -> stone(x, y);
                    default               -> new int[]{ 255, 0, 255, 255 }; // magenta = missing
                };
                buf.put((byte) rgba[0]).put((byte) rgba[1])
                   .put((byte) rgba[2]).put((byte) rgba[3]);
            }
        }
        buf.flip();
        return buf;
    }

    /** Grass top — medium green with slight per-pixel noise. */
    private static int[] grassTop(int x, int y) {
        int n = noise(x, y, 0, 18);
        return new int[]{ clamp(90 + n), clamp(172 + n), clamp(55 + n / 2), 255 };
    }

    /**
     * Grass side — 3-pixel green strip at top, dirt body below, 1-pixel blended row
     * between them to avoid a hard seam.
     *
     * NOTE: OpenGL textures are stored bottom-up, so y=0 = bottom of the rendered face.
     * The green strip must be at high y values (y >= TILE_SIZE - 3) to appear at the top.
     */
    private static int[] grassSide(int x, int y) {
        int n = noise(x, y, 1, 15);
        if (y >= TILE_SIZE - 3) {
            // Green top strip
            return new int[]{ clamp(72 + n), clamp(148 + n), clamp(44 + n / 2), 255 };
        } else if (y == TILE_SIZE - 4) {
            // Transition pixel — blends green and brown
            return new int[]{ clamp(100 + n), clamp(110 + n), clamp(38 + n / 2), 255 };
        } else {
            return new int[]{ clamp(120 + n), clamp(80 + n), clamp(38 + n / 2), 255 };
        }
    }

    /** Dirt — warm brown with organic noise and occasional darker patches. */
    private static int[] dirt(int x, int y) {
        int n  = noise(x, y, 2, 20);
        int dk = (noise(x * 3, y * 3, 22, 1) > 0) ? -12 : 0;
        return new int[]{ clamp(145 + n + dk), clamp(100 + n + dk), clamp(50 + n / 2 + dk), 255 };
    }

    /**
     * Stone — mid-grey with subtle variation and faint "mortar" lines at tile edges,
     * giving a loose cobblestone feel.
     */
    private static int[] stone(int x, int y) {
        int n     = noise(x, y, 3, 15);
        boolean crack = (x == 7 || y == 7 || x == 15 || y == 15);
        int base  = crack ? 112 : 135;
        return new int[]{ clamp(base + n), clamp(base + n), clamp(base + n), 255 };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Deterministic per-pixel noise via integer hashing.
     * Returns a value in {@code [-range, range]} based solely on position and seed —
     * independent of iteration order, always reproducible.
     */
    private static int noise(int x, int y, int seed, int range) {
        int h = seed * 374761393 + x * 1000003 + y * 1000033;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= (h >>> 16);
        return (Math.abs(h) % (range * 2 + 1)) - range;
    }

    /** Clamps an integer to the valid byte range [0, 255]. */
    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}