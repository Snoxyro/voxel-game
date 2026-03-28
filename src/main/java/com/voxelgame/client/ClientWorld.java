package com.voxelgame.client;

import com.voxelgame.common.world.BlockView;
import com.voxelgame.common.world.BlockRegistry;
import com.voxelgame.common.world.BlockType;
import com.voxelgame.common.world.Blocks;
import com.voxelgame.common.world.Chunk;
import com.voxelgame.common.world.ChunkPos;
import com.voxelgame.common.world.LightEngine;
import com.voxelgame.common.world.WorldTime;
import com.voxelgame.engine.Mesh;
import com.voxelgame.engine.ShaderProgram;
import com.voxelgame.game.ChunkMesher;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.*;

/**
 * Client-side world. Receives chunk data from the server via network packets,
 * builds GPU meshes for rendering, and answers block queries for physics and raycasting.
 *
 * <h3>Threading model</h3>
 * Three threads interact with this class:
 * <ul>
 *   <li><b>Netty I/O thread</b> — calls {@link #queueChunkData} and {@link #queueUnload}
 *       to enqueue arrivals into concurrent queues. Never touches the chunk/mesh maps.</li>
 *   <li><b>Mesh worker threads</b> — run {@link ChunkMesher#mesh} on submitted jobs
 *       and offer results into {@link #pendingMeshes}. Never touch GL or the maps.</li>
 *   <li><b>Main (GL) thread</b> — calls {@link #update()} to drain all queues and
 *       upload finalized meshes to the GPU. Owns the chunk/mesh maps exclusively.</li>
 * </ul>
 *
 * <h3>Mesh pipeline</h3>
 * When a chunk arrives:
 * <ol>
 *   <li>{@code queueChunkData} converts the raw bytes to a {@link Chunk} on the Netty
 *       thread and enqueues a {@link PendingChunkData} record.</li>
 *   <li>{@code drainPendingChunkData} (main thread) stores the chunk and dirty-marks
 *       it and its 6 face-adjacent neighbors for remeshing.</li>
 *   <li>{@code processDirtyMeshes} (main thread) captures a 26-neighbor snapshot and
 *       submits the chunk to a mesh worker thread.</li>
 *   <li>{@code drainPendingMeshes} (main thread) receives the completed vertex array
 *       and calls {@code new Mesh(vertices)} — the only GL call in the pipeline.</li>
 * </ol>
 * This mirrors the async architecture of the server-side {@link com.voxelgame.game.World}
 * exactly. All GL calls stay on the main thread; all CPU work runs on workers.
 */
public class ClientWorld implements BlockView {
    // Remote player state — main (GL) thread only
    private final Map<Integer, RemotePlayer> remotePlayers = new HashMap<>();
    private RemotePlayerRenderer remotePlayerRenderer;

    /** Client-side world time — updated by WorldTimePackets from the server. */
    private final WorldTime worldTime = new WorldTime();

    // Cross-thread queues for remote player events — written by Netty, drained by main thread
    private record SpawnData(int playerId, String username, float x, float y, float z) {}
    private record MoveData(int playerId, float x, float y, float z) {}

    private final java.util.concurrent.atomic.AtomicLong chunkSequence = new java.util.concurrent.atomic.AtomicLong();
    private final Map<ChunkPos, Long> arrivalTickets = new ConcurrentHashMap<>();

    // The server only sends chunks within this vertical radius
    private static final int RENDER_DISTANCE_V = 8;

    // Limits how many completed meshes are uploaded to the GPU per frame to prevent frame drops during heavy chunk updates
    private static final int MAX_MESH_UPLOADS_PER_FRAME = 40;

    // Limits how many tasks are sent to the thread pool to prevent the internal queue from backing up
    private static final int MAX_ACTIVE_MESH_TASKS = 128;
    
    private final Set<ChunkPos> dirtyMeshesSet = new HashSet<>();
    private final PriorityQueue<ChunkPos> dirtyMeshesQueue = new PriorityQueue<>(
        Comparator.comparingLong(p -> arrivalTickets.getOrDefault(p, Long.MAX_VALUE))
    );

    // -------------------------------------------------------------------------
    // Chunk and mesh storage — main (GL) thread only
    // -------------------------------------------------------------------------
    private final Map<ChunkPos, Chunk> chunks = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Mesh>  meshes = new HashMap<>();

    // Dedicated background threads for BFS calculations
    private final Queue<Mesh> meshesToCleanup = new ArrayDeque<>();
    private final ThreadPoolExecutor meshExecutor;  
    private final Set<ChunkPos> meshScheduled = new HashSet<>();

    // -------------------------------------------------------------------------
    // Cross-thread queues — written by Netty/worker threads, drained by main thread
    // -------------------------------------------------------------------------
    private final ConcurrentLinkedQueue<PendingChunkData> pendingChunkData = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ChunkPos>         pendingUnloads   = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingMesh>      pendingMeshes    = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingBlockChange> pendingBlockChanges = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SpawnData>  pendingSpawns   = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<MoveData>   pendingMoves    = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Integer>    pendingDespawns = new ConcurrentLinkedQueue<>();

    // -------------------------------------------------------------------------
    // Mesh scheduling — main thread only
    // -------------------------------------------------------------------------

    /**
     * Chunks that need their mesh rebuilt. Uses LinkedHashSet for FIFO insertion
     * order — the first dirty chunk is meshed first, giving a consistent pop-in pattern.
     */
    private final Set<ChunkPos> dirtyMeshes    = new LinkedHashSet<>();

    /**
     * Chunks currently being meshed on a worker thread.
     * Prevents the same position being submitted twice while a result is in-flight.
     */
    private final Set<ChunkPos> meshInProgress = new HashSet<>();

    // -------------------------------------------------------------------------
    // Spawn position — set from LoginSuccessPacket, read by GameLoop
    // -------------------------------------------------------------------------
    private volatile float spawnX = 64.0f;
    private volatile float spawnY = 70.0f;
    private volatile float spawnZ = 64.0f;

    private int currentCenterCY = 0;

    /** Carrier for a chunk arrival from the network. Chunk is pre-converted from bytes. */
    private record PendingChunkData(ChunkPos pos, Chunk chunk) {}

    /** Carrier for a completed mesh build from a worker thread. */
    private record PendingMesh(ChunkPos pos, float[] vertices) {}

    /** Carrier for a server-authoritative block change. Applied on the main thread. */
    private record PendingBlockChange(int worldX, int worldY, int worldZ, int blockId) {}

    // Promotes a chunk into the meshing queue once its full 3x3x3 collar is present.
    private void promoteToMeshIfReady(ChunkPos pos) {
        if (!chunks.containsKey(pos) || meshScheduled.contains(pos)) return;
        if (canMesh(pos)) {
            meshScheduled.add(pos);
            enqueueDirtyMesh(pos);
        }
    }

    /**
     * Initializes GL-dependent resources. Must be called on the main thread after
     * the OpenGL context has been created — i.e. after Window.init().
     *
     * @thread GL-main
     * @gl-state creates GL resources for remote player rendering
     */
    public void initRenderResources() {
        remotePlayerRenderer = new RemotePlayerRenderer();
    }   

    /**
     * Creates a ClientWorld and starts the mesh worker thread pool.
     * Leaves one core for the main/render thread; uses all remaining for meshing.
     *
     * @thread GL-main (construction thread)
     * @gl-state n/a
     */
    public ClientWorld() {
        int available = Runtime.getRuntime().availableProcessors();
        int workerCount = Math.max(1, (available - 1) / 2);

        meshExecutor = new ThreadPoolExecutor(workerCount, workerCount, 0L, TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "client-mesher"); t.setDaemon(true); return t;
        });
    }

    // -------------------------------------------------------------------------
    // Network callbacks — called from Netty I/O thread
    // -------------------------------------------------------------------------

    /**
     * Enqueues a chunk received from the server for processing on the main thread.
     * Converts raw bytes to a {@link Chunk} here on the Netty thread to reduce
     * main-thread load.
     *
     * @param cx        chunk-grid X
     * @param cy        chunk-grid Y
     * @param cz        chunk-grid Z
     * @param blockData Chunk.SERIALIZED_SIZE raw bytes from the server's chunk block array
     * @thread netty-io
     * @gl-state n/a
     */
    public void queueChunkData(int cx, int cy, int cz, byte[] blockData) {
        Chunk chunk = Chunk.fromBytes(blockData);
        ChunkPos pos = new ChunkPos(cx, cy, cz);
        arrivalTickets.put(pos, chunkSequence.getAndIncrement());
        pendingChunkData.offer(new PendingChunkData(pos, chunk));
    }

    /**
     * Enqueues a server-authoritative block change for application on the main thread.
     * Thread-safe — called from the Netty I/O thread.
     *
     * @param worldX     world-space X
     * @param worldY     world-space Y
     * @param worldZ     world-space Z
     * @param blockId block registry ID (0 = AIR)
      * @thread netty-io
      * @gl-state n/a
     */
    public void queueBlockChange(int worldX, int worldY, int worldZ, int blockId) {
        pendingBlockChanges.offer(new PendingBlockChange(worldX, worldY, worldZ, blockId));
    }

    /**
     * Enqueues a chunk unload request from the server for processing on the main thread.
     *
     * @param cx chunk-grid X
     * @param cy chunk-grid Y
     * @param cz chunk-grid Z
      * @thread netty-io
      * @gl-state n/a
     */
    public void queueUnload(int cx, int cy, int cz) {
        pendingUnloads.offer(new ChunkPos(cx, cy, cz));
    }

    /**
     * Stores the spawn position received from the server's {@code LoginSuccessPacket}.
     * Volatile — written from Netty thread, read from main thread.
     *
     * @param x spawn X
     * @param y spawn Y (feet)
     * @param z spawn Z
      * @thread netty-io
      * @gl-state n/a
     */
    public void setSpawn(float x, float y, float z) {
        spawnX = x;
        spawnY = y;
        spawnZ = z;
    }

    /**
     * Enqueues a remote player spawn received from the server.
     * Thread-safe — called from the Netty I/O thread.
     *
     * @thread netty-io
     * @gl-state n/a
     */
    public void queueRemotePlayerSpawn(int playerId, String username, float x, float y, float z) {
        pendingSpawns.offer(new SpawnData(playerId, username, x, y, z));
    }

    /**
     * Enqueues a remote player position update received from the server.
     * Thread-safe — called from the Netty I/O thread.
     *
     * @thread netty-io
     * @gl-state n/a
     */
    public void queueRemotePlayerMove(int playerId, float x, float y, float z) {
        pendingMoves.offer(new MoveData(playerId, x, y, z));
    }

    /**
     * Enqueues a remote player despawn received from the server.
     * Thread-safe — called from the Netty I/O thread.
     *
     * @thread netty-io
     * @gl-state n/a
     */
    public void queueRemotePlayerDespawn(int playerId) {
        pendingDespawns.offer(playerId);
    }

    // -------------------------------------------------------------------------
    // Main thread update — called once per frame from GameLoop
    // -------------------------------------------------------------------------

    /**
     * Drains all pending queues and processes the mesh pipeline.
     * Must be called on the main (GL) thread every frame.
     *
     * <p>Order matters:
     * <ol>
     *   <li>Drain chunk arrivals — adds to chunks map, dirties neighbors.</li>
     *   <li>Drain unloads — removes from maps, frees GPU memory.</li>
     *   <li>Submit dirty chunks to mesh workers (with neighbor snapshots).</li>
     *   <li>Drain completed meshes — uploads to GPU.</li>
     * </ol>
    *
    * @thread GL-main
    * @gl-state creates/deletes Mesh GPU resources during queue drains
    * @see #drainPendingMeshes()
     */
    public void update(float cameraY) {
        // Keep track of the chunk the player is currently standing in
        currentCenterCY = Math.floorDiv((int) cameraY, Chunk.SIZE);

        drainPendingPlayerEvents();
        drainPendingBlockChanges();
        drainPendingChunkData();
        drainPendingUnloads();
        processDirtyMeshes();
        drainPendingMeshes();
    }

    // -------------------------------------------------------------------------
    // Internal pipeline steps — main thread only
    // -------------------------------------------------------------------------

    // Applies queued remote-player spawn/move/despawn events on the GL thread.
    private void drainPendingPlayerEvents() {
        SpawnData spawn;
        while ((spawn = pendingSpawns.poll()) != null) {
            remotePlayers.put(spawn.playerId(),
                new RemotePlayer(spawn.playerId(), spawn.username(), spawn.x(), spawn.y(), spawn.z()));
            System.out.printf("[Client] Remote player '%s' (id=%d) spawned%n",
                spawn.username(), spawn.playerId());
        }

        MoveData move;
        while ((move = pendingMoves.poll()) != null) {
            RemotePlayer rp = remotePlayers.get(move.playerId());
            if (rp != null) rp.updatePosition(move.x(), move.y(), move.z());
        }

        Integer despawnId;
        while ((despawnId = pendingDespawns.poll()) != null) {
            RemotePlayer rp = remotePlayers.remove(despawnId);
            if (rp != null) System.out.printf("[Client] Remote player '%s' (id=%d) despawned%n",
                rp.getUsername(), despawnId);
        }
    }

    // Applies server-authoritative block changes received on Netty threads.
    private void drainPendingBlockChanges() {
        PendingBlockChange change;
        while ((change = pendingBlockChanges.poll()) != null) {
            BlockType block = BlockRegistry.getById(change.blockId());
            applyBlockChange(change.worldX(), change.worldY(), change.worldZ(), block);
        }
    }

    // Stores arrived chunks, then tries to unlock meshing for the chunk and its neighborhood.
    private void drainPendingChunkData() {
        PendingChunkData pending;
        while ((pending = pendingChunkData.poll()) != null) {
            ChunkPos pos = pending.pos();
            chunks.put(pos, pending.chunk());

            // The chunk arrived fully lit. Check if it unlocks meshing for itself or neighbors.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        promoteToMeshIfReady(new ChunkPos(pos.x() + dx, pos.y() + dy, pos.z() + dz));
                    }
                }
            }
        }
    }

    // Unloads chunks and defers GPU mesh destruction in bounded batches per frame.
    private void drainPendingUnloads() {
        ChunkPos pos;
        while ((pos = pendingUnloads.poll()) != null) {
            chunks.remove(pos);
            arrivalTickets.remove(pos);
            meshScheduled.remove(pos);
            
            if (dirtyMeshesSet.remove(pos)) {
                dirtyMeshesQueue.remove(pos);
            }

            Mesh mesh = meshes.remove(pos);
            if (mesh != null) meshesToCleanup.add(mesh);
        }

        int glCleanups = 0;
        Mesh m;
        while (glCleanups < 100 && (m = meshesToCleanup.poll()) != null) {
            m.cleanup();
            glCleanups++;
        }
    }

    // Requires full 26-neighbor availability unless a neighbor is beyond vertical world limits.
    private boolean canMesh(ChunkPos pos) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    ChunkPos n = new ChunkPos(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                    if (!chunks.containsKey(n)) {
                        // Bypass missing chunks if they are beyond bedrock or the vertical render distance
                        if (n.y() < 0 || Math.abs(n.y() - currentCenterCY) > RENDER_DISTANCE_V) continue;
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Marks every currently loaded chunk as needing a full mesh rebuild.
     *
     * <p>Called when a setting that affects mesh content changes globally —
     * currently only the AO toggle. All chunk positions are added to the dirty
     * queue; the worker thread pool will rebuild them over the following frames
     * at the normal async rate.
     *
     * <p>Must be called on the GL thread (reads the chunk map).
     *
     * @thread GL-main
     * @gl-state n/a (schedules future mesh uploads)
     */
    public void invalidateAllMeshes() {
        for (ChunkPos p : chunks.keySet()) {
            enqueueDirtyMesh(p);
        }
    }

    // Captures immutable neighbor snapshots and dispatches pure CPU meshing to workers.
    private void processDirtyMeshes() {
        List<ChunkPos> deferred = new ArrayList<>();
        
        while (meshInProgress.size() < MAX_ACTIVE_MESH_TASKS) {
            ChunkPos pos = dirtyMeshesQueue.poll();
            if (pos == null) break; 
            
            dirtyMeshesSet.remove(pos);

            if (!chunks.containsKey(pos)) continue;

            // CRITICAL FIX: If a chunk is busy, defer it so the dirty flag isn't permanently deleted
            if (meshInProgress.contains(pos)) {
                deferred.add(pos);
                continue;
            }

            Chunk chunk = chunks.get(pos);
            Map<ChunkPos, Chunk> neighborSnapshot = captureNeighbors(pos);

            meshInProgress.add(pos);
            final ChunkPos fPos = pos; 
            long ticket = arrivalTickets.getOrDefault(pos, Long.MAX_VALUE);
            
            meshExecutor.execute(new PriorityTask(ticket, () -> {
                float[] vertices = ChunkMesher.mesh(chunk, fPos, neighborSnapshot);
                pendingMeshes.offer(new PendingMesh(fPos, vertices));
            }));
        }
        
        // Put busy chunks back in line for the next frame
        for (ChunkPos p : deferred) {
            enqueueDirtyMesh(p);
        }
    }

    // Uploads completed mesh vertices to GPU on the GL thread.
    private void drainPendingMeshes() {
        int uploaded = 0;
        PendingMesh pending;
        while (uploaded < MAX_MESH_UPLOADS_PER_FRAME && (pending = pendingMeshes.poll()) != null) {
            meshInProgress.remove(pending.pos());

            if (!chunks.containsKey(pending.pos())) continue;

            Mesh old = meshes.remove(pending.pos());
            if (old != null) old.cleanup();

            if (pending.vertices().length > 0) {
                meshes.put(pending.pos(), new Mesh(pending.vertices()));
            }
            uploaded++;
        }
    }

    /**
     * Captures a snapshot of all 26 adjacent neighbor chunks (face, edge, and corner).
     * AO sampling requires diagonal neighbors, so all 26 are needed — not just 6.
     * Snapshot is safe to hand to a worker thread (immutable copy of references).
     */
    private Map<ChunkPos, Chunk> captureNeighbors(ChunkPos pos) {
        Map<ChunkPos, Chunk> neighbors = new HashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    ChunkPos n = new ChunkPos(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                    Chunk c = chunks.get(n);
                    if (c != null) {
                        neighbors.put(n, c);
                    }
                }
            }
        }
        return neighbors;
    }

    /**
     * Applies a world time update received from the server.
     * Called from the Netty I/O thread — safe due to volatile field in WorldTime.
     *
     * @param tick the server-authoritative world tick
      * @thread netty-io
      * @gl-state n/a
     */
    public void applyWorldTime(long tick) {
        worldTime.setWorldTick(tick);
    }

    /**
     * Returns the ambient light factor for the current time of day.
     * Computed from the most recently received server tick.
     *
     * @return ambient factor in [0.15, 1.0]
      * @thread GL-main
      * @gl-state n/a
     */
    public float getAmbientFactor() {
        return worldTime.getAmbientFactor();
    }

    /**
     * Returns the sky RGB colour for the current time of day.
     * Used to set glClearColor before each frame.
     *
     * @return float[3] RGB in [0.0, 1.0]
      * @thread GL-main
      * @gl-state n/a
     */
    public float[] getSkyColor() {
        return worldTime.getSkyColor();
    }

    // -------------------------------------------------------------------------
    // BlockView implementation — main thread only
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     * Returns {@link Blocks#AIR} for chunks that haven't arrived from the server yet.
      * @thread GL-main
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
    // Rendering — main (GL) thread only
    // -------------------------------------------------------------------------

    /**
     * Renders all currently meshed chunks that pass frustum culling.
     *
     * @param shader active world shader
     * @param projectionMatrix projection matrix for the frame
     * @param viewMatrix camera view matrix for the frame
     * @return int[2] where index 0 = rendered chunk count, index 1 = total meshed chunks
     * @thread GL-main
     * @gl-state shader=bound (required by caller), issues Mesh draw calls
     */
    public int[] render(ShaderProgram shader, Matrix4f projectionMatrix, Matrix4f viewMatrix) {
        FrustumIntersection frustum = new FrustumIntersection(
            new Matrix4f(projectionMatrix).mul(viewMatrix)
        );

        int visible = 0;
        for (Map.Entry<ChunkPos, Mesh> entry : meshes.entrySet()) {
            ChunkPos pos  = entry.getKey();
            float minX = pos.worldX(), minY = pos.worldY(), minZ = pos.worldZ();

            if (!frustum.testAab(minX, minY, minZ,
                                 minX + Chunk.SIZE, minY + Chunk.SIZE, minZ + Chunk.SIZE)) continue;

            shader.setUniform("modelMatrix", new Matrix4f().translation(minX, minY, minZ));
            entry.getValue().render();
            visible++;
        }

        return new int[]{ visible, meshes.size() };
    }

    /**
     * Renders all remote players. The main shader must be bound with
     * {@code useTexture=false} before calling.
     *
     * @param shader the currently bound shader program
     * @thread GL-main
     * @gl-state shader=bound, useTexture=false
     */
    public void renderRemotePlayers(ShaderProgram shader) {
        if (remotePlayerRenderer == null) return;
        remotePlayerRenderer.render(shader, remotePlayers.values());
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Unloads all chunks and releases all GL mesh resources. Must be called on the GL thread. Used when returning to the main menu.
      *
      * @thread GL-main
      * @gl-state deletes Mesh GPU resources
     */
    public void reset() {
        pendingChunkData.clear();
        pendingUnloads.clear();
        pendingMeshes.clear();
        pendingBlockChanges.clear();

        meshInProgress.clear();
        dirtyMeshes.clear();

        for (Mesh mesh : meshes.values()) {
            mesh.cleanup();
        }
        meshes.clear();
        chunks.clear();

        spawnX = 64.0f;
        spawnY = 70.0f;
        spawnZ = 64.0f;
    }

    /**
     * Stops mesh workers and releases all owned GPU resources.
     *
     * @thread GL-main
     * @gl-state deletes Mesh/RemotePlayerRenderer GPU resources
     */
    public void cleanup() {
        meshExecutor.shutdownNow();
        if (remotePlayerRenderer != null) remotePlayerRenderer.cleanup();
        for (Mesh mesh : meshes.values()) mesh.cleanup();
        meshes.clear();
        chunks.clear();
    }

    /**
     * Public endpoint for Client-Side Prediction. Instantly updates local memory,
     * recalculates light, and rebuilds the mesh without waiting for the server.
     *
     * @param worldX world-space block X
     * @param worldY world-space block Y
     * @param worldZ world-space block Z
     * @param block target block type to apply locally
     * @thread GL-main
     * @gl-state may create/delete Mesh GPU resources synchronously
     */
    public void setBlock(int worldX, int worldY, int worldZ, BlockType block) {
        applyBlockChange(worldX, worldY, worldZ, block);
    }

    // Core local mutation path used by both prediction and server-authoritative updates.
    private void applyBlockChange(int worldX, int worldY, int worldZ, BlockType block) {
        int cx = Math.floorDiv(worldX, Chunk.SIZE);
        int cy = Math.floorDiv(worldY, Chunk.SIZE);
        int cz = Math.floorDiv(worldZ, Chunk.SIZE);
        ChunkPos pos = new ChunkPos(cx, cy, cz);

        Chunk chunk = chunks.get(pos);
        if (chunk == null) return;

        int lx = Math.floorMod(worldX, Chunk.SIZE);
        int ly = Math.floorMod(worldY, Chunk.SIZE);
        int lz = Math.floorMod(worldZ, Chunk.SIZE);

        BlockType oldBlock = chunk.getBlock(lx, ly, lz);
        
        // PREDICTION BYPASS: If the block is already the requested type, ignore it.
        // This prevents double-meshing when the server echoes back our predicted action.
        if (oldBlock.getId() == block.getId()) return; 

        chunk.setBlock(lx, ly, lz, block);

        Set<ChunkPos> lightChanged;
        boolean wasOpaque = oldBlock.isOpaque();
        boolean isOpaque  = block.isOpaque();

        if (!wasOpaque && isOpaque) {
            lightChanged = LightEngine.propagateAfterPlace(chunks, worldX, worldY, worldZ);
        } else if (wasOpaque && !isOpaque) {
            lightChanged = LightEngine.propagateAfterBreak(chunks, worldX, worldY, worldZ);
        } else {
            lightChanged = Collections.emptySet();
        }

        // Group the central chunk, any chunk with altered light, and their immediate physical neighbors
        Set<ChunkPos> syncMeshGroup = new HashSet<>(lightChanged);
        syncMeshGroup.add(pos);

        int[][] dirs = {{1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}};
        Set<ChunkPos> boundaryNeighbors = new HashSet<>();
        for (ChunkPos lp : syncMeshGroup) {
            for (int[] d : dirs) {
                boundaryNeighbors.add(new ChunkPos(lp.x() + d[0], lp.y() + d[1], lp.z() + d[2]));
            }
        }
        syncMeshGroup.addAll(boundaryNeighbors);

        // Mesh all affected chunks synchronously on the main thread to prevent the "see-through" visual gap
        for (ChunkPos p : syncMeshGroup) {
            Chunk c = chunks.get(p);
            if (c == null) continue;

            pendingMeshes.removeIf(pending -> pending.pos().equals(p));

            Map<ChunkPos, Chunk> snap = captureNeighbors(p);
            float[] verts = ChunkMesher.mesh(c, p, snap);

            Mesh old = meshes.remove(p);
            if (old != null) old.cleanup();

            if (verts.length > 0) meshes.put(p, new Mesh(verts));

            // Clean these from the background queues so the worker pool ignores them
            dirtyMeshes.remove(p);
            dirtyMeshesQueue.remove(p);
            meshInProgress.remove(p);
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /**
     * Returns the latest spawn X received from the server.
     *
     * @return spawn X, or default 64.0 if not yet received
     * @thread any
     */
    public float getSpawnX() { return spawnX; }

    /**
     * Returns the latest spawn Y received from the server.
     *
     * @return spawn Y, or default 70.0 if not yet received
     * @thread any
     */
    public float getSpawnY() { return spawnY; }

    /**
     * Returns the latest spawn Z received from the server.
     *
     * @return spawn Z, or default 64.0 if not yet received
     * @thread any
     */
    public float getSpawnZ() { return spawnZ; }

    // Keeps the dedupe set and priority queue in sync for dirty mesh scheduling.
    private void enqueueDirtyMesh(ChunkPos pos) {
        if (dirtyMeshesSet.add(pos)) {
            dirtyMeshesQueue.add(pos);
        }
    }

    // Orders worker tasks by chunk arrival ticket to stabilize visible pop-in ordering.
    private static class PriorityTask implements Runnable, Comparable<PriorityTask> {
        final long ticket;
        final Runnable action;

        PriorityTask(long ticket, Runnable action) {
            this.ticket = ticket;
            this.action = action;
        }

        @Override
        public void run() { action.run(); }

        @Override
        public int compareTo(PriorityTask o) {
            return Long.compare(this.ticket, o.ticket);
        }
    }
}