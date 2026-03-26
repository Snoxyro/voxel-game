# Development Log

A chronological record of progress, decisions, problems encountered, and lessons
learned. Includes honest notes on AI assistance — what worked, what didn't.

## CURRENT STATE
- **Phase:** 6D (next)
- **Last completed:** 6C — Lighting + Day/Night Cycle
- **Active packages:** `game/`, `common/world/` (entity system coming in 6D)

> Past phases (Phase 0–5) history is archived in **DEVLOG_ARCHIVE.md**. Do not read it by default.

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

## Entry 043 — Multiplayer Bug Fix: Chunk Load Order (Nearest-First Streaming)
**Date:** 24.03.2026
**Phase:** 6 — Foundation for extensibility (6B)

### What Was Done
- Fixed chunk streaming wave pattern on second client join. Root cause: `ServerWorld.streamChunksToPlayer`
  iterated the server's `HashSet<ChunkPos>` in undefined hash-table order. Spawn
  chunks arrived in arbitrary sequence, causing the player to fall through the ground
  before the terrain underfoot loaded.
- Replaced the unordered iteration with a sort step: unsent chunks are collected into
  a `List`, sorted by squared chunk-distance from the player's current chunk position,
  then streamed nearest-first. Spawn chunk scores 0 and always goes first.
- Added `chunkDistSq(dx, dy, dz)` private static helper for the sort (later removed
  when direction bias was added — see Entry 044).

### Decisions Made
- Sort on every call to `streamChunksToPlayer`, not just on first join. The player
  moves between ticks; the nearest-first order stays correct dynamically.
- Squared distance used throughout — avoids `Math.sqrt()` with identical ordering.

### Problems Encountered
- None. Fix confirmed working on first test.

### AI Assistance Notes
- Claude diagnosed root cause and wrote the fix.

### Lessons / Observations
- A `HashSet` iteration order that "happens to work" in singleplayer falls apart in
  multiplayer where the set is populated from a different starting point per player.
  Whenever order matters, sort explicitly — never rely on map/set iteration order.

---

## Entry 044 — Multiplayer Bug Fix: Block Placement Inside Player Hitboxes
**Date:** 24.03.2026
**Phase:** 6 — Foundation for extensibility (6B)

### What Was Done
- Added server-side hitbox check to `ServerWorld.applyBlockPlace`. Before applying
  a block placement, the server now tests the target block's unit cube against every
  connected player's AABB. If any player overlaps, the placement is silently rejected
  — no `BlockChangePacket` is broadcast.
- Added `isBlockInsideAnyPlayer(worldX, worldY, worldZ)` private helper to
  `ServerWorld`. Player AABB mirrors `PhysicsBody` constants: 0.6 × 1.8 × 0.6,
  origin at feet (px, py, pz).
- Standard AABB overlap test: two boxes overlap only when all three axes overlap
  simultaneously (`blockMax > playerMin && blockMin < playerMax` on X, Y, Z).

### Decisions Made
- Rejection is silent (no error packet to client). The client's local guard already
  prevents the local player from placing inside themselves; the server guard covers
  other players. Sending an explicit rejection packet is unnecessary complexity at
  this stage.
- Threading note preserved: called from Netty I/O thread, reads `players` ArrayList.
  Same race as `broadcastBlockChange` — documented as acceptable until block actions
  are queued through the tick thread.

### Problems Encountered
- None.

### AI Assistance Notes
- Claude wrote the fix.

---

## Entry 045 — Direction-Biased Chunk Streaming (Server-Side)
**Date:** 24.03.2026
**Phase:** 6 — Foundation for extensibility (6B)

### What Was Done
- Restored look-direction bias to chunk streaming in multiplayer. Previously `ServerWorld`
  passed `(0, 0, 0)` as direction to `World.update()` — generation queue bias was
  dead in multiplayer from the start.
- Added `yaw` and `pitch` fields (volatile) to `PlayerSession`. Updated
  `updatePosition()` to accept and store yaw/pitch. Added `getYaw()` / `getPitch()`.
- Expanded `PendingMove` record to carry `yaw` and `pitch`.
- Updated `queuePlayerMove()` signature to accept yaw and pitch. Updated
  `ClientHandler` to pass `p.yaw()` and `p.pitch()` from `PlayerMoveSBPacket`.
- `ServerWorld.tick()` now calls `yawPitchToDir(player.getYaw(), player.getPitch())`
  per player and passes a real direction into `World.ViewerInfo`.
- Applied the same 75/25 direction-biased sort to `streamChunksToPlayer` — chunks
  in the player's look direction stream to the client first, background chunks still
  guaranteed via the distance component.
- Added `yawPitchToDir(float yaw, float pitch)` private static helper: converts
  degrees to a normalised XYZ direction matching `GameLoop`'s look vector convention.
- Removed `chunkDistSq` helper (now dead — replaced by the bias formula).

### Decisions Made
- Direction bias formula: `score = distSq * (1.0 - dot * 0.5)`. Multiplier range
  0.5–1.5 — near chunks always beat far chunks regardless of look direction. A chunk
  at distance 1 worst-case (1.5) still beats a chunk at distance 3 best-case (4.5).
- Initial yaw/pitch default of 0 on join produces direction `(1, 0, 0)`. Spawn
  chunks are perpendicular (dot ≈ 0) so they sort by pure distance — correct behaviour.

### Problems Encountered
- None. Confirmed working — chunks in look direction load visibly faster.

### AI Assistance Notes
- Claude diagnosed that direction bias was silently broken in multiplayer (hardcoded
  zero direction) and wrote all changes.

### Lessons / Observations
- Data already on the wire (`PlayerMoveSBPacket` has yaw/pitch since Phase 5D) but
  discarded by the receiving end. Check what packets already carry before adding new
  fields — the data is often already there.

---

## Entry 046 — Multiplayer Bug Fix: Per-Player Independent Chunk Streaming
**Date:** 24.03.2026
**Phase:** 6 — Foundation for extensibility (6B)

### What Was Done
- Fixed bug where Player 2 could only receive chunks already loaded for Player 1.
  Root cause: `World.update()` accepted a single viewer position — only `players.get(0)`
  drove chunk generation. Player 2's area was never scheduled.
- Refactored `World.update()` to accept `List<World.ViewerInfo>` — one entry per
  connected player. Added public inner record `ViewerInfo(x, y, z, dirX, dirY, dirZ)`.
- Updated `scheduleNeededChunks`, `drainPendingChunks`, `unloadDistantChunks`, and
  `tickGenerationQueue` to operate on a viewer list:
  - Schedule: scans around every viewer's center chunk.
  - Drain: discards results only if out of range of ALL viewers.
  - Unload: keeps a chunk if ANY viewer is in range.
  - Generation queue sort: finds nearest viewer per candidate chunk for direction/distance scoring.
- Schedule guard updated from three `lastScheduledCX/Y/Z` ints to a
  `Set<ChunkPos> lastViewerChunks` — reschedules only when the set of viewer center
  chunks changes.
- Made `RENDER_DISTANCE_H` and `RENDER_DISTANCE_V` `public static final` so
  `ServerWorld` can use them for the visibility range check.
- Fixed per-player streaming unload condition in `streamChunksToPlayer`: a chunk is
  now unloaded from a player if the server dropped it OR if it left that player's
  personal render distance. Previously, Player 2's chunks stayed on Player 1's client
  because the server kept them loaded for Player 2 — the personal range check fixes this.
- `ServerWorld.tick()` builds a `List<ViewerInfo>` from all connected players each
  tick and passes it to `world.update()`.

### Decisions Made
- Copilot handled the `World.java` multi-viewer refactor via a structured prompt.
  Claude wrote the `ServerWorld` streaming and unload changes.
- "Union load, intersection unload" rule: load for any viewer, unload when no viewer
  needs it. Simple, correct, no edge cases.

### Problems Encountered
- None on first test after applying both sets of changes.

### AI Assistance Notes
- Claude diagnosed root cause, designed the fix, wrote the `ServerWorld` changes,
  and provided the Copilot prompt for the `World.java` refactor.
- Copilot executed the `World.java` multi-viewer refactor.

### Lessons / Observations
- `World.update()` taking a single position was flagged in Phase 4 as a known
  limitation ("Phase 5+: `update(Collection<Vector3f>)`"). The fix was deferred but
  the design was always clear — composition over multiple viewers is the right model.

---

## Entry 047 — Multiplayer Bug Fix: Dynamic Player Visibility (Spawn/Despawn by Range)
**Date:** 24.03.2026
**Phase:** 6 — Foundation for extensibility (6B)

### What Was Done
- Fixed ghost player model bug: when two players moved out of each other's render
  distance, the remote player model froze at last known position and never disappeared.
  Root cause: `PlayerMoveCBPacket` was range-gated (Entry 046) but `RemotePlayer`
  objects were never removed from `ClientWorld.remotePlayers`.
- Added `visiblePlayerIds` (`HashSet<Integer>`) to `PlayerSession`. Three methods:
  `isPlayerVisible(id)`, `addVisiblePlayer(id)`, `removeVisiblePlayer(id)`.
- Replaced the static spawn-on-connect / despawn-on-disconnect announcements in
  `ServerWorld.tick()` with a per-tick visibility management loop. For every ordered
  pair of players:
  - In range + not visible → send `PlayerSpawnPacket`, call `addVisiblePlayer`
  - Out of range + visible → send `PlayerDespawnPacket`, call `removeVisiblePlayer`
  - In range + already visible → send `PlayerMoveCBPacket` (position update)
- Disconnect path updated: `PlayerDespawnPacket` only sent to sessions that had the
  disconnecting player visible; `removeVisiblePlayer` called on those sessions.
- Player join path simplified: new player just added to the list. Visibility loop
  handles the spawn announcement on the first tick where they are in range.

### Decisions Made
- Visibility and position updates unified into one loop — three mutually exclusive
  cases per player pair, cleanly handled by if/else if/else. No separate broadcast
  loops.
- Range check uses horizontal distance only (X/Z squared), matching `World`'s
  horizontal render distance constant. Y distance not included — same convention as
  chunk streaming.
- `RENDER_DISTANCE_H * Chunk.SIZE` gives the visibility radius in world units.
  Squared for the comparison to avoid sqrt.

### Problems Encountered
- None. Both bugs (seeing other player's chunks, frozen ghost model) confirmed fixed.

### AI Assistance Notes
- Claude diagnosed both bugs, designed the unified visibility loop, and wrote all changes.

### Lessons / Observations
- Spawn/despawn must be symmetrical events tied to the same condition (range check),
  not to connection lifecycle. Tying them to connect/disconnect is correct for the
  disconnection case but wrong for players who are connected but far apart.
- The client is now fully server-driven for player model lifecycle: it renders exactly
  what the server tells it to render. No client-side filtering needed.

---

## Entry 048 — Phase 6B-7 Roadmap: Settings System
**Date:** 24.03.2026
**Phase:** 6 — Foundation for extensibility (6B)

### What Was Decided

6B-7 expands from "settings stub" to a full settings system. This entry documents
the complete design so any future Claude session can resume without losing context.
No code was written yet.

---

### Scope

Settings are accessible from both the main menu and the pause menu while in-game.
Some settings are locked while a session is active (e.g. username cannot change
while connected to a server). Settings persist to `settings.properties` in the
working directory alongside the `worlds/` folder.

---

### Architecture

**`GameSettings.java`** — `game/` package.
Owned by `GameLoop`, passed via constructor injection to every screen that needs it.
Never a static singleton — mods in Phase 7 will have their own `GameSettings`
instances pointing to different `.properties` files. Uses `java.util.Properties` for
persistence. Typed getters/setters per setting. `load()` fills all fields from file,
falling back to defaults for any missing key. `save()` writes all fields atomically.
Methods: `load(Path)`, `save(Path)`, typed getters/setters per field.

**`KeyBindings.java`** — `game/` package (GLFW-only, client-side).
Wraps an `EnumMap<Action, Integer>` mapping every bindable `Action` to a GLFW key or
mouse button code. `getKey(Action)` replaces all hardcoded `GLFW.GLFW_KEY_*` literals
in `GameLoop`. Unbinding is represented as `GLFW_KEY_UNKNOWN` (-1) — the action simply
never fires. Serialized as `keybind.MOVE_FORWARD=87` etc. in `settings.properties`.

**`Action.java`** — `game/` package. Enum of all bindable actions:
`MOVE_FORWARD`, `MOVE_BACKWARD`, `MOVE_LEFT`, `MOVE_RIGHT`, `JUMP`,
`BREAK_BLOCK`, `PLACE_BLOCK`, `TOGGLE_FREECAM`, `OPEN_CHAT` (stub — chat not yet built).

**`SettingsScreen.java`** — `game/screen/`. Tabbed layout.
Tabs: `GAMEPLAY`, `GRAPHICS`, `DISPLAY`, `CONTROLS`, `KEYBINDS`, `SOUND`.
Each tab renders a scrollable list of setting rows. Every row has:
- Label
- Widget (see widget types below)
- "Reset to default" button (small, right-aligned per row)
- Dirty highlight: row background tints yellow-orange if value differs from saved state
- For keybind rows: conflict highlight (yellow text + warning icon) if two actions share
  the same key

Tab header shows a warning badge (⚠) if any row in that tab has a conflict or
validation error. Currently only `KEYBINDS` can produce this state.

Save / Cancel buttons at the bottom of the screen, always visible regardless of
active tab. Save writes to disk and applies live settings. Cancel discards all
pending changes and restores last-saved values. Both dismiss the screen.

**Widget types implemented in SettingsScreen:**
- Text field (username)
- Slider with min/max/step (render distance, FOV, mouse sensitivity)
- Toggle button / on-off (VSync)
- Dropdown (UI theme, window mode) — rendered as a button that cycles through options
  since no full dropdown widget exists yet; full dropdown deferred until a widget is built
- Key selector (all keybinds) — click to enter listening mode ("Press any key…"),
  press a key to bind, press Escape or click X button to unbind (sets to UNKNOWN)

**Settings locked while a session is active:**
- Username (Gameplay tab): rendered as a disabled/greyed text field with a label
  "(active session)" when `GameLoop.isSessionActive()` is true.

---

### Full Settings Inventory

| Tab       | Setting          | Widget                      | Live? | Locked in session? |
|-----------|------------------|-----------------------------|-------|--------------------|
| Gameplay  | Username         | Text field                  | No    | Yes                |
| Graphics  | Render Distance  | Slider (2–32)               | Yes   | No                 |
| Graphics  | FOV              | Slider (50–110°)            | Yes   | No                 |
| Graphics  | UI Theme         | Dropdown (Dark / Light)     | Yes   | No                 |
| Display   | VSync            | Toggle                      | Yes   | No                 |
| Display   | Window Mode      | Dropdown (Windowed / Fullscreen / Borderless) | Yes | No     |
| Display   | Resolution       | Dropdown (monitor modes)    | Defer | No                 |
| Controls  | Mouse Sensitivity| Slider (0.01–2.0)           | Yes   | No                 |
| Keybinds  | Move Forward     | Key selector                | —     | No                 |
| Keybinds  | Move Backward    | Key selector                | —     | No                 |
| Keybinds  | Move Left        | Key selector                | —     | No                 |
| Keybinds  | Move Right       | Key selector                | —     | No                 |
| Keybinds  | Jump             | Key selector                | —     | No                 |
| Keybinds  | Break Block      | Key selector                | —     | No                 |
| Keybinds  | Place Block      | Key selector                | —     | No                 |
| Keybinds  | Freecam Toggle   | Key selector                | —     | No                 |
| Keybinds  | Open Chat        | Key selector (stub)         | —     | No                 |
| Sound     | (stub — no audio yet) | —                      | —     | —                  |

Resolution dropdown deferred — requires enumerating monitor video modes via
`glfwGetVideoModes` and a real dropdown widget neither of which exists yet.

---

### Live Render Distance — Delta Approach

Decreasing render distance: explicit unload sweep — iterate loaded chunks, unload any
whose Chebyshev distance from every viewer exceeds the new radius. `World` already has
the unload path; it needs to be triggerable on-demand (not just from the scheduler).
Increasing render distance: automatic — next `scheduleNeededChunks` tick picks up the
new outer ring with no extra code.
Result: same behavior as Minecraft's slider without a full reload.

---

### Delta Time Fix (bundled with 6B-7)

`Screen.render()` signature changes from `(UiTheme, int, int)` to
`(UiTheme, float deltaTime, int, int)`. `GameLoop` computes `deltaTime` for physics
already — same value forwarded to `screenManager.renderActiveScreen(...)`.
All `caretBlinkTimer += 0.016f` hardcoded lines replaced with `+= deltaTime`.
Fixes caret blink speed drift at non-60 FPS frame rates.
This is a one-line change per screen but touches every screen class and the `Screen`
interface.

---

### VSync Fix (bundled with 6B-7)

`glfwSwapInterval(vsync ? 1 : 0)` called immediately on save when VSync setting
changes. This is why the FPS is currently locked — `glfwSwapInterval(1)` is
called at init with no way to change it. Once the setting exists, the default will
be stored in `settings.properties` and can be overridden.

---

### Deferred — Server Global Chat (TODO)

Not part of 6B-7. Recorded here for future planning.

- Keybind: `Action.OPEN_CHAT` (Enter) — stub registered in `KeyBindings` now,
  screen implementation deferred.
- Rendering: semi-transparent overlay at bottom of screen. `ChatScreen` extends
  overlay — world still visible behind it.
- History: circular buffer of last N messages, scrollable with mouse wheel.
- Packets: `ChatMessageSBPacket` (client→server: message string),
  `ChatMessageCBPacket` (server→all: username + message + timestamp).
- Commands: `/command` prefix detection deferred — needs a `CommandDispatcher`
  pattern, which is a Phase 7 modding concern.

---

### Implementation Order

1. Delta time fix — `Screen` interface change, all existing screens updated.
2. `Action.java` + `KeyBindings.java` — data types, no wiring yet.
3. `GameSettings.java` — load/save, all fields, defaults.
4. `GameLoop` wiring — replace all hardcoded constants with settings reads,
   VSync call, window mode logic, `isSessionActive()` helper.
5. `World` render distance — make `RENDER_DISTANCE_H` live-readable, add
   on-demand unload sweep.
6. `SettingsScreen.java` — full tabbed UI.
7. Wire `SettingsScreen` into `MainMenuScreen` and `PauseMenuScreen`.

Steps 1–5 are data and engine changes. Step 6 is the large UI build.
Step 6 will likely be delegated to Copilot with a structured prompt given the
mechanical but voluminous nature of the widget rendering code.

---

### Decisions Made
- `GameSettings` owned by `GameLoop`, passed via constructor — not a static singleton.
  Mod settings in Phase 7 will use separate `GameSettings` instances per mod.
- `KeyBindings` lives in `game/` — references GLFW constants, client-only.
- Unbinding represented as `GLFW_KEY_UNKNOWN` (-1). An unbound action simply never
  fires — no special-case code needed in `GameLoop`.
- Duplicate keybind detection: warn on all conflicting rows, allow save anyway.
  Same behavior as Minecraft — lets players reassign a chain of keys without being
  blocked mid-way.
- Tab-level warning badge only wired to `KEYBINDS`. Other tabs have no invalid states.
  Infrastructure supports it for future mod settings with validation.
- Resolution picker deferred until a dropdown widget exists.
- VSync default: enabled. Stored in `settings.properties`, overridable.
- Window mode default: Windowed.

---

## Entry 049 — Phase 6B-7 (Partial): Settings Data Layer
**Date:** 24.03.2026
**Phase:** 6 — Foundation for extensibility (6B)

### What Was Done
- Added `deltaTime` parameter to `Screen.render()` interface — signature is now
  `render(UiTheme, float deltaTime, int w, int h)`. All screen implementations
  updated. `ScreenManager.renderActiveScreen()` accepts and forwards `deltaTime`.
- Added `lastFrameTime` (long, nanoTime) and `lastDeltaTime` (float) fields to
  `GameLoop`. Delta time computed at the top of each loop iteration as
  `(now - lastFrameTime) / 1_000_000_000f`, clamped to 100ms. Stored in
  `lastDeltaTime` for the window refresh callback (fires during OS drag, outside
  the loop). Fixes caret blink drift — all `caretBlinkTimer += 0.016f` replaced
  with `+= deltaTime`.
- Created `Action.java` in `game/` — enum of all bindable actions: `MOVE_FORWARD`,
  `MOVE_BACKWARD`, `MOVE_LEFT`, `MOVE_RIGHT`, `JUMP`, `BREAK_BLOCK`, `PLACE_BLOCK`,
  `TOGGLE_FREECAM`, `OPEN_CHAT` (stub). Each entry has a `displayName` for the UI.
- Created `KeyBindings.java` in `game/` — `EnumMap<Action, Integer>` mapping each
  action to a GLFW key or encoded mouse button code. Mouse buttons offset by
  `MOUSE_BUTTON_OFFSET = 1000` to avoid collision with key codes. `UNBOUND = -1`
  sentinel — unbound actions never fire. Default bindings match previous hardcoded
  constants. `saveTo(Properties)` / `loadFrom(Properties)` for persistence.
  `displayName(int)` returns human-readable key name for the settings UI.
- Created `GameSettings.java` in `game/` — persistent settings backed by
  `settings.properties`. Owned by `GameLoop`, passed via constructor injection.
  Fields: username, renderDistance (2–32), fov (50–110), mouseSensitivity,
  theme, vsync, windowMode. `load()` reads from file with clamped fallbacks.
  `save()` writes atomically via `.tmp` rename. `WindowMode` enum: WINDOWED,
  FULLSCREEN, BORDERLESS. `KeyBindings` serialized into the same file.
- Refactored `InputHandler` — replaced hardcoded left/right mouse button fields
  with `boolean[8]` arrays covering all GLFW mouse buttons. Added
  `wasMouseButtonJustPressed(int)` and `isMouseButtonDown(int)`. Kept
  `wasMouseLeftClicked()` / `wasMouseRightClicked()` as deprecated delegates.
- Refactored `Camera` — `FOV` changed from `private static final float` to a
  mutable instance field. Added `setFov(int degrees)`. `getProjectionMatrix()`
  uses the instance field.
- Refactored `GameLoop` constructor — replaced `String username` parameter with
  `GameSettings settings`. `Window` still constructed internally. Added
  `isSessionActive()` helper (returns `serverChannel != null`). Added
  `applyWindowMode(WindowMode)` private helper for FULLSCREEN / BORDERLESS /
  WINDOWED switching via `glfwSetWindowMonitor` and `glfwSetWindowAttrib`.
- `GameLoop.init()` now calls `glfwSwapInterval` from settings (fixes locked FPS),
  `applyWindowMode` from settings, and `camera.setFov` from settings.
- All hardcoded GLFW key/mouse constants in `GameLoop.update()` replaced with
  `isActionDown(Action)` / `wasActionJustPressed(Action)` helpers that read from
  `settings.getKeyBindings()`. Mouse sensitivity reads from `settings.getMouseSensitivity()`.
- `Main.java` — removed `--username` CLI arg parsing. Constructs `GameSettings`,
  calls `settings.load()`, passes to `GameLoop`.

### Decisions Made
- `GameSettings` owned by `GameLoop`, not static. Phase 7 mod settings will use
  separate instances pointing to different property files — same class, zero refactor.
- `UNBOUND` actions return false from both `isActionDown` and `wasActionJustPressed`
  — no special-case needed anywhere in the input path.
- VSync default is `true` in `GameSettings`. `Window.init()` still calls
  `glfwSwapInterval(1)` as before — `GameLoop.init()` immediately overrides it
  from settings after `window.init()` returns. Net effect: same behavior until
  the user changes the setting.

### Problems Encountered
- `GameLoop` constructor rewrite dropped the `this.window = new Window(...)` line,
  causing "blank final field window may not have been initialized" compile error.
  Fixed by restoring the Window construction in the new constructor body.
- Physics mode camera froze after freecam refactor — `camera.getPosition().set(
  player.getEyePosition())` was accidentally omitted from the physics `else` branch
  when rewriting the movement section. Physics still ran (player moved) but camera
  never followed. Fixed by restoring the line.

### AI Assistance Notes
- Claude wrote all changes across all six files.

### Remaining for 6B-7
- Step 5: Live render distance — `World.RENDER_DISTANCE_H` driven by `GameSettings`,
  on-demand unload sweep when distance decreases.
- Step 6: `SettingsScreen.java` — full tabbed UI (Gameplay, Graphics, Display,
  Controls, Keybinds, Sound stub).
- Step 7: Wire `SettingsScreen` into `MainMenuScreen` and `PauseMenuScreen`.

---

## Entry 051 — Settings Screen Polish
**Date:** 25.03.2026
**Phase:** 6 — Foundation for extensibility (6B)

### What Was Done
- Fixed mouse sensitivity scale: `DEFAULT_MOUSE_SENS` changed from `0.1f` to `1.0f`.
  Sensitivity is now stored as a human-readable multiplier (1.0 = normal, 2.0 = 2× faster).
  A base factor of `0.1f` is applied at the camera rotation call site in `GameLoop` —
  the stored value is never the raw camera multiplier. Old `settings.properties` must be
  deleted on upgrade as the stored `0.1` would read as 10× slower than intended.
- Fixed slider keyboard adjustment: added `focusedSlider` int field to `SettingsScreen`.
  Clicking a slider sets focus; arrow Left/Right nudge the value by one step (1 for
  integer sliders, 0.05f for sensitivity). Focus clears on tab switch or any non-arrow key.
  Focused slider draws a subtle highlight border and a brighter thumb.
- Fixed conflict row height: `ROW_H + 16` replaced with `ROW_H + theme.lineHeight() + 8`
  so the warning text never overlaps the next row regardless of font size.
- Fixed key display names: expanded `KeyBindings.displayName()` switch to cover F1–F12,
  Home, End, Page Up/Down, Insert, Print Screen, Pause, Caps Lock, Num Lock, Super,
  and all numpad keys (Num 0–9, Num Enter). Previously these fell through to `"Key N"`.
- Fixed username overflow: `(session active)` label moved from right of the widget
  (outside the panel) to below the label text inside the label column. Username row
  height expands by `lineHeight + 2` when a session is active to accommodate the note.
- Fixed `wasActionJustPressed` for keyboard bindings: added `Set<Integer> justPressedKeys`
  to `InputHandler`, populated via `onKeyPressed(int)` from the GLFW key callback
  (`GLFW_PRESS` only, not repeat). `clearJustPressed()` called at end of each `update()`
  tick. `wasActionJustPressed()` in `GameLoop` now calls `inputHandler.wasKeyJustPressed()`
  for keyboard actions instead of returning `false`. Break Block and Place Block now work
  correctly when rebound to keyboard keys.
- Fixed resume-click bleeding into block break: added `InputHandler.consumeMouseClick()` —
  synchronises `lastMouseButtons` to current physical state and clears all just-pressed
  flags. Called in the Resume callback of `createPauseMenu()`. Without this, the Resume
  click registered as `mouseButtonJustPressed[0] = true` on the next `update()` tick
  because `lastMouseButtons` still showed the button as "up" from last frame.
- Removed `listeningArmed` flag from `SettingsScreen`: the flag required 3 clicks to bind
  a key via mouse (click to select row, arm flag, click to bind) instead of 2. The extra
  step was unnecessary — the activating click sets `listeningForAction`, and the next
  click immediately binds.

### Decisions Made
- Mouse sensitivity base factor (0.1f) lives at the camera call site, not in `GameSettings`.
  This keeps the stored value human-readable and means the display in the slider always
  matches player expectation (1.0 = the feel the game shipped with).
- `consumeMouseClick()` is the correct pattern for any UI-to-gameplay transition that
  involves a mouse button. Document it; future screens (inventory, chat) must call it
  on any "click to dismiss and return to game" path.

### Problems Encountered
- None. All fixes applied and verified visually.

### AI Assistance Notes
- Claude diagnosed all six issues and wrote all fixes.

### Lessons / Observations
- The sensitivity scale bug is a unit mismatch hidden by a lucky default: `0.1` happened
  to feel correct as a raw multiplier, so nobody noticed the slider was in "raw multiplier
  units" until the range was changed. Always store human-readable values; apply the
  hardware-to-human conversion at one call site with a named constant.
- The resume-click bleed is a classic input edge detection hazard: the UI consumes the
  press event but the polling-based system sees the transition on the next frame. The
  fix pattern (`synchronise last-state to current physical state before re-enabling
  polling`) is reusable anywhere the cursor is recaptured mid-click.

---

## Entry 050 — Phase 6B-7 Complete: Settings System
**Date:** 25.03.2026
**Phase:** 6 — Foundation for extensibility (6B)

### What Was Done

#### Step 5 — Live Render Distance
- Converted `World.RENDER_DISTANCE_H` from `public static final int` to a mutable
  instance field `renderDistanceH` (default 16). Added `getRenderDistanceH()` and
  `setRenderDistance(int)` (clamped to [2, 32]).
- `setRenderDistance()` sets a `volatile boolean pendingUnloadSweep` flag when the
  distance decreases. `World.update()` checks this flag first and runs an immediate
  `unloadDistantChunks()` before any other work, then clears the flag. Growing the
  distance is free — the next `scheduleNeededChunks` tick picks up the new outer ring.
- All internal uses of the static constant inside `scheduleNeededChunks` and
  `isInRange()` updated to read `renderDistanceH`.
- `ServerWorld.isInPlayerRange()` changed from `static` to instance method (needed
  `world.getRenderDistanceH()`). Added `ServerWorld.setRenderDistance(int)` delegating
  to `world.setRenderDistance()`. Added `GameServer.setRenderDistance(int)` delegating
  to `serverWorld.setRenderDistance()`.
- `GameLoop.applySettings()` calls `activeServer.setRenderDistance(settings.getRenderDistance())`
  when a session is active.

#### Step 6 — SettingsScreen
- Created `SettingsScreen.java` in `game/screen/` via structured Copilot prompt.
  Tabbed layout: Gameplay, Graphics, Display, Controls, Keybinds, Sound.
  Working-copy pattern: all edits are local; Cancel discards, Save writes back to
  `GameSettings` and calls `settings.save()` then `onSave.run()`.
- Tabs (corrected from Copilot output):
  - Gameplay: Username text field (locked with dim note when session active)
  - Graphics: Render Distance slider, FOV slider, UI Theme cycle button
  - Display: VSync toggle, Window Mode cycle button
  - Controls: Mouse Sensitivity slider
  - Keybinds: one row per Action; click-to-listen, keyboard or mouse bind,
    Escape to unbind; conflict detection + warning text; conflict row height grows
    to fit warning line
  - Sound: "coming soon" stub
- Dirty row highlight (blue-tint) when working value differs from saved value.
- Save/Cancel always-visible bottom bar.
- Scroll support (`onScroll` → `scrollOffset`); tab switch resets scroll to 0.
- Added `KeyBindings.copy()` and `KeyBindings.setAll(EnumMap)` used by working-copy
  save flow. Added `GameSettings.save(Path)` overload.
- Fixed Copilot bug: `listeningArmed` flag removed — required 3 clicks instead of 2
  to bind via mouse.

#### Step 7 — Wiring into Menus
- `MainMenuScreen`: added Settings button (between Multiplayer and Quit); panel height
  increased to 340; constructor gains `onSettings` parameter.
- `PauseMenuScreen`: added Settings button (between Resume and Main Menu); panel height
  increased to 272; constructor gains `onSettings` parameter; button order:
  Resume → Settings → Main Menu → Quit.
- `GameLoop`: added `createPauseMenu(long win)` factory method (extracted from
  `handleEscapeKey` for reuse). Added `openSettings(Runnable onDone)` — constructs
  `SettingsScreen` and pushes it; `onDone` is called by both Save and Cancel.
  Added `applySettings()` — applies VSync, FOV, window mode, theme, render distance
  immediately on the GL thread. Theme swap constructs new `DarkTheme` or `LightTheme`
  wrapping the existing renderer (zero GL work).
- From main menu: Settings opens full-screen; Cancel/Save return to main menu.
- From pause menu: Settings opens full-screen; Cancel/Save return to pause menu.
  The world continues to run behind the settings screen (no special pause needed —
  the server keeps ticking, physics are already paused by the screen-active check).

### Decisions Made
- `pendingUnloadSweep` is `volatile` so `setRenderDistance()` can be called from
  any thread (e.g. the GL thread via `applySettings()`). The flag is read and cleared
  only on the server tick thread inside `update()`.
- Settings screen is full-screen (not overlay) — the world render is skipped while
  settings are open. This simplifies rendering and avoids confusion about live preview.
- `createPauseMenu()` extracted as a factory rather than inlining — both
  `handleEscapeKey` and the Settings → Back path need to push the same configured
  pause menu. Without the factory, the callback wiring would be duplicated.

### Problems Encountered
- `isInPlayerRange` in `ServerWorld` was `static` — failed to compile after referencing
  `world.getRenderDistanceH()`. Fixed by removing `static` from the method signature.

### AI Assistance Notes
- Claude wrote Steps 5 and 7 entirely. Step 6 delegated to Copilot (GPT-5.3-Codex)
  via a structured prompt; Claude reviewed and fixed tab assignment errors and the
  `listeningArmed` bug before wiring.
- Tab misassignment (mouse sensitivity in Gameplay instead of Controls; VSync in
  Graphics instead of Display) was a Copilot deviation from the spec. Caught on review.

### Lessons / Observations
- Copilot followed the architectural constraints (no raw GL, UiTheme only, working-copy
  pattern) correctly. The errors were in business logic (which setting goes in which tab)
  not in architecture. A spec table is more reliable than prose for tab-to-setting mapping.
- The pause-menu factory pattern (`createPauseMenu(win)`) is directly analogous to
  `createMainMenu()` introduced in 6B-3. Extracting screen factories into GameLoop
  is the right place for callback wiring — screens stay ignorant of GameLoop internals.

---

## Entry 051 — Phase 6C-1/2/3: Lighting Foundation
**Date:** 25.03.2026
**Phase:** 6 — Foundation for extensibility (6C)

### What Was Done

#### 6C-1 — Light data storage
- Added `lightEmission` field (0–15) to `BlockType`. Package-private constructor
  updated. `BlockRegistry.register()` gained a 6-parameter overload; existing
  5-parameter overload defaults emission to 0 — no callers changed.
- Added `byte[] lightData` to `Chunk` (4096 bytes, one byte per block, packed nibbles:
  high = skylight 0–15, low = block light 0–15). Added `MAX_LIGHT = 15` constant.
- Added `getSkyLight`, `setSkyLight`, `getBlockLight`, `setBlockLight`, `getMaxLight`,
  `clearLight` methods to `Chunk`.

#### 6C-2 — LightEngine
- Created `LightEngine.java` in `common/world/`. Static utility, no state.
- `computeChunkLight(chunk, pos, neighbors)` — calls `clearLight()`, then
  `computeBlockLight()` (seeds emission values), then `computeSkyLight()`.
- Skylight is column-only top-down: scans each X/Z column from Y=15 to Y=0.
  Checks chunk above via neighbor snapshot for whether sky enters at the top.
  Solid blocks absorb sky (hasSky=false), air passes it through.
- Block light seeds each emissive block's own position. No propagation — full
  BFS deferred to Phase 8. Data layout is upgrade-compatible.
- `LightEngine.computeChunkLight()` called in `ClientWorld.processDirtyMeshes()`
  worker lambda, before `ChunkMesher.mesh()`.

#### 6C-3 — Mesher integration and shader changes
- `ChunkMesher` vertex layout expanded from 9 to 10 floats per vertex.
  New 10th float: `brightness = pow(0.8, 15 - light)` — gamma-mapped face light.
- Added `FACE_LIGHT_OFFSET` table and `getLightAt` / `getLightForFace` helpers
  to `ChunkMesher`. Face light reads from the open-air neighbor block on the
  exposed side of each face.
- `packMask` expanded: bits 25–28 now store face light level (0–15).
- `buildMergedQuads` expanded: result array is 10 ints per quad, extracts light
  at index 9. Light is included in the merge condition — faces with different
  light levels cannot be greedy-merged.
- `emitQuad` gained `int light` parameter. Computes `brightness` once per quad,
  emits as 10th float on every vertex in both normal and flipped branches.
- All six face methods updated: `getLightForFace` called before `packMask`,
  `light` passed through to `emitQuad`. Loop stride changed from 9 to 10.
- `ensureCapacity` calls updated from 54 to 60.
- `Mesh.java` `FLOATS_PER_VERTEX` updated 9→10. Attribute slot 4 added
  (lightLevel, offset 36 bytes).
- `default.vert` — added `layout(location=4) in float lightLevel`, passes
  `vertLight` to fragment shader.
- `default.frag` — multiplies fragment color by `clamp(vertLight + u_brightnessFloor, 0, 1)`
  when `useTexture=true`. Added `u_brightnessFloor` uniform (default 0.0).
  Untextured geometry (highlight, player boxes) bypasses light entirely.
- `ShaderProgram.java` — added `setUniform(String, float)` overload.

### Decisions Made
- Column-only skylight first, BFS-ready data layout. Upgradeable in Phase 8.
- `u_brightnessFloor` uniform is wired but not yet connected to settings — caves
  are currently pitch black. Brightness slider setting is next (6C-4).
- Light included in greedy merge condition via `packMask` bits 25–28. Prevents
  incorrect brightness interpolation across merged quads with different light levels.
- `pow(0.8, 15 - level)` gamma curve: perceptually uniform steps, 16 levels
  visually smooth.

### Problems Encountered
- Geometry corruption (black lines): `buildMergedQuads` still allocated `* 9` and
  only wrote 9 values per quad after face methods were updated to read 10. Fixed.
- `emitQuad` `int light` parameter was placed at wrong position in signature.
  Identified via Copilot inspection of updated call sites. Fixed.

### Lessons / Observations
- When expanding a packed format, every layer that reads or writes it must be
  updated atomically: `packMask` → `buildMergedQuads` → face method loop stride →
  `emitQuad` signature → vertex emission body. Missing any one layer produces
  silent data corruption visible only as geometry scrambling.
- The stride mismatch (9 vs 10 floats) produced the exact "exploded geometry"
  artifact: position floats were being read from color/UV slots.

### Remaining in 6C
- 6C-4: `u_brightnessFloor` → brightness slider in `GameSettings` + `SettingsScreen`
- 6C-5: AO toggle in settings (triggers full async remesh)
- 6C-6: Day/night cycle (`WorldTime`, `u_ambientFactor` uniform, server→client packet)
- 6C-7: Skylight recompute on block place/break

---

## Entry 052 — Phase 6C-4/5: Brightness Slider + AO Toggle
**Date:** 25.03.2026
**Phase:** 6 — Foundation for extensibility (6C)

### What Was Done

#### 6C-4 — Brightness slider
- Added `brightnessFloor` field (float, 0.0–0.3) to `GameSettings`. Load/save
  via `settings.properties` key `brightnessFloor`. Default 0.0.
- Added Brightness slider to `SettingsScreen` Graphics tab (between FOV and UI
  Theme). Display shows 0–100% (`Math.round(value / 0.3 * 100)`). Arrow key nudge
  step 0.01f. Dirty highlight when working value differs from saved.
- `GameLoop.render()` calls `shaderProgram.setUniform("u_brightnessFloor",
  settings.getBrightnessFloor())` each frame while shader is bound.
- `GameLoop.applySettings()` binds the shader, sets the uniform, unbinds — allows
  immediate effect on save without waiting for next render frame. Guarded with
  `shaderProgram != null`.

#### 6C-5 — AO toggle
- Added `aoEnabled` boolean field to `GameSettings`. Default true. Persisted as
  `aoEnabled` in `settings.properties`.
- Added `public static volatile boolean aoEnabled = true` to `ChunkMesher`.
  `computeAO()` early-returns 3 (fully open) when false. Volatile ensures GL-thread
  write is immediately visible to all worker threads without locking.
- Added `invalidateAllMeshes()` to `ClientWorld` — adds all loaded chunk positions
  to `dirtyMeshes`. Used to trigger a full world remesh when AO is toggled.
- `GameLoop.applySettings()` compares `ChunkMesher.aoEnabled` against
  `settings.isAoEnabled()` before writing — only triggers a remesh when the value
  actually changed. Remesh is async; no frame hitch.
- Added Ambient Occlusion ON/OFF toggle to `SettingsScreen` Graphics tab. Dirty
  highlight when working value differs from saved.

### Decisions Made
- `u_brightnessFloor` set every render frame (not just on save) — uniform must be
  refreshed after shader program rebind. Simpler than tracking dirty state.
- `ChunkMesher.aoEnabled` is `public static volatile` — AO is a global setting,
  not per-chunk. Static is appropriate; volatile is required for cross-thread
  visibility without locks.
- AO remesh guard (`if changed`) prevents unnecessary full remesh when user opens
  and closes settings without changing the AO toggle.

### Problems Encountered
- None.

### AI Assistance Notes
- Claude wrote GameSettings, ChunkMesher, ClientWorld, and GameLoop changes.
- Copilot wrote SettingsScreen slider and toggle additions via structured prompt.

### Lessons / Observations
- The pattern for any setting that affects mesh content: static volatile flag in
  the mesher → check-and-set in applySettings → invalidateAllMeshes on change.
  Reusable for any future per-mesh setting (e.g. smooth lighting, LOD level).

---

## Entry 053 — Phase 6C-6/7: Day/Night Cycle + Skylight Recompute
**Date:** 25.03.2026
**Phase:** 6 — Foundation for extensibility (6C)

### What Was Done

#### 6C-6 — Day/night cycle
- Created `WorldTime.java` in `common/world/`. Pure math, no GL, no network.
  `DAY_LENGTH_TICKS = 24_000` (20 real minutes at 20 TPS). `worldTick` field
  is `volatile` for safe cross-thread reads. `tick()` advances by one each server
  tick. `getAmbientFactor()` uses a sine curve centred on noon (tick 6000) remapped
  from [-1,1] to [MIN_AMBIENT=0.15, 1.0]. `getSkyColor()` lerps between day blue
  `(0.53, 0.81, 0.98)` and night dark blue `(0.02, 0.02, 0.10)` using the same curve.
- Created `WorldTimePacket.java` in `common/network/packets/`. Record with single
  `long worldTick` field. Clientbound.
- Added `WORLD_TIME (0x18)` to `PacketId`.
- Added `WorldTimePacket` encode/decode to `PacketEncoder` / `PacketDecoder`.
  Wire format: 1-byte ID + 8-byte long.
- `GameServer` gains `WorldTime worldTime`, `timeBroadcastCooldown` int, and
  `TIME_BROADCAST_INTERVAL = 20` constant. `tick()` advances time and broadcasts
  `WorldTimePacket` via `serverWorld.broadcastToAll()` every 20 ticks (once/second).
- Added `broadcastToAll(Packet)` to `ServerWorld` — iterates the `players` list and
  calls `session.getChannel().writeAndFlush()`. Added `getChannel()` to `PlayerSession`.
- `ClientWorld` gains `WorldTime worldTime` field. Added `applyWorldTime(long)`,
  `getAmbientFactor()`, `getSkyColor()` methods. `applyWorldTime` is called from
  the Netty thread — safe via `volatile` field in `WorldTime`.
- `ServerHandler.channelRead0()` routes `WorldTimePacket` to
  `clientWorld.applyWorldTime()`.
- `default.frag` gains `uniform float u_ambientFactor`. Applied as a final
  multiplier on the fully lit+AO fragment colour: `texSample.rgb * vertColor *
  light * u_ambientFactor`. Untextured geometry bypasses it (already bypasses
  `useTexture` branch).
- `GameLoop.render()`: dynamic `glClearColor` from `clientWorld.getSkyColor()`
  (falls back to hardcoded day blue when no world loaded). Sets
  `u_ambientFactor` uniform each frame from `clientWorld.getAmbientFactor()`
  (defaults to 1.0 when no world loaded).

#### 6C-7 — Skylight recompute on block place/break
- No additional code required. `drainPendingBlockChanges()` in `ClientWorld`
  already dirty-marks the modified chunk and all face-adjacent neighbors. Every
  dirty chunk runs `LightEngine.computeChunkLight()` before remeshing in
  `processDirtyMeshes()`. The chunk directly below a broken surface block is
  always in the dirty set via `markNeighborsDirty`, and `computeSkyLight`
  checks the chunk above via the neighbor snapshot — so the skylight column
  updates correctly through one chunk boundary automatically.

### Decisions Made
- `worldTick` in `WorldTime` is `volatile` — `applyWorldTime` is called from
  the Netty I/O thread while `getAmbientFactor` / `getSkyColor` are called from
  the GL thread. Volatile provides the necessary visibility guarantee for a single
  `long` write without the overhead of a lock.
- `u_ambientFactor` is set every render frame (same pattern as `u_brightnessFloor`).
  No dirty tracking — simpler and the cost is one `glUniform1f` call per frame.
- Sync interval is 20 ticks (1 real second). At a 20-minute day length, 1-second
  staleness is imperceptible. No client-side interpolation needed.
- `glClearColor` falls back to day blue when `clientWorld` is null (main menu).
  The menu always appears in daylight — cosmetically correct.
- Sky colour and ambient factor share the same sine curve so they stay visually
  consistent — geometry and sky dim together.
- Day/night does not affect untextured geometry (block highlight wireframe, remote
  player boxes). These bypass the `useTexture` branch and are unaffected by
  `u_ambientFactor`.
- 6C-7 confirmed as already-implemented: the existing dirty-mark + LightEngine
  pipeline handles skylight recompute automatically on every block change. No
  additional code was needed.

### Problems Encountered
- `u_brightnessFloor` was accidentally removed from `default.frag` when
  `u_ambientFactor` was added. Shader compilation failed with
  `error C1503: undefined variable "u_brightnessFloor"`. Fixed by restoring
  both uniform declarations.
- `glClearColor` was placed after `glClear` instead of before it — previous frame
  contents were never erased, causing severe render corruption (geometry "extending"
  across screen). Fixed by moving `glClearColor` to immediately before `glClear`.
- `WorldTimePacket` was not routed in `ServerHandler` — server broadcast worked
  but client logged `[Client] Unhandled packet: WorldTimePacket` every tick.
  Fixed by adding the `else if (packet instanceof WorldTimePacket p)` branch.
- `broadcastToAll` was added to `GameServer` instead of `ServerWorld` — `players`
  list and `getChannel()` live in `ServerWorld`. Moved to correct class. Added
  `getChannel()` to `PlayerSession`.

### AI Assistance Notes
- Claude wrote all new files and all changes across all 10 affected files.
- Three of the four bugs above were caught at runtime on first run.

### Lessons / Observations
- `glClearColor` and `glClear` must always appear in this order: set color, then
  clear. Reversing them produces one of the most visually dramatic bugs possible —
  every frame bleeds into the next. Always pair them.
- When adding a new packet, there are always 4 mandatory touch points: `PacketId`,
  `PacketEncoder`, `PacketDecoder`, and the receiving handler (`ServerHandler` or
  `ClientHandler`). Missing any one silently drops the packet with an unhandled log.
- The dirty-mark + LightEngine pipeline built in 6C-2/3 was forward-designed
  correctly — skylight recompute on block change required zero additional code.
- `volatile` on a `long` field is the minimal correct solution for a single-writer
  (Netty thread) / single-reader (GL thread) value. No lock, no `AtomicLong` needed
  when ordering guarantees don't extend beyond the single field.

### Future Reference — Transparent Blocks
Transparent and semi-transparent blocks (glass, water, leaves) require significant
rendering pipeline changes when they arrive:
1. `BlockType` needs a `transparent` boolean and `occludesFace()` method. Current
   face occlusion culling assumes all solid blocks hide their neighbors — transparent
   blocks cannot participate in this.
2. `ChunkMesher` must produce two vertex buffers per chunk: one opaque, one
   transparent. `ClientChunk` stores two `Mesh` objects.
3. The render loop becomes: draw all opaque geometry → depth-sort transparent chunks
   back-to-front → draw transparent geometry. Sorting is required because OpenGL
   blends a transparent fragment with whatever is already in the framebuffer — draw
   order matters.
4. Greedy meshing for transparent faces is disabled or heavily restricted — merged
   faces that are partially visible through each other produce incorrect blending.
5. `BlockRegistry` / `BlockType` from 6A is already shaped correctly for this.
   Nothing done to date makes the transparent block implementation harder.
Deferred to Phase 8+. Data layout is compatible.

### Phase Roadmap Revision — BFS Before Modding
Phase ordering updated after 6C completion:
- **Phase 7** promoted to **BFS Light Propagation** (was: Modding API).
- **Phase 8** becomes **Modding API** (was: BFS + deferred items).
Rationale: BFS propagation completes the lighting story (correct gradients under
overhangs, torch spread), is a prerequisite for torches feeling correct, and has
significantly higher visual and gameplay impact than a modding API at this stage.
The modding API requires entity and item systems that don't exist yet anyway —
deferring it one phase costs nothing. BFS data layout (lightData nibble arrays,
`setSkyLight`/`setBlockLight`, `LightEngine`) is already fully in place from 6C-1.

---

## Entry 054 — Workflow: Context System Improvements
**Date:** 26.03.2026
**Phase:** 6 — Meta / Workflow

### What Was Done
- Created `DEVLOG_ARCHIVE.md`: Phase 0–5 entries (001–047) moved there.
  DEVLOG.md now contains Phase 6+ only (~400 lines vs 3000+).
- Added `## CURRENT STATE` header to top of DEVLOG.md for RAG pinning.
- Updated CLAUDE.md:
  - "Always Read DEVLOG.md First" clarified to mention archive.
  - Context File Maintenance list updated with archive note.
  - Added Section 14: Cross-File State Dependencies (GameLoop/ClientWorld/
    ChunkMesher/World state boundaries documented for RAG retrieval).
  - Maintenance rule now explicitly covers Section 14 updates.
- Updated system prompt to reflect DEVLOG_ARCHIVE existence.
- Created `.github/prompts/annotate-gl-state.prompt.md` — reusable Copilot
  agent prompt that adds @thread / @gl-state / @see Javadoc annotations to
  any high-risk engine file.
- Created `.github/prompts/annotate-gl-state-triage.prompt.md` — one-time
  prompt that scans the entire repo and applies annotations to all files
  meeting the GL/thread-boundary criteria.

### Why
RAG blind spots caused occasional "wrong method assumed" errors — most notably
applySettings() GL state assumptions being missed when only render() chunk was
retrieved. The annotation system embeds state context directly into each method's
Javadoc so any retrieved chunk carries its own dependency information.

### Decisions Made
- Annotation maintenance is triggered by Claude at session end, not manually.
  CLAUDE.md Section 14 is the authority for cross-file dependencies.
- Only 6 files qualify for annotation: GameLoop, ClientWorld, ChunkMesher,
  World, ServerWorld, ShaderProgram. All others skipped.

### AI Assistance Notes
- Entire workflow redesign done with Claude based on analysis of RAG blind spot patterns.
- Copilot will handle the actual annotation execution via triage prompt.

---

## Entry 055 — Pure Radial Chunk Loading & State Machine Limits
**Date:** 26.03.2026
**Phase:** 6 — Foundation for extensibility (6C)

### What Was Done
- Fixed a major bug where chunks were loaded into memory but remained completely invisible unless the player interacted with them or flew high into the sky. 
- Root Cause: The client's Radius-Based State Machine (`hasCompleteNetworkCollar` and `hasCompleteLightCollar`) demanded a strict 3x3x3 collar of neighbors. Chunks at `y = 0` infinitely waited for neighbors at `y = -1` (which the server never generates).
- Added boundary awareness `isOutsideGenerationLimits` to bypass missing neighbors that fall outside the physical bedrock/sky limits.
- **Removed Look-Direction Bias:** Removed the 75/25 look-direction generation bias in `World.java`. Replaced it with pure radial distance loading (concentric rings).
- **Radius Padding:** Padded the server's chunk generation and streaming bounds by `+2` chunks (`RENDER_DISTANCE_H + 2` and `RENDER_DISTANCE_V + 2`). 

### Decisions Made
- Look-direction bias fundamentally starves a Radius-Based State Machine. When a narrow "tube" of chunks is sent to the client, none of them have their lateral neighbors, causing the pipeline to freeze and leaving the background threads idle. Pure radial loading feeds the state machine exactly what it needs (concentric rings) to instantly process light and meshes.
- Padding the generation radius by 2 chunks allows the server to hold a "buffer ring" so the client's visible render distance perfectly matches the setting without clipping the outer two layers.

### Problems Encountered
- **The Ceiling Deadlock:** Padding the vertical generation limit caused a garbage-collection loop. `isInRange` was deleting the newly generated ceiling chunks instantly because its bounds were not padded, which permanently paralyzed the chunks below them. Fixed by padding both horizontal and vertical limits in `isInRange`.

### AI Assistance Notes
- As Claude greatly failed the development of light system twice, Gemini (gemini 3.1 pro) was used for everything in this part.

---

## Entry 056 — Server-Side Lighting & The Server State Machine
**Date:** 26.03.2026
**Phase:** 6 — Foundation for extensibility (6C)

### What Was Done
- Shifted absolute lighting authority from the client to the server to support future core mechanics (mob spawning, crop growth). 
- Updated `Chunk.java` serialization. `getLightBytes()` and `setLightBytes()` now pack/unpack the 4096-byte `lightData` array directly into the network buffer. `SERIALIZED_SIZE` increased from 8192 to 12288 bytes (4KB blocks + 8KB lighting arrays).
- **The Server State Machine:** Re-architected `World.java` to use a 2-stage state machine to prevent "dark seams". 
  - **Stage 1:** Raw blocks are generated and placed into `generatedChunks`. The server waits for a 3x3x3 collar of raw chunks before submitting the center chunk to the background `LightEngine`.
  - **Stage 2:** Fully lit chunks are placed into `lightReady`. The server waits for a 3x3x3 collar of lit chunks before promoting the center chunk to `networkScheduled`.
- Updated `ServerWorld.streamChunksToPlayer` to only transmit chunks if `world.isChunkReadyForNetwork(pos)` is true.
- Completely removed `lightExecutor`, `promoteToLightIfReady`, and all initial lighting logic from `ClientWorld.java`. Newly downloaded chunks bypass light calculation and go straight to meshing.

### Decisions Made
- **Server Authority:** The server must run the state machine, not the client. If the server computes light immediately upon generation without waiting for neighbors, light will not bleed across chunk borders, resulting in a checkerboard of dark seams.
- **Compressed Lighting:** Sky light (4 bits) and block light (4 bits) are successfully packed into a single 4096-byte array, halving the network overhead of transmitting lighting.

### Problems Encountered
- **The Old Save File Crash:** After expanding the chunk byte length to 12KB, the engine experienced a silent background thread crash on launch. The `WorldMeta` storage system was loading old 8KB chunks from disk, causing `Chunk.fromBytes` to throw a length mismatch exception. Because the exception was swallowed by the thread pool, 88 spawn chunks vanished, deadlocking the state machine and leaving a perfectly rectangular hole at spawn. 
- *Fix:* Deleted the `worlds/default` folder to force a fresh generation of 12KB chunks.

### AI Assistance Notes
- As Claude greatly failed the development of light system twice, Gemini (gemini 3.1 pro) was used for everything in this part.

---

## Entry 057 — Client-Side Prediction (Zero-Latency Block Edits)
**Date:** 26.03.2026
**Phase:** 6 — Foundation for extensibility (6C)

### What Was Done
- Implemented Client-Side Prediction to eliminate network ping latency during block placement and breaking.
- Extracted the localized BFS light recalculation and synchronous chunk meshing from `ClientWorld.drainPendingBlockChanges` into a public `setBlock` method.
- Updated `GameLoop.update()` to instantly call `clientWorld.setBlock()` to locally modify the world *before* sending the `BlockBreakPacket` or `BlockPlacePacket` to the server.
- Added a Prediction Bypass guard: `if (oldBlock.getId() == block.getId()) return;`. When the server eventually echoes the action back via a `BlockChangePacket`, the client sees the block is already correct and silently drops the packet, saving CPU cycles.
- Added a Ghost Block prevention scrub: `pendingMeshes.removeIf()` purges stale asynchronous meshes waiting in the background queue whenever a synchronous block edit occurs.

### Decisions Made
- Instant visual feedback is critical for game feel. Client-Side Prediction allows the player to see block and shadow changes instantly, while the server maintains ultimate authority and can issue correction packets if an illegal move is detected.

### Problems Encountered
- **The Ghost Block Race Condition:** If a background thread was actively building a mesh for a chunk right before the player broke a block in it, the stale "pre-break" mesh would arrive moments later and overwrite the local prediction, causing the broken block to pop back into existence. Fixed via the `removeIf` scrubber.

### AI Assistance Notes
- As Claude greatly failed the development of light system twice, Gemini (gemini 3.1 pro) was used for everything in this part.

### NOTE FROM DEVELOPER (IMPORTANT):
- Gemini violated many rules i used with Claude. It didn't add comments where it needed to, it didn't followed the `CLAUDE.md` properly and most likely broke some architectural constratint/rule/design or whatever. In short: It probably did something bad, but Claude wasn't working so i chose the easiest way out. My bad, noting this here so we can fix whatever headache this causes later.

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