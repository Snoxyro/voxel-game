package com.voxelgame.common.world;

/**
 * The result of stepping a ray through the voxel world.
 *
 * <p>A hit carries the integer world-space coordinates of the struck block
 * and the face normal of the face the ray entered. A miss carries no
 * meaningful coordinate data.
 *
 * <p>The face normal is one of the six cardinal unit vectors — for example,
 * faceN = (0, 1, 0) means the ray entered through the bottom face of the block
 * (came from below). Adding faceN to the hit block position gives the placement
 * position for a new block adjacent to the struck face.
 */
public record RaycastResult(
        boolean hit,
        int blockX, int blockY, int blockZ,
        int faceNX, int faceNY, int faceNZ
) {
    /** Convenience factory for a miss — no block was struck. */
    public static RaycastResult miss() {
        return new RaycastResult(false, 0, 0, 0, 0, 0, 0);
    }

    /**
     * World-space X of the block adjacent to the struck face.
     * This is where a new block should be placed on right-click.
     */
    public int placeX() { return blockX + faceNX; }

    /** @see #placeX() */
    public int placeY() { return blockY + faceNY; }

    /** @see #placeX() */
    public int placeZ() { return blockZ + faceNZ; }
}