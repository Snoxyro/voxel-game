package com.voxelgame.common.network;

/**
 * All packet IDs in the protocol. Shared by client and server.
 *
 * <p>Serverbound packets (client → server): 0x00–0x0F
 * Clientbound packets (server → client): 0x10–0x1F
 *
 * <p>The current protocol version is {@link #PROTOCOL_VERSION}. When the packet
 * format changes in a breaking way, increment this. Clients with a mismatched
 * version are rejected during the handshake.
 */
public enum PacketId {

    // --- Serverbound (client → server) ---
    HANDSHAKE          (0x00),
    LOGIN_REQUEST      (0x01),
    PLAYER_MOVE_SB     (0x02),
    BLOCK_BREAK        (0x03),
    BLOCK_PLACE        (0x04),
    KEEPALIVE_RESPONSE (0x05),

    // --- Clientbound (server → client) ---
    LOGIN_SUCCESS      (0x10),
    CHUNK_DATA         (0x11),
    UNLOAD_CHUNK       (0x12),
    BLOCK_CHANGE       (0x13),
    PLAYER_SPAWN       (0x14),
    PLAYER_MOVE_CB     (0x15),
    PLAYER_DESPAWN     (0x16),
    KEEPALIVE          (0x17);

    /** Increment when the packet format changes in a breaking way. */
    public static final int PROTOCOL_VERSION = 1;

    /** The one-byte wire ID for this packet. */
    public final int id;

    PacketId(int id) {
        this.id = id;
    }

    /**
     * Resolves a wire byte value to a PacketId.
     *
     * @param id the raw byte read from the wire
     * @return the matching PacketId
     * @throws IllegalArgumentException if no packet has this ID
     */
    public static PacketId fromId(int id) {
        for (PacketId p : values()) {
            if (p.id == id) return p;
        }
        throw new IllegalArgumentException("Unknown packet ID: 0x" + Integer.toHexString(id));
    }
}