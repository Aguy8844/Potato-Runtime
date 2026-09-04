# Potato Runtime Architecture

The source tree is intentionally separated by responsibility even while Version 1 has only one NeoForge 1.21.1 build.

- `api/` — stable internal interfaces shared by future adapters
- `core/` — loader-agnostic runtime decisions and orchestration
- `diagnostics/` — benchmarks, reports, telemetry and self-tests
- `platform/` — Minecraft/mod-loader-specific bridges
- `platform/neoforge/` — NeoForge-specific implementation
- `render/` — renderer-agnostic rendering infrastructure
- `render/vulkan/` — Vulkan backend
- `compat/` — targeted compatibility adapters and feature gating
- `test/` — Potato self-test framework

The first implementation stays deliberately simple. We only split into Gradle subprojects once there is a real second platform/version target; premature multi-project complexity would slow down Version 1.

## Vulkan foundation

The startup probe is intentionally decomposed:

- `VulkanProbe` — orchestration only
- `VulkanProbeContext` — explicit native resource ownership and cleanup
- `VulkanDeviceCatalog` — physical-device inspection and scoring
- `VulkanQueueFamilySelector` — queue-family capability selection
- `VulkanFormat` — Vulkan-specific formatting helpers
- `VulkanProbeException` — known probe-stage failures

The production renderer will not be implemented inside the startup probe. The
probe exists to validate hardware/runtime capabilities; renderer state will
receive its own lifecycle and abstractions.
## Vulkan GPU submission probe

`VulkanSubmissionProbe` owns the startup-only command-submission validation:

1. create a transient command pool for the selected graphics queue family;
2. allocate one primary command buffer;
3. record an empty one-time command buffer;
4. submit it to the graphics queue;
5. synchronize host completion with a fence;
6. destroy the fence and command pool deterministically.

This remains separate from the future frame renderer. The startup probe proves
driver/runtime correctness; production rendering will use persistent,
frame-oriented command infrastructure rather than recreating these objects per
frame.
## Hidden presentation surface probe

Minecraft 1.21.1 creates its main GLFW window with an OpenGL context. GLFW does
not allow a Vulkan surface to take presentation ownership of that same window.

During the bootstrap phase Potato therefore validates presentation separately:

1. query the GLFW-required Vulkan instance extensions;
2. create a tiny invisible `GLFW_NO_API` window;
3. create `VkSurfaceKHR` for that window;
4. inspect graphics and present queue-family support independently;
5. run the existing empty GPU submission probe;
6. destroy the surface and hidden window without touching Minecraft's window.

This hidden window is strictly a development/bootstrap validation device. The
production Vulkan backend will eventually intercept Minecraft window creation
early enough for the real main window to be created without an OpenGL context.
## Hidden swapchain roundtrip

The bootstrap probe now validates the entire Vulkan WSI chain without touching
Minecraft's OpenGL-owned main window:

1. validate `VK_KHR_swapchain`;
2. inspect all queue families and presentation support;
3. query surface capabilities, formats and present modes;
4. derive image count, format, extent and sharing mode from driver data;
5. create `VkSwapchainKHR`;
6. retrieve swapchain images;
7. acquire one image through a semaphore;
8. record a first-use layout transition to `PRESENT_SRC_KHR`;
9. submit on the graphics queue and synchronize with a fence + semaphore;
10. present on the presentation queue;
11. wait only for probe cleanup and destroy resources in dependency order.

The old empty `VulkanSubmissionProbe` was removed because this roundtrip
strictly supersedes it.

The production renderer must not recreate these resources per frame. It will
own persistent swapchain/frame contexts and recreate only when the window or
surface requires it.
## First pixel path

The bootstrap probe can now write actual pixel data to a swapchain image:

1. acquire a swapchain image;
2. transition `UNDEFINED -> TRANSFER_DST_OPTIMAL`;
3. clear the complete color subresource with `vkCmdClearColorImage`;
4. transition `TRANSFER_DST_OPTIMAL -> PRESENT_SRC_KHR`;
5. submit with the acquire semaphore waited at the transfer stage;
6. signal the present-ready semaphore;
7. present the image.

`VulkanClearFrameRecorder` owns only command recording. Swapchain lifetime,
acquisition, submission and presentation remain owned by `VulkanSwapchainProbe`.

This is intentionally not the future Minecraft rendering strategy. It is the
smallest pipeline-free proof that Potato can generate pixel data and present it.
## Visible frame verification

The first-pixel bootstrap remains hidden by default.

Developers may explicitly request a visual verification window with:

`POTATO_VULKAN_PROBE_VISIBLE=1`

The probe window is still created as `GLFW_NO_API` and starts hidden. Potato
shows it only after Vulkan surface and queue validation, then presents the same
real swapchain frame used by the automated probe. The visual window is held
briefly while GLFW events are processed and is then destroyed.

This path is a development diagnostic only. It is not a second renderer window,
not a release UI and not part of Minecraft's eventual main-window lifecycle.
## Foreground visual verification

The opt-in Vulkan visual probe is deliberately stronger than normal application
window behavior because its only purpose is human verification.

When enabled it:
- centers the debug window on the primary monitor;
- marks it floating/always-on-top;
- focuses and requests attention when shown;
- raises/focuses it again after the Vulkan frame is presented;
- records GLFW `VISIBLE`, `FOCUSED`, `FLOATING` and position state in the report.

None of this behavior exists in the normal hidden probe or future production
renderer.
## First graphics pipeline

Patch 011 moves the probe from transfer-clearing pixels to an actual graphics
pipeline.

Responsibilities:
- `VulkanFeatureSet` — queries and enables required Vulkan 1.3 features.
- `VulkanShaderCompiler` — loads GLSL resources and compiles them to owned SPIR-V.
- `VulkanTrianglePipeline` — owns pipeline layout + graphics pipeline.
- `VulkanTriangleFrameRecorder` — records layout transitions, Dynamic Rendering
  begin/end, dynamic viewport/scissor and one `vkCmdDraw(3, 1, 0, 0)`.
- `VulkanSwapchainProbe` — continues to own swapchain acquisition,
  synchronization and presentation.

The triangle uses `gl_VertexIndex`, so the first pipeline needs no vertex
buffers, descriptors or push constants. This keeps the milestone focused on
pipeline correctness rather than unrelated resource systems.

GLSL source files are first-class resources. Runtime Shaderc compilation is a
bootstrap/development strategy, not a mandate for the eventual production
renderer.
## Persistent frame ring

Patch 012 replaces the one-shot swapchain probe with reusable renderer-shaped
resources:

- `VulkanSwapchainResources`
  - owns the swapchain;
  - owns all swapchain image views;
  - owns one render-finished binary semaphore per swapchain image;
  - tracks first-use image layout state.

- `VulkanFrameRing`
  - owns two frames-in-flight;
  - one command buffer per frame;
  - one acquire semaphore per frame;
  - one in-flight fence per frame;
  - one resettable command pool for this single-threaded probe.

- `VulkanFrameLoopProbe`
  - waits only for the frame being reused;
  - acquires an image;
  - re-records that frame command buffer;
  - submits without per-frame `vkDeviceWaitIdle`;
  - presents using the semaphore belonging to the acquired swapchain image;
  - advances through the frame ring.

The visible probe animates the triangle with a four-byte vertex-stage push
constant. Hidden startup validation renders a small fixed number of frames.

This architecture intentionally separates frame lifetime from swapchain-image
lifetime. Those are related but not identical concepts in Vulkan.
## Swapchain generations

A swapchain is not treated as permanent renderer state.

Patch 013 introduces explicit swapchain generations:

- the frame ring survives resize/recreation;
- framebuffer extent changes are detected before acquire;
- `VK_ERROR_OUT_OF_DATE_KHR` from acquire or present triggers recreation;
- `VK_SUBOPTIMAL_KHR` completes the current acquired frame, then recreates;
- zero-sized framebuffers are waited out before creating a new generation;
- a new swapchain receives the previous swapchain through `oldSwapchain`;
- the old generation is destroyed only after the replacement is created;
- per-image fences/layout history are reset for the new image set;
- if the surface color format changes, the graphics pipeline is recreated too.

The visible verification probe forces one deterministic 640x360 -> 800x450
resize roughly one third into the run.

`vkDeviceWaitIdle` is currently allowed on the rare recreation path for
correctness and deterministic bootstrap testing. It remains forbidden from the
steady-state per-frame path.
## Minecraft window bootstrap seam

Patch 014 establishes the first direct integration point with Minecraft's real
native window.

A client-only Mixin targets `com.mojang.blaze3d.platform.Window` and redirects
the exact `GLFW.glfwCreateWindow(int, int, CharSequence, long, long)` call
through Potato.

Current behavior is deliberately transparent:
- all width/height/title/monitor/share arguments are forwarded unchanged;
- GLFW still creates Minecraft's original OpenGL window;
- no GLFW hints are changed;
- no OpenGL calls are disabled;
- Potato does not render to the Minecraft window yet.

A constructor-RETURN callback records the completed window/context contract.
The Vulkan report therefore proves both:
1. Potato can intercept the native creation call that will eventually choose
   the backend window type.
2. Minecraft currently finishes bootstrap with an OpenGL-owned GLFW window.

This seam is the future backend switch boundary. Actual Vulkan takeover must not
be enabled until downstream OpenGL initialization/render assumptions have also
been isolated.
## NeoForge early-window handoff

Patch 014b corrects an important bootstrap assumption discovered on the real
NeoForge 1.21.1 runtime.

The Minecraft `Window` constructor does not necessarily own the native
`glfwCreateWindow` call. NeoForge's early-display system can create/own the
OpenGL window before Minecraft's normal client bootstrap and then hands that
window to Minecraft through:

`ImmediateWindowHandler.setupMinecraftWindow(IntSupplier, IntSupplier,
Supplier<String>, LongSupplier)`

Potato therefore observes that handoff operation with MixinExtras
`@WrapOperation` and delegates the original call unchanged.

Consequences for the future Vulkan backend:
- changing only Mojang's `Window` constructor is too late on NeoForge;
- the actual backend-selection architecture must account for the early-window
  provider;
- a production Vulkan path will need either a Vulkan-capable early-window
  provider or a deliberate early-display bypass/replacement;
- Minecraft's downstream rendering assumptions still need to be isolated before
  any such mutation is enabled.

Patch 014b remains observation-only.
## Backend boundary contract

Patch 016 converts the Patch 015c bytecode census into a machine-readable
backend ownership manifest under `render/backend`.

The contract separates:
- common GLFW platform services that survive a Vulkan cutover;
- verified seams;
- OpenGL responsibilities that Vulkan must replace;
- lifetime adaptations;
- the current hard cutover blocker.

The current blocker is NeoForge EarlyDisplay window ownership. Main-window
mutation remains forbidden until that ownership strategy is resolved.

See `docs/BACKEND_BOUNDARIES.md`.
## Handoff candidate window

Patch 017 creates a hidden GLFW_NO_API window directly at the verified
NeoForge-to-Minecraft window handoff.

Unlike the earlier generic presentation probe, this window is derived from the
actual EarlyDisplay window geometry and is then adopted by the Vulkan
presentation probe. The original OpenGL window is still returned to Minecraft,
so the rehearsal is behavior-preserving.

The candidate is Potato-owned and destroyed after Vulkan surface/device
validation.
## Early Vulkan limits snapshot

Patch 018 introduces `VulkanEarlyDeviceLimits`.

It is intentionally smaller than the full renderer bootstrap. Its only current
responsibility is to make Vulkan physical-device limits available early enough
for Minecraft's `Window` constructor, before the normal Potato Vulkan probe
runs.

It creates no logical device, queue, surface or swapchain and destroys its
temporary VkInstance immediately.
## Renderer initialization seam

Patch 019 establishes a required call-site seam around
`Minecraft.<init> -> RenderSystem.initRenderer(IZ)V`.

The seam is behavior-preserving. It exists so backend dispatch can eventually
replace one whole renderer-initialization responsibility after `GLX._init` has
been decomposed, rather than suppressing individual OpenGL calls throughout
Minecraft.

Current state:
- baseline OpenGL initialization still executes exactly once;
- GLFW context ownership must remain unchanged across the call;
- the same Render-thread `GLCapabilities` object must survive;
- API description is still populated by the OpenGL baseline;
- Vulkan main-window mutation remains forbidden.
## Renderer initialization dispatcher

Patch 021 moves the Minecraft renderer initialization call site under Potato
ownership.

`RendererInitializationDispatcher` is the high-level backend dispatch seam.

Current branch:
`OPENGL_TRANSITION`
- CPU diagnostics
- GL debug callback
- OpenGL API metadata

Future branch:
`VULKAN`
- shared CPU diagnostics
- Vulkan validation/debug policy
- Vulkan device/API metadata

The original `RenderSystem.initRenderer` and `GLX._init` are no longer needed at
the Minecraft constructor call site.
## Per-target backend state

Patch 023 attaches backend state directly to each Minecraft `RenderTarget`
instance through a narrow internal interface.

This avoids a global object registry and keeps target lifetime/state local to
the object whose API Minecraft already uses.

The model explicitly separates logical target state from backend-native GPU
resources. OpenGL integer handles never become Potato's generic resource IDs.
## Vulkan offscreen attachment lifetime

Patch 024 introduces `VulkanOffscreenTargetPrototype`.

It owns real Vulkan image/view/memory resources separately from Minecraft's
OpenGL object IDs.

The prototype lifetime is nested inside the Vulkan frame-validation session and
is destroyed before the logical device is destroyed.

This is the resource shape that a future Vulkan `RenderTarget` owner will evolve
from; it is not yet wired into Minecraft rendering.
## Renderer-shaped Vulkan frame path

Patch 025 establishes the first complete Potato Vulkan frame shape:

- scene attachment resources are separate from presentation resources;
- graphics pipelines target the offscreen attachment formats;
- swapchain images are presentation transfer destinations;
- offscreen and swapchain extents may differ and the presentation transfer can
  scale;
- swapchain format changes do not force scene-pipeline recreation;
- acquire synchronization gates first swapchain use, not unrelated offscreen
  rendering.

This is intentionally closer to Minecraft's MainTarget -> blit-to-window model
than the earlier direct-to-swapchain triangle.
## Persistent Vulkan runtime context

Patch 026 promotes the complete validated Vulkan backend skeleton out of
startup-probe lifetime.

`VulkanRuntimeContext` owns:

- VkInstance;
- selected VkPhysicalDevice identity;
- VkDevice;
- graphics/present queues;
- hidden GLFW_NO_API presentation window;
- VkSurfaceKHR;
- swapchain generation;
- frame ring;
- offscreen color/depth target;
- graphics pipeline.

`VulkanProbe.probe()` still performs the validation run, but successful resources
are transferred instead of destroyed when the method returns.

`PotatoDiagnostics` then verifies that the same resources survived the probe
scope.

Runtime shutdown is tied to the exact Minecraft `stop()V` lifecycle seam proven
from the persisted 1.21.1 bytecode census.

Shutdown ownership is strictly reversed:

1. frame synchronization / command resources;
2. swapchain;
3. graphics pipeline;
4. offscreen image views/images/memory;
5. VkSurfaceKHR + hidden GLFW window;
6. VkDevice;
7. VkInstance.

Patch 026 still does not route Minecraft rendering into Vulkan. The next
milestone is `RENDER_TARGET_OPERATION_DISPATCH`.
## RenderTarget operation dispatch seam

Patch 027 establishes a backend-neutral live-operation seam between Minecraft's
real `RenderTarget` API and a persistent backend runtime.

The mixin does not know about Vulkan.

`RenderTargetOperationDispatcher` accepts semantic operations and forwards them
to the currently installed `RenderTargetOperationSink`.

The persistent Vulkan runtime installs
`VulkanRenderTargetOperationMirror` for its lifetime and removes it before
runtime teardown.

Only the semantic `MAIN` target is dispatched.

Exact execution seams are used where Minecraft public methods may defer work:
- `_resize(IIZ)V`
- `_bindWrite(Z)V`
- `_blitToScreen(IIZ)V`

Other render-thread-safe operations currently mirrored:
- `bindRead`
- `unbindRead`
- `setClearColor`
- `clear`
- `copyDepthFrom`

Patch 027 is deliberately mirror-only: the original OpenGL operation still
executes and Vulkan receives the semantic event without performing the GPU
equivalent yet.

This cleanly separates routing correctness from resource mutation.
## Live Vulkan target replacement

Patch 028 gives `VulkanFrameSession` ownership of target replacement inputs:
physical device, logical device, graphics queue and graphics queue-family index.

The operation mirror decides *when* a semantic Minecraft resize is stable
enough to apply.

The frame session decides *how* native Vulkan resources are replaced.

This keeps scheduling/debouncing policy separate from native GPU-resource
ownership.

Target replacement uses a strong guarantee: the old target remains valid until
the replacement target (and, if necessary, replacement pipeline) has been
created successfully.
## Ordered MainTarget operation application

Patch 029 establishes an important rule for semantic mirroring: dispatch-hook
order is not automatically equivalent to logical GPU-state order.

A nested Minecraft operation may complete before an outer lifecycle operation
is dispatched.

The Vulkan mirror therefore owns a small pending-operation state machine.

For resize + clear, target replacement precedes clear application even when the
raw Minecraft hooks were observed in the opposite order.

This model should be preferred over adding direct native calls to individual
Mixin hooks.
## Persistent live Vulkan presentation scheduler

Patch 030 reuses the startup-created frame ring and swapchain during the live
Minecraft runtime.

`VulkanLiveBlitPresenter` owns scheduling state only:
- current frame slot;
- swapchain-image in-flight fence associations;
- live present count.

Native resource ownership remains in `VulkanFrameSession`.

This prevents presentation scheduling state from being mixed into the semantic
Minecraft operation mirror while also avoiding duplicate ownership of command
buffers, semaphores, fences and swapchain images.
## Coherent mirrored frame state machine

Patch 031 moves runtime frame semantics into a small state machine owned by the
Vulkan operation mirror.

Responsibilities are split:

`RenderTargetOperationDispatcher`
- emits backend-neutral semantic operations.

`VulkanRenderTargetOperationMirror`
- identifies semantic frame boundaries;
- coalesces resize/frame work;
- owns transitional throttle policy;
- chooses the latest logical frame state.

`VulkanFrameSession`
- owns persistent native resources;
- executes target replacement;
- submits a complete mirrored frame.

`VulkanMainTargetFrameRecorder`
- records native image-layout, clear and blit commands only.

`VulkanLiveFramePresenter`
- owns frame-ring/swapchain scheduling only.

This keeps Mixin routing, semantic policy, resource lifetime, command recording
and presentation scheduling as separate concerns.
## Draw resource lifecycle boundary

Patch 033 establishes `VertexBuffer` as the first backend-neutral geometry
resource lifecycle.

The boundary is deliberately split:

1. `upload(MeshData)` — CPU geometry is still available;
2. shader draw — matrices and ShaderInstance are available;
3. `close()` — backend resource retirement.

This is preferable to intercepting `RenderSystem.drawElements`, where the raw
MeshData has already been destroyed, and preferable to intercepting only
`BufferUploader`, which represents the immediate path but not persistent
VertexBuffer ownership.

`DrawSubmissionDispatcher` owns routing only.

`DrawSubmissionContractRehearsal` is a temporary diagnostics sink.

Native Vulkan geometry ownership belongs in the next backend-specific sink, not
in the Mixin.
## Section-layer draw context boundary

The direct world/chunk render boundary is not generic VertexBuffer.draw().

`LevelRenderer.renderSectionLayer(...)` owns the missing higher-level state:
RenderType, active ShaderInstance, matrices and per-section CHUNK_OFFSET.

Potato therefore combines two existing layers:

- DrawBufferBackendState owns persistent geometry-resource identity.
- SectionLayerDrawContext owns one direct world/chunk draw instance.

The Vulkan backend may correlate both without importing OpenGL IDs into either
backend-neutral object.
## CPU texture resource bridge

Minecraft resource ownership and Vulkan resource ownership are separated by
immutable CPU snapshots.

Minecraft:
- TextureAtlas / TextureAtlasSprite / SpriteContents;
- LightTexture / DynamicTexture / NativeImage.

Potato resource bridge:
- BlockVertexLayoutSnapshot;
- BlockAtlasSnapshot;
- LightmapSnapshot.

Vulkan receives those snapshots only in later backend code.

This prevents OpenGL texture ids, NativeImage lifetimes and Vulkan VkImage
handles from leaking across abstraction layers.
## Research instrumentation retirement

Validated milestones progress from:
1. observation;
2. bounded proof;
3. production hot path.

Once a seam is proven, its high-volume rehearsal counters and duplicate GPU
work are removed from the default runtime.

Patch 040b is the first explicit retirement milestone:
- VertexBuffer upload/close remains;
- exact tracked section-layer context remains;
- atlas/lightmap capture remains;
- full draw census is retired;
- hidden frame mirror is disabled by default;
- future Vulkan texture work builds on the lean path.
## Production BLOCK frame core

Patch 043 introduces the production-shaped chunk frame split:

`LevelRenderer layer`
-> `SectionLayerFrameContext` once
-> lightweight per-section ChunkOffset dispatch
-> `VulkanTexturedMultiSectionFrame`
-> one dynamic-rendering batch

The old `VulkanTexturedSectionDrawPrototype` source is removed from the active
tree after its Patch-042 historical proof.

The remaining OpenGL dependency is authority/presentation, not uncertainty
about basic textured BLOCK shader execution.
## Potato Visibility Engine

The renderer now has a dedicated camera-driven visibility subsystem.

Responsibilities:
- classify section render urgency;
- provide HOT/WARM/COLD inputs to future mesh scheduling;
- later own occlusion/LOD visibility decisions.

Non-responsibilities:
- simulation;
- chunk loading;
- saves.

The visibility subsystem must remain backend-neutral enough to guide both
OpenGL fallback work and Vulkan production rendering.
## Hierarchical world representation

Potato does not treat render distance as a flat list of chunks.

Terrain render work is organized into horizontal world clusters with
progressively larger footprints and data-driven vertical envelopes.

Future visibility selection order:

1. reject a cluster outside the camera volume;
2. reject a cluster hidden by HZB;
3. evaluate projected screen-space error;
4. render a coarse surface representation when sufficient;
5. descend to children only when more visual detail is actually resolvable.

The hierarchy is renderer state only. It must not own simulation or saving.
## Lossless surface layer

Before lossy LOD, Potato may replace redundant adjacent block faces with a
surface mesh that preserves:
- exact face plane;
- block texture repetition;
- atlas subrect;
- UV orientation;
- uniform color;
- uniform lightmap;
- normal direction.

The surface layer is renderer state only. The original blocks continue to
exist normally in world state and simulation.