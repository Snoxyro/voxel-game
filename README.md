# Voxel Game Engine

A voxel game engine built from scratch in Java 21 with LWJGL 3. This is a long-term
learning project exploring engine architecture, graphics programming, and the practical
limits of AI-assisted development.

## Goals
- A functioning voxel game engine with chunked infinite terrain
- High performance rendering (greedy meshing, frustum culling, LOD)
- Multiplayer support (server/client architecture)
- Modding API (scripting runtime, block/item/entity registry)
- Honest documentation of where AI assistance helped and where it failed

## Technology Stack
| Component | Technology |
|---|---|
| Language | Java 21 (Eclipse Temurin) |
| Build | Gradle 8.x |
| Rendering | OpenGL 4.5 via LWJGL 3.3.4 |
| Windowing | GLFW via LWJGL |
| Math | JOML 1.10.7 |
| Audio | OpenAL via LWJGL |
| Networking | Netty 4.1.115.Final |

## Building and Running

Prerequisites: Java 21 JDK, Git
```bash
git clone https://github.com/Snoxyro/voxel-game.git
cd voxel-game

# Singleplayer (menu → world select → play)
./gradlew run

# Dedicated headless server
./gradlew runServer
./gradlew runServer --args="--world survival --port 25565"
```

### Controls
| Input | Action |
|---|---|
| WASD | Move |
| Mouse | Look |
| Space | Jump (physics) / Fly up (freecam) |
| Left Shift | Fly down (freecam only) |
| F | Toggle freecam / physics |
| Left click | Break block |
| Right click | Place block |
| 1 / 2 / 3 | Select Grass / Dirt / Stone |
| Escape (in-game) | Open pause menu |
| Escape (in menu) | Back / Resume |

All keybinds are rebindable in Settings → Keybinds. Settings are persisted to
`settings.properties` in the working directory.

## Architecture

Singleplayer runs as an embedded server on localhost — identical to Minecraft's
integrated server model. There is no separate singleplayer code path. `GameLoop`
owns the server lifecycle: world selection triggers a background launch thread
that starts `GameServer`, waits for the port to bind, connects the client, then
hands control back to the GL thread.

```
com.voxelgame.
├── Main.java                  ← minimal bootstrap: registries + GameLoop
├── common/                    ← shared types: Block, BlockView, Chunk, network packets
├── server/                    ← headless server — zero GL/LWJGL dependency
│   ├── GameServer.java        ← 20 TPS game loop; setRenderDistance()
│   ├── PlayerSession.java     ← per-client state: position, yaw/pitch, loaded chunks, visible players
│   ├── ServerWorld.java       ← per-player chunk streaming + dynamic player visibility
│   └── storage/               ← FlatFileChunkStorage, WorldMeta (seed persistence)
├── client/
│   ├── ClientWorld.java       ← receives chunks from server, meshes + renders; reset()
│   └── network/
├── engine/                    ← GL/GLFW systems — main thread only
│   ├── ui/                    ← GlyphAtlas, UiShader, UiRenderer (batched 2D quads)
│   │                             UiTheme (abstract), DarkTheme, LightTheme
│   └── GameLoop, Camera (setFov), Window, InputHandler (consumeMouseClick), ...
└── game/
    ├── screen/                ← Screen, ScreenManager, MainMenu, WorldSelect, PauseMenu,
    │                             MultiplayerConnectScreen, SettingsScreen
    ├── Action.java            ← enum of all bindable actions
    ├── KeyBindings.java       ← EnumMap<Action,Integer>; mouse offset encoding
    ├── GameSettings.java      ← persistent settings; load/save; WindowMode enum
    └── World (multi-viewer streaming; live renderDistanceH), TerrainGenerator, ChunkMesher, Player
```

### Network Protocol
Custom binary TCP via Netty.
Wire format: `[4-byte length][1-byte packet ID][payload]`.
Default port: **24463**.

## Project Structure
```
src/main/java/com/voxelgame/   ← Java source
src/main/resources/shaders/    ← GLSL shaders (default, hud, ui)
docs/                          ← Architecture decisions and design notes
worlds/                        ← World save directories (created at runtime)
settings.properties            ← User settings (created at runtime)
```

## Development Log
See [DEVLOG.md](DEVLOG.md) for a chronological record of progress,
decisions, and lessons learned — including honest notes on AI assistance.

## Phases
- [x] Phase 0 — Foundation
  - [x] Project setup (Gradle, LWJGL, JOML, Git)
  - [x] Window, OpenGL context, fixed-timestep game loop
  - [x] First triangle (shaders, VAO/VBO, ShaderProgram, Mesh)
- [x] Phase 1 — Chunk system and player movement
  - [x] Camera, input handler, freecam (MVP matrix pipeline)
  - [x] Flat chunk rendering with face culling
  - [x] Multi-chunk world management with model matrix translation
- [x] Phase 2 — World generation
  - [x] Noise terrain with vertex colors and directional shading
  - [x] fBm (4-octave fractional Brownian motion) terrain upgrade
  - [x] 3D chunk positions and full-height terrain layering
- [x] Phase 3 — Gameplay basics
  - [x] DDA raycasting, block break/place, block highlight wireframe
  - [x] Cursor toggle, window resize, aspect ratio correction
  - [x] Player physics (AABB collision, gravity, jumping)
  - [x] Air control, block placement guard, sky color
- [x] Phase 4 — Performance optimization
  - [x] Neighbor-aware meshing, window drag fix, rebuild sequencing fix
  - [x] Frustum culling, indexed rendering (EBO)
  - [x] Greedy meshing
  - [x] Chunk streaming with background generation
  - [x] Meshing on worker thread (neighbor snapshot isolation)
  - [x] Async neighbor remesh path, thread pool
  - [x] Terrain early exits, float[] buffer, flat byte[] block storage
  - [x] Heightmap column cache, Y occupancy clamping, schedule guard
  - [x] Async remesh pipeline bug fix
  - [x] Direction-biased generation queue (75/25 split)
  - [x] Ambient occlusion (baked per-vertex, diagonal flip)
  - [x] Textures (GL_TEXTURE_2D_ARRAY, procedural tiles, tiled UVs)
- [x] Phase 5 — Multiplayer
  - [x] 5A — Package restructure, Netty, handshake/login
  - [x] 5B — Chunk streaming over TCP
  - [x] 5C — Block interaction sync (break/place/broadcast)
  - [x] 5D — Player movement sync, RemotePlayer interpolation
  - [x] 5E — World persistence (ChunkStorage, dirty tracking, GZIP files)
  - [x] 5F — CLI args, world.dat seed file, configurable launch
- [ ] Phase 6 — Foundation for extensibility
  - [x] 6A — Block Registry (Block enum → registered class, ID-stable saves)
  - [x] 6B — Menu / UI System
    - [x] 6B-1 — UI rendering foundation (GlyphAtlas, UiShader, UiRenderer)
    - [x] 6B-2 — Screen abstraction (Screen, ScreenManager, GameLoop wiring)
    - [x] 6B-3 — Main menu (Singleplayer / Multiplayer / Settings / Quit)
    - [x] 6B-4 — World selection screen (list, create with seed, delete, launch)
    - [x] 6B-5 — Multiplayer connect screen (IP/port input, direct server connect)
    - [x] 6B-6 — In-game pause menu (overlay, Resume / Settings / Main Menu / Quit)
    - [x] 6B-theme — UI Theme system (UiTheme, DarkTheme, LightTheme)
    - [x] Multiplayer bug fixes (chunk load order, block-in-hitbox, per-player streaming, player visibility)
    - [x] 6B-7 — Full settings system (GameSettings, KeyBindings, Action, SettingsScreen)
  - [ ] 6C — Lighting + Day/Night Cycle
  - [ ] 6D — Entity System + Player Model (nametags deferred to here)
  - [ ] 6E — Items + Inventory
- [ ] Phase 7 — Modding API
  - [ ] Block / item / entity registry hooks exposed to external code
  - [ ] World gen hooks, event listeners
  - [ ] Scripting runtime