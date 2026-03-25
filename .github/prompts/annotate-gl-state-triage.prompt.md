---
agent: 'agent'
tools: ['search/codebase']
description: 'Identify and annotate all GL/thread-boundary files in the project'
---

Read CLAUDE.md to understand the project's threading model (GL thread rules,
server thread rules, worker thread rules).

Then scan all Java files under src/main/java/com/voxelgame/ and identify every
file where ANY of the following is true:
- Methods are called from more than one thread type
- Methods interact with OpenGL (GL calls, shader state, Mesh construction)
- The class lives in the engine/ package

For each identified file, apply the @thread / @gl-state / @see annotation pattern
from .github/prompts/annotate-gl-state.prompt.md to every method.

Skip all files where none of the above applies (server/network/, common/world/
data classes, Screen subclasses, BlockRegistry, Blocks, etc.).

After completing all annotations, output a single summary:
ANNOTATED: [list of files touched]
SKIPPED: [list of files skipped and why]