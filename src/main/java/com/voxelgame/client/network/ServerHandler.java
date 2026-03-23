package com.voxelgame.client.network;

import com.voxelgame.client.ClientWorld;
import com.voxelgame.common.network.Packet;
import com.voxelgame.common.network.packets.*;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Client-side channel handler. Receives packets from the server and routes them
 * to the appropriate client systems.
 *
 * <h3>Thread safety</h3>
 * All methods here run on the Netty I/O thread. {@link ClientWorld} exposes
 * thread-safe entry points ({@link ClientWorld#queueChunkData}, {@link ClientWorld#queueUnload},
 * {@link ClientWorld#setSpawn}) for exactly this reason — no GL calls or map access here.
 *
 * <h3>Growth plan</h3>
 * Phase 5C: handle {@code BlockChangePacket}.
 * Phase 5D: handle {@code PlayerSpawnPacket}, {@code PlayerMovePacket}, {@code PlayerDespawnPacket}.
 */
public class ServerHandler extends SimpleChannelInboundHandler<Packet> {

    private final String      username;
    private final ClientWorld clientWorld;

    /**
     * Creates a ServerHandler.
     *
     * @param username    the local player's username — sent in the login packet
     * @param clientWorld the client's world — receives chunk data from the server
     */
    public ServerHandler(String username, ClientWorld clientWorld) {
        this.username    = username;
        this.clientWorld = clientWorld;
    }

    /**
     * Sends handshake and login request immediately after the TCP connection is established.
     * Both packets are written before flushing to batch them into a single TCP write.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.write(new HandshakePacket());
        ctx.writeAndFlush(new LoginRequestPacket(username));
        System.out.println("[Client] Sent handshake + login request as '" + username + "'");
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet msg) {
        if (msg instanceof LoginSuccessPacket p) {
            handleLoginSuccess(p);
        } else if (msg instanceof ChunkDataPacket p) {
            handleChunkData(p);
        } else if (msg instanceof UnloadChunkPacket p) {
            handleUnloadChunk(p);
        } else if (msg instanceof BlockChangePacket p) {
            clientWorld.queueBlockChange(p.worldX(), p.worldY(), p.worldZ(), p.blockId());
        } else if (msg instanceof PlayerSpawnPacket p) {
            clientWorld.queueRemotePlayerSpawn(p.playerId(), p.username(), p.x(), p.y(), p.z());
        } else if (msg instanceof PlayerMoveCBPacket p) {
            clientWorld.queueRemotePlayerMove(p.playerId(), p.x(), p.y(), p.z());
        } else if (msg instanceof PlayerDespawnPacket p) {
            clientWorld.queueRemotePlayerDespawn(p.playerId());
        } else {
            System.out.println("[Client] Unhandled packet: " + msg.getClass().getSimpleName());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println("[Client] Disconnected from server.");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[Client] Network error: " + cause.getMessage());
        ctx.close();
    }

    // -------------------------------------------------------------------------
    // Packet handlers
    // -------------------------------------------------------------------------

    private void handleLoginSuccess(LoginSuccessPacket p) {
        System.out.printf("[Client] Login successful — playerId=%d, spawn=(%.1f, %.1f, %.1f)%n",
            p.playerId(), p.spawnX(), p.spawnY(), p.spawnZ());
        clientWorld.setSpawn(p.spawnX(), p.spawnY(), p.spawnZ());
    }

    private void handleChunkData(ChunkDataPacket p) {
        // queueChunkData is thread-safe — converts bytes to Chunk here on the Netty
        // thread, then enqueues for the main thread to mesh and upload.
        clientWorld.queueChunkData(p.cx(), p.cy(), p.cz(), p.blockData());
    }

    private void handleUnloadChunk(UnloadChunkPacket p) {
        clientWorld.queueUnload(p.cx(), p.cy(), p.cz());
    }
}