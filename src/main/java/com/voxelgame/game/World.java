package com.voxelgame.game;

import com.voxelgame.engine.Mesh;
import com.voxelgame.engine.ShaderProgram;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.*;

/**
 * Holds all loaded chunks and their corresponding GPU meshes.
 * Drives chunk streaming — loading chunks near the viewer and unloading
 * chunks that have moved out of range.
 *
 * <h3>Threading model</h3>
 * All calls to {@link ChunkMesher#mesh} run on the worker thread — never the
 * main thread. The main thread only performs GPU uploads ({@code new Mesh()})
 * and map bookkeeping.
 *
 * <p>Two async paths exist:
 * <ul>
 *   <li><b>New chunk path:</b> worker generates terrain + meshes → puts result
 *       in {@link #pendingChunks}. Main thread drains, stores block data,
 *       uploads mesh, and marks the six neighbors dirty.</li>
 *   <li><b>Remesh path:</b> positions in {@link #dirtyMeshes} are submitted to
 *       the worker for re-meshing → results land in {@link #pendingRemeshes}.
 *       Main thread drains and swaps the GPU mesh. Used for neighbor boundary
 *       corrections and chunk unload recovery.</li>
 * </ul>
 *
 * <p>{@code setBlock} is the only place that still calls {@link ChunkMesher#mesh}
 * synchronously — it is player-triggered, infrequent, and affects at most 7 chunks.
 *
 * <h3>Multiplayer note</h3>
 * This class is server-side logic. {@link #update} will become
 * {@code update(Collection<Vector3f>)} when multiplayer is added. The seam is here.
 */
public class World {

    /** Horizontal circular chunk load radius in chunk units. */
    private static final int RENDER_DISTANCE_H = 16;

    /** Vertical chunk load radius in chunk units (above and below viewer). */
    private static final int RENDER_DISTANCE_V = 6;

    /**
     * Maximum new-chunk GPU uploads per frame.
     * Each upload also marks up to 6 neighbors dirty for async remeshing.
     */
    private static final int MAX_UPLOADS_PER_FRAME = 4;

    /**
     * Maximum neighbor remesh results applied per frame.
     * Separate from the new-chunk cap so remeshes don't starve new chunk uploads.
     */
    private static final int MAX_REMESHES_PER_FRAME = 8;

    /** Maps chunk grid positions to their block data. */
    private final Map<ChunkPos, Chunk> chunks = new HashMap<>();

    /** Maps chunk grid positions to their GPU meshes. */
    private final Map<ChunkPos, Mesh> meshes = new HashMap<>();

    /** Positions currently being generated or meshed on the worker thread. */
    private final Set<ChunkPos> inProgress = new HashSet<>();

    /**
     * Positions that need their GPU mesh rebuilt due to a neighbor change.
     * Processed asynchronously — positions are submitted to the worker and
     * results land in {@link #pendingRemeshes}.
     * Using LinkedHashSet preserves insertion order (FIFO-ish) for consistent behavior.
     */
    private final Set<ChunkPos> dirtyMeshes = new LinkedHashSet<>();

    /**
     * Positions currently submitted for async remeshing.
     * Prevents the same position being submitted multiple times while in-flight.
     */
    private final Set<ChunkPos> remeshInProgress = new HashSet<>();

    /** New chunks (block data + pre-built vertices) waiting for GPU upload. */
    private final ConcurrentLinkedQueue<PendingChunk> pendingChunks = new ConcurrentLinkedQueue<>();

    /** Completed remesh jobs (vertices only) waiting for GPU swap. */
    private final ConcurrentLinkedQueue<PendingRemesh> pendingRemeshes = new ConcurrentLinkedQueue<>();

    /**
     * Single background thread for all meshing work.
     * Upgrade to newFixedThreadPool(N) when parallel generation is needed —
     * each task is self-contained (no shared mutable state), so no other
     * changes are required.
     */
    private final ExecutorService generationExecutor = Executors.newFixedThreadPool(
        Math.max(1, Runtime.getRuntime().availableProcessors() - 1), r -> {
            Thread t = new Thread(r, "chunk-generator");
            t.setDaemon(true);
            return t;
        }
    );

    private final TerrainGenerator terrainGenerator;

    /** Carrier for a newly generated chunk — block data + pre-built vertex array. */
    private record PendingChunk(ChunkPos pos, Chunk chunk, float[] vertices) {}

    /** Carrier for a completed neighbor remesh — vertex array only, no new chunk data. */
    private record PendingRemesh(ChunkPos pos, float[] vertices) {}

    /**
     * Creates a World with the given world seed.
     *
     * @param seed world seed — same seed always produces identical terrain
     */
    public World(long seed) {
        this.terrainGenerator = new TerrainGenerator(seed);
    }

    /**
     * Drives chunk streaming for a single viewer position. Call once per tick
     * on the main thread.
     *
     * <p>In multiplayer: replace with {@code update(Collection<Vector3f>)} and
     * compute the union of all viewer load areas.
     *
     * @param px viewer X in world space
     * @param py viewer Y in world space
     * @param pz viewer Z in world space
     */
    public void update(float px, float py, float pz) {
        int centerCX = Math.floorDiv((int) px, Chunk.SIZE);
        int centerCY = Math.floorDiv((int) py, Chunk.SIZE);
        int centerCZ = Math.floorDiv((int) pz, Chunk.SIZE);

        drainPendingChunks(centerCX, centerCY, centerCZ);
        drainPendingRemeshes();
        processDirtyMeshes();
        scheduleNeededChunks(centerCX, centerCY, centerCZ);
        unloadDistantChunks(centerCX, centerCY, centerCZ);
    }

    /**
     * Returns the block at the given world-space coordinates.
     * Returns {@link Block#AIR} for unloaded chunks.
     *
     * @param worldX world-space X
     * @param worldY world-space Y
     * @param worldZ world-space Z
     * @return block at that position, or {@link Block#AIR} if unloaded
     */
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

    /**
     * Sets the block at the given world-space coordinates and synchronously
     * rebuilds affected meshes. Synchronous here is acceptable — this is
     * player-triggered, affects at most 7 chunks, and happens infrequently.
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
        ChunkPos pos = new ChunkPos(cx, cy, cz);
        Chunk chunk = chunks.get(pos);
        if (chunk == null) return;

        int lx = Math.floorMod(worldX, Chunk.SIZE);
        int ly = Math.floorMod(worldY, Chunk.SIZE);
        int lz = Math.floorMod(worldZ, Chunk.SIZE);

        chunk.setBlock(lx, ly, lz, block);
        rebuildMeshSync(pos);

        if (lx == 0)              rebuildMeshSync(new ChunkPos(cx - 1, cy, cz));
        if (lx == Chunk.SIZE - 1) rebuildMeshSync(new ChunkPos(cx + 1, cy, cz));
        if (ly == 0)              rebuildMeshSync(new ChunkPos(cx, cy - 1, cz));
        if (ly == Chunk.SIZE - 1) rebuildMeshSync(new ChunkPos(cx, cy + 1, cz));
        if (lz == 0)              rebuildMeshSync(new ChunkPos(cx, cy, cz - 1));
        if (lz == Chunk.SIZE - 1) rebuildMeshSync(new ChunkPos(cx, cy, cz + 1));
    }

    /**
     * Renders all visible chunks. Chunks outside the camera frustum are skipped.
     *
     * @param shader           the currently bound shader program
     * @param projectionMatrix camera projection matrix
     * @param viewMatrix       camera view matrix
     * @return int[2] — [visible chunk count, total mesh count]
     */
    public int[] render(ShaderProgram shader, Matrix4f projectionMatrix, Matrix4f viewMatrix) {
        FrustumIntersection frustum = new FrustumIntersection(
            new Matrix4f(projectionMatrix).mul(viewMatrix)
        );

        int visible = 0;

        for (Map.Entry<ChunkPos, Mesh> entry : meshes.entrySet()) {
            ChunkPos pos  = entry.getKey();
            Mesh     mesh = entry.getValue();

            float minX = pos.worldX();
            float minY = pos.worldY();
            float minZ = pos.worldZ();
            if (!frustum.testAab(minX, minY, minZ,
                                 minX + Chunk.SIZE,
                                 minY + Chunk.SIZE,
                                 minZ + Chunk.SIZE)) continue;

            shader.setUniform("modelMatrix", new Matrix4f().translation(minX, minY, minZ));
            mesh.render();
            visible++;
        }

        return new int[]{ visible, meshes.size() };
    }

    /**
     * Shuts down the generation thread and releases all GPU resources.
     * Must be called on the main thread at shutdown.
     */
    public void cleanup() {
        generationExecutor.shutdownNow();
        for (Mesh mesh : meshes.values()) mesh.cleanup();
        meshes.clear();
        chunks.clear();
    }

    // -------------------------------------------------------------------------
    // Streaming internals
    // -------------------------------------------------------------------------

    /**
     * Uploads completed new chunks to the GPU (up to MAX_UPLOADS_PER_FRAME).
     * Stores block data, uploads the pre-built vertex array, then marks the
     * six neighbors dirty for async boundary correction.
     */
    private void drainPendingChunks(int centerCX, int centerCY, int centerCZ) {
        PendingChunk pending;
        int uploaded = 0;
        while (uploaded < MAX_UPLOADS_PER_FRAME && (pending = pendingChunks.poll()) != null) {
            inProgress.remove(pending.pos());
            if (!isInRange(pending.pos(), centerCX, centerCY, centerCZ)) continue;

            chunks.put(pending.pos(), pending.chunk());

            if (pending.vertices().length > 0) {
                meshes.put(pending.pos(), new Mesh(pending.vertices()));
            }

            // Mark neighbors dirty for async remeshing — they may have emitted
            // boundary faces toward this position when it was absent.
            // This replaces the old synchronous rebuildNeighbors() call.
            markNeighborsDirty(pending.pos());
            uploaded++;
        }
    }

    /**
     * Applies completed neighbor remesh results to the GPU (up to MAX_REMESHES_PER_FRAME).
     * Skips results for chunks that were unloaded while the remesh was in flight.
     */
    private void drainPendingRemeshes() {
        PendingRemesh pending;
        int applied = 0;
        while (applied < MAX_REMESHES_PER_FRAME && (pending = pendingRemeshes.poll()) != null) {
            remeshInProgress.remove(pending.pos());

            // Chunk may have been unloaded while remesh was in flight — discard
            if (!chunks.containsKey(pending.pos())) continue;

            Mesh old = meshes.remove(pending.pos());
            if (old != null) old.cleanup();

            if (pending.vertices().length > 0) {
                meshes.put(pending.pos(), new Mesh(pending.vertices()));
            }
            applied++;
        }
    }

    /**
     * Submits dirty mesh positions to the worker thread for remeshing.
     * Captures a neighbor snapshot on the main thread before submission.
     * Skips positions already in-flight to avoid redundant work.
     */
    private void processDirtyMeshes() {
        Iterator<ChunkPos> it = dirtyMeshes.iterator();
        while (it.hasNext()) {
            ChunkPos pos = it.next();
            it.remove();

            // Only remesh if chunk data still exists and isn't already being remeshed
            if (!chunks.containsKey(pos) || remeshInProgress.contains(pos)) continue;

            Chunk chunk = chunks.get(pos);
            Map<ChunkPos, Chunk> snapshot = captureNeighbors(pos);

            remeshInProgress.add(pos);
            generationExecutor.submit(() -> {
                float[] vertices = ChunkMesher.mesh(chunk, pos, snapshot);
                pendingRemeshes.offer(new PendingRemesh(pos, vertices));
            });
        }
    }

    /**
     * Queues generation + meshing for every in-range chunk that isn't loaded
     * or already in progress. Sorts by distance — closest chunks first.
     */
    private void scheduleNeededChunks(int centerCX, int centerCY, int centerCZ) {
        List<ChunkPos> needed = new ArrayList<>();

        for (int cx = centerCX - RENDER_DISTANCE_H; cx <= centerCX + RENDER_DISTANCE_H; cx++) {
            for (int cz = centerCZ - RENDER_DISTANCE_H; cz <= centerCZ + RENDER_DISTANCE_H; cz++) {
                int dx = cx - centerCX;
                int dz = cz - centerCZ;
                if (dx * dx + dz * dz > RENDER_DISTANCE_H * RENDER_DISTANCE_H) continue;

                for (int cy = centerCY - RENDER_DISTANCE_V; cy <= centerCY + RENDER_DISTANCE_V; cy++) {
                    if (cy < 0) continue;
                    ChunkPos pos = new ChunkPos(cx, cy, cz);
                    if (!chunks.containsKey(pos) && !inProgress.contains(pos)) {
                        needed.add(pos);
                    }
                }
            }
        }

        needed.sort(Comparator.comparingInt(p -> {
            int dx = p.x() - centerCX;
            int dy = p.y() - centerCY;
            int dz = p.z() - centerCZ;
            return dx * dx + dy * dy + dz * dz;
        }));

        for (ChunkPos pos : needed) {
            Map<ChunkPos, Chunk> neighborSnapshot = captureNeighbors(pos);
            inProgress.add(pos);
            generationExecutor.submit(() -> {
                Chunk chunk = terrainGenerator.generateChunk(pos);
                float[] vertices = ChunkMesher.mesh(chunk, pos, neighborSnapshot);
                pendingChunks.offer(new PendingChunk(pos, chunk, vertices));
            });
        }
    }

    /**
     * Unloads all chunks outside the render distance.
     * Marks their neighbors dirty so exposed boundary faces are rebuilt async.
     */
    private void unloadDistantChunks(int centerCX, int centerCY, int centerCZ) {
        List<ChunkPos> toUnload = new ArrayList<>();
        for (ChunkPos pos : chunks.keySet()) {
            if (!isInRange(pos, centerCX, centerCY, centerCZ)) toUnload.add(pos);
        }
        for (ChunkPos pos : toUnload) {
            chunks.remove(pos);
            Mesh mesh = meshes.remove(pos);
            if (mesh != null) mesh.cleanup();
            markNeighborsDirty(pos);
        }
    }

    private boolean isInRange(ChunkPos pos, int centerCX, int centerCY, int centerCZ) {
        int dx = pos.x() - centerCX;
        int dy = pos.y() - centerCY;
        int dz = pos.z() - centerCZ;
        return dx * dx + dz * dz <= RENDER_DISTANCE_H * RENDER_DISTANCE_H
            && Math.abs(dy) <= RENDER_DISTANCE_V;
    }

    // -------------------------------------------------------------------------
    // Mesh management
    // -------------------------------------------------------------------------

    /**
     * Adds the six face-adjacent neighbors of the given position to the dirty
     * mesh set. Only neighbors with loaded chunk data are added — no-op for
     * positions that don't exist in the chunk map.
     *
     * @param pos the chunk whose neighbors should be marked dirty
     */
    private void markNeighborsDirty(ChunkPos pos) {
        ChunkPos[] adjacent = {
            new ChunkPos(pos.x() - 1, pos.y(), pos.z()),
            new ChunkPos(pos.x() + 1, pos.y(), pos.z()),
            new ChunkPos(pos.x(), pos.y() - 1, pos.z()),
            new ChunkPos(pos.x(), pos.y() + 1, pos.z()),
            new ChunkPos(pos.x(), pos.y(), pos.z() - 1),
            new ChunkPos(pos.x(), pos.y(), pos.z() + 1),
        };
        for (ChunkPos n : adjacent) {
            if (chunks.containsKey(n)) dirtyMeshes.add(n);
        }
    }

    /**
     * Synchronously rebuilds the GPU mesh for the given position.
     * Only used by {@link #setBlock} — all other mesh rebuilds go through the
     * async dirty mesh path.
     *
     * @param pos the chunk position to rebuild
     */
    private void rebuildMeshSync(ChunkPos pos) {
        Chunk chunk = chunks.get(pos);
        if (chunk == null) return;

        Mesh old = meshes.remove(pos);
        if (old != null) old.cleanup();

        float[] vertices = ChunkMesher.mesh(chunk, pos, captureNeighbors(pos));
        if (vertices.length > 0) {
            meshes.put(pos, new Mesh(vertices));
        }
    }

    /**
     * Captures a snapshot of the six face-adjacent neighbor chunks.
     * Must be called on the main thread — reads from the live chunk map.
     *
     * @param pos the chunk to capture neighbors for
     * @return map of loaded neighbors — may be empty if none are loaded
     */
    private Map<ChunkPos, Chunk> captureNeighbors(ChunkPos pos) {
        Map<ChunkPos, Chunk> neighbors = new HashMap<>();
        ChunkPos[] adjacent = {
            new ChunkPos(pos.x() - 1, pos.y(), pos.z()),
            new ChunkPos(pos.x() + 1, pos.y(), pos.z()),
            new ChunkPos(pos.x(), pos.y() - 1, pos.z()),
            new ChunkPos(pos.x(), pos.y() + 1, pos.z()),
            new ChunkPos(pos.x(), pos.y(), pos.z() - 1),
            new ChunkPos(pos.x(), pos.y(), pos.z() + 1),
        };
        for (ChunkPos n : adjacent) {
            Chunk c = chunks.get(n);
            if (c != null) neighbors.put(n, c);
        }
        return neighbors;
    }
}