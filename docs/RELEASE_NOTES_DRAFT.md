# Potato Runtime - Release Notes Draft

## What Potato Runtime is

Potato Runtime is a client-side performance runtime aimed especially at large
modpacks and lower-end computers. Its current release path combines adaptive
CPU scheduling, bounded chunk/mesh work, visibility-aware prioritization,
hardware-aware resource policy and conservative fail-open compatibility.

The project is research-informed rather than a direct reproduction of any one
research system. See `docs/RESEARCH_BASIS.md`.

## Recommended companion mods

Potato Runtime is intentionally designed to coexist with established
optimization/rendering mods. The large compatibility stress profile has been
tested with Sodium, Iris, Voxy/Roxy, ImmediatelyFast, EntityCulling and a much
larger mod stack.

Recommendations are version-specific compatibility observations, not a promise
that every future combination is automatically compatible.

- **Sodium:** strongly recommended as a complementary renderer optimizer where
  supported. Potato 169b integrates its DYNAMIC / ON / OFF control through
  Sodium's public Config API.
- **Iris:** recommended when shader support is wanted. Iris is not described as
  a universal FPS booster; Potato treats its renderer ownership conservatively.
- **Voxy/Roxy:** useful complementary distant-terrain/LOD tooling where the
  chosen versions are compatible.
- **ImmediatelyFast / EntityCulling / Lithium and similar optimizers:** may
  complement Potato because they target different costs. Test the exact pack.

## Important: completely fresh worlds on very slow systems

Potato can optimize client-side scheduling, compilation and presentation, but
it cannot render chunk data that Minecraft's integrated server and world
generator have not produced yet.

In one deliberately extreme stress test with roughly 150 mods on low-end
hardware, only the immediate spawn area was initially available and surrounding
fresh terrain took on the order of roughly two minutes to begin catching up.
That is an **observed worst-case stress-test result**, not a universal or
guaranteed loading time.

If a completely new world initially looks sparse on a very slow PC, give world
generation time to catch up before assuming the game has frozen. Once chunks
started becoming available in that stress test, their client-side loading and
presentation recovered quickly.

Further cold-world-generation cooperation is a possible 1.0 research target;
it is not promised as solved by this release.

## User modes

- **DYNAMIC** - recommended; Potato enabled with automatic hardware/JVM policy
  and adaptive runtime behavior.
- **ON** - Potato enabled with hardware-safe automatic resource sizing while
  the dynamic soft-view policy is disabled.
- **OFF** - Potato mixins are rejected on the next startup for an A/B baseline.
  The jar remains loaded, so OFF is not byte-for-byte identical to removing the
  mod.

Mode changes are startup-latched and require a restart.

## Compatibility / safety philosophy

- no continuous mutation of Minecraft render distance for FPS
- no simulation-distance mutation
- no world tick/save mutation
- no forced network chunk loads
- no gameplay `vkWaitForFences`, `vkQueueWaitIdle` or `vkDeviceWaitIdle`
- renderer takeover remains fail-open when required proof is missing
- external mod errors are not silently suppressed