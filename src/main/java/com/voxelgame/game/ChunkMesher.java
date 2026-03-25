package com.voxelgame.game;

import com.voxelgame.common.world.BlockRegistry;
import com.voxelgame.common.world.BlockType;
import com.voxelgame.common.world.Blocks;
import com.voxelgame.common.world.Chunk;
import com.voxelgame.common.world.ChunkPos;
import java.util.Arrays;
import java.util.Map;

/**
 * Generates an interleaved vertex array (position + color) from a Chunk
 * using greedy meshing with per-vertex ambient occlusion.
 *
 * <h3>Threading</h3>
 * This class is stateless and thread-safe. It receives a pre-captured snapshot
 * of all 26 adjacent neighbor chunks (face, edge, and corner neighbors) instead
 * of a World reference, allowing meshing to run on background threads with no
 * shared-state access. 26 neighbors are required (vs the 6 used before AO) because
 * AO sampling at chunk-boundary vertices needs diagonal neighbor chunks too.
 *
 * <h3>How greedy meshing works</h3>
 * For each of the six face directions, each layer along that direction's axis
 * is scanned independently. A 2D mask records which cells have a visible face,
 * what block type they belong to, and all four corner AO values packed into a
 * single int. The greedy merge pass finds the largest axis-aligned rectangle of
 * identical packed values, emits one quad for it, marks those cells consumed,
 * and repeats until the layer is fully processed.
 *
 * <h3>Ambient occlusion</h3>
 * Each vertex on a face is occluded by up to 3 blocks in the occluder plane:
 * two "side" neighbors and one diagonal corner. The AO value (0–3) is:
 * <pre>
 *   if (side1 && side2) → 0   (fully occluded — corner irrelevant)
 *   else                → 3 - (side1 + side2 + corner)
 * </pre>
 * AO is baked into vertex colors at mesh-build time — zero runtime cost.
 * Two cells can only be greedy-merged when their block type AND all four vertex
 * AO values match. This is enforced automatically because both are packed into
 * the same mask int that the merge algorithm compares for equality.
 *
 * <h3>Diagonal flip</h3>
 * When a quad's opposite corners have unequal AO sums, the default triangle
 * split direction introduces a visible anisotropy artifact. {@link #emitQuad}
 * detects this and flips the split to keep shading symmetric. This is the
 * single most impactful quality detail in voxel AO.
 *
 * <h3>Mask encoding</h3>
 * <pre>
 *   bits 0–2:   block type (ordinal + 1; 0 = no face)
 *   bits 3–4:   AO at vertex 0  (0–3)
 *   bits 5–6:   AO at vertex 1  (0–3)
 *   bits 7–8:   AO at vertex 2  (0–3)
 *   bits 9–10:  AO at vertex 3  (0–3)
 * </pre>
 *
 * <h3>Output format</h3>
 * Interleaved [x, y, z, r, g, b] — 6 floats, 6 vertices per quad (two
 * triangles). {@link com.voxelgame.engine.Mesh} converts these to indexed
 * geometry internally.
 *
 * <h3>Allocation</h3>
 * Uses a manually grown {@code float[]} instead of {@code List<Float>} to
 * avoid per-element boxing. The buffer starts at a capacity sized for a typical
 * chunk and doubles when full.
 */
public class ChunkMesher {

    // -------------------------------------------------------------------------
    // Directional base brightness — applied on top of per-vertex AO.
    //
    // Top and bottom are axis-aligned: brightest from above, darkest from below.
    // The four side directions use different values to simulate a sun angle from
    // the south-east. This gives the terrain large-scale directional depth that
    // looks natural and doesn't produce blocky AO boundary artifacts.
    //
    //   SOUTH (+Z) = most lit (sun-facing)
    //   EAST  (+X) = partially lit
    //   NORTH (-Z) = partially shadowed
    //   WEST  (-X) = most shadowed
    //
    // Keep these within a modest range (~0.20 spread) — too much contrast looks
    // artificial; too little and the directional effect disappears.
    // -------------------------------------------------------------------------
    private static final float SHADE_TOP    = 1.00f;
    private static final float SHADE_BOTTOM = 0.50f;
    private static final float SHADE_SOUTH  = 0.85f;  // +Z, sun-facing
    private static final float SHADE_EAST   = 0.75f;  // +X
    private static final float SHADE_NORTH  = 0.70f;  // -Z
    private static final float SHADE_WEST   = 0.65f;  // -X, most shadowed

    /**
     * AO darkening scale for SIDE and BOTTOM faces.
     * These faces show corner shadows at block junctions that look correct and natural.
     * ao=3 (fully open) → 1.0 brightness, ao=0 (fully occluded) → this value.
     */
    private static final float AO_STRENGTH_SIDE = 0.30f;

    /**
     * AO darkening scale for TOP faces only.
     *
     * Top-face AO checks X and Z neighbors at y+1. On sloped terrain this creates
     * dark stripes running PERPENDICULAR to the slope direction — the opposite of
     * natural-looking terrain shading. Keeping this nearly 1.0 (very weak AO)
     * suppresses those stripes without affecting the good corner shadows on walls.
     * The directional side shading already provides the large-scale depth on walls.
     */
    private static final float AO_STRENGTH_TOP = 0.45f;

    /**
     * Floats per quad (6 vertices × 6 floats). Starting at 1024 quads × 36
     * floats = 36864 floats covers most cases without excessive initial allocation.
     */
    private static final int INITIAL_CAPACITY = 54 * 1024;

    // -------------------------------------------------------------------------
    // AO offset tables — one entry per face direction.
    //
    // For a face whose normal is N, the occluder plane is offset by N from the
    // block. Each vertex is a corner of the quad in that plane. The three
    // occluder blocks are the two "side" neighbors and the diagonal corner,
    // all evaluated at positions relative to the block (already including N).
    //
    // Layout: [faceDir][vertex 0-3][occluder: 0=side1, 1=side2, 2=corner][axis: 0=x,1=y,2=z]
    //
    // Vertex order matches the CCW winding used by each face's emitQuad call:
    //   v0 = scan axis (i,   j  )  →  used in all six face methods
    //   v1 = scan axis (i,   j+h)
    //   v2 = scan axis (i+w, j+h)
    //   v3 = scan axis (i+w, j  )
    //
    // The six face normals and their 2D scan axes:
    //   TOP    (+Y): scan X,Z — occluder plane at y+1
    //   BOTTOM (-Y): scan X,Z — occluder plane at y
    //   NORTH  (-Z): scan X,Y — occluder plane at z
    //   SOUTH  (+Z): scan X,Y — occluder plane at z+1
    //   EAST   (+X): scan Z,Y — occluder plane at x+1
    //   WEST   (-X): scan Z,Y — occluder plane at x
    // -------------------------------------------------------------------------

    /**
     * AO offsets for each face direction and vertex.
     * Index as: AO_OFFSETS[faceDir][vertex][occluder][axis].
     */
    private static final int[][][][] AO_OFFSETS = {

        // --- TOP (+Y): occluder plane at y+1, scan axes X(i) and Z(j) ---
        // v0=(i,y+1,j):     side1=(-1,+1,0) side2=(0,+1,-1) corner=(-1,+1,-1)
        // v1=(i,y+1,j+h):   side1=(-1,+1,0) side2=(0,+1,+1) corner=(-1,+1,+1)
        // v2=(i+w,y+1,j+h): side1=(+1,+1,0) side2=(0,+1,+1) corner=(+1,+1,+1)
        // v3=(i+w,y+1,j):   side1=(+1,+1,0) side2=(0,+1,-1) corner=(+1,+1,-1)
        {
            { {-1,1,0}, {0,1,-1}, {-1,1,-1} },  // v0
            { {-1,1,0}, {0,1, 1}, {-1,1, 1} },  // v1
            { { 1,1,0}, {0,1, 1}, { 1,1, 1} },  // v2
            { { 1,1,0}, {0,1,-1}, { 1,1,-1} },  // v3
        },

        // --- BOTTOM (-Y): occluder plane at y, scan axes X(i) and Z(j) ---
        // v0=(i,y,j+h):   side1=(-1,-1,0) side2=(0,-1,+1) corner=(-1,-1,+1)
        // v1=(i,y,j):     side1=(-1,-1,0) side2=(0,-1,-1) corner=(-1,-1,-1)
        // v2=(i+w,y,j):   side1=(+1,-1,0) side2=(0,-1,-1) corner=(+1,-1,-1)
        // v3=(i+w,y,j+h): side1=(+1,-1,0) side2=(0,-1,+1) corner=(+1,-1,+1)
        {
            { {-1,-1,0}, {0,-1, 1}, {-1,-1, 1} },  // v0
            { {-1,-1,0}, {0,-1,-1}, {-1,-1,-1} },  // v1
            { { 1,-1,0}, {0,-1,-1}, { 1,-1,-1} },  // v2
            { { 1,-1,0}, {0,-1, 1}, { 1,-1, 1} },  // v3
        },

        // --- NORTH (-Z): occluder plane at z, scan axes X(i) and Y(j) ---
        // v0=(i,j,z):     side1=(-1,0,-1) side2=(0,-1,-1) corner=(-1,-1,-1)
        // v1=(i,j+h,z):   side1=(-1,0,-1) side2=(0,+1,-1) corner=(-1,+1,-1)
        // v2=(i+w,j+h,z): side1=(+1,0,-1) side2=(0,+1,-1) corner=(+1,+1,-1)
        // v3=(i+w,j,z):   side1=(+1,0,-1) side2=(0,-1,-1) corner=(+1,-1,-1)
        {
            { {-1,0,-1}, {0,-1,-1}, {-1,-1,-1} },  // v0
            { {-1,0,-1}, {0, 1,-1}, {-1, 1,-1} },  // v1
            { { 1,0,-1}, {0, 1,-1}, { 1, 1,-1} },  // v2
            { { 1,0,-1}, {0,-1,-1}, { 1,-1,-1} },  // v3
        },

        // --- SOUTH (+Z): occluder plane at z+1, scan axes X(i) and Y(j) ---
        // v0=(i+w,j,z+1):   side1=(+1,0,+1) side2=(0,-1,+1) corner=(+1,-1,+1)
        // v1=(i+w,j+h,z+1): side1=(+1,0,+1) side2=(0,+1,+1) corner=(+1,+1,+1)
        // v2=(i,j+h,z+1):   side1=(-1,0,+1) side2=(0,+1,+1) corner=(-1,+1,+1)
        // v3=(i,j,z+1):     side1=(-1,0,+1) side2=(0,-1,+1) corner=(-1,-1,+1)
        {
            { { 1,0,1}, {0,-1,1}, { 1,-1,1} },  // v0
            { { 1,0,1}, {0, 1,1}, { 1, 1,1} },  // v1
            { {-1,0,1}, {0, 1,1}, {-1, 1,1} },  // v2
            { {-1,0,1}, {0,-1,1}, {-1,-1,1} },  // v3
        },

        // --- EAST (+X): occluder plane at x+1, scan axes Z(i) and Y(j) ---
        // v0=(x+1,j,i):     side1=(+1,0,-1) side2=(+1,-1,0) corner=(+1,-1,-1)
        // v1=(x+1,j+h,i):   side1=(+1,0,-1) side2=(+1,+1,0) corner=(+1,+1,-1)
        // v2=(x+1,j+h,i+w): side1=(+1,0,+1) side2=(+1,+1,0) corner=(+1,+1,+1)
        // v3=(x+1,j,i+w):   side1=(+1,0,+1) side2=(+1,-1,0) corner=(+1,-1,+1)
        {
            { {1,0,-1}, {1,-1,0}, {1,-1,-1} },  // v0
            { {1,0,-1}, {1, 1,0}, {1, 1,-1} },  // v1
            { {1,0, 1}, {1, 1,0}, {1, 1, 1} },  // v2
            { {1,0, 1}, {1,-1,0}, {1,-1, 1} },  // v3
        },

        // --- WEST (-X): occluder plane at x, scan axes Z(i) and Y(j) ---
        // v0=(x,j,i+w):   side1=(-1,0,+1) side2=(-1,-1,0) corner=(-1,-1,+1)
        // v1=(x,j+h,i+w): side1=(-1,0,+1) side2=(-1,+1,0) corner=(-1,+1,+1)
        // v2=(x,j+h,i):   side1=(-1,0,-1) side2=(-1,+1,0) corner=(-1,+1,-1)
        // v3=(x,j,i):     side1=(-1,0,-1) side2=(-1,-1,0) corner=(-1,-1,-1)
        {
            { {-1,0, 1}, {-1,-1,0}, {-1,-1, 1} },  // v0
            { {-1,0, 1}, {-1, 1,0}, {-1, 1, 1} },  // v1
            { {-1,0,-1}, {-1, 1,0}, {-1, 1,-1} },  // v2
            { {-1,0,-1}, {-1,-1,0}, {-1,-1,-1} },  // v3
        },
    };

    // Face direction index constants — match the order of AO_OFFSETS rows.
    private static final int FACE_TOP    = 0;
    private static final int FACE_BOTTOM = 1;
    private static final int FACE_NORTH  = 2;
    private static final int FACE_SOUTH  = 3;
    private static final int FACE_EAST   = 4;
    private static final int FACE_WEST   = 5;

    /**
     * Global AO toggle — readable by all worker threads via volatile visibility.
     * When false, {@link #computeAO} always returns 3 (fully open, no darkening).
     * Written by the GL thread via {@link GameSettings}; read by worker threads
     * during mesh generation. Volatile ensures the write is immediately visible
     * across threads without locking.
     */
    public static volatile boolean aoEnabled = true;

    /**
     * Generates the greedy-merged visible mesh for the given chunk, with
     * per-vertex ambient occlusion baked into vertex colors.
     *
     * <p>Fast-path: all-air chunks return an empty array immediately with no
     * iteration. For occupied chunks, the Y occupancy range from the chunk is
     * passed to each face pass so layer loops are clamped to the occupied band.
     *
     * @param chunk     the chunk to mesh
     * @param pos       the chunk's position in the world grid
     * @param neighbors snapshot of all 26 adjacent neighbor chunks
     * @return interleaved float array [x, y, z, r, g, b, ...], trimmed to exact size
    * @thread mesh-worker (typically), any
    * @gl-state n/a
     */
    public static float[] mesh(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors) {
        if (chunk.isAllAir()) return new float[0];

        int minY = chunk.getMinOccupiedY();
        int maxY = chunk.getMaxOccupiedY();

        float[] buf  = new float[INITIAL_CAPACITY];
        int     size = 0;
        int[][] mask = new int[Chunk.SIZE][Chunk.SIZE];

        size = meshTopFaces   (chunk, pos, neighbors, buf, size, mask, minY, maxY);
        size = meshBottomFaces(chunk, pos, neighbors, buf, size, mask, minY, maxY);
        size = meshNorthFaces (chunk, pos, neighbors, buf, size, mask, minY, maxY);
        size = meshSouthFaces (chunk, pos, neighbors, buf, size, mask, minY, maxY);
        size = meshEastFaces  (chunk, pos, neighbors, buf, size, mask, minY, maxY);
        size = meshWestFaces  (chunk, pos, neighbors, buf, size, mask, minY, maxY);

        return Arrays.copyOf(buf, size);
    }

    // -------------------------------------------------------------------------
    // Per-direction mesh passes
    // -------------------------------------------------------------------------

    /**
     * TOP faces (+Y): outer loop clamped to [minY, maxY].
     */
    private static int meshTopFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                    float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, pos, neighbors, x, y + 1, z)) {
                        mask[z][x] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, pos, neighbors, x, y, z, FACE_TOP);
                    mask[z][x] = packMask(b.getId(),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_TOP, 0),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_TOP, 1),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_TOP, 2),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_TOP, 3),
                        light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 10) {
                int i = quads[qi], j = quads[qi+1];
                int w = quads[qi+2];
                int h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);
                int light = quads[qi+9];
                // Textures provide color — vertex color carries only brightness (AO + directional shade).
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 60);
                size = emitQuad(buf, size, color, SHADE_TOP, AO_STRENGTH_TOP,
                        quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.topTextureLayer(), light,
                        i,     y + 1, j,
                        i,     y + 1, j + h,
                        i + w, y + 1, j + h,
                        i + w, y + 1, j);
            }
        }
        return size;
    }

    /**
     * BOTTOM faces (-Y): outer loop clamped to [minY, maxY].
     */
    private static int meshBottomFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                       float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, pos, neighbors, x, y - 1, z)) {
                        mask[z][x] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, pos, neighbors, x, y, z, FACE_BOTTOM);
                    mask[z][x] = packMask(b.getId(),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_BOTTOM, 0),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_BOTTOM, 1),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_BOTTOM, 2),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_BOTTOM, 3),
                        light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 10) {
                int i = quads[qi], j = quads[qi+1];
                int w = quads[qi+2];
                int h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);
                int light = quads[qi+9];
                // Textures provide color — vertex color carries only brightness (AO + directional shade).
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 60);
                size = emitQuad(buf, size, color, SHADE_BOTTOM, AO_STRENGTH_TOP,
                        quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.bottomTextureLayer(), light,
                        i,     y, j + h,
                        i,     y, j,
                        i + w, y, j,
                        i + w, y, j + h);
            }
        }
        return size;
    }

    /**
     * NORTH faces (-Z): stale mask rows outside [minY, maxY] are explicitly zeroed.
     */
    private static int meshNorthFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                      float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) { Arrays.fill(mask[y], 0); continue; }
                for (int x = 0; x < Chunk.SIZE; x++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, pos, neighbors, x, y, z - 1)) {
                        mask[y][x] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, pos, neighbors, x, y, z, FACE_NORTH);
                    mask[y][x] = packMask(b.getId(),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_NORTH, 0),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_NORTH, 1),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_NORTH, 2),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_NORTH, 3),
                        light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 10) {
                int i = quads[qi], j = quads[qi+1];
                int w = quads[qi+2];
                int h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);
                int light = quads[qi+9];
                // Textures provide color — vertex color carries only brightness (AO + directional shade).
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 60);
                size = emitQuad(buf, size, color, SHADE_NORTH, AO_STRENGTH_SIDE,
                        quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.sideTextureLayer(), light,
                        i,     j,     z,
                        i,     j + h, z,
                        i + w, j + h, z,
                        i + w, j,     z);
            }
        }
        return size;
    }

    /**
     * SOUTH faces (+Z): stale mask rows outside [minY, maxY] are explicitly zeroed.
     */
    private static int meshSouthFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                      float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) { Arrays.fill(mask[y], 0); continue; }
                for (int x = 0; x < Chunk.SIZE; x++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, pos, neighbors, x, y, z + 1)) {
                        mask[y][x] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, pos, neighbors, x, y, z, FACE_SOUTH);
                    mask[y][x] = packMask(b.getId(),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_SOUTH, 0),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_SOUTH, 1),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_SOUTH, 2),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_SOUTH, 3),
                        light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 10) {
                int i = quads[qi], j = quads[qi+1];
                int w = quads[qi+2];
                int h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);
                int light = quads[qi+9];
                // Textures provide color — vertex color carries only brightness (AO + directional shade).
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 60);
                size = emitQuad(buf, size, color, SHADE_SOUTH, AO_STRENGTH_SIDE,
                        quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.sideTextureLayer(), light,
                        i + w, j,     z + 1,
                        i + w, j + h, z + 1,
                        i,     j + h, z + 1,
                        i,     j,     z + 1);
            }
        }
        return size;
    }

    /**
     * EAST faces (+X): stale mask rows outside [minY, maxY] are explicitly zeroed.
     */
    private static int meshEastFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                     float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) { Arrays.fill(mask[y], 0); continue; }
                for (int z = 0; z < Chunk.SIZE; z++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, pos, neighbors, x + 1, y, z)) {
                        mask[y][z] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, pos, neighbors, x, y, z, FACE_EAST);
                    mask[y][z] = packMask(b.getId(),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_EAST, 0),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_EAST, 1),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_EAST, 2),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_EAST, 3),
                        light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 10) {
                int i = quads[qi], j = quads[qi+1];
                int w = quads[qi+2];
                int h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);
                int light = quads[qi+9];
                // Textures provide color — vertex color carries only brightness (AO + directional shade).
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 60);
                size = emitQuad(buf, size, color, SHADE_EAST, AO_STRENGTH_SIDE,
                        quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.sideTextureLayer(), light,
                        x + 1, j,     i,
                        x + 1, j + h, i,
                        x + 1, j + h, i + w,
                        x + 1, j,     i + w);
            }
        }
        return size;
    }

    /**
     * WEST faces (-X): stale mask rows outside [minY, maxY] are explicitly zeroed.
     */
    private static int meshWestFaces(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                     float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) { Arrays.fill(mask[y], 0); continue; }
                for (int z = 0; z < Chunk.SIZE; z++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, pos, neighbors, x - 1, y, z)) {
                        mask[y][z] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, pos, neighbors, x, y, z, FACE_WEST);
                    mask[y][z] = packMask(b.getId(),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_WEST, 0),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_WEST, 1),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_WEST, 2),
                        computeAO(chunk, pos, neighbors, x, y, z, FACE_WEST, 3),
                        light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 10) {
                int i = quads[qi], j = quads[qi+1];
                int w = quads[qi+2];
                int h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);
                int light = quads[qi+9];
                // Textures provide color — vertex color carries only brightness (AO + directional shade).
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 60);
                size = emitQuad(buf, size, color, SHADE_WEST, AO_STRENGTH_SIDE,
                        quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.sideTextureLayer(), light,
                        x, j,     i + w,
                        x, j + h, i + w,
                        x, j + h, i,
                        x, j,     i);
            }
        }
        return size;
    }

    // -------------------------------------------------------------------------
    // Light sampling
    // -------------------------------------------------------------------------

    /**
     * Per-face neighbor offsets for light lookup.
     * For each face direction, the offset points from the solid block toward
     * the open air block whose light level illuminates that face.
     * Order matches FACE_TOP, FACE_BOTTOM, FACE_NORTH, FACE_SOUTH, FACE_EAST, FACE_WEST.
     */
    private static final int[][] FACE_LIGHT_OFFSET = {
        { 0,  1,  0},  // TOP    — block above
        { 0, -1,  0},  // BOTTOM — block below
        { 0,  0, -1},  // NORTH  — block to -Z
        { 0,  0,  1},  // SOUTH  — block to +Z
        { 1,  0,  0},  // EAST   — block to +X
        {-1,  0,  0},  // WEST   — block to -X
    };

    /**
     * Returns the maximum light level (skylight or block light, whichever is higher)
     * at the given local block coordinate, resolving cross-chunk boundaries via the
     * neighbor snapshot. Used to determine face brightness at mesh-build time.
     *
     * <p>Returns {@link Chunk#MAX_LIGHT} (15) when the position is outside any loaded
     * chunk — treating unloaded space as fully lit avoids dark seams at load boundaries.
     *
     * @param chunk     the chunk being meshed
     * @param pos       its world-grid position
     * @param neighbors snapshot of all 26 adjacent chunks
     * @param bx        local X (may be out of [0, SIZE) — boundary crossing handled)
     * @param by        local Y
     * @param bz        local Z
     * @return light level in [0, 15]
    * @thread mesh-worker
    * @gl-state n/a
     */
    private static int getLightAt(Chunk chunk, ChunkPos pos,
                                Map<ChunkPos, Chunk> neighbors,
                                int bx, int by, int bz) {
        // Resolve which chunk owns this coordinate
        int cx = pos.x() + Math.floorDiv(bx, Chunk.SIZE);
        int cy = pos.y() + Math.floorDiv(by, Chunk.SIZE);
        int cz = pos.z() + Math.floorDiv(bz, Chunk.SIZE);
        int lx = Math.floorMod(bx, Chunk.SIZE);
        int ly = Math.floorMod(by, Chunk.SIZE);
        int lz = Math.floorMod(bz, Chunk.SIZE);

        if (cx == pos.x() && cy == pos.y() && cz == pos.z()) {
            return chunk.getMaxLight(lx, ly, lz);
        }
        Chunk neighbor = neighbors.get(new ChunkPos(cx, cy, cz));
        if (neighbor == null) return Chunk.MAX_LIGHT; // treat unloaded as fully lit
        return neighbor.getMaxLight(lx, ly, lz);
    }

    /**
     * Returns the light level (0–15) that illuminates the given face of a block.
     * Reads the light from the open-air neighbor on the exposed side of the face —
     * the block the face is "looking toward".
     *
     * @param chunk     the chunk being meshed
     * @param pos       its world-grid position
     * @param neighbors snapshot of all 26 adjacent chunks
     * @param bx        local X of the solid block
     * @param by        local Y of the solid block
     * @param bz        local Z of the solid block
     * @param face      face direction index (FACE_TOP, FACE_BOTTOM, etc.)
     * @return light level in [0, 15]
    * @thread mesh-worker
    * @gl-state n/a
     */
    private static int getLightForFace(Chunk chunk, ChunkPos pos,
                                        Map<ChunkPos, Chunk> neighbors,
                                        int bx, int by, int bz, int face) {
        int[] off = FACE_LIGHT_OFFSET[face];
        return getLightAt(chunk, pos, neighbors, bx + off[0], by + off[1], bz + off[2]);
    }

    // -------------------------------------------------------------------------
    // Ambient occlusion
    // -------------------------------------------------------------------------

    /**
     * Computes the ambient occlusion value (0–3) for one vertex of a block face.
     * 3 = fully open (no occlusion), 0 = fully occluded (two solid sides).
     *
     * <p>Looks up three blocks in the occluder plane for this face direction and
     * vertex using the precomputed {@link #AO_OFFSETS} table. Offsets are relative
     * to the block's local chunk coordinates, so this works correctly across chunk
     * boundaries as long as the neighbors snapshot contains all 26 adjacent chunks.
     *
     * @param chunk     the chunk being meshed
     * @param pos       the chunk's world-grid position
     * @param neighbors snapshot of all 26 adjacent chunks
     * @param bx        local X of the block whose face we're evaluating
     * @param by        local Y
     * @param bz        local Z
     * @param face      face direction index (FACE_TOP, FACE_BOTTOM, etc.)
     * @param vertex    vertex index within the face (0–3)
     * @return AO value in [0, 3]
    * @thread mesh-worker
    * @gl-state n/a
     */
    private static int computeAO(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                  int bx, int by, int bz, int face, int vertex) {
        // AO disabled globally — return fully open (no darkening on any vertex).
        if (!aoEnabled) return 3;

        int[][] offsets = AO_OFFSETS[face][vertex];
        boolean side1  = !isAirAt(chunk, pos, neighbors,
                bx + offsets[0][0], by + offsets[0][1], bz + offsets[0][2]);
        boolean side2  = !isAirAt(chunk, pos, neighbors,
                bx + offsets[1][0], by + offsets[1][1], bz + offsets[1][2]);
        boolean corner = !isAirAt(chunk, pos, neighbors,
                bx + offsets[2][0], by + offsets[2][1], bz + offsets[2][2]);

        // When both sides are solid the vertex is fully occluded — corner is irrelevant.
        if (side1 && side2) return 0;
        return 3 - (side1 ? 1 : 0) - (side2 ? 1 : 0) - (corner ? 1 : 0);
    }

    /**
     * Packs block registry ID, four per-vertex AO values, and a face light level
     * into a single int for use as a mask cell value. Two cells with identical packed
     * values merge greedily — this enforces that block type, AO, AND light level must
     * all match across merged faces, preventing incorrect light interpolation on merged quads.
     *
     * <pre>
     *   bits  0–16:  blockId + 1   (0 = empty face)
     *   bits 17–18:  AO vertex 0   (0–3)
     *   bits 19–20:  AO vertex 1   (0–3)
     *   bits 21–22:  AO vertex 2   (0–3)
     *   bits 23–24:  AO vertex 3   (0–3)
     *   bits 25–28:  light level   (0–15)
     * </pre>
    *
    * @thread mesh-worker
    * @gl-state n/a
     */
    private static int packMask(int blockId, int ao0, int ao1, int ao2, int ao3, int light) {
        return (blockId + 1)
            | (ao0   << 17)
            | (ao1   << 19)
            | (ao2   << 21)
            | (ao3   << 23)
            | (light << 25);
    }

    // -------------------------------------------------------------------------
    // Greedy merge
    // -------------------------------------------------------------------------

    /**
     * Runs the greedy merge algorithm on a SIZE×SIZE mask.
     *
     * <p>Returns a flat int array of [i, j, w, h, type, ao0, ao1, ao2, ao3, light, ...]
     * — 10 ints per quad. AO values are unpacked from the packed
     * mask of the first cell in each merged rectangle — all cells in a merged
     * region have identical packed values by the merge condition, so any cell's
     * values are representative.
     *
     * <p>Modifies the mask in place. Callers must rebuild before the next layer.
     *
     * @param mask SIZE×SIZE mask — 0 = no face, positive = packed(type, ao0..ao3)
     * @return flat int array of quads, length = quadCount × 10
    * @thread mesh-worker
    * @gl-state n/a
     */
    private static int[] buildMergedQuads(int[][] mask) {
        int[] result = new int[Chunk.SIZE * Chunk.SIZE * 10];
        int count = 0;

        for (int j = 0; j < Chunk.SIZE; j++) {
            for (int i = 0; i < Chunk.SIZE; i++) {
                int packed = mask[j][i];
                if (packed == 0) continue;

                // Extend right while the full packed value (type + AO) matches
                int w = 1;
                while (i + w < Chunk.SIZE && mask[j][i + w] == packed) w++;

                // Extend down while the entire row width matches
                int h = 1;
                outer:
                while (j + h < Chunk.SIZE) {
                    for (int k = 0; k < w; k++)
                        if (mask[j + h][i + k] != packed) break outer;
                    h++;
                }

                // Unpack type and AO values from the representative cell
                result[count++] = i;
                result[count++] = j;
                result[count++] = w;
                result[count++] = h;
                result[count++] = (packed & 0x1FFFF) - 1; // block registry ID (bits 0-16, minus the +1 offset)
                result[count++] = (packed >> 17) & 0x3;   // ao0 (bits 17-18)
                result[count++] = (packed >> 19) & 0x3;   // ao1 (bits 19-20)
                result[count++] = (packed >> 21) & 0x3;   // ao2 (bits 21-22)
                result[count++] = (packed >> 23) & 0x3;   // ao3 (bits 23-24)
                result[count++] = (packed >> 25) & 0xF;   // light level (bits 25-28)

                // Mark the merged rectangle as consumed
                for (int dj = 0; dj < h; dj++)
                    for (int di = 0; di < w; di++)
                        mask[j + dj][i + di] = 0;
            }
        }

        return Arrays.copyOf(result, count);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Ensures the buffer has at least {@code needed} free slots starting at
     * {@code size}. Doubles capacity if not. Returns the (possibly new) buffer.
      *
      * @thread mesh-worker
      * @gl-state n/a
     */
    private static float[] ensureCapacity(float[] buf, int size, int needed) {
        if (size + needed <= buf.length) return buf;
        return Arrays.copyOf(buf, Math.max(buf.length * 2, size + needed));
    }

    /**
     * Checks whether the block at the given local chunk coordinates is air.
     * Fast path for in-bounds coordinates; neighbor snapshot for cross-boundary.
     * Thread-safe — reads only from chunk and the immutable neighbors snapshot.
     *
     * <p>AO requires diagonal neighbor chunks (e.g. a vertex at local x=0,z=0
     * needs the chunk at cx-1,cy,cz-1). The caller must ensure the snapshot
     * was captured with all 26 adjacent chunks, not just the 6 face-adjacent ones.
     *
     * @param chunk     the chunk being meshed
     * @param pos       the chunk's world grid position
     * @param neighbors snapshot of all 26 adjacent neighbor chunks
     * @param lx        local X (may be outside [0, SIZE) to cross a boundary)
     * @param ly        local Y
     * @param lz        local Z
     * @return true if the position is air or in an unloaded/missing neighbor
    * @thread mesh-worker
    * @gl-state n/a
     */
    private static boolean isAirAt(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors,
                                    int lx, int ly, int lz) {
        if (lx >= 0 && lx < Chunk.SIZE
         && ly >= 0 && ly < Chunk.SIZE
         && lz >= 0 && lz < Chunk.SIZE) {
            return chunk.getBlock(lx, ly, lz) == Blocks.AIR;
        }

        int worldX = pos.worldX() + lx;
        int worldY = pos.worldY() + ly;
        int worldZ = pos.worldZ() + lz;

        Chunk neighbor = neighbors.get(new ChunkPos(
            Math.floorDiv(worldX, Chunk.SIZE),
            Math.floorDiv(worldY, Chunk.SIZE),
            Math.floorDiv(worldZ, Chunk.SIZE)
        ));
        if (neighbor == null) return true;

        return neighbor.getBlock(
            Math.floorMod(worldX, Chunk.SIZE),
            Math.floorMod(worldY, Chunk.SIZE),
            Math.floorMod(worldZ, Chunk.SIZE)
        ) == Blocks.AIR;
    }

    /**
     * Writes a quad as two triangles into buf at position size, with per-vertex
     * AO darkening baked into the color. Returns the new size after writing 36
     * floats (6 vertices × 6 floats).
     *
     * <h3>AO brightness</h3>
     * Each vertex's final brightness = directionalShade × lerp(AO_STRENGTH, 1.0, ao/3.0).
     * At ao=3 (fully open): multiplier = 1.0.
     * At ao=0 (fully occluded): multiplier = AO_STRENGTH.
     *
     * <h3>Diagonal flip</h3>
     * A quad split into two triangles can show an anisotropy artifact when
     * opposite vertices have unequal AO. If ao0+ao2 > ao1+ao3 the "bright"
     * diagonal runs v0→v2 — we flip the split to v0,v1,v3 / v1,v2,v3 so both
     * triangles share one bright and one dark vertex equally.
     *
     * @param buf              output buffer — must have ≥60 free slots at size (6 verts × 10 floats)
     * @param size             current write position
     * @param color            base block color [r, g, b]
     * @param directionalShade brightness for this face direction
     * @param ao0              AO at v0 (0–3)
     * @param ao1              AO at v1 (0–3)
     * @param ao2              AO at v2 (0–3)
     * @param ao3              AO at v3 (0–3)
     * @return new size after writing 60 floats (6 vertices × 10 floats)
    * @thread mesh-worker
    * @gl-state n/a
     */
    private static int emitQuad(float[] buf, int size,
                                 float[] color, float directionalShade, float aoStrength,
                                 int ao0, int ao1, int ao2, int ao3,
                                 float w, float h, int texLayer, int light,
                                 float x0, float y0, float z0,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3) {
        // Per-vertex brightness: lerp from aoStrength (ao=0) to 1.0 (ao=3),
        // then multiply by the directional base shade for this face direction.
        float b0 = directionalShade * (aoStrength + (1.0f - aoStrength) * (ao0 / 3.0f));
        float b1 = directionalShade * (aoStrength + (1.0f - aoStrength) * (ao1 / 3.0f));
        float b2 = directionalShade * (aoStrength + (1.0f - aoStrength) * (ao2 / 3.0f));
        float b3 = directionalShade * (aoStrength + (1.0f - aoStrength) * (ao3 / 3.0f));

        float r0 = color[0]*b0, g0 = color[1]*b0, bl0 = color[2]*b0;
        float r1 = color[0]*b1, g1 = color[1]*b1, bl1 = color[2]*b1;
        float r2 = color[0]*b2, g2 = color[1]*b2, bl2 = color[2]*b2;
        float r3 = color[0]*b3, g3 = color[1]*b3, bl3 = color[2]*b3;

        float tl = texLayer;
        // Gamma-map the face light level to a perceptually linear brightness.
        // pow(0.8, 15 - level): level 15 = 1.0, level 0 ≈ 0.035.
        // Constant per quad — all 6 vertices share the same face light value.
        float brightness = (float) Math.pow(0.8, 15.0 - light);
        // UV coordinates in tile units. v0=(0,0), v1=(0,h), v2=(w,h), v3=(w,0).
        // GL_REPEAT handles tiling — no fract() needed.

        // Diagonal flip: when the bright diagonal runs v0→v2, flip the split so
        // both triangles share the gradient evenly instead of one getting all the
        // dark or all the bright interpolation.
        //
        // IMPORTANT: Mesh.java assumes a fixed 6-vertex format: [A B C A C D],
        // extracting unique verts from positions 0,1,2,5 and building indices (0,1,2)+(0,2,3).
        // The flipped case must therefore emit [v1 v2 v3 v1 v3 v0] — not [v0 v1 v3 v1 v2 v3].
        // That produces triangles (v1,v2,v3) and (v1,v3,v0), which equals (v0,v1,v3),
        // both CCW. Emitting [v0 v1 v3 v1 v2 v3] would cause Mesh.java to extract
        // unique[2]=unique[3]=v3, creating a degenerate triangle (a visible hole).
        if (ao0 + ao2 < ao1 + ao3) {
            // Flipped split — emit as [v1 v2 v3 v1 v3 v0] for Mesh.java compatibility.
            // Triangles: (v1,v2,v3) and (v1,v3,v0) ≡ (v0,v1,v3). Both CCW.
            buf[size++]=x1; buf[size++]=y1; buf[size++]=z1; buf[size++]=r1; buf[size++]=g1; buf[size++]=bl1; buf[size++]=0f; buf[size++]=h;  buf[size++]=tl; buf[size++]=brightness;
            buf[size++]=x2; buf[size++]=y2; buf[size++]=z2; buf[size++]=r2; buf[size++]=g2; buf[size++]=bl2; buf[size++]=w;  buf[size++]=h;  buf[size++]=tl; buf[size++]=brightness;
            buf[size++]=x3; buf[size++]=y3; buf[size++]=z3; buf[size++]=r3; buf[size++]=g3; buf[size++]=bl3; buf[size++]=w;  buf[size++]=0f; buf[size++]=tl; buf[size++]=brightness;

            buf[size++]=x1; buf[size++]=y1; buf[size++]=z1; buf[size++]=r1; buf[size++]=g1; buf[size++]=bl1; buf[size++]=0f; buf[size++]=h;  buf[size++]=tl; buf[size++]=brightness;
            buf[size++]=x3; buf[size++]=y3; buf[size++]=z3; buf[size++]=r3; buf[size++]=g3; buf[size++]=bl3; buf[size++]=w;  buf[size++]=0f; buf[size++]=tl; buf[size++]=brightness;
            buf[size++]=x0; buf[size++]=y0; buf[size++]=z0; buf[size++]=r0; buf[size++]=g0; buf[size++]=bl0; buf[size++]=0f; buf[size++]=0f; buf[size++]=tl; buf[size++]=brightness;
        } else {
            // Normal split — emit as [v0 v1 v2 v0 v2 v3]. Triangles: (v0,v1,v2) and (v0,v2,v3). Both CCW.
            buf[size++]=x0; buf[size++]=y0; buf[size++]=z0; buf[size++]=r0; buf[size++]=g0; buf[size++]=bl0; buf[size++]=0f; buf[size++]=0f; buf[size++]=tl; buf[size++]=brightness;
            buf[size++]=x1; buf[size++]=y1; buf[size++]=z1; buf[size++]=r1; buf[size++]=g1; buf[size++]=bl1; buf[size++]=0f; buf[size++]=h;  buf[size++]=tl; buf[size++]=brightness;
            buf[size++]=x2; buf[size++]=y2; buf[size++]=z2; buf[size++]=r2; buf[size++]=g2; buf[size++]=bl2; buf[size++]=w;  buf[size++]=h;  buf[size++]=tl; buf[size++]=brightness;

            buf[size++]=x0; buf[size++]=y0; buf[size++]=z0; buf[size++]=r0; buf[size++]=g0; buf[size++]=bl0; buf[size++]=0f; buf[size++]=0f; buf[size++]=tl; buf[size++]=brightness;
            buf[size++]=x2; buf[size++]=y2; buf[size++]=z2; buf[size++]=r2; buf[size++]=g2; buf[size++]=bl2; buf[size++]=w;  buf[size++]=h;  buf[size++]=tl; buf[size++]=brightness;
            buf[size++]=x3; buf[size++]=y3; buf[size++]=z3; buf[size++]=r3; buf[size++]=g3; buf[size++]=bl3; buf[size++]=w;  buf[size++]=0f; buf[size++]=tl; buf[size++]=brightness;
        }

        return size;
    }
}