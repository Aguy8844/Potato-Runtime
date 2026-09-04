# Contributing to Potato Runtime

Thanks for helping improve Potato Runtime. 🥔

## Bug reports

Please use the GitHub issue tracker and include as much of the following as possible:

- Minecraft version
- NeoForge version
- Potato Runtime version
- Potato mode (`DYNAMIC`, `ON`, or `OFF`)
- CPU and GPU
- Java version
- relevant rendering/performance mods (for example Sodium, Iris, Voxy/Roxy, ImmediatelyFast, EntityCulling)
- clear reproduction steps
- whether the issue also happens with Potato Runtime set to `OFF`
- crash reports or logs when relevant

A useful A/B comparison between `DYNAMIC` and `OFF` is especially valuable for performance or chunk-streaming reports.

## Pull requests

Before opening a PR:

1. Keep changes focused and explain the problem being solved.
2. Preserve fail-open behavior for renderer/runtime changes.
3. Do not silently change Minecraft render distance, simulation distance, world ticks, saves, or network/server chunk requests for performance.
4. Avoid blocking GPU-wide waits in normal gameplay paths.
5. Include test notes and hardware/mod-stack context for performance-sensitive changes.
6. Keep user-facing settings simple unless there is a strong reason not to.

For larger architectural changes, opening an issue first is recommended.

## Performance claims

Please do not describe a change as `X% faster` unless the measurement method and comparison data support that claim.

Prefer frametime distributions, repeated A/B runs, and reproducible test routes over one-off FPS screenshots.

## License

By contributing, you agree that your contribution will be licensed under the Mozilla Public License 2.0, consistent with the rest of the project.
