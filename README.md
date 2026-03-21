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
git clone https://github.com/YOUR_USERNAME/voxel-game.git
cd voxel-game
./gradlew run
```

## Project Structure
```
src/main/java/com/voxelgame/
├── engine/     ← Core engine systems
├── game/       ← Gameplay logic
└── util/       ← Shared utilities

docs/           ← Architecture decisions and design notes
```

## Development Log
See [DEVLOG.md](DEVLOG.md) for a chronological record of progress,
decisions, and lessons learned.

## Phases
- [x] Phase 0 — Foundation (window, OpenGL context, game loop)
- [x] Phase 1 — Chunk system and flat world
- [x] Phase 2 — World generation
- [x] Phase 3 — Gameplay basics
- [ ] Phase 4 — Performance optimization
- [ ] Phase 5 — Multiplayer
- [ ] Phase 6 — Modding API