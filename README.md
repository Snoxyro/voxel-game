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

## Building and Running

Prerequisites: Java 21 JDK, Git
```bash
git clone https://github.com/Snoxyro/voxel-game.git
cd voxel-game

# Singleplayer (embedded server + client window)
./gradlew run

# Dedicated headless server only
./gradlew runServer
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
| Escape | Release cursor |

## Architecture

Singleplayer runs as an embedded server on localhost — identical to Minecraft's
integrated server model. There is no separate singleplayer code path.
```
com.voxelgame.
├── Main.java                  ← singleplayer: embedded server + client on localhost
├── common/                    ← shared types used by both client and server
│   ├── world/                 ← Block, Chunk, ChunkPos, PhysicsBody, RayCaster, BlockView
│   └── network/               ← wire protocol: packets, encoder, decoder
├── server/                    ← headless server — zero GL/LWJGL dependency
│   ├── GameServer.java        ← 20 TPS game loop
│   ├── ServerWorld.java       ← chunk streaming per player
│   ├── PlayerSession.java     ← per-client state
│   └── network/               ← Netty pipeline, ClientHandler
├── client/                    ← client-side logic
│   ├── ClientWorld.java       ← receives chunks from server, meshes + renders
│   └── network/               ← Netty pipeline, ServerHandler
├── engine/                    ← GL/GLFW systems — client main thread only
│   └── GameLoop, Camera, Window, InputHandler, ShaderProgram, Mesh, etc.
└── game/                      ← server-side gameplay
    └── World, TerrainGenerator, ChunkMesher, Player
```

### Network Protocol
Custom binary TCP via Netty. Wire format: `[4-byte length][1-byte packet ID][payload]`.
Default port: **24463**.

## Project Structure
```
src/main/java/com/voxelgame/   ← Java source
src/main/resources/shaders/    ← GLSL shaders
docs/                          ← Architecture decisions and design notes
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
  - [x] Neighbor-aware meshing, window drag fix
  - [x] Neighbor rebuild sequencing fix
  - [x] Frustum culling, indexed rendering (EBO)
  - [x] Greedy meshing
  - [x] Chunk streaming with background generation
  - [x] Meshing moved to worker thread (neighbor snapshot isolation)
  - [x] Async neighbor remesh path
  - [x] Thread pool (availableProcessors - 1 workers)
  - [x] Terrain early exits, float[] buffer, flat byte[] block storage
  - [x] Heightmap column cache, Y occupancy clamping, schedule guard
  - [x] Async remesh pipeline bug fix (self-remesh + dirty mark loss)
  - [x] Direction-biased generation queue (75/25 split)
  - [x] Ambient occlusion (baked per-vertex, diagonal flip)
  - [x] Textures (GL_TEXTURE_2D_ARRAY, procedural tiles, tiled UVs)
- [ ] Phase 5 — Multiplayer (in progress)
  - [x] 5A — Package restructure, Netty, handshake/login
  - [x] 5B — Chunk streaming over TCP
  - [x] 5C — Block interaction sync (break/place/broadcast)
  - [ ] 5D — Player movement sync and other-player rendering
    - [x] PlayerMoveSBPacket, streaming center follows player
    - [ ] Other-player broadcasting (PlayerSpawn/Move/Despawn)
    - [ ] RemotePlayer with position interpolation
    - [ ] Client-side prediction + server reconciliation
  - [ ] 5E — World persistence (save/load chunks to disk)
  - [ ] 5F — Singleplayer integration cleanup
- [ ] Phase 6 — Modding API