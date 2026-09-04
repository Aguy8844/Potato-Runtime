# Potato Runtime Engineering Principles

Potato Runtime treats code quality as a performance feature. A renderer that is
fast today but impossible to reason about tomorrow is not considered optimized.

## Core rules

1. **Measure before optimizing**
   - Performance claims require benchmark evidence.
   - Prefer frame-time distributions and bottleneck metrics over FPS anecdotes.

2. **Explicit ownership**
   - Native Vulkan objects have a clear owner.
   - Cleanup order mirrors creation order.
   - Partial initialization must be safe to unwind.

3. **Small responsibilities**
   - Orchestrators coordinate.
   - Selectors select.
   - Inspectors inspect.
   - Resource owners own resources.
   - Avoid God classes and utility dumping grounds.

4. **No magic hardware assumptions**
   - Capability detection drives feature selection.
   - Native drivers are preferred, but fallback paths remain explicit.

5. **Fallback is architecture, not error handling**
   - A failed experimental subsystem must not make Minecraft unusable.
   - Potato degrades individual capabilities instead of collapsing wholesale.

6. **Version boundaries stay visible**
   - Minecraft/loader-specific code belongs behind platform adapters.
   - Renderer/core logic should not casually import loader APIs.

7. **Optimize hot paths, not source-code golf**
   - Readable code is preferred until profiling proves structure itself is a cost.
   - Allocation, synchronization and cache behavior matter more than clever syntax.

8. **Automated support claims**
   - A Minecraft version is not "supported" until the relevant automated test
     suite passes for it.

## Vulkan-specific direction

- One command pool per recording thread once multi-threaded recording begins.
- Avoid unnecessary per-frame allocation.
- Prefer pooled/reused command buffers and explicit lifecycle management.
- Prefer modern Vulkan capabilities when detected, never when merely assumed.
- Keep diagnostics independent from the eventual production renderer.
## Window/API ownership

- Never attach Vulkan presentation to a GLFW window already owned by OpenGL.
- The final Vulkan backend must establish window/API ownership at creation time.
- Bootstrap probes must remain isolated from Minecraft's production window.
- Graphics and presentation queue families are modeled independently even when
  a specific GPU exposes one family for both.
## Swapchain policy

- Never hard-code swapchain image count, extent, format or presentation support.
- Treat surface capabilities as runtime data.
- `VK_PRESENT_MODE_FIFO_KHR` is the bootstrap portability baseline; production
  frame-pacing policy is configurable and capability-driven.
- Use concurrent swapchain sharing only when graphics and present families
  differ; otherwise prefer exclusive sharing.
- `vkDeviceWaitIdle` is acceptable for short-lived bootstrap cleanup, not as a
  normal per-frame synchronization strategy.
## First-pixel probe

- Swapchain usage flags are capability-checked before creation.
- Synchronization waits at the first actual consuming stage (`TRANSFER`) rather
  than a generic graphics stage.
- Pixel-writing command recording is isolated from swapchain ownership.
- Transfer-clear is a bootstrap proof only; the production renderer will use
  appropriate render/compute paths selected by workload and measured cost.
## Visual diagnostics

- Visual diagnostics are opt-in; headless/hidden verification remains default.
- Debug windows must not steal focus from Minecraft.
- Debug visualization must reuse the real validated GPU path instead of a
  separate fake rendering implementation.
- Temporary startup stalls are allowed only behind explicit development flags,
  never in the normal runtime path.
- Shader source and compiled SPIR-V must become first-class assets; do not embed
  opaque shader byte arrays into Java source merely to accelerate prototyping.
## Shader ownership

- Human-readable shader source is the source of truth.
- Never store opaque SPIR-V integer arrays in Java source.
- Shader compilation failures are explicit staged failures.
- Shader modules are destroyed immediately after successful pipeline creation.
- Pipeline ownership is independent from command recording.
- Production should move toward build-time/cached SPIR-V and pipeline caches
  once the renderer architecture stabilizes; startup compilation is confined to
  the development/bootstrap probe.

## Dynamic rendering

- Required Vulkan features are queried before device creation.
- `dynamicRendering` is explicitly enabled in the logical-device feature chain.
- Pipeline attachment formats are declared with
  `VkPipelineRenderingCreateInfo`.
- Render targets are bound at recording time through `VkRenderingInfo`.
## Frame lifetime

- Frames-in-flight are not swapchain images.
- CPU-reused frame resources are guarded by that frame's fence.
- Acquire semaphores are per frame-in-flight.
- Present-wait semaphores are per swapchain image so they are not reused merely
  because a graphics submit fence completed.
- Command buffers are reused and reset; they are not allocated per frame.
- `vkDeviceWaitIdle` is forbidden in the steady-state frame path.
- Image layout history is explicit per swapchain image.

## Small per-draw data

- Push constants are preferred for tiny, frequently changing values when they
  fit the workload.
- Large or persistent scene data belongs in proper GPU buffers, not push
  constants.
## Swapchain invalidation

- Never assume a swapchain remains compatible with its surface indefinitely.
- Treat OUT_OF_DATE as mandatory recreation.
- Treat SUBOPTIMAL as a successful-but-recreate signal.
- Do not rebuild frame-in-flight resources merely because swapchain images
  changed.
- Swapchain image lifetime and frame-slot lifetime remain separate.
- Re-query surface capabilities for every generation.
- Pipeline compatibility includes attachment format; format changes must rebuild
  format-dependent pipelines.
- Minimized/zero-sized framebuffers must not create fake 1x1 production
  swapchains; wait until a renderable framebuffer exists.
## Bootstrap interception

- Window bootstrap interception must be fail-fast and version-specific.
- Observation and mutation are separate milestones.
- A backend switch must never silently change GLFW window semantics before all
  downstream renderer dependencies are ready.
- Constructor callback injection is restricted to `RETURN`; earlier constructor
  control must use an appropriate call-site injector such as the explicit
  `glfwCreateWindow` redirect.
- The interception wrapper must preserve every native call argument until a
  later patch intentionally changes backend policy.
## Handoff wrappers

- Prefer wrapping the loader's real handoff operation over targeting a vanilla
  call that no longer exists in transformed bytecode.
- Preserve supplier/lambda arguments as opaque objects unless the production
  contract explicitly requires evaluating them.
- Observation wrappers must delegate exactly once.
- Required bootstrap seams stay fail-fast; never hide a missing target with
  `require = 0`.
- Runtime evidence wins over assumptions based on vanilla source layout.
## Backend cutover rules

- Keep backend-neutral GLFW responsibilities shared.
- Never equate "uses GLFW" with "requires OpenGL".
- Replace renderer responsibilities, not hundreds of individual GL calls.
- A `GLFW_NO_API` main-window mutation is forbidden while any earlier owner has
  already created the handed-off window with an OpenGL context.
- Evidence gates must fail closed when the expected runtime architecture is not
  present.
- VSync is backend policy: swap interval for OpenGL, present mode for Vulkan.
- Native window destruction occurs only after backend-owned GPU/surface cleanup.
## Replacement-window rehearsal rules

- A rehearsal candidate never replaces the production handle.
- Create the candidate only after the EarlyDisplay provider completed its
  handoff.
- Never destroy or mutate the EarlyDisplay window during rehearsal.
- GLFW window hints are process-global; restore defaults immediately after
  candidate creation.
- Creating a NO_API candidate must not alter the currently bound OpenGL context.
- The Vulkan probe must adopt the exact handoff candidate; generic fallback
  windows are forbidden for this milestone.
- Candidate Vulkan resources are destroyed before the candidate GLFW window.
## Context-bootstrap bypass rules

- Constructor redirects must have evidence-backed exact targets and `require=1`.
- Rehearsal redirects keep a baseline fallback so bootstrap experiments do not
  unnecessarily destroy Minecraft startup.
- A rehearsal is considered PASS only when no fallback was required.
- Never fabricate a Vulkan hardware limit constant when the physical device can
  be queried.
- Early capability probes may create a VkInstance, but not a logical device or
  renderer state unless explicitly owned by that subsystem.
- Temporary Vulkan instance creation must not disturb the currently bound
  OpenGL context during the transition period.
- Reuse existing LWJGL `GLCapabilities` only when they are already installed on
  the current render thread.
## Diagnostics are never startup-fatal

- Probe/rehearsal failures return structured fail-safe reports.
- Diagnostic exceptions must not escape into NeoForge lifecycle/deferred-work
  queues.
- `FAIL_SAFE_FALLBACK` means Potato remains on the known-good OpenGL backend.
- A transition bridge is not mislabeled as a fallback when it is intentionally
  required by a still-active baseline subsystem.
- Backend boundaries may overlap during migration; encode that dependency
  instead of forcing an artificial all-or-nothing milestone.
## Renderer initialization transition rules

- Do not bypass a renderer bootstrap method until its side effects are mapped.
- Observe one exact high-level ownership seam instead of scattering low-level
  GL suppressions.
- Baseline rehearsal wrappers delegate exactly once.
- A backend transition must preserve thread and native-window/context ownership
  unless the milestone explicitly replaces them.
- Thread-local `GLCapabilities` are part of the temporary OpenGL transition
  contract, not a backend-neutral service.
- Renderer/API diagnostics must become backend-owned rather than pretending a
  Vulkan renderer is OpenGL.
- Diagnostic rehearsal failure returns a structured OpenGL fallback; it never
  intentionally poisons NeoForge lifecycle state.
## Backend dispatch ownership rules

- Once a high-level responsibility has a Potato dispatcher, Minecraft must not
  continue executing the old owner in parallel.
- Prove zero old-owner calls with independent observers where practical.
- Preserve backend-neutral diagnostics explicitly rather than keeping an entire
  OpenGL initialization method alive for one field assignment.
- Backend metadata must describe the active backend truthfully.
- OpenGL transition bridges are named as such and may not be mistaken for
  backend-neutral services.
- Prefer accessor mixins over reflection for narrow, version-verified static
  state seams.
## Render-target ownership rules

- Do not reinterpret OpenGL integer IDs as generic backend handles.
- Logical target dimensions/state and native GPU resources are separate layers.
- Preserve MainTarget's special initial allocation fallback semantics.
- Preserve the inherited destroy-and-recreate resize lifecycle.
- Track backend state per RenderTarget instance instead of a global
  identity-map when the target object can own the state directly.
- Observation milestones leave baseline OpenGL behavior untouched.
- Vulkan image/view/memory lifetime must be explicit and independently
  verifiable before any Minecraft target method is redirected.
## Vulkan image ownership rules

- Every Potato-owned VkImage has explicit memory and view ownership.
- Select memory from the physical device's compatible memory types; do not
  assume memory type indices.
- Prefer device-local attachment memory for render targets.
- Validate image-format usage before allocation.
- Transition image layouts explicitly before GPU use.
- Use finite synchronization waits in bootstrap/probe work.
- Destroy views before images and images before freeing bound memory.
- Never encode native Vulkan handles into Mojang OpenGL integer-ID fields.
- A resource prototype does not imply Minecraft has switched backend ownership.
## Offscreen presentation rules

- Scene pipelines target scene/offscreen attachment formats, not swapchain
  formats.
- Swapchain acquisition synchronization begins at the first pipeline stage that
  actually accesses the acquired image.
- Validate blit source/destination format features before recording blits.
- Swapchain images used as blit destinations require
  `VK_IMAGE_USAGE_TRANSFER_DST_BIT`.
- Return reusable offscreen images to their expected attachment layout before
  the next frame.
- A swapchain format change does not recreate an offscreen graphics pipeline
  unless the offscreen attachment format itself changes.
- On exceptional teardown, wait for submitted GPU work before destroying
  command pools or attachment resources.
## Persistent backend lifetime rules

- Probe validation and runtime ownership are separate responsibilities.
- Successful probe resources may be transferred; failed probe resources are
  destroyed immediately.
- Try-with-resources owners become no-op wrappers only after explicit transfer.
- Runtime verification occurs after the original probe scope has exited.
- A persistent runtime owns exactly one active Instance/Device/presentation/frame
  stack.
- Shutdown is idempotent.
- GPU work is idle before frame resources are destroyed.
- Surface/window resources die before the owning VkInstance.
- Native Vulkan resources are never left to JVM finalization.
- The runtime report is rewritten after normal Minecraft shutdown so teardown is
  externally verifiable.
## Live RenderTarget dispatch rules

- Minecraft mixins dispatch semantic operations, not Vulkan calls.
- Backend implementations register a narrow operation sink.
- Generic/non-main RenderTargets are ignored until their ownership model is
  explicitly designed.
- If a public Minecraft method can queue work to the Render thread, dispatch
  from the private actual-execution method rather than from the public request.
- Mirror milestones never cancel the baseline OpenGL call.
- Backend sink installation and removal follow backend runtime lifetime.
- Runtime dispatch verification is based on live calls after startup diagnostics,
  not bootstrap calls observed before Vulkan became persistent.
- GPU execution is introduced one operation family at a time after semantic
  dispatch has been proven.
## Live Vulkan resize rules

- Coalesce window-drag resize requests before allocating GPU resources.
- Do not destroy the current target until the replacement is fully created.
- Keep the previous target installed on replacement failure.
- Complete submitted work before destroying images or image views that earlier
  command buffers referenced.
- A resize may use a conservative device-idle boundary because it is
  event-driven and coalesced; never move that wait into the per-frame path.
- Recreate a dynamic-rendering graphics pipeline only if its declared
  color/depth attachment formats actually change.
- Offscreen target extent and presentation/swapchain extent remain separate
  responsibilities.
- Previously recorded command buffers that reference retired attachments must
  be re-recorded before any future submission.
## Live Vulkan clear rules

- Treat clear as semantic state until ordering against pending target mutations
  is resolved.
- Apply target resize before clear when both are pending.
- Clear both color and depth when the Minecraft target uses depth.
- Return images to their normal attachment layouts after an immediate clear.
- Use a finite fence wait for proof-only immediate submissions.
- Do not place a device-wide idle wait on each clear.
- Coalesce pending clears.
- Duplicate clear elimination is valid only while no intervening Vulkan runtime
  draw can modify the mirrored target; revoke that optimization when draw
  dispatch is introduced.
- Clear failures remain contained because OpenGL already executed the real
  Minecraft operation.
## Live Vulkan presentation rules

- Reuse persistent frame synchronization instead of allocating per blit.
- Wait only the selected frame/image fences; never device-idle per present.
- Acquire synchronization gates the transfer stage because the swapchain image
  is first touched by the blit.
- Restore the offscreen color image to its attachment layout after presentation.
- Keep semantic Minecraft destination size separate from the hidden Vulkan
  swapchain extent; the transfer may scale between them.
- Do not present a target while a newer resize or clear is pending.
- Throttle mirror-only presentation while OpenGL is still authoritative.
- On mirror presentation failure, disable further mirror presents rather than
  risking repeated stalls or invalid fence reuse.
- Revalidate source/destination blit compatibility when a replacement offscreen
  target changes format.
## Coherent frame lifecycle rules

- Do not submit independent GPU work for semantic operations that belong to the
  same logical frame when one command buffer can preserve their ordering.
- Treat clear as pending frame state until presentation closes the frame.
- Resource-lifetime operations such as attachment resize remain outside the
  per-frame command stream.
- Apply a pending resize before recording any frame that references the new
  target dimensions.
- Reuse the persistent frame ring and swapchain synchronization.
- Never allocate a transient command pool/fence for each live clear or present.
- Keep the OpenGL-authoritative mirror throttle until Vulkan owns the visible
  frame path.
- Preserve backend-neutral Mixin dispatch; native command recording belongs
  below the backend boundary.
- Map the actual Minecraft draw-submission boundary before intercepting draw
  calls.
## Draw submission contract rules

- Never reinterpret OpenGL buffer IDs as backend-neutral resource handles.
- Capture geometry while MeshData is alive, before vanilla closes it.
- Ephemeral ByteBuffer views must not escape the synchronous upload callback.
- A backend that needs persistent geometry must copy/upload it explicitly.
- Per-draw shader/matrix state is captured at the actual render-thread draw
  execution seam, not the public possibly-deferred wrapper.
- Persistent and immediate VertexBuffers share the same lifecycle contract.
- Backend sink failure disables only the draw mirror; OpenGL rendering
  continues.
- Patch 033 never cancels upload, draw or close.
- A real Vulkan geometry backend must own its own VkBuffer/VkDeviceMemory
  lifetime behind DrawBufferBackendState.
## Prototype geometry upload rules

- The transition renderer must not mirror every Minecraft upload merely to prove
  that a Vulkan upload seam works.
- Prototype native-resource admission is bounded and explicit.
- Dynamic proof uploads are throttled while OpenGL remains authoritative.
- Backend-neutral DrawBufferBackendState never stores VkBuffer/VkDeviceMemory
  handles.
- Vulkan native resources live in backend-specific sidecars.
- Host-visible coherent memory is preferred for the first upload prototype.
- Non-coherent fallback must flush mapped writes.
- Persistently mapped prototype memory avoids map/unmap churn.
- No queue submission is required merely to populate a host-visible VkBuffer.
- No vkDeviceWaitIdle is allowed per geometry upload.
- Real draw execution must not begin until direct VertexBuffer.draw() state
  ownership is mapped.
## Section-layer context rules

- Capture direct chunk draw state at LevelRenderer.renderSectionLayer, not at
  the terminal OpenGL draw call.
- RenderType setup remains authoritative while OpenGL is the active backend.
- ShaderInstance.apply occurs once per section layer, not once per section.
- CHUNK_OFFSET is per-section state and must be part of every backend draw
  submission.
- Model-view and projection matrices are layer-level state and may be reused by
  section draws within the same layer.
- Keep texture/sampler ownership transitional until a dedicated resource
  abstraction exists.
- Do not issue Vulkan draw commands until a live section draw is correlated with
  a current Vulkan geometry sidecar.
## First real section-draw prototype rules

- First Vulkan Minecraft draw is one-shot and bounded.
- Baseline OpenGL draw remains authoritative.
- Use only a live current Vulkan geometry sidecar.
- Do not draw stale upload generations.
- Do not reinterpret Minecraft/OpenGL sequential index buffers as Vulkan
  resources; synthesize backend-owned indices.
- QUADS must be expanded to triangle-list topology.
- Wait on a dedicated finite fence for the one-shot proof so Minecraft cannot
  retire the selected VertexBuffer while the proof is still in flight.
- Do not use vkQueueWaitIdle or vkDeviceWaitIdle for the proof draw.
- Prove rasterization with an occlusion query, not only VkResult success.
- Texture/lightmap/fog fidelity is a separate resource-abstraction milestone.
## BLOCK resource capture rules

- Never retain Minecraft `NativeImage` instances as backend resources.
- Copy source pixels synchronously at the proven resource lifecycle boundary.
- Store owned RGBA8 bytes with explicit packing semantics.
- Never use OpenGL texture ids as backend-neutral resource handles.
- Never use OpenGL readback when the same CPU resource already exists.
- Atlas upload/reload increments a generation.
- Lightmap generation increments only when pixel contents change.
- Keep resource-capture failure isolated from Minecraft resource loading.
- Derive vertex offsets from `VertexFormat`; do not hard-code them as the
  backend-neutral source of truth.
- Patch 040 captures base atlas mip only; Vulkan mip support is a later fidelity
  step.
## Runtime performance safety policy

Patch 040b separates historical proof instrumentation from normal runtime.

Default release-oriented behavior:
- no global shader-draw census;
- no global plain VertexBuffer draw census;
- no per-event dispatcher lock;
- no eager DrawGeometryView for unadmitted resources;
- no section Matrix4f snapshots for unmirrored buffers;
- no repeated Patch-037 one-shot fence/query draw;
- no continuous hidden Vulkan frame presentation while OpenGL is visible;
- only rare MainTarget resize is mirrored before visible Vulkan cutover.

The full hidden frame mirror may be enabled for targeted development with:

`-Dpotato.dev.hiddenFrameMirror=true`

Historical proof milestones are documentation/evidence, not work that should be
repeated every player session.

Hot-path instrumentation must justify its runtime cost.
## Ultra hot-path filtering

Patch 040d narrows runtime work to the exact next Vulkan milestone.

Before a Potato sidecar or DrawGeometryView is created, VertexBuffer uploads are
filtered to:
- VertexBuffer.Usage.STATIC;
- DefaultVertexFormat.BLOCK;
- QUADS;
- sequential index path.

LevelRenderer uses `potato$drawBackendStateIfPresent()` so merely seeing an
untracked section buffer does not allocate backend state.

DYNAMIC Vulkan geometry mirroring is disabled until a later milestone has a
specific consumer for it.

Lightmap capture occurs only after Minecraft itself executes
`DynamicTexture.upload()` inside `LightTexture.updateLightTexture`. Calls that
return early because the vanilla dirty flag is false perform zero Potato pixel
copy/CRC work.
## Vulkan texture upload policy

- Never upload a captured texture generation merely because it changed unless
  a Vulkan consumer needs that generation.
- Initial resource proofs may use one finite fence wait.
- Never use `vkDeviceWaitIdle` for ordinary texture uploads.
- Reuse staging/command/fence resources.
- Keep device-local sampled images backend-owned.
- Keep CPU snapshots backend-neutral.
- Live lightmap synchronization must be coalesced with actual Vulkan frame
  consumption rather than copied blindly on every vanilla update.
## Native resource lifetime diagnostics

A successful native resource must keep two different diagnostic facts:

1. historical proof — the resource was created, uploaded and valid while live;
2. current state — the native handle may correctly be zero after teardown.

Never overwrite a successful runtime proof with a post-shutdown zero handle.

For Vulkan texture resources Patch 041b reports both:
- `*HandleNonZero` = historical creation proof;
- `*HandleCurrentlyNonZero` = current native state;
- `*TeardownVerified` = all native resources were released.

`blockTextureUploadVerified` remains a latched lifetime proof after clean
shutdown.
## Textured draw proof policy

- Descriptor sets are persistent resources, not per-draw allocations.
- The first textured proof may use one finite fence/query verification.
- Never re-enable global draw census to prove one narrow Vulkan path.
- Derive BLOCK attribute offsets from `BlockVertexLayoutSnapshot`.
- Do not expose mipmapped render layers until the Vulkan atlas owns matching
  mip levels.
- Live texture generation synchronization must be demand-driven and coalesced.
- A proof draw must leave the OpenGL baseline authoritative until visible
  cutover is separately verified.
## One-shot proof retirement

A bounded proof must leave the hot path immediately after its first terminal
attempt.

Patch 042a applies this rule to the textured section proof:

- before proof:
  - STATIC BLOCK uploads may be mirrored;
  - tracked section draws may create exact SectionLayerDrawContext snapshots;

- after first GPU attempt:
  - `wantsUpload()` returns false;
  - `wantsSectionLayerDraw()` returns false;
  - no further model/projection Matrix4f copies are created for this milestone;
  - no new Vulkan geometry mirrors are created for this milestone;
  - OpenGL remains authoritative.

The retirement predicate is a volatile boolean and does not call synchronized
verification from the draw hot path.

Future multi-section rendering must opt in through a new production frame
consumer rather than silently keeping a completed proof active.
## Bounded proof retry

A one-shot rasterization proof must not treat one zero-sample eligible draw as
a renderer failure.

An eligible section can be fully clipped or occluded while the Vulkan command
submission itself is valid.

Patch 042b therefore allows at most 8 GPU proof attempts:
- retire immediately on positive rasterization proof;
- retire immediately on contained Vulkan failure;
- otherwise retire after the fixed attempt budget.

The retry window is deliberately tiny. It must never turn into a permanent
per-section observer.

After retirement, Patch 042a's lock-free hot-path rejection remains active.
## Layer-scoped section batching

The production chunk path must never copy model/projection matrices per
section.

Capture them once per RenderType layer, then represent each section by its
backend resource and ChunkOffset.

For solid/cutout BLOCK rendering:
- bind pipeline once per batch;
- bind descriptors once per batch;
- use one shared sequential QUADS index buffer;
- do not queue-submit or fence-wait per draw.

Validation fence waits are allowed only while OpenGL remains authoritative.
The visible renderer must use frame-ring synchronization instead.
## Visibility is not simulation

Potato Visibility Engine decisions are renderer scheduling decisions only.

HOT/WARM/COLD may influence render work, but must never silently change:
- world ticking;
- entity AI/ticking;
- block entity ticking;
- chunk saves;
- game mechanics.

The actual render-camera matrices are authoritative. Never substitute the
player entity position for camera visibility because third-person, spectator
and camera mods can move the rendered point of view.

COLD work may be deferred only when the visible result is unaffected.
## Frame-budgeted chunk work

Do not duplicate vanilla visibility filtering without evidence.

Patch 045 proved that chunk compilation already iterates
`LevelRenderer.visibleSections`.

Potato's first scheduler intervention therefore controls frame-time spikes
rather than discarding additional offscreen meshes:

- preserve vanilla dirty/light/player-priority decisions;
- allow a bounded number of synchronous rebuilds per compile pass;
- redirect overflow to vanilla asynchronous rebuild tasks;
- bound render-thread upload draining;
- leave deferred upload Runnables in the original queue;
- never clear dirty state unless work has already executed or been scheduled.

This scheduler is a frame-pacing feature. Occlusion/LOD are separate
steady-state draw-reduction systems.
## Adaptive upload wall-time budget

Fixed work-count limits are useful for proving frame-pacing behavior but are
not ideal production scheduling.

Different modpacks produce wildly different upload Runnable costs.

Potato therefore bounds completed chunk uploads by:
- guaranteed minimum progress;
- actual elapsed render-thread wall time;
- a hard count ceiling.

Cheap work can use spare frame time while expensive work yields early.
No queue entries are discarded.
## Bounded hierarchy observation

Do not leave experimental hierarchy instrumentation permanently active on every
terrain draw.

The Patch 046 observer:
- samples only RenderType.solid;
- uses primitive fixed-capacity tables;
- allocates no object per observed section;
- retires after a bounded proof window;
- leaves only a cheap boolean branch after retirement.

Screen-space LOD decisions must be based on actual projected error rather than
distance alone.
## Viewport-bounded projection diagnostics

Any metric described as visible pixel occupancy must be physically bounded by
the framebuffer dimensions.

Raw finite NDC values are not sufficient because geometry near the camera plane
can project to arbitrarily large coordinates.

Diagnostic projection must clip to the viewport, and near-plane intersections
must remain conservative.

Do not use cluster footprint alone as a lossy LOD decision. Future LOD requires
a projected surface-error bound.
## Strict surface merging before LOD

Do not spend a visual-error budget when exact redundancy can be removed first.

Lossless surface merging must use conservative material/shading signatures.
If RenderType, texture sampling semantics or shading equivalence are not known,
the renderer must not substitute the original mesh.

Census code may measure candidate geometry without being production-safe.
Production mutation requires an explicit stronger contract.
## Surface attributes must not dictate topology

For far-field rendering, baked per-face shading/material values should not force
the renderer to retain one geometry quad per block.

When exact reconstruction is possible from a compact per-tile attribute buffer,
topology and surface attributes may use different granularities.

This trade must remain screen-space aware: extra fragment-buffer lookups are
appropriate only where they save more CPU/vertex/draw work than they cost.
## Historical Vulkan proofs stay retired

A positively verified bounded research proof is not a production workload.

Patch 047b removes the old Patch 043/044 validation path from normal launches.
Release operation no longer allocates geometry mirrors, uploads validation-only
textures, records hidden multi-section proof batches, or waits for proof fences
just to re-prove an established milestone.

Explicit diagnostic re-enable:

`-Dpotato.debug.multiSectionValidation=true`

Only the current milestone should remain live in the normal hot path.
## Surface topology can be procedural

Merged axis-aligned surface rectangles do not require a traditional four-vertex
BLOCK stream.

A compact rectangle descriptor can later generate corners procedurally in the
shader. Keep topology, exact tile attributes and Vulkan resources as separate
layers.