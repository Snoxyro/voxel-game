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
 * Terrain generation is CPU-heavy and runs on a dedicated background thread.
 * Everything that touches OpenGL (mesh build, GPU upload, neighbor rebuilds)
 * runs on the main thread. The handoff point is {@link #pendingChunks} — a
 * lock-free queue that the worker writes to and the main thread drains each tick.
 *
 * <h3>Multiplayer note</h3>
 * This class is server-side logic. When multiplayer is added, it will move into
 * a Server class and {@link #update} will accept a collection of viewer positions
 * (one per connected player) rather than a single position. {@code GameLoop} will
 * become the client-side rendering loop. The seam is here.
 */
public class World {

    /**
     * Horizontal chunk load radius around the viewer in chunk units.
     * Chunks within this circular radius on the XZ plane are loaded.
     */
    private static final int RENDER_DISTANCE_H = 16;

    /**
     * Vertical chunk load radius around the viewer in chunk units.
     * Chunks within this many chunk-heights above or below the viewer are loaded.
     */
    private static final int RENDER_DISTANCE_V = 8;

    /** Maps chunk grid positions to their block data. */
    private final Map<ChunkPos, Chunk> chunks = new HashMap<>();

    /** Maps chunk grid positions to their GPU meshes. */
    private final Map<ChunkPos, Mesh> meshes = new HashMap<>();

    /**
     * Positions currently being generated on the worker thread.
     * Synchronized set — written from both the main thread (on schedule) and
     * read from the main thread (on drain), but also checked before scheduling.
     * Using a synchronized set is sufficient since all reads and writes happen
     * on the main thread except the offer() in the worker lambda, which only
     * writes to pendingChunks, not inProgress directly.
     */
    private final Set<ChunkPos> inProgress = new HashSet<>();

    /**
     * Completed (chunk data, position) pairs waiting to be uploaded to the GPU.
     * Written by the worker thread, drained by the main thread each tick.
     * ConcurrentLinkedQueue is lock-free and safe for this single-producer,
     * single-consumer pattern.
     */
    private final ConcurrentLinkedQueue<PendingChunk> pendingChunks = new ConcurrentLinkedQueue<>();

    /**
     * Single background thread for terrain generation.
     * Marked daemon so it doesn't prevent JVM shutdown if cleanup() isn't called.
     * Will become a thread pool when parallel generation is needed.
     */
    private final ExecutorService generationExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chunk-generator");
        t.setDaemon(true);
        return t;
    });

    private final TerrainGenerator terrainGenerator;

    /**
     * Carrier for a chunk that finished generating on the worker thread.
     * Held in pendingChunks until the main thread uploads it.
     */
    private record PendingChunk(ChunkPos pos, Chunk chunk) {}

    /**
     * Creates a World with the given world seed.
     * The seed is passed to the terrain generator — same seed always produces
     * identical terrain.
     *
     * @param seed world seed
     */
    public World(long seed) {
        this.terrainGenerator = new TerrainGenerator(seed);
    }

    /**
     * Drives chunk streaming for a single viewer position. Call once per tick
     * on the main thread.
     *
     * <p>Order of operations each tick:
     * <ol>
     *   <li>Drain the pending queue — upload any chunks the worker finished.</li>
     *   <li>Schedule generation for chunks in range that aren't loaded or queued.</li>
     *   <li>Unload chunks that have moved out of range.</li>
     * </ol>
     *
     * <p>In multiplayer this will accept a {@code Collection<Vector3f>} of viewer
     * positions (one per connected player) and compute the union of their load areas.
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
        scheduleNeededChunks(centerCX, centerCY, centerCZ);
        unloadDistantChunks(centerCX, centerCY, centerCZ);
    }

    /**
     * Returns the block at the given world-space coordinates.
     * Returns {@link Block#AIR} if the coordinates fall outside any loaded chunk.
     *
     * <p>Uses {@code Math.floorDiv} / {@code Math.floorMod} so negative coordinates
     * resolve correctly — world X=-1 belongs to chunk X=-1 (local X=15), not chunk 0.
     *
     * @param worldX world-space X
     * @param worldY world-space Y
     * @param worldZ world-space Z
     * @return the block at that position, or {@link Block#AIR} if unloaded
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
     * Sets the block at the given world-space coordinates. Rebuilds the mesh for
     * the affected chunk and any face-adjacent neighbors whose boundary faces are
     * now stale. Does nothing if the coordinates fall outside any loaded chunk.
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
        rebuildMesh(pos);

        if (lx == 0)              rebuildMesh(new ChunkPos(cx - 1, cy, cz));
        if (lx == Chunk.SIZE - 1) rebuildMesh(new ChunkPos(cx + 1, cy, cz));
        if (ly == 0)              rebuildMesh(new ChunkPos(cx, cy - 1, cz));
        if (ly == Chunk.SIZE - 1) rebuildMesh(new ChunkPos(cx, cy + 1, cz));
        if (lz == 0)              rebuildMesh(new ChunkPos(cx, cy, cz - 1));
        if (lz == Chunk.SIZE - 1) rebuildMesh(new ChunkPos(cx, cy, cz + 1));
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
     * Uploads any chunks the worker thread finished generating.
     * Discards chunks that moved out of range while being generated.
     */
    private void drainPendingChunks(int centerCX, int centerCY, int centerCZ) {
        PendingChunk pending;
        while ((pending = pendingChunks.poll()) != null) {
            inProgress.remove(pending.pos());
            if (isInRange(pending.pos(), centerCX, centerCY, centerCZ)) {
                addChunk(pending.pos(), pending.chunk());
            }
        }
    }

    /**
     * Queues generation for every chunk in range that isn't loaded or already
     * being generated. Sorts candidates by distance so the closest chunks
     * are generated first — gives a better visual pop-in experience.
     */
    private void scheduleNeededChunks(int centerCX, int centerCY, int centerCZ) {
        List<ChunkPos> needed = new ArrayList<>();

        for (int cx = centerCX - RENDER_DISTANCE_H; cx <= centerCX + RENDER_DISTANCE_H; cx++) {
            for (int cz = centerCZ - RENDER_DISTANCE_H; cz <= centerCZ + RENDER_DISTANCE_H; cz++) {
                int dx = cx - centerCX;
                int dz = cz - centerCZ;
                // Circular horizontal check — skip corners of the square range
                if (dx * dx + dz * dz > RENDER_DISTANCE_H * RENDER_DISTANCE_H) continue;

                for (int cy = centerCY - RENDER_DISTANCE_V; cy <= centerCY + RENDER_DISTANCE_V; cy++) {
                    if (cy < 0) continue; // nothing exists below world y=0
                    ChunkPos pos = new ChunkPos(cx, cy, cz);
                    if (!chunks.containsKey(pos) && !inProgress.contains(pos)) {
                        needed.add(pos);
                    }
                }
            }
        }

        // Closest chunks first — better pop-in experience
        needed.sort(Comparator.comparingInt(p -> {
            int dx = p.x() - centerCX;
            int dy = p.y() - centerCY;
            int dz = p.z() - centerCZ;
            return dx * dx + dy * dy + dz * dz;
        }));

        for (ChunkPos pos : needed) {
            inProgress.add(pos);
            generationExecutor.submit(() -> {
                Chunk chunk = terrainGenerator.generateChunk(pos);
                pendingChunks.offer(new PendingChunk(pos, chunk));
            });
        }
    }

    /**
     * Unloads all chunks that are now outside the render distance.
     * Rebuilds neighbor meshes after each unload so they re-expose
     * their boundary faces.
     */
    private void unloadDistantChunks(int centerCX, int centerCY, int centerCZ) {
        List<ChunkPos> toUnload = new ArrayList<>();
        for (ChunkPos pos : chunks.keySet()) {
            if (!isInRange(pos, centerCX, centerCY, centerCZ)) {
                toUnload.add(pos);
            }
        }
        for (ChunkPos pos : toUnload) {
            chunks.remove(pos);
            Mesh mesh = meshes.remove(pos);
            if (mesh != null) mesh.cleanup();
            // Neighbors now have exposed boundary faces — rebuild them
            rebuildNeighbors(pos);
        }
    }

    /**
     * Returns true if the given chunk position is within load range of the viewer.
     *
     * @param pos      chunk to test
     * @param centerCX viewer's chunk X
     * @param centerCY viewer's chunk Y
     * @param centerCZ viewer's chunk Z
     */
    private boolean isInRange(ChunkPos pos, int centerCX, int centerCY, int centerCZ) {
        int dx = pos.x() - centerCX;
        int dy = pos.y() - centerCY;
        int dz = pos.z() - centerCZ;
        return dx * dx + dz * dz <= RENDER_DISTANCE_H * RENDER_DISTANCE_H
            && Math.abs(dy) <= RENDER_DISTANCE_V;
    }

    // -------------------------------------------------------------------------
    // Chunk registration and mesh management
    // -------------------------------------------------------------------------

    /**
     * Registers a chunk, builds its mesh, and rebuilds the six face-adjacent
     * neighbors that may have emitted stale boundary faces when this position
     * was absent.
     */
    private void addChunk(ChunkPos pos, Chunk chunk) {
        chunks.put(pos, chunk);
        rebuildMesh(pos);
        rebuildNeighbors(pos);
    }

    /**
     * Rebuilds meshes for all six face-adjacent neighbors of the given position.
     * No-op for neighbors that have no chunk data.
     */
    private void rebuildNeighbors(ChunkPos pos) {
        rebuildMesh(new ChunkPos(pos.x() - 1, pos.y(), pos.z()));
        rebuildMesh(new ChunkPos(pos.x() + 1, pos.y(), pos.z()));
        rebuildMesh(new ChunkPos(pos.x(), pos.y() - 1, pos.z()));
        rebuildMesh(new ChunkPos(pos.x(), pos.y() + 1, pos.z()));
        rebuildMesh(new ChunkPos(pos.x(), pos.y(), pos.z() - 1));
        rebuildMesh(new ChunkPos(pos.x(), pos.y(), pos.z() + 1));
    }

    /**
     * Regenerates the GPU mesh for the chunk at the given position.
     * Deletes the existing mesh first if one exists.
     * No-op if no chunk data exists at this position.
     */
    private void rebuildMesh(ChunkPos pos) {
        Chunk chunk = chunks.get(pos);
        if (chunk == null) return;

        Mesh old = meshes.remove(pos);
        if (old != null) old.cleanup();

        float[] vertices = ChunkMesher.mesh(chunk, pos, this);
        if (vertices.length > 0) {
            meshes.put(pos, new Mesh(vertices));
        }
    }
}