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