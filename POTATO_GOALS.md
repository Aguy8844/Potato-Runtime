# Potato Runtime — Project Goals

## Mission

Make modded Minecraft playable on hardware on which it has absolutely no business running.

## Non-negotiable rules

1. Compatibility before benchmark numbers.
2. Stability before aggressive optimization.
3. Every performance claim must be measured.
4. Every risky subsystem must have a fallback.
5. A broken optimization is disabled; the whole runtime should not become unusable.
6. No Minecraft version is called "supported" until it is automatically tested.
7. 1.21.1 NeoForge is the first production target.
8. The architecture must allow future Minecraft/loader adapters.
9. Client-only first. Server acceleration comes later.
10. Small, testable patches instead of giant rewrites.

## Version 1 target

- Minecraft 1.21.1
- NeoForge
- Client-only
- Hardware diagnostics
- Vulkan capability detection
- Vulkan rendering backend
- Chunk/render pipeline optimization
- Culling
- CPU scheduling improvements where safe
- Memory improvements where measurable
- Safe mode / fallback
- Automated regression tests
- Representative modpack testing

## Future

- Additional Minecraft versions and loaders
- One universal distribution artifact where technically practical
- Potato Server acceleration
- Extreme low-end hardware research
