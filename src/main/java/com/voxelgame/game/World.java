package com.voxelgame.game;

import com.voxelgame.engine.Mesh;
import com.voxelgame.engine.ShaderProgram;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds all loaded chunks and their corresponding GPU meshes.
 * Responsible for chunk registration, mesh generation, rendering,
 * and resource cleanup.
 */
public class World {

    /** Maps chunk grid positions to their block data. */
    private final Map<ChunkPos, Chunk> chunks = new HashMap<>();

    /** Maps chunk grid positions to their GPU meshes. */
    private final Map<ChunkPos, Mesh> meshes = new HashMap<>();

    /**
     * Adds a chunk at the given grid position, generates its mesh, then
     * triggers mesh rebuilds on all six face-adjacent neighbors that already exist.
     *
     * <p>Neighbor rebuilds are necessary because adjacent chunks may have emitted
     * boundary faces toward this position when it was absent (world.getBlock
     * returned AIR for unloaded chunks). Now that this chunk exists, those faces
     * may be incorrectly visible — a rebuild corrects them.
     *
     * @param pos   the chunk's grid coordinate
     * @param chunk the chunk data to register
     */
    public void addChunk(ChunkPos pos, Chunk chunk) {
        chunks.put(pos, chunk);
        rebuildMesh(pos);
        rebuildNeighbors(pos);
    }

    /**
     * Returns the block at the given world-space coordinates.
     * Returns {@link Block#AIR} if the coordinates fall outside any loaded chunk.
     *
     * <p>Uses {@code Math.floorDiv} and {@code Math.floorMod} rather than {@code /}
     * and {@code %} so that negative world coordinates resolve to the correct chunk.
     * For example, world X = -1 belongs to chunk X = -1 (local X = 15), not chunk 0.
     *
     * @param worldX world-space X coordinate
     * @param worldY world-space Y coordinate
     * @param worldZ world-space Z coordinate
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
     * Sets the block at the given world-space coordinates, rebuilds the mesh for
     * the affected chunk, and rebuilds any face-adjacent neighbor chunks whose
     * boundary faces may now be stale.
     *
     * <p>A neighbor rebuild is triggered when the modified block sits on the edge
     * of its chunk (local coordinate 0 or {@link Chunk#SIZE} - 1) — the adjacent
     * chunk may have been emitting or suppressing a boundary face based on the old
     * block state.
     *
     * <p>Does nothing if the coordinates fall outside any loaded chunk.
     *
     * @param worldX world-space X coordinate
     * @param worldY world-space Y coordinate
     * @param worldZ world-space Z coordinate
     * @param block  the block type to place (use {@link Block#AIR} to remove)
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

        // If the modified block is on a chunk boundary, the face the neighbor
        // emitted (or suppressed) toward this position is now stale.
        if (lx == 0)              rebuildMesh(new ChunkPos(cx - 1, cy, cz));
        if (lx == Chunk.SIZE - 1) rebuildMesh(new ChunkPos(cx + 1, cy, cz));
        if (ly == 0)              rebuildMesh(new ChunkPos(cx, cy - 1, cz));
        if (ly == Chunk.SIZE - 1) rebuildMesh(new ChunkPos(cx, cy + 1, cz));
        if (lz == 0)              rebuildMesh(new ChunkPos(cx, cy, cz - 1));
        if (lz == Chunk.SIZE - 1) rebuildMesh(new ChunkPos(cx, cy, cz + 1));
    }

    /**
     * Renders all visible chunks using the provided shader.
     * Chunks whose axis-aligned bounding box lies entirely outside the camera
     * frustum are skipped before any GPU work is done.
     *
     * <p>The frustum is extracted from the combined projection × view matrix.
     * JOML's FrustumIntersection decomposes that matrix into six clip planes
     * and tests each chunk AABB against all of them.
     *
     * @param shader           the currently bound shader program
     * @param projectionMatrix the camera's projection matrix
     * @param viewMatrix       the camera's view matrix
     * @return int[2] — [visible chunk count, total mesh count]
     */
    public int[] render(ShaderProgram shader, Matrix4f projectionMatrix, Matrix4f viewMatrix) {
        // Multiplying projection × view gives the clip-space transform.
        // FrustumIntersection extracts the six frustum planes from it.
        FrustumIntersection frustum = new FrustumIntersection(
            new Matrix4f(projectionMatrix).mul(viewMatrix)
        );

        int visible = 0;

        for (Map.Entry<ChunkPos, Mesh> entry : meshes.entrySet()) {
            ChunkPos pos  = entry.getKey();
            Mesh     mesh = entry.getValue();

            // Each chunk occupies a Chunk.SIZE³ world-space box.
            // If the box is entirely outside any frustum plane, skip it.
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
     * Deletes all GPU meshes. Call on shutdown.
     */
    public void cleanup() {
        for (Mesh mesh : meshes.values()) {
            mesh.cleanup();
        }
        meshes.clear();
        chunks.clear();
    }

    /**
     * Triggers a mesh rebuild for all six face-adjacent neighbors of the given
     * chunk position. {@link #rebuildMesh} is a no-op for positions that have
     * no chunk data, so non-existent neighbors are skipped automatically.
     *
     * @param pos the chunk whose neighbors should be rebuilt
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
     * If a mesh already exists for this position it is deleted first.
     * No-op if no chunk data exists at this position.
     *
     * @param pos the grid position of the chunk to rebuild
     */
    private void rebuildMesh(ChunkPos pos) {
        Chunk chunk = chunks.get(pos);
        if (chunk == null) return;

        Mesh old = meshes.remove(pos);
        if (old != null) old.cleanup();

        float[] vertices = ChunkMesher.mesh(chunk, pos, this);

        // Don't upload an empty mesh — chunk may be all air
        if (vertices.length > 0) {
            meshes.put(pos, new Mesh(vertices));
        }
    }
}