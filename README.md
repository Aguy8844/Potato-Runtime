# 🥔 Potato Runtime

**Potato Runtime** is a research-informed Minecraft performance runtime focused on smoother chunk streaming, smarter CPU scheduling, and adaptive resource management for weak hardware and heavily modded setups.

> **Minecraft:** 1.21.1  
> **Loader:** NeoForge  
> **Current release:** 0.9.0  
> **Recommended mode:** `DYNAMIC`

## What does it do?

Potato Runtime tries to do the right work at the right time instead of throwing every available CPU thread at Minecraft and hoping for divine intervention.

It focuses on:

- smarter chunk and mesh scheduling;
- multithreaded chunk preparation;
- movement-aware and predictive terrain prioritization;
- bounded upload work to protect frame time;
- hardware-aware resource limits;
- safe fail-open behavior when a renderer path is not ready.

It does **not** secretly lower your selected render distance or alter world simulation just to make an FPS counter look prettier.

## Modes

### `DYNAMIC` — recommended

Automatically adapts Potato Runtime to the detected hardware and current workload.

### `ON`

Keeps Potato Runtime enabled with hardware-aware resource sizing, but without the dynamic chunk policy.

### `OFF`

Disables Potato Runtime's runtime mixins and leaves the rest of the modpack authoritative. This also provides a genuine A/B troubleshooting baseline.

When Sodium is installed, Potato Runtime integrates its selector directly into Sodium Video Settings through Sodium's official Config API.

## Testing

Potato Runtime 0.9.0 was tested on low-end laptop hardware and in a heavily modded Minecraft 1.21.1 setup with roughly 150 active mods, including Sodium, Iris, Voxy/Roxy, ImmediatelyFast, EntityCulling, Create addons, Applied Energistics 2, and large world-generation/structure stacks.

Repeated A/B testing found that Potato Runtime enabled **felt smoother and allowed chunks to keep up better than OFF**. This is currently a qualitative result; 0.9.0 does **not** publish an invented `X% faster` claim. A more formal frametime benchmark is planned for a later update.

## Vulkan

Potato Runtime contains substantial ongoing Vulkan renderer research, but **Vulkan is not advertised as the active gameplay renderer in 0.9.0**. The tested Sodium + Iris profile safely uses the OpenGL compatibility path when the Vulkan path is not fully qualified.

Incomplete renderer ownership fails open instead of turning your world into experimental modern art.

## Research basis

Potato Runtime is a **research-informed engineering project**. Design inspiration includes:

- [Scheduling Multithreaded Computations by Work Stealing](https://doi.org/10.1145/324133.324234) — Blumofe & Leiserson
- [Adaptive Work-Stealing With Parallelism Feedback](https://doi.org/10.1145/1394441.1394443) — Agrawal et al.
- [Work Stealing for Interactive Services to Meet Target Latency](https://doi.org/10.1145/2851141.2851151) — Li et al.
- [Visibility-Based Prefetching for Interactive Out-of-Core Rendering](https://doi.org/10.1109/PVGS.2003.1249035) — Corrêa, Klosowski & Silva
- [Geometry Clipmaps](https://doi.org/10.1145/1015706.1015799) — Losasso & Hoppe

These papers influence the engineering approach; they do not make Potato Runtime "scientifically proven faster" or imply that the original systems are reproduced 1:1.

## Issues and contributions

Found a reproducible bug? Please use the [issue tracker](../../issues) and include your Minecraft version, NeoForge version, relevant performance/rendering mods, hardware, Potato mode, and reproduction steps.

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Potato Runtime is licensed under the [Mozilla Public License 2.0](LICENSE).

That means the source can be used, studied, modified, and redistributed under the MPL's terms while keeping file-level modifications open.

---

**Minecraft should run on potatoes too.** 🥔
