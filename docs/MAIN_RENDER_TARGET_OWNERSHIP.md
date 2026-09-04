# Main Render Target Ownership

Patch 022 proved that Minecraft 1.21.1 `MainTarget` immediately allocates real
OpenGL framebuffer and texture resources in its constructor.

Patch 023 installs the first backend-neutral per-target ownership seam while
leaving all OpenGL behavior unchanged.

## Logical state

The following data is backend-neutral target state:

- `width`
- `height`
- `viewWidth`
- `viewHeight`
- `useDepth`
- clear color
- filter intent
- stencil-enabled intent

## Backend-specific resources

The following integers are explicitly OpenGL handles, not generic resource IDs:

- `frameBufferId`
- `colorTextureId`
- `depthBufferId`

Potato stores them only as OpenGL-transition diagnostic snapshots.

A Vulkan implementation will own native objects such as VkImage, VkImageView
and device-memory allocations in its own backend state. It must not encode those
objects into Mojang's integer GL-ID fields.

## Lifecycle discovered by Patch 022

### Initial `MainTarget`

`MainTarget(int, int)` immediately performs its own OpenGL allocation path:

- texture IDs
- color/depth storage
- framebuffer
- attachments
- framebuffer validation

This path also has Minecraft's special dimension fallback behavior.

### Resize

Inherited `RenderTarget.resize(...)` is a destroy-and-recreate lifecycle:

1. `destroyBuffers()`
2. `createBuffers(...)`

The initial `MainTarget.createFrameBuffer(...)` path and later inherited
`RenderTarget.createBuffers(...)` path are therefore distinct and must both be
represented by a future Vulkan backend.

## Patch 023 ownership model

Every Minecraft `RenderTarget` receives a per-instance
`RenderTargetBackendState`.

The state tracks:
- semantic role (`GENERIC` / `MAIN`);
- current resource owner;
- logical dimensions;
- OpenGL resource snapshots;
- allocation/destroy generations;
- resize requests;
- bind/clear/blit/depth-copy lifecycle calls.

Patch 023 is deliberately observation-only:
- all Mojang OpenGL methods still execute;
- no Vulkan target replaces Minecraft's target;
- no GL IDs are suppressed.

The next milestone is `VULKAN_OFFSCREEN_TARGET_PROTOTYPE`: allocate a real
Potato-owned Vulkan color/depth target with the main target's actual dimensions,
validate image/view/memory lifetime, and destroy it without changing normal
Minecraft rendering.
## Patch 024 — Vulkan offscreen target prototype

Patch 024 creates the first real Potato-owned Vulkan render-target resource
stack in parallel with Minecraft's still-active OpenGL MainTarget.

The prototype uses the live logical dimensions recorded by Patch 023.

### Color attachment

Potato creates:
- VkImage
- device-local VkDeviceMemory
- vkBindImageMemory
- VkImageView

Usage includes:
- color attachment
- transfer destination
- transfer source

The preferred semantic format is linear RGBA8.

### Depth attachment

When the Minecraft target uses depth, Potato also creates:
- depth VkImage
- device-local VkDeviceMemory
- VkImageView

A supported depth format is selected from D32/D32S8/D24S8 candidates.

### GPU initialization proof

A one-time graphics command buffer performs:

Color:
`UNDEFINED -> TRANSFER_DST_OPTIMAL -> clear -> COLOR_ATTACHMENT_OPTIMAL`

Depth:
`UNDEFINED -> TRANSFER_DST_OPTIMAL -> clear -> DEPTH_STENCIL_ATTACHMENT_OPTIMAL`

The command is submitted to the real selected graphics queue and waited with a
finite fence timeout.

This proves the resources are not merely host-side handles: they are bound,
GPU-accessible images prepared for attachment use.

### Lifetime

The temporary prototype is scoped to Potato's Vulkan frame validation session.

Destruction order:
1. image views
2. images
3. device memory

Minecraft's OpenGL MainTarget remains the actual owner used by the game.

No VkImage or VkDeviceMemory handle is written into Mojang's OpenGL integer ID
fields.

The next milestone is `VULKAN_OFFSCREEN_RENDER_AND_PRESENT`: render actual
graphics into the offscreen color/depth attachments, then route that result into
the presentation path.
## Patch 025 — Offscreen render and presentation

Patch 025 changes Potato's Vulkan validation frame from direct swapchain
rendering to a renderer-shaped two-stage path:

```text
Triangle
  -> Potato offscreen color + depth
  -> transfer/blit
  -> acquired swapchain image
  -> present
```

### Dynamic rendering target

The graphics pipeline now declares:
- the offscreen color format;
- the offscreen depth format;
- depth test/write state.

The swapchain format is no longer the graphics pipeline's attachment format.

This means a swapchain format change does not require graphics-pipeline
recreation for this probe.

### Presentation transfer

The offscreen color image is created with `TRANSFER_SRC`.

Swapchain images once again require `TRANSFER_DST`.

Each frame:

1. dynamic rendering clears and draws into the offscreen color/depth images;
2. color changes from `COLOR_ATTACHMENT_OPTIMAL` to
   `TRANSFER_SRC_OPTIMAL`;
3. the acquired swapchain image changes to `TRANSFER_DST_OPTIMAL`;
4. `vkCmdBlitImage` copies/scales the offscreen color result to the swapchain;
5. offscreen color returns to `COLOR_ATTACHMENT_OPTIMAL`;
6. swapchain changes to `PRESENT_SRC_KHR`;
7. normal queue presentation follows.

The blit path explicitly validates source `BLIT_SRC` and destination `BLIT_DST`
format features and currently uses nearest filtering.

### Synchronization correction

The image-available semaphore is now waited at the transfer stage rather than
the color-attachment stage.

Offscreen rendering does not access the swapchain and may occur before the
acquired image becomes available. The transfer that first touches the swapchain
is the synchronization boundary.

### Exception-safe resource teardown

Because the offscreen target is now referenced by in-flight frame command
buffers, Patch 025 also moves frame/offscreen cleanup behind a best-effort
`vkDeviceWaitIdle` boundary.

This prevents command pools, image views, images or bound memory from being
destroyed while submitted work may still reference them.

Minecraft's real MainTarget remains OpenGL. This milestone proves the resource
and presentation shape only.

The next milestone is `PERSISTENT_VULKAN_RUNTIME_CONTEXT`: promote the
currently short-lived validation renderer into a lifecycle-owned backend
context before Minecraft render operations are dispatched to it.
## Patch 026 — Persistent runtime lifetime

The renderer-shaped offscreen/swapchain frame stack from Patch 025 now survives
startup diagnostics inside `VulkanRuntimeContext`.

Minecraft's real MainTarget remains `OPENGL_TRANSITION`.

The Vulkan offscreen target is therefore persistent but still parallel. This is
intentional: Patch 027 can begin mirroring/dispatching RenderTarget operations
against a backend that already exists for runtime lifetime, instead of creating
and destroying Vulkan state inside every operation.
## Patch 027 — Live operation dispatch

The real Minecraft MainTarget remains OpenGL-owned.

Patch 027 mirrors completed live MainTarget operations into the persistent
Vulkan runtime through a backend-neutral sink.

This proves that the runtime backend is no longer merely alive in parallel; it
is connected to Minecraft's real render-target lifecycle.

No Vulkan target is resized, cleared, rebound or presented because of these
events yet.

The shutdown report verifies live routing using the stable per-frame
`bindWrite -> blitToScreen` path.

Resize events are also captured when they occur. The next milestone,
`VULKAN_MAIN_TARGET_RESIZE_PROPAGATION`, will use that proven route to rebuild
the Vulkan color/depth target when Minecraft's real MainTarget changes size.
## Patch 028 — Vulkan MainTarget resize propagation

Patch 028 is the first live Minecraft operation that mutates persistent Vulkan
GPU resources.

Minecraft's OpenGL MainTarget remains the real game target.

A completed `_resize(IIZ)V` operation is still routed through the backend-neutral
RenderTarget operation sink. The Vulkan sink now queues the newest logical
MainTarget size.

### Resize coalescing

Window drags can produce multiple intermediate dimensions. Potato does not
allocate a full color/depth target for every intermediate callback.

The mirror stores only the latest requested size and waits for a short stable
period before applying it on a subsequent live MainTarget bind/blit operation.

Shutdown force-flushes any remaining pending resize.

### Strong replacement guarantee

A replacement Vulkan target is:

1. allocated;
2. bound to device-local memory;
3. given image views;
4. GPU-initialized and transitioned;
5. checked for attachment-format compatibility.

Only after replacement creation succeeds does Potato idle the device and swap
the runtime-owned target.

If attachment formats remain equal, the graphics pipeline survives unchanged.

If color/depth attachment formats change, a compatible dynamic-rendering
pipeline is created before the old pipeline is retired.

If replacement creation fails, the existing persistent Vulkan target remains
installed and OpenGL continues unaffected.

### Presentation independence

Patch 028 does not resize the hidden Vulkan presentation window or swapchain.

The offscreen scene target and presentation extent are already decoupled by
Patch 025's blit path, so target-size propagation does not imply swapchain-size
propagation.

The next milestone is `VULKAN_MAIN_TARGET_CLEAR_PROPAGATION`.
## Patch 029 — Vulkan MainTarget clear propagation

Patch 029 activates the second real GPU operation family driven by Minecraft's
live MainTarget lifecycle: Color + Depth clear.

OpenGL remains the actual Minecraft renderer.

### Ordering with resize

Minecraft can clear the newly created OpenGL target *inside* its resize path,
before Potato's outer `_resize(...)` RETURN dispatch occurs.

Therefore Patch 029 does not immediately execute a Vulkan clear from the
`clear(...)` hook.

Instead the Vulkan mirror stores the latest clear request.

At the next stable bind/blit boundary:

1. pending Vulkan resize is applied first;
2. pending clear is then applied to the current replacement target.

Shutdown force-flushes both in the same order.

This prevents a clear from being executed on a Vulkan target that is immediately
retired by a resize.

### GPU clear

The active Vulkan offscreen target performs:

Color:
`COLOR_ATTACHMENT_OPTIMAL -> TRANSFER_DST_OPTIMAL`
`vkCmdClearColorImage`
`-> COLOR_ATTACHMENT_OPTIMAL`

Depth:
`DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> TRANSFER_DST_OPTIMAL`
`vkCmdClearDepthStencilImage(depth=1.0)`
`-> DEPTH_STENCIL_ATTACHMENT_OPTIMAL`

A finite fence wait proves completion.

### Coalescing / duplicate suppression

Multiple queued clears collapse to the latest semantic clear.

Repeated identical clears against the same target generation are also skipped
while runtime Vulkan draw dispatch is still disabled. In Patch 029 no runtime
draw can dirty the Vulkan mirror between those clears, so the duplicate has no
observable Vulkan-state effect.

That optimization must be revisited when live Vulkan draw submission is added.

The next milestone is `VULKAN_MAIN_TARGET_BLIT_PROPAGATION`.
## Patch 030 — Vulkan MainTarget blit propagation

Patch 030 activates the third real GPU operation family driven by Minecraft's
live MainTarget lifecycle: presentation of the mirrored offscreen color image.

OpenGL remains the real Minecraft renderer and still performs its normal
`blitToScreen`.

### Runtime presentation path

A mirrored blit uses the already persistent resources created during startup:

- existing two-frame command/synchronization ring;
- existing hidden Vulkan swapchain;
- existing per-swapchain-image render-finished semaphores;
- current persistent Vulkan offscreen color target.

No command pool, semaphore or fence is allocated per live blit.

The command buffer records only:

`COLOR_ATTACHMENT_OPTIMAL -> TRANSFER_SRC_OPTIMAL`
`vkCmdBlitImage(offscreen -> swapchain)`
`offscreen -> COLOR_ATTACHMENT_OPTIMAL`
`swapchain -> PRESENT_SRC_KHR`

Then the normal acquire -> submit -> present sequence runs.

### Ordering

A pending live presentation never jumps ahead of a known pending resize or
clear.

The mirror applies operations in logical GPU order:

1. resize;
2. clear;
3. blit/present.

### Throttled mirror cadence

While OpenGL is still authoritative, Vulkan presentation is a proof mirror, not
the user's visible frame path.

FIFO presentation on a second hidden surface could otherwise introduce an
artificial wait into every Minecraft frame.

Therefore every Minecraft blit is observed and queued, but mirror presentation
is limited to one application per 250 ms. The newest semantic blit replaces an
older pending one.

Shutdown force-flushes the final pending presentation.

This throttle is transitional and disappears when Vulkan becomes the actual
frame owner.

### Failure containment

Any live Vulkan presentation failure disables further mirror presents for that
run.

The real OpenGL blit has already completed, so Minecraft remains unaffected.

The next milestone is `VULKAN_MAIN_TARGET_FRAME_LIFECYCLE`, which will replace
the current separate immediate resize/clear/present submissions with one
coherent persistent Vulkan frame state machine.
## Patch 031 — Coherent Vulkan MainTarget frame lifecycle

Patch 031 replaces the separate runtime Clear and Present GPU submissions with a
single frame-lifecycle submission.

The Minecraft semantic sequence is now modeled explicitly:

`bindWrite -> clear/state mutation -> blitToScreen`

`bindWrite` opens a semantic MainTarget frame.

A clear updates pending frame state but does not submit Vulkan work.

`blitToScreen` closes the semantic frame and queues the newest frame snapshot.

When the mirror throttle permits a Vulkan frame:

1. any settled pending target resize is applied;
2. one existing frame-ring command buffer is reused;
3. optional color/depth clear is recorded;
4. offscreen color is transitioned for transfer;
5. the current offscreen image is blitted into the persistent swapchain;
6. layouts are restored;
7. one queue submission executes the whole frame;
8. presentation follows.

### Removed immediate path

`VulkanOffscreenClearExecutor`,
`VulkanOffscreenPresentationRecorder` and
`VulkanLiveBlitPresenter` are superseded.

The runtime path now uses:
- `VulkanMainTargetFrameRecorder`;
- `VulkanLiveFramePresenter`.

No new command pool, fence or semaphore is created for a live frame.

### Transitional throttle

The 250 ms mirror throttle remains while OpenGL is authoritative.

It limits the cost of the hidden proof renderer without changing semantic
operation observation.

### Next boundary

The next milestone is `MINECRAFT_DRAW_SUBMISSION_CENSUS`.

Before attempting real Minecraft draw dispatch, Potato will map the exact
1.21.1 draw-upload/shader/buffer submission ownership boundary so the Vulkan
backend can consume real scene work without turning RenderTarget mixins into a
renderer-sized God object.