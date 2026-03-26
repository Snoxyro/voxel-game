package com.voxelgame.game;

import com.voxelgame.common.world.BlockRegistry;
import com.voxelgame.common.world.BlockType;
import com.voxelgame.common.world.Blocks;
import com.voxelgame.common.world.Chunk;
import com.voxelgame.common.world.ChunkPos;
import java.util.Arrays;
import java.util.Map;

public class ChunkMesher {

    private static final float SHADE_TOP    = 1.00f;
    private static final float SHADE_BOTTOM = 0.50f;
    private static final float SHADE_SOUTH  = 0.85f;
    private static final float SHADE_EAST   = 0.75f;
    private static final float SHADE_NORTH  = 0.70f;
    private static final float SHADE_WEST   = 0.65f;

    private static final float AO_STRENGTH_SIDE = 0.30f;
    private static final float AO_STRENGTH_TOP = 0.45f;
    private static final int INITIAL_CAPACITY = 54 * 1024;

    private static final int[][][][] AO_OFFSETS = {
        { {{-1,1,0}, {0,1,-1}, {-1,1,-1}}, {{-1,1,0}, {0,1, 1}, {-1,1, 1}}, {{ 1,1,0}, {0,1, 1}, { 1,1, 1}}, {{ 1,1,0}, {0,1,-1}, { 1,1,-1}} },
        { {{-1,-1,0}, {0,-1, 1}, {-1,-1, 1}}, {{-1,-1,0}, {0,-1,-1}, {-1,-1,-1}}, {{ 1,-1,0}, {0,-1,-1}, { 1,-1,-1}}, {{ 1,-1,0}, {0,-1, 1}, { 1,-1, 1}} },
        { {{-1,0,-1}, {0,-1,-1}, {-1,-1,-1}}, {{-1,0,-1}, {0, 1,-1}, {-1, 1,-1}}, {{ 1,0,-1}, {0, 1,-1}, { 1, 1,-1}}, {{ 1,0,-1}, {0,-1,-1}, { 1,-1,-1}} },
        { {{ 1,0,1}, {0,-1,1}, { 1,-1,1}}, {{ 1,0,1}, {0, 1,1}, { 1, 1,1}}, {{-1,0,1}, {0, 1,1}, {-1, 1,1}}, {{-1,0,1}, {0,-1,1}, {-1,-1,1}} },
        { {{1,0,-1}, {1,-1,0}, {1,-1,-1}}, {{1,0,-1}, {1, 1,0}, {1, 1,-1}}, {{1,0, 1}, {1, 1,0}, {1, 1, 1}}, {{1,0, 1}, {1,-1,0}, {1,-1, 1}} },
        { {{-1,0, 1}, {-1,-1,0}, {-1,-1, 1}}, {{-1,0, 1}, {-1, 1,0}, {-1, 1, 1}}, {{-1,0,-1}, {-1, 1,0}, {-1, 1,-1}}, {{-1,0,-1}, {-1,-1,0}, {-1,-1,-1}} }
    };

    private static final int FACE_TOP    = 0;
    private static final int FACE_BOTTOM = 1;
    private static final int FACE_NORTH  = 2;
    private static final int FACE_SOUTH  = 3;
    private static final int FACE_EAST   = 4;
    private static final int FACE_WEST   = 5;

    public static volatile boolean aoEnabled = true;

    public static float[] mesh(Chunk chunk, ChunkPos pos, Map<ChunkPos, Chunk> neighbors) {
        if (chunk.isAllAir()) return new float[0];

        int minY = chunk.getMinOccupiedY();
        int maxY = chunk.getMaxOccupiedY();

        // Convert the map to a flat array to completely eliminate ChunkPos object allocations in worker threads
        Chunk[] nArray = new Chunk[27];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        nArray[(dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)] = chunk;
                    } else {
                        ChunkPos nPos = new ChunkPos(pos.x() + dx, pos.y() + dy, pos.z() + dz);
                        nArray[(dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)] = neighbors.get(nPos);
                    }
                }
            }
        }

        float[] buf  = new float[INITIAL_CAPACITY];
        int     size = 0;
        int[][] mask = new int[Chunk.SIZE][Chunk.SIZE];

        size = meshTopFaces   (chunk, nArray, buf, size, mask, minY, maxY);
        size = meshBottomFaces(chunk, nArray, buf, size, mask, minY, maxY);
        size = meshNorthFaces (chunk, nArray, buf, size, mask, minY, maxY);
        size = meshSouthFaces (chunk, nArray, buf, size, mask, minY, maxY);
        size = meshEastFaces  (chunk, nArray, buf, size, mask, minY, maxY);
        size = meshWestFaces  (chunk, nArray, buf, size, mask, minY, maxY);

        return Arrays.copyOf(buf, size);
    }

    private static int meshTopFaces(Chunk chunk, Chunk[] nArray, float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, nArray, x, y + 1, z)) {
                        mask[z][x] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, nArray, x, y, z, FACE_TOP);
                    mask[z][x] = packMask(b.getId(),
                        computeAO(chunk, nArray, x, y, z, FACE_TOP, 0),
                        computeAO(chunk, nArray, x, y, z, FACE_TOP, 1),
                        computeAO(chunk, nArray, x, y, z, FACE_TOP, 2),
                        computeAO(chunk, nArray, x, y, z, FACE_TOP, 3), light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 11) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);

                int skyLight = quads[qi+9];
                int blockLight = quads[qi+10];
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 66);
                size = emitQuad(buf, size, color, SHADE_TOP, AO_STRENGTH_TOP, quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.topTextureLayer(), skyLight, blockLight, i, y + 1, j, i, y + 1, j + h, i + w, y + 1, j + h, i + w, y + 1, j);
            }
        }
        return size;
    }

    private static int meshBottomFaces(Chunk chunk, Chunk[] nArray, float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, nArray, x, y - 1, z)) {
                        mask[z][x] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, nArray, x, y, z, FACE_BOTTOM);
                    mask[z][x] = packMask(b.getId(),
                        computeAO(chunk, nArray, x, y, z, FACE_BOTTOM, 0),
                        computeAO(chunk, nArray, x, y, z, FACE_BOTTOM, 1),
                        computeAO(chunk, nArray, x, y, z, FACE_BOTTOM, 2),
                        computeAO(chunk, nArray, x, y, z, FACE_BOTTOM, 3), light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 11) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);

                int skyLight = quads[qi+9];
                int blockLight = quads[qi+10];
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 66);
                size = emitQuad(buf, size, color, SHADE_BOTTOM, AO_STRENGTH_TOP, quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.bottomTextureLayer(), skyLight, blockLight, i, y, j + h, i, y, j, i + w, y, j, i + w, y, j + h);
            }
        }
        return size;
    }

    private static int meshNorthFaces(Chunk chunk, Chunk[] nArray, float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) { Arrays.fill(mask[y], 0); continue; }
                for (int x = 0; x < Chunk.SIZE; x++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, nArray, x, y, z - 1)) {
                        mask[y][x] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, nArray, x, y, z, FACE_NORTH);
                    mask[y][x] = packMask(b.getId(),
                        computeAO(chunk, nArray, x, y, z, FACE_NORTH, 0),
                        computeAO(chunk, nArray, x, y, z, FACE_NORTH, 1),
                        computeAO(chunk, nArray, x, y, z, FACE_NORTH, 2),
                        computeAO(chunk, nArray, x, y, z, FACE_NORTH, 3), light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 11) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);

                int skyLight = quads[qi+9];
                int blockLight = quads[qi+10];
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 66);
                size = emitQuad(buf, size, color, SHADE_NORTH, AO_STRENGTH_SIDE, quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.sideTextureLayer(), skyLight, blockLight, i, j, z, i, j + h, z, i + w, j + h, z, i + w, j, z);
            }
        }
        return size;
    }

    private static int meshSouthFaces(Chunk chunk, Chunk[] nArray, float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) { Arrays.fill(mask[y], 0); continue; }
                for (int x = 0; x < Chunk.SIZE; x++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, nArray, x, y, z + 1)) {
                        mask[y][x] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, nArray, x, y, z, FACE_SOUTH);
                    mask[y][x] = packMask(b.getId(),
                        computeAO(chunk, nArray, x, y, z, FACE_SOUTH, 0),
                        computeAO(chunk, nArray, x, y, z, FACE_SOUTH, 1),
                        computeAO(chunk, nArray, x, y, z, FACE_SOUTH, 2),
                        computeAO(chunk, nArray, x, y, z, FACE_SOUTH, 3), light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 11) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);

                int skyLight = quads[qi+9];
                int blockLight = quads[qi+10];
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 66);
                size = emitQuad(buf, size, color, SHADE_SOUTH, AO_STRENGTH_SIDE, quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.sideTextureLayer(), skyLight, blockLight, i + w, j, z + 1, i + w, j + h, z + 1, i, j + h, z + 1, i, j, z + 1);
            }
        }
        return size;
    }

    private static int meshEastFaces(Chunk chunk, Chunk[] nArray, float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) { Arrays.fill(mask[y], 0); continue; }
                for (int z = 0; z < Chunk.SIZE; z++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, nArray, x + 1, y, z)) {
                        mask[y][z] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, nArray, x, y, z, FACE_EAST);
                    mask[y][z] = packMask(b.getId(),
                        computeAO(chunk, nArray, x, y, z, FACE_EAST, 0),
                        computeAO(chunk, nArray, x, y, z, FACE_EAST, 1),
                        computeAO(chunk, nArray, x, y, z, FACE_EAST, 2),
                        computeAO(chunk, nArray, x, y, z, FACE_EAST, 3), light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 11) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);

                int skyLight = quads[qi+9];
                int blockLight = quads[qi+10];
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 66);
                size = emitQuad(buf, size, color, SHADE_EAST, AO_STRENGTH_SIDE, quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.sideTextureLayer(), skyLight, blockLight, x + 1, j, i, x + 1, j + h, i, x + 1, j + h, i + w, x + 1, j, i + w);
            }
        }
        return size;
    }

    private static int meshWestFaces(Chunk chunk, Chunk[] nArray, float[] buf, int size, int[][] mask, int minY, int maxY) {
        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int y = 0; y < Chunk.SIZE; y++) {
                if (y < minY || y > maxY) { Arrays.fill(mask[y], 0); continue; }
                for (int z = 0; z < Chunk.SIZE; z++) {
                    BlockType b = chunk.getBlock(x, y, z);
                    if (b == Blocks.AIR || !isAirAt(chunk, nArray, x - 1, y, z)) {
                        mask[y][z] = 0;
                        continue;
                    }
                    int light = getLightForFace(chunk, nArray, x, y, z, FACE_WEST);
                    mask[y][z] = packMask(b.getId(),
                        computeAO(chunk, nArray, x, y, z, FACE_WEST, 0),
                        computeAO(chunk, nArray, x, y, z, FACE_WEST, 1),
                        computeAO(chunk, nArray, x, y, z, FACE_WEST, 2),
                        computeAO(chunk, nArray, x, y, z, FACE_WEST, 3), light);
                }
            }
            int[] quads = buildMergedQuads(mask);
            for (int qi = 0; qi < quads.length; qi += 11) {
                int i = quads[qi], j = quads[qi+1], w = quads[qi+2], h = quads[qi+3];
                BlockType blk = BlockRegistry.getById(quads[qi+4]);

                int skyLight = quads[qi+9];
                int blockLight = quads[qi+10];
                float[] color = new float[]{ 1.0f, 1.0f, 1.0f };
                buf = ensureCapacity(buf, size, 66);
                size = emitQuad(buf, size, color, SHADE_WEST, AO_STRENGTH_SIDE, quads[qi+5], quads[qi+6], quads[qi+7], quads[qi+8],
                    w, h, blk.sideTextureLayer(), skyLight, blockLight, x, j, i + w, x, j + h, i + w, x, j + h, i, x, j, i);
            }
        }
        return size;
    }

    private static final int[][] FACE_LIGHT_OFFSET = {
        { 0,  1,  0}, { 0, -1,  0}, { 0,  0, -1}, { 0,  0,  1}, { 1,  0,  0}, {-1,  0,  0}
    };

    private static int getLightAt(Chunk chunk, Chunk[] nArray, int bx, int by, int bz) {
        if (bx >= 0 && bx < Chunk.SIZE && by >= 0 && by < Chunk.SIZE && bz >= 0 && bz < Chunk.SIZE) {
            return (chunk.getSkyLight(bx, by, bz) << 4) | chunk.getBlockLight(bx, by, bz);
        }
        
        int dx = bx < 0 ? -1 : (bx >= Chunk.SIZE ? 1 : 0);
        int dy = by < 0 ? -1 : (by >= Chunk.SIZE ? 1 : 0);
        int dz = bz < 0 ? -1 : (bz >= Chunk.SIZE ? 1 : 0);

        Chunk neighbor = nArray[(dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)];
        if (neighbor == null) return (Chunk.MAX_LIGHT << 4) | 0; // Unloaded space = full sky, zero block

        return (neighbor.getSkyLight(bx & 15, by & 15, bz & 15) << 4) | neighbor.getBlockLight(bx & 15, by & 15, bz & 15);
    }

    private static int getLightForFace(Chunk chunk, Chunk[] nArray, int bx, int by, int bz, int face) {
        int[] off = FACE_LIGHT_OFFSET[face];
        return getLightAt(chunk, nArray, bx + off[0], by + off[1], bz + off[2]);
    }

    private static int computeAO(Chunk chunk, Chunk[] nArray, int bx, int by, int bz, int face, int vertex) {
        if (!aoEnabled) return 3;

        int[][] offsets = AO_OFFSETS[face][vertex];
        boolean side1  = !isAirAt(chunk, nArray, bx + offsets[0][0], by + offsets[0][1], bz + offsets[0][2]);
        boolean side2  = !isAirAt(chunk, nArray, bx + offsets[1][0], by + offsets[1][1], bz + offsets[1][2]);
        boolean corner = !isAirAt(chunk, nArray, bx + offsets[2][0], by + offsets[2][1], bz + offsets[2][2]);

        if (side1 && side2) return 0;
        return 3 - (side1 ? 1 : 0) - (side2 ? 1 : 0) - (corner ? 1 : 0);
    }

    private static int packMask(int blockId, int ao0, int ao1, int ao2, int ao3, int light) {
        int sky = (light >> 4) & 0xF;
        int blk = light & 0xF;
        // Reduces blockId to 13 bits (8192 variants) to fit everything in 32 bits
        return (blockId + 1)
            | (ao0 << 13) | (ao1 << 15) | (ao2 << 17) | (ao3 << 19)
            | (sky << 21) | (blk << 25);
    }

    private static int[] buildMergedQuads(int[][] mask) {
        int[] result = new int[Chunk.SIZE * Chunk.SIZE * 11];
        int count = 0;

        for (int j = 0; j < Chunk.SIZE; j++) {
            for (int i = 0; i < Chunk.SIZE; i++) {
                int packed = mask[j][i];
                if (packed == 0) continue;

                int w = 1;
                while (i + w < Chunk.SIZE && mask[j][i + w] == packed) w++;

                int h = 1;
                outer:
                while (j + h < Chunk.SIZE) {
                    for (int k = 0; k < w; k++)
                        if (mask[j + h][i + k] != packed) break outer;
                    h++;
                }

                result[count++] = i; result[count++] = j; result[count++] = w; result[count++] = h;
                result[count++] = (packed & 0x1FFF) - 1;
                result[count++] = (packed >> 13) & 0x3; result[count++] = (packed >> 15) & 0x3;
                result[count++] = (packed >> 17) & 0x3; result[count++] = (packed >> 19) & 0x3;
                result[count++] = (packed >> 21) & 0xF; result[count++] = (packed >> 25) & 0xF;

                for (int dj = 0; dj < h; dj++)
                    for (int di = 0; di < w; di++)
                        mask[j + dj][i + di] = 0;
            }
        }
        return Arrays.copyOf(result, count);
    }

    private static float[] ensureCapacity(float[] buf, int size, int needed) {
        if (size + needed <= buf.length) return buf;
        return Arrays.copyOf(buf, Math.max(buf.length * 2, size + needed));
    }

    private static boolean isAirAt(Chunk chunk, Chunk[] nArray, int lx, int ly, int lz) {
        if (lx >= 0 && lx < Chunk.SIZE && ly >= 0 && ly < Chunk.SIZE && lz >= 0 && lz < Chunk.SIZE) {
            return chunk.getBlock(lx, ly, lz) == Blocks.AIR;
        }
        int dx = lx < 0 ? -1 : (lx >= Chunk.SIZE ? 1 : 0);
        int dy = ly < 0 ? -1 : (ly >= Chunk.SIZE ? 1 : 0);
        int dz = lz < 0 ? -1 : (lz >= Chunk.SIZE ? 1 : 0);

        Chunk neighbor = nArray[(dx + 1) * 9 + (dy + 1) * 3 + (dz + 1)];
        if (neighbor == null) return true;

        return neighbor.getBlock(lx & 15, ly & 15, lz & 15) == Blocks.AIR;
    }

    private static int emitQuad(float[] buf, int size,
                                 float[] color, float directionalShade, float aoStrength,
                                 int ao0, int ao1, int ao2, int ao3,
                                 float w, float h, int texLayer, int skyLight, int blockLight,
                                 float x0, float y0, float z0, float x1, float y1, float z1,
                                 float x2, float y2, float z2, float x3, float y3, float z3) {
        
        float b0 = directionalShade * (aoStrength + (1.0f - aoStrength) * (ao0 / 3.0f));
        float b1 = directionalShade * (aoStrength + (1.0f - aoStrength) * (ao1 / 3.0f));
        float b2 = directionalShade * (aoStrength + (1.0f - aoStrength) * (ao2 / 3.0f));
        float b3 = directionalShade * (aoStrength + (1.0f - aoStrength) * (ao3 / 3.0f));

        float r0 = color[0]*b0, g0 = color[1]*b0, bl0 = color[2]*b0;
        float r1 = color[0]*b1, g1 = color[1]*b1, bl1 = color[2]*b1;
        float r2 = color[0]*b2, g2 = color[1]*b2, bl2 = color[2]*b2;
        float r3 = color[0]*b3, g3 = color[1]*b3, bl3 = color[2]*b3;

        float tl = texLayer;
        float skyB = (float) Math.pow(0.8, 15.0 - skyLight);
        float blkB = (float) Math.pow(0.8, 15.0 - blockLight);

        if (ao0 + ao2 < ao1 + ao3) {
            buf[size++]=x1; buf[size++]=y1; buf[size++]=z1; buf[size++]=r1; buf[size++]=g1; buf[size++]=bl1; buf[size++]=0f; buf[size++]=h;  buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
            buf[size++]=x2; buf[size++]=y2; buf[size++]=z2; buf[size++]=r2; buf[size++]=g2; buf[size++]=bl2; buf[size++]=w;  buf[size++]=h;  buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
            buf[size++]=x3; buf[size++]=y3; buf[size++]=z3; buf[size++]=r3; buf[size++]=g3; buf[size++]=bl3; buf[size++]=w;  buf[size++]=0f; buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
            buf[size++]=x1; buf[size++]=y1; buf[size++]=z1; buf[size++]=r1; buf[size++]=g1; buf[size++]=bl1; buf[size++]=0f; buf[size++]=h;  buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
            buf[size++]=x3; buf[size++]=y3; buf[size++]=z3; buf[size++]=r3; buf[size++]=g3; buf[size++]=bl3; buf[size++]=w;  buf[size++]=0f; buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
            buf[size++]=x0; buf[size++]=y0; buf[size++]=z0; buf[size++]=r0; buf[size++]=g0; buf[size++]=bl0; buf[size++]=0f; buf[size++]=0f; buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
        } else {
            buf[size++]=x0; buf[size++]=y0; buf[size++]=z0; buf[size++]=r0; buf[size++]=g0; buf[size++]=bl0; buf[size++]=0f; buf[size++]=0f; buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
            buf[size++]=x1; buf[size++]=y1; buf[size++]=z1; buf[size++]=r1; buf[size++]=g1; buf[size++]=bl1; buf[size++]=0f; buf[size++]=h;  buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
            buf[size++]=x2; buf[size++]=y2; buf[size++]=z2; buf[size++]=r2; buf[size++]=g2; buf[size++]=bl2; buf[size++]=w;  buf[size++]=h;  buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
            buf[size++]=x0; buf[size++]=y0; buf[size++]=z0; buf[size++]=r0; buf[size++]=g0; buf[size++]=bl0; buf[size++]=0f; buf[size++]=0f; buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
            buf[size++]=x2; buf[size++]=y2; buf[size++]=z2; buf[size++]=r2; buf[size++]=g2; buf[size++]=bl2; buf[size++]=w;  buf[size++]=h;  buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
            buf[size++]=x3; buf[size++]=y3; buf[size++]=z3; buf[size++]=r3; buf[size++]=g3; buf[size++]=bl3; buf[size++]=w;  buf[size++]=0f; buf[size++]=tl; buf[size++]=skyB; buf[size++]=blkB;
        }

        return size;
    }
}