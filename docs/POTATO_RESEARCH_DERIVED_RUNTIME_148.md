# Potato Runtime 148 - Research-derived streaming/runtime architecture

This milestone deliberately separates **active production-safe techniques** from
research ideas that require the later Vulkan ownership cutover.

## Active in 148

- **Predictive Residency / Time-to-visible scheduling**
  - 100 ms, 300 ms and velocity-adaptive 700-1800 ms future camera footprints.
  - Swept motion corridor for FAST/EXTREME traversal.
  - Priority bands: `IMMEDIATE`, `PREDICTED_NEAR`, `PREDICTED_FAR`, `NORMAL`.
  - No server chunk tickets and no simulation/render-distance mutation.
- **Async work-stealing terrain compile pool**
  - `ForkJoinPool` async mode with CPU/memory headroom.
  - Tail-latency telemetry at 8/16/33/100/250 ms.
- **Speed-aware upload deadline recovery**
  - FAST: up to +0.5 ms; EXTREME: up to +1.0 ms.
  - Hard 4 ms ceiling; spike cooldown always wins.
- **Clipmap-inspired LOD stability**
  - Small motion-dependent far-field inset.
  - Stronger recovery hysteresis while moving quickly.
  - Patch-145 Tier-1 quality contract remains authoritative.
- **Motion-safe temporal occlusion**
  - Previous-frame zero-sample evidence is reused less aggressively under fast motion.

## Research basis

- Correa, Klosowski & Silva, *Visibility-Based Prefetching for Interactive Out-Of-Core Rendering* (IEEE PVG 2003, DOI 10.1109/PVGS.2003.1249035): predict geometry likely to become visible and prefetch it on a separate thread so bursty data demand is amortized before it reaches the frame deadline.
- Crassin et al., *GigaVoxels: Ray-Guided Streaming for Efficient and Detailed Voxel Rendering* (I3D 2009): rendering-driven data requests, bounded cache and temporal coherence.
- Losasso & Hoppe, *Geometry Clipmaps: Terrain Rendering Using Nested Regular Grids* (SIGGRAPH 2004, DOI 10.1145/1015706.1015799): viewer-centred stable LOD regions, incremental refill, visual continuity and graceful degradation.
- Yoon et al., *Quick-VDR: Out-of-Core View-Dependent Rendering of Gigantic Models*: hierarchy + LOD + occlusion with deliberately deferred fetches instead of blocking the frame.
- Fang et al., *Aokana: A GPU-Driven Voxel Rendering Framework for Open World Games* (PACMCGIT 2025, DOI 10.1145/3728299): GPU-driven voxel rendering, streaming, LOD and large-world working-set control.
- Bittner et al., *Coherent Hierarchical Culling: Hardware Occlusion Queries Made Useful* (CGF 2004, DOI 10.1111/j.1467-8659.2004.00793.x): hierarchy and temporal coherence reduce the cost and stall risk of naive per-object occlusion queries.
- Blumofe & Leiserson, *Scheduling Multithreaded Computations by Work Stealing*: dynamic load balancing for irregular task graphs; Potato applies the principle conservatively with explicit CPU/memory headroom rather than consuming every logical core.
- Burns & Hunt, *The Visibility Buffer*: defer expensive shading until visibility is known.
- Mlakar et al., *End-to-End Compressed Meshlet Rendering* (Computer Graphics Forum 2024, DOI 10.1111/cgf.15002): keep geometry compressed in GPU memory and decode just-in-time in mesh shaders to reduce memory footprint and streaming/decompression pressure.

## Rust voxel-renderer comparison

Projects such as **Minerust**, **wgpu-mc/Electrum** and **Pomme** reinforce the
same architectural lesson rather than proving that Rust itself is a magic FPS
switch: unified/suballocated terrain buffers, asynchronous meshing, GPU culling
and indirect submission are the high-leverage ideas. Potato therefore keeps its
Java/Mixin/NeoForge compatibility layer and adopts the data-oriented scheduling
and GPU-driven architecture. A native Rust core remains a later optional design
choice, not a prerequisite for this milestone.

## Staged, not falsely enabled

The following are architecture targets, **not** production-active in Patch 148:

- current-frame GPU Hi-Z recheck / visibility pyramid;
- visibility buffer shading path;
- compressed terrain meshlets / mesh-shader fast path;
- production-visible textured region-indirect SOLID ownership.

The current runtime already proved real device-local arena geometry and
`vkCmdDrawIndexedIndirect`, but OpenGL still owns the visible production raster.
Those Vulkan techniques must therefore enter through one later atomic ownership
cutover rather than by adding more shadow work to the current hybrid path.
