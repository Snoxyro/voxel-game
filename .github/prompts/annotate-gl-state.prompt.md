---
agent: 'agent'
tools: ['search/codebase', 'edit/editFiles']
description: 'Add GL thread and state annotations to all methods in a high-risk engine file'
---

Scan the Java file at the path provided and add or update Javadoc block comments on
every method — public, private, and package-private. Do NOT change any method body
or existing logic. Only add/update documentation.

For each method, determine and document the following:

### 1. Thread annotation (@thread)
Which thread is this method called from?
- `@thread GL-main`     — GameLoop's main thread; GL calls are safe here
- `@thread server-tick` — GameServer's tick thread; NO GL calls ever
- `@thread worker`      — meshExecutor pool thread; NO GL calls ever
- `@thread Netty-IO`    — Netty event loop; NO GL, NO direct map writes
- `@thread any`         — explicitly thread-safe

### 2. GL shader state (@gl-state)
Only for methods on `@thread GL-main`. Is the shader program bound when this method runs?
- `@gl-state shader=bound`   — shaderProgram.bind() was called before entry; setUniform() is safe
- `@gl-state shader=unbound` — shader NOT bound; must call bind() explicitly before setUniform()
- `@gl-state n/a`            — method does not interact with shader state

### 3. Cross-method dependency note (@see)
If this method's correctness depends on state established by another method in the same
class, add a single-line @see note. Only add when the gap would cause a real bug.

Example of correct output for an annotated method:
```java
/**
 * Applies all persisted settings to live engine systems immediately.
 *
 * @thread GL-main
 * @gl-state shader=unbound — call shaderProgram.bind() before any setUniform() here
 * @see #render(float) — shader IS bound there; do not copy patterns from render() here
 */
public void applySettings() {
```

Follow the copilot-instructions.md coding standards. Every public method must
retain or gain a descriptive first-line summary before the annotations.

File to annotate: ${input:file:File path (e.g. src/main/java/com/voxelgame/engine/GameLoop.java)}