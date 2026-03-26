package com.voxelgame.game;

import com.voxelgame.common.world.BlockView;
import com.voxelgame.common.world.BlockType;
import com.voxelgame.common.world.Blocks;
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
 * {@link #update(List)} streams around all provided viewer positions and computes
 * the union of all view areas.
 */
public class World implements BlockView {

    // State Machine Tracking Sets
    private final Set<ChunkPos> generatedChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> lightScheduled = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> lightReady = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> networkScheduled = ConcurrentHashMap.newKeySet();

    // Thread-safe queue to hand off chunks from the generation thread back to the main tick
    private final ConcurrentLinkedQueue<ChunkPos> pendingLight = new ConcurrentLinkedQueue<>();

    /**
     * Default horizontal chunk load radius in chunk units.
     * Used to initialize {@link #renderDistanceH} and as the fallback for any
     * code that needs a compile-time default.
     */
    public static final int RENDER_DISTANCE_H = 16;

    /** Vertical chunk load radius in chunk units (above and below viewer). Fixed — not user-configurable yet. */
    public static final int RENDER_DISTANCE_V = 8;

    /**
     * Current horizontal chunk load radius, driven by {@link com.voxelgame.game.GameSettings}.
     * Mutable — call {@link #setRenderDistance(int)} to change it.
     * All internal streaming methods read this field, not the static constant.
     */
    private int renderDistanceH = RENDER_DISTANCE_H;

    /**
     * When true, {@link #update} will run an immediate unload sweep at the start of the
     * next tick before anything else. Set by {@link #setRenderDistance(int)} when
     * the distance decreases, so newly out-of-range chunks are evicted without waiting
     * for the normal scheduler cycle.
     */
    private volatile boolean pendingUnloadSweep = false;

    /**
     * Maximum generation tasks submitted to the executor per tick.
     * Keeps the executor queue shallow so priority re-sorts take effect immediately.
     */
    private static final int MAX_CHUNKS_PER_TICK = 64;

    // -------------------------------------------------------------------------
    // Chunk data — server tick thread only after draining
    // -------------------------------------------------------------------------
    private final Map<ChunkPos, Chunk> chunks = new ConcurrentHashMap<>();
    private final Set<ChunkPos>        inProgress  = new HashSet<>();
    private final List<ChunkPos>       generationQueue = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Cross-thread queue — written by worker threads, drained by tick thread
    // -------------------------------------------------------------------------

    /** Completed generation results waiting to be stored. */
    private final ConcurrentLinkedQueue<PendingChunk> pendingChunks = new ConcurrentLinkedQueue<>();

    // -------------------------------------------------------------------------
    // Schedule guard — skip the scan when the set of viewer center chunks is unchanged
    // -------------------------------------------------------------------------
    private final Set<ChunkPos> lastViewerChunks = new HashSet<>();

    // -------------------------------------------------------------------------
    // Persistence — dirty tracking and background save queue
    // -------------------------------------------------------------------------

    /**
     * Chunks modified since their last save. Uses a concurrent set because
     * setBlock() can be called from the Netty I/O thread (Phase 5C), while the
     * drain loop runs on the server tick thread.
     */
    private final Set<ChunkPos> dirtyChunks = ConcurrentHashMap.newKeySet();

    /**
     * Single background thread for GZIP writes. Always receives a defensive
     * byte[] copy from chunk.toBytes() — never races with the tick thread.
     */
    private final ExecutorService saveExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chunk-saver");
        t.setDaemon(true);
        return t;
    });

    /**
     * Chunk persistence backend. {@code null} disables persistence entirely
     * (no-op path kept for clean interface — unused case in this project).
     */
    private final ChunkStorage storage;

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

    /** Per-viewer streaming anchor and look direction used for generation prioritisation. */
    public record ViewerInfo(float x, float y, float z, float dirX, float dirY, float dirZ) {}

    /**
     * Creates a World with the given world seed.
     *
     * @param seed world seed — same seed always produces identical terrain
        * @thread server-tick (construction thread)
        * @gl-state n/a
     */
    public World(long seed, ChunkStorage storage) {
        this.terrainGenerator = new TerrainGenerator(seed);
        this.storage = storage;
    }

    /**
     * Returns the current horizontal render distance in chunk units.
     *
     * @return horizontal chunk radius
        * @thread any
        * @gl-state n/a
     */
    public int getRenderDistanceH() {
        return renderDistanceH;
    }

    /**
     * Sets the horizontal render distance. Clamped to [2, 32].
     *
     * <p>If the new value is smaller than the current value, schedules an immediate
     * unload sweep at the top of the next {@link #update} tick so newly out-of-range
     * chunks are removed within one server tick (~50 ms).
     *
     * <p>If the new value is larger, no special action is needed — the next
     * {@link #scheduleNeededChunks} call will pick up the expanded outer ring
     * automatically.
     *
     * @param chunks new horizontal radius in chunk units
    * @thread any
    * @gl-state n/a
     */
    public void setRenderDistance(int chunks) {
        int clamped = Math.max(2, Math.min(32, chunks));
        if (clamped < renderDistanceH) {
            // Shrinking: trigger an unload sweep on the next update() tick.
            // Also invalidate the schedule guard so scheduleNeededChunks re-runs —
            // not strictly needed when shrinking (no new chunks to load), but it
            // keeps the guard consistent.
            lastViewerChunks.clear();
            pendingUnloadSweep = true;
        } else if (clamped > renderDistanceH) {
            // Growing: invalidate the schedule guard so the next tick expands the load area.
            lastViewerChunks.clear();
        }
        renderDistanceH = clamped;
    }

    // -------------------------------------------------------------------------
    // Main update — call once per tick on the server tick thread
    // -------------------------------------------------------------------------

    /**
     * Drives chunk streaming for all active viewers. Call once per tick.
     *
     * @param viewers active viewer positions and look directions
        * @thread server-tick
        * @gl-state n/a
        * @see #scheduleNeededChunks(List)
     */
    public void update(List<ViewerInfo> viewers) {
        // If render distance was reduced, do an immediate unload sweep before anything else.
        // This evicts newly out-of-range chunks within one tick of the setting change.
        if (pendingUnloadSweep) {
            pendingUnloadSweep = false;
            unloadDistantChunks(viewers);
        }

        Set<ChunkPos> viewerChunks = new HashSet<>();
        for (ViewerInfo viewer : viewers) {
            int centerCX = Math.floorDiv((int) viewer.x(), Chunk.SIZE);
            int centerCY = Math.floorDiv((int) viewer.y(), Chunk.SIZE);
            int centerCZ = Math.floorDiv((int) viewer.z(), Chunk.SIZE);
            viewerChunks.add(new ChunkPos(centerCX, centerCY, centerCZ));
        }

        if (!viewerChunks.equals(lastViewerChunks)) {
            scheduleNeededChunks(viewers);
            lastViewerChunks.clear();
            lastViewerChunks.addAll(viewerChunks);
        }

        tickGenerationQueue(viewers);
        tickStateMachine(viewers);
        unloadDistantChunks(viewers);
        saveDirtyChunks(); // flush block changes to disk at end of each tick
    }

    // -------------------------------------------------------------------------
    // BlockView implementation
    // -------------------------------------------------------------------------

    /**
     * Returns the block at the given world-space coordinates.
    * Returns {@link Blocks#AIR} for unloaded chunks.
     *
     * {@inheritDoc}
        * @thread server-tick
        * @gl-state n/a
     */
    @Override
    public BlockType getBlock(int worldX, int worldY, int worldZ) {
        int cx = Math.floorDiv(worldX, Chunk.SIZE);
        int cy = Math.floorDiv(worldY, Chunk.SIZE);
        int cz = Math.floorDiv(worldZ, Chunk.SIZE);
        Chunk chunk = chunks.get(new ChunkPos(cx, cy, cz));
        if (chunk == null) return Blocks.AIR;
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
     * @param type   block type to place ({@link Blocks#AIR} to remove)
    * @thread server-tick
    * @gl-state n/a
     */
    public void setBlock(int worldX, int worldY, int worldZ, BlockType type) {
        int cx = Math.floorDiv(worldX, Chunk.SIZE);
        int cy = Math.floorDiv(worldY, Chunk.SIZE);
        int cz = Math.floorDiv(worldZ, Chunk.SIZE);
        Chunk chunk = chunks.get(new ChunkPos(cx, cy, cz));
        if (chunk == null) return;

        chunk.setBlock(
            Math.floorMod(worldX, Chunk.SIZE),
            Math.floorMod(worldY, Chunk.SIZE),
            Math.floorMod(worldZ, Chunk.SIZE),
            type
        );
        // Mark modified — will be flushed to disk at end of current tick
        dirtyChunks.add(new ChunkPos(cx, cy, cz));
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
    * @thread server-tick
    * @gl-state n/a
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
    * @thread server-tick
    * @gl-state n/a
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
        *
        * @thread server-tick
        * @gl-state n/a
     */
    public void cleanup() {
        flushDirtyChunks(); // persist all pending writes before shutdown
        generationExecutor.shutdownNow();
        chunks.clear();
    }

    // -------------------------------------------------------------------------
    // Streaming internals — tick thread only
    // -------------------------------------------------------------------------

    /**
     * Adds all in-range, not-yet-queued chunk positions to {@link #generationQueue}.
     * Only called when the set of viewer center chunks changes.
     */
    private void scheduleNeededChunks(List<ViewerInfo> viewers) {
        for (ViewerInfo viewer : viewers) {
            int centerCX = Math.floorDiv((int) viewer.x(), Chunk.SIZE);
            int centerCY = Math.floorDiv((int) viewer.y(), Chunk.SIZE);
            int centerCZ = Math.floorDiv((int) viewer.z(), Chunk.SIZE);
            
            // Pad the radius by 2 to feed the client's State Machine collar
            int paddedRadius = renderDistanceH + 2;

            for (int cx = centerCX - paddedRadius; cx <= centerCX + paddedRadius; cx++) {
                for (int cz = centerCZ - paddedRadius; cz <= centerCZ + paddedRadius; cz++) {
                    int dx = cx - centerCX, dz = cz - centerCZ;
                    if (dx * dx + dz * dz > paddedRadius * paddedRadius) continue;
                    
                    for (int cy = centerCY - RENDER_DISTANCE_V - 2; cy <= centerCY + RENDER_DISTANCE_V + 2; cy++) {
                        if (cy < 0) continue;
                        ChunkPos pos = new ChunkPos(cx, cy, cz);
                        if (!chunks.containsKey(pos) && !inProgress.contains(pos) && !generationQueue.contains(pos)) {
                            generationQueue.add(pos);
                        }
                    }
                }
            }
        }
    }

    /**
     * Unloads all chunks outside the render distance.
     * Evicts their heightmap cache entries when the full column is gone.
     */
    private void unloadDistantChunks(List<ViewerInfo> viewers) {
        List<ChunkPos> toUnload = new ArrayList<>();
        for (ChunkPos pos : chunks.keySet()) {
            if (!isInRangeOfAny(pos, viewers)) toUnload.add(pos);
        }
        for (ChunkPos pos : toUnload) {
            chunks.remove(pos);
            evictHeightmapIfColumnUnloaded(pos);

            // Clear state machine memory to prevent RAM leaks
            generatedChunks.remove(pos);
            lightScheduled.remove(pos);
            lightReady.remove(pos);
            networkScheduled.remove(pos);
        }
    }

    /**
     * Submits all dirty chunks to the background save executor.
     * Each chunk snapshot ({@link Chunk#toBytes()}) is an immutable defensive copy,
     * so the executor thread never races with the tick thread modifying block data.
     * Called at the end of each tick in {@link #update}.
     */
    private void saveDirtyChunks() {
        if (storage == null || dirtyChunks.isEmpty()) return;

        Iterator<ChunkPos> it = dirtyChunks.iterator();
        while (it.hasNext()) {
            ChunkPos pos = it.next();
            it.remove();
            Chunk chunk = chunks.get(pos);
            if (chunk == null) continue; // chunk unloaded before save could run — skip
            byte[] data = chunk.toBytes(); // defensive copy — safe to hand off to executor
            saveExecutor.execute(() -> storage.save(pos, data));
        }
    }

    /**
     * Saves all dirty chunks synchronously and waits for any in-flight background
     * saves to complete. Called from {@link #cleanup()} before executors shut down —
     * guarantees no writes are lost on a clean server stop.
     */
    public void flushDirtyChunks() {
        if (storage == null) return;
        Iterator<ChunkPos> it = dirtyChunks.iterator();
        while (it.hasNext()) {
            ChunkPos pos = it.next();
            it.remove();
            Chunk chunk = chunks.get(pos);
            if (chunk != null) storage.save(pos, chunk.toBytes());
        }
        // Drain the background executor — wait for any writes already in-flight
        saveExecutor.shutdown();
        try {
            if (!saveExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                System.err.println("[World] Save executor did not finish in 10s — some chunks may be unsaved.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isInRange(ChunkPos pos, int centerCX, int centerCY, int centerCZ) {
        int dx = pos.x() - centerCX, dy = pos.y() - centerCY, dz = pos.z() - centerCZ;
        
        // Pad BOTH horizontal and vertical radiuses by 2 to sustain the State Machine collars
        int paddedRadiusH = renderDistanceH + 2;
        int paddedRadiusV = RENDER_DISTANCE_V + 2;
        
        return dx * dx + dz * dz <= paddedRadiusH * paddedRadiusH
            && Math.abs(dy) <= paddedRadiusV;
    }

    private boolean isInRangeOfAny(ChunkPos pos, List<ViewerInfo> viewers) {
        for (ViewerInfo v : viewers) {
            int cx = Math.floorDiv((int) v.x(), Chunk.SIZE);
            int cy = Math.floorDiv((int) v.y(), Chunk.SIZE);
            int cz = Math.floorDiv((int) v.z(), Chunk.SIZE);
            if (isInRange(pos, cx, cy, cz)) return true;
        }
        return false;
    }

    private void evictHeightmapIfColumnUnloaded(ChunkPos pos) {
        boolean columnStillLoaded = chunks.keySet().stream()
            .anyMatch(p -> p.x() == pos.x() && p.z() == pos.z());
        if (!columnStillLoaded) {
            terrainGenerator.evictColumn(pos.x(), pos.z());
        }
    }

    private void tickGenerationQueue(List<ViewerInfo> viewers) {
        if (generationQueue.isEmpty()) return;
        if (viewers.isEmpty()) return;

        generationQueue.removeIf(pos ->
            chunks.containsKey(pos) || inProgress.contains(pos));

        if (generationQueue.isEmpty()) return;

        // --- Pure Distance Pass: Guarantees perfect concentric rings for the client's State Machine ---
        generationQueue.sort(Comparator.comparingInt(p -> {
            ViewerInfo nearest = findNearestViewer(p, viewers);
            int centerCX = Math.floorDiv((int) nearest.x(), Chunk.SIZE);
            int centerCY = Math.floorDiv((int) nearest.y(), Chunk.SIZE);
            int centerCZ = Math.floorDiv((int) nearest.z(), Chunk.SIZE);
            int dx = p.x() - centerCX, dy = p.y() - centerCY, dz = p.z() - centerCZ;
            return dx * dx + dy * dy + dz * dz;
        }));

        Iterator<ChunkPos> it = generationQueue.iterator();
        int submitted = 0;
        while (it.hasNext() && submitted < MAX_CHUNKS_PER_TICK) {
            ChunkPos pos = it.next();
            it.remove();
            submitToExecutor(pos);
            submitted++;
        }
    }

    ///**
    // * Re-sorts the generation queue by direction-biased distance and submits tasks
    // * to the executor each tick. Budget split: 75% direction-biased, 25% pure distance.
    // * Keeps the executor queue shallow so priority changes take effect within one tick.
    // */
    //private void tickGenerationQueue(List<ViewerInfo> viewers) {
    //    if (generationQueue.isEmpty()) return;
    //    if (viewers.isEmpty()) return;
//
    //    generationQueue.removeIf(pos ->
    //        chunks.containsKey(pos) || inProgress.contains(pos));
//
    //    if (generationQueue.isEmpty()) return;
//
    //    int biasedCount   = (int) (MAX_CHUNKS_PER_TICK * 0.75);
    //    int distanceCount = MAX_CHUNKS_PER_TICK - biasedCount;
//
    //    // --- Biased pass: sort by direction-weighted distance ---
    //    generationQueue.sort(Comparator.comparingDouble(p -> {
    //        ViewerInfo nearest = findNearestViewer(p, viewers);
    //        int centerCX = Math.floorDiv((int) nearest.x(), Chunk.SIZE);
    //        int centerCY = Math.floorDiv((int) nearest.y(), Chunk.SIZE);
    //        int centerCZ = Math.floorDiv((int) nearest.z(), Chunk.SIZE);
    //        float dx = p.x() - centerCX, dy = p.y() - centerCY, dz = p.z() - centerCZ;
    //        float squaredDist = dx * dx + dy * dy + dz * dz;
    //        float dot = 0f;
    //        float len = (float) Math.sqrt(squaredDist);
    //        if (len > 0.001f && (nearest.dirX() != 0 || nearest.dirY() != 0 || nearest.dirZ() != 0)) {
    //            dot = (dx / len) * nearest.dirX()
    //                + (dy / len) * nearest.dirY()
    //                + (dz / len) * nearest.dirZ();
    //        }
    //        return squaredDist * (1.0 - dot * 0.5);
    //    }));
//
    //    Set<ChunkPos> submittedThisTick = new HashSet<>();
    //    Iterator<ChunkPos> it = generationQueue.iterator();
    //    int submitted = 0;
    //    while (it.hasNext() && submitted < biasedCount) {
    //        ChunkPos pos = it.next();
    //        it.remove();
    //        submitToExecutor(pos);
    //        submittedThisTick.add(pos);
    //        submitted++;
    //    }
//
    //    // --- Distance pass: guarantees background progress regardless of look direction ---
    //    generationQueue.sort(Comparator.comparingInt(p -> {
    //        ViewerInfo nearest = findNearestViewer(p, viewers);
    //        int centerCX = Math.floorDiv((int) nearest.x(), Chunk.SIZE);
    //        int centerCY = Math.floorDiv((int) nearest.y(), Chunk.SIZE);
    //        int centerCZ = Math.floorDiv((int) nearest.z(), Chunk.SIZE);
    //        int dx = p.x() - centerCX, dy = p.y() - centerCY, dz = p.z() - centerCZ;
    //        return dx * dx + dy * dy + dz * dz;
    //    }));
//
    //    it = generationQueue.iterator();
    //    submitted = 0;
    //    while (it.hasNext() && submitted < distanceCount) {
    //        ChunkPos pos = it.next();
    //        if (submittedThisTick.contains(pos)) continue;
    //        it.remove();
    //        submitToExecutor(pos);
    //        submitted++;
    //    }
    //}

    private int debugTickCounter = 0;

    private void tickStateMachine(List<ViewerInfo> viewers) {
        if (viewers == null || viewers.isEmpty()) return;

        // --- STAGE 1: Drain raw block data -> Promote to Light Engine ---
        PendingChunk pending;
        while ((pending = pendingChunks.poll()) != null) {
            ChunkPos pos = pending.pos();
            chunks.put(pos, pending.chunk());
            generatedChunks.add(pos);
            inProgress.remove(pos);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        ChunkPos n = new ChunkPos(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                        
                        if (generatedChunks.contains(n) && !lightScheduled.contains(n)) {
                            if (hasCompleteCollar(n, generatedChunks, viewers)) {
                                lightScheduled.add(n);
                                
                                generationExecutor.submit(() -> {
                                    try {
                                        // Catch silent background crashes!
                                        com.voxelgame.common.world.LightEngine.initChunkLight(chunks, n);
                                        pendingLight.offer(n);
                                    } catch (Exception e) {
                                        System.err.println("CRASH IN LIGHT ENGINE AT " + n + ": " + e.getMessage());
                                        e.printStackTrace();
                                    }
                                });
                            }
                        }
                    }
                }
            }
        }

        // --- STAGE 2: Drain lit chunks -> Promote to Network Stream ---
        ChunkPos litPos;
        while ((litPos = pendingLight.poll()) != null) {
            lightReady.add(litPos);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        ChunkPos n = new ChunkPos(litPos.x() + dx, litPos.y() + dy, litPos.z() + dz);
                        
                        if (lightReady.contains(n) && !networkScheduled.contains(n)) {
                            if (hasCompleteCollar(n, lightReady, viewers)) {
                                networkScheduled.add(n);
                            }
                        }
                    }
                }
            }
        }

        // --- TELEMETRY TRACKER ---
        debugTickCounter++;
        if (debugTickCounter >= 60) {
            System.out.println(String.format(
                "[State Machine] RawGen: %d | LightSch: %d | LightRdy: %d | NetSch: %d",
                generatedChunks.size(), lightScheduled.size(), lightReady.size(), networkScheduled.size()
            ));
            debugTickCounter = 0;
        }
    }

    private ViewerInfo findNearestViewer(ChunkPos pos, List<ViewerInfo> viewers) {
        ViewerInfo nearest = viewers.get(0);
        int nearestDistSq = chunkDistanceSq(pos, nearest);
        for (int i = 1; i < viewers.size(); i++) {
            ViewerInfo candidate = viewers.get(i);
            int distSq = chunkDistanceSq(pos, candidate);
            if (distSq < nearestDistSq) {
                nearest = candidate;
                nearestDistSq = distSq;
            }
        }
        return nearest;
    }

    private int chunkDistanceSq(ChunkPos pos, ViewerInfo viewer) {
        int centerCX = Math.floorDiv((int) viewer.x(), Chunk.SIZE);
        int centerCY = Math.floorDiv((int) viewer.y(), Chunk.SIZE);
        int centerCZ = Math.floorDiv((int) viewer.z(), Chunk.SIZE);
        int dx = pos.x() - centerCX;
        int dy = pos.y() - centerCY;
        int dz = pos.z() - centerCZ;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Captures a neighbor snapshot and submits one generation task to the executor.
     * Worker thread generates terrain only — no meshing, no GL calls.
     */
    private void submitToExecutor(ChunkPos pos) {
        inProgress.add(pos);
        final ChunkStorage storageRef = this.storage;
        
        generationExecutor.submit(() -> {
            byte[] saved = storageRef != null ? storageRef.load(pos) : null;
            Chunk chunk;
            
            if (saved != null) {
                // Restore from disk and populate sky light columns
                chunk = Chunk.fromBytes(saved);
                terrainGenerator.fillSkyLight(chunk, pos);
            } else {
                // Generate fresh terrain arrays
                chunk = terrainGenerator.generateChunk(pos);
            }
            
            // Pass the raw chunk back to the main thread to enter the State Machine
            pendingChunks.offer(new PendingChunk(pos, chunk));
        });
    }

    private boolean isOutsideGenerationLimits(ChunkPos pos, List<ViewerInfo> viewers) {
        if (pos.y() < 0) return true;

        for (ViewerInfo viewer : viewers) {
            int centerCX = Math.floorDiv((int) viewer.x(), Chunk.SIZE);
            int centerCY = Math.floorDiv((int) viewer.y(), Chunk.SIZE);
            int centerCZ = Math.floorDiv((int) viewer.z(), Chunk.SIZE);
            
            int dx = pos.x() - centerCX;
            int dy = pos.y() - centerCY;
            int dz = pos.z() - centerCZ;
            
            // Validate against the padded horizontal and vertical boundaries
            int paddedRadius = renderDistanceH + 2;
            if (dx * dx + dz * dz <= paddedRadius * paddedRadius && Math.abs(dy) <= RENDER_DISTANCE_V + 2) {
                return false; 
            }
        }
        return true;
    }

    private boolean hasCompleteCollar(ChunkPos pos, Set<ChunkPos> stateSet, List<ViewerInfo> viewers) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    
                    ChunkPos n = new ChunkPos(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                    
                    if (!stateSet.contains(n)) {
                        // If the neighbor is missing but outside the world limits, bypass it
                        if (isOutsideGenerationLimits(n, viewers)) continue;
                        
                        return false; // Neighbor is missing and within limits; collar is incomplete
                    }
                }
            }
        }
        return true;
    }

    public boolean isChunkReadyForNetwork(ChunkPos pos) {
        return networkScheduled.contains(pos);
    }
}