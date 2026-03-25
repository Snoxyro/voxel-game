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
│   ├── GameServer.java        ← 20 TPS game loop; setRenderDistance(int) delegates to ServerWorld
│   ├── PlayerSession.java     ← per-client: channel, x/y/z, yaw/pitch, loadedChunks, visiblePlayerIds
│   ├── ServerWorld.java       ← multi-viewer World.update(); per-player chunk streaming and visibility
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
│   ├── GameLoop.java          ← owns ScreenManager + GameSettings; launchWorld(), returnToMainMenu(),
│   │                             openSettings(), applySettings(), createPauseMenu(), isSessionActive()
│   ├── Camera.java            ← setFov(int degrees) — mutable, driven by GameSettings
│   ├── InputHandler.java      ← resetMouseDelta(), consumeMouseClick(), wasKeyJustPressed(),
│   │                             onKeyPressed(), clearJustPressed()
│   └── Window, ShaderProgram, Mesh, TextureManager, HudRenderer, BlockHighlightRenderer
└── game/
    ├── screen/
    │   ├── Screen.java                   ← interface; render(UiTheme, float deltaTime, int w, int h);
    │   │                                    isOverlay() default false
    │   ├── ScreenManager.java            ← owns UiTheme; setTheme(), getTheme(), renderActiveScreen()
    │   ├── MainMenuScreen.java           ← 4 callbacks: onSingleplayer, onMultiplayer, onSettings, onQuit
    │   ├── WorldSelectScreen.java        ← statusMessage field + setStatusMessage() for launch errors
    │   ├── PauseMenuScreen.java          ← isOverlay() = true; 4 callbacks incl. onSettings
    │   ├── MultiplayerConnectScreen.java ← ConnectHandler FI; cancelledFlag abort; connecting lock
    │   └── SettingsScreen.java           ← tabbed settings (Gameplay/Graphics/Display/Controls/Keybinds/Sound);
    │                                        working-copy pattern; focusedSlider for arrow-key nudge
    ├── Action.java            ← enum of all bindable actions; displayName for UI
    ├── KeyBindings.java       ← EnumMap<Action,Integer>; MOUSE_BUTTON_OFFSET=1000; displayName(int);
    │                             copy(), setAll(); full key name table (F1-F12, Home, End, etc.)
    ├── GameSettings.java      ← settings.properties persistence; load/save atomic; WindowMode enum;
    │                             DEFAULT_MOUSE_SENS=1.0f (human-readable; 0.1f base applied at camera)
    └── World (ViewerInfo record; update(List<ViewerInfo>); setRenderDistance(int) — live mutable field),
        TerrainGenerator, ChunkMesher, Player, ChunkStorage
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

### GL Resource Construction Timing
Any class instantiated before `GameLoop.init()` must not create GL resources in its
constructor or field initializers. The pattern is: nullable field + separate
`initRenderResources()` method called from `GameLoop.init()` after `window.init()`.
This has caused two crashes in the project history — it is a hard rule.

### BlockView Interface
`PhysicsBody`, `RayCaster`, and `Player` accept `BlockView` (not `World` or
`ClientWorld`). Both `World` and `ClientWorld` implement `BlockView`. Never change
these to accept a concrete type — that would break the client/server separation.

### Multi-Viewer Chunk Streaming
`World.update()` accepts `List<World.ViewerInfo>`. Chunks load if in range of ANY
viewer; unload only when out of range of ALL viewers. `ServerWorld` builds this list
from all connected players every tick. Each player's client only receives chunks
within their own personal render distance — the server loaded set is the union, but
per-player streaming is range-gated.

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
Phase 6 — Foundation for extensibility. Phase 6B complete. Next: 6C Lighting.

### Phase 6 status
- 6A done: Block Registry
- 6B done: Full UI/menu system including settings
  - UiTheme system, DarkTheme, LightTheme
  - Main menu, world select, multiplayer connect, pause menu
  - GameSettings, KeyBindings, Action — persistent settings.properties
  - SettingsScreen — tabbed UI (Gameplay/Graphics/Display/Controls/Keybinds/Sound)
  - Live render distance, live FOV/VSync/theme/window mode on save
  - Multiplayer bugs fixed: chunk load order, block-in-hitbox, per-player streaming, visibility
- 6C (in progress): Lighting + Day/Night Cycle
  - 6C-1/2/3 (done): LightEngine, vertex light baking, shader integration
  - 6C-4 (next): Brightness slider + AO toggle settings
  - 6C-5: Day/night cycle (WorldTime, ambientFactor uniform, server packet)
  - 6C-6: Skylight recompute on block place/break

### After 6C:
- 6D: Entity System + Player Model (nametags deferred to here)
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
- Call `inputHandler.consumeMouseClick()` after any UI click that transitions back
  to gameplay (e.g. Resume button). Without this, the click bleeds into game input
  and fires wasMouseButtonJustPressed on the next frame.
- Button/title text centering must use `r.measureText()` — hardcoded `length * N`
  values are always wrong for proportional or atlas-based fonts.
- Screen.render() signature: `(UiTheme theme, float deltaTime, int sw, int sh)` —
  never add UiRenderer as a screen parameter; never drop deltaTime
- All colors in themes are packed 0xRRGGBBAA ints — never use float r,g,b,a in screen code
- World.renderDistanceH is a mutable instance field driven by GameSettings.
  Use world.getRenderDistanceH() / world.setRenderDistance(int). Never make it static again.
- Mouse sensitivity stored as human-readable multiplier (1.0 = normal feel).
  The 0.1f base scale factor is applied at the camera rotation call site in GameLoop,
  not stored in GameSettings. Do not change this — it would break saved settings.
- Do not revert World.update() to a single-viewer signature — it accepts
  List<World.ViewerInfo> and that is a locked architecture decision

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