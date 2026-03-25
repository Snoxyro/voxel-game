package com.voxelgame.common.world;

import java.util.Map;

/**
 * Computes static light levels for a chunk before it is meshed.
 *
 * <h3>Two light channels</h3>
 * <ul>
 *   <li><b>Skylight</b> (high nibble, 0–15): how much sunlight reaches a block from
 *       above. 15 = full open sky, 0 = solid block or covered by terrain.</li>
 *   <li><b>Block light</b> (low nibble, 0–15): light emitted by the block itself.
 *       Seeded from {@link BlockType#getLightEmission()}. No propagation yet —
 *       each block carries its own emission; neighbours are unaffected until full
 *       BFS propagation is added in Phase 8.</li>
 * </ul>
 *
 * <h3>Skylight algorithm — column-only</h3>
 * For each X/Z column in the chunk, the engine scans from y=15 down to y=0:
 * <ol>
 *   <li>Determine whether the top of the column has sky access by checking whether
 *       the chunk directly above (cy+1) has any solid blocks in that same column.
 *       If no chunk exists above, the column starts with full skylight.</li>
 *   <li>While scanning down: air blocks pass skylight through (inherit current
 *       level), solid blocks absorb it (set to 0 and mark sky as blocked).</li>
 * </ol>
 *
 * <p>This is correct for open terrain and the majority of gameplay situations.
 * Light does not bend around corners or under overhangs — a proper BFS propagation
 * pass (Phase 8) will handle those cases. The data layout ({@link Chunk#setSkyLight})
 * is identical either way; only the propagation algorithm changes.
 *
 * <h3>Threading</h3>
 * Stateless — all state is in the {@link Chunk} being computed. Safe to call from
 * any worker thread as long as no other thread is concurrently writing to the same
 * chunk (which the mesh pipeline guarantees via the dirty-mark / in-progress sets).
 *
 * <h3>When to call</h3>
 * Call {@link #computeChunkLight} once per chunk, every time the chunk is dirty-marked
 * and re-submitted to the mesh executor. This ensures light stays consistent with block
 * data after placements and breaks.
 */
public final class LightEngine {

    private LightEngine() {} // static-only utility

    /**
     * Computes skylight and block light for the given chunk, writing results into
     * {@link Chunk}'s light nibble arrays. Existing light data is fully overwritten —
     * stale values from a previous computation are never carried over.
     *
     * <p>Must be called before {@link com.voxelgame.game.ChunkMesher#mesh} so that
     * the mesher reads up-to-date light values when baking vertex brightness.
     *
     * @param chunk     the chunk whose light data should be computed
     * @param pos       the chunk's position in the world grid
     * @param neighbors snapshot of all 26 adjacent chunks — the chunk directly above
     *                  (dy = +1) is required for correct skylight at the top of this chunk
     */
    public static void computeChunkLight(Chunk chunk, ChunkPos pos,
                                          Map<ChunkPos, Chunk> neighbors) {
        chunk.clearLight(); // start from zero — no stale values
        computeBlockLight(chunk);
        computeSkyLight(chunk, pos, neighbors);
    }

    // -------------------------------------------------------------------------
    // Block light — seed from BlockType emission, no propagation
    // -------------------------------------------------------------------------

    /**
     * Seeds block light from each block's inherent {@link BlockType#getLightEmission()}.
     * Non-emissive blocks (emission = 0) are skipped — their block-light nibble
     * remains 0 from the clearLight() call.
     *
     * <p>No propagation: the emission value is written only to the emissive block's
     * own position. Adjacent blocks are unaffected. Full BFS propagation in Phase 8
     * will spread these seed values outward.
     */
    private static void computeBlockLight(Chunk chunk) {
        if (chunk.isAllAir()) return; // fast-path: no blocks, no emission

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    int emission = chunk.getBlock(x, y, z).getLightEmission();
                    if (emission > 0) {
                        chunk.setBlockLight(x, y, z, emission);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Skylight — column-only top-down propagation
    // -------------------------------------------------------------------------

    /**
     * Fills the skylight channel for every block in the chunk via a column-only
     * top-down scan. Each of the 256 X/Z columns is processed independently.
     *
     * <p>The key question per column is: does skylight enter from the top of this chunk?
     * This is determined by inspecting the chunk directly above in the neighbor snapshot.
     */
    private static void computeSkyLight(Chunk chunk, ChunkPos pos,
                                         Map<ChunkPos, Chunk> neighbors) {
        // The chunk directly above this one in the vertical stack.
        // If absent (null), there is nothing above — sky is unobstructed.
        Chunk chunkAbove = neighbors.get(new ChunkPos(pos.x(), pos.y() + 1, pos.z()));

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                // Does this column receive unobstructed sunlight at the top of this chunk?
                boolean hasSky = columnReceivesSkyAtBottom(chunkAbove, x, z);

                // Scan top-to-bottom within this chunk
                for (int y = Chunk.SIZE - 1; y >= 0; y--) {
                    if (chunk.getBlock(x, y, z).isSolid()) {
                        // Solid block: absorbs all incoming skylight.
                        // Does not propagate downward — hasSky is blocked.
                        chunk.setSkyLight(x, y, z, 0);
                        hasSky = false;
                    } else {
                        // Air (or future transparent block): passes skylight through.
                        chunk.setSkyLight(x, y, z, hasSky ? Chunk.MAX_LIGHT : 0);
                    }
                }
            }
        }
    }

    /**
     * Returns {@code true} if a sky column reaches the bottom face of {@code chunkAbove},
     * meaning skylight can enter this chunk from the top in the given X/Z column.
     *
     * <p>If {@code chunkAbove} is {@code null}, there is no geometry above —
     * the column is open sky and the result is {@code true}.
     *
     * <p>If {@code chunkAbove} exists, the column is scanned from its top (y=15)
     * downward. If any solid block is found, sky is blocked before reaching the
     * bottom of that chunk, so the result is {@code false}.
     *
     * <h4>Limitation</h4>
     * Only the immediately adjacent chunk above is checked. A world taller than
     * two chunk heights (32 blocks) needs a recursive check up the column, which
     * is part of the full BFS propagation planned for Phase 8. Current terrain
     * height stays well within this range.
     *
     * @param chunkAbove the chunk at (cx, cy+1, cz), or {@code null} if absent
     * @param x          local X column coordinate [0, SIZE)
     * @param z          local Z column coordinate [0, SIZE)
     * @return {@code true} if sky reaches the bottom of the chunk above
     */
    private static boolean columnReceivesSkyAtBottom(Chunk chunkAbove, int x, int z) {
        if (chunkAbove == null) return true; // open sky — nothing blocking above

        // Scan top-to-bottom in the chunk above: any solid block means sky is blocked
        // before it exits the bottom of that chunk
        for (int y = Chunk.SIZE - 1; y >= 0; y--) {
            if (chunkAbove.getBlock(x, y, z).isSolid()) {
                return false;
            }
        }
        return true; // entire column above is air — sky passes through
    }
}