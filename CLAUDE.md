# CLAUDE.md — Voxel Game Engine Project Context

## What This Project Is
A voxel game engine built from scratch in Java 21 with LWJGL 3. This is a deliberate
long-term learning challenge by a 4th year software engineering student. The explicit
goal is to experience the boundaries of AI-assisted development firsthand — where it
helps, where it fails, and what it cannot replace.

## Developer Profile
- 4th year software engineering student, about to graduate
- Strong backend experience: Spring Boot, ASP.NET Core
- Some AI engineering experience: built a RAG chatbot
- Networking knowledge: self-hosted WireGuard VPN, understands TCP/UDP, subnets
- No prior graphics programming experience
- No prior game engine experience
- Learning OpenGL, GLSL, 3D math, ECS architecture, and game networking through this project

## Technology Stack
- **Language:** Java 21 (Eclipse Temurin)
- **Build:** Gradle 8.x with Gradle wrapper (gradlew)
- **Rendering:** OpenGL 4.5 via LWJGL 3.3.4
- **Windowing:** GLFW via LWJGL
- **Math:** JOML 1.10.7
- **Audio:** OpenAL via LWJGL
- **Networking:** Netty 4.1.115.Final (TCP, NIO, pipeline codec)
- **IDE:** VS Code with Extension Pack for Java, Gradle for Java, GitHub Copilot

## Package Structure
```
src/main/java/com/voxelgame/
├── Main.java                  ← singleplayer: embedded server + client on localhost
├── common/
│   ├── world/                 ← shared data types used by both server and client
│   │   ├── Block.java
│   │   ├── BlockView.java     ← interface: getBlock(x,y,z) — implemented by World and ClientWorld
│   │   ├── Chunk.java
│   │   ├── ChunkPos.java
│   │   ├── PhysicsBody.java
│   │   ├── RayCaster.java
│   │   └── RaycastResult.java
│   └── network/               ← wire protocol: packets, encoder, decoder (shared by client+server)
│       ├── Packet.java
│       ├── PacketId.java
│       ├── PacketEncoder.java
│       ├── PacketDecoder.java
│       └── packets/
│           ├── HandshakePacket.java
│           ├── LoginRequestPacket.java
│           ├── LoginSuccessPacket.java
│           ├── ChunkDataPacket.java
│           └── UnloadChunkPacket.java
├── server/                    ← headless server: no GL, no LWJGL, no engine imports
│   ├── ServerMain.java        ← dedicated server entry point (./gradlew runServer)
│   ├── GameServer.java        ← 20 TPS game loop, player login/disconnect callbacks
│   ├── PlayerSession.java     ← per-client state: channel, position, loaded chunk set
│   ├── ServerWorld.java       ← wraps World, drives chunk streaming per player
│   ├── network/
│   |   ├── ServerNetworkManager.java
│   |   └── ClientHandler.java
│   └── storage/               ← FlatFileChunkStorage (ChunkStorage impl)
├── client/                    ← client-side: rendering, input, local world state
│   ├── ClientWorld.java       ← receives chunks from server, meshes + renders them
│   └── network/
│       ├── ClientNetworkManager.java
│       └── ServerHandler.java
├── engine/                    ← GL/GLFW systems — client-only, never server
│   ├── GameLoop.java
│   ├── Camera.java
│   ├── Window.java
│   ├── InputHandler.java
│   ├── ShaderProgram.java
│   ├── Mesh.java
│   ├── TextureManager.java
│   ├── HudRenderer.java
│   └── BlockHighlightRenderer.java
├── game/                      ← server-side gameplay logic
│   ├── World.java             ← chunk data manager: generation, storage, load/unload (NO GL)
│   ├── TerrainGenerator.java
│   ├── ChunkMesher.java       ← stateless, thread-safe — used by ClientWorld on worker threads
│   ├── Player.java
|   └── ChunkStorage.java
└── util/
    └── OpenSimplex2S.java

src/main/resources/
├── shaders/
│   ├── default.vert / default.frag
│   └── hud.vert / hud.frag
├── textures/
└── sounds/
```

## Architecture Decisions (Locked)

### 1. Singleplayer = Embedded Server
`Main.java` launches a `GameServer` on a daemon thread, waits for the port to bind
via `CountDownLatch`, then connects a `ClientNetworkManager` and runs `GameLoop`.
No separate singleplayer code path. Ever.

### 2. Server Is Fully Headless
The `server/` package has **zero imports from `engine/`**. No `Mesh`, no `ShaderProgram`,
no `GL11`, no LWJGL. The server thread has no OpenGL context — any GL call from it
causes a native crash (`EXCEPTION_ACCESS_VIOLATION`). This is a hard rule.

### 3. BlockView Interface
`PhysicsBody`, `RayCaster`, and `Player` depend on `BlockView` (not `World` or
`ClientWorld` directly). This lets physics and raycasting run identically on both
client and server without any code duplication.

### 4. GL Thread Rule
All OpenGL calls on the main thread only. `ClientWorld` runs `ChunkMesher.mesh()`
on worker threads (CPU work) but calls `new Mesh(vertices)` only on the main thread
(GL work). Worker threads never touch GL.

### 5. Network Protocol
Custom binary TCP via Netty. Wire format: `[4-byte length][1-byte packet ID][payload]`.
`LengthFieldBasedFrameDecoder` handles TCP reassembly. `TCP_NODELAY` on all channels.
Default port: **24463**.

### 6. OpenGL 4.5, Vulkan Later
Renderer abstracted behind interfaces. Vulkan backend is a future option, not current work.

### 7. ECS Architecture (Target)
Entities use composition, not deep inheritance. `PhysicsBody` is already a reusable
component owned by `Player` — the pattern to follow.

### 8. Multithreading Model
- Server: Netty I/O threads ↔ concurrent queues ↔ 20 TPS server tick thread
- Client: Netty I/O thread → `ClientWorld` queues → main GL thread (mesh upload)
- Chunk meshing: worker thread pool (availableProcessors - 1), snapshot isolation
- Rule: never pass live mutable state between threads; always snapshot first

## Development Phases
- **Phase 0 (done):** Foundation — window, OpenGL context, game loop, triangle
- **Phase 1 (done):** Chunk system, flat world, player movement
- **Phase 2 (done):** World generation (fBm noise, terrain layering)
- **Phase 3 (done):** Gameplay basics — block placing/breaking, HUD, physics
- **Phase 4 (done):** Performance — greedy meshing, AO, textures, async streaming
- **Phase 5 (current):** Multiplayer — client/server split, chunk streaming, block sync
- **Phase 6:** Modding API — scripting runtime, registry system

## Phase 5 Sub-phases
- **5A (done):** Package restructure, Netty, handshake/login
- **5B (done):** Chunk streaming — server generates, client renders via TCP
- **5C (done):** Block interaction sync — break/place packets, server broadcast
- **5D (done):** Player movement sync — streaming center follows player;
  other-player broadcasting with PlayerSpawn/Move/Despawn; RemotePlayer interpolation.
  Client-side prediction deferred indefinitely — imperceptible on localhost.
- **5E (done):** World persistence — ChunkStorage interface, FlatFileChunkStorage,
  dirty tracking, background save queue, load-from-disk-or-generate on executor
- **5F (current):** Singleplayer integration cleanup, dedicated server mode, CLI args,
  world.dat seed file (seed per world, not hardcoded)

## How to Build and Run
```bash
./gradlew run          # Singleplayer (embedded server + client window)
./gradlew runServer    # Dedicated headless server only
./gradlew build        # Build without running
./gradlew clean build  # Clean build
```

## Important Notes for Claude

### Always read DEVLOG.md
Check DEVLOG.md for the latest progress before responding. It contains the full
history of decisions, bugs encountered, and current state.

### GL Thread Safety is Critical
If any code in `server/` or `game/` (World, ServerWorld, etc.) imports or calls
anything from `engine/` — flag it immediately. That is always a bug. The server
thread has no GL context and will crash natively without a clear Java stack trace.

### Developer Knowledge Level
The developer is learning OpenGL, GLSL, game networking, and 3D math through this
project. Explain concepts before code. Prioritize code they can understand over
clever optimizations.

### Flag Architectural Consequences
Always flag when a decision will have long-term consequences. The developer is
intentionally learning from these decisions — don't hide them.

### AI Honesty
This project intentionally explores AI limitations. Be honest when a problem requires
human debugging judgment that AI cannot reliably provide.

### What NOT to Do
- Do not suggest Maven, Vulkan, Spring, Jakarta, or any web framework
- Do not use deprecated LWJGL 2 APIs
- Do not put GL calls outside the main thread
- Do not import `engine/` from `server/` or `game/World.java`
- Do not add `Mesh` or rendering code to `World.java` or `ServerWorld.java`
- Do not construct GL resources (VAOs, VBOs, textures) in constructors of classes
  that are instantiated before GameLoop.init() — the GL context does not exist yet.
  Use a separate initRenderResources() method called after window.init().