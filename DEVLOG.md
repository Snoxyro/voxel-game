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

### Addendum — fBm Terrain Upgrade
- Replaced single-octave noise with 4-octave fractional Brownian motion (fBm)
- Configurable constants: OCTAVES, BASE_FREQUENCY, LACUNARITY, PERSISTENCE
- Per-octave seed offset (seed + i * 31337L) prevents harmonic interference at origin
- BASE_HEIGHT raised to 8, HEIGHT_VARIATION raised to 24
- Chunk grid expanded from 4×4 to 8×8 for visible terrain variation
- MOVEMENT_SPEED raised from 0.05 to 0.15 (3× increase)
- Observation: BASE_FREQUENCY is the most impactful tuning knob for terrain character

---

## Entry 008 — 3D Chunk Positions and Full-Height Terrain
**Date:** 21.03.2026
**Phase:** 2 — World Generation

### What Was Done
- Extended `ChunkPos` from a 2D record (x, z) to a 3D record (x, y, z) with a
  `worldY()` helper alongside the existing `worldX()` and `worldZ()`
- Updated `TerrainGenerator.generateChunk()` to generate only the blocks that fall
  within a given chunk's world-space Y range — chunks fully above the surface remain
  air, chunks fully below are stone, boundary chunks receive the GRASS/DIRT/STONE
  layering as before
- Updated `World.render()` model matrix to include Y translation via `pos.worldY()`
- Updated `GameLoop` to seed an 8×5×8 chunk grid (8 XZ, 5 Y levels = 80 blocks
  of vertical headroom)
- Raised `BASE_HEIGHT` to 16 and `HEIGHT_VARIATION` to 48 — surface range is now
  [16, 64], fully visible within the 5 Y chunk levels
- Raised `BASE_FREQUENCY` to 0.006 for more visible terrain variation across the grid
- Removed unused `chunkWorldYTop` variable from `TerrainGenerator`

### Decisions Made
- **3D chunk grid is the correct long-term architecture.** The 2D column model
  (one chunk per XZ column) would have imposed a hard height limit and made caves,
  underground structures, and vertical streaming impossible without a painful retrofit.
  Changing this now while the codebase is small was the right call.
- Chunks are the atomic unit of the world in all three axes — sky, surface, and
  underground all live in the same chunk type with no special cases.
- `TerrainGenerator` computes surface height in world space and each chunk only
  fills its own Y slice — the generator is stateless and parallelizable per chunk.

### Problems Encountered
- Unused variable `chunkWorldYTop` left in from a draft early-exit — removed.

### AI Assistance Notes
- Claude identified this as the last easy moment to make the architectural change
  before Phase 3 gameplay adds more dependencies on ChunkPos
- All four changed files (ChunkPos, TerrainGenerator, World, GameLoop) worked
  correctly on first run

### Lessons / Observations
- The change touched only 4 files and left Chunk, ChunkMesher, Mesh, ShaderProgram,
  and all shaders completely untouched — good sign that the abstraction boundaries
  are in the right places
- With HEIGHT_VARIATION at 48, terrain height differences are now clearly visible
  as multi-chunk cliffs and valleys rather than shallow steps
- The 3D chunk grid means future features (caves, dungeons, floating islands, sky
  limit removal) all come for free with no further architectural changes

---

## Entry 009 — Block Interaction, Highlight, and HUD
**Date:** 21.03.2026
**Phase:** 3 — Gameplay Basics

### What Was Done
- Implemented `RaycastResult.java` — immutable record carrying hit/miss state,
  struck block coordinates, and face normal. `placeX/Y/Z()` helpers compute the
  adjacent placement position from the face normal.
- Implemented `RayCaster.java` — DDA (Digital Differential Analyzer) algorithm
  that steps a ray through the voxel grid one block boundary at a time. Records
  the last axis crossed to derive the face normal for free.
- Added `getBlock(worldX, worldY, worldZ)` and `setBlock(worldX, worldY, worldZ, Block)`
  to `World.java`. Both use `Math.floorDiv` / `Math.floorMod` to correctly resolve
  negative world coordinates to chunk and local positions.
- Implemented `BlockHighlightRenderer.java` — wireframe cube outline rendered with
  `GL_LINES` around the targeted block. Expanded slightly beyond block bounds to
  prevent z-fighting. Reuses the main 3D shader.
- Implemented `HudRenderer.java` — 2D screen-space crosshair rendered with a
  dedicated HUD shader that bypasses the MVP pipeline entirely (NDC coordinates
  direct). Aspect ratio compensated for 16:9.
- Added `hud.vert` and `hud.frag` shaders under `src/main/resources/shaders/`.
- Added mouse button edge detection to `InputHandler.java` — `wasMouseLeftClicked()`
  and `wasMouseRightClicked()` fire only on the frame the button transitions down,
  preventing hold-to-rapid-fire behavior.
- Wired all systems into `GameLoop`: raycast each update tick, left click breaks
  the targeted block, right click places the selected block on the struck face.
- Block type selection via keys 1 (GRASS), 2 (DIRT), 3 (STONE).

### Decisions Made
- DDA preferred over fixed-step ray marching — exact boundary crossings, no
  block-skip artifacts at steep angles, and face normal comes out of the algorithm
  for free with no extra work.
- `wasMouseLeftClicked()` / `wasMouseRightClicked()` are edge-triggered, not
  level-triggered — holding a mouse button should not continuously modify the world.
  Continuous break/place is a gameplay decision deferred to later.
- Block highlight reuses the main shader rather than a dedicated one — the
  interleaved vertex format is identical, only the draw primitive changes
  (GL_LINES vs GL_TRIANGLES).
- HUD uses its own shader with no uniforms and no MVP transform — HUD geometry
  is authored directly in NDC, the simplest possible approach for a static overlay.
- `Math.floorDiv` / `Math.floorMod` used throughout world coordinate resolution —
  Java's `/` truncates toward zero, which maps world X=-1 to chunk 0 incorrectly.
  Floor division is the correct semantic for voxel grids.

### Problems Encountered
- None. All systems worked on first run.

### AI Assistance Notes
- Claude wrote all new files with concept explanations for DDA, NDC coordinates,
  and edge-triggered input detection.
- `Math.floorDiv` / `Math.floorMod` distinction flagged proactively — a silent
  correctness bug that would only surface when the player moves into negative
  world coordinates.

### Lessons / Observations
- DDA is satisfying to understand — the "always jump to the nearest boundary"
  insight makes it feel inevitable once explained.
- The face normal falling out of DDA for free (recording which axis was last
  crossed) is an elegant property of the algorithm.
- Known limitation: breaking or placing a block on a chunk boundary does not
  update the adjacent chunk's mesh. The seam face remains until Phase 4 adds
  neighbour-aware mesh rebuilding.

---

## Entry 010 — Cursor Toggle and Window Resize
**Date:** 21.03.2026
**Phase:** 3 — Gameplay Basics

### What Was Done
- Escape now toggles cursor release instead of closing the window. Left-clicking
  the window re-captures the cursor. The re-capture click is consumed and does
  not fire a block break.
- Added `GLFWFramebufferSizeCallback` to `Window.java` — updates the GL viewport
  immediately when the window is resized. Actual framebuffer size queried at init
  via `glfwGetFramebufferSize` for correct HiDPI handling.
- `Window` now exposes `getFramebufferWidth()` / `getFramebufferHeight()`.
- `Camera.setAspectRatio(width, height)` added — aspect ratio is no longer baked
  at construction time. `GameLoop` compares framebuffer dimensions each tick and
  calls this only when a change is detected.
- Camera mouse look and block interaction both gated behind `cursorCaptured` flag.

### Decisions Made
- Cursor toggle on Escape is standard for PC games — gives the player OS access
  without closing the application.
- Aspect ratio re-checked every tick via integer comparison — effectively free,
  and simpler than a callback chain that would need to cross the window/camera
  boundary.
- Framebuffer size used instead of logical window size — on HiDPI displays these
  differ, and the framebuffer size is what the GL viewport must match.

### Problems Encountered
- None.

### Known Issue (deferred)
- Rendering freezes while the window border is actively being dragged. This is
  the same Windows modal loop behavior noted in Entry 002. The main thread is
  blocked by the OS during the drag operation, causing a UPS/FPS spike on
  release. Fixing this requires platform-specific workarounds or render thread
  restructuring — deferred to Phase 4.

### AI Assistance Notes
- Claude wrote all changes.

### Lessons / Observations
- `GLFWFramebufferSizeCallback` must be held in a field — if it goes out of
  scope and gets garbage collected, GLFW will crash calling into a freed native
  pointer. A subtle Java/native interop hazard.

---

## Entry 011 — Player Physics and Freecam Toggle
**Date:** 22.03.2026
**Phase:** 3 — Gameplay Basics

### What Was Done
- Implemented `PhysicsBody.java` — reusable physics component owning position,
  velocity, AABB dimensions, gravity, and per-axis collision resolution against
  world blocks. Designed to be owned by any entity (player, mob, etc.) without
  modification.
- Implemented `Player.java` — owns a PhysicsBody, translates horizontal movement
  intent into velocity each tick, exposes eye position for camera placement.
- Added F-key freecam toggle to `GameLoop` — switches between physics-driven
  player movement and the old fly camera. Transitions snap position cleanly in
  both directions so the view never jumps.
- Spawn position set to Y=70, above the tallest possible terrain surface (max
  surface is BASE_HEIGHT + HEIGHT_VARIATION = 64), so the player always falls
  onto terrain cleanly on first load.
- Physics mode: WASD controls horizontal movement, Space jumps (grounded only),
  gravity and collision handled by PhysicsBody.
- Camera follows player eye position (feet + 1.62 offset) every tick in physics
  mode.

### Decisions Made
- `PhysicsBody` is input-agnostic — it receives pre-processed velocity from the
  owning entity, not raw input. This makes it directly reusable for AI-driven
  mobs with no changes, aligning with the ECS architecture goal.
- Per-axis collision resolution (X → Y → Z separately) — standard voxel approach.
  Allows smooth wall-sliding instead of stopping dead at corners.
- `DELTA_TIME` is a fixed constant (1/60s) rather than a measured elapsed time —
  deterministic physics, no frame-rate dependent behaviour.
- `PhysicsBody` lives in `com.voxelgame.game` — it queries `World` for block
  solidity, which is game logic. No engine/game boundary violation.

### Problems Encountered
- None. Physics worked correctly on first run.

### AI Assistance Notes
- Claude wrote all files with full concept explanation of AABB per-axis collision
  resolution before the code was written.
- Architecture decision to separate PhysicsBody from Player flagged proactively
  for future mob reuse.

### Lessons / Observations
- Per-axis AABB resolution is elegant — the player slides along walls naturally
  without any special-case code for that behaviour.
- The gravity/onGround loop is stable: gravity pushes the player a tiny distance
  into the ground each tick, the Y resolver snaps them back, position is stable.

---

## Entry 012 — Air Control, Block Placement Guard, Sky Color
**Date:** 22.03.2026
**Phase:** 3 — Gameplay Basics

### What Was Done
- Reduced air control in `Player.update()` — when airborne, horizontal velocity
  lerps toward the desired direction at 10% per tick instead of snapping instantly.
  Momentum is preserved mid-air, movement feels physically believable.
- Added `PhysicsBody.overlapsBlock(bx, by, bz)` — AABB vs unit block overlap test.
  Reusable for any future entity collision query.
- Block placement guard in `GameLoop` — right-click checks `overlapsBlock` before
  calling `world.setBlock`. Placement is blocked if the target position intersects
  the player's AABB. Guard is skipped in freecam mode (freecam can place anywhere).
- Sky color changed from dark grey to light blue (`glClearColor(0.5, 0.7, 1.0, 1.0)`).

### Decisions Made
- Air control lives in `Player.update()` not `PhysicsBody` — it is a player
  movement policy, not a physics primitive. A future mob might want different
  air control behaviour without changing the physics layer.
- `overlapsBlock` lives on `PhysicsBody` — it depends only on AABB geometry,
  making it reusable for any entity that owns a body.
- Freecam bypasses the placement guard — consistent with its role as a
  debug/building tool with no physical presence in the world.

### Problems Encountered
- None.

### AI Assistance Notes
- Claude wrote all changes.

### Lessons / Observations
- The 0.1 lerp factor for air control produces a smooth, satisfying feel —
  responsive enough to steer jumps, sluggish enough to feel like real momentum.
- Phase 3 complete. The game loop now has full player physics, block interaction,
  HUD, and a visually coherent world.

### Phase 3 Summary
Phase 3 delivered: DDA raycasting, block breaking and placing, block highlight,
crosshair HUD, player physics (gravity, AABB collision, jumping), air control,
freecam toggle, cursor capture toggle, window resize support, and sky color.
The project is now a functional voxel sandbox. Phase 4 focuses on performance
and rendering quality: greedy meshing, frustum culling, neighbour-aware chunk
boundaries, and the window drag freeze.

---

## Entry 013 — Neighbor-Aware Meshing and Window Drag Fix
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization
 
### What Was Done
- Fixed `ChunkMesher` to be neighbor-aware — faces on chunk boundaries are no
  longer blindly emitted. A new `isAirAt()` helper checks within the chunk bounds
  for the fast path, and falls through to `world.getBlock()` for cross-boundary
  lookups. `ChunkMesher.mesh()` signature updated to accept `ChunkPos` and `World`.
- Updated `World.rebuildMesh()` to pass `pos` and `this` to the mesher — one line
  change.
- Fixed window drag freeze on Windows — registered a `GLFWWindowRefreshCallback`
  in `Window.java` that fires during the OS modal drag loop. `GameLoop` provides
  a render+swapBuffers runnable via `window.setRefreshCallback()`. The callback
  reference is stored as a field to prevent GC (same hazard as the framebuffer
  size callback from Entry 010).
 
### Decisions Made
- `isAirAt()` uses the chunk directly when the neighbor is in-bounds (fast array
  lookup, no HashMap) and falls back to `world.getBlock()` only at boundaries.
  The world returns `Block.AIR` for unloaded chunks, so faces at the loaded world
  edge are always emitted — the boundary is treated as open air, which is the
  correct visual result.
- The refresh callback calls `render()` only (not `update()`) — physics and game
  logic should not advance during a window drag. The screen stays alive visually
  but the game state is frozen, which is acceptable behavior.
- `GLFWWindowRefreshCallback` stored as a field on `Window` — consistent with the
  pattern established for `GLFWFramebufferSizeCallback` in Entry 010.
 
### Problems Encountered
- **Partial face culling (discovered after testing):** The mesher logic was correct
  but chunk meshes were built eagerly at addChunk time before neighboring chunks
  existed. The fix is recorded in Entry 014.
 
### AI Assistance Notes
- Claude wrote both fixes with concept explanations.
 
### Lessons / Observations
- Neighbor-aware meshing removes a correctness bug that was invisible from above
  but would have been visible when looking at chunk seams from below or at an angle.
- The `GLFWWindowRefreshCallback` pattern is the same Java/native GC hazard as
  the framebuffer callback — worth noting as a general rule: any LWJGL callback
  registered with GLFW must be kept alive in a field.
- Block break/place at chunk boundaries still does not rebuild the adjacent chunk's
  mesh — that requires a more involved fix (detect boundary touches in setBlock and
  queue neighbor rebuilds). Deferred — see Phase 4 Roadmap below.
 
---
 
## Phase 4 Roadmap — Future TODOs
*This section exists to preserve planning context between chat sessions.
A future Claude instance reading this should treat these as the agreed plan.*
 
### Remaining Phase 4 work (in priority order)
 
**1. Frustum culling** *(next up)*
Chunks outside the camera's view frustum are still submitted for rendering every
frame. JOML's `FrustumIntersection` class can test a chunk's axis-aligned bounding
box against the frustum. If the AABB is outside, skip the draw call entirely.
This is cheap to compute and essential once chunk streaming is in place.
Implementation: compute frustum from projectionMatrix * viewMatrix each frame in
`World.render()`, test each chunk's AABB before calling `mesh.render()`.
 
**2. Indexed rendering (EBO — Element Buffer Object)**
Currently each quad emits 6 vertices (two triangles), with 4 of them being
duplicates of the quad's 4 corners. An EBO lets you define 4 unique vertices and
index into them, reducing vertex data by ~33%. Requires updating `Mesh.java` to
accept both a vertex array and an index array, and uploading a GL_ELEMENT_ARRAY_BUFFER.
Draw call changes from `glDrawArrays` to `glDrawElements`. Do this before greedy
meshing since greedy meshing will produce quads anyway.
 
**3. Greedy meshing**
The signature voxel optimization. Instead of one quad per visible face, scan each
layer (per axis) and merge adjacent faces of the same block type and shade into a
single large quad. A flat grass surface of 16×16 blocks collapses from 256 quads
to 1 quad. The algorithm is a 2D rectangle merge per layer.
IMPORTANT: greedy meshing interacts with textures. If textures are added after
greedy meshing, UV tiling across merged quads must be handled (either via UV
scaling or texture arrays with per-face UVs). Design decision needed when textures
are approached — the mesher may need a second pass at that point.
Deferred neighbor chunk rebuild (block break/place at boundaries) should also be
done at this step, since greedy meshing makes boundary handling slightly more complex.
 
**4. Chunk streaming + background generation**
Replace the fixed 8×5×8 grid with dynamic load/unload based on player position.
Chunks within a configurable radius are loaded; chunks beyond it are unloaded and
their GPU resources freed. Terrain generation (CPU-heavy) runs on worker threads.
CRITICAL threading rule from CLAUDE.md: mesh generation (ChunkMesher.mesh()) can
run on a worker thread; new Mesh(vertices) (GPU upload) must happen on the main
thread. A concurrent queue or similar handoff point is needed between the two.
This is the most architecturally significant Phase 4 change.
 
**5. Ambient occlusion**
Bake per-vertex corner darkening into the mesh at build time — no runtime cost,
no separate light system. At each vertex, count how many of the surrounding corner
blocks are solid, and darken proportionally. Gives depth and contact shadow that
directional shading alone doesn't produce. Visual impact is large for very low
implementation cost. Does not require a light propagation system.
 
**6. Textures** *(deferred until after greedy meshing is stable)*
Adding textures requires: UV attribute in Mesh (layout location 2), texture atlas
image loaded via STB (already in LWJGL dependencies), sampler2D uniform in shader,
UV lookup table in ChunkMesher per block type per face. The texture atlas approach
(all block textures packed into one image, sampled by UV coordinates) avoids texture
binding overhead. Greedy meshing must be revisited when textures are added — merged
quads need tiling UVs.
 
### Deferred to Phase 5+
- Full Minecraft-style light propagation (block lights + skylight + dynamic updates)
  — large system, significant chunk update complexity. Ambient occlusion covers
  most of the visual benefit for now.
- LOD / VOXY-style distant rendering — different mesh representations at different
  distances. Phase 6+ territory.
- Caves and underground generation — TerrainGenerator currently only does height
  maps. 3D noise density functions needed for cave carving.
 
---

## Entry 014 — Neighbor Rebuild Sequencing Fix
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Fixed asymmetric chunk face culling from Entry 013. Root cause: chunks are added
  in ascending cx/cz/cy order. When chunk (cx,cy,cz) is meshed, its +x/+z/+y
  neighbors don't exist yet — world.getBlock() returns AIR, so boundary faces are
  emitted that should be hidden. The -x/-z/-y neighbors already exist so those
  faces ARE culled, matching the observed asymmetry exactly.
- Added rebuildNeighbors(ChunkPos) to World.java — called from addChunk after the
  new chunk's own mesh is built. Iterates all six face-adjacent positions and calls
  rebuildMesh for each. rebuildMesh is already a no-op for non-existent chunks.
- Also fixed the setBlock boundary bug from Entry 009: when a block is modified at
  local coordinate 0 or Chunk.SIZE-1, the face-adjacent neighbor's mesh is now
  also rebuilt.

### Decisions Made
- rebuildNeighbors is also the correct pattern for chunk streaming — when a chunk
  streams in, its neighbors' previously-exposed boundary faces must be re-evaluated.
  The same addChunk call handles both cases.
- A render thread was considered for the screen freeze issue but rejected — OpenGL
  is tied to the thread that created the context, and splitting GL context from GLFW
  event management conflicts with CLAUDE.md architecture rules and adds significant
  complexity for a cosmetic issue. The GLFWWindowRefreshCallback remains the fix.

### Problems Encountered
- None. Both fixes worked on first run.

### AI Assistance Notes
- Claude identified the root cause from a visual description of the asymmetry
  (x+/z+ unculled, x-/z- culled) and the known chunk load order.

### Lessons / Observations
- Eager meshing during chunk registration only works correctly when all neighbors
  already exist. In a streaming world, meshes must always be rebuilt after a
  neighbor is added or removed — not just the newly loaded chunk.
- The asymmetry was a direct consequence of loop order, not a logic error in the
  mesher itself.

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