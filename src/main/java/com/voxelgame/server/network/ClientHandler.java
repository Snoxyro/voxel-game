package com.voxelgame.server.network;

import com.voxelgame.common.network.Packet;
import com.voxelgame.common.network.PacketId;
import com.voxelgame.common.network.packets.*;
import com.voxelgame.server.GameServer;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * Server-side handler for one client connection. Manages the connection state
 * machine and delegates game events to {@link GameServer}.
 *
 * <h3>State machine</h3>
 * <pre>
 *   HANDSHAKING  ← initial state on connect
 *       ↓  receive HandshakePacket (correct version)
 *   LOGGING_IN
 *       ↓  receive LoginRequestPacket → server sends LoginSuccessPacket
 *   PLAYING      ← game packets handled here
 * </pre>
 *
 * <h3>Thread safety</h3>
 * All callbacks fire on the channel's Netty I/O thread. {@link GameServer} callbacks
 * ({@link GameServer#onPlayerLogin}, {@link GameServer#onPlayerDisconnect}) are safe
 * to call from this thread — they only write into concurrent queues in {@link com.voxelgame.server.ServerWorld}.
 */
public class ClientHandler extends SimpleChannelInboundHandler<Packet> {

    private enum State { HANDSHAKING, LOGGING_IN, PLAYING }

    private final GameServer server;
    private State  state    = State.HANDSHAKING;
    private String username = "(unknown)";
    private int    playerId = -1;

    /**
     * Creates a ClientHandler for one connection.
     *
     * @param server the owning game server — receives login and disconnect callbacks
     */
    public ClientHandler(GameServer server) {
        this.server = server;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        System.out.println("[Server] Client connected: " + ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.out.println("[Server] Client disconnected: " + username
            + " (" + ctx.channel().remoteAddress() + ")");
        if (state == State.PLAYING) {
            server.onPlayerDisconnect(playerId, username);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Packet msg) {
        switch (state) {
            case HANDSHAKING -> handleHandshake(ctx, msg);
            case LOGGING_IN  -> handleLogin(ctx, msg);
            case PLAYING     -> handlePlaying(ctx, msg);
        }
    }

    // -------------------------------------------------------------------------
    // State handlers
    // -------------------------------------------------------------------------

    private void handleHandshake(ChannelHandlerContext ctx, Packet msg) {
        if (!(msg instanceof HandshakePacket p)) {
            System.err.println("[Server] Expected HandshakePacket, got "
                + msg.getClass().getSimpleName() + " — closing");
            ctx.close();
            return;
        }
        if (p.protocolVersion() != PacketId.PROTOCOL_VERSION) {
            System.err.printf("[Server] Protocol mismatch: client=%d server=%d — closing%n",
                p.protocolVersion(), PacketId.PROTOCOL_VERSION);
            ctx.close();
            return;
        }
        state = State.LOGGING_IN;
    }

    private void handleLogin(ChannelHandlerContext ctx, Packet msg) {
        if (!(msg instanceof LoginRequestPacket p)) {
            System.err.println("[Server] Expected LoginRequestPacket, got "
                + msg.getClass().getSimpleName() + " — closing");
            ctx.close();
            return;
        }
        username = p.username();
        playerId = server.onPlayerLogin(ctx.channel(), username);

        ctx.writeAndFlush(new LoginSuccessPacket(
            playerId,
            GameServer.SPAWN_X,
            GameServer.SPAWN_Y,
            GameServer.SPAWN_Z
        ));
        state = State.PLAYING;
    }

    private void handlePlaying(ChannelHandlerContext ctx, Packet msg) {
        // Phase 5C: handle BlockBreak / BlockPlace
        // Phase 5D: handle PlayerMove
        System.out.println("[Server] Unhandled play-state packet from " + username
            + ": " + msg.getClass().getSimpleName());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.err.println("[Server] Exception from " + username + ": " + cause.getMessage());
        ctx.close();
    }
}