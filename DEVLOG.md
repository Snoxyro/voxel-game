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

## Entry 015 — Frustum Culling and Indexed Rendering (EBO)
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Added frustum culling to `World.render()` — now accepts projectionMatrix and
  viewMatrix, combines them into a clip-space matrix, and uses JOML's
  FrustumIntersection to test each chunk's AABB before issuing any draw calls.
  Chunks fully outside the frustum are skipped entirely.
- `World.render()` now returns int[2] — [visible chunk count, total mesh count].
- GameLoop diagnostic line updated to show "Chunks: X/Y" — confirmed working:
  looking at sky reports 0, full world view reports max.
- Rewrote `Mesh.java` to use an EBO (Element Buffer Object). ChunkMesher output
  is unchanged — Mesh internally converts the 6-vertex-per-quad input into 4
  unique vertices + 6 indices before GPU upload, reducing vertex data by ~33%.
  Draw call changed from glDrawArrays to glDrawElements with GL_UNSIGNED_INT indices.

### Decisions Made
- GL_UNSIGNED_INT chosen for index type — supports ~4 billion unique vertices per
  mesh, far beyond any practical chunk size. Other bottlenecks would be hit first.
- EBO is unbound correctly: GL_ELEMENT_ARRAY_BUFFER must NOT be unbound while the
  VAO is active — doing so removes the EBO association from the VAO silently.
- BlockHighlightRenderer uses GL_LINES with its own VAO and is unaffected by the
  Mesh EBO change.

### Problems Encountered
- None. Both changes worked on first run.

### AI Assistance Notes
- Claude wrote both changes.

### Lessons / Observations
- Frustum culling impact is immediately observable via the chunk counter — looking
  straight up drops visible chunks to 0.
- EBO correctness is not visually verifiable — identical output to before is the
  confirmation. The critical gotcha: unbinding GL_ELEMENT_ARRAY_BUFFER while a VAO
  is bound silently breaks the association. This is a well-known OpenGL trap.

---

## Entry 016 — Greedy Meshing
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Rewrote ChunkMesher.java to use greedy meshing. One mask array (int[SIZE][SIZE])
  is reused across all six face directions and all layers. For each layer, the mask
  records which cells have a visible face and what block type. buildMergedQuads()
  runs the greedy merge — extends right while type matches, then down while the
  full row width matches — and returns merged rectangles as [i, j, w, h, type].
  Each face direction method maps the 2D scan axes back to 3D world coordinates
  with the correct CCW winding for that direction.
- A flat 16×16 surface now produces 1 quad instead of 256. Terrain reduction
  varies by surface complexity.
- No changes to Mesh.java, World.java, or any other file.

### Decisions Made
- Six separate face-direction methods rather than a generic loop — keeps the
  winding order logic explicit and readable per direction.
- buildMergedQuads() is extracted as a shared helper — the algorithm lives in
  one place and is called once per layer per direction.
- Output format is unchanged: same interleaved float array, same emitQuad calls.
  Mesh.java's EBO conversion handles the rest transparently.
- block.ordinal() + 1 used as mask value — 0 reserved for "no face", ordinals
  start at 0 so +1 avoids collision with AIR.

### Problems Encountered
- None. Geometry matches previous output exactly on first run.

### AI Assistance Notes
- Claude wrote the implementation with winding order verified analytically for
  all six face directions against the original per-block quad patterns.

### Lessons / Observations
- The greedy merge is satisfying: a single horizontal scan handles rectangles
  of arbitrary size cleanly because consumed cells are zeroed immediately.
- Winding order is the main hazard — the 2D scan axes (i, j) map to different
  world axes for each face direction, and getting the CCW order wrong produces
  invisible faces (back-face culled) with no error.
- Textures will require revisiting this mesher — merged quads spanning multiple
  blocks need tiled UVs, which vertex colors don't require.

---

## Entry 017 — Chunk Streaming
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Replaced fixed 8×5×8 chunk grid with dynamic streaming. World.update(px, py, pz)
  is called every tick and drives load/unload based on viewer position.
- Terrain generation runs on a dedicated daemon background thread. The main thread
  drains a ConcurrentLinkedQueue each tick and handles GPU upload and neighbor rebuilds.
- Chunks are generated in distance order (closest first) for better pop-in experience.
- Horizontal load area is circular (not square) to avoid generating unnecessary corner chunks.
- Chunks below cy=0 are skipped — terrain never exists below world y=0.
- World constructor now accepts a seed — TerrainGenerator ownership moved from
  GameLoop into World (server-side concern).
- RENDER_DISTANCE_H = 16 chunks, RENDER_DISTANCE_V = 4 chunks. Both are named
  constants at the top of World.java.

### Decisions Made
- Worker thread does terrain generation only — no GL calls, no world queries.
  Meshing and GPU upload remain on the main thread. This is safe with no
  synchronization beyond the ConcurrentLinkedQueue handoff.
- world.update() accepts a single viewer position for now. When multiplayer is
  added, this becomes a Collection<Vector3f> — one position per connected player.
  World is server-side logic; GameLoop will become the client-side rendering loop.
  The seam is the world.update() call in GameLoop.
- Single executor thread now, thread pool later. The upgrade path is changing
  newSingleThreadExecutor to newFixedThreadPool(N) — no other changes needed,
  since ChunkMesher.mesh() is not called on the worker thread (it reads from World
  which isn't thread-safe yet).

### Problems Encountered
- None on first run.

### AI Assistance Notes
- Claude wrote World.java. GameLoop changes were surgical (3 lines).

### Lessons / Observations
- Sorting generation tasks by distance before submission gives noticeably better
  pop-in behavior — ground chunks appear before distant terrain.
- Chunks that go out of range while being generated are discarded on drain rather
  than uploaded — avoids a ghost chunk appearing briefly before the next unload pass.
- rebuildNeighbors() on unload is necessary — without it, neighbors keep their
  old meshes which suppressed boundary faces that are now exposed.

---

## Entry 018 — Streaming Fixes
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Capped chunk uploads to MAX_CHUNK_UPLOADS_PER_FRAME = 2 per tick in
  drainPendingChunks(). Eliminates frame hitches caused by multiple simultaneous
  mesh rebuilds when many chunks finish generating at once.
- Raised RENDER_DISTANCE_V from 4 to 6 chunks (96 blocks). The previous value
  caused terrain to unload when flying ~70 blocks above spawn.

### Decisions Made
- Upload cap is a constant — tunable if 2 feels too slow to load in.
- The real fix for hitches is moving ChunkMesher.mesh() to the worker thread,
  which requires thread-safe world reads. Deferred to the thread pool upgrade.

### Problems Encountered
- Vertical unload was reachable in normal freecam usage — not an edge case.

---

## Entry 019 — Meshing Moved to Worker Thread
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Moved ChunkMesher.mesh() from the main thread to the worker thread.
  Worker now does: generateChunk() + mesh() → float[] vertices.
  Main thread now only does: new Mesh(vertices) + rebuildNeighbors().
- Thread safety achieved without locks: at submission time the main thread
  captures a snapshot of the 6 face-adjacent neighbor chunks into a HashMap.
  The worker receives this snapshot and never touches the live chunks map.
- ChunkMesher signature changed from mesh(Chunk, ChunkPos, World) to
  mesh(Chunk, ChunkPos, Map<ChunkPos, Chunk>). isAirAt() now queries the
  neighbor snapshot instead of World directly.
- World.rebuildMesh() (main thread, setBlock and neighbor rebuilds) builds
  its own neighbor snapshot via captureNeighbors() before calling the mesher.
- PendingChunk record updated to carry (pos, chunk, vertices) — worker
  produces both the block data and the pre-built vertex array.
- MAX_UPLOADS_PER_FRAME = 4 retained — each upload now also triggers up to
  6 neighbor rebuilds on the main thread, so capping is still useful.

### Decisions Made
- Snapshot approach preferred over locks or ConcurrentHashMap — cleaner,
  no contention, and naturally extends to the thread pool case. Each worker
  task is fully self-contained with zero shared state access.
- Upgrade path to thread pool: replace newSingleThreadExecutor with
  newFixedThreadPool(N). No other changes needed — architecture already supports it.
- Slightly stale neighbor snapshots are acceptable: if a block is placed
  between submission and completion, setBlock's rebuildMesh call corrects
  the boundary immediately after.

### Problems Encountered
- Geometry identical to before on first run. However some stutters still persist.

### AI Assistance Notes
- Claude designed and wrote both files.

### Lessons / Observations
- The key insight: thread safety doesn't require synchronization if you
  eliminate sharing. Capturing an immutable snapshot before submission means
  the worker has everything it needs with no coordination overhead.
- rebuildNeighbors on the main thread is still the limiting factor for
  very fast movement — deferred until it proves to be a measurable problem.

---

## Entry 020 — Async Neighbor Remesh Path
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Identified root cause of remaining stutters: rebuildNeighbors() was still
  calling ChunkMesher.mesh() synchronously on the main thread. With 4 uploads
  per frame × 6 neighbors each = up to 24 synchronous mesh builds per frame.
- Added dirty mesh set and async remesh path. When a chunk loads or unloads,
  its neighbors are added to dirtyMeshes instead of being rebuilt immediately.
  processDirtyMeshes() submits them to the worker; results land in
  pendingRemeshes and are drained separately (MAX_REMESHES_PER_FRAME = 8).
- setBlock retains synchronous mesh rebuild — player-triggered, infrequent,
  affects at most 7 chunks.
- Main thread now only calls new Mesh() (GPU buffer allocation) — no
  ChunkMesher.mesh() calls outside of setBlock.

### Decisions Made
- Separate caps for new chunks (4) and remeshes (8) — prevents remeshes from
  starving new chunk uploads and vice versa.
- remeshInProgress set prevents the same position being submitted to the worker
  multiple times while a result is already in flight.
- Stale remesh results (chunk unloaded before result arrives) are discarded
  on drain via the chunks.containsKey() check.
- Known limitation: boundary faces are briefly incorrect for a few frames
  after a chunk loads, until the async neighbor remesh completes. Visually
  a fraction of a second at 120fps.

### Lessons / Observations
- The pattern "move CPU work off the main thread, main thread only does GPU
  calls" is the correct architecture for any streaming voxel engine.
- Each optimization revealed the next bottleneck — generation → meshing →
  neighbor rebuilds. This is the last synchronous mesh build in the hot path.

---

## Entry 021 — Terrain Generation and Meshing Optimizations
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Added chunk-level early exits to TerrainGenerator. Before sampling any
  noise, the chunk's Y range is compared against [MIN_SURFACE_Y, MAX_SURFACE_Y].
  Chunks entirely above the max surface return immediately as all-air.
  Chunks entirely below the min surface return immediately as all-stone.
  Only chunks intersecting the surface band require per-column noise evaluation.
- Replaced List<Float> with a manually grown float[] in ChunkMesher.
  List<Float> boxes every float into a heap-allocated Float object — for a
  busy chunk this is tens of thousands of allocations per mesh build.
  The float[] buffer starts at a reasonable capacity and doubles when full,
  same growth strategy as ArrayList but with no boxing overhead.
- buildMergedQuads() now returns a flat int[] (5 ints per quad) instead of
  List<int[]>, eliminating int[] object allocations per quad.

### Decisions Made
- Early exits are the more impactful of the two changes — at render distance
  16 with 12 vertical levels, the majority of chunks are pure air or pure
  stone and now cost near-zero generation time.
- float[] buffer initial capacity set at 1024 quads × 36 floats = 36864.
  Covers most surface chunks without reallocation; doubles automatically
  for worst-case dense chunks.

### Lessons / Observations
- The most expensive thing in any system is work you never needed to do.
  Chunk-level early exits eliminate noise sampling for ~70% of chunks.
- Autoboxing in hot paths is a common Java performance hazard — List<Float>
  is convenient but inappropriate for tight mesh-building loops.

---

## Entry 021 — Thread Pool Upgrade
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Replaced newSingleThreadExecutor with newFixedThreadPool(availableProcessors - 1).
  Leaves one core for the main/render thread, uses all remaining cores for chunk
  generation and meshing. On a quad-core with hyperthreading this is 7 concurrent
  workers instead of 1.

### Decisions Made
- availableProcessors - 1 is the standard formula for CPU-bound worker pools.
  Reserving one core prevents the worker threads from starving the render loop.
- No other code changes were needed — the neighbor snapshot architecture already
  made each task fully self-contained with zero shared state.

### Lessons / Observations
- The single-threaded executor was always the bottleneck for initial load.
  The snapshot-based design paid off here — parallelism came for free.

---

## Phase 4 Roadmap — Remaining Work

### Completed
- Neighbor-aware chunk meshing (correctness fix)
- Window drag freeze fix (GLFWWindowRefreshCallback)
- Neighbor rebuild sequencing fix (rebuildNeighbors on addChunk)
- Frustum culling with chunk counter
- Indexed rendering (EBO)
- Greedy meshing
- Chunk streaming with background generation (render distance 16)
- Meshing moved to worker thread (neighbor snapshot for thread safety)
- Async neighbor remesh path (all ChunkMesher calls off main thread)
- Thread pool (availableProcessors - 1 workers)
- Terrain early exits for air/stone chunks
- Float[] buffer in ChunkMesher (no boxing)

### Remaining (in priority order)

**1. Ambient occlusion** *(next up)*
Bake per-vertex corner darkening into vertex colors at mesh-build time.
At each vertex, count how many of the surrounding corner blocks are solid
and darken proportionally. Large visual impact, zero runtime cost, no separate
light system required. Integrates into ChunkMesher — runs on worker thread
naturally. Needs to happen before textures since AO affects vertex data layout.

**2. Textures**
UV attribute in Mesh (layout location 2), texture atlas loaded via STB,
sampler2D uniform in shader, UV lookup per block type per face in ChunkMesher.
Greedy meshing must be revisited — merged quads need tiling UVs.
IMPORTANT: partial block types (stairs, slabs) will break greedy merging and
face occlusion culling. When partial blocks are introduced, Block needs an
occludesFace() method and the mesher needs a fallback per-block path for
non-full blocks.

### Deferred to Phase 5+
- Full light propagation (block lights + skylight + dynamic updates)
- LOD / distant rendering
- Caves (3D noise density functions)
- Multiplayer: World.update() → update(Collection<Vector3f>), World moves
  to Server class, GameLoop becomes client-side rendering loop

### Performance note
Generation speed on Ryzen 3550H is hardware-limited at this render distance.
The architecture is correct — all CPU work is off the main thread, tasks are
parallel, and unnecessary work is skipped via early exits. Further gains would
require either reducing render distance or optimizing the noise function itself
(e.g. pre-computing height maps per chunk column rather than per block).

---

## Entry 022 — Flat Byte Array Block Storage
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Replaced `Block[][][]` in `Chunk.java` with a flat `byte[]` storing block ordinals.
  Public API (`getBlock`, `setBlock`, `isAir`) is unchanged — purely internal.
- Index formula: `x * SIZE * SIZE + y * SIZE + z` (Y innermost stride, aligns with
  how TerrainGenerator fills columns and how ChunkMesher iterates).
- `isAir()` now compares a byte to 0 directly — skips the `BLOCK_VALUES` lookup
  entirely in the mesher's hot path.
- Added `BLOCK_VALUES` static cache to avoid repeated synthetic `Block.values()` calls.
- RAM usage dropped from ~2200MB to ~1500MB — 4× smaller per-chunk storage,
  no scattered enum reference objects.

### Decisions Made
- `& 0xFF` mask in `getBlock` makes ordinal lookup future-proof against >127 block types.
- No changes required outside `Chunk.java` — all callers use the same public API.

### Lessons / Observations
- `Block[][][]` required 3 pointer dereferences per block access and scattered 4096
  heap objects per chunk. A flat `byte[]` is one contiguous 4KB allocation — the
  entire chunk fits in CPU L1/L2 cache during a mesh build.

---

## Entry 023 — Heightmap Column Cache
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Added a `ConcurrentHashMap<Long, int[]>` heightmap cache to `TerrainGenerator`.
  All Y chunks in the same column share one 256-value heightmap computed once.
- Without the cache, every Y chunk independently evaluated all 256 fBm columns —
  up to 12× redundant noise work per column at full render distance.
- Key is a packed long `(cx << 32 | cz & 0xFFFFFFFFL)` — avoids key object allocation
  per lookup.
- `computeIfAbsent` is atomic — thread-safe for concurrent workers on the same column
  with no explicit locks.
- Added `evictColumn(cx, cz)` called from `World.unloadDistantChunks` when the last
  Y chunk in a column is removed — prevents unbounded cache growth as the player moves.

### Decisions Made
- `evictHeightmapIfColumnUnloaded` on `World` checks if any sibling Y chunk is still
  loaded before evicting. Only the final unload triggers eviction.

### Lessons / Observations
- Noise evaluation is the most expensive part of generation. Eliminating redundant
  evaluations across Y slices of the same column is more impactful than any micro-
  optimization inside the noise function itself.

---

## Entry 024 — Chunk Occupancy Tracking and Mesher Y Clamping
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Added `solidCount`, `minOccupiedY`, `maxOccupiedY` to `Chunk`. Updated `setBlock`
  to maintain these on every block change at zero extra cost.
- Added `isAllAir()` — the mesher fast-paths entirely empty chunks (all-air) and
  returns an empty array without entering any of the six face passes.
- All six face passes in `ChunkMesher` now clamp their Y layer loops to
  `[minOccupiedY, maxOccupiedY]`, skipping guaranteed-empty layers above and below
  actual content. For chunks at the top of the surface band this can skip 10+ layers
  per direction pass.
- Pre-computed per-octave arrays (`octaveSeeds`, `octaveAmplitudes`, `octaveFrequencies`,
  `maxAmplitude`) in `TerrainGenerator` constructor. `fbm()` reads arrays instead of
  recalculating seeds and scalar parameters on every call.

### Decisions Made
- `minOccupiedY` / `maxOccupiedY` only expand on block placement, never shrink on
  removal. Shrinking would require a full O(N) scan. The mesher scans a few extra
  empty layers at worst after a block is broken at a boundary — always correct,
  never wrong.
- Sentinels when empty: `minOccupiedY = SIZE`, `maxOccupiedY = -1`. Only read when
  `isAllAir()` is false.

### Bugs Fixed
- **Mask contamination bug:** The four side face passes (North/South/East/West) reuse
  the mask array across layers. After Y clamping was introduced, rows outside
  `[minY, maxY]` were never written, leaving stale values from previous layers.
  `buildMergedQuads` read those stale values and emitted phantom quads. Fixed by
  explicitly `Arrays.fill(mask[y], 0)` for rows outside the occupied band before
  calling `buildMergedQuads`.

### Lessons / Observations
- Reusing an array across loop iterations is a performance pattern but requires every
  cell to be written on every iteration. Partial writes combined with partial reads
  from a previous iteration produce silent data corruption that only manifests visually
  as phantom geometry — hard to attribute to the right cause without careful reading.

---

## Entry 025 — Schedule Guard and Upload Cap Increase
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Added `lastScheduledCX/Y/Z` fields to `World`. `scheduleNeededChunks` is now
  skipped entirely when the viewer's center chunk hasn't changed since the last call.
  Avoids scanning ~14,000 positions per tick (33×13×33 region, two HashMap lookups
  each) when standing still or moving within the same chunk.
- Schedule re-runs immediately when the center chunk changes, so new columns are
  picked up without delay.

### Decisions Made
- Guard is on center chunk position, not world position — world position changes
  every tick even when standing still. Chunk position only changes when crossing a
  16-block boundary, which is the only time the schedule scan produces new results.

---

## Entry 026 — Async Remesh Pipeline Bug Fix
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Fixed two related bugs in the async meshing pipeline that caused permanent phantom
  faces at chunk boundaries, most visible when underground or at chunk load edges.

**Bug 1 — New chunks never remeshed themselves after loading:**
When a chunk was submitted for generation, a neighbor snapshot was captured at
submission time. By the time it drained from `pendingChunks`, more neighbors may
have loaded. The chunk's initial mesh had faces toward now-solid neighbors.
`markNeighborsDirty` correctly triggered neighbor remeshes but never added the
chunk itself to `dirtyMeshes`. Those phantom faces persisted permanently.
Fix: `drainPendingChunks` now adds the newly loaded chunk's own position to
`dirtyMeshes` after upload, triggering a self-remesh with the current neighbor state.

**Bug 2 — Dirty marks lost while a remesh was in-flight:**
In `processDirtyMeshes`, `it.remove()` ran unconditionally before the
`remeshInProgress` check. If a chunk was already being remeshed when a new neighbor
loaded and re-dirtied it, that dirty mark was consumed and discarded. The in-flight
remesh completed with a stale snapshot. Nobody ever triggered another remesh.
Fix: Restructured `processDirtyMeshes` so in-flight positions are skipped (not
removed) from `dirtyMeshes`. They are picked up and resubmitted on the next tick
once the in-flight result has drained and cleared `remeshInProgress`.

### Lessons / Observations
- The directional pattern of the bug (only faces behind the moving player persisted)
  was the diagnostic clue — those were chunks whose neighbors all finished loading
  after they had already been meshed with a stale snapshot.
- Screenshots are unreliable for diagnosing logic bugs in voxel rendering. Manual
  behavioural descriptions of what is and is not visible from where, and in what
  direction, are far more diagnostic than visual captures.
- When debugging async pipelines, trace the full lifecycle of one data item (a single
  chunk position) through every queue and set it touches. The bug lives wherever the
  item falls out of the pipeline prematurely.

---

## Phase 4 Roadmap — Remaining Work

### Completed
- Neighbor-aware chunk meshing (correctness fix)
- Window drag freeze fix
- Neighbor rebuild sequencing fix
- Frustum culling with chunk counter
- Indexed rendering (EBO)
- Greedy meshing
- Chunk streaming with background generation
- Meshing moved to worker thread (neighbor snapshot)
- Async neighbor remesh path
- Thread pool (availableProcessors - 1 workers)
- Terrain early exits for air/stone chunks
- float[] buffer in ChunkMesher (no boxing)
- Flat byte[] block storage in Chunk (~700MB RAM reduction)
- Heightmap column cache (up to 12× noise reduction per column)
- isAllAir() fast-path + Y occupancy range clamping in mesher
- Mask contamination fix (phantom faces from Y clamping)
- Pre-computed fBm octave arrays
- Schedule guard (skip 14k position scan when center chunk unchanged)
- Async remesh pipeline fix (self-remesh + dirty mark loss)

### Remaining (in priority order)

**1. Generation speed — direction bias + submission cap** *(next up)*
Chunks ahead of the player in the movement direction should be prioritised over
equidistant chunks behind. Current sort is distance-only. Weight by dot product with
velocity direction so forward chunks jump the queue. Also cap batch submission to
~32 tasks per schedule run so the executor queue stays shallow and new priority chunks
aren't buried behind hundreds of already-queued distant tasks. Raise
MAX_UPLOADS_PER_FRAME from 4 to 16 — uploads are now cheap (vertices pre-built on
worker thread).

**2. Ambient occlusion** *(after generation speed)*
Bake per-vertex corner darkening into vertex colors at mesh-build time. Large visual
impact, zero runtime cost, no separate light system. Integrates into ChunkMesher,
runs on worker thread naturally.

**3. Textures**
UV attribute, texture atlas via STB, sampler2D uniform, per-block UV lookup in mesher.
Greedy meshing needs revisiting for tiled UVs across merged quads.

### Deferred to Phase 5+
- Full light propagation
- LOD / distant rendering
- Caves (3D noise density functions)
- Multiplayer: `World.update()` → `update(Collection<Vector3f>)`

---

## Entry 027 — Direction-Biased Generation Queue
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Replaced direct executor submission in `scheduleNeededChunks` with a main-thread
  `generationQueue` list. Tasks are no longer submitted directly — they sit in this
  list and are drip-fed to the executor each tick via a new `tickGenerationQueue()`.
- `tickGenerationQueue()` re-sorts and submits every tick, so a camera turn takes
  effect within one tick rather than waiting for a backlogged executor queue to drain.
- Added look direction as input to `World.update()` — camera yaw/pitch direction
  computed in `GameLoop` and passed in each tick. More reliable than deriving
  direction from position delta (which dies when standing still).
- Submission budget split 75/25 per tick: 75% goes to direction-biased chunks
  (forward chunks prioritized), 25% goes to closest chunks by pure distance
  regardless of look direction. Guarantees all chunks eventually load even if the
  player never looks at them — prevents far chunks from starving indefinitely.
- Extracted `submitToExecutor(pos)` helper to avoid duplicating snapshot+submit
  logic between the two passes.
- `MAX_CHUNKS_PER_TICK = 16` — tunable constant, higher values load faster at
  the cost of occasional frame budget pressure during fast movement.
- Raised `MAX_UPLOADS_PER_FRAME` from 4 to 16 — uploads are cheap now that
  vertices are pre-built on the worker thread.

### Decisions Made
- Main-thread priority queue is the correct architecture for any system where
  priority changes faster than tasks complete. Sorting a list each tick is cheap;
  draining a backlogged executor queue is not.
- Look direction preferred over velocity direction — always available, directly
  represents player intent, works when standing still.
- 75/25 split is a tunable policy decision. 100% biased starves background chunks.
  100% distance loses all directional benefit. 75/25 balances both well in practice.

### Problems Encountered
- Initial implementation submitted all tasks directly to the executor on schedule.
  Camera turns had no effect on already-queued tasks — executor queue locked in the
  old priority order. Fixed by the main-thread queue approach.
- Bias factor of 0.5 was too aggressive — chunks directly behind the player were
  penalized enough to never load until the player turned. Fixed by the 25% distance
  pass which guarantees background progress.

### AI Assistance Notes
- Claude wrote all changes. First implementation had the executor queue problem
  above — required a second pass to redesign around the main-thread queue.

### Lessons / Observations
- Sorting before submitting only helps if the executor queue is shallow. A deep
  executor queue locks in old priorities. The fix is to keep the queue shallow by
  controlling submission rate from the main thread.
- Manual behaviour descriptions are far more reliable than screenshots for diagnosing
  ordering and priority bugs in async systems.

---

## Entry 028 — TerrainGenerator Seed Field Cleanup
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done
- Removed unused `private final long seed` field from `TerrainGenerator`.
  The seed value is fully consumed during constructor execution to populate
  `octaveSeeds[]` — storing it as a field served no purpose after that.
- Constructor parameter `seed` retained — still needed to compute per-octave seeds.

### Lessons / Observations
- Pre-computing into arrays at construction time eliminates the need to retain
  input parameters as fields. Fields that exist only to silence a compiler warning
  are a code smell worth removing immediately.

---

## Phase 4 Roadmap — Remaining Work

### Completed
- Neighbor-aware chunk meshing (correctness fix)
- Window drag freeze fix
- Neighbor rebuild sequencing fix
- Frustum culling with chunk counter
- Indexed rendering (EBO)
- Greedy meshing
- Chunk streaming with background generation
- Meshing moved to worker thread (neighbor snapshot)
- Async neighbor remesh path
- Thread pool (availableProcessors - 1 workers)
- Terrain early exits for air/stone chunks
- float[] buffer in ChunkMesher (no boxing)
- Flat byte[] block storage in Chunk (~700MB RAM reduction)
- Heightmap column cache (up to 12× noise reduction per column)
- isAllAir() fast-path + Y occupancy range clamping in mesher
- Mask contamination fix (phantom faces from Y clamping)
- Pre-computed fBm octave arrays
- Schedule guard (skip 14k position scan when center chunk unchanged)
- Async remesh pipeline fix (self-remesh + dirty mark loss)
- Direction-biased generation queue with 75/25 split budget
- MAX_UPLOADS_PER_FRAME raised to 16

### Remaining (in priority order)

**1. Ambient occlusion** *(next up — continue in new session)*
Bake per-vertex corner darkening into vertex colors at mesh-build time. Large visual
impact, zero runtime cost, no separate light system. Integrates into ChunkMesher,
runs on worker thread naturally.
IMPORTANT: AO values must be included in the greedy merge condition —
two faces can only merge if block type AND all four vertex AO values match.
Otherwise darkening interpolates incorrectly across merged quads.
Research confirmed two additional details worth implementing:
- The standard 3-sample AO formula uses side1, side2, and corner to compute
  each vertex value. When side1 AND side2 are both solid, corner is irrelevant
  (the vertex is fully occluded regardless).
- Quads where opposite corners have unequal AO sums should flip their diagonal
  split to avoid a visible anisotropy artifact on flat surfaces. This is the
  single most important quality detail in voxel AO.

**2. Textures**
UV attribute, texture atlas via STB, sampler2D uniform, per-block UV lookup in
mesher. Greedy meshing needs revisiting for tiled UVs across merged quads.
IMPORTANT: partial block types (stairs, slabs) will break greedy merging and
face occlusion culling when introduced. Block needs an occludesFace() method
and the mesher needs a fallback per-block path for non-full blocks.

### Deferred to Phase 5+
- Full light propagation (block lights + skylight + dynamic updates)
- LOD / distant rendering
- Caves (3D noise density functions)
- Multiplayer: `World.update()` → `update(Collection<Vector3f>)`

---

## Entry 029 — Ambient Occlusion
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization
### What Was Done

- Rewrote `ChunkMesher.java` with per-vertex ambient occlusion baked into vertex colors at mesh-build time. Zero runtime cost — all darkening happens on the worker thread during mesh generation.
- Expanded `captureNeighbors()` in `World.java` from 6 face-adjacent chunks to all 26 neighbors (face + edge + corner). Required because AO sampling at chunk-boundary vertices needs diagonal neighbor chunks.
- AO uses the canonical 3-sample formula per vertex: side1, side2, corner. When both sides are solid, corner is irrelevant (fully occluded). Values range 0–3.
- Mask encoding extended from 3 bits (block type only) to 11 bits (block type + 4×2-bit AO values). Greedy merge condition now implicitly enforces AO matching — two cells only merge when block type AND all four vertex AO values are identical.
- Diagonal flip implemented in `emitQuad`: when `ao0 + ao2 < ao1 + ao3`, the triangle split is flipped to prevent interpolation anisotropy artifacts. Condition is inverted relative to the 0fps.net reference formula because our vertex winding is CCW, not CW.
- Per-direction shading split into six constants (TOP, BOTTOM, NORTH, SOUTH, EAST, WEST) simulating a sun angle from the south-east, replacing the single SHADE_SIDE value.
- Two separate AO strength constants: `AO_STRENGTH_TOP = 0.75f`, `AO_STRENGTH_SIDE = 0.60f`

### Decisions Made

- Top-face AO is nearly disabled. On 1-block-high staircase terrain, top-face AO produces dark stripes perpendicular to the slope direction — an artifact of correct AO values on repeating 1-block geometry. Side-face AO is unaffected and looks correct.
- Six directional shade values replace one SHADE_SIDE constant. Gives terrain large-scale natural depth that doesn't produce per-block boundary artifacts.
- `AO_STRENGTH_TOP` and `AO_STRENGTH_SIDE` are separate tunable constants at the top of `ChunkMesher` — easy to adjust when textures arrive.

### Problems Encountered

- Initial `emitQuad` flipped branch emitted vertices in an order incompatible with `Mesh.java`'s fixed deduplication logic (extracts unique verts from positions 0,1,2,5). Fixed by emitting `[v1,v2,v3,v1,v3,v0]` for the flipped case instead of `[v0,v1,v3,v1,v2,v3]`.
- Diagonal flip condition was inverted relative to the 0fps.net reference. Root cause: reference formula assumes CW vertex ordering; our winding is CCW. Inverting `>` to `<` corrected shadow orientation.

### AI Assistance Notes

- Claude implemented the full AO system, `captureNeighbors` expansion, and directional shading.
- Mesh.java compatibility bug in the flipped branch caught and fixed by Claude from a screenshot.
- Diagonal flip condition was wrong (shadow stripes perpendicular to slope). Gemini identified the fix — invert the operator. Claude had incorrectly concluded the stripes were unsolvable, which was wrong.

### Lessons / Observations

- The CCW vs CW winding difference is exactly the kind of subtle assumption mismatch that causes correct-looking code to produce wrong results. Reference formulas should always be checked for coordinate system and winding assumptions.
- Vertex AO on flat-color terrain exposes interpolation artifacts that textures will naturally hide. The current result is a correct baseline that will look significantly better once textures are added.
- Two AI tools gave contradictory answers on the same problem. The correct answer came from trying the simpler fix first.

---

## Entry 030 — Textures with GL_TEXTURE_2D_ARRAY
**Date:** 22.03.2026
**Phase:** 4 — Performance Optimization

### What Was Done

- Added `TextureManager.java` — generates a `GL_TEXTURE_2D_ARRAY` with one 16×16 RGBA tile per block face type (grass top, grass side, dirt, stone). Textures are generated procedurally at startup using deterministic integer hashing — reproducible every run, no external files required.
- Updated `default.vert` — added `texCoord` (vec2, location 2) and `texLayer` (float, location 3) attributes. Fragment shader receives them packed as `vec3 fragTexCoord`.
- Updated `default.frag` — samples `sampler2DArray` and multiplies by `vertColor` for AO/directional shading. Added `useTexture` bool uniform so the block highlight wireframe can skip texture sampling without a separate shader.
- Updated `Mesh.java` — vertex format expanded from 6 to 9 floats per vertex (`[x,y,z,r,g,b,u,v,layer]`). Added attribute pointers for locations 2 and 3.
- Updated `Block.java` — added `topTextureLayer()`, `sideTextureLayer()`, `bottomTextureLayer()` methods referencing `TextureManager` layer constants.
- Updated `ShaderProgram.java` — added `setUniform(String, int)` and `setUniform(String, boolean)` overloads.
- Updated `ChunkMesher.java` — emits UV coordinates in tile units (`0→w`, `0→h`) and texture layer index per vertex. All six face passes use the correct layer selector per face direction. Base color changed from `block.color()` to white `(1,1,1)` — textures provide color, vertex color carries only brightness.
- Updated `GameLoop.java` — wires `TextureManager` into init, render, and cleanup.

### Decisions Made

- `GL_TEXTURE_2D_ARRAY` over a traditional atlas. With an atlas, `GL_REPEAT` only wraps at the full texture boundary — tiling across greedy-merged quads requires UV sub-region math. With a texture array each tile occupies its own layer with full `[0,1]` UV space, so `GL_REPEAT` wraps correctly and merged quads tile for free.
- UV coordinates in tile units rather than normalized. A greedy-merged `w×h` quad gets UVs `0→w` and `0→h`. `GL_REPEAT` handles the tiling — no `fract()` in the shader.
- `GL_NEAREST` / `GL_NEAREST_MIPMAP_NEAREST` — preserves the pixelated voxel aesthetic at all distances, mipmaps prevent shimmer at distance.
- `useTexture` bool uniform on the existing shader rather than a separate wireframe shader — keeps the renderer simple.
- `Block.color()` is now dead code. Removed entirely.

### Problems Encountered

- OpenGL stores textures bottom-up. The grass side green strip was placed at `y < 3` (bottom of buffer = bottom of rendered face), which rendered it at the bottom instead of the top. Fixed by moving the condition to `y >= TILE_SIZE - 3`.
- `ChunkMesher` was still passing `block.color()` as the base vertex color. The fragment shader multiplies texture sample by vertex color, so the green tint from `color()` was darkening and tinting the grass texture. Fixed by using white `(1,1,1)` as the base color in all face passes.

### AI Assistance Notes

- Claude wrote all new/updated files. Copilot (GPT 5.3 Codex) handled the mechanical ChunkMesher UV emission changes and GameLoop wiring.
- The bottom-up texture flip was a known OpenGL convention that should have been accounted for upfront.
- The `block.color()` tinting bug was caught from a screenshot after first run.

### Lessons / Observations

- The `GL_TEXTURE_2D_ARRAY` approach pays off immediately with greedy meshing — tiling just works without any extra math in either the mesher or the shader.
- Procedurally generated placeholder textures are underrated for early development. No file management, always reproducible, trivial to swap for real art later.

---
 
## Phase 5 — Multiplayer Architecture Plan
**Date:** 22.03.2026 (Planning session)
**Phase:** 5 — Multiplayer
 
### Design Goals
- Singleplayer = embedded server on localhost. No separate singleplayer code path.
- Server is headless and portable: zip the world folder, upload to any machine, run `ServerMain`. Connect from client with IP:port.
- Movement is smooth. Client-side prediction with server reconciliation is the target. Deferred to Phase 5D — simpler sync first.
- Server is authoritative. Client handles rendering only.
 
### Architectural Decisions (Locked)
 
**Package restructure:**
```
com.voxelgame.
├── Main.java                        ← singleplayer: launches embedded server + client
├── common/
│   ├── world/
│   │   ├── Block.java               ← moved from game/
│   │   ├── Chunk.java               ← moved from game/
│   │   ├── ChunkPos.java            ← moved from game/
│   │   └── PhysicsBody.java         ← moved from game/
│   └── network/
│       ├── PacketId.java            ← all packet IDs, shared by client + server
│       └── packets/                 ← all packet types as Java records
├── server/
│   ├── ServerMain.java              ← headless dedicated server entry point
│   ├── GameServer.java              ← 20 TPS server game loop
│   ├── PlayerSession.java           ← per-client state: position, loaded chunks, queue
│   ├── ServerWorld.java             ← World + chunk persistence (save/load)
│   └── network/
│       ├── ServerNetworkManager.java
│       └── ClientHandler.java
├── client/
│   ├── GameClient.java              ← current GameLoop adapted for network
│   ├── ClientWorld.java             ← chunk storage from received data, no generation
│   ├── RemotePlayer.java            ← other players with position interpolation
│   └── network/
│       ├── ClientNetworkManager.java
│       └── ServerHandler.java
└── engine/                          ← unchanged: Mesh, Camera, ShaderProgram, etc.
```
 
**Network library:** Netty. Handles TCP framing, non-blocking NIO, buffer pooling. One Gradle dependency.
 
**Protocol:** Custom binary TCP. Length-prefixed packets: `[4-byte length][1-byte packet ID][payload]`.
 
**Default port:** 24463 (unassigned by IANA, not used by any known game or service).
 
**Server TPS:** 20 ticks/second. Client runs at 60 UPS — 3 client frames per server tick, a clean multiple.
 
**Authentication:** Offline mode only. Username string, no verification. Online auth deferred indefinitely.
 
### Protocol Packet List
 
**Serverbound (client → server):**
- `Handshake` — protocol version
- `LoginRequest` — username
- `PlayerMove` — x, y, z, yaw, pitch, onGround (sent 20× per second)
- `BlockBreak` — worldX, worldY, worldZ
- `BlockPlace` — worldX, worldY, worldZ, blockType
- `KeepAliveResponse` — echo of server's keepalive id
 
**Clientbound (server → client):**
- `LoginSuccess` — playerId, spawnX, spawnY, spawnZ
- `ChunkData` — chunkPos + 4096 bytes (raw block array)
- `UnloadChunk` — chunkPos
- `BlockChange` — worldX, worldY, worldZ, blockType
- `PlayerSpawn` — playerId, username, x, y, z
- `PlayerMove` — playerId, x, y, z, yaw, pitch
- `PlayerDespawn` — playerId
- `KeepAlive` — id
 
### World Persistence — Folder Structure
```
worlds/
└── world_name/
    ├── world.dat          ← seed, spawn point (binary)
    └── chunks/
        └── cx_cy_cz.dat   ← 4096 bytes raw block array, GZIP compressed
```
One file per chunk initially. Region files (32×32 chunks packed) deferred to Phase 6 if I/O proves to be a bottleneck.
 
### Phase 5 Roadmap (in order)
 
**5A — Package restructure + Netty + handshake** *(current)*
Move shared classes to `common/`. Add Netty to build.gradle. Implement ServerNetworkManager and ClientNetworkManager. Implement Handshake + Login packets. Milestone: client connects to server and both log the connection. No gameplay yet.
 
**5B — Chunk streaming**
Server sends ChunkData packets when chunks enter player range. Client reconstructs Chunk objects and meshes them locally. PlayerSession tracks loaded chunk set per client. Milestone: world renders identically to today but all chunk data arrived over the wire.
 
**5C — Block interaction sync**
Client sends BlockBreak/BlockPlace. Server validates, updates ServerWorld, broadcasts BlockChange to all clients in range. Milestone: two clients see each other's block edits in real time.
 
**5D — Player movement sync + client-side prediction**
Client sends PlayerMove every tick. Server broadcasts to other clients in range. RemotePlayer renders other players with position interpolation (smoothes 20Hz updates). Client-side prediction + server reconciliation for local player. Milestone: two clients see each other moving smoothly with no input lag.
 
**5E — World persistence**
Server saves modified chunks to disk. Server loads saved chunks before generating. World folder selectable at launch. Milestone: break blocks, restart server, blocks remain.
 
**5F — Singleplayer integration**
Main.java launches embedded GameServer + GameClient on the same JVM. ServerMain.java for dedicated server mode. Milestone: `./gradlew run` = singleplayer, `./gradlew runServer` = dedicated server.
 
### Future Considerations (not planned, not blocked)
- **Online authentication:** Offline mode is permanent until a public release is considered. If implemented, it would sit between Handshake and Login as a separate protocol state.
- **Claims / protected areas:** Per-area permission system allowing specific players or groups to have build/break rights over a region. Only worth implementing after authentication exists.
- **Commands and permissions:** Server command system with permission levels (owner, admin, player). Pairs naturally with claims.
- **Region files:** Packing 32×32 chunks into a single file (Minecraft's .mca format). Deferred to Phase 6 if one-file-per-chunk proves slow at large render distances.
 
### Notes for Future Claude Sessions
- `World.java` update() was always designed with a `Collection<Vector3f>` viewer positions upgrade in mind — the seam is the `world.update()` call in GameLoop.
- `ChunkMesher` is stateless and thread-safe — stays on the client, meshing chunks received from the server. No changes needed.
- `TerrainGenerator` moves entirely to the server. The client never generates terrain.
- `PhysicsBody` stays in `common/` — both client (local player prediction) and server (authoritative physics) will use the same class.
- OpenGL rule unchanged: all GL calls remain on the client main thread. Server has zero GL dependency and zero LWJGL imports.
- `RayCaster` and `RaycastResult` stay in `common/` — server needs them for block interaction validation.

---

## Entry 031 — Phase 5A: Package Restructure + Netty Handshake/Login
**Date:** 23.03.2026
**Phase:** 5 — Multiplayer

### What Was Done
- Moved six classes from `com.voxelgame.game` to `com.voxelgame.common.world`:
  `Block`, `Chunk`, `ChunkPos`, `PhysicsBody`, `RayCaster`, `RaycastResult`.
  Updated all imports across the project. No logic changes.
- Added Netty 4.1.115.Final to `build.gradle` as the network library.
- Added `runServer` Gradle task — runs `ServerMain` headless with no window.
- Created `com.voxelgame.common.network` package:
  - `Packet` — marker interface for all packet types
  - `PacketId` — enum of all wire IDs (serverbound 0x00–0x0F, clientbound 0x10–0x1F)
  - `PacketEncoder` — Netty `MessageToByteEncoder`, serializes `Packet` → `ByteBuf`
  - `PacketDecoder` — Netty `MessageToMessageDecoder`, deserializes `ByteBuf` → `Packet`
- Created three Phase 5A packets: `HandshakePacket`, `LoginRequestPacket`, `LoginSuccessPacket`.
- Created `com.voxelgame.server` package:
  - `GameServer` — owns Netty server, minimal Phase 5A game loop stub
  - `ServerMain` — headless dedicated server entry point
  - `ServerNetworkManager` — Netty `ServerBootstrap`, one `ClientHandler` per connection
  - `ClientHandler` — server-side state machine (HANDSHAKING → LOGGING_IN → PLAYING)
- Created `com.voxelgame.client.network` package:
  - `ClientNetworkManager` — Netty `Bootstrap`, connects to server
  - `ServerHandler` — client-side handler, sends Handshake+Login on `channelActive`
- Updated `Main.java` to launch embedded `GameServer` on a daemon thread, wait for
  port bind via `CountDownLatch`, connect `ClientNetworkManager`, then run `GameLoop`.

### Protocol Details
- Wire format: `[4-byte length][1-byte packet ID][payload]`
- `LengthFieldBasedFrameDecoder` / `LengthFieldPrepender` handle TCP framing
- `TCP_NODELAY` enabled on all channels — packets sent immediately, no Nagle buffering
- Default port: **24463** (unassigned by IANA, no conflicts with known services)
- All Netty I/O runs on dedicated NIO threads; game logic never blocks on socket reads

### Decisions Made
- `netty-all` artifact for simplicity; can be replaced with three individual modules
  (`netty-transport`, `netty-codec`, `netty-handler`) if linking issues arise
- Singleplayer = embedded server on `localhost` — identical to Minecraft's integrated
  server model. No separate singleplayer code path will ever exist.
- `CountDownLatch` for server-ready synchronization — deterministic, no `Thread.sleep()`

### Problems Encountered
- None. First run produced the expected login exchange in the console and the game
  window opened and played identically to Phase 4.

### AI Assistance Notes
- Claude wrote all new files with Netty pipeline explanation from first principles.
- Copilot handled the mechanical package restructure (file moves, import updates).

### Lessons / Observations
- Netty's pipeline abstraction (decode → handle → encode) makes adding new packet types
  a one-file change to the encoder and decoder — handlers never touch bytes.
- `CountDownLatch` is the correct primitive for "wait until ready" between threads.
  `Thread.sleep()` is always wrong for this.

---

## Entry 032 — Phase 5B: Chunk Streaming Over TCP
**Date:** 23.03.2026
**Phase:** 5 — Multiplayer

### What Was Done
- Added `BlockView` interface to `common/world/` — single method `getBlock(x, y, z)`.
  Both `World` (server) and `ClientWorld` (client) implement it. `PhysicsBody`,
  `RayCaster`, and `Player` now depend on `BlockView` instead of `World`, making
  them usable on both sides with no duplication.
- **Stripped `World.java` of all mesh/GL code** — this was the critical architectural
  fix. `World` now has one job: chunk data management. Removed: `meshes` map, all
  `Mesh` imports, `dirtyMeshes`, `remeshInProgress`, `pendingRemeshes`, `PendingRemesh`,
  `drainPendingRemeshes()`, `processDirtyMeshes()`, `rebuildMeshSync()`, `render()`.
  Worker thread now generates terrain only — no `ChunkMesher.mesh()` on the server.
- Added `Chunk.toBytes()` and `Chunk.fromBytes(byte[])` — serialize/deserialize the
  4096-byte flat block array. Used for network transmission; will reuse for disk
  persistence in Phase 5E.
- Added `World.getChunk(ChunkPos)` and `World.getLoadedChunkPositions()` for
  `ServerWorld` access.
- Added two new packets: `ChunkDataPacket` (cx, cy, cz, 4096 bytes) and
  `UnloadChunkPacket` (cx, cy, cz). Updated `PacketEncoder` and `PacketDecoder`.
- Created `PlayerSession` — per-client server state: channel, position, loaded chunk set.
  Methods: `sendPacket()`, `hasChunk()`, `markChunkLoaded()`, `markChunkUnloaded()`.
- Created `ServerWorld` — wraps `World`, drives chunk streaming per player. On each
  20 TPS tick: drains player connect/disconnect queues, calls `world.update()`,
  sends `ChunkDataPacket` for newly loaded chunks (capped at 16/player/tick),
  sends `UnloadChunkPacket` for chunks the server unloaded.
- Upgraded `GameServer` with a proper 20 TPS game loop. On login: creates
  `PlayerSession`, registers with `ServerWorld`. On disconnect: removes player.
- Created `ClientWorld` — client-side `BlockView` and renderer. Receives chunk data
  from the Netty I/O thread via thread-safe queues. Meshes chunks on worker threads
  (same pattern as old `World`). Uploads `Mesh` objects on the main GL thread only.
  Implements the same 26-neighbor AO snapshot for `ChunkMesher`.
- Updated `GameLoop` to accept `ClientWorld` as a constructor argument. Removed
  local `World` ownership. `clientWorld.update()` drains pending mesh builds each
  tick; `clientWorld.render()` replaces `world.render()`.
- Updated `ServerHandler` and `ClientNetworkManager` to accept `ClientWorld`.
- Updated `Main.java` to construct `ClientWorld` first, pass it to both
  `ClientNetworkManager` and `GameLoop`.

### Architecture — The GL Thread Rule
The crash encountered during implementation was caused by `ServerWorld` calling
`world.update()`, which still contained `new Mesh(vertices)` — a GL call — on the
`embedded-server` thread which has no OpenGL context. Windows/NVIDIA crashes hard on
this with `EXCEPTION_ACCESS_VIOLATION` inside `lwjgl_opengl.dll`.

The fix was to strip all mesh/GL code from `World` entirely rather than add a mode
flag. The boundary is now absolute:
- `World` and `ServerWorld` → zero engine imports, zero GL calls, ever
- `ClientWorld` → owns all meshing and rendering

### Phase 5B Milestone Result
```
[Server] Listening on port 24463
[Client] Login successful — playerId=1, spawn=(64.0, 70.0, 64.0)
FPS: 27  | Chunks: 12/120    ← initial load
FPS: 121 | Chunks: 1762/1762 ← fully loaded, stable
Engine shut down cleanly.
```
120 FPS maintained throughout. Physics, freecam, and camera work against `ClientWorld`.
Block interaction disabled pending Phase 5C.

### Known Limitation (expected, resolved in Phase 5D)
The server streams chunks around the player's spawn position only. When the player
moves, the server doesn't know — `PlayerMove` packets don't exist until Phase 5D.
The streaming center doesn't follow the player. All chunks loaded are correct; the
load area just stays anchored to spawn for now.

### AI Assistance Notes
- Claude wrote all new files. Copilot wrote the three mechanical files (`ChunkDataPacket`,
  `UnloadChunkPacket`, `PlayerSession`).
- The GL crash was diagnosed by Copilot from the native crash log and confirmed by
  Claude. Fix was Claude's (strip GL from `World` entirely rather than add a mode flag).

### Lessons / Observations
- The CLAUDE.md architecture rule "all GL calls on the main thread" applies to the
  server thread just as much as any worker thread — the server thread has no GL
  context at all. Any `engine` import in the `server` package is a bug.
- `Chunk.toBytes()` / `Chunk.fromBytes()` will be reused verbatim for disk persistence
  in Phase 5E — designing network and persistence serialization together avoids doing
  the work twice.
- Chunk streaming cap (16/player/tick) prevents flooding a new client with hundreds
  of large packets in a single tick. Without the cap, the Netty write buffer fills
  and back-pressure can cause frame hitches on the main thread.

---

## Phase 5 Roadmap — Updated Status

### Completed
- 5A — Package restructure + Netty + handshake/login
- 5B — Chunk streaming (server generates, client renders via TCP)

### Remaining (in priority order)

**5C — Block interaction sync** *(next up)*
Client sends `BlockBreakPacket` / `BlockPlacePacket`. Server validates, updates
`ServerWorld`, broadcasts `BlockChangePacket` to all clients in range. Client's
`ServerHandler` receives `BlockChangePacket` and calls a new `ClientWorld.applyBlockChange()`
which queues a local block update and dirty-marks affected chunks for remeshing.
Milestone: two clients see each other's block edits in real time.

**5D — Player movement sync + client-side prediction**
Client sends `PlayerMovePacket` every tick. Server updates `PlayerSession` position
(fixing the streaming-center-stuck-at-spawn issue). Server broadcasts other players'
positions. `RemotePlayer` with interpolation renders other players smoothly.
Client-side prediction + server reconciliation for local player.
Milestone: streaming center follows player; two clients see each other moving.

**5E — World persistence**
Server saves modified chunks to disk on block change and on shutdown. Loads saved
chunks from disk before generating. One file per chunk (`chunks/cx_cy_cz.dat`).
Milestone: break blocks, restart server, blocks remain.

**5F — Singleplayer integration cleanup**
Properly split `GameLoop` into `GameClient`. Move `engine/` under `client/engine/`
or leave as-is (decision deferred). `ServerMain` for dedicated server. `Main` for
singleplayer. Wire up world folder selection and username from CLI args.
Milestone: `./gradlew run` = singleplayer, `./gradlew runServer` = dedicated server.

---

## Entry 033 — Phase 5C: Block Interaction Sync
**Date:** 23.03.2026
**Phase:** 5 — Multiplayer

### What Was Done
- Added three packets: `BlockBreakPacket`, `BlockPlacePacket` (serverbound),
  `BlockChangePacket` (clientbound). All three added to `PacketEncoder` and
  `PacketDecoder`.
- `ClientWorld` gained a `pendingBlockChanges` concurrent queue and
  `queueBlockChange()` entry point (called from Netty thread). `drainPendingBlockChanges()`
  runs first in `update()` — applies the server-authoritative block change to the
  local chunk and dirty-marks the chunk and its face-adjacent neighbors for remesh.
- `ServerWorld` gained `applyBlockBreak()`, `applyBlockPlace()`, and
  `broadcastBlockChange()`. Break/place validation is a stub — Phase 5D will add
  range checks and permission guards. Broadcast targets only players who have the
  affected chunk loaded — players without the chunk receive correct data when it
  streams to them later.
- `ClientHandler.handlePlaying()` now routes `BlockBreakPacket` and `BlockPlacePacket`
  to `ServerWorld` instead of logging them as unhandled.
- `ServerHandler.channelRead0()` routes `BlockChangePacket` to
  `ClientWorld.queueBlockChange()`.
- `GameLoop` gained a `serverChannel` field (injected via constructor). Block
  interaction in `update()` now sends packets to the server instead of calling
  `world.setBlock()` directly. The player overlap guard is preserved for physics
  mode; freecam bypasses it.
- `Main.java` updated to pass `network.getChannel()` to `GameLoop`.

### Decisions Made
- Client does not optimistically update the local world — it waits for the server's
  `BlockChangePacket` before the block visually changes. This is one RTT of latency
  on localhost, which is imperceptible. Client-side prediction deferred to Phase 5D.
- `broadcastBlockChange` skips players without the chunk loaded — avoids sending
  updates for chunks the client hasn't received yet and would ignore anyway.

### Known Issue (noted, deferred)
`applyBlockBreak/Place` are called from `ClientHandler` on the Netty I/O thread
but access `players` (an `ArrayList` owned by the tick thread) — a data race.
Benign for single-player. Resolved in Entry 034 when the pending move queue was added.

### Problems Encountered
- None. Block break and place worked on first run.

### Lessons / Observations
- The server-authoritative model means two clients automatically see each other's
  block edits — the broadcast loop handles it with no extra client-side logic.

---

## Entry 034 — Phase 5D (Partial): Player Movement Sync and Streaming Fix
**Date:** 23.03.2026
**Phase:** 5 — Multiplayer

### What Was Done
- Added `PlayerMoveSBPacket` (serverbound): `float x, y, z, yaw, pitch`. Registered
  in `PacketEncoder` and `PacketDecoder`.
- `PlayerSession.x/y/z` changed from `private final float` to `private volatile float`
  with a new `updatePosition(x, y, z)` method. Volatile makes reads safe across the
  Netty and tick threads without a full lock.
- `ServerWorld` gained a `pendingMoves` concurrent queue and `queuePlayerMove()` entry
  point. The tick thread drains it at the start of each tick alongside player
  connects/disconnects — position updates now flow through the same queue pattern,
  which also resolves the data race from Entry 033.
- `ClientHandler.handlePlaying()` routes `PlayerMoveSBPacket` to
  `ServerWorld.queuePlayerMove()`.
- `GameLoop.update()` sends a `PlayerMoveSBPacket` every tick. Y coordinate is feet
  position in physics mode, camera Y in freecam — either is sufficient for streaming.
- Result: the server's chunk streaming center now follows the player. Moving in any
  direction generates new chunks and unloads distant ones correctly.

### Decisions Made
- Packet sent every tick (60/s) rather than throttled to 20/s. The server discards
  redundant updates harmlessly — the queue drains all of them at the start of each
  tick and the last write wins. Throttling deferred until bandwidth is measured as
  a concern.
- Yaw and pitch are included in the packet for future use in Phase 5D (look-direction
  biased generation, other-player rendering) — no extra cost now.

### Problems Encountered
- None. Streaming followed the player correctly on first run.

### What Remains in Phase 5D
- Broadcasting other players' positions (`PlayerSpawn`, `PlayerMove`, `PlayerDespawn`
  clientbound packets).
- `RemotePlayer` entity with position interpolation for smooth 20Hz → 60 FPS rendering.
- Client-side prediction + server reconciliation for local player.

---

## Entry 035 — Server TPS Throughput Fix
**Date:** 23.03.2026
**Phase:** 5 — Multiplayer

### What Was Done
- Raised three throughput constants to compensate for the move from 60 UPS (Phase 4
  game loop) to 20 TPS (server tick):
  - `World.MAX_UPLOADS_PER_FRAME`: 16 → 256. On the server this is a `HashMap.put()`
    with zero GL cost — the old cap was protecting against mesh build time that no
    longer exists here.
  - `World.MAX_CHUNKS_PER_TICK`: 16 → 64. Restores generation submission rate to
    roughly Phase 4 levels (64 × 20 TPS ≈ 960 × 16 UPS).
  - `ServerWorld.MAX_CHUNKS_PER_PLAYER_PER_TICK`: 16 → 64. Restores client delivery
    rate to match generation throughput.

### Root Cause
All three constants were designed for a 60 UPS loop. The server tick runs at 20 TPS —
one third the rate. Effective throughput dropped to one third without any code change.
The fix is to scale the per-tick budgets by 3× so the per-second rate matches Phase 4.

### Lessons / Observations
- Per-tick budget constants require re-evaluation whenever the tick rate changes.
  Documenting the intended per-second rate alongside the constant would make this
  class of bug self-evident at a glance.

---

## Entry 036 — Phase 5D: Other-Player Broadcasting and RemotePlayer Rendering
**Date:** 23.03.2026
**Phase:** 5 — Multiplayer

### What Was Done
- Added three clientbound packets: `PlayerSpawnPacket`, `PlayerMoveCBPacket`,
  `PlayerDespawnPacket`. All registered in `PacketEncoder` and `PacketDecoder`.
- Created `RemotePlayer.java` — holds interpolation state for another connected
  player. On each server update, `updatePosition()` snaps `prev` to the current
  rendered position and sets a new `target`. `interpolate()` lerps between them
  using elapsed time since the last update divided by `SERVER_TICK_MS` (50ms),
  clamped to [0,1] to prevent overshoot when updates are delayed.
- Created `RemotePlayerRenderer.java` — renders a solid orange box (0.6 × 1.8 × 0.6,
  matching PhysicsBody dimensions) for each remote player. Uses the main 3D shader
  with `useTexture=false`. Directional shading constants match ChunkMesher for visual
  consistency. Calls `interpolate()` on each player during the render pass.
- `ClientWorld` gained `remotePlayers` map, `remotePlayerRenderer`, and three
  concurrent queues (`pendingSpawns`, `pendingMoves`, `pendingDespawns`).
  `drainPendingPlayerEvents()` runs first in `update()`. `renderRemotePlayers()`
  delegates to the renderer.
- `ServerWorld.tick()` updated: on player connect, announces all existing players
  to the new player and the new player to all existing players. On disconnect,
  broadcasts `PlayerDespawnPacket` to all remaining players. After chunk streaming,
  broadcasts `PlayerMoveCBPacket` to all other players each tick (20 TPS).
- `ServerHandler` routes all three new clientbound packets to `ClientWorld` queues.
- `GameLoop.render()` calls `clientWorld.renderRemotePlayers(shader)` after the
  block highlight pass — `useTexture` is already false at that point.

### Decisions Made
- Position broadcast runs every tick (20 TPS) — clients interpolate between
  updates. O(n²) broadcast loop is fine for any realistic player count.
- `PlayerSpawnPacket` serves double duty: announces a new arrival to existing
  players, and announces each existing player to a new arrival. One packet type,
  two use cases, no duplication.
- Server filters `PlayerMoveCBPacket` so a player never receives their own position
  broadcast — no client-side filtering by playerId needed.

### Problems Encountered
- **GL context crash on startup.** `RemotePlayerRenderer` was constructed as a
  field initializer in `ClientWorld`, which is instantiated in `Main.java` before
  `GameLoop` calls `window.init()`. The GL context does not exist at that point —
  `glGenVertexArrays` caused `EXCEPTION_ACCESS_VIOLATION` in `lwjgl_opengl.dll`.
  Fixed by making `remotePlayerRenderer` a nullable field and adding
  `initRenderResources()` to `ClientWorld`, called from `GameLoop.init()` immediately
  after `window.init()`. Same root cause as the Phase 5B crash.

### AI Assistance Notes
- Copilot identified the GL context crash from the hs_err log — recognized the
  native crash pattern and traced it back to the field initializer execution order.

### Lessons / Observations
- Any class instantiated before `GameLoop.init()` must not create GL resources in
  its constructor or field initializers. The pattern is: nullable field + separate
  `initRenderResources()` called after `window.init()`. Added to CLAUDE.md.
- This is the second time this exact mistake has been made (Phase 5B was the first).
  The fix is now documented as a hard rule.

### What Remains in Phase 5D
- Client-side prediction + server reconciliation for the local player. Deferred —
  on localhost latency is imperceptible and the system is fully playable without it.
  Will revisit after Phase 5E and 5F if needed.

---

## Entry 035 — Phase 5E: World Persistence
**Date:** 23.03.2026
**Phase:** 5 — Multiplayer

### What Was Done
- Created `ChunkStorage` interface in `com.voxelgame.game` — two methods:
  `save(ChunkPos, byte[])` and `load(ChunkPos) → byte[]`. Works in raw bytes
  to stay free of game-type dependencies and match the existing `Chunk.toBytes()`
  / `Chunk.fromBytes()` serialization from Phase 5B.
- Created `FlatFileChunkStorage implements ChunkStorage` in
  `com.voxelgame.server.storage` — one GZIP-compressed `.dat` file per chunk
  under `worlds/default/chunks/<cx>_<cy>_<cz>.dat`. Directory created automatically
  on construction. Thread-safe: stateless beyond the immutable `chunksDir` path.
- Added dirty tracking to `World` — `ConcurrentHashMap.newKeySet()` for
  `dirtyChunks`. `setBlock()` adds the affected `ChunkPos` after every mutation.
- Added background save executor to `World` — single daemon thread (`chunk-saver`).
  `saveDirtyChunks()` drains the dirty set each tick, captures a `chunk.toBytes()`
  defensive copy per chunk, and submits it to the save executor. Tick thread never
  blocks on disk I/O.
- Modified `submitToExecutor()` in `World` — generation tasks now check disk first
  via `storage.load(pos)`. If a saved file exists, `Chunk.fromBytes()` is used
  instead of terrain generation. Both paths feed the same `pendingChunks` queue —
  the rest of the pipeline is unchanged.
- Added `flushDirtyChunks()` to `World` — called from `cleanup()` before executor
  shutdown. Saves all remaining dirty chunks synchronously, then waits up to 10s
  for in-flight background saves to complete. Guarantees no writes are lost on a
  clean server stop.
- `ServerWorld` constructor now accepts `ChunkStorage` and passes it to `World`.
- `GameServer` constructs `FlatFileChunkStorage(Path.of("worlds", "default"))` and
  passes it to `ServerWorld`.

### Decisions Made
- **Save only modified chunks, not all generated chunks.** Unmodified chunks are
  identical to what terrain generation produces from the seed — saving them wastes
  disk space and write time for zero benefit. The set of `.dat` files is exactly
  the diff between the world and its seed.
- **`ChunkStorage` interface from day one.** The migration to region files later
  becomes a single swap in `GameServer` — `RegionFileChunkStorage implements
  ChunkStorage`. Nothing else changes.
- **Delta/diff saving considered and rejected.** Saving individual block changes
  instead of full chunks would require replaying N mutations on top of fresh
  generation at load time. The storage savings are marginal (crossover ~30-80
  changed blocks per chunk); the added code paths and replay complexity are not
  worth it at this scale.
- **Multiple worlds deferred.** `FlatFileChunkStorage` already accepts any `Path`,
  so world selection falls out naturally from Phase 5F CLI args. Nothing to design now.

### Problems Encountered
- None. First run created `worlds/default/chunks/`, wrote `.dat` files for
  modified chunks, and correctly restored them after a server restart.

### AI Assistance Notes
- Claude wrote all new and modified files with full explanation of dirty tracking,
  the defensive copy pattern, and the load-or-generate executor task.

### Lessons / Observations
- Defensive copies (`chunk.toBytes()`) before handing data to a background thread
  are the correct primitive here — the save executor never needs synchronization
  because it only ever sees immutable byte arrays.
- The `ChunkStorage` interface cost nothing and buys a clean swap point for region
  files later. Interfaces on I/O boundaries are almost always worth it.

---

## Entry 036 — Phase 5F: CLI Args, world.dat, Configurable Launch
**Date:** 23.03.2026
**Phase:** 5 — Multiplayer

### What Was Done
- Created `WorldMeta` in `server/storage/` — reads/writes `world.dat` (8-byte
  big-endian long seed) via `DataOutputStream`. `loadOrCreate(Path worldDir)`
  handles all cases: new world (generate random seed + write), existing world
  (read seed), corrupt file (warn + overwrite with new seed). Directory is created
  if absent.
- `GameServer` constructor chain replaced: default no-arg constructor uses
  `worlds/default` and `GameServer.PORT`; primary constructor takes `(Path worldDir,
  int port)`, calls `WorldMeta.loadOrCreate()`, constructs `FlatFileChunkStorage`
  from the same `worldDir`, and passes the loaded seed to `ServerWorld`. Hardcoded
  `WORLD_SEED` constant removed.
- `ServerMain` updated with CLI arg parsing: `--world <name>` selects
  `worlds/<name>/` (default: `default`), `--port <n>` overrides the TCP port
  (default: 24463). Invalid port value falls back to default with a warning.
- `Main` updated with `--username <name>` parsing (default: `Player`), passed
  to `ClientNetworkManager`.
- `build.gradle` updated: both `run` and `runServer` tasks forward
  `--args="..."` from the Gradle command line via `project.hasProperty('args')`.

### Decisions Made
- `Main.java` still constructs `GameServer()` with no args (always `worlds/default`).
  Singleplayer world selection belongs in the future menu UI, not the CLI.
- No arg-parsing library — 10 lines of manual `args[]` scanning is simpler and
  has zero dependencies for this number of flags.
- `world.dat` format is just the seed long for now. Future fields (spawn coords,
  game rules) append cleanly — reads are length-guarded by `DataInputStream`.

### Problems Encountered
- None. All four argument combinations tested and confirmed working.

### AI Assistance Notes
- Claude wrote all changes.

### Lessons / Observations
- The Ctrl+C → "Terminate batch job (Y/N)?" prompt on Windows is cmd.exe asking
  whether to kill the Gradle wrapper batch script — not a code issue. The shutdown
  hook fires correctly before that prompt appears.
- `WorldMeta.loadOrCreate()` is the right pattern for any config file that must
  exist: try to read, generate fresh if missing or corrupt, always return a valid
  value. No caller ever handles a null or exception.

---

## Entry 037 — Phase Roadmap Revision: Phase 6 Replanned
**Date:** 23.03.2026
**Phase:** 5 — Multiplayer (complete)

### What Was Decided
Phase 6 was previously labelled "Modding API." After review, modding was pushed to
Phase 7 because the prerequisite work it depends on does not exist yet:

- `Block` is a hardcoded enum. A mod cannot register new block types without a
  `BlockRegistry`. Converting Block from enum to a registered class touches Chunk
  serialization, ChunkMesher, TerrainGenerator, the network protocol, and save files.
  This refactor must happen before the modding API is designed — not inside it.
- No menu system exists. World selection, multiplayer connect, and settings are all
  CLI-only. A modding API without a UI to load mods from is incomplete.
- No entity system exists. Mods that add mobs or items have nowhere to register them.
- No item/inventory system exists. The gameplay loop is not functional enough to
  meaningfully extend.

### New Phase 6 Plan

**6A — Block Registry**
Convert `Block` from a Java enum to a registered class with stable numeric IDs.
`Chunk` serialization, network protocol, and save files all switch from `ordinal()`
to registry ID. This is the single most architecturally disruptive change remaining
and is the prerequisite for everything else in Phase 6.

**6B — Menu / UI System**
Main menu (Singleplayer / Multiplayer / Quit), world selection screen (lists
`worlds/` directory, create/delete), multiplayer connect screen (IP + port input),
settings screen (render distance, username, key bindings), in-game pause menu.
Resolves the current limitation where connecting to a running dedicated server
requires CLI workarounds.

**6C — Lighting + Day/Night Cycle**
Skylight propagation (sunlight enters from top, decreases downward), basic block
light (static brightness value per block type, foundation for torches), day/night
cycle (sun position, ambient light level changes over time). Mods that add light
sources need this system to exist first.

**6D — Entity System + Player Model**
Entity framework: position, AABB, update tick, server-side entity list, client-side
rendering. Local player and remote players become proper entity instances. Basic
skeletal player model with idle/walk/jump animations. Item entities (block drops).
Prerequisite for mobs, throwable items, and anything animated.

**6E — Items + Inventory**
Item registry (same pattern as block registry from 6A), hotbar and full inventory
screen, block drops on break, basic crafting grid. Makes the gameplay loop functional
beyond just placing and breaking blocks.

**Phase 7 — Modding API**
With registries (blocks, items, entities), lighting, UI, and a real gameplay loop
all in place, the modding API exposes `BlockRegistry.register()`,
`ItemRegistry.register()`, `EntityRegistry.register()`, world gen hooks, and event
listeners. Has something meaningful to offer because the game underneath it is built.

### Deferred to Phase 8+
- Non-solid blocks (slabs, stairs) — 6A prerequisite satisfied first, can be added
  as the first real use-case of the new registry
- Liquids — complex simulation, independent of everything else
- Better world gen / biomes — can happen during or after Phase 6
- Server standalone fat-jar distribution — trivial Gradle config, not blocking anything
- Further performance optimizations — 120+ FPS already, no measurable bottleneck

---

## Entry 038 — Phase 6A: Block Registry
**Date:** 23.03.2026
**Phase:** 6 — Foundation for extensibility

### What Was Done
- Created `TextureLayers.java` in `common/world/` — moved texture layer index constants
  out of `engine/TextureManager` and into `common/`. This cuts the `common/ → engine/`
  import dependency that existed because `Block` referenced `TextureManager.LAYER_*`.
  `TextureManager` now re-exports the constants by referencing `TextureLayers` — one
  source of truth, no circular dependency.
- Created `BlockType.java` in `common/world/` — immutable registered block type with
  fields: `id`, `name`, `solid`, `topTextureLayer`, `sideTextureLayer`,
  `bottomTextureLayer`. Package-private constructor — only `BlockRegistry` creates instances.
  Identity comparison (`==`) remains valid and efficient.
- Created `BlockRegistry.java` in `common/world/` — static registry. `register()` assigns
  IDs sequentially from 0. `getById(int)` and `getByName(String)` for lookups. ID array
  grows dynamically (doubles). Thread-safe registration; unsynchronised reads safe after
  startup.
- Created `Blocks.java` in `common/world/` — static constants `AIR` (ID 0), `GRASS`,
  `DIRT`, `STONE` registered in a `static` block. Registration order is permanent —
  append-only, never reorder. `bootstrap()` triggers class loading explicitly at startup.
- Deleted `Block.java` — the old enum. Replaced throughout the codebase.
- Updated `Chunk.java` — block storage changed from `byte[]` (ordinals, 4096 bytes) to
  `short[]` (registry IDs, 8192 bytes). `SERIALIZED_SIZE = 8192`. `toBytes()`/`fromBytes()`
  serialize big-endian unsigned shorts. `getBlock()` returns `BlockType`.
  `setBlock()` takes `BlockType`. `BLOCK_VALUES` cache removed.
- Updated `BlockView.java` — `getBlock()` return type `Block` → `BlockType`.
- Updated `ChunkMesher.java` — `packMask` bit layout expanded: bits 0–16 for block ID
  (was bits 0–2), AO shifted to bits 17–24 (was 3–10). `packMask` call sites changed
  from `b.ordinal() + 1` to `b.getId()`. Extraction in `buildMergedQuads` updated to
  match. Block reconstruction changed from `Block.values()[n-1]` to
  `BlockRegistry.getById(n)`. AIR checks use `Blocks.AIR`.
- Updated `TextureManager.java` — `LAYER_*` constants now delegate to `TextureLayers`.
- Cascading migration across: `TerrainGenerator`, `RayCaster`, `PhysicsBody`, `World`,
  `ClientWorld`, `BlockChangePacket`, `BlockPlacePacket`, `PacketEncoder`, `PacketDecoder`,
  `ServerWorld`, `ClientHandler`, `ServerHandler`, `GameLoop`, `Main`, `ServerMain`.
  All `Block.X` → `Blocks.X`, all `blockOrdinal` → `blockId`, all `Block` types →
  `BlockType`. `Blocks.bootstrap()` added as first line of both `main()` entry points.

### Decisions Made
- **ID 0 = AIR is a permanent architectural contract.** `Chunk` zero-initialises its
  `short[]` and relies on 0 meaning AIR. `Blocks` static block registers AIR first,
  guaranteeing this forever.
- **Append-only registration order.** Adding a block between two existing ones would
  shift IDs and corrupt every save file and in-flight packet. The rule is the same as
  a database migration — new entries go at the end only.
- **`Blocks.bootstrap()` called explicitly.** Static initializers fire lazily on first
  class access, which is non-deterministic. Explicit bootstrap at the top of `main()`
  guarantees registration happens before any other code runs.
- **Old save files are incompatible.** Chunks were previously stored as 4096-byte ordinal
  arrays; they are now 8192-byte registry ID arrays. The `worlds/` directory was deleted
  before first run. `fromBytes()` validates length and throws on mismatch — a corrupt or
  old-format file falls back to generation rather than loading wrong block types.

### Problems Encountered
- None at runtime. Copilot caught two additional compile-time breakages outside the
  listed files (`ServerHandler.java` still used `p.blockOrdinal()`; `ChunkMesher.java`
  had a stale `Chunk.isAir()` call) and fixed them in the same pass.

### AI Assistance Notes
- Claude wrote all five new files (`TextureLayers`, `BlockType`, `BlockRegistry`,
  `Blocks`, updated `Chunk`) in full, with the ChunkMesher bit layout changes and
  explanation.
- Copilot handled the mechanical cascade migration across 14 files via a single
  structured prompt, plus two self-identified compile fixes. BUILD SUCCESSFUL on
  first Copilot compile attempt.

### Lessons / Observations
- Enum ordinals as serialized IDs is a trap that is trivial to avoid at design time
  and expensive to fix later. Any value that crosses a persistence or network boundary
  needs a stable, explicitly assigned ID — not a position-derived one.
- The `common/ → engine/` import was transitive and silent. Java's class loader
  inlined the `TextureManager` constants so the server never actually loaded the GL
  class. It worked by accident. Moving constants to `common/` makes the boundary
  explicit and verifiable.
- With 4 block types (IDs 0–3), the old 3-bit mask layout would have continued to
  work for a long time before breaking. Fixing the bit layout now costs nothing;
  fixing it after the first block with ID ≥ 8 would have required a save file migration.

---

## Entry 039 — Phase 6B: UI Foundation + Main Menu
**Date:** 23.03.2026
**Phase:** 6 — Foundation for extensibility

### What Was Done
- Created `src/main/resources/shaders/ui.vert` and `ui.frag` — orthographic 2D
  shader pair. Vertex shader applies a `uProjection` ortho2D matrix (Y-down,
  pixel-space). Fragment shader multiplies texture sample by per-vertex tint color.
  Solid rects use a 1×1 white texture so the tint becomes the color directly.
- Created `GlyphAtlas.java` in `engine/ui/` — bakes all printable ASCII (32–126)
  into a single `GL_TEXTURE_2D` at startup using Java AWT. Monospaced font,
  anti-aliasing off, white-on-transparent. Provides UV bounds and advance widths
  per character.
- Created `UiShader.java` in `engine/ui/` — thin wrapper around `ShaderProgram`
  for the UI pass. `setProjection(w, h)` rebuilds the ortho matrix on resize.
- Created `UiRenderer.java` in `engine/ui/` (Copilot) — batched quad renderer.
  8 floats/vertex `[x,y,u,v,r,g,b,a]`, max 4096 quads/batch, pre-generated EBO.
  `begin()`/`end()` bracket the UI pass and manage GL state.
  `drawRect()` uses the 1×1 white texture. `drawText()` uses the glyph atlas.
  Flushes automatically on texture switch or batch full.
- Created `Screen.java` interface in `game/screen/` — `onShow()`, `onHide()`,
  `render(UiRenderer, w, h)`, `onMouseClick()`, `onKeyPress()`, `onCharTyped()`.
- Created `ScreenManager.java` in `game/screen/` — owns `UiShader`, `GlyphAtlas`,
  `UiRenderer`. `setScreen()` calls `onHide()`/`onShow()`. `renderActiveScreen()`
  calls `begin()`/`end()` around the active screen's `render()`. Input forwarding
  returns true if consumed.
- Created `MainMenuScreen.java` in `game/screen/` — three buttons: Singleplayer,
  Multiplayer (stub), Quit. Dark panel centered on screen, yellow title, hover
  highlight on buttons. Cursor position polled each frame for hover detection.
- Updated `GameLoop.java` — added `ScreenManager` field; registered three GLFW
  callbacks (key, mouse button, char) stored as fields to prevent GC; loop body
  branches on `hasActiveScreen()` to skip update/world-render when a screen is
  active; `cleanup()` frees callbacks and ScreenManager. Main menu shown at
  startup with cursor released; Singleplayer recaptures cursor and dismisses menu.

### Decisions Made
- `GL_CULL_FACE` must be disabled in `UiRenderer.begin()` and restored in `end()`.
  UI quads are wound clockwise in screen-space (Y-down), which OpenGL reads as
  back faces under the default CCW front-face convention. The world render is
  unaffected — cull face is always restored before the 3D pass runs.
- `begin()`/`end()` is the correct pattern for any render pass that needs different
  GL state. Each pass is fully self-contained — world render and UI render never
  interfere with each other's state.
- Full-screen menus (main menu) replace the world render entirely. Overlay screens
  (pause menu, inventory) will run after `render()` in the same frame — a two-line
  change in `GameLoop.loop()` deferred to when those screens are built (6B-6).
- Three GLFW callbacks stored as fields on `GameLoop` — same GC hazard as the
  framebuffer and refresh callbacks established in earlier phases.

### Problems Encountered
- `ShaderProgram.setUniform("uProjection", matrix)` — method name confirmed as
  `setUniform`, not `setUniformMatrix4f`. Corrected in `UiShader.setProjection()`.
- Shader resource not found — `UiShader` was passing `"shaders/ui.vert"` without
  a leading `/`. Fixed to `"/shaders/ui.vert"` matching the existing convention.
- Blank screen despite correct screen activation — root cause was `GL_CULL_FACE`
  remaining enabled during the UI pass, culling all quads as back faces.

### AI Assistance Notes
- Claude wrote all files except `UiRenderer.java`.
- Copilot generated `UiRenderer.java` from a structured prompt. Build succeeded
  on first compile attempt.
- The `GL_CULL_FACE` bug was diagnosed by Claude from the `UiRenderer` source after
  the blank screen was confirmed as a render-pass state issue, not a data issue.

### Lessons / Observations
- Any render pass that changes GL state must restore it. The `begin()`/`end()`
  pattern makes this explicit and auditable — it is not sufficient to set state
  once at init and assume it holds.
- UI quads wound in screen-space (Y-down) appear as back faces to OpenGL's default
  CCW convention. Either disable `GL_CULL_FACE` for UI or reverse winding — disabling
  is simpler and has no performance cost at this polygon count.
- AWT glyph rendering gives workable results immediately with no font file dependency.
  `GL_NEAREST` is essential — bilinear filtering blurs pixel-art-scale glyphs.

---

## Entry 040 — Phase 6B-4: World Selection Screen
**Date:** 24.03.2026
**Phase:** 6 — Foundation for extensibility

### What Was Done
- Created `WorldSelectScreen.java` in `game/screen/` — full world management UI.
  Scans `worlds/` for directories containing `world.dat`. Displays up to 6 worlds
  in a scrollable list. Four actions: Play (launch selected), New World (create),
  Delete (with confirmation prompt), Back (return to main menu).
- World creation accepts a name (letters/digits/hyphens/underscores, max 32 chars)
  and an optional seed (numeric, max 20 chars). Blank seed = random. Seed is passed
  through to `WorldMeta.loadOrCreate(Path, long)` on new worlds; existing worlds
  always keep their stored seed.
- Added `WorldMeta.loadOrCreate(Path worldDir, long forcedSeed)` overload —
  uses the forced seed when creating a new `world.dat`, delegates to the existing
  overload when the file already exists.
- Added `GameServer(Path, int, long)` constructor — passes `forcedSeed` to
  `WorldMeta.loadOrCreate`.
- Rewrote `GameLoop` server lifecycle: server no longer starts in `Main.java`.
  `launchWorld(String worldName, Long seed)` starts a daemon background thread
  that calls `GameServer`, `awaitReady()`, and `ClientNetworkManager.connect()`,
  then posts a `Runnable` to `pendingMainThreadAction` (AtomicReference). The GL
  thread drains this at the top of each loop iteration — the world-launch thread
  never touches GL.
- Added `returnToMainMenu()` to `GameLoop` — disconnects network, stops server,
  calls `clientWorld.reset()`, resets player and camera, releases cursor, shows
  main menu.
- Added `ClientWorld.reset()` — releases all GL mesh resources (`mesh.cleanup()`
  on each entry in the mesh map), clears all chunk data maps and pending queues,
  resets spawn position. Must be called on the GL thread.
- Simplified `Main.java` — now just `Blocks.bootstrap()` + username parse + `new GameLoop(...).run()`.
- `MainMenuScreen` "Singleplayer" button now pushes `WorldSelectScreen` instead of
  directly starting the game. Extracted `createMainMenu()` factory method in
  `GameLoop` so the Back button can return to the main menu without duplicating
  callback wiring.
- Added `WorldSelectScreen.refreshWorldList()` — scans with `Files.newDirectoryStream`,
  filters for `world.dat` presence, sorts alphabetically.
- Added `WorldSelectScreen.deleteDirectoryRecursive(Path)` — DFS delete avoiding
  `Files.walk` stream exception issues.
- Diagnostic console output (`FPS | UPS | Chunks`) now only prints when
  `serverChannel != null && !screenManager.hasActiveScreen()` — suppressed on menus
  where no world is loaded.

### Decisions Made
- **Callback pattern (Consumer/BiConsumer) for world launch.** `WorldSelectScreen`
  knows nothing about `GameServer` — it calls `onWorldSelected.accept(name, seed)`.
  `GameLoop` supplies the lambda that starts the server. Clean separation: the screen
  is a pure UI component, the loop owns lifecycle.
- **Background launch thread with AtomicReference pending action.** `awaitReady()`
  blocks for ~50–100ms until Netty binds the port. Blocking the GL thread would
  freeze the window. The `pendingMainThreadAction` single-slot queue is the same
  pattern used throughout the project for cross-thread handoff.
- **`seed = null` means random.** `WorldSelectScreen` passes `null` when the seed
  field is blank. `GameLoop.launchWorld()` branches on null to choose the constructor.
  Avoids magic sentinel values like `-1` or `Long.MIN_VALUE`.
- **World validity = `world.dat` presence.** A directory without `world.dat` is not
  listed. This correctly excludes partially-created directories and non-world folders
  that happen to be inside `worlds/`.

### Problems Encountered
- `StringIndexOutOfBoundsException` in `renderCreate` — `nameCaretPos` was not reset
  when entering `CREATE_NEW` mode, so a stale value from a previous open exceeded the
  (empty) string length. Fixed by resetting all caret and input state in `onShow()`
  and also in `handleListClick` when switching to `CREATE_NEW`.
- Wrong buttons triggering wrong actions — `handleListClick` was using the old
  single-row button coordinates (`btnRowX` / `totalW`) while `renderList` had been
  updated to a 2×2 grid. The render and click-handler must always compute positions
  with identical formulas. Fixed by making both use `btnW = (PANEL_W - 32 - BTN_GAP) / 2`
  and the same `row1Y` / `row2Y` locals.

### AI Assistance Notes
- Claude wrote all new files and modifications. Copilot fixed the `onCharTyped`
  argument order in `UiRenderer.drawText` (x, y, text vs text, x, y).

### Lessons / Observations
- Render and click-handler layout calculations must be kept in sync. The safest
  pattern is to extract button coordinates into local variables computed identically
  in both methods, or to extract a layout object. Drift between the two is the most
  common source of "wrong button fires" bugs.
- `AtomicReference<Runnable>` as a single-slot main-thread queue is a clean, minimal
  pattern for posting GL-thread work from background threads. For more than one
  concurrent pending action, switch to `ConcurrentLinkedQueue<Runnable>`.

---

## Entry 041 — Phase 6B-6: Pause Menu + UI Polish
**Date:** 24.03.2026
**Phase:** 6 — Foundation for extensibility

### What Was Done
- Added `Screen.isOverlay()` default method (returns false) — overlay screens render
  on top of the world instead of replacing it.
- Added `ScreenManager.isActiveScreenOverlay()` — returns true if active screen is
  an overlay.
- Updated `GameLoop.loop()` and `window.setRefreshCallback()` render branches:
  full-screen menus skip world render; overlay screens run world render first, then
  draw the screen on top.
- Created `PauseMenuScreen.java` in `game/screen/` — overlay (`isOverlay()` = true).
  Three buttons: Resume (recaptures cursor), Main Menu (calls `returnToMainMenu()`),
  Quit (closes window). Dark semi-transparent panel over the rendered world.
- Rewrote `GameLoop` Escape key handling: extracted `handleEscapeKey(long win, int key)`
  called from the GLFW key callback. In-game + cursor captured → open pause menu +
  release cursor. Overlay active → forward Escape to screen (Resume behavior). Full-screen
  menu active → forward Escape to screen (Back behavior). Escape no longer handled
  inside `update()` to avoid conflicts.
- Added `InputHandler.resetMouseDelta()` — snaps `lastMouseX/Y` to current cursor
  position. Called after every cursor recapture (`GLFW_CURSOR_DISABLED`) to discard
  movement accumulated during menu navigation. Without this, the camera jumps by the
  full cursor travel distance on the first frame after resuming.
- Added `UiRenderer.measureText(String)` — delegates to `GlyphAtlas.measureText()`.
  Returns actual rendered pixel width from AWT font metrics.
- Added `UiRenderer.drawCenteredText(float centerX, float y, String, ...)` — draws
  text horizontally centered around a pixel X coordinate.
- Fixed button text centering in `MainMenuScreen` and `WorldSelectScreen` — replaced
  all `label.length() * 8` and hardcoded `* 16` formulas with `r.measureText(label)`.
  Root cause: `length * constant` only works when the constant exactly matches the
  atlas advance width, which AWT does not guarantee for all glyphs.
- Fixed title centering: replaced `(sw - title.length() * 16) / 2` with
  `r.drawCenteredText(panelX + PANEL_W / 2.0f, ...)`.
- Added key-repeat forwarding in `GameLoop` key callback: `GLFW_REPEAT` events are
  now forwarded to `screenManager.onKeyPress()` in addition to `GLFW_PRESS`. Escape
  is excluded from repeat forwarding. This gives backspace and delete auto-repeat
  behavior in text input fields at the OS key-repeat rate with no extra code in screens.
- Improved `WorldSelectScreen` input fields:
  - Insert-mode caret: typed characters insert at `nameCaretPos` / `seedCaretPos`
    using substring split; characters after the caret shift right rather than being
    overwritten.
  - Arrow keys (Left/Right), Home, End: move caret without altering text.
  - Delete key: forward-delete (removes character after caret).
  - Mouse click → caret position: `handleCreateClick` computes caret from click X
    using `charPixelWidth` (cached from `r.measureText("X")` each render frame).
  - Tab key: switches active field between name and seed.
- Replaced string-interpolated `|` caret with a 2px wide `drawRect` caret drawn
  at the exact pixel position between characters. The `|` character occupies a full
  character cell, pushing subsequent text right; the rect caret does not.
- Added blinking caret: `caretBlinkTimer` accumulates `0.016f` per frame and toggles
  `caretVisible` every `CARET_BLINK_INTERVAL = 0.5f` seconds. Timer and visibility
  reset in `onShow()` so the caret is always visible when the screen first opens.

### Known Issue
- Caret blink speed is approximated at 60 FPS using a fixed `+0.016f` per frame.
  At other frame rates the blink interval drifts proportionally (faster at >60 FPS,
  slower at <60 FPS). Acceptable for a blink effect; exact timing would require
  passing real delta time into `Screen.render()`, which is a `Screen` interface
  change deferred until there is a stronger reason to do it.

### Decisions Made
- **Overlay vs full-screen menus.** The `isOverlay()` flag on `Screen` is the
  clean architectural seam. Adding a new overlay (inventory, hotbar, chat) requires
  only overriding one method — the loop branching handles the rest automatically.
- **`handleEscapeKey()` owns Escape globally.** Previously Escape was handled inside
  `update()`, which is skipped when a screen is active. Moving it to the key callback
  ensures it fires regardless of screen state and centralizes all Escape behavior in
  one place.
- **`resetMouseDelta()` call site is the recapture point.** The fix must be at the
  cursor recapture call (`glfwSetInputMode → CURSOR_DISABLED`), not inside
  `InputHandler.update()`, because the cursor position at capture time is the correct
  new baseline.
- **`charPixelWidth` cached from render, used in click handler.** The click handler
  needs the same advance width the renderer used. Measuring `r.measureText("X")` once
  per render frame and caching it guarantees the two are always in sync regardless of
  any future font changes.

### Problems Encountered
- Camera jump on pause resume — cursor traveled freely during menu; first frame after
  recapture computed a huge delta. Fixed by `resetMouseDelta()`.
- Text centering visually wrong even after `measureText` was added — investigation
  showed `MainMenuScreen.drawButton` still used `label.length() * 8` (half the real
  width). Fixed by replacing all hardcoded formulas with `r.measureText()`.
- Caret position off by ~2× when clicking input fields — `handleCreateClick` used
  the hardcoded `GLYPH_W = 16` constant instead of the real atlas advance width.
  Fixed by using `charPixelWidth` cached from the render pass.

### AI Assistance Notes
- Claude wrote all files. Copilot assisted with `ClientWorld.reset()` implementation
  (identifying the correct map/queue field names to clear).

### Lessons / Observations
- The render pass and the click handler are two halves of the same layout. Any pixel
  value computed in one must be reachable by the other — either as a shared constant,
  a shared local (if same method), or a cached field. Ad-hoc duplication always drifts.
- Overlay rendering is essentially free — the world was already being rendered. The
  only cost is the UI draw calls on top, which are negligible.
- Key repeat (`GLFW_REPEAT`) is easy to miss — GLFW documents it but the default
  callback setup only checks `GLFW_PRESS`. Adding it to the forwarding condition
  gives all screens free hold-to-repeat with zero per-screen code.

---

## Entry 042 — UI Theme System + 6B-5 Multiplayer Connect + Bug Fixes
**Date:** 24.03.2026
**Phase:** 6 — Foundation for extensibility

### What Was Done

#### UI Theme System (pre-6B-5 cleanup)
- Added `UiRenderer.lineHeight()` — delegates to `GlyphAtlas.lineHeight()`.
- Added int-color overloads to `UiRenderer`: `drawRect(x,y,w,h,int color)`,
  `drawText(x,y,String,int color)`, `drawCenteredText(cx,y,String,int color)`.
  Colors packed as `0xRRGGBBAA`. Float overloads preserved.
- Created `UiTheme.java` in `engine/ui/` — abstract base class. Holds all named
  color fields (`colBackground`, `colPanel`, `colButton`, `colButtonHover`,
  `colDangerButton`, `colText`, `colInputBg`, `colListItem`, `colOverlayDim`, etc.)
  as `protected int` fields (packed RGBA). Owns a `UiRenderer` reference. Exposes
  compound draw helpers: `drawBackground`, `drawPanel`, `drawButton`,
  `drawDangerButton`, `drawInputField` (with integrated caret), `drawListItem`,
  `drawTitle`, `drawLabel`, `drawDimLabel`, `drawWarnLabel`, `drawOverlayDim`.
  Forwards `measureText()` and `lineHeight()`. Draw helpers are instance methods —
  subclasses can override shape/layout behavior, not just colors.
- Created `DarkTheme.java` — default theme. Deep navy panel, grey-blue buttons,
  red danger buttons, gold title text.
- Created `LightTheme.java` — alternate theme. Light grey background, muted
  blue-grey buttons, deep blue title text.
- Changed `Screen.render()` signature from `(UiRenderer r, int sw, int sh)` to
  `(UiTheme theme, int sw, int sh)`.
- Updated `ScreenManager` — constructs `DarkTheme` wrapping `UiRenderer`. Exposes
  `setTheme(UiTheme)` and `getTheme()`. `renderActiveScreen()` calls
  `activeTheme.begin()/end()` and passes `activeTheme` to `render()`.
- Refactored `MainMenuScreen`, `WorldSelectScreen`, `PauseMenuScreen` — all color
  constants removed, raw `r.drawRect/drawText` calls replaced with `theme.*` helpers.

#### 6B-5: Multiplayer Connect Screen
- Created `MultiplayerConnectScreen.java` in `game/screen/` — two input fields
  (host pre-filled `localhost`, port pre-filled `24463`), Connect and Back buttons,
  status line for connecting state and error messages.
- Input handling: Tab switches field focus, Enter triggers connect, arrow keys/
  Home/End/Backspace/Delete all work. Port field accepts digits only.
- `ConnectHandler` functional interface — `GameLoop` supplies the implementation,
  screen knows nothing about networking.
- `AtomicBoolean cancelledFlag` — set in `onHide()` if the player clicks Back
  during a connection attempt. Background thread checks flag before posting any
  main-thread action and cleans up the partial connection silently.
- `connecting` boolean gates Connect button and all input — prevents spam.
  Reset in `onShow()` for a clean slate on every visit.
- Added `connectToMultiplayer(host, port, cancelledFlag, onFailure)` to `GameLoop`
  — daemon thread, no embedded server started. On success: stores `activeNetwork`,
  sets `serverChannel`, posts GL-thread action to capture cursor and dismiss screen.
  On failure: posts `onFailure` callback with a friendly error string.
- Added `friendlyErrorMessage(Exception)` to `GameLoop` — maps raw Netty exception
  messages to short human-readable strings ("Connection refused.", "Connection timed
  out.", "Unknown host.", etc.).
- Added `onMultiplayer` callback to `MainMenuScreen` — replaces the stub println.
- Added `CONNECT_TIMEOUT_MS = 5000` to `ClientNetworkManager` —
  `ChannelOption.CONNECT_TIMEOUT_MILLIS` on the Bootstrap.
- `returnToMainMenu()` already handles multiplayer correctly — `activeServer` is
  null so the embedded server stop is skipped; `activeNetwork.disconnect()` closes
  the external connection cleanly.

#### Bug Fix: Pause menu freezing world updates
- `GameLoop.loop()` previously skipped `clientWorld.update()` entirely whenever
  `hasActiveScreen()` was true, including overlay screens (pause menu).
- `clientWorld.update()` drains `pendingBlockChanges` and `pendingPlayerEvents`
  queues written by Netty I/O threads — skipping it caused block changes and
  remote player moves to queue up and only apply on resume.
- Fix: added `isActiveScreenOverlay()` branch — overlay screens run
  `clientWorld.update()` but skip player input. Full-screen menus still skip
  everything.

#### Bug Fix: Singleplayer port clash hanging forever
- When `ServerNetworkManager.start()` failed to bind the port, it threw before
  reaching `readyLatch.countDown()`. `server.awaitReady()` in the launch thread
  blocked indefinitely — the world select screen stayed stuck on "Launching...".
- Fix: wrapped the bind call in try/catch inside `ServerNetworkManager.start()`.
  On failure, stores the exception via `server.setBindError(e)` and calls
  `readyLatch.countDown()` so `awaitReady()` always unblocks.
- Added `bindError` volatile field + `getBindError()`/`setBindError()` to
  `GameServer`.
- `launchWorld()` checks `server.getBindError()` after `awaitReady()` and throws
  with a human-readable message if set.
- `WorldSelectScreen` gained `statusMessage` field and `setStatusMessage(String)`
  setter. `renderLaunching()` shows the error in warn color with a "Back to Menu"
  button when `statusMessage` is non-empty. `onMouseClick` in `LAUNCHING` mode
  handles the Back button via `onBack.run()`. `statusMessage` reset in `onShow()`.
- `launchWorld()` now accepts `Consumer<String> onFailure` — posts error to
  `WorldSelectScreen.setStatusMessage` via `pendingMainThreadAction`.
- `createMainMenu()` uses `WorldSelectScreen[] ref` single-element array to
  capture the screen reference before construction for the error callback wiring.

### TODO — Known Issue: Chunk Loading Wave Pattern
When a second client connects to a multiplayer server, chunks load in a wave from
left to right (HashMap iteration order of loaded chunk positions) rather than
expanding outward from the spawn point. Spawn chunks arrive late, causing the
player to fall through the ground before they load.

**Root cause:** `ServerWorld` iterates chunk positions in `HashMap` order when
deciding what to stream to a new player. No distance-from-spawn sort is applied.

**Correct fix (Option A):** Sort the per-player pending chunk send queue by
distance from the player's current position in `ServerWorld`. Spawn chunks stream
first, ground exists before the player is subject to gravity.

**Safety net (Option B):** Hold the player in gravity-disabled floating state in
`ClientWorld`/`GameLoop` until the chunk at spawn is confirmed meshed.

Option A fixes the root cause and the visual wave. Option B is a fallback.
Deferred to the next session — do this before any other work.

### Decisions Made
- `UiTheme` is an abstract class, not an interface, so draw helpers can share
  implementation. Color fields are `protected` so subclasses can override
  individual values without extending the draw behavior.
- Theme swap is a single `ScreenManager.setTheme()` call — no GL work, no
  resource recreation. Takes effect on the next frame.
- `ThemeRegistry` deferred to Phase 7 (modding). `ScreenManager` holds the active
  theme directly for now.
- `connectToMultiplayer` keeps `activeServer = null` — `returnToMainMenu()` already
  null-checks before stopping the server. No multiplayer-specific teardown path.
- `WorldSelectScreen[] ref` lambda capture trick — standard Java workaround for
  capturing a not-yet-assigned variable. Kept in `createMainMenu()` to avoid
  adding a `setLaunchCallback()` setter to `WorldSelectScreen`.

### Problems Encountered
- `MainMenuScreen` constructor call in `createMainMenu()` reported "undefined
  constructor" after adding the `onMultiplayer` parameter. Root cause: nested
  lambda type inference failure — Java reported the outer constructor as undefined
  instead of the actual type error inside a nested lambda. Fixed by extracting each
  lambda into an explicitly typed `Runnable` local variable.

### AI Assistance Notes
- Claude wrote all new files and modifications.
- Copilot generated `LightTheme.java` and refactored the three existing screens to
  use `UiTheme`.

### Lessons / Observations
- When Java reports "constructor X is undefined" and the constructor clearly exists,
  the real error is almost always a type mismatch inside a nested lambda argument.
  Extract lambdas to typed locals to surface the actual error.
- `AtomicBoolean cancelledFlag` is the right primitive for "background thread should
  abort" — single writer (GL thread via `onHide`), single reader (background thread).
  No lock needed.
- A `CountDownLatch` that never counts down is an infinite hang with no diagnostic.
  Any code path that can prevent the countDown must have a catch that still calls it.

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