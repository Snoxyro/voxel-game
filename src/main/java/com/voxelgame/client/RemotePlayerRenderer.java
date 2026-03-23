package com.voxelgame.client;

import com.voxelgame.engine.ShaderProgram;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Collection;

/**
 * Renders a solid colored box for each remote player.
 *
 * <p>The box dimensions match {@link com.voxelgame.common.world.PhysicsBody}:
 * 0.6 wide × 1.8 tall × 0.6 deep, centered on the player's X/Z position
 * with the base at feet Y. A bright orange color distinguishes remote players
 * from terrain. Directional shading matches the chunk shading conventions for
 * visual consistency.
 *
 * <p>Uses the main 3D shader with {@code useTexture=false}. The shader must
 * already be bound and {@code useTexture} must be set to false before calling
 * {@link #render}. Only vertex attributes 0 (position) and 1 (color) are used —
 * attributes 2 and 3 (UV, layer) are not set up and not read by the shader
 * when {@code useTexture} is false.
 *
 * <p>Calls {@link RemotePlayer#interpolate()} on each player during rendering
 * to advance position smoothly between server updates.
 */
public class RemotePlayerRenderer {

    // Box half-width — full dimensions 0.6 × 1.8 × 0.6
    private static final float W = 0.3f;
    private static final float H = 1.8f;

    // Base orange color — distinct against terrain and sky
    private static final float CR = 1.0f, CG = 0.55f, CB = 0.0f;

    // Directional shade multipliers — same sun angle as ChunkMesher
    private static final float SHADE_TOP    = 1.00f;
    private static final float SHADE_BOTTOM = 0.50f;
    private static final float SHADE_SOUTH  = 0.85f;
    private static final float SHADE_NORTH  = 0.70f;
    private static final float SHADE_EAST   = 0.75f;
    private static final float SHADE_WEST   = 0.65f;

    /** 6 faces × 2 triangles × 3 vertices = 36 vertices. */
    private static final int VERTEX_COUNT = 36;

    private final int vaoId;
    private final int vboId;

    /**
     * Builds the box geometry and uploads it to the GPU.
     * Must be called on the main (GL) thread.
     */
    public RemotePlayerRenderer() {
        float[] vertices = buildBox();

        vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        FloatBuffer buf = MemoryUtil.memAllocFloat(vertices.length);
        buf.put(vertices).flip();

        vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_STATIC_DRAW);
        MemoryUtil.memFree(buf);

        // Matches BlockHighlightRenderer layout: [x, y, z, r, g, b], stride 6
        int stride = 6 * Float.BYTES;
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 3 * Float.BYTES);
        GL20.glEnableVertexAttribArray(1);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
    }

    /**
     * Interpolates and renders all remote players.
     * The main shader must be bound with {@code useTexture=false} before calling.
     *
     * @param shader  the currently bound shader program
     * @param players the collection of remote players to render
     */
    public void render(ShaderProgram shader, Collection<RemotePlayer> players) {
        if (players.isEmpty()) return;

        GL30.glBindVertexArray(vaoId);
        for (RemotePlayer player : players) {
            player.interpolate();
            // Translate so the box is centered on X/Z with base at feet Y
            Matrix4f model = new Matrix4f().translation(
                player.getRenderX() - W,
                player.getRenderY(),
                player.getRenderZ() - W
            );
            shader.setUniform("modelMatrix", model);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, VERTEX_COUNT);
        }
        GL30.glBindVertexArray(0);
    }

    /**
     * Deletes the VAO and VBO from GPU memory. Call on shutdown.
     */
    public void cleanup() {
        GL15.glDeleteBuffers(vboId);
        GL30.glDeleteVertexArrays(vaoId);
    }

    /**
     * Builds a solid box as 12 triangles (36 vertices) in interleaved [x, y, z, r, g, b]
     * format. The box occupies (0,0,0)→(W*2, H, W*2). Winding order matches ChunkMesher
     * conventions — CCW front faces, compatible with GL_CULL_FACE.
     */
    private static float[] buildBox() {
        float x0 = 0f, x1 = W * 2;
        float y0 = 0f, y1 = H;
        float z0 = 0f, z1 = W * 2;

        // Pre-compute shaded colors per face direction
        float rt = CR*SHADE_TOP,    gt = CG*SHADE_TOP,    bt = CB*SHADE_TOP;
        float rb = CR*SHADE_BOTTOM, gb = CG*SHADE_BOTTOM, bb = CB*SHADE_BOTTOM;
        float rn = CR*SHADE_NORTH,  gn = CG*SHADE_NORTH,  bn = CB*SHADE_NORTH;
        float rs = CR*SHADE_SOUTH,  gs = CG*SHADE_SOUTH,  bs = CB*SHADE_SOUTH;
        float re = CR*SHADE_EAST,   ge = CG*SHADE_EAST,   be = CB*SHADE_EAST;
        float rw = CR*SHADE_WEST,   gw = CG*SHADE_WEST,   bw = CB*SHADE_WEST;

        return new float[]{
            // Top face (+Y) — winding matches ChunkMesher top
            x0,y1,z0, rt,gt,bt,   x0,y1,z1, rt,gt,bt,   x1,y1,z1, rt,gt,bt,
            x0,y1,z0, rt,gt,bt,   x1,y1,z1, rt,gt,bt,   x1,y1,z0, rt,gt,bt,

            // Bottom face (-Y) — winding matches ChunkMesher bottom
            x0,y0,z1, rb,gb,bb,   x0,y0,z0, rb,gb,bb,   x1,y0,z0, rb,gb,bb,
            x0,y0,z1, rb,gb,bb,   x1,y0,z0, rb,gb,bb,   x1,y0,z1, rb,gb,bb,

            // North face (-Z) — winding matches ChunkMesher north
            x0,y0,z0, rn,gn,bn,   x0,y1,z0, rn,gn,bn,   x1,y1,z0, rn,gn,bn,
            x0,y0,z0, rn,gn,bn,   x1,y1,z0, rn,gn,bn,   x1,y0,z0, rn,gn,bn,

            // South face (+Z) — winding matches ChunkMesher south
            x1,y0,z1, rs,gs,bs,   x1,y1,z1, rs,gs,bs,   x0,y1,z1, rs,gs,bs,
            x1,y0,z1, rs,gs,bs,   x0,y1,z1, rs,gs,bs,   x0,y0,z1, rs,gs,bs,

            // East face (+X) — winding matches ChunkMesher east
            x1,y0,z0, re,ge,be,   x1,y1,z0, re,ge,be,   x1,y1,z1, re,ge,be,
            x1,y0,z0, re,ge,be,   x1,y1,z1, re,ge,be,   x1,y0,z1, re,ge,be,

            // West face (-X) — winding matches ChunkMesher west
            x0,y0,z1, rw,gw,bw,   x0,y1,z1, rw,gw,bw,   x0,y1,z0, rw,gw,bw,
            x0,y0,z1, rw,gw,bw,   x0,y1,z0, rw,gw,bw,   x0,y0,z0, rw,gw,bw,
        };
    }
}