package com.voxelgame.common.world;

import org.joml.Vector3f;

/**
 * Steps a ray through the voxel world using the DDA (Digital Differential
 * Analyser) algorithm to find the first solid block the ray passes through.
 *
 * <h3>How DDA works</h3>
 * For each axis (X, Y, Z) we precompute {@code tDelta} — how far along the ray
 * (measured in units of ray length) it takes to cross one full voxel in that
 * direction. Then, for each step, we advance to whichever axis boundary is
 * nearest ({@code min(tMaxX, tMaxY, tMaxZ)}), move into the new voxel, and
 * check if it is solid.
 *
 * <p>Recording which axis we just crossed gives us the face normal for free:
 * if we crossed the X boundary stepping in +X, we entered through the -X face
 * (face normal = (-1, 0, 0)).
 */
public class RayCaster {

    /**
     * Casts a ray and returns the first non-air block hit within
     * {@code maxDistance} blocks.
     *
     * @param origin      ray start position (typically the camera position)
     * @param direction   normalised look direction
     * @param world       the world to query for block solidity
     * @param maxDistance maximum ray length in blocks
     * @return a hit result, or {@link RaycastResult#miss()} if nothing was found
     */
    public static RaycastResult cast(Vector3f origin, Vector3f direction, BlockView world, float maxDistance){

        // Starting voxel — Math.floor handles negative coords correctly,
        // unlike a plain (int) cast which truncates toward zero.
        int x = (int) Math.floor(origin.x);
        int y = (int) Math.floor(origin.y);
        int z = (int) Math.floor(origin.z);

        // Which grid direction we step on each axis (+1 or -1)
        int stepX = direction.x >= 0 ? 1 : -1;
        int stepY = direction.y >= 0 ? 1 : -1;
        int stepZ = direction.z >= 0 ? 1 : -1;

        // How far along the ray it takes to cross one voxel on each axis.
        // Zero direction components never produce a crossing — use MAX_VALUE.
        float tDeltaX = direction.x == 0 ? Float.MAX_VALUE : Math.abs(1.0f / direction.x);
        float tDeltaY = direction.y == 0 ? Float.MAX_VALUE : Math.abs(1.0f / direction.y);
        float tDeltaZ = direction.z == 0 ? Float.MAX_VALUE : Math.abs(1.0f / direction.z);

        // Distance to the *first* boundary crossing on each axis.
        // Stepping +X: next boundary is at floor(x)+1. Stepping -X: at floor(x).
        float tMaxX = direction.x == 0 ? Float.MAX_VALUE
                : (direction.x > 0 ? (x + 1 - origin.x) : (origin.x - x)) * tDeltaX;
        float tMaxY = direction.y == 0 ? Float.MAX_VALUE
                : (direction.y > 0 ? (y + 1 - origin.y) : (origin.y - y)) * tDeltaY;
        float tMaxZ = direction.z == 0 ? Float.MAX_VALUE
                : (direction.z > 0 ? (z + 1 - origin.z) : (origin.z - z)) * tDeltaZ;

        // Face normal of the last boundary crossed — the face the ray entered.
        // Updated each step; used to determine block placement position.
        int faceNX = 0, faceNY = 0, faceNZ = 0;

        while (true) {
            // If the nearest remaining boundary is beyond maxDistance, give up
            float nearestT = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (nearestT > maxDistance) {
                return RaycastResult.miss();
            }

            // Advance to the nearest boundary and record which face we entered
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x      += stepX;
                faceNX  = -stepX; faceNY = 0; faceNZ = 0;
                tMaxX  += tDeltaX;
            } else if (tMaxY < tMaxZ) {
                y      += stepY;
                faceNX  = 0; faceNY = -stepY; faceNZ = 0;
                tMaxY  += tDeltaY;
            } else {
                z      += stepZ;
                faceNX  = 0; faceNY = 0; faceNZ = -stepZ;
                tMaxZ  += tDeltaZ;
            }

            // Check the voxel we just entered
            if (world.getBlock(x, y, z) != Block.AIR) {
                return new RaycastResult(true, x, y, z, faceNX, faceNY, faceNZ);
            }
        }
    }
}