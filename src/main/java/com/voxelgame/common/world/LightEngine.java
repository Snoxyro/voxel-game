package com.voxelgame.common.world;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stateless lighting utility with BFS propagation for sky and block light.
 *
 * <p>The engine uses a 3x3x3 local chunk snapshot for cross-boundary reads and
 * tracks touched chunk positions so callers can rebuild only affected meshes.</p>
 */
public final class LightEngine {

    /** Utility class; not instantiable. */
    private LightEngine() {}

    private static final int[] DIR_X = {  0,  0, -1,  1,  0,  0 };
    private static final int[] DIR_Y = {  1, -1,  0,  0,  0,  0 };
    private static final int[] DIR_Z = {  0,  0,  0,  0, -1,  1 };
    private static final int UP   = 0;
    private static final int DOWN = 1;

    private static final class PrimitiveQueue {
        private int[] data = new int[1024 * 64]; 
        private int head = 0;
        private int tail = 0;

        // Stores one BFS node as four packed ints in sequence: x, y, z, lightLevel.
        void add(int x, int y, int z, int l) {
            if (tail + 4 > data.length) {
                int size = tail - head;
                // Only double the array if we are ACTUALLY out of space. 
                // Otherwise, just slide the data back to the start of the array to reclaim memory.
                if (size + 4 > data.length) {
                    int[] nd = new int[data.length * 2];
                    System.arraycopy(data, head, nd, 0, size);
                    data = nd;
                } else {
                    System.arraycopy(data, head, data, 0, size);
                }
                tail = size;
                head = 0;
            }
            data[tail++] = x; data[tail++] = y; data[tail++] = z; data[tail++] = l;
        }

        // Empty when read and write cursors meet.
        boolean isEmpty() { return head == tail; }
        // Poll in the same x/y/z/l order used by add(...).
        int pollX() { return data[head++]; }
        int pollY() { return data[head++]; }
        int pollZ() { return data[head++]; }
        int pollL() { return data[head++]; }
        // Reuse the same backing array to avoid allocations between BFS runs.
        void clear() { head = tail = 0; }
    }

    // Gives every worker thread its own private queue array, eliminating GC allocation AND thread crashes
    private static final ThreadLocal<PrimitiveQueue> SKY_QUEUE = ThreadLocal.withInitial(PrimitiveQueue::new);
    private static final ThreadLocal<PrimitiveQueue> BLOCK_QUEUE = ThreadLocal.withInitial(PrimitiveQueue::new);
    private static final ThreadLocal<PrimitiveQueue> REMOVE_QUEUE = ThreadLocal.withInitial(PrimitiveQueue::new);
    private static final ThreadLocal<PrimitiveQueue> READD_QUEUE = ThreadLocal.withInitial(PrimitiveQueue::new);

    // -------------------------------------------------------------------------
    // Zero-allocation flat array lookup system
    // -------------------------------------------------------------------------
    
    /**
     * Builds a 3x3x3 chunk window centered on {@code center} for boundary-safe neighbor reads.
     */
    private static Chunk[] buildLocalGrid(Map<ChunkPos, Chunk> chunks, ChunkPos center) {
        Chunk[] grid = new Chunk[27];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    grid[(dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)] = chunks.get(new ChunkPos(center.x() + dx, center.y() + dy, center.z() + dz));
                }
            }
        }
        return grid;
    }

    /**
     * Resolves a world-space block coordinate to the corresponding chunk in the local 3x3x3 grid.
     */
    private static Chunk getChunkFromGrid(Chunk[] grid, ChunkPos center, int wx, int wy, int wz) {
        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cy = Math.floorDiv(wy, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        int dx = cx - center.x();
        int dy = cy - center.y();
        int dz = cz - center.z();
        if (dx < -1 || dx > 1 || dy < -1 || dy > 1 || dz < -1 || dz > 1) return null;
        return grid[(dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)];
    }

    // -------------------------------------------------------------------------

    /**
     * Recomputes sky and block light for one chunk from authoritative neighbor state.
     *
     * @param chunks loaded chunks keyed by chunk position
     * @param pos chunk position to relight
     * @return chunk positions whose packed light bytes were modified
     * @thread worker
     */
    public static Set<ChunkPos> initChunkLight(Map<ChunkPos, Chunk> chunks, ChunkPos pos) {
        Chunk chunk = chunks.get(pos);
        if (chunk == null) return Collections.emptySet();

        Chunk[] grid = buildLocalGrid(chunks, pos);
        chunk.clearLight();

        PrimitiveQueue skyQueue = SKY_QUEUE.get();
        PrimitiveQueue blockQueue = BLOCK_QUEUE.get();
        skyQueue.clear(); blockQueue.clear();

        Set<ChunkPos> changed = new HashSet<>();
        changed.add(pos);

        int baseX = pos.x() * Chunk.SIZE;
        int baseY = pos.y() * Chunk.SIZE;
        int baseZ = pos.z() * Chunk.SIZE;

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int lz = 0; lz < Chunk.SIZE; lz++) {
                if (!columnReceivesSky(grid, lx, lz)) continue;
                int topLy = Chunk.SIZE - 1;
                if (!chunk.getBlock(lx, topLy, lz).isOpaque()) {
                    int wx = baseX + lx, wy = baseY + topLy, wz = baseZ + lz;
                    setSkyAt(grid, pos, wx, wy, wz, Chunk.MAX_LIGHT, changed);
                    // Seed BFS with direct-sunlight columns at local top surface.
                    skyQueue.add(wx, wy, wz, Chunk.MAX_LIGHT);
                }
            }
        }

        for (int d = 0; d < 6; d++) {
            Chunk neighbor = grid[(DIR_X[d] + 1) * 9 + (DIR_Y[d] + 1) * 3 + (DIR_Z[d] + 1)];
            if (neighbor == null) continue;

            for (int fa = 0; fa < Chunk.SIZE; fa++) {
                for (int fb = 0; fb < Chunk.SIZE; fb++) {
                    int lx, ly, lz, nlx, nly, nlz;
                    if (DIR_X[d] != 0) {
                        lx  = (DIR_X[d] > 0) ? Chunk.SIZE - 1 : 0; nlx = (DIR_X[d] > 0) ? 0 : Chunk.SIZE - 1;
                        ly = nly = fa; lz = nlz = fb;
                    } else if (DIR_Y[d] != 0) {
                        ly  = (DIR_Y[d] > 0) ? Chunk.SIZE - 1 : 0; nly = (DIR_Y[d] > 0) ? 0 : Chunk.SIZE - 1;
                        lx = nlx = fa; lz = nlz = fb;
                    } else {
                        lz  = (DIR_Z[d] > 0) ? Chunk.SIZE - 1 : 0; nlz = (DIR_Z[d] > 0) ? 0 : Chunk.SIZE - 1;
                        lx = nlx = fa; ly = nly = fb;
                    }

                    if (chunk.getBlock(lx, ly, lz).isOpaque()) continue;

                    int wx = baseX + lx, wy = baseY + ly, wz = baseZ + lz;
                    int nSky = neighbor.getSkyLight(nlx, nly, nlz);

                    if (nSky > 0) {
                        // Sunlight-column rule: full sunlight moving downward does not decay.
                        int propagated = (d == UP && nSky == Chunk.MAX_LIGHT) ? Chunk.MAX_LIGHT : nSky - 1;
                        if (propagated > 0 && propagated > chunk.getSkyLight(lx, ly, lz)) {
                            setSkyAt(grid, pos, wx, wy, wz, propagated, changed);
                            // Re-enqueue only when we improved this voxel's light value.
                            skyQueue.add(wx, wy, wz, propagated);
                        }
                    }

                    int nBlock = neighbor.getBlockLight(nlx, nly, nlz);
                    if (nBlock > 1 && (nBlock - 1) > chunk.getBlockLight(lx, ly, lz)) {
                        setBlockLightAt(grid, pos, wx, wy, wz, nBlock - 1, changed);
                        // Block light decays by one per step, so queue the decayed level.
                        blockQueue.add(wx, wy, wz, nBlock - 1);
                    }
                }
            }
        }

        for (int lx = 0; lx < Chunk.SIZE; lx++) {
            for (int ly = 0; ly < Chunk.SIZE; ly++) {
                for (int lz = 0; lz < Chunk.SIZE; lz++) {
                    int emission = chunk.getBlock(lx, ly, lz).getLightEmission();
                    if (emission <= 0) continue;

                    chunk.setBlockLight(lx, ly, lz, emission); 

                    int wx = baseX + lx, wy = baseY + ly, wz = baseZ + lz;
                    for (int d = 0; d < 6; d++) {
                        int nx = wx + DIR_X[d], ny = wy + DIR_Y[d], nz = wz + DIR_Z[d];
                        BlockType nb = getBlockAt(grid, pos, nx, ny, nz);
                        if (nb == null || nb.isOpaque()) continue;

                        if (emission - 1 > getBlockLightAt(grid, pos, nx, ny, nz)) {
                            setBlockLightAt(grid, pos, nx, ny, nz, emission - 1, changed);
                            // Seed outward BFS from emissive sources.
                            blockQueue.add(nx, ny, nz, emission - 1);
                        }
                    }
                }
            }
        }

        bfsSkyAdd(grid, pos, skyQueue, changed);
        bfsBlockAdd(grid, pos, blockQueue, changed);

        return changed;
    }

    /**
     * Repairs local lighting after an opaque block is removed.
     *
     * @param chunks loaded chunks keyed by chunk position
     * @param wx world-space block X that became non-opaque
     * @param wy world-space block Y that became non-opaque
     * @param wz world-space block Z that became non-opaque
     * @return chunk positions whose packed light bytes were modified
     * @thread GL-main
        * @gl-state n/a
     */
    public static Set<ChunkPos> propagateAfterBreak(Map<ChunkPos, Chunk> chunks, int wx, int wy, int wz) {
        ChunkPos center = Chunk.worldToChunkPos(wx, wy, wz);
        Set<ChunkPos> changed = new HashSet<>();
        changed.add(center);
        
        Chunk[] grid = buildLocalGrid(chunks, center);
        PrimitiveQueue skyQueue = SKY_QUEUE.get();
        PrimitiveQueue blockQueue = BLOCK_QUEUE.get();
        skyQueue.clear(); blockQueue.clear();

        int maxIncomingSky   = 0;
        int maxIncomingBlock = 0;

        for (int d = 0; d < 6; d++) {
            int nx = wx + DIR_X[d], ny = wy + DIR_Y[d], nz = wz + DIR_Z[d];

            int nSky = getSkyAt(grid, center, nx, ny, nz);
            if (nSky > 0) {
                // Preserve max sunlight in open vertical shafts.
                int incoming = (d == UP && nSky == Chunk.MAX_LIGHT) ? Chunk.MAX_LIGHT : nSky - 1;
                if (incoming > maxIncomingSky) maxIncomingSky = incoming;
            }

            int nBlock = getBlockLightAt(grid, center, nx, ny, nz);
            if (nBlock > 1 && nBlock - 1 > maxIncomingBlock) {
                maxIncomingBlock = nBlock - 1;
            }
        }

        if (maxIncomingSky > 0) {
            setSkyAt(grid, center, wx, wy, wz, maxIncomingSky, changed);
            // Re-expand from the changed voxel to fill newly reachable air.
            skyQueue.add(wx, wy, wz, maxIncomingSky);
        }
        if (maxIncomingBlock > 0) {
            setBlockLightAt(grid, center, wx, wy, wz, maxIncomingBlock, changed);
            // Same idea for block light after removing an occluder.
            blockQueue.add(wx, wy, wz, maxIncomingBlock);
        }

        bfsSkyAdd(grid, center, skyQueue, changed);
        bfsBlockAdd(grid, center, blockQueue, changed);

        return changed;
    }

    /**
     * Repairs local lighting after placing an opaque block.
     *
     * @param chunks loaded chunks keyed by chunk position
     * @param wx world-space block X that became opaque
     * @param wy world-space block Y that became opaque
     * @param wz world-space block Z that became opaque
     * @return chunk positions whose packed light bytes were modified
     * @thread GL-main
        * @gl-state n/a
     */
    public static Set<ChunkPos> propagateAfterPlace(Map<ChunkPos, Chunk> chunks, int wx, int wy, int wz) {
        ChunkPos center = Chunk.worldToChunkPos(wx, wy, wz);
        Set<ChunkPos> changed = new HashSet<>();
        changed.add(center);
        
        Chunk[] grid = buildLocalGrid(chunks, center);
        
        PrimitiveQueue removeQueue = REMOVE_QUEUE.get();
        PrimitiveQueue readdQueue = READD_QUEUE.get();
        PrimitiveQueue blockQueue = BLOCK_QUEUE.get();
        removeQueue.clear(); readdQueue.clear();

        int oldSky   = getSkyAt(grid, center, wx, wy, wz);
        int oldBlock = getBlockLightAt(grid, center, wx, wy, wz);

        // The placed block itself blocks both channels at its voxel.
        setSkyAt(grid, center, wx, wy, wz, 0, changed);
        setBlockLightAt(grid, center, wx, wy, wz, 0, changed);

        if (oldSky > 0) {
            removeQueue.add(wx, wy, wz, oldSky);
            // First remove invalid light, then re-add surviving light from unaffected fronts.
            bfsSkyRemove(grid, center, removeQueue, readdQueue, changed);
            bfsSkyAdd(grid, center, readdQueue, changed);
        }

        if (oldBlock > 0) {
            removeQueue.clear(); readdQueue.clear();
            removeQueue.add(wx, wy, wz, oldBlock);
            // Same two-phase repair for block light.
            bfsBlockRemove(grid, center, removeQueue, readdQueue, changed);
            bfsBlockAdd(grid, center, readdQueue, changed);
        }

        BlockType placed = getBlockAt(grid, center, wx, wy, wz);
        if (placed != null && placed.getLightEmission() > 0) {
            blockQueue.clear();
            int emission = placed.getLightEmission();
            setBlockLightAt(grid, center, wx, wy, wz, emission, changed);

            for (int d = 0; d < 6; d++) {
                int nx = wx + DIR_X[d], ny = wy + DIR_Y[d], nz = wz + DIR_Z[d];
                BlockType nb = getBlockAt(grid, center, nx, ny, nz);
                if (nb == null || nb.isOpaque()) continue;

                if (emission - 1 > getBlockLightAt(grid, center, nx, ny, nz)) {
                    setBlockLightAt(grid, center, nx, ny, nz, emission - 1, changed);
                    // Re-seed local BFS if the placed block emits light.
                    blockQueue.add(nx, ny, nz, emission - 1);
                }
            }
            bfsBlockAdd(grid, center, blockQueue, changed);
        }

        return changed;
    }

    /** Flood-fills skylight additions from a queue of candidate voxels. */
    private static void bfsSkyAdd(Chunk[] grid, ChunkPos center, PrimitiveQueue queue, Set<ChunkPos> changed) {
        while (!queue.isEmpty()) {
            // Poll one BFS node in x/y/z/lightLevel order.
            int wx = queue.pollX(), wy = queue.pollY(), wz = queue.pollZ(), level = queue.pollL();
            if (level <= 1) continue;

            for (int d = 0; d < 6; d++) {
                int nx = wx + DIR_X[d], ny = wy + DIR_Y[d], nz = wz + DIR_Z[d];
                BlockType nb = getBlockAt(grid, center, nx, ny, nz);
                if (nb == null || nb.isOpaque()) continue;

                // Sunlight-column rule: full sunlight propagating straight down remains 15.
                int newLevel = (d == DOWN && level == Chunk.MAX_LIGHT) ? Chunk.MAX_LIGHT : level - 1;
                if (newLevel > getSkyAt(grid, center, nx, ny, nz)) {
                    setSkyAt(grid, center, nx, ny, nz, newLevel, changed);
                    // Only enqueue when we actually brightened a voxel.
                    queue.add(nx, ny, nz, newLevel);
                }
            }
        }
    }

    /** Removes now-invalid skylight and queues edges that must be re-added. */
    private static void bfsSkyRemove(Chunk[] grid, ChunkPos center, PrimitiveQueue removeQueue, PrimitiveQueue readdQueue, Set<ChunkPos> changed) {
        while (!removeQueue.isEmpty()) {
            int wx = removeQueue.pollX(), wy = removeQueue.pollY(), wz = removeQueue.pollZ(), removedLevel = removeQueue.pollL();

            for (int d = 0; d < 6; d++) {
                int nx = wx + DIR_X[d], ny = wy + DIR_Y[d], nz = wz + DIR_Z[d];
                BlockType nb = getBlockAt(grid, center, nx, ny, nz);
                if (nb == null || nb.isOpaque()) continue;

                int nSky = getSkyAt(grid, center, nx, ny, nz);
                if (nSky == 0) continue;

                // Direct-sunlight shafts are treated specially during downward removal.
                boolean freeDown = (d == DOWN && removedLevel == Chunk.MAX_LIGHT);

                if (freeDown && nSky == Chunk.MAX_LIGHT) {
                    setSkyAt(grid, center, nx, ny, nz, 0, changed);
                    removeQueue.add(nx, ny, nz, Chunk.MAX_LIGHT);
                } else if (!freeDown && nSky < removedLevel) {
                    setSkyAt(grid, center, nx, ny, nz, 0, changed);
                    removeQueue.add(nx, ny, nz, nSky);
                } else if (nSky >= removedLevel) {
                    // Border light that might still be valid is reconsidered by the add pass.
                    readdQueue.add(nx, ny, nz, nSky);
                }
            }
        }
    }

    /** Flood-fills block-light additions from a queue of candidate voxels. */
    private static void bfsBlockAdd(Chunk[] grid, ChunkPos center, PrimitiveQueue queue, Set<ChunkPos> changed) {
        while (!queue.isEmpty()) {
            int wx = queue.pollX(), wy = queue.pollY(), wz = queue.pollZ(), level = queue.pollL();
            if (level <= 1) continue;

            for (int d = 0; d < 6; d++) {
                int nx = wx + DIR_X[d], ny = wy + DIR_Y[d], nz = wz + DIR_Z[d];
                BlockType nb = getBlockAt(grid, center, nx, ny, nz);
                if (nb == null || nb.isOpaque()) continue;

                int newLevel = level - 1;
                if (newLevel > getBlockLightAt(grid, center, nx, ny, nz)) {
                    setBlockLightAt(grid, center, nx, ny, nz, newLevel, changed);
                    // Continue propagation frontier from newly brightened voxel.
                    queue.add(nx, ny, nz, newLevel);
                }
            }
        }
    }

    /** Removes now-invalid block light and queues border light to be re-added. */
    private static void bfsBlockRemove(Chunk[] grid, ChunkPos center, PrimitiveQueue removeQueue, PrimitiveQueue readdQueue, Set<ChunkPos> changed) {
        while (!removeQueue.isEmpty()) {
            int wx = removeQueue.pollX(), wy = removeQueue.pollY(), wz = removeQueue.pollZ(), removedLevel = removeQueue.pollL();

            for (int d = 0; d < 6; d++) {
                int nx = wx + DIR_X[d], ny = wy + DIR_Y[d], nz = wz + DIR_Z[d];
                BlockType nb = getBlockAt(grid, center, nx, ny, nz);
                if (nb == null || nb.isOpaque()) continue;

                int nBlock = getBlockLightAt(grid, center, nx, ny, nz);
                if (nBlock == 0) continue;

                if (nBlock < removedLevel) {
                    setBlockLightAt(grid, center, nx, ny, nz, 0, changed);
                    removeQueue.add(nx, ny, nz, nBlock);
                } else {
                    // Preserve competing light fronts for the later add pass.
                    readdQueue.add(nx, ny, nz, nBlock);
                }
            }
        }
    }

    /** Returns whether the local X/Z column can receive top-down skylight from above. */
    private static boolean columnReceivesSky(Chunk[] grid, int lx, int lz) {
        Chunk chunkAbove = grid[(0 + 1) * 9 + (1 + 1) * 3 + (0 + 1)]; // Center chunk with dy = +1
        if (chunkAbove != null) {
            for (int ly = Chunk.SIZE - 1; ly >= 0; ly--) {
                if (chunkAbove.getBlock(lx, ly, lz).isOpaque()) return false;
            }
            return true;
        }

        Chunk self = grid[(0 + 1) * 9 + (0 + 1) * 3 + (0 + 1)];
        if (self == null) return false;
        for (int ly = 0; ly < Chunk.SIZE; ly++) {
            if (self.getBlock(lx, ly, lz).isOpaque()) return false;
        }
        return true; 
    }

    /** Reads a block from world-space coordinates via the local 3x3x3 chunk window. */
    private static BlockType getBlockAt(Chunk[] grid, ChunkPos center, int wx, int wy, int wz) {
        Chunk c = getChunkFromGrid(grid, center, wx, wy, wz);
        if (c == null) return null;
        return c.getBlock(Math.floorMod(wx, Chunk.SIZE), Math.floorMod(wy, Chunk.SIZE), Math.floorMod(wz, Chunk.SIZE));
    }

    /** Reads skylight at world-space coordinates, returning 0 when out of the local window. */
    private static int getSkyAt(Chunk[] grid, ChunkPos center, int wx, int wy, int wz) {
        Chunk c = getChunkFromGrid(grid, center, wx, wy, wz);
        if (c == null) return 0;
        return c.getSkyLight(Math.floorMod(wx, Chunk.SIZE), Math.floorMod(wy, Chunk.SIZE), Math.floorMod(wz, Chunk.SIZE));
    }

    /** Reads block light at world-space coordinates, returning 0 when out of the local window. */
    private static int getBlockLightAt(Chunk[] grid, ChunkPos center, int wx, int wy, int wz) {
        Chunk c = getChunkFromGrid(grid, center, wx, wy, wz);
        if (c == null) return 0;
        return c.getBlockLight(Math.floorMod(wx, Chunk.SIZE), Math.floorMod(wy, Chunk.SIZE), Math.floorMod(wz, Chunk.SIZE));
    }

    /** Writes skylight and records the touched chunk in {@code changed}. */
    private static void setSkyAt(Chunk[] grid, ChunkPos center, int wx, int wy, int wz, int level, Set<ChunkPos> changed) {
        Chunk c = getChunkFromGrid(grid, center, wx, wy, wz);
        if (c == null) return;
        c.setSkyLight(Math.floorMod(wx, Chunk.SIZE), Math.floorMod(wy, Chunk.SIZE), Math.floorMod(wz, Chunk.SIZE), level);
        
        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cy = Math.floorDiv(wy, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        changed.add(new ChunkPos(cx, cy, cz));
    }

    /** Writes block light and records the touched chunk in {@code changed}. */
    private static void setBlockLightAt(Chunk[] grid, ChunkPos center, int wx, int wy, int wz, int level, Set<ChunkPos> changed) {
        Chunk c = getChunkFromGrid(grid, center, wx, wy, wz);
        if (c == null) return;
        c.setBlockLight(Math.floorMod(wx, Chunk.SIZE), Math.floorMod(wy, Chunk.SIZE), Math.floorMod(wz, Chunk.SIZE), level);
        
        int cx = Math.floorDiv(wx, Chunk.SIZE);
        int cy = Math.floorDiv(wy, Chunk.SIZE);
        int cz = Math.floorDiv(wz, Chunk.SIZE);
        changed.add(new ChunkPos(cx, cy, cz));
    }
}