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
│   │   ├── Block.java
│   │   ├── BlockType.java     ← registered block type (replaces Block enum)
│   │   ├── BlockRegistry.java ← static registry: register(), getById(), getByName()
│   │   ├── Blocks.java        ← well-known block constants: AIR, GRASS, DIRT, STONE
│   │   ├── TextureLayers.java ← texture layer index constants (moved out of engine/)
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
│           ├── UnloadChunkPacket.java
│           ├── BlockBreakPacket.java
│           ├── BlockPlacePacket.java
│           ├── BlockChangePacket.java
│           ├── PlayerMoveSBPacket.java
│           ├── PlayerMoveCBPacket.java
│           ├── PlayerSpawnPacket.java
│           └── PlayerDespawnPacket.java
├── server/                    ← headless server: no GL, no LWJGL, no engine imports
│   ├── ServerMain.java        ← dedicated server entry point (./gradlew runServer)
│   ├── GameServer.java        ← 20 TPS game loop, player login/disconnect callbacks; setRenderDistance()
│   ├── PlayerSession.java     ← per-client state: channel, position, yaw/pitch, loaded chunks, visible players
│   ├── ServerWorld.java       ← wraps World, drives chunk streaming + player visibility per player
│   ├── network/
│   │   ├── ServerNetworkManager.java
│   │   └── ClientHandler.java
│   └── storage/
│       ├── ChunkStorage.java  ← interface: load/save chunks
│       ├── FlatFileChunkStorage.java
│       └── WorldMeta.java     ← reads/writes world.dat (seed); loadOrCreate(Path) and loadOrCreate(Path, long)
├── client/                    ← client-side: rendering, input, local world state
│   ├── ClientWorld.java       ← receives chunks from server, meshes + renders; reset() for menu return
│   └── network/
│       ├── ClientNetworkManager.java
│       └── ServerHandler.java
├── engine/                    ← GL/GLFW systems — client-only, never server
│   ├── ui/                    ← UI rendering subsystem
│   │   ├── GlyphAtlas.java    ← AWT font baked to GL_TEXTURE_2D; measureText(), charWidth(), lineHeight()
│   │   ├── UiShader.java      ← orthographic 2D shader wrapper
│   │   ├── UiRenderer.java    ← batched quad renderer; int-color + float-color overloads for drawRect/drawText
│   │   ├── UiTheme.java       ← abstract base: named color fields + compound draw helpers (drawButton, drawPanel, etc.)
│   │   ├── DarkTheme.java     ← default built-in dark theme
│   │   └── LightTheme.java    ← built-in light theme
│   ├── GameLoop.java          ← main loop; owns ScreenManager, settings; launchWorld(), returnToMainMenu(),
│   │                             openSettings(), applySettings(), createPauseMenu(), isSessionActive()
│   ├── Camera.java            ← setFov(int degrees) — mutable FOV driven by GameSettings
│   ├── Window.java
│   ├── InputHandler.java      ← resetMouseDelta(), consumeMouseClick(), justPressedKeys edge detection
│   ├── ShaderProgram.java
│   ├── Mesh.java
│   ├── TextureManager.java
│   ├── HudRenderer.java
│   └── BlockHighlightRenderer.java
├── game/                      ← server-side gameplay logic + client-only data types
│   ├── screen/                ← Screen system
│   │   ├── Screen.java                ← interface: render(UiTheme,float,w,h), onShow/Hide, input; isOverlay() default false
│   │   ├── ScreenManager.java         ← owns UiTheme (swappable); setTheme(), getTheme(), renderActiveScreen()
│   │   ├── MainMenuScreen.java        ← Singleplayer / Multiplayer / Settings / Quit
│   │   ├── WorldSelectScreen.java     ← world list, create, delete, launch; statusMessage for launch errors
│   │   ├── PauseMenuScreen.java       ← overlay; Resume / Settings / Main Menu / Quit
│   │   ├── MultiplayerConnectScreen.java ← IP+port input, direct connect, cancelledFlag abort pattern
│   │   └── SettingsScreen.java        ← full tabbed settings: Gameplay/Graphics/Display/Controls/Keybinds/Sound
│   ├── Action.java            ← enum of all bindable actions with displayName
│   ├── KeyBindings.java       ← EnumMap<Action,Integer>; mouse offset encoding; displayName(int); copy/setAll
│   ├── GameSettings.java      ← persistent settings (settings.properties); load/save atomic; WindowMode enum
│   ├── World.java             ← chunk data management; renderDistanceH mutable via setRenderDistance()
│   ├── TerrainGenerator.java
│   ├── ChunkMesher.java
│   ├── Player.java
│   └── ChunkStorage.java
└── util/
    └── OpenSimplex2S.java

src/main/resources/
├── shaders/
│   ├── default.vert / default.frag   ← 3D world shader
│   ├── hud.vert / hud.frag           ← HUD crosshair shader
│   └── ui.vert / ui.frag             ← orthographic 2D UI shader
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
- World launch: dedicated daemon thread blocks on `awaitReady()` + `network.connect()`,
  then posts a `Runnable` to `pendingMainThreadAction` (AtomicReference) for the GL thread
- Rule: never pass live mutable state between threads; always snapshot first

### 9. Screen System
- `Screen` interface: `render(UiTheme, float deltaTime, int w, int h)`, `onShow/Hide()`, input callbacks, `isOverlay()` (default false)
- Full-screen menus (main menu, world select, settings): replace world render entirely
- Overlay screens (pause menu): world renders first, screen draws on top
- `ScreenManager` owns `UiRenderer`, `GlyphAtlas`, `UiShader` — shared across all screens
- Button centering always uses `r.measureText(label)` — never hardcoded character widths
- `isOverlay()` flag controls whether `GameLoop.loop()` skips or keeps the world render

### 10. UI Theme System
`UiTheme` is an abstract class in `engine/ui/`. Subclasses set `protected int col*`
fields (packed `0xRRGGBBAA`) and optionally override draw helpers. `ScreenManager`
owns the active theme and exposes `setTheme(UiTheme)` — swapping themes requires no
GL work. `ThemeRegistry` is deferred to Phase 7. All screens depend on `UiTheme`,
never on `UiRenderer` directly. `Screen.render()` receives `UiTheme`, not `UiRenderer`.

### 11. Multi-Viewer Chunk Streaming
`World.update()` accepts `List<World.ViewerInfo>` — one entry per connected player.
Each `ViewerInfo` carries position and normalised look direction. Chunks are loaded
if in range of ANY viewer and unloaded only when out of range of ALL viewers.
`ServerWorld` builds the viewer list from all connected players each tick.

### 12. Per-Player Visibility (Player Models)
`PlayerSession` tracks which remote player IDs each session has been told about via
a `visiblePlayerIds` set. `ServerWorld` manages spawn/despawn dynamically each tick:
when two players enter each other's render distance a `PlayerSpawnPacket` is sent;
when they move out of range a `PlayerDespawnPacket` is sent. Position updates
(`PlayerMoveCBPacket`) are only sent while visibility is active.

### 13. GL Resource Construction Timing
Any class instantiated before `GameLoop.init()` must not create GL resources in its
constructor or field initializers. The pattern is: nullable field + separate
`initRenderResources()` method called from `GameLoop.init()` after `window.init()`.
This has caused two crashes in the project history — it is a hard rule.

### 14. Settings System
`GameSettings` is owned by `GameLoop` and passed via constructor injection. Never a
static singleton — Phase 7 mod settings will use separate `GameSettings` instances
pointing to different `.properties` files. `KeyBindings` (in `game/`) wraps an
`EnumMap<Action, Integer>` where mouse buttons are offset by `MOUSE_BUTTON_OFFSET=1000`
to avoid colliding with key codes. `World.renderDistanceH` is a mutable instance field
(not a compile-time constant) driven by `GameSettings` — `setRenderDistance()` sets a
`pendingUnloadSweep` flag so the unload happens on the next server tick.
`GameLoop.applySettings()` applies all live-applicable settings immediately on save:
VSync, FOV, window mode, theme, render distance. Mouse sensitivity is stored as a
human-readable multiplier (1.0 = normal feel) and multiplied by a base factor of
`0.1f` when applied to camera rotation. `InputHandler.consumeMouseClick()` must be
called after any UI click that transitions back to gameplay to prevent the click from
bleeding into game input (e.g. the Resume button breaking a block).

## How to Build and Run
```bash
./gradlew run                              # Singleplayer (menu → world select → play)
./gradlew runServer                        # Dedicated headless server only
./gradlew runServer --args="--world myworld --port 25565"
./gradlew build                            # Build without running
./gradlew clean build                      # Clean build
```

## Important Notes for Claude

### Context File Maintenance (REQUIRED)
After every session that makes meaningful progress, the following files MUST be updated
before giving the commit message. Do not wait to be asked:
- **DEVLOG.md** — add a new entry for everything done this session
- **CLAUDE.md** — update package structure, phase status, architecture decisions if changed
- **README.md** — update phase checklist, controls, package overview if changed
- **.github/copilot-instructions.md** — update current phase, key constraints if changed

This is mandatory. Stale context files defeat the purpose of having them.

### Always Read DEVLOG.md First
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

### Provide Exact File and Location for Every Change
When giving code changes, always state the exact file path, the exact method or block
to replace, and provide the full replacement block. The codebase is large enough that
"find the X method and update it" is not sufficient. Full copy-pasteable blocks only.

### What NOT to Do
- Do not suggest Maven, Vulkan, Spring, Jakarta, or any web framework
- Do not use deprecated LWJGL 2 APIs
- Do not put GL calls outside the main thread
- Do not import `engine/` from `server/` or `game/World.java`
- Do not add `Mesh` or rendering code to `World.java` or `ServerWorld.java`
- Do not construct GL resources (VAOs, VBOs, textures) in constructors of classes
  that are instantiated before GameLoop.init() — the GL context does not exist yet
- Do not make `World.renderDistanceH` static again — it is a live mutable field
- Do not skip calling `inputHandler.consumeMouseClick()` after UI-to-gameplay transitions
- Do not store mouse sensitivity as a raw camera multiplier — store as human-readable
  (1.0 = normal) and apply the 0.1f base scale factor at the camera rotation call site

## Development Phases
- **Phase 0 (done):** Foundation — window, OpenGL context, game loop, triangle
- **Phase 1 (done):** Chunk system, flat world, player movement
- **Phase 2 (done):** World generation (fBm noise, terrain layering)
- **Phase 3 (done):** Gameplay basics — block placing/breaking, HUD, physics
- **Phase 4 (done):** Performance — greedy meshing, AO, textures, async streaming
- **Phase 5 (done):** Multiplayer — client/server split, chunk streaming, block sync
- **Phase 6 (current):** Foundation for extensibility
  - **6A (done):** Block Registry — Block enum → registered class with stable IDs
  - **6B (done):** Menu / UI System
    - **6B-1 (done):** UI rendering foundation — GlyphAtlas, UiShader, UiRenderer
    - **6B-2 (done):** Screen abstraction — Screen, ScreenManager, GameLoop wiring
    - **6B-3 (done):** Main menu — Singleplayer / Multiplayer / Settings / Quit
    - **6B-4 (done):** World selection screen — list, create (name+seed), delete, launch
    - **6B-5 (done):** Multiplayer connect screen — IP/port input, direct server connect
    - **6B-6 (done):** In-game pause menu — overlay, Resume / Settings / Main Menu / Quit
    - **6B-theme (done):** UI Theme system — UiTheme abstract class, DarkTheme, LightTheme
    - **6B-7 (done):** Full settings system — GameSettings, KeyBindings, Action, SettingsScreen
  - **6C (next):** Lighting + Day/Night Cycle — skylight propagation, block light foundation,
    sun cycle, ambient light.
  - **6D:** Entity System + Player Model — entity framework, skeletal player model,
    item drop entities. Nametags above player models deferred to here.
  - **6E:** Items + Inventory — item registry, hotbar, crafting grid, block drops.
- **Phase 7:** Modding API