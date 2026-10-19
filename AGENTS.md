# Notes for AI coding agents

- **Building a brain** (how a companion thinks: prompts, context, tools, memory): read `brains/README.md` and work only in the
  brain's folder. No Java changes are needed for a brain.
- **Changing the mod itself:** read `CONTRIBUTING.md` and `docs/VERSIONS.md`. Write code in the Minecraft 26.2 names; the other
  15 targets are derived from it. `./gradlew :26.2:runGameTest` must pass.
- The core (body, tools, AI label and safety rule, providers, permissions) is maintained by Nyvoro UG; changes to it come in as
  pull requests.
