package com.voxelgame.server;

import com.voxelgame.common.network.packets.ChunkDataPacket;
import com.voxelgame.common.network.packets.UnloadChunkPacket;
import com.voxelgame.common.world.Chunk;
import com.voxelgame.common.world.ChunkPos;
import com.voxelgame.game.World;

import java.util.ArrayList;
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
 * <h3>Phase 5D upgrade path</h3>
 * Currently {@code world.update()} uses the first connected player's position as
 * the streaming center. Phase 5D adds player movement sync — at that point this
 * should expand to compute the union of all player view areas.
 */
public class ServerWorld {

    /**
     * Maximum chunks sent to a single player per tick.
     * Prevents a spike of large ChunkDataPackets flooding a newly connected client.
     */
    private static final int MAX_CHUNKS_PER_PLAYER_PER_TICK = 64;

    private final World world;

    /** Carries a position update from the Netty thread to the tick thread. */
    private record PendingMove(int playerId, float x, float y, float z) {}

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
     */
    public ServerWorld(long seed) {
        this.world = new World(seed);
    }

    /**
     * Advances the server world by one tick. Called at 20 TPS from the server game loop.
     *
     * <ol>
     *   <li>Drains pending player connects and disconnects.</li>
     *   <li>Updates chunk streaming in the underlying {@link World} using the primary
     *       player's position as the streaming center.</li>
     *   <li>Sends newly loaded chunks to each player and unloads chunks that left the
     *       server's loaded set.</li>
     * </ol>
     */
    public void tick() {
        // --- Drain pending position updates ---
        PendingMove move;
        while ((move = pendingMoves.poll()) != null) {
            int id = move.playerId();
            for (PlayerSession p : players) {
                if (p.getPlayerId() == id) {
                    p.updatePosition(move.x(), move.y(), move.z());
                    break;
                }
            }
        }

        // --- Drain pending player connects ---
        // For each new player: announce all existing players to them,
        // then announce them to all existing players. Add them last so
        // they don't receive their own announcement.
        PlayerSession add;
        while ((add = pendingAdds.poll()) != null) {
            for (PlayerSession existing : players) {
                // Tell the new player about each existing player
                add.sendPacket(new com.voxelgame.common.network.packets.PlayerSpawnPacket(
                    existing.getPlayerId(), existing.getUsername(),
                    existing.getX(), existing.getY(), existing.getZ()));
                // Tell each existing player about the new player
                existing.sendPacket(new com.voxelgame.common.network.packets.PlayerSpawnPacket(
                    add.getPlayerId(), add.getUsername(),
                    add.getX(), add.getY(), add.getZ()));
            }
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
                p.sendPacket(despawn);
            }
        }

        if (players.isEmpty()) return;

        // --- Advance chunk streaming ---
        PlayerSession primary = players.get(0);
        world.update(
            primary.getX(), primary.getY(), primary.getZ(),
            0, 0, 0
        );

        // --- Stream chunks to / from each player ---
        Set<ChunkPos> loaded = world.getLoadedChunkPositions();
        for (PlayerSession player : players) {
            streamChunksToPlayer(player, loaded);
        }

        // --- Broadcast positions to all other players ---
        // Runs every tick (20 TPS) — clients interpolate between updates.
        // Only meaningful with 2+ players; the single-player case exits early above.
        if (players.size() > 1) {
            for (PlayerSession mover : players) {
                com.voxelgame.common.network.packets.PlayerMoveCBPacket movePacket =
                    new com.voxelgame.common.network.packets.PlayerMoveCBPacket(
                        mover.getPlayerId(), mover.getX(), mover.getY(), mover.getZ());
                for (PlayerSession other : players) {
                    if (other.getPlayerId() != mover.getPlayerId()) {
                        other.sendPacket(movePacket);
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
     * @param loaded the current set of chunks loaded by the server
     */
    private void streamChunksToPlayer(PlayerSession player, Set<ChunkPos> loaded) {
        // Send chunks that are loaded server-side but not yet sent to this player
        int sent = 0;
        for (ChunkPos pos : loaded) {
            if (sent >= MAX_CHUNKS_PER_PLAYER_PER_TICK) break;
            if (player.hasChunk(pos)) continue;

            Chunk chunk = world.getChunk(pos);
            if (chunk == null) continue; // should not happen, but guard anyway

            player.sendPacket(new ChunkDataPacket(pos.x(), pos.y(), pos.z(), chunk.toBytes()));
            player.markChunkLoaded(pos);
            sent++;
        }

        // Unload chunks the player has that the server has unloaded
        // Collect first to avoid ConcurrentModificationException on the set
        List<ChunkPos> toUnload = new ArrayList<>();
        for (ChunkPos pos : player.getLoadedChunks()) {
            if (!loaded.contains(pos)) toUnload.add(pos);
        }
        for (ChunkPos pos : toUnload) {
            player.sendPacket(new UnloadChunkPacket(pos.x(), pos.y(), pos.z()));
            player.markChunkUnloaded(pos);
        }
    }

    /**
     * Validates and applies a block break from a player. Called from the server tick
     * thread via the pending action queue (Phase 5D will formalize this queue).
     * Broadcasts the change to all players who have the affected chunk loaded.
     *
     * @param worldX world-space X of the broken block
     * @param worldY world-space Y
     * @param worldZ world-space Z
     */
    public void applyBlockBreak(int worldX, int worldY, int worldZ) {
        // Server-side validation goes here in Phase 5D (range check, permissions, etc.)
        world.setBlock(worldX, worldY, worldZ, com.voxelgame.common.world.Block.AIR);
        broadcastBlockChange(worldX, worldY, worldZ, 0); // 0 = AIR ordinal
    }

    /**
     * Validates and applies a block place from a player. Broadcasts the change to all
     * players who have the affected chunk loaded.
     *
     * @param worldX      world-space X of the placed block
     * @param worldY      world-space Y
     * @param worldZ      world-space Z
     * @param blockOrdinal Block.ordinal() of the placed type
     */
    public void applyBlockPlace(int worldX, int worldY, int worldZ, int blockOrdinal) {
        com.voxelgame.common.world.Block[] blocks = com.voxelgame.common.world.Block.values();
        if (blockOrdinal <= 0 || blockOrdinal >= blocks.length) return; // reject invalid/AIR place
        world.setBlock(worldX, worldY, worldZ, blocks[blockOrdinal]);
        broadcastBlockChange(worldX, worldY, worldZ, blockOrdinal);
    }

    /**
     * Sends a BlockChangePacket to every player who has the affected chunk loaded.
     * Players without the chunk don't need the update — they'll receive correct data
     * when the chunk streams to them later.
     */
    private void broadcastBlockChange(int worldX, int worldY, int worldZ, int blockOrdinal) {
        ChunkPos affected = new ChunkPos(
            Math.floorDiv(worldX, com.voxelgame.common.world.Chunk.SIZE),
            Math.floorDiv(worldY, com.voxelgame.common.world.Chunk.SIZE),
            Math.floorDiv(worldZ, com.voxelgame.common.world.Chunk.SIZE)
        );
        com.voxelgame.common.network.packets.BlockChangePacket packet =
            new com.voxelgame.common.network.packets.BlockChangePacket(worldX, worldY, worldZ, blockOrdinal);

        for (PlayerSession player : players) {
            if (player.hasChunk(affected)) {
                player.sendPacket(packet);
            }
        }
    }

    /**
     * Enqueues a position update from a client. Safe to call from the Netty I/O thread.
     *
     * @param playerId the player whose position changed
     * @param x        new world-space X
     * @param y        new world-space Y (feet)
     * @param z        new world-space Z
     */
    public void queuePlayerMove(int playerId, float x, float y, float z) {
        pendingMoves.offer(new PendingMove(playerId, x, y, z));
    }

    // -------------------------------------------------------------------------
    // Player lifecycle — called from Netty I/O threads
    // -------------------------------------------------------------------------

    /**
     * Enqueues a player for addition at the start of the next tick.
     * Safe to call from any thread.
     *
     * @param session the newly logged-in player session
     */
    public void addPlayer(PlayerSession session) {
        pendingAdds.offer(session);
    }

    /**
     * Enqueues a player for removal at the start of the next tick.
     * Safe to call from any thread.
     *
     * @param playerId the ID of the player that disconnected
     */
    public void removePlayer(int playerId) {
        pendingRemovals.offer(playerId);
    }

    /**
     * Shuts down the underlying world's generation threads and releases GPU resources.
     * Must be called on the server shutdown — not a GL context requirement here since
     * the server has no GL dependency, but the executor shutdown matters.
     */
    public void cleanup() {
        world.cleanup();
    }
}