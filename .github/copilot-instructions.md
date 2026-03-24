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
├── Main.java                  ← minimal bootstrap: Blocks.bootstrap() + new GameLoop(...).run()
├── common/
│   ├── world/                 ← shared types: BlockType, BlockRegistry, Blocks, Block,
│   │                             BlockView, Chunk, ChunkPos, PhysicsBody, RayCaster,
│   │                             RaycastResult, TextureLayers
│   └── network/               ← wire protocol: Packet, PacketId, encoder, decoder, all packet records
├── server/                    ← headless server — ZERO engine/GL imports
│   ├── ServerMain.java
│   ├── GameServer.java        ← 20 TPS game loop; constructors: (Path,int) and (Path,int,long)
│   ├── PlayerSession.java
│   ├── ServerWorld.java
│   ├── network/               ← ServerNetworkManager, ClientHandler
│   └── storage/               ← FlatFileChunkStorage, WorldMeta, ChunkStorage interface
├── client/
│   ├── ClientWorld.java       ← receives chunks from server, meshes + renders; reset() clears all state
│   └── network/               ← ClientNetworkManager, ServerHandler
├── engine/                    ← GL/GLFW systems — client main thread only
│   ├── ui/
│   │   ├── GlyphAtlas.java    ← AWT font baked to GL texture; measureText(), charWidth(), lineHeight()
│   │   ├── UiShader.java
│   │   ├── UiRenderer.java    ← drawRect(), drawText(), drawCenteredText(); float + int(0xRRGGBBAA) overloads
│   │   ├── UiTheme.java       ← abstract; protected int col* fields + drawButton/drawPanel/drawInputField etc.
│   │   ├── DarkTheme.java     ← default theme
│   │   └── LightTheme.java    ← alternate theme
│   ├── GameLoop.java          ← owns ScreenManager; launchWorld(), returnToMainMenu(), handleEscapeKey()
│   ├── InputHandler.java      ← resetMouseDelta() — call after cursor recapture to prevent camera jump
│   └── Camera, Window, ShaderProgram, Mesh, TextureManager, HudRenderer, BlockHighlightRenderer
└── game/
    ├── screen/
    │   ├── Screen.java                   ← interface; render(UiTheme,w,h); isOverlay() default false
    │   ├── ScreenManager.java            ← owns UiTheme; setTheme(), getTheme(), renderActiveScreen()
    │   ├── MainMenuScreen.java           ← 3 callbacks: onSingleplayer, onMultiplayer, onQuit
    │   ├── WorldSelectScreen.java        ← statusMessage field + setStatusMessage() for launch errors
    │   ├── PauseMenuScreen.java          ← isOverlay() = true
    │   └── MultiplayerConnectScreen.java ← ConnectHandler FI; cancelledFlag abort; connecting lock
    └── World, TerrainGenerator, ChunkMesher, Player, ChunkStorage
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
as well — mesh uploads must only happen on the main thread.

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
`Main.java` is now minimal. `GameLoop.launchWorld(worldName, seed)` handles the full
server lifecycle on a background thread. `returnToMainMenu()` handles teardown.
No separate singleplayer code path. Ever.

### Screen System Rules
- `Screen.isOverlay()` returning false (default): world render is skipped, only screen renders
- `Screen.isOverlay()` returning true: world renders first, screen draws on top
- `GameLoop.handleEscapeKey()` owns all Escape key behavior — screens must not intercept
  Escape via `onKeyPress()` unless they are a non-overlay screen (WorldSelect, CreateNew substate)
- After recapturing the cursor, always call `inputHandler.resetMouseDelta()` to prevent
  the camera from lurching by however far the cursor traveled during the menu
- Button text centering always uses `r.measureText(label)` — never hardcoded character widths

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
capture a snapshot. `GameLoop.pendingMainThreadAction` (AtomicReference) is used
for the world-launch background thread to post GL-thread work.

## Code Style
- Standard Java naming conventions
- Every public class and method must have a Javadoc comment
- No raw types — generics fully specified
- Explicit error handling, no silent catches — log all exceptions with context
- No magic numbers — constants in dedicated constants classes or at top of file
- All OpenGL resources explicitly cleaned up in `cleanup()` methods

## Current Development Phase
Phase 6 — Foundation for extensibility. Sub-phase 6B nearly complete.

### Phase 6 status
- 6A done: Block Registry
- 6B-theme done: UiTheme abstract class, DarkTheme, LightTheme; Screen.render() takes UiTheme
- 6B-1 through 6B-6 done: full UI screen system
- 6B-5 done: MultiplayerConnectScreen — real server connect, cancelledFlag abort, friendly errors
- 6B-7 next: Settings stub
- TODO first in next session: fix chunk loading wave pattern (see DEVLOG Entry 042)

### After 6B
- 6C: Lighting + Day/Night Cycle
- 6D: Entity System + Player Model
- 6E: Items + Inventory
- Phase 7: Modding API

## Key Constraints
- Server has zero GL dependency — no `engine/` imports in `server/` or `game/World.java`
- GL calls on main thread only — never pass GL calls to worker or Netty threads
- Chunk mesh generation on worker threads; `new Mesh(vertices)` on main thread only
- `BlockView` interface must remain the abstraction for physics/raycasting
- Netty I/O threads must only write to concurrent queues — never touch GL or chunk maps
- Default port: 24463
- UI render pass must call `glDisable(GL_CULL_FACE)` in `begin()` and restore it
  in `end()` — UI quads are wound CW in screen-space and are culled as back faces
  otherwise. `UiRenderer` already does this. Never remove those two calls.
- Full-screen menus replace world render. Overlay screens render after world render.
  `GameLoop.loop()` branches on `screenManager.isActiveScreenOverlay()`.
- Call `inputHandler.resetMouseDelta()` after every cursor recapture to prevent
  camera jump. Failing to do this causes the camera to snap by the distance the
  free cursor traveled while the menu was open.
- Button/title text centering must use `r.measureText()` — hardcoded `length * N`
  values are always wrong for proportional or atlas-based fonts.
- Screen.render() takes UiTheme, not UiRenderer — never add UiRenderer as a screen parameter
- All colors in themes are packed 0xRRGGBBAA ints — never use float r,g,b,a in screen code

## What NOT to Do
- Do not suggest switching to Maven
- Do not suggest Vulkan (OpenGL 4.5 is current target)
- Do not introduce Spring, Jakarta, or any web framework
- Do not use deprecated LWJGL 2 APIs
- Do not add `Mesh`, `ShaderProgram`, or any `engine/` import to `server/` code
- Do not add rendering code to `World.java` or `ServerWorld.java`
- Do not call `new Mesh()` outside the main thread
- Do not hardcode character widths for UI centering — use `r.measureText()`
- Do not intercept Escape key in screen `onKeyPress()` for overlay screens —
  `GameLoop.handleEscapeKey()` owns that behavior