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
│   ├── network/               ← ServerNetworkManager, ClientHandler
│   └── storage/               ← FlatFileChunkStorage (ChunkStorage impl)
├── client/                    ← client-side logic
│   ├── ClientWorld.java       ← receives chunks from server, meshes + renders
│   └── network/               ← ClientNetworkManager, ServerHandler
├── engine/                    ← GL/GLFW systems — client main thread only
│   └── GameLoop, Camera, Window, InputHandler, ShaderProgram, Mesh, etc.
└── game/                      ← server-side gameplay
    └── World (chunk data + persistence), TerrainGenerator, ChunkMesher,
        Player, ChunkStorage (interface)
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
Phase 6 — Foundation for extensibility. Sub-phase 6A next.

### Phase 5 complete
- 5A done: package restructure, Netty, handshake/login
- 5B done: chunk streaming (server generates, client receives and renders)
- 5C done: block interaction sync (break/place/broadcast)
- 5D done: player movement sync, streaming center follows player,
  other-player broadcasting, RemotePlayer with interpolation
- 5E done: world persistence — ChunkStorage interface, FlatFileChunkStorage,
  dirty tracking, background save executor, load-from-disk-or-generate
- 5F done: CLI args (--world, --port, --username), world.dat seed file per world

### Phase 6 plan
- 6A next: Block Registry — convert Block enum to registered class with stable
  numeric IDs. Chunk serialization, network protocol, save files all switch from
  ordinal() to registry ID. Most architecturally disruptive change remaining.
- 6B: Menu / UI System — main menu, world select, multiplayer connect, settings,
  pause menu.
- 6C: Lighting + Day/Night Cycle — skylight, block light, sun position, ambient.
- 6D: Entity System + Player Model — entity framework, skeletal model, item drops.
- 6E: Items + Inventory — item registry, hotbar, crafting, block drops.

### Phase 7 (after Phase 6)
Modding API — block/item/entity registries and event hooks exposed to external code.
Deferred until registries, UI, lighting, and entities all exist as stable foundations.

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