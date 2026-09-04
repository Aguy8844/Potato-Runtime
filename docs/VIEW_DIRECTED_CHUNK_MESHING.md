# View-Directed Chunk Meshing

Patch 045 is the exact bytecode census for the first active Potato Visibility
Engine consumer.

Patch 044 proved camera-projective HOT/WARM/COLD classification, but its runtime
hook is downstream of vanilla section selection. The successful 32-chunk test
classified only a tiny set and saw no COLD sections.

Therefore Potato must not blindly defer work at the existing draw hook.

Patch 045 maps:
- LevelRenderer section selection;
- dirty-section compilation decisions;
- synchronous/asynchronous rebuild ownership;
- SectionRenderDispatcher queueing;
- RenderSection task lifetime;
- SectionCompiler CPU mesh work;
- render-thread upload boundaries.

Patch 045a may modify scheduling only after this census identifies a seam that:
- leaves simulation untouched;
- leaves chunk loading untouched;
- leaves saving untouched;
- preserves dirty state for deferred work;
- prevents duplicate task churn;
- cannot create visible holes for HOT sections.

Canonical evidence is retained in:

`_dropoff/state/045_view_directed_chunk_meshing/`
## Patch 045a finding

The exact 1.21.1 bytecode census corrected the original assumption.

`LevelRenderer.compileSections(Camera)` iterates `visibleSections`, not every
section in the configured ViewArea.

`visibleSections` is populated through `SectionOcclusionGraph` and Frustum
selection before compilation.

Therefore adding a second COLD/offscreen compile filter at `compileSections`
would duplicate vanilla behavior and provide little steady-state benefit.

Patch 045a instead frame-budgets two remaining render-thread spike sources:

1. synchronous visible-section rebuilds;
2. completed mesh uploads.

No mesh work is discarded.

After one synchronous rebuild in a compile pass, additional sync candidates are
sent through the existing vanilla `rebuildSectionAsync` path.

`uploadAllPendingUploads()` is changed from an unbounded queue drain to at most
12 queued upload Runnables per pass by default. Remaining entries stay queued.

Developer overrides:

`-Dpotato.chunk.maxSyncBuildsPerPass=<0..8>`

`-Dpotato.chunk.maxUploadsPerPass=<1..64>`

This milestone targets frame pacing/stutter. It is not expected to solve
steady-state 32-chunk draw cost.

The next large steady-state performance milestone is:

`POTATO_GPU_OCCLUSION_HZB_FOUNDATION`
## Patch 045b - adaptive upload time budget

Patch 045a proved that bounding completed mesh uploads has a very large
frame-pacing benefit at 32 chunks.

The first implementation used a fixed limit of 12 upload Runnables per pass.
That protected FPS but could temporarily make visible chunk completion slower.

Patch 045b keeps the safety property while replacing the fixed limit with:

- minimum forward progress: 4 uploads/pass;
- soft wall-time budget: 1.5 ms/pass;
- hard count cap: 32 uploads/pass.

After at least 4 uploads, the next queue poll stops the pass when elapsed
render-thread upload time reaches 1.5 ms.

Cheap uploads can therefore exceed the old fixed 12 limit and finish chunks
faster.

Expensive uploads stop early before they monopolize a frame.

Remaining Runnables stay in the original vanilla queue and resume on a later
pass.

Developer overrides:

`-Dpotato.chunk.minUploadsPerPass=<1..128>`

`-Dpotato.chunk.maxUploadsPerPass=<1..128>`

`-Dpotato.chunk.uploadTimeBudgetNanos=<250000..8000000>`