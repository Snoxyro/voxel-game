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
    private static final int MAX_CHUNKS_PER_PLAYER_PER_TICK = 16;

    private final World world;

    /**
     * Queues for cross-thread player lifecycle events.
     * Netty I/O threads write; server tick thread drains.
     */
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
        // --- Drain pending player connects / disconnects ---
        PlayerSession add;
        while ((add = pendingAdds.poll()) != null) players.add(add);

        Integer removeId;
        while ((removeId = pendingRemovals.poll()) != null) {
            int id = removeId; // effectively final for lambda
            players.removeIf(p -> p.getPlayerId() == id);
        }

        if (players.isEmpty()) return;

        // --- Advance chunk streaming ---
        // Phase 5D: replace with union of all player positions.
        // For now, stream around the first player's position (or spawn if no movement yet).
        PlayerSession primary = players.get(0);
        world.update(
            primary.getX(), primary.getY(), primary.getZ(),
            0, 0, 0  // zero direction = pure distance ordering, no look-ahead bias
        );

        // --- Stream chunks to / from each player ---
        Set<ChunkPos> loaded = world.getLoadedChunkPositions();
        for (PlayerSession player : players) {
            streamChunksToPlayer(player, loaded);
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