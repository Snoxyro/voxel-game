package com.voxelgame.server;

import com.voxelgame.common.network.Packet;
import com.voxelgame.common.network.packets.BlockChangePacket;
import com.voxelgame.common.network.packets.ChunkDataPacket;
import com.voxelgame.common.network.packets.UnloadChunkPacket;
import com.voxelgame.common.world.BlockType;
import com.voxelgame.common.world.Blocks;
import com.voxelgame.common.world.Chunk;
import com.voxelgame.common.world.ChunkPos;
import com.voxelgame.game.ChunkStorage;
import com.voxelgame.game.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Server-side world. Wraps the existing {@link World} (which handles terrain generation,
 * chunk streaming, and worker threads) and layers player-aware chunk delivery on top.
 *
 * <h3>Responsibility split</h3>
 * {@link World} generates and stores chunks — it is the authoritative block database.
 * {@code ServerWorld} tracks which chunks each connected player has received and
 * sends {@link ChunkDataPacket} / {@link UnloadChunkPacket} to keep clients in sync.
 *
 * <h3>Threading</h3>
 * {@link #tick()} runs on the server game loop thread (20 TPS).
 * {@link #addPlayer} and {@link #removePlayer} are called from Netty I/O threads
 * when clients connect and disconnect — they write into concurrent queues that
 * {@link #tick()} drains at the start of each tick. This is the same handoff
 * pattern used by {@link World}'s worker thread architecture.
 *
 * <h3>Streaming model</h3>
 * {@link World#update(List)} receives all connected player viewpoints each tick,
 * so chunk scheduling and unloading is computed as the union of player view areas.
 */
public class ServerWorld {

    /**
     * Maximum chunks sent to a single player per tick.
     * Prevents a spike of large ChunkDataPackets flooding a newly connected client.
     */
    private static final int MAX_CHUNKS_PER_PLAYER_PER_TICK = 64;

    private final World world;

    /** Carries a position update from the Netty thread to the tick thread. */
    private record PendingMove(int playerId, float x, float y, float z, float yaw, float pitch) {}

    /**
     * Queues for cross-thread player lifecycle events.
     * Netty I/O threads write; server tick thread drains.
     */
    private final ConcurrentLinkedQueue<PendingMove> pendingMoves = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PlayerSession> pendingAdds      = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Integer>        pendingRemovals  = new ConcurrentLinkedQueue<>(); // by playerId

    /** Active player list. Accessed only from the server tick thread after draining. */
    private final List<PlayerSession> players = new ArrayList<>();

    /**
     * Creates a ServerWorld with the given world seed.
     *
     * @param seed world generation seed — same seed produces identical terrain every run
     * @param storage chunk storage implementation for persistence
        * @thread server-tick (construction thread)
        * @gl-state n/a
     */
    public ServerWorld(long seed, ChunkStorage storage) {
        this.world = new World(seed, storage);
    }

    /**
     * Advances the server world by one tick. Called at 20 TPS from the server game loop.
     *
     * <ol>
     *   <li>Drains pending player connects and disconnects.</li>
     *   <li>Updates chunk streaming in the underlying {@link World} using all
     *       connected player viewpoints.</li>
     *   <li>Sends newly loaded chunks to each player and unloads chunks that left the
     *       server's loaded set.</li>
     * </ol>
    *
    * @thread server-tick
    * @gl-state n/a
    * @see World#update(List)
     */
    public void tick() {
        // --- Drain pending position updates ---
        PendingMove move;
        while ((move = pendingMoves.poll()) != null) {
            int id = move.playerId();
            for (PlayerSession p : players) {
                if (p.getPlayerId() == id) {
                    p.updatePosition(move.x(), move.y(), move.z(), move.yaw(), move.pitch());
                    break;
                }
            }
        }

        // --- Drain pending player connects ---
        // Just add the player — the visibility loop below sends spawn packets on the
        // first tick where two players are within range of each other.
        PlayerSession add;
        while ((add = pendingAdds.poll()) != null) {
            players.add(add);
        }

        // --- Drain pending player disconnects ---
        Integer removeId;
        while ((removeId = pendingRemovals.poll()) != null) {
            int id = removeId;
            players.removeIf(p -> p.getPlayerId() == id);
            // Announce departure to all remaining players
            com.voxelgame.common.network.packets.PlayerDespawnPacket despawn =
                new com.voxelgame.common.network.packets.PlayerDespawnPacket(id);
            for (PlayerSession p : players) {
                if (p.isPlayerVisible(id)) {
                    p.sendPacket(despawn);
                    p.removeVisiblePlayer(id);
                }
            }
        }

        if (players.isEmpty()) return;

        // --- Advance chunk streaming ---
        // Build a viewer list from every connected player so World loads the union
        // of all players' render areas, not just the first player's.
        List<World.ViewerInfo> viewers = new ArrayList<>();
        for (PlayerSession p : players) {
            float[] dir = yawPitchToDir(p.getYaw(), p.getPitch());
            viewers.add(new World.ViewerInfo(p.getX(), p.getY(), p.getZ(), dir[0], dir[1], dir[2]));
        }
        world.update(viewers);

        // --- Stream chunks to / from each player ---
        Set<ChunkPos> loaded = world.getLoadedChunkPositions();
        for (PlayerSession player : players) {
            streamChunksToPlayer(player, loaded);
        }

        // --- Broadcast positions to all other players ---
        // Visibility management — runs every tick for every player pair.
        // Handles dynamic spawn/despawn as players move into and out of each other's
        // render distance, and sends position updates only to players who have the
        // mover currently visible.
        if (players.size() > 1) {
            float renderDistBlocks = World.RENDER_DISTANCE_H * com.voxelgame.common.world.Chunk.SIZE;
            float renderDistSq = renderDistBlocks * renderDistBlocks;

            for (PlayerSession observer : players) {
                for (PlayerSession other : players) {
                    if (observer.getPlayerId() == other.getPlayerId()) continue;

                    float dx = other.getX() - observer.getX();
                    float dz = other.getZ() - observer.getZ();
                    boolean inRange = dx * dx + dz * dz <= renderDistSq;
                    boolean currentlyVisible = observer.isPlayerVisible(other.getPlayerId());

                    if (inRange && !currentlyVisible) {
                        // Other player entered range — announce them and start sending moves
                        observer.sendPacket(new com.voxelgame.common.network.packets.PlayerSpawnPacket(
                            other.getPlayerId(), other.getUsername(),
                            other.getX(), other.getY(), other.getZ()));
                        observer.addVisiblePlayer(other.getPlayerId());
                    } else if (!inRange && currentlyVisible) {
                        // Other player left range — remove their model
                        observer.sendPacket(new com.voxelgame.common.network.packets.PlayerDespawnPacket(
                            other.getPlayerId()));
                        observer.removeVisiblePlayer(other.getPlayerId());
                    } else if (inRange) {
                        // In range and already visible — send position update
                        observer.sendPacket(new com.voxelgame.common.network.packets.PlayerMoveCBPacket(
                            other.getPlayerId(), other.getX(), other.getY(), other.getZ()));
                    }
                }
            }
        }
    }

    /**
     * Sends newly loaded chunks to a player and unloads chunks that have left the
     * server's loaded set.
     *
     * <p>The send cap ({@link #MAX_CHUNKS_PER_PLAYER_PER_TICK}) prevents flooding
     * a new client with hundreds of large packets in a single tick. Remaining chunks
     * are picked up in subsequent ticks as the set is iterated again.
     *
     * @param player the player to update
     * @param loaded the set of currently loaded chunk positions in the world
    * @thread server-tick
    * @gl-state n/a
     */
    private void streamChunksToPlayer(PlayerSession player, Set<ChunkPos> loaded) {
        // Compute the player's current chunk-space position for distance sorting.
        // Spawn chunks must arrive before gravity can drop the player — sorting
        // nearest-first guarantees terrain exists underfoot before distant chunks stream in.
        int pcx = Math.floorDiv((int) player.getX(), Chunk.SIZE);
        int pcy = Math.floorDiv((int) player.getY(), Chunk.SIZE);
        int pcz = Math.floorDiv((int) player.getZ(), Chunk.SIZE);

        // Collect chunks the server has loaded but has not yet sent to this player,
        // then sort from nearest to farthest chunk position.
        List<ChunkPos> toSend = new ArrayList<>();
        for (ChunkPos pos : loaded) {
            if (!player.hasChunk(pos)) toSend.add(pos);
        }
        // Apply the same 75/25 bias used by World.tickGenerationQueue:
        // chunks in the player's look direction jump the queue, but all chunks
        // eventually stream regardless of look direction.
        float[] dir = yawPitchToDir(player.getYaw(), player.getPitch());
        final float dirX = dir[0], dirY = dir[1], dirZ = dir[2];
        toSend.sort(Comparator.comparingDouble(pos -> {
            int dx = pos.x() - pcx, dy = pos.y() - pcy, dz = pos.z() - pcz;
            float distSq = dx * dx + dy * dy + dz * dz;
            float dot = 0f;
            float len = (float) Math.sqrt(distSq);
            if (len > 0.001f) {
                dot = (dx / len) * dirX + (dy / len) * dirY + (dz / len) * dirZ;
            }
            return distSq * (1.0 - dot * 0.5);
        }));

        int sent = 0;
        for (ChunkPos pos : toSend) {
            if (sent >= MAX_CHUNKS_PER_PLAYER_PER_TICK) break;

            Chunk chunk = world.getChunk(pos);
            if (chunk == null) continue; // guard: should not happen

            player.sendPacket(new ChunkDataPacket(pos.x(), pos.y(), pos.z(), chunk.toBytes()));
            player.markChunkLoaded(pos);
            sent++;
        }

        // Unload chunks the player has that the server has unloaded.
        // Collected into a separate list first to avoid ConcurrentModificationException.
        List<ChunkPos> toUnload = new ArrayList<>();
        // Unload if the server dropped the chunk OR if it left this player's personal range.
        // The second condition handles the case where the server keeps a chunk loaded for
        // another player — without it, players see chunks from the other side of the world.
        for (ChunkPos pos : player.getLoadedChunks()) {
            if (!loaded.contains(pos) || !isInPlayerRange(pos, player)) toUnload.add(pos);
        }
        for (ChunkPos pos : toUnload) {
            player.sendPacket(new UnloadChunkPacket(pos.x(), pos.y(), pos.z()));
            player.markChunkUnloaded(pos);
        }
    }

    /**
     * Converts yaw and pitch angles (degrees) to a normalised world-space direction
     * vector matching the same convention used by {@link com.voxelgame.engine.GameLoop}.
     *
     * @param yaw   look yaw in degrees
     * @param pitch look pitch in degrees
     * @return float[3] normalised direction {x, y, z}
    * @thread server-tick
    * @gl-state n/a
     */
    private static float[] yawPitchToDir(float yaw, float pitch) {
        float yawRad   = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float cosPitch = (float) Math.cos(pitchRad);
        return new float[] {
            (float) Math.cos(yawRad) * cosPitch,
            (float) Math.sin(pitchRad),
            (float) Math.sin(yawRad) * cosPitch
        };
    }

    /**
     * Returns true if the given chunk is within this player's personal render distance.
     * Used to unload chunks from a player's client even when the server keeps them
     * loaded for another player.
     *
     * @param pos    chunk to test
     * @param player the player whose view area is checked
     * @return true if the chunk is within range
    * @thread server-tick
    * @gl-state n/a
     */
    private boolean isInPlayerRange(ChunkPos pos, PlayerSession player) {
        int pcx = Math.floorDiv((int) player.getX(), Chunk.SIZE);
        int pcy = Math.floorDiv((int) player.getY(), Chunk.SIZE);
        int pcz = Math.floorDiv((int) player.getZ(), Chunk.SIZE);
        int dx = pos.x() - pcx, dy = pos.y() - pcy, dz = pos.z() - pcz;
        int rdh = world.getRenderDistanceH();
        return dx * dx + dz * dz <= rdh * rdh && Math.abs(dy) <= World.RENDER_DISTANCE_V;
    }

    /**
     * Validates and applies a block break from a player. Called from the server tick
     * thread via the pending action queue (Phase 5D will formalize this queue).
     * Broadcasts the change to all players who have the affected chunk loaded.
     *
     * @param worldX world-space X of the broken block
     * @param worldY world-space Y
     * @param worldZ world-space Z
    * @thread server-tick
    * @gl-state n/a
    * @see #broadcastBlockChange(int, int, int, BlockType)
     */
    public void applyBlockBreak(int worldX, int worldY, int worldZ) {
        // Server-side validation goes here in Phase 5D (range check, permissions, etc.)
        world.setBlock(worldX, worldY, worldZ, Blocks.AIR);
        broadcastBlockChange(worldX, worldY, worldZ, Blocks.AIR);
    }

    /**
     * Validates and applies a block place from a player. Broadcasts the change to all
     * players who have the affected chunk loaded.
     *
     * @param worldX      world-space X of the placed block
     * @param worldY      world-space Y
     * @param worldZ      world-space Z
     * @param blockType placed block type
    * @thread server-tick
    * @gl-state n/a
    * @see #isBlockInsideAnyPlayer(int, int, int)
     */
    public void applyBlockPlace(int worldX, int worldY, int worldZ, BlockType blockType) {
        if (blockType == Blocks.AIR) return; // reject AIR place
        if (isBlockInsideAnyPlayer(worldX, worldY, worldZ)) return; // reject placement inside a player hitbox
        world.setBlock(worldX, worldY, worldZ, blockType);
        broadcastBlockChange(worldX, worldY, worldZ, blockType);
    }

    /**
     * Sends a BlockChangePacket to every player who has the affected chunk loaded.
     * Players without the chunk don't need the update — they'll receive correct data
     * when the chunk streams to them later.
        *
        * @thread server-tick
        * @gl-state n/a
     */
    private void broadcastBlockChange(int worldX, int worldY, int worldZ, BlockType blockType) {
        ChunkPos affected = new ChunkPos(
            Math.floorDiv(worldX, com.voxelgame.common.world.Chunk.SIZE),
            Math.floorDiv(worldY, com.voxelgame.common.world.Chunk.SIZE),
            Math.floorDiv(worldZ, com.voxelgame.common.world.Chunk.SIZE)
        );
        BlockChangePacket packet = new BlockChangePacket(worldX, worldY, worldZ, blockType.getId());

        for (PlayerSession player : players) {
            if (player.hasChunk(affected)) {
                player.sendPacket(packet);
            }
        }
    }

    /**
     * Broadcasts a packet to every connected player.
     * Called from the server tick thread.
     *
     * @param packet the packet to send
        * @thread server-tick
        * @gl-state n/a
     */
    public void broadcastToAll(Packet packet) {
        for (PlayerSession session : players) {
            session.getChannel().writeAndFlush(packet);
        }
    }

    /**
     * Returns true if the unit cube at (worldX, worldY, worldZ) overlaps the AABB
     * of any currently connected player.
     *
     * <p>Player AABB mirrors {@link com.voxelgame.common.world.PhysicsBody}:
     * width=0.6, height=1.8, depth=0.6, with origin at feet position (px, py, pz).
     * <ul>
     *   <li>Min: (px - 0.3, py,       pz - 0.3)</li>
     *   <li>Max: (px + 0.3, py + 1.8, pz + 0.3)</li>
     * </ul>
     *
     * <p><b>Threading note:</b> Called from the Netty I/O thread, reads the {@code players}
     * list — the same race that exists in {@link #broadcastBlockChange}. Player position
     * fields are {@code volatile} so individual reads are safe. Acceptable until block
     * actions are queued through the tick thread.
     *
     * @param worldX world-space X of the block to place
     * @param worldY world-space Y
     * @param worldZ world-space Z
     * @return true if the block volume intersects any player hitbox
    * @thread server-tick
    * @gl-state n/a
     */
    private boolean isBlockInsideAnyPlayer(int worldX, int worldY, int worldZ) {
        // Block occupies the unit cube: min=(worldX, worldY, worldZ), max=(worldX+1, worldY+1, worldZ+1)
        for (PlayerSession player : players) {
            float px = player.getX();
            float py = player.getY();
            float pz = player.getZ();

            // Two AABBs overlap only when all three axes overlap simultaneously.
            // Condition: blockMax > playerMin AND blockMin < playerMax on every axis.
            boolean overlapX = (worldX + 1) > (px - 0.3f) && worldX < (px + 0.3f);
            boolean overlapY = (worldY + 1) > py           && worldY < (py + 1.8f);
            boolean overlapZ = (worldZ + 1) > (pz - 0.3f) && worldZ < (pz + 0.3f);

            if (overlapX && overlapY && overlapZ) return true;
        }
        return false;
    }

    /**
     * Propagates a render distance change from the settings system to the underlying world.
     * Safe to call from any thread — {@link World#setRenderDistance} is thread-safe
     * (only writes a volatile flag and a primitive field).
     *
     * @param chunks new horizontal render distance in chunk units
        * @thread any
        * @gl-state n/a
     */
    public void setRenderDistance(int chunks) {
        world.setRenderDistance(chunks);
    }

    /**
     * Enqueues a position and look direction update from a client. Safe to call
     * from the Netty I/O thread.
     *
     * @param playerId the player whose position changed
     * @param x        new world-space X
     * @param y        new world-space Y (feet)
     * @param z        new world-space Z
     * @param yaw      look yaw in degrees
     * @param pitch    look pitch in degree
    * @thread netty-io
    * @gl-state n/a
     */
    public void queuePlayerMove(int playerId, float x, float y, float z, float yaw, float pitch) {
        pendingMoves.offer(new PendingMove(playerId, x, y, z, yaw, pitch));
    }

    // -------------------------------------------------------------------------
    // Player lifecycle — called from Netty I/O threads
    // -------------------------------------------------------------------------

    /**
     * Enqueues a player for addition at the start of the next tick.
     * Safe to call from any thread.
     *
     * @param session the newly logged-in player session
        * @thread netty-io
        * @gl-state n/a
     */
    public void addPlayer(PlayerSession session) {
        pendingAdds.offer(session);
    }

    /**
     * Enqueues a player for removal at the start of the next tick.
     * Safe to call from any thread.
     *
     * @param playerId the ID of the player that disconnected
        * @thread netty-io
        * @gl-state n/a
     */
    public void removePlayer(int playerId) {
        pendingRemovals.offer(playerId);
    }

    /**
     * Shuts down the underlying world's generation threads and releases GPU resources.
     * Must be called on the server shutdown — not a GL context requirement here since
     * the server has no GL dependency, but the executor shutdown matters.
        *
        * @thread server-tick
        * @gl-state n/a
     */
    public void cleanup() {
        world.cleanup();
    }
}