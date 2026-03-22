package com.voxelgame.client.network;

import com.voxelgame.client.ClientWorld;
import com.voxelgame.common.network.PacketDecoder;
import com.voxelgame.common.network.PacketEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

/**
 * Netty TCP client. Connects to a game server and manages the channel lifecycle.
 *
 * <h3>Pipeline</h3>
 * <pre>
 * INBOUND  socket → LengthFieldBasedFrameDecoder → PacketDecoder → ServerHandler
 * OUTBOUND socket ← LengthFieldPrepender         ← PacketEncoder ← ServerHandler
 * </pre>
 *
 * <h3>Lifecycle</h3>
 * {@link #connect()} blocks synchronously until the TCP connection is established.
 * After that the Netty I/O thread drives all network activity asynchronously.
 * Call {@link #disconnect()} at shutdown to close the channel gracefully.
 */
public class ClientNetworkManager {

    private static final int MAX_FRAME_BYTES = 1 << 20; // 1 MB

    private final String      host;
    private final int         port;
    private final String      username;
    private final ClientWorld clientWorld;

    private EventLoopGroup group;
    private Channel        channel;

    /**
     * Creates a ClientNetworkManager.
     *
     * @param host        server hostname or IP address
     * @param port        server TCP port
     * @param username    player username (sent in LoginRequestPacket, unverified)
     * @param clientWorld the client's world — receives chunk data from the server
     */
    public ClientNetworkManager(String host, int port, String username, ClientWorld clientWorld) {
        this.host        = host;
        this.port        = port;
        this.username    = username;
        this.clientWorld = clientWorld;
    }

    /**
     * Connects to the server synchronously. Blocks until the TCP connection is
     * established, at which point {@link ServerHandler#channelActive} has already
     * sent the Handshake and LoginRequest packets.
     *
     * @throws Exception if the connection is refused, times out, or the thread is interrupted
     */
    public void connect() throws Exception {
        group = new NioEventLoopGroup();

        Bootstrap bootstrap = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();

                    // Inbound: reassemble length-prefixed frames, decode to Packet objects
                    p.addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_BYTES, 0, 4, 0, 4));
                    p.addLast(new PacketDecoder());

                    // Outbound: serialize Packet to bytes, prepend 4-byte frame length
                    p.addLast(new LengthFieldPrepender(4));
                    p.addLast(new PacketEncoder());

                    // Business logic — sends Handshake+Login in channelActive, routes inbound packets
                    p.addLast(new ServerHandler(username, clientWorld));
                }
            });

        channel = bootstrap.connect(host, port).sync().channel();
        System.out.println("[Client] Connected to " + host + ":" + port);
    }

    /**
     * Closes the channel and shuts down the I/O thread. Safe to call from any thread.
     */
    public void disconnect() {
        if (channel != null) channel.close();
        if (group   != null) group.shutdownGracefully();
        System.out.println("[Client] Disconnected.");
    }

    /**
     * Returns the active Netty channel. Use to write packets directly from game code.
     * Returns {@code null} before {@link #connect()} is called.
     *
     * @return the active channel, or null
     */
    public Channel getChannel() {
        return channel;
    }
}