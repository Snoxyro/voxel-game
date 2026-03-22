package com.voxelgame.server.network;

import com.voxelgame.common.network.PacketDecoder;
import com.voxelgame.common.network.PacketEncoder;
import com.voxelgame.server.GameServer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import java.util.concurrent.CountDownLatch;

/**
 * Netty TCP server. Accepts incoming client connections on the configured port
 * and builds a handler pipeline for each one.
 *
 * <h3>Thread model</h3>
 * Netty uses two thread groups:
 * <ul>
 *   <li><b>bossGroup</b> — 1 thread. Accepts new TCP connections and hands them off.</li>
 *   <li><b>workerGroup</b> — N threads (default: 2× CPU cores). Handles I/O for each
 *       accepted connection. All {@link ClientHandler} callbacks fire on a worker thread.</li>
 * </ul>
 * Neither group runs the game loop — that's the main server thread's job.
 *
 * <h3>Pipeline per connection</h3>
 * <pre>
 * INBOUND  socket → LengthFieldBasedFrameDecoder → PacketDecoder → ClientHandler
 * OUTBOUND socket ← LengthFieldPrepender         ← PacketEncoder ← ClientHandler
 * </pre>
 *
 * <h3>Start / stop lifecycle</h3>
 * {@link #start(CountDownLatch)} binds the port synchronously, counts down the latch
 * so the caller knows the server is ready, then blocks on {@code closeFuture().sync()}
 * until {@link #stop()} is called. Designed to run on a dedicated background thread.
 */
public class ServerNetworkManager {

    /** Maximum single packet size in bytes — prevents memory exhaustion from malformed data. */
    private static final int MAX_FRAME_BYTES = 1 << 20; // 1 MB

    private final int        port;
    private final GameServer server;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel        serverChannel;

    /**
     * Creates a ServerNetworkManager. Does not start listening yet — call {@link #start}.
     *
     * @param port   TCP port to listen on
     * @param server the owning GameServer — passed to each ClientHandler for game callbacks
     */
    public ServerNetworkManager(int port, GameServer server) {
        this.port   = port;
        this.server = server;
    }

    /**
     * Binds the port, counts down {@code readyLatch}, then blocks until stopped.
     * Must be called on a dedicated background thread.
     *
     * @param readyLatch counted down once the port is bound and accepting connections
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    public void start(CountDownLatch readyLatch) throws InterruptedException {
        bossGroup   = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                // TCP_NODELAY: disable Nagle's algorithm — send packets immediately, no buffering
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();

                        // Inbound: reassemble length-prefixed frames, then decode to Packet objects
                        // LengthFieldBasedFrameDecoder args:
                        //   maxFrameLength   — reject frames larger than this
                        //   lengthFieldOffset — the length field starts at byte 0
                        //   lengthFieldLength — the length field is 4 bytes
                        //   lengthAdjustment  — no adjustment needed
                        //   initialBytesToStrip — strip the 4-byte length field before handing to decoder
                        p.addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_BYTES, 0, 4, 0, 4));
                        p.addLast(new PacketDecoder());

                        // Outbound: serialize Packet to bytes, then prepend the 4-byte frame length
                        // These must be between the inbound handlers and ClientHandler in the pipeline
                        // so that writes from ClientHandler pass through both before hitting the wire.
                        p.addLast(new LengthFieldPrepender(4));
                        p.addLast(new PacketEncoder());

                        // Business logic — one instance per client connection
                        p.addLast(new ClientHandler(server));
                    }
                });

            serverChannel = bootstrap.bind(port).sync().channel();
            System.out.println("[Server] Listening on port " + port);

            // Signal to Main / GameServer that the port is bound and we're accepting connections
            readyLatch.countDown();

            // Block here until stop() closes the server channel
            serverChannel.closeFuture().sync();

        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    /**
     * Closes the server channel. {@link #start} will unblock and return.
     * Safe to call from any thread.
     */
    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
            System.out.println("[Server] Network shut down.");
        }
    }
}