package com.voxelgame.game;

import com.voxelgame.common.world.Block;
import com.voxelgame.common.world.BlockView;
import com.voxelgame.common.world.Chunk;
import com.voxelgame.common.world.ChunkPos;

import java.util.*;
import java.util.concurrent.*;

/**
 * Server-side chunk data manager. Owns all loaded chunks and drives async terrain
 * generation. Has no rendering or GPU dependency whatsoever — all meshing happens
 * in {@link com.voxelgame.client.ClientWorld} on the client side.
 *
 * <h3>Responsibility</h3>
 * <ul>
 *   <li>Generating chunks on background worker threads.</li>
 *   <li>Storing chunk block data in a HashMap.</li>
 *   <li>Loading chunks near the viewer and unloading distant ones.</li>
 *   <li>Answering block queries for physics and raycasting ({@link BlockView}).</li>
 * </ul>
 *
 * <h3>Threading model</h3>
 * Worker threads run {@link TerrainGenerator#generateChunk} and offer results into
 * {@link #pendingChunks}. The server tick thread (or main thread in older non-network
 * usage) drains {@link #pendingChunks} during {@link #update}, stores chunk data,
 * and manages the generation queue. No GL calls anywhere in this class.
 *
 * <h3>Multiplayer note</h3>
 * {@link #update(float, float, float, float, float, float)} currently streams around
 * a single viewer position. Phase 5D will expand this to accept a collection of
 * player positions and compute the union of all view areas.
 */
public class World implements BlockView {

    /** Horizontal circular chunk load radius in chunk units. */
    private static final int RENDER_DISTANCE_H = 16;

    /** Vertical chunk load radius in chunk units (above and below viewer). */
    private static final int RENDER_DISTANCE_V = 6;

    /** Maximum chunk uploads (data stores) per frame from the pending queue. */
    private static final int MAX_UPLOADS_PER_FRAME = 16;

    /**
     * Maximum generation tasks submitted to the executor per tick.
     * Keeps the executor queue shallow so priority re-sorts take effect immediately.
     */
    private static final int MAX_CHUNKS_PER_TICK = 16;

    // -------------------------------------------------------------------------
    // Chunk data — server tick thread only after draining
    // -------------------------------------------------------------------------
    private final Map<ChunkPos, Chunk> chunks      = new HashMap<>();
    private final Set<ChunkPos>        inProgress  = new HashSet<>();
    private final List<ChunkPos>       generationQueue = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Cross-thread queue — written by worker threads, drained by tick thread
    // -------------------------------------------------------------------------

    /** Completed generation results waiting to be stored. */
    private final ConcurrentLinkedQueue<PendingChunk> pendingChunks = new ConcurrentLinkedQueue<>();

    // -------------------------------------------------------------------------
    // Schedule guard — skip the 14k-position scan when center chunk is unchanged
    // -------------------------------------------------------------------------
    private int lastScheduledCX = Integer.MIN_VALUE;
    private int lastScheduledCY = Integer.MIN_VALUE;
    private int lastScheduledCZ = Integer.MIN_VALUE;

    private final ExecutorService generationExecutor = Executors.newFixedThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() - 1), r -> {
            Thread t = new Thread(r, "chunk-generator");
            t.setDaemon(true);
            return t;
        }
    );

    private final TerrainGenerator terrainGenerator;

    /** Carries a freshly generated chunk from the worker thread to the tick thread. */
    private record PendingChunk(ChunkPos pos, Chunk chunk) {}

    /**
     * Creates a World with the given world seed.
     *
     * @param seed world seed — same seed always produces identical terrain
     */
    public World(long seed) {
        this.terrainGenerator = new TerrainGenerator(seed);
    }

    // -------------------------------------------------------------------------
    // Main update — call once per tick on the server tick thread
    // -------------------------------------------------------------------------

    /**
     * Drives chunk streaming for a single viewer position. Call once per tick.
     *
     * @param px   viewer X in world space
     * @param py   viewer Y in world space
     * @param pz   viewer Z in world space
     * @param dirX normalised look direction X (used for direction-biased generation)
     * @param dirY normalised look direction Y
     * @param dirZ normalised look direction Z
     */
    public void update(float px, float py, float pz, float dirX, float dirY, float dirZ) {
        int centerCX = Math.floorDiv((int) px, Chunk.SIZE);
        int centerCY = Math.floorDiv((int) py, Chunk.SIZE);
        int centerCZ = Math.floorDiv((int) pz, Chunk.SIZE);

        drainPendingChunks(centerCX, centerCY, centerCZ);

        if (centerCX != lastScheduledCX
                || centerCY != lastScheduledCY
                || centerCZ != lastScheduledCZ) {
            scheduleNeededChunks(centerCX, centerCY, centerCZ);
            lastScheduledCX = centerCX;
            lastScheduledCY = centerCY;
            lastScheduledCZ = centerCZ;
        }

        tickGenerationQueue(centerCX, centerCY, centerCZ, dirX, dirY, dirZ);
        unloadDistantChunks(centerCX, centerCY, centerCZ);
    }

    // -------------------------------------------------------------------------
    // BlockView implementation
    // -------------------------------------------------------------------------

    /**
     * Returns the block at the given world-space coordinates.
     * Returns {@link Block#AIR} for unloaded chunks.
     *
     * {@inheritDoc}
     */
    @Override
    public Block getBlock(int worldX, int worldY, int worldZ) {
        int cx = Math.floorDiv(worldX, Chunk.SIZE);
        int cy = Math.floorDiv(worldY, Chunk.SIZE);
        int cz = Math.floorDiv(worldZ, Chunk.SIZE);
        Chunk chunk = chunks.get(new ChunkPos(cx, cy, cz));
        if (chunk == null) return Block.AIR;
        return chunk.getBlock(
            Math.floorMod(worldX, Chunk.SIZE),
            Math.floorMod(worldY, Chunk.SIZE),
            Math.floorMod(worldZ, Chunk.SIZE)
        );
    }

    // -------------------------------------------------------------------------
    // Block mutation — server-authoritative
    // -------------------------------------------------------------------------

    /**
     * Sets the block at the given world-space coordinates. Purely a data operation —
     * no mesh rebuild, no GL calls. The client receives a {@code BlockChangePacket}
     * separately and updates its own mesh.
     *
     * @param worldX world-space X
     * @param worldY world-space Y
     * @param worldZ world-space Z
     * @param block  block type to place ({@link Block#AIR} to remove)
     */
    public void setBlock(int worldX, int worldY, int worldZ, Block block) {
        int cx = Math.floorDiv(worldX, Chunk.SIZE);
        int cy = Math.floorDiv(worldY, Chunk.SIZE);
        int cz = Math.floorDiv(worldZ, Chunk.SIZE);
        Chunk chunk = chunks.get(new ChunkPos(cx, cy, cz));
        if (chunk == null) return;

        chunk.setBlock(
            Math.floorMod(worldX, Chunk.SIZE),
            Math.floorMod(worldY, Chunk.SIZE),
            Math.floorMod(worldZ, Chunk.SIZE),
            block
        );
    }

    // -------------------------------------------------------------------------
    // Data accessors for ServerWorld
    // -------------------------------------------------------------------------

    /**
     * Returns the chunk at the given grid position, or {@code null} if not loaded.
     * Used by {@link com.voxelgame.server.ServerWorld} to access block data for
     * building {@code ChunkDataPackets}.
     *
     * @param pos the chunk grid position
     * @return the chunk, or null if unloaded
     */
    public Chunk getChunk(ChunkPos pos) {
        return chunks.get(pos);
    }

    /**
     * Returns an unmodifiable live view of the currently loaded chunk positions.
     * Used by {@link com.voxelgame.server.ServerWorld} to determine which chunks
     * to stream to each connected player.
     *
     * @return unmodifiable set of loaded chunk positions
     */
    public Set<ChunkPos> getLoadedChunkPositions() {
        return Collections.unmodifiableSet(chunks.keySet());
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Shuts down the generation thread pool and clears all chunk data.
     * Call once on server shutdown.
     */
    public void cleanup() {
        generationExecutor.shutdownNow();
        chunks.clear();
    }

    // -------------------------------------------------------------------------
    // Streaming internals — tick thread only
    // -------------------------------------------------------------------------

    /**
     * Drains completed generation results into the chunk map.
     * Discards results for chunks that have moved out of range while the worker was running.
     */
    private void drainPendingChunks(int centerCX, int centerCY, int centerCZ) {
        PendingChunk pending;
        int stored = 0;
        while (stored < MAX_UPLOADS_PER_FRAME && (pending = pendingChunks.poll()) != null) {
            inProgress.remove(pending.pos());
            if (!isInRange(pending.pos(), centerCX, centerCY, centerCZ)) continue;
            chunks.put(pending.pos(), pending.chunk());
            stored++;
        }
    }

    /**
     * Adds all in-range, not-yet-queued chunk positions to {@link #generationQueue}.
     * Only called when the center chunk changes — skipped otherwise to avoid
     * scanning ~14,000 positions per tick while standing still.
     */
    private void scheduleNeededChunks(int centerCX, int centerCY, int centerCZ) {
        for (int cx = centerCX - RENDER_DISTANCE_H; cx <= centerCX + RENDER_DISTANCE_H; cx++) {
            for (int cz = centerCZ - RENDER_DISTANCE_H; cz <= centerCZ + RENDER_DISTANCE_H; cz++) {
                int dx = cx - centerCX, dz = cz - centerCZ;
                if (dx * dx + dz * dz > RENDER_DISTANCE_H * RENDER_DISTANCE_H) continue;
                for (int cy = centerCY - RENDER_DISTANCE_V; cy <= centerCY + RENDER_DISTANCE_V; cy++) {
                    if (cy < 0) continue;
                    ChunkPos pos = new ChunkPos(cx, cy, cz);
                    if (!chunks.containsKey(pos)
                            && !inProgress.contains(pos)
                            && !generationQueue.contains(pos)) {
                        generationQueue.add(pos);
                    }
                }
            }
        }
    }

    /**
     * Unloads all chunks outside the render distance.
     * Evicts their heightmap cache entries when the full column is gone.
     */
    private void unloadDistantChunks(int centerCX, int centerCY, int centerCZ) {
        List<ChunkPos> toUnload = new ArrayList<>();
        for (ChunkPos pos : chunks.keySet()) {
            if (!isInRange(pos, centerCX, centerCY, centerCZ)) toUnload.add(pos);
        }
        for (ChunkPos pos : toUnload) {
            chunks.remove(pos);
            evictHeightmapIfColumnUnloaded(pos);
        }
    }

    private boolean isInRange(ChunkPos pos, int centerCX, int centerCY, int centerCZ) {
        int dx = pos.x() - centerCX, dy = pos.y() - centerCY, dz = pos.z() - centerCZ;
        return dx * dx + dz * dz <= RENDER_DISTANCE_H * RENDER_DISTANCE_H
            && Math.abs(dy) <= RENDER_DISTANCE_V;
    }

    private void evictHeightmapIfColumnUnloaded(ChunkPos pos) {
        boolean columnStillLoaded = chunks.keySet().stream()
            .anyMatch(p -> p.x() == pos.x() && p.z() == pos.z());
        if (!columnStillLoaded) {
            terrainGenerator.evictColumn(pos.x(), pos.z());
        }
    }

    /**
     * Re-sorts the generation queue by direction-biased distance and submits tasks
     * to the executor each tick. Budget split: 75% direction-biased, 25% pure distance.
     * Keeps the executor queue shallow so priority changes take effect within one tick.
     */
    private void tickGenerationQueue(int centerCX, int centerCY, int centerCZ,
                                     float dirX, float dirY, float dirZ) {
        if (generationQueue.isEmpty()) return;

        generationQueue.removeIf(pos ->
            chunks.containsKey(pos) || inProgress.contains(pos));

        if (generationQueue.isEmpty()) return;

        int biasedCount   = (int) (MAX_CHUNKS_PER_TICK * 0.75);
        int distanceCount = MAX_CHUNKS_PER_TICK - biasedCount;

        // --- Biased pass: sort by direction-weighted distance ---
        generationQueue.sort(Comparator.comparingDouble(p -> {
            float dx = p.x() - centerCX, dy = p.y() - centerCY, dz = p.z() - centerCZ;
            float squaredDist = dx * dx + dy * dy + dz * dz;
            float dot = 0f;
            float len = (float) Math.sqrt(squaredDist);
            if (len > 0.001f && (dirX != 0 || dirY != 0 || dirZ != 0)) {
                dot = (dx / len) * dirX + (dy / len) * dirY + (dz / len) * dirZ;
            }
            return squaredDist * (1.0 - dot * 0.5);
        }));

        Set<ChunkPos> submittedThisTick = new HashSet<>();
        Iterator<ChunkPos> it = generationQueue.iterator();
        int submitted = 0;
        while (it.hasNext() && submitted < biasedCount) {
            ChunkPos pos = it.next();
            it.remove();
            submitToExecutor(pos);
            submittedThisTick.add(pos);
            submitted++;
        }

        // --- Distance pass: guarantees background progress regardless of look direction ---
        generationQueue.sort(Comparator.comparingInt(p -> {
            int dx = p.x() - centerCX, dy = p.y() - centerCY, dz = p.z() - centerCZ;
            return dx * dx + dy * dy + dz * dz;
        }));

        it = generationQueue.iterator();
        submitted = 0;
        while (it.hasNext() && submitted < distanceCount) {
            ChunkPos pos = it.next();
            if (submittedThisTick.contains(pos)) continue;
            it.remove();
            submitToExecutor(pos);
            submitted++;
        }
    }

    /**
     * Captures a neighbor snapshot and submits one generation task to the executor.
     * Worker thread generates terrain only — no meshing, no GL calls.
     */
    private void submitToExecutor(ChunkPos pos) {
        inProgress.add(pos);
        generationExecutor.submit(() -> {
            Chunk chunk = terrainGenerator.generateChunk(pos);
            pendingChunks.offer(new PendingChunk(pos, chunk));
        });
    }
}