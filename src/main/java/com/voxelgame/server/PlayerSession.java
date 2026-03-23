package com.voxelgame.server;

import com.voxelgame.common.network.Packet;
import com.voxelgame.common.world.ChunkPos;
import io.netty.channel.Channel;

import java.util.HashSet;
import java.util.Set;

/**
 * Represents a connected player session on the server.
 */
public final class PlayerSession {

    private final int playerId;
    private final String username;
    private final Channel channel;
    // Position — updated each tick from PlayerMoveSBPacket on the server tick thread.
    // Written only from the tick thread (via ClientHandler queuing), so no volatile needed
    // as long as updates always flow through the pending queue pattern.
    private volatile float x;
    private volatile float y;
    private volatile float z;
    private final Set<ChunkPos> loadedChunks = new HashSet<>();

    /**
     * Creates a new player session.
     *
     * @param playerId player id
     * @param username player username
     * @param channel player network channel
     * @param x player x position
     * @param y player y position
     * @param z player z position
     */
    public PlayerSession(int playerId, String username, Channel channel, float x, float y, float z) {
        this.playerId = playerId;
        this.username = username;
        this.channel = channel;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Updates the player's known position. Called on the server tick thread
     * after draining the pending move queue.
     *
     * @param x world-space X
     * @param y world-space Y (feet)
     * @param z world-space Z
     */
    public void updatePosition(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Sends a packet to this player.
     *
     * @param packet packet to send
     */
    public void sendPacket(Packet packet) {
        channel.writeAndFlush(packet);
    }

    /**
     * Checks whether the given chunk is currently loaded for this player.
     *
     * @param pos chunk position
     * @return true if loaded; false otherwise
     */
    public boolean hasChunk(ChunkPos pos) {
        return loadedChunks.contains(pos);
    }

    /**
     * Marks a chunk as loaded for this player.
     *
     * @param pos chunk position
     */
    public void markChunkLoaded(ChunkPos pos) {
        loadedChunks.add(pos);
    }

    /**
     * Marks a chunk as unloaded for this player.
     *
     * @param pos chunk position
     */
    public void markChunkUnloaded(ChunkPos pos) {
        loadedChunks.remove(pos);
    }

    /**
     * Returns the live loaded chunk set.
     * Callers must not modify it during iteration.
     *
     * @return live loaded chunk set
     */
    public Set<ChunkPos> getLoadedChunks() {
        return loadedChunks;
    }

    /**
     * Gets the player id.
     *
     * @return player id
     */
    public int getPlayerId() {
        return playerId;
    }

    /**
     * Gets the username.
     *
     * @return username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Gets the x position.
     *
     * @return x position
     */
    public float getX() {
        return x;
    }

    /**
     * Gets the y position.
     *
     * @return y position
     */
    public float getY() {
        return y;
    }

    /**
     * Gets the z position.
     *
     * @return z position
     */
    public float getZ() {
        return z;
    }
}
