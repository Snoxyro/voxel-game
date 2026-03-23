package com.voxelgame.client;

import com.voxelgame.common.world.Block;
import com.voxelgame.common.world.BlockView;
import com.voxelgame.common.world.Chunk;
import com.voxelgame.common.world.ChunkPos;
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

    // Cross-thread queues for remote player events — written by Netty, drained by main thread
    private record SpawnData(int playerId, String username, float x, float y, float z) {}
    private record MoveData(int playerId, float x, float y, float z) {}

    // -------------------------------------------------------------------------
    // Chunk and mesh storage — main (GL) thread only
    // -------------------------------------------------------------------------
    private final Map<ChunkPos, Chunk> chunks = new HashMap<>();
    private final Map<ChunkPos, Mesh>  meshes = new HashMap<>();

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

    private final ExecutorService meshExecutor;

    /** Carrier for a chunk arrival from the network. Chunk is pre-converted from bytes. */
    private record PendingChunkData(ChunkPos pos, Chunk chunk) {}

    /** Carrier for a completed mesh build from a worker thread. */
    private record PendingMesh(ChunkPos pos, float[] vertices) {}

    /** Carrier for a server-authoritative block change. Applied on the main thread. */
    private record PendingBlockChange(int worldX, int worldY, int worldZ, int blockOrdinal) {}

    /**
     * Initializes GL-dependent resources. Must be called on the main thread after
     * the OpenGL context has been created — i.e. after Window.init().
     */
    public void initRenderResources() {
        remotePlayerRenderer = new RemotePlayerRenderer();
    }   

    /**
     * Creates a ClientWorld and starts the mesh worker thread pool.
     * Leaves one core for the main/render thread; uses all remaining for meshing.
     */
    public ClientWorld() {
        int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        meshExecutor = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(r, "client-mesher");
            t.setDaemon(true);
            return t;
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
     * @param blockData 4096 raw bytes from the server's chunk block array
     */
    public void queueChunkData(int cx, int cy, int cz, byte[] blockData) {
        Chunk chunk = Chunk.fromBytes(blockData);
        pendingChunkData.offer(new PendingChunkData(new ChunkPos(cx, cy, cz), chunk));
    }

    /**
     * Enqueues a server-authoritative block change for application on the main thread.
     * Thread-safe — called from the Netty I/O thread.
     *
     * @param worldX     world-space X
     * @param worldY     world-space Y
     * @param worldZ     world-space Z
     * @param blockOrdinal block ordinal (0 = AIR)
     */
    public void queueBlockChange(int worldX, int worldY, int worldZ, int blockOrdinal) {
        pendingBlockChanges.offer(new PendingBlockChange(worldX, worldY, worldZ, blockOrdinal));
    }

    /**
     * Enqueues a chunk unload request from the server for processing on the main thread.
     *
     * @param cx chunk-grid X
     * @param cy chunk-grid Y
     * @param cz chunk-grid Z
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
     */
    public void setSpawn(float x, float y, float z) {
        spawnX = x;
        spawnY = y;
        spawnZ = z;
    }

    /**
     * Enqueues a remote player spawn received from the server.
     * Thread-safe — called from the Netty I/O thread.
     */
    public void queueRemotePlayerSpawn(int playerId, String username, float x, float y, float z) {
        pendingSpawns.offer(new SpawnData(playerId, username, x, y, z));
    }

    /**
     * Enqueues a remote player position update received from the server.
     * Thread-safe — called from the Netty I/O thread.
     */
    public void queueRemotePlayerMove(int playerId, float x, float y, float z) {
        pendingMoves.offer(new MoveData(playerId, x, y, z));
    }

    /**
     * Enqueues a remote player despawn received from the server.
     * Thread-safe — called from the Netty I/O thread.
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
     */
    public void update() {
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

    private void drainPendingBlockChanges() {
        PendingBlockChange change;
        while ((change = pendingBlockChanges.poll()) != null) {
            Block block = Block.values()[change.blockOrdinal()];

            int cx = Math.floorDiv(change.worldX(), Chunk.SIZE);
            int cy = Math.floorDiv(change.worldY(), Chunk.SIZE);
            int cz = Math.floorDiv(change.worldZ(), Chunk.SIZE);
            ChunkPos pos = new ChunkPos(cx, cy, cz);

            Chunk chunk = chunks.get(pos);
            if (chunk == null) continue; // chunk not loaded client-side — ignore

            chunk.setBlock(
                Math.floorMod(change.worldX(), Chunk.SIZE),
                Math.floorMod(change.worldY(), Chunk.SIZE),
                Math.floorMod(change.worldZ(), Chunk.SIZE),
                block
            );

            // Dirty-mark this chunk and any face-adjacent neighbors the modified block touches.
            // A block at local coordinate 0 or SIZE-1 sits on a chunk boundary — the neighbor's
            // mesh needs updating too because it may have been emitting a face toward that block.
            dirtyMeshes.add(pos);
            markNeighborsDirty(pos);
        }
    }

    private void drainPendingChunkData() {
        PendingChunkData pending;
        while ((pending = pendingChunkData.poll()) != null) {
            ChunkPos pos = pending.pos();

            // If replacing an existing chunk (server resent it), dirty-mark neighbors
            // so their boundary faces are corrected even if the chunk data is the same.
            chunks.put(pos, pending.chunk());

            // Mark this chunk and its 6 face-adjacent neighbors dirty.
            // Neighbors need remeshing because their boundary faces toward this chunk
            // may have been treating it as air (if this is a new arrival).
            dirtyMeshes.add(pos);
            markNeighborsDirty(pos);
        }
    }

    private void drainPendingUnloads() {
        ChunkPos pos;
        while ((pos = pendingUnloads.poll()) != null) {
            chunks.remove(pos);
            Mesh mesh = meshes.remove(pos);
            if (mesh != null) mesh.cleanup(); // GL call — safe, we're on the main thread

            // Neighbors may have emitted faces toward this chunk; they need remeshing
            // to reveal the now-exposed boundary faces.
            markNeighborsDirty(pos);
        }
    }

    private void processDirtyMeshes() {
        Iterator<ChunkPos> it = dirtyMeshes.iterator();
        while (it.hasNext()) {
            ChunkPos pos = it.next();

            // Chunk was unloaded while dirty — no longer relevant
            if (!chunks.containsKey(pos)) {
                it.remove();
                continue;
            }

            // Already being meshed — leave the dirty mark. Once the in-flight result
            // drains and clears meshInProgress, this position is picked up next frame.
            if (meshInProgress.contains(pos)) continue;

            it.remove();

            Chunk chunk = chunks.get(pos);
            Map<ChunkPos, Chunk> neighborSnapshot = captureNeighbors(pos);

            meshInProgress.add(pos);
            final ChunkPos fPos = pos; // effectively final for lambda
            meshExecutor.submit(() -> {
                float[] vertices = ChunkMesher.mesh(chunk, fPos, neighborSnapshot);
                pendingMeshes.offer(new PendingMesh(fPos, vertices));
            });
        }
    }

    private void drainPendingMeshes() {
        PendingMesh pending;
        while ((pending = pendingMeshes.poll()) != null) {
            meshInProgress.remove(pending.pos());

            // Chunk may have been unloaded while meshing was in-flight — discard
            if (!chunks.containsKey(pending.pos())) continue;

            Mesh old = meshes.remove(pending.pos());
            if (old != null) old.cleanup();

            if (pending.vertices().length > 0) {
                meshes.put(pending.pos(), new Mesh(pending.vertices()));
            }
        }
    }

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
                    if (c != null) neighbors.put(n, c);
                }
            }
        }
        return neighbors;
    }

    // -------------------------------------------------------------------------
    // BlockView implementation — main thread only
    // -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     * Returns {@link Block#AIR} for chunks that haven't arrived from the server yet.
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
    // Rendering — main (GL) thread only
    // -------------------------------------------------------------------------

    /**
     * Renders all visible chunk meshes with frustum culling.
     * The shader must be bound and the texture array must be bound to unit 0 before calling.
     *
     * @param shader          the currently bound shader program
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
     */
    public void renderRemotePlayers(ShaderProgram shader) {
            if (remotePlayerRenderer == null) return;
            remotePlayerRenderer.render(shader, remotePlayers.values());
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Shuts down mesh worker threads and frees all GPU resources.
     * Must be called on the main (GL) thread at shutdown.
     */
    public void cleanup() {
        meshExecutor.shutdownNow();
        if (remotePlayerRenderer != null) remotePlayerRenderer.cleanup();
        for (Mesh mesh : meshes.values()) mesh.cleanup();
        meshes.clear();
        chunks.clear();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /** @return spawn X received from server, or 64.0 if not yet received */
    public float getSpawnX() { return spawnX; }

    /** @return spawn Y received from server, or 70.0 if not yet received */
    public float getSpawnY() { return spawnY; }

    /** @return spawn Z received from server, or 64.0 if not yet received */
    public float getSpawnZ() { return spawnZ; }
}