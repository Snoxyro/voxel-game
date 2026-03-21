package com.voxelgame.game;

import com.voxelgame.engine.Mesh;
import com.voxelgame.engine.ShaderProgram;
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
     * Adds a chunk at the given grid position and immediately generates its mesh.
     *
     * @param pos   the chunk's grid coordinate
     * @param chunk the chunk data to register
     */
    public void addChunk(ChunkPos pos, Chunk chunk) {
        chunks.put(pos, chunk);
        rebuildMesh(pos);
    }

    /**
     * Renders all loaded chunks using the provided shader.
     * The shader must be bound before calling this.
     * Sets the modelMatrix uniform for each chunk to translate it
     * to its correct world-space position.
     *
     * @param shader the currently bound shader program
     */
    public void render(ShaderProgram shader) {
        for (Map.Entry<ChunkPos, Mesh> entry : meshes.entrySet()) {
            ChunkPos pos = entry.getKey();
            Mesh mesh    = entry.getValue();

            // Build a translation matrix for this chunk's world-space origin
            Matrix4f modelMatrix = new Matrix4f().translation(
                pos.worldX(), pos.worldY(), pos.worldZ()
            );

            shader.setUniform("modelMatrix", modelMatrix);
            mesh.render();
        }
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
     * Regenerates the GPU mesh for the chunk at the given position.
     * If a mesh already exists for this position it is deleted first.
     *
     * @param pos the grid position of the chunk to rebuild
     */
    private void rebuildMesh(ChunkPos pos) {
        Chunk chunk = chunks.get(pos);
        if (chunk == null) return;

        // Delete old mesh if one exists
        Mesh old = meshes.remove(pos);
        if (old != null) old.cleanup();

        float[] vertices = ChunkMesher.mesh(chunk);

        // Don't upload an empty mesh — chunk may be all air
        if (vertices.length > 0) {
            meshes.put(pos, new Mesh(vertices));
        }
    }
}