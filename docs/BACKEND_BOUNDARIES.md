# Potato Runtime — Backend Boundaries

Evidence source: Patch 015c bytecode census of the exact Minecraft 1.21.1 /
NeoForge runtime.

## Core conclusion

Potato must **not** replace GLFW wholesale.

GLFW still owns useful backend-neutral platform responsibilities:

- initialization and timing;
- native window/event handling;
- monitor and fullscreen management;
- title/icon/callback handling;
- event polling.

The OpenGL cut is narrower and begins where code assumes an OpenGL context or
OpenGL rendering semantics.

## Verified startup chain

```text
Minecraft constructor
    |
    +-- RenderSystem.initBackendSystem()
    |      `-- GLX._initGlfw()
    |          KEEP: GLFW platform/timing bootstrap
    |
    +-- VirtualScreen -> Window
           |
           +-- OpenGL-oriented GLFW hints
           |
           +-- ImmediateWindowHandler.setupMinecraftWindow(...)
           |      `-- NeoForge ImmediateWindowProvider
           |          `-- current provider: DisplayWindow
           |
           +-- glfwMakeContextCurrent(window)       REPLACE
           +-- GL.createCapabilities()              REPLACE
           +-- RenderSystem.maxSupportedTextureSize REPLACE/query Vulkan limits
           +-- normal GLFW callbacks                KEEP
    |
    +-- RenderSystem.initRenderer(...)
    |      `-- GLX._init(...)                       REPLACE
    |
    +-- new MainTarget(...)                         REPLACE backend resources
    |
    +-- RenderSystem.setupDefaultState(...)         REPLACE
```

## Blocker #1 — NeoForge EarlyDisplay

The current NeoForge `DisplayWindow`:

1. configures OpenGL GLFW window hints;
2. creates the loading window with `glfwCreateWindow`;
3. makes the GL context current;
4. calls `GL.createCapabilities`;
5. renders the loading UI with GL32C;
6. presents it with `glfwSwapBuffers`;
7. later returns that same native window from
   `setupMinecraftWindow(...)`.

Therefore Potato cannot merely change Minecraft's later `Window` constructor to
`GLFW_NO_API`: by then the handed-off native window already owns an OpenGL
context.

### Required next decision

Patch 017 must choose and prove one EarlyDisplay strategy:

**A. Replace the EarlyDisplay provider**
- Potato owns the main window from the beginning.
- Loading UI must be Vulkan-capable or intentionally minimal.

**B. Isolate EarlyDisplay**
- Let NeoForge keep a temporary OpenGL loading window.
- At Minecraft handoff, retire that window and create a new `GLFW_NO_API`
  Minecraft window.
- Requires careful transfer of size/monitor/title/focus and deterministic
  teardown.

No production cutover happens until one strategy is verified.

## Boundaries to keep

### GLFW platform services
Keep:
- `RenderSystem.initBackendSystem()` / `GLX._initGlfw()`;
- GLFW timing;
- event polling;
- input/window callbacks;
- monitor/fullscreen/title APIs.

### Minecraft window management
Mostly keep:
- size and framebuffer-size tracking;
- monitor selection;
- fullscreen transitions;
- focus/position callbacks;
- native close semantics.

Backend-specific branches are needed for VSync/presentation and shutdown order.

## Boundaries to replace

### Context bootstrap
Replace:
- `glfwMakeContextCurrent`;
- `GL.createCapabilities`;
- GL-derived hardware-limit queries.

### Renderer initialization
Replace:
- `RenderSystem.initRenderer`;
- `GLX._init`;
- OpenGL API/version diagnostics.

### Render targets and mutable GL state
Replace as responsibilities:
- `MainTarget` / `RenderTarget` backing resources;
- texture/framebuffer ownership;
- `GlStateManager` state mutation;
- draw submission.

Do not create hundreds of one-call Mixins. Introduce backend-owned resource,
pipeline and command abstractions.

### Default state
`RenderSystem.setupDefaultState` is explicitly OpenGL state programming.
Equivalent Vulkan state belongs in pipeline/rendering configuration.

### Presentation
Baseline:
`Window.updateDisplay -> RenderSystem.flipFrame -> glfwSwapBuffers`

Vulkan:
`acquire -> record -> submit -> present`

Event polling/replay responsibilities must be preserved without preserving
`glfwSwapBuffers`.

### VSync
Baseline:
`glfwSwapInterval`

Vulkan:
swapchain present-mode policy plus recreation when policy changes.

### Readback/debugging
Replace OpenGL-specific screenshot readback, GL debug output and API strings
with backend-aware implementations.

### Shutdown
Destroy Vulkan frame/swapchain/device/surface resources before destroying the
GLFW native window.

## Cutover gate

`mainWindowVulkanMutationReady` remains **false** until the EarlyDisplay strategy
has been implemented and verified.

Next milestone:
`EARLY_DISPLAY_CUTOVER_STRATEGY`
## Patch 017 — Strategy B rehearsal

Strategy B is selected for the first real cutover path:

1. NeoForge EarlyDisplay may keep its temporary OpenGL loading window.
2. At `ImmediateWindowHandler.setupMinecraftWindow(...)`, Potato observes the
   returned native window.
3. Potato creates a second hidden `GLFW_NO_API` window using the actual handoff
   window's logical size and position.
4. Minecraft still receives the original OpenGL window.
5. Potato's existing Vulkan probe adopts the handoff-created NO_API candidate,
   creates a Vulkan surface/swapchain and renders its hidden validation frames.
6. The candidate is destroyed by Potato after Vulkan cleanup.

This is deliberately not yet the actual replacement. It proves that the
replacement window can be created at the correct lifecycle seam and can carry
the existing Vulkan presentation path without disturbing NeoForge EarlyDisplay.

### Why EarlyDisplay remains the formal blocker

The rehearsal does not retire NeoForge's OpenGL window and does not return the
NO_API candidate to Minecraft yet. The blocker is cleared only when the real
handoff replacement exists.

### Next cut

Minecraft's `Window` constructor currently executes, immediately after the
handoff:

- `glfwMakeContextCurrent(window)`
- `GL.createCapabilities()`
- a GL-backed maximum texture-size query

Those operations cannot run on a NO_API replacement window. The next milestone
is therefore `MINECRAFT_CONTEXT_BOOTSTRAP_BYPASS`.
## Patch 018 — Context bootstrap bypass rehearsal

Patch 018 removes the three immediate OpenGL bootstrap assumptions from the
Minecraft `Window` constructor **while still returning NeoForge's original
OpenGL window**.

### 1. `glfwMakeContextCurrent(window)`

The redirect first verifies that NeoForge EarlyDisplay already left the handed
off window current on the render thread.

If true:
- Minecraft's redundant make-current call is skipped.

If false:
- the original GLFW call is executed as a baseline fallback;
- the Vulkan rehearsal is marked failed, but Minecraft may continue.

### 2. `GL.createCapabilities()`

LWJGL keeps `GLCapabilities` as thread-local state associated with the current
context. EarlyDisplay already creates them.

Patch 018 attempts `GL.getCapabilities()`:
- existing capabilities -> reuse them and skip recreation;
- missing capabilities -> call `GL.createCapabilities()` as fallback.

### 3. `RenderSystem.maxSupportedTextureSize()`

A NO_API Vulkan window cannot ask OpenGL for this value.

Before this call is reached, Potato creates a minimal temporary Vulkan instance,
uses the same device-scoring policy as the normal Vulkan catalog, and reads:

`VkPhysicalDeviceProperties.limits.maxImageDimension2D`

That Vulkan value is returned to Minecraft's window-size-limit setup instead of
the OpenGL query.

The temporary Vulkan instance is destroyed immediately and must not alter the
current OpenGL context.

### Pass condition

The context-bootstrap rehearsal passes only when:
- the handed-off context was already current;
- `glfwMakeContextCurrent` was skipped;
- LWJGL GL capabilities were already installed;
- `GL.createCapabilities` was skipped;
- a Vulkan physical-device limit snapshot succeeded;
- the GL max-texture query was replaced by Vulkan `maxImageDimension2D`;
- no baseline fallback was used;
- GL capabilities/context remain usable after the constructor.

This is still not the actual NO_API Minecraft window. The next replacement
boundary is `RenderSystem.initRenderer / GLX._init`.
## Patch 018a — Capabilities dependency correction

Patch 018 demonstrated that the direct `GL.createCapabilities()` call cannot be
treated as an independently removable Window concern while Minecraft's
downstream renderer is still OpenGL.

LWJGL stores `GLCapabilities` per thread. NeoForge EarlyDisplay can prepare GL
state on its own rendering thread; Minecraft's Render thread therefore may need
to install its own capabilities even when the same GLFW OpenGL context is
already current.

The transition boundary is now:

- `glfwMakeContextCurrent(window)`:
  expected to be redundant and bypassable;
- `RenderSystem.maxSupportedTextureSize()`:
  replaced by Vulkan `maxImageDimension2D`;
- `GL.createCapabilities()`:
  temporary OpenGL transition bridge, removed together with renderer
  initialization rather than independently.

This means the context bootstrap and
`RenderSystem.initRenderer / GLX._init / GlStateManager` ownership boundaries
partially overlap. That dependency is intentional and now machine-reported.

A failed rehearsal returns a fail-safe OpenGL report. It must never throw into
NeoForge's deferred mod-loading work queue.
## Patch 019 — Renderer initialization dependency rehearsal

The exact Minecraft 1.21.1 runtime census shows:

`Minecraft.<init> -> RenderSystem.initRenderer(int, boolean)`

and `RenderSystem.initRenderer` itself performs only:

1. `GLX._init(int, boolean)`;
2. `GLX.getOpenGLVersionString()`;
3. assignment of `RenderSystem.apiDescription`.

Patch 019 intercepts only the Minecraft constructor call site and executes the
baseline method exactly once.

The rehearsal records:
- render thread identity;
- current GLFW context before/after;
- LWJGL `GLCapabilities` presence and object identity before/after;
- renderer-init arguments;
- API description before/after.

No OpenGL renderer initialization is skipped in this patch.

A PASS proves the current GL renderer initialization does not take new native
window/context ownership and that the already-installed Render-thread
capabilities remain the same object across the call.

The next task is not a blind `initRenderer` cancellation. It is a responsibility
split inside `GLX._init`, separating:
- diagnostics/debug configuration that belongs to the OpenGL backend;
- backend-neutral system information if any;
- renderer API metadata;
- state that a future Vulkan bootstrap must own.

Until that split is complete, `GL.createCapabilities` remains an explicit
transition bridge and the real Minecraft window remains OpenGL.
## Patch 021 — Renderer initialization dispatch

Patch 020 proved that `GLX._init(IZ)V` contains no GLFW ownership, no context
creation, no capability creation, no GlStateManager work and no direct LWJGL
OpenGL instructions.

Its responsibilities are:
1. backend-neutral CPU diagnostic text using OSHI;
2. OpenGL-only `GlDebug.enableDebugCallback`.

`RenderSystem.initRenderer` then adds:
3. OpenGL API metadata from `GLX.getOpenGLVersionString`.

Patch 021 moves this high-level responsibility into
`RendererInitializationDispatcher`.

Minecraft's constructor no longer calls `RenderSystem.initRenderer`.

The current OpenGL transition branch explicitly performs:
- CPU diagnostic initialization;
- OpenGL debug callback setup;
- OpenGL API-description installation.

Independent observer mixins require:
- `RenderSystem.initRenderer` call count = 0;
- `GLX._init` call count = 0.

The active GLFW context and Render-thread GLCapabilities must remain unchanged.

### Static state seams

Accessor mixins expose only the two Mojang diagnostic fields required to
preserve behavior:
- `GLX.cpuInfo`;
- `RenderSystem.apiDescription`.

This avoids reflection and keeps backend state ownership explicit.

### Remaining OpenGL transition bridges

The renderer-initialization dispatcher still invokes:
- `GlDebug.enableDebugCallback`;
- `GLX.getOpenGLVersionString`.

Those are intentionally OpenGL-specific and will not exist in the future Vulkan
branch.

The next boundary is no longer renderer initialization. It is actual render
resource ownership beginning with Minecraft's main render target.