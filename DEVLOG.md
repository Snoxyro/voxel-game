# Development Log

A chronological record of progress, decisions, problems encountered, and lessons
learned. Includes honest notes on AI assistance — what worked, what didn't.

---

## Entry 001 — Project Setup
**Date:** 21.03.2026
**Phase:** 0 — Foundation

### What Was Done
- Installed Java 21 JDK (Eclipse Temurin) and Gradle 8.x
- Initialized Gradle project with LWJGL 3.3.4 and JOML dependencies
- Created project folder structure and Git repository
- Wrote context files: CLAUDE.md, .github/copilot-instructions.md
- Configured VS Code with Java and Gradle extensions

### Decisions Made
- **OpenGL over Vulkan:** Vulkan's setup overhead would have required learning two
  things simultaneously — graphics fundamentals and an explicit API. OpenGL 4.5 removes
  that blocker. Renderer will be abstracted behind interfaces for a potential Vulkan
  backend later.
- **Java 21 LTS:** Chose LTS over the installed Java 25 for long-term tooling stability.
- **Gradle over Maven:** Better dependency management for native LWJGL binaries.

### AI Assistance Notes
- Claude guided the entire setup process step by step.
- Caught a JAVA_HOME conflict between user and system environment variables that
  would have caused Gradle to compile with JDK 17 silently.
- Context files (CLAUDE.md, copilot-instructions.md) written with AI assistance
  to establish consistent guidance throughout the project.

### Lessons / Observations
- Environment setup on Windows has more friction than expected — PATH, JAVA_HOME,
  user vs system variables all interact in non-obvious ways.
- Starting a long project with proper documentation and context files is worth the
  upfront time investment.

---

## Entry 002 — Smoke Test
**Date:** 21.03.2026
**Phase:** 0 — Foundation

### What Was Done
- Created Main.java, Window.java, GameLoop.java
- Window opens successfully with OpenGL 4.5 core profile context
- Fixed-timestep game loop running at 60 UPS, ~120 FPS (vsync off)
- Clean shutdown confirmed

### Decisions Made
- Fixed timestep at 60 UPS with uncapped FPS — accumulator pattern
- V-sync left off for now to observe raw performance numbers
- glClearColor set to dark grey as visual confirmation of context activity

### Problems Encountered
- None at this stage

### AI Assistance Notes
- Claude wrote all three files with full explanation of each concept
- GLFW context creation, OpenGL capability binding, and game loop structure
  all explained from first principles before code was provided

### Lessons / Observations
- Windows blocks the main thread during window drag — causes UPS spike on release.
  This is a known OS behavior, not a code bug. To be addressed later.
- Running via ./gradlew run is correct. Right-click Run on Main.java bypasses
  Gradle dependency resolution.

---

## Entry 003 — First Triangle
**Date:** 21.03.2026
**Phase:** 0 — Foundation

### What Was Done
- Created GLSL vertex and fragment shaders (default.vert, default.frag)
- Implemented ShaderProgram.java — loads, compiles, and links shader stages from
  classpath resources
- Implemented Mesh.java — uploads vertex data to GPU via VAO and VBO
- Updated GameLoop.java to initialize and render the triangle mesh
- Orange triangle renders correctly centered on screen

### Decisions Made
- Shaders loaded from classpath resources under src/main/resources/shaders/ —
  clean separation of GLSL from Java code
- VAO/VBO abstracted behind Mesh class — GameLoop has no direct buffer management
- ShaderProgram handles its own resource loading — self-contained compilation unit
- Vertex data in NDC (Normalized Device Coordinates) for now — projection matrix
  and aspect ratio correction deferred to Phase 1

### Problems Encountered
- None. Triangle rendered correctly on first run.

### AI Assistance Notes
- Claude wrote all files with concept explanations preceding each one
- GPU pipeline explained from first principles: vertex shader → rasterization →
  fragment shader
- VAO/VBO relationship explained before implementation
- Developer chose to understand the shape of the code rather than every detail —
  intentional approach consistent with the AI-assisted learning experiment

### Lessons / Observations
- Triangle stretches when window is resized — expected behavior at this stage.
  NDC coordinates have no awareness of aspect ratio. Projection matrix in Phase 1
  will fix this.
- GLSL compilation errors surface directly in the terminal via glGetShaderInfoLog —
  shader debugging is more accessible than expected.
- Phase 0 complete. Full pipeline from Java → GPU → screen is verified working.

---

## Entry 004 — Camera, Input, and Freecam
**Date:** 21.03.2026
**Phase:** 1 — Chunk System and Player Movement

### What Was Done
- Implemented `Camera.java` — maintains position, yaw, pitch; produces view and
  projection matrices via JOML `lookAt` and `perspective`
- Added `setUniform(String, Matrix4f)` to `ShaderProgram.java` — uploads mat4
  uniforms to the GPU via `MemoryStack`
- Updated `default.vert` to apply `projectionMatrix * viewMatrix` transform —
  triangle now renders with correct perspective and aspect ratio
- Implemented `InputHandler.java` — GLFW cursor capture, per-frame mouse delta
  sampling, keyboard polling via `glfwGetKey`
- Wired camera movement into `GameLoop.update()` — WASD + Space/Shift fly-cam,
  mouse look via yaw/pitch, Escape closes the window
- Exposed `getWindowHandle()` on `Window.java` to allow `InputHandler` construction

### Decisions Made
- Movement computed from yaw only, not pitch — standard FPS convention so looking
  up doesn't cause the player to fly upward
- Mouse delta consumed in `update()` at fixed 60 UPS rather than in `render()` —
  keeps rotation speed frame-rate independent
- First mouse frame skipped in `InputHandler` — prevents camera snap on cursor capture
- Pitch clamped to ±89° in `Camera.setPitch()` — prevents gimbal flip at straight
  up/down

### Problems Encountered
- Freecam correctness could not be visually confirmed yet — the scene contains 
  only a grey background, giving no spatial reference. Assumed working
  based on cursor lock and Escape closing the window correctly.

### AI Assistance Notes
- Claude wrote all files with concept explanations: MVP matrix pipeline, Euler angles,
  mouse delta sampling, frame-rate independent input
- Copilot student plan no longer includes Claude Sonnet/Opus models — delegating to
  Copilot is not viable for now, Claude writing all code directly going forward

### Lessons / Observations
- The MVP matrix pipeline explanation (model → view → projection, right-to-left
  application) was necessary before the code made sense
- A flat chunk with multiple blocks at known positions will be the first real test
  of whether the camera and movement are working correctly

---

## Entry 005 — Flat Chunk and Verified Freecam
**Date:** 21.03.2026
**Phase:** 1 — Chunk System and Player Movement

### What Was Done
- Implemented `Block.java` (enum) in `com.voxelgame.game` — AIR and GRASS types
- Implemented `Chunk.java` — 16×16×16 block array, bounds-safe get/set/isAir
- Implemented `ChunkMesher.java` — face-culled mesh generation, emits only faces
  adjacent to air
- Replaced triangle with a flat 16×16 GRASS chunk (y=0 layer filled)
- Enabled `GL_DEPTH_TEST` and `GL_CULL_FACE` in GameLoop init
- Split `Window.update()` into `pollEvents()` and `swapBuffers()` — events now
  polled at the start of each frame, eliminating one frame of input lag
- Repositioned camera spawn to `(8, 5, 20)` — spawns above and in front of chunk

### Problems Encountered
- Camera spawned inside the chunk on first run — no spatial reference, WASD appeared
  broken. Fixed by repositioning spawn above the chunk.
- Side faces rendered inverted — outside invisible, inside visible. Root cause was
  incorrect CCW winding order on the four side quads in ChunkMesher. Fixed by
  reversing vertex order on NORTH, SOUTH, EAST, WEST faces.
- Left/right movement inverted — right vector signs were flipped in GameLoop.update().
  Fixed by negating sin component and using positive cos.
- Uniform upload bug carried over from Entry 004 — uniforms were being set after
  `shaderProgram.unbind()`. Fixed by moving setUniform calls between bind and render.

### AI Assistance Notes
- Claude wrote Block, ChunkMesher, and the GameLoop wiring with concept explanation
- Chunk.java delegated to GitHub Copilot (GPT Codex) via prompt
- Winding order bug required a second pass — original side face quads were incorrect
- Input lag fix identified during debugging of perceived WASD unresponsiveness

### Lessons / Observations
- Face culling winding order is easy to get wrong — top/bottom and sides don't share
  the same "natural" CCW orientation when you write coordinates by hand
- Having actual geometry in the scene immediately exposed camera position assumptions
  that weren't visible with just a triangle
- Chunk render confirms the full pipeline: Java block data → mesher → GPU upload →
  camera transforms → correct 3D output

---

## Entry 006 — Multi-Chunk World Management
**Date:** 21.03.2026
**Phase:** 1 — Chunk System and Player Movement

### What Was Done
- Implemented `ChunkPos.java` as a Java record — immutable chunk grid coordinate
  with `worldX()` and `worldZ()` helpers for world-space translation
- Implemented `World.java` — holds chunks and GPU meshes in separate HashMaps,
  handles mesh generation, per-chunk model matrix translation, and cleanup
- Updated `default.vert` to include `modelMatrix` uniform — full MVP transform
  now in place (model × view × projection)
- Added `setUniform(String, Matrix4f)` call for model matrix in `World.render()`
- Updated `GameLoop` to use `World` — seeds a 4×4 grid of flat GRASS chunks
- Removed single hardcoded `Mesh` from `GameLoop`

### Decisions Made
- `ChunkPos` uses only X and Z — one chunk per vertical column for now. Adding
  vertical stacking later will require adding Y to ChunkPos and updating World.
- `Chunk` and `Mesh` stored in separate maps — chunk data and GPU resources remain
  decoupled, allowing independent mesh rebuilds and future streaming
- Empty chunks (all air) produce no mesh — guarded in `rebuildMesh()`

### Problems Encountered
- None. 4×4 chunk grid rendered correctly on first run.

### AI Assistance Notes
- Claude wrote all files with concept explanations for chunk-space vs world-space
  coordinates and the model matrix translation

### Lessons / Observations
- Faces between adjacent chunks are still generated and visible from below —
  known limitation, deferred to Phase 4 optimization (neighbor-aware meshing)
- Chunk seams are invisible from above — world-space translation via model matrix
  is seamless
- Phase 1 complete. Camera, input, chunk data, meshing, and world management
  all verified working.

---

## Entry 007 — Noise Terrain and Vertex Colors
**Date:** 21.03.2026
**Phase:** 2 — World Generation

### What Was Done
- Added `OpenSimplex2S.java` to `com.voxelgame.util` (public domain, static API)
- Added DIRT and STONE to `Block.java` with per-block `color()` returning float[3] RGB
- Implemented `TerrainGenerator.java` — 2D noise height map with BASE_HEIGHT + HEIGHT_VARIATION
  range, GRASS/DIRT/STONE layering rules, world-space coordinate sampling for seamless
  cross-chunk consistency
- Rewrote `Mesh.java` to use interleaved vertex layout: [x, y, z, r, g, b] — 6 floats per
  vertex, stride/offset setup for two vertex attribute slots
- Rewrote `ChunkMesher.java` to emit color data alongside position, with directional
  brightness multipliers baked at mesh-build time: top=1.0, sides=0.7, bottom=0.5
- Updated `default.vert` to receive color at layout location 1 and pass to fragment shader
- Updated `default.frag` to output interpolated vertex color instead of hardcoded orange
- Updated `GameLoop.init()` to seed world via TerrainGenerator with fixed seed 12345L

### Decisions Made
- OpenSimplex2S static API used directly — seed passed per noise call, no instance state
- Directional shading baked into mesh at build time rather than computed in shader —
  simple, zero runtime cost, sufficient for this phase
- Block color lives on the Block enum — block-specific data belongs with the block definition
- NOISE_SCALE=0.04 produces broad smooth hills; tunable constant for later biome work

### Problems Encountered
- Downloaded OpenSimplex2S uses a static API (seed per call) not a constructor-based
  instance. TerrainGenerator adjusted to store the seed as a long and pass it to each
  noise2() call.

### AI Assistance Notes
- Claude wrote all files with concept explanations for interleaved vertex buffers,
  stride/offset layout, and noise remapping math
- All code worked on first run after the OpenSimplex2S API mismatch was corrected

### Lessons / Observations
- Directional shading with a single brightness multiplier per face direction gives
  surprisingly good depth readability at near-zero cost
- Noise scale has a large visual impact — worth exposing as a tunable constant early
- Stone is not visible from above yet — need to walk to the edge to see the layering.
  Caves or overhangs in a later phase will expose it from above.

---

<!-- 
DEVLOG TEMPLATE — copy this block for each new entry:

## Entry XXX — [Title]
**Date:** 
**Phase:** 

### What Was Done

### Decisions Made

### Problems Encountered

### AI Assistance Notes

### Lessons / Observations

-->