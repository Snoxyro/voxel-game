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
    private final float x;
    private final float y;
    private final float z;
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
