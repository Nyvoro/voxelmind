# Contributing to VoxelMind Local

Thanks for wanting to make VoxelMind better. The code is open (MIT) so you can read it, check what it does with your data, build it yourself and suggest changes.

## Clean core

This repository is the core of the mod. It is maintained by Nyvoro UG, and only the maintainers merge into it, so that every release stays stable on all 16 Minecraft versions. The rule for contributions is the same as for any "clean core": extend through the defined places instead of bending the core.

- **Easiest:** a brain (how a companion thinks: prompts, context, tools, memory). It is data only, no Java; see `brains/README.md`. A pull request adds `brains/<id>/`.
- **Best:** code changes that fit the existing places: a new body tool in `tool/`, a better perception line in `mind/Sensory`, a translation in `lang/`, a fix with a game test.
- **Welcome, but talk first:** new kinds of AI beings, new providers, new menus. Open an issue or post in the Discord feature-request channel before you write a lot of code.
- **Not for the core:** changes that only one server needs. Keep those in your fork or, later, in an addon.

## How a change gets in

1. Open an issue (or comment on one) so we agree on the what.
2. Write the code in the 26.2 names; see `docs/VERSIONS.md` for how the other 15 targets are derived (rename rule, `Compat` helper or a `//?` block).
3. Add or extend a game test in `src/gametest` when behaviour changes. `./gradlew :26.2:runGameTest` must pass.
4. Open a pull request with what changed and why. We may take it as it is, adapt it, or say no; either way we explain why.

By opening a pull request you agree that your contribution is licensed under the MIT license of this repository.

## Building

See `README.md` and `docs/VERSIONS.md`. You need JDK 21 (for 1.21.x) and JDK 25 (for 26.x).

## Security

Please do not open public issues for security problems (for example a way to leak API keys or Sparks tokens). Write to info@voxel-mind.com instead.
