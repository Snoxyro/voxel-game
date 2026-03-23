# Copilot Instructions — Voxel Game Engine

## Project Overview
A voxel game engine with client/server multiplayer architecture, built in Java 21
with LWJGL 3 and Netty. Long-term learning project exploring engine architecture,
graphics programming, networking, and AI-assisted development. Target: a feature-complete
voxel game with multiplayer, world persistence, and modding support.

## Technology Stack
- **Language:** Java 21
- **Build:** Gradle 8.x with Gradle wrapper
- **Rendering:** OpenGL 4.5 via LWJGL 3.3.4
- **Windowing:** GLFW via LWJGL
- **Math:** JOML (Java OpenGL Math Library)
- **Audio:** OpenAL via LWJGL
- **Networking:** Netty 4.1.115.Final (TCP, NIO event loop, pipeline codec)

## Package Structure
```
com.voxelgame.
├── Main.java                  ← singleplayer entry (embedded server + client)
├── common/
│   ├── world/                 ← shared types: Block, BlockView, Chunk, ChunkPos,
│   │                             PhysicsBody, RayCaster, RaycastResult
│   └── network/               ← wire protocol: Packet, PacketId, encoder, decoder, packet records
├── server/                    ← headless server — ZERO engine/GL imports
│   ├── ServerMain.java
│   ├── GameServer.java        ← 20 TPS game loop
│   ├── PlayerSession.java
│   ├── ServerWorld.java
│   └── network/               ← ServerNetworkManager, ClientHandler
├── client/                    ← client-side logic
│   ├── ClientWorld.java       ← receives chunks from server, meshes + renders
│   └── network/               ← ClientNetworkManager, ServerHandler
├── engine/                    ← GL/GLFW systems — client main thread only
│   └── GameLoop, Camera, Window, InputHandler, ShaderProgram, Mesh, etc.
└── game/                      ← server-side gameplay
    └── World (chunk data only — NO GL), TerrainGenerator, ChunkMesher, Player
```

## Architecture Principles

### The Most Important Rule: Server Has No GL Context
The `server/` package and `game/World.java` must never import anything from `engine/`.
The server thread has no OpenGL context. Any GL call from a non-main thread causes
a native crash with no useful Java stack trace. If Copilot is editing server-side code
and wants to add a `Mesh` or `ShaderProgram` — that is always wrong.

### GL Calls on Main Thread Only
All OpenGL calls (`GL11.*`, `GL30.*`, `new Mesh()`, etc.) must occur on the main
thread only. Worker threads run `ChunkMesher.mesh()` (pure CPU). The main thread
picks up results and calls `new Mesh(vertices)`. This rule applies to `ClientWorld`
as well — `drainPendingMeshes()` must only be called from the main thread.

### BlockView Interface
`PhysicsBody`, `RayCaster`, and `Player` accept `BlockView` (not `World` or
`ClientWorld`). Both `World` and `ClientWorld` implement `BlockView`. Never change
these to accept a concrete type — that would break the client/server separation.

### Network Protocol
Wire format: `[4-byte length][1-byte packet ID][payload]`. When adding a new packet:
1. Add an ID to `PacketId` enum
2. Create a record in `common/network/packets/` implementing `Packet`
3. Add a serialization branch to `PacketEncoder`
4. Add a deserialization branch to `PacketDecoder`
All four files must be updated together.

### Singleplayer = Embedded Server
`Main.java` starts `GameServer` on a daemon thread, waits for the port to bind,
then connects `ClientNetworkManager` and runs `GameLoop`. No separate singleplayer
code path. Ever.

### Engine/Game Separation
`engine/` never imports from `game/` or `server/`. `game/` never imports from
`engine/`. `server/` never imports from `engine/`. `common/` never imports from
any of the others.

### Composition Over Inheritance
ECS is the target architecture. `PhysicsBody` is owned by `Player` as a component.
No deep inheritance hierarchies.

### Thread Handoff via Concurrent Queues
Netty I/O threads write into `ConcurrentLinkedQueue`s. The main thread or server
tick thread drains them. Never pass live mutable state between threads — always
capture a snapshot before handing work to a background thread.

## Code Style
- Standard Java naming conventions
- Every public class and method must have a Javadoc comment
- No raw types — generics fully specified
- Explicit error handling, no silent catches — log all exceptions with context
- No magic numbers — constants in dedicated constants classes or at top of file
- All OpenGL resources explicitly cleaned up in `cleanup()` methods

## Current Development Phase
Phase 5 — Multiplayer. Sub-phase 5D (partial).
- 5A done: package restructure, Netty, handshake/login
- 5B done: chunk streaming (server generates, client receives and renders)
- 5C done: BlockBreakPacket / BlockPlacePacket → server validates → BlockChangePacket broadcast
- 5D partial: PlayerMoveSBPacket → server updates PlayerSession position → streaming center follows player
- 5D remaining: PlayerSpawn / PlayerMove / PlayerDespawn clientbound packets, RemotePlayer with interpolation
- 5E next after 5D: world persistence

## Key Constraints
- Server has zero GL dependency — no `engine/` imports in `server/` or `game/World.java`
- GL calls on main thread only — never pass GL calls to worker or Netty threads
- Chunk mesh generation on worker threads; `new Mesh(vertices)` on main thread only
- `BlockView` interface must remain the abstraction for physics/raycasting
- Netty I/O threads must only write to concurrent queues — never touch GL or chunk maps
- Default port: 24463

## What NOT to Do
- Do not suggest switching to Maven
- Do not suggest Vulkan (OpenGL 4.5 is current target)
- Do not introduce Spring, Jakarta, or any web framework
- Do not use deprecated LWJGL 2 APIs
- Do not add `Mesh`, `ShaderProgram`, or any `engine/` import to `server/` code
- Do not add rendering code to `World.java` or `ServerWorld.java`
- Do not call `new Mesh()` outside the main thread