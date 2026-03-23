package com.voxelgame.common.world;

/**
 * Texture array layer index constants — the single source of truth for which
 * layer in the GL_TEXTURE_2D_ARRAY corresponds to each block face type.
 *
 * <p>Kept in {@code common/world/} so that {@link BlockType} (also common) can
 * reference these constants without importing anything from {@code engine/}.
 * {@link com.voxelgame.engine.TextureManager} references these same constants so
 * the two stay in sync automatically — there is only one definition.
 *
 * <p>When adding a new texture, add a constant here first, then add a
 * corresponding {@code generateLayer} case in {@code TextureManager}.
 */
public final class TextureLayers {

    /** Grass top face — bright green. */
    public static final int LAYER_GRASS_TOP  = 0;
    /** Grass side face — thin green strip over dirt body. */
    public static final int LAYER_GRASS_SIDE = 1;
    /** Dirt — warm brown. */
    public static final int LAYER_DIRT       = 2;
    /** Stone — mid-grey with cobblestone mortar lines. */
    public static final int LAYER_STONE      = 3;

    /** Total number of registered texture layers. Must match {@code TextureManager.LAYER_COUNT}. */
    public static final int COUNT = 4;

    private TextureLayers() {} // static-only utility class
}