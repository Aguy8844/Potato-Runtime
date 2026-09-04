# Potato Runtime - Research Basis

Potato Runtime is a **research-informed engineering project**. It adapts ideas
from established parallel-scheduling and real-time graphics research to
Minecraft's client runtime. The cited papers do **not** prove that Potato
Runtime makes Minecraft faster, and Potato does not claim to reproduce each
paper's system or theoretical guarantees. Potato's implementation and benchmark
results are its own and must be evaluated independently.

## Core references and what Potato takes from them

### Blumofe & Leiserson (1999) - Scheduling Multithreaded Computations by Work Stealing
Journal of the ACM 46(5).
DOI: https://doi.org/10.1145/324133.324234

Used as conceptual grounding for dynamic work distribution and work stealing
when independent background jobs have uneven costs. Potato applies that
principle to chunk/mesh-related background work. The paper's formal bounds are
not claimed to apply directly to Minecraft.

### Agrawal, Leiserson, He & Hsu (2008) - Adaptive Work-Stealing with Parallelism Feedback
ACM Transactions on Computer Systems.
Reference: https://www.microsoft.com/en-us/research/publication/adaptive-work-stealing-with-parallelism-feedback/

Used as conceptual grounding for adapting parallelism rather than simply
saturating every logical CPU. Potato's resource ceilings and feedback policy are
Minecraft-specific empirical engineering decisions, not copied constants from
the paper.

### Li et al. (2016) - Work Stealing for Interactive Services to Meet Target Latency
PPoPP 2016.
DOI: https://doi.org/10.1145/2851141.2851151

Used as conceptual grounding for prioritizing interactive latency over maximum
background throughput. This maps to Potato's frame-first approach: background
chunk work can be bounded when interactive/render pressure is high.

### Correa, Klosowski & Silva (2003) - Visibility-Based Prefetching for Interactive Out-of-Core Rendering
IEEE Symposium on Parallel and Large-Data Visualization and Graphics.
DOI: https://doi.org/10.1109/PVGS.2003.1249035

Used as conceptual grounding for predicting future visibility and preparing
data before it becomes immediately visible. Potato adapts this idea to
movement/view-sensitive chunk and mesh prioritization; it is not a direct
implementation of the paper's renderer.

### Losasso & Hoppe (2004) - Geometry Clipmaps: Terrain Rendering Using Nested Regular Grids
ACM Transactions on Graphics 23(3).
DOI: https://doi.org/10.1145/1015706.1015799

Used as inspiration for viewer-centric distance bands, incremental terrain
updates, and distance-dependent detail. Potato does **not** implement Geometry
Clipmaps.

### Mattausch, Bittner & Wimmer (2008) - CHC++: Coherent Hierarchical Culling Revisited
Computer Graphics Forum 27(2).
DOI: https://doi.org/10.1111/j.1467-8659.2008.01119.x

Used as conceptual grounding for temporal coherence and conservative reuse of
visibility information. Potato's occlusion path remains fail-open and avoids
blocking gameplay on GPU completion.

### Crassin et al. (2009) - GigaVoxels: Ray-Guided Streaming for Efficient and Detailed Voxel Rendering
I3D 2009.
DOI: https://doi.org/10.1145/1507149.1507152

Used as broader inspiration for feedback-guided streaming and bounded resident
working sets in large voxel scenes. Potato does **not** implement the GigaVoxels
ray-casting system.

## Release-safe wording

Recommended:

> Potato Runtime is a research-informed Minecraft optimization project. Its
> scheduling, visibility, streaming and level-of-detail systems draw on
> established ideas from parallel scheduling and real-time graphics research.
> These ideas are adapted specifically for Minecraft's runtime architecture;
> Potato Runtime's implementation and performance results are independently
> engineered and benchmarked rather than direct reproductions of the cited
> research systems.

Avoid claims such as "scientifically proven to make Minecraft faster".