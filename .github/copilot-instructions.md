# Copilot Instructions — Voxel Game Engine

## Project Overview
This is a voxel game engine built with Java 21 and LWJGL 3. It is a long-term learning
project exploring engine architecture, graphics programming, and the boundaries of
AI-assisted development. The goal is a feature-complete voxel game with multiplayer,
modding support, and high performance.

## Technology Stack
- **Language:** Java 21
- **Build:** Gradle 8.x with Gradle wrapper
- **Rendering:** OpenGL 4.5 via LWJGL 3.3.4
- **Windowing:** GLFW via LWJGL
- **Math:** JOML (Java OpenGL Math Library)
- **Audio:** OpenAL via LWJGL

## Package Structure
- `com.voxelgame.engine` — Core engine systems: renderer, window, game loop, ECS
- `com.voxelgame.game` — Gameplay logic: world, chunks, player, entities
- `com.voxelgame.util` — Shared utilities: logging, math helpers, resource loading

## Architecture Principles
- Engine and game logic must remain strictly separated. Nothing in `engine` should
  import from `game`.
- All OpenGL calls must be made on the main thread only.
- Use abstraction interfaces for renderer, mesh, and shader — future Vulkan backend
  compatibility is a long-term goal.
- Prefer composition over inheritance. ECS (Entity Component System) is the target
  architecture for entities.
- No magic numbers. All constants belong in dedicated constants classes.

## Code Style
- Standard Java naming conventions strictly followed.
- Every public class and method must have a Javadoc comment.
- No raw types. Generics must be fully specified.
- Prefer explicit error handling over silent catches. Log all exceptions with context.
- Resource management: all OpenGL resources must be explicitly cleaned up in a
  `cleanup()` method. Use try-with-resources where applicable.

## Current Development Phase
Phase 0 — Foundation. Focus is on: window creation, OpenGL context initialization,
game loop, basic shader loading, and drawing a triangle.

## Key Constraints
- OpenGL calls on main thread only — never pass GL calls to worker threads.
- Chunk mesh generation happens on worker threads, but mesh upload to GPU happens
  on the main thread.
- Memory: target under 4GB heap usage. JVM args set to -Xmx4G.

## What NOT to do
- Do not suggest switching to Maven. Gradle is the build tool for this project.
- Do not suggest Vulkan. OpenGL 4.5 is the current renderer target.
- Do not introduce Spring, Jakarta, or any web framework dependencies.
- Do not use deprecated LWJGL 2 APIs. All LWJGL usage must be LWJGL 3 style.