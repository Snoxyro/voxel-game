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
├── Main.java                  ← singleplayer: minimal bootstrap only (server lifecycle in GameLoop)
├── common/
│   ├── world/                 ← shared data types used by both server and client
│   │   ├── BlockType.java     ← registered block type (replaces Block enum); lightEmission field
│   │   ├── BlockRegistry.java ← static registry: register(), getById(), getByName()
│   │   ├── Blocks.java        ← well-known block constants: AIR, GRASS, DIRT, STONE
│   │   ├── TextureLayers.java ← texture layer index constants (moved out of engine/)
│   │   ├── BlockView.java     ← interface: getBlock(x,y,z) — implemented by World and ClientWorld
│   │   ├── Chunk.java         ← short[] blocks + byte[] lightData (packed nibbles: high=sky, low=block)
│   │   ├── ChunkPos.java
│   │   ├── WorldTime.java     ← day/night math: tick(), getAmbientFactor(), getSkyColor(); volatile worldTick
│   │   ├── LightEngine.java   ← static: computeChunkLight(chunk, pos, neighbors); column skylight + emission seed
│   │   ├── PhysicsBody.java
│   │   ├── RayCaster.java
│   │   └── RaycastResult.java
│   └── network/               ← wire protocol: packets, encoder, decoder (shared by client+server)
│       ├── Packet.java
│       ├── PacketId.java      ← WORLD_TIME = 0x18 (clientbound)
│       ├── PacketEncoder.java
│       ├── PacketDecoder.java
│       └── packets/
│           ├── HandshakePacket.java
│           ├── LoginRequestPacket.java
│           ├── LoginSuccessPacket.java
│           ├── ChunkDataPacket.java
│           ├── UnloadChunkPacket.java
│           ├── BlockBreakPacket.java
│           ├── BlockPlacePacket.java
│           ├── BlockChangePacket.java
│           ├── PlayerMoveSBPacket.java
│           ├── PlayerMoveCBPacket.java
│           ├── PlayerSpawnPacket.java
│           ├── PlayerDespawnPacket.java
│           └── WorldTimePacket.java   ← long worldTick; broadcast every 20 ticks
├── server/                    ← headless server: no GL, no LWJGL, no engine imports
│   ├── ServerMain.java        ← dedicated server entry point (./gradlew runServer)
│   ├── GameServer.java        ← 20 TPS game loop; WorldTime tick + broadcast; setRenderDistance()
│   ├── PlayerSession.java     ← per-client state: channel (getChannel()), position, loaded chunks, visible players
│   ├── ServerWorld.java       ← wraps World; chunk streaming; player visibility; broadcastToAll(Packet)
│   ├── network/
│   │   ├── ServerNetworkManager.java
│   │   └── ClientHandler.java
│   └── storage/
│       ├── ChunkStorage.java
│       ├── FlatFileChunkStorage.java
│       └── WorldMeta.java     ← reads/writes world.dat (seed); loadOrCreate(Path) and loadOrCreate(Path, long)
├── client/                    ← client-side: rendering, input, local world state
│   ├── ClientWorld.java       ← receives chunks; meshes + renders; WorldTime (applyWorldTime, getAmbientFactor,
│   │                             getSkyColor); invalidateAllMeshes(); reset() for menu return
│   └── network/
│       ├── ClientNetworkManager.java
│       └── ServerHandler.java ← routes WorldTimePacket → clientWorld.applyWorldTime()
├── engine/                    ← GL/GLFW systems — client-only, never server
│   ├── ui/                    ← UI rendering subsystem
│   │   ├── GlyphAtlas.java    ← AWT font baked to GL_TEXTURE_2D; measureText(), charWidth(), lineHeight()
│   │   ├── UiShader.java      ← orthographic 2D shader wrapper
│   │   ├── UiRenderer.java    ← batched quad renderer; int-color + float-color overloads for drawRect/drawText
│   │   ├── UiTheme.java       ← abstract base: named color fields + compound draw helpers
│   │   ├── DarkTheme.java     ← default built-in dark theme
│   │   └── LightTheme.java    ← built-in light theme
│   ├── GameLoop.java          ← main loop; owns ScreenManager, settings; launchWorld(), returnToMainMenu(),
│   │                             openSettings(), applySettings(), createPauseMenu(), isSessionActive()
│   │                             render(): dynamic glClearColor from WorldTime; u_brightnessFloor +
│   │                             u_ambientFactor uniforms set each frame
│   ├── Camera.java            ← setFov(int degrees) — mutable FOV driven by GameSettings
│   ├── Window.java
│   ├── InputHandler.java      ← resetMouseDelta(), consumeMouseClick(), justPressedKeys edge detection
│   ├── ShaderProgram.java     ← setUniform overloads: Matrix4f, float, int, boolean
│   ├── Mesh.java              ← FLOATS_PER_VERTEX = 10 (pos3 + col3 + uv2 + layer1 + light1)
│   ├── TextureManager.java
│   ├── HudRenderer.java
│   └── BlockHighlightRenderer.java
├── game/                      ← gameplay logic + client-only data types
│   ├── screen/
│   │   ├── Screen.java
│   │   ├── ScreenManager.java
│   │   ├── MainMenuScreen.java
│   │   ├── WorldSelectScreen.java
│   │   ├── PauseMenuScreen.java
│   │   ├── MultiplayerConnectScreen.java
│   │   └── SettingsScreen.java   ← Graphics tab: RenderDist, FOV, Brightness, AO toggle, Theme
│   ├── Action.java
│   ├── KeyBindings.java
│   ├── GameSettings.java      ← fields: brightnessFloor [0.0,0.3], aoEnabled bool; all persisted
│   ├── World.java
│   ├── TerrainGenerator.java
│   ├── ChunkMesher.java       ← public static volatile boolean aoEnabled; computeAO() respects it
│   ├── Player.java
│   └── ChunkStorage.java
└── util/
    └── OpenSimplex2S.java

src/main/resources/
├── shaders/
│   ├── default.vert           ← layout locations 0-4: pos, col, uv, layer, lightLevel
│   ├── default.frag           ← uniforms: texArray, useTexture, u_brightnessFloor, u_ambientFactor
│   ├── hud.vert / hud.frag
│   └── ui.vert / ui.frag
├── textures/
└── sounds/
```

## Architecture Decisions (Locked)

### 1. Singleplayer = Embedded Server
`Main.java` is now minimal — it bootstraps registries and constructs `GameLoop`.
`GameLoop` owns the server lifecycle: `launchWorld(worldName, seed)` starts
`GameServer` on a daemon thread, waits for the port to bind via `awaitReady()`,
connects `ClientNetworkManager`, and posts a main-thread action to dismiss the
loading screen. `returnToMainMenu()` disconnects, stops the server, calls
`clientWorld.reset()`, and returns to the main menu. No separate singleplayer
code path. Ever.

### 2. Server Is Fully Headless
The `server/` package has **zero imports from `engine/`**. Any violation is always
a bug and will crash natively on the server thread with no useful Java stack trace.

### 3. GL Thread Rules
- GL calls on the main thread only — never on worker threads or Netty I/O threads.
- `new Mesh(vertices)` (GPU buffer allocation) on the main thread only.
- Chunk mesh generation (CPU side) runs on worker threads via `meshExecutor`.
- Netty I/O threads only write to `ConcurrentLinkedQueue` instances — never touch
  GL resources or chunk maps directly.

### 4. Chunk Mesh Pipeline
1. Block change / chunk arrival → dirty-mark chunk + face neighbors
2. `processDirtyMeshes()` (main thread) → captures neighbor snapshot → submits to `meshExecutor`
3. Worker thread: `LightEngine.computeChunkLight()` → `ChunkMesher.mesh()` → result queued
4. Main thread drains results → `new Mesh(vertices)` → stored in `meshes` map

### 5. Block Registry
`BlockRegistry` is a static registry with append-only registration order.
ID 0 = AIR is a permanent contract — `Chunk` zero-initialises its `short[]` to AIR.
`Blocks.bootstrap()` must be the first line of both `main()` entry points.
Never reorder existing registrations — it corrupts save files and in-flight packets.

### 6. Light System
`Chunk` stores `byte[] lightData` — 4096 bytes, packed nibbles (high = skylight 0–15,
low = block light 0–15). `LightEngine.computeChunkLight()` runs column-only skylight
(top-down, checks chunk above for sky entry) and seeds block light from
`BlockType.getLightEmission()`. No BFS propagation yet — deferred to Phase 7.
Data layout is fully BFS-compatible; only the propagation algorithm changes.
`ChunkMesher` reads face light via `getLightForFace()` and bakes `pow(0.8, 15-level)`
as a float into vertex slot 4. The fragment shader applies `u_brightnessFloor`
(additive floor, settings-driven) and `u_ambientFactor` (multiplicative, day/night).

### 7. Day/Night Cycle
`WorldTime` lives in `common/world/`. Server owns one instance, ticks it every server
tick, broadcasts `WorldTimePacket` every 20 ticks (1 real second). Client's
`ClientWorld` owns a read-only instance updated via `applyWorldTime(long)` from
`ServerHandler`. `worldTick` is `volatile` — Netty thread writes, GL thread reads.
`GameLoop.render()` calls `glClearColor` from `getSkyColor()` and sets
`u_ambientFactor` from `getAmbientFactor()` every frame. Day = 24,000 ticks = 20
real minutes. Noon = tick 6000, midnight = 18000.

### 8. Settings System
`GameSettings` is owned by `GameLoop` and passed via constructor injection. Never a
static singleton. `KeyBindings` (in `game/`) wraps `EnumMap<Action, Integer>` where
mouse buttons are offset by `MOUSE_BUTTON_OFFSET=1000`. `ChunkMesher.aoEnabled` is
`public static volatile boolean` — written by the GL thread via `applySettings()`,
read by worker threads. AO toggle triggers `clientWorld.invalidateAllMeshes()` only
when the value actually changes. `brightnessFloor` [0.0, 0.3] is set as a shader
uniform every render frame. Mouse sensitivity stored as human-readable multiplier
(1.0 = normal), multiplied by base factor `0.1f` at the camera rotation call site.

### 9. UI System
`UiTheme` is abstract; `DarkTheme` and `LightTheme` are built-in. `ScreenManager`
owns the active theme. Full-screen menus replace world render; overlay screens render
after world render — `GameLoop.loop()` branches on `screenManager.isActiveScreenOverlay()`.
`Screen.render()` signature: `render(UiTheme, float deltaTime, int w, int h)`.

### 10. Multi-Viewer Chunk Streaming
`World.update()` accepts `List<World.ViewerInfo>`. Chunks loaded if in range of ANY
viewer; unloaded only when out of range of ALL viewers.

### 11. Per-Player Visibility
`PlayerSession` tracks visible remote player IDs. `ServerWorld` manages spawn/despawn
each tick when players enter/leave each other's render distance.

### 12. GL Resource Construction Timing
Classes instantiated before `GameLoop.init()` must not create GL resources in their
constructor. Pattern: nullable field + `initRenderResources()` called after `window.init()`.

### 13. Packet Addition Checklist
Every new packet requires exactly 4 touch points:
1. `PacketId` — add wire ID constant
2. `PacketEncoder` — add `else if` branch, write ID + payload
3. `PacketDecoder` — add `case` to switch, read payload in same order
4. Handler — `ServerHandler` (clientbound) or `ClientHandler` (serverbound)
Missing any one silently drops the packet.

## How to Build and Run
```bash
./gradlew run                              # Singleplayer (menu → world select → play)
./gradlew runServer                        # Dedicated headless server only
./gradlew runServer --args="--world myworld --port 25565"
./gradlew build                            # Build without running
./gradlew clean build                      # Clean build
```

### 14. Cross-File State Dependencies
These are the non-obvious states that span multiple files. Violating them causes silent
bugs or crashes with no useful stack trace.

- **`ChunkMesher.aoEnabled`** — `public static volatile boolean`. Written by GL thread
  via `GameLoop.applySettings()`. Read by worker threads in `ChunkMesher.mesh()`. Any
  change must call `clientWorld.invalidateAllMeshes()` — only when the value actually
  changes, not unconditionally.

- **`GameLoop.applySettings()` is the single authority for pushing settings to GPU.**
  It calls `shaderProgram.setUniform("u_brightnessFloor", ...)`, `camera.setFov()`,
  `window.setVSync()`, etc. If a new setting needs a GL call, it goes here — nowhere else.

- **`ClientWorld.processDirtyMeshes()` worker lambda order is fixed:**
  `LightEngine.computeChunkLight()` → `ChunkMesher.mesh()` → result posted to main thread
  for `new Mesh(vertices)`. Never swap the order; never move `new Mesh()` to the worker.

- **`World.renderDistanceH` is a live mutable field** (not a constant). Every read must
  use `world.getRenderDistanceH()`. `ServerWorld.setRenderDistance()` delegates to
  `world.setRenderDistance()`. `GameLoop.applySettings()` calls
  `activeServer.setRenderDistance()`. No other call site should exist.

## Important Notes for Claude

### Context File Maintenance (REQUIRED)
After every session that makes meaningful progress, the following files MUST be updated
before giving the commit message. Do not wait to be asked:
- **DEVLOG.md** — add a new entry for everything done this session (Current and future phase entries only; archive is append-only)
- **CLAUDE.md** — update package structure, phase status, architecture decisions if changed
- **README.md** — update phase checklist, controls, package overview if changed
- **.github/copilot-instructions.md** — update current phase, key constraints if changed
- If a new Java file was created or an existing file significantly refactored in
  `engine/`, `game/World.java`, `game/ChunkMesher.java`, or `client/ClientWorld.java`,
  flag at session end: "⚠️ Run /annotate-gl-state on [filename] before next session."

This is mandatory. Stale context files defeat the purpose of having them.

### Always Read DEVLOG.md First
Check DEVLOG.md for the latest progress before responding. It contains current phase
and onward — decisions, bugs, and current state.
DEVLOG_ARCHIVE.md holds Phase 0–5 history. Do not read it by default — only
consult it when explicitly asked about a historical decision from a completed phase.

### GL Thread Safety is Critical
If any code in `server/` or `game/` (World, ServerWorld, etc.) imports or calls
anything from `engine/` — flag it immediately. That is always a bug.

### Developer Knowledge Level
The developer is learning OpenGL, GLSL, game networking, and 3D math through this
project. Explain concepts before code. Prioritize code they can understand over
clever optimizations.

### Flag Architectural Consequences
Always flag when a decision will have long-term consequences.

### AI Honesty
This project intentionally explores AI limitations. Be honest when a problem requires
human debugging judgment that AI cannot reliably provide.

### Provide Exact File and Location for Every Change
When giving code changes, always state the exact file path, the exact method or block
to replace, and provide the full replacement block. Full copy-pasteable blocks only.

### What NOT to Do
- Do not suggest Maven, Vulkan, Spring, Jakarta, or any web framework
- Do not use deprecated LWJGL 2 APIs
- Do not put GL calls outside the main thread
- Do not import `engine/` from `server/` or `game/World.java`
- Do not add `Mesh` or rendering code to `World.java` or `ServerWorld.java`
- Do not construct GL resources in constructors of classes instantiated before `GameLoop.init()`
- Do not make `World.renderDistanceH` static again — it is a live mutable field
- Do not skip calling `inputHandler.consumeMouseClick()` after UI-to-gameplay transitions
- Do not store mouse sensitivity as a raw camera multiplier
- Do not place `glClearColor` after `glClear` — order is always: set color, then clear
- Do not add new packets without touching all 4 required files (see Packet Addition Checklist)

## Development Phases
- **Phase 0 (done):** Foundation — window, OpenGL context, game loop, triangle
- **Phase 1 (done):** Chunk system, flat world, player movement
- **Phase 2 (done):** World generation (fBm noise, terrain layering)
- **Phase 3 (done):** Gameplay basics — block placing/breaking, HUD, physics
- **Phase 4 (done):** Performance — greedy meshing, AO, textures, async streaming
- **Phase 5 (done):** Multiplayer — client/server split, chunk streaming, block sync
- **Phase 6 (current):** Foundation for extensibility
  - **6A (done):** Block Registry
  - **6B (done):** Menu / UI System (full tabbed settings, all screens)
  - **6C (done):** Lighting + Day/Night Cycle
    - LightEngine, vertex brightness baking, shader uniforms
    - Brightness slider, AO toggle, async remesh on toggle
    - WorldTime, day/night sky color + ambient factor, WorldTimePacket sync
    - Skylight recompute on block change (automatic via dirty-mark pipeline)
  - **6D (next):** Entity System + Player Model — entity framework, skeletal player
    model, item drop entities. Nametags deferred to here.
  - **6E:** Items + Inventory — item registry, hotbar, crafting grid, block drops.
- **Phase 7:** BFS Light Propagation
  - Queue-based flood fill across chunk boundaries (skylight + block light)
  - Correct gradients under overhangs, into caves, around corners
  - Torch-style point light sources with proper spread
  - Incremental updates on block place/break (not full chunk recompute)
  - Data layout already in place (lightData nibble arrays, LightEngine) — only
    the propagation algorithm changes.
- **Phase 8:** Modding API
  - Block / item / entity registry hooks exposed to external code
  - World gen hooks, event listeners
  - Scripting runtime