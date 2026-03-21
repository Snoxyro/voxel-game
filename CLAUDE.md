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
- Learning OpenGL, GLSL, 3D math, and ECS architecture through this project

## Technology Stack
- **Language:** Java 21 (Eclipse Temurin)
- **Build:** Gradle 8.x with Gradle wrapper (gradlew)
- **Rendering:** OpenGL 4.5 via LWJGL 3.3.4
- **Windowing:** GLFW via LWJGL
- **Math:** JOML 1.10.7
- **Audio:** OpenAL via LWJGL
- **IDE:** VS Code with Extension Pack for Java, Gradle for Java, GitHub Copilot

## Package Structure
```
src/main/java/com/voxelgame/
├── engine/     ← Core engine: renderer, window, game loop, ECS, shaders
├── game/       ← Gameplay: world, chunks, player, blocks, entities
└── util/       ← Utilities: logging, resource loading, math helpers

src/main/resources/
├── shaders/    ← GLSL vertex and fragment shaders
├── textures/   ← Texture atlases, block textures
└── sounds/     ← Audio files
```

## Architecture Decisions (Locked)
1. **OpenGL 4.5 now, Vulkan later** — renderer is abstracted behind interfaces so
   a Vulkan backend can be added without touching game logic
2. **ECS architecture** for entities — no deep inheritance hierarchies
3. **Strict engine/game separation** — engine package never imports from game package
4. **Multithreading model** — chunk generation on worker threads, GPU upload on main thread
5. **OpenGL single-thread rule** — all GL calls on the main thread, no exceptions

## Development Phases
- **Phase 0 (current):** Foundation — window, OpenGL context, game loop, triangle
- **Phase 1:** Chunk system, flat world, player movement
- **Phase 2:** World generation — noise terrain, biomes
- **Phase 3:** Gameplay basics — block placing/breaking, inventory
- **Phase 4:** Performance — greedy meshing, LOD, culling, threading
- **Phase 5:** Multiplayer — server/client architecture
- **Phase 6:** Modding API — scripting runtime, registry system

## How to Build and Run
```bash
./gradlew run          # Run the game
./gradlew build        # Build the project
./gradlew clean build  # Clean build
```

## Important Notes for Claude
- When reading this repository, always check DEVLOG.md for the latest progress context
- The developer is learning graphics programming from zero — explain OpenGL/GLSL
  concepts when introducing them, don't assume prior knowledge
- Prioritize code the developer can understand over clever optimizations
- Always flag when a design decision will have long-term architectural consequences
- This project intentionally explores AI limitations — be honest when a problem
  requires human debugging judgment that AI cannot reliably provide