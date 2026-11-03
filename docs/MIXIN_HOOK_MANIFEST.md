# Potato Runtime 0.9.0 - Mixin and Hook Manifest

This is a compact technical map for reviewers. The authoritative configuration is:

```text
src/main/resources/potato_runtime.mixins.json
```

Potato Runtime 0.9.0 declares **18 client mixins/accessors** through:

```text
dev.ordovicium.potato.mixin.PotatoMixinCompatibilityPlugin
```

## Compatibility gating before transformation

The compatibility plugin applies three important boundaries:

1. **Potato mode OFF** -> all Potato mixins are rejected before transformation.
2. **Sodium renderer detected** -> Potato skips the two vanilla `LevelRenderer` ownership hooks rather than injecting against Sodium's renderer internals.
3. **Iris detected** -> Potato skips the Minecraft renderer-initialization redirect so vanilla/Iris OpenGL initialization remains authoritative.

Compatibility plugin source:

https://github.com/Aguy8844/Potato-Runtime/blob/main/src/main/java/dev/ordovicium/potato/mixin/PotatoMixinCompatibilityPlugin.java

## Hook inventory

### Window and renderer bootstrap

- **WindowBootstrapMixin** -> `com.mojang.blaze3d.platform.Window`
  - wraps/observes the window bootstrap seam used by the Vulkan migration work;
  - includes guarded OpenGL bootstrap interception;
  - fails open to the normal OpenGL path when Vulkan is not qualified.

- **MinecraftRendererInitializationMixin** -> `net.minecraft.client.Minecraft`
  - renderer-initialization ownership redirect;
  - skipped entirely when Iris is detected.

- **MinecraftVulkanRuntimeLifecycleMixin** -> `net.minecraft.client.Minecraft`
  - hooks client construction/shutdown for Vulkan runtime lifecycle and cleanup.

- **RenderSystemInitObservationMixin** -> `com.mojang.blaze3d.systems.RenderSystem`
  - diagnostic observation of renderer initialization.

- **GLXInitObservationMixin** -> `com.mojang.blaze3d.platform.GLX`
  - diagnostic observation of GLX initialization.

### Render targets and draw submission

- **RenderTargetOwnershipMixin** -> `com.mojang.blaze3d.pipeline.RenderTarget`
  - bridges target resize/read/bind lifecycle events into Potato's backend abstraction.

- **MainTargetOwnershipMixin** -> `com.mojang.blaze3d.pipeline.MainTarget`
  - identifies Minecraft's main target after normal construction.

- **VertexBufferDrawSubmissionMixin** -> `com.mojang.blaze3d.vertex.VertexBuffer`
  - hooks vertex-buffer construction/upload/draw seams used by renderer/LOD work.

- **LevelRendererSectionLayerDrawMixin** -> `net.minecraft.client.renderer.LevelRenderer`
  - hooks section-layer drawing;
  - skipped when Sodium's renderer is detected.

### Chunk work and multithreading

- **LevelRendererChunkWorkBudgetMixin** -> `net.minecraft.client.renderer.LevelRenderer`
  - hooks `compileSections(Camera)` for client render-mesh budgeting and visible-work priority;
  - does **not** modify server chunk tickets, simulation distance, world ticking, saving, or network chunk requests;
  - skipped when Sodium's renderer is detected.

- **SectionRenderDispatcherUploadBudgetMixin** -> `net.minecraft.client.renderer.chunk.SectionRenderDispatcher`
  - bounds how many queued render-upload tasks are consumed in one pass;
  - remaining tasks stay in the existing queue for later passes.

- **SectionRenderDispatcherCompileExecutorMixin** -> `net.minecraft.client.renderer.chunk.SectionRenderDispatcher`
  - replaces only the asynchronous compile executor argument;
  - retains Minecraft's task/priority model while using Potato's hardware-aware executor/ForkJoin scheduling.

### Texture/resource capture

- **TextureAtlasResourceCaptureMixin** -> `net.minecraft.client.renderer.texture.TextureAtlas`
  - captures the completed block-atlas CPU representation after vanilla upload for renderer research/runtime bookkeeping.

- **LightTextureResourceCaptureMixin** -> `net.minecraft.client.renderer.LightTexture`
  - captures light-texture lifecycle state around vanilla updates.

### Input compatibility

- **ScreenModifierStateMixin** -> `net.minecraft.client.gui.screens.Screen`
  - narrowly bridges modifier-key state during deferred screen handling.

- **InputConstantsModifierStateMixin** -> `com.mojang.blaze3d.platform.InputConstants`
  - narrowly handles modifier-key queries against Minecraft's lifecycle window;
  - ordinary gameplay/native key polling remains vanilla.

### Accessors

- **GLXAccessor** -> `com.mojang.blaze3d.platform.GLX`
  - accessor for GLX diagnostic CPU information.

- **RenderSystemAccessor** -> `com.mojang.blaze3d.systems.RenderSystem`
  - accessor for backend API-description diagnostics.

## Security-relevant boundaries

Potato Runtime does **not** implement:

- arbitrary process execution;
- shell/PowerShell execution;
- socket or server-socket networking;
- Java HTTP clients;
- custom class downloading/loading;
- credential access;
- browser access;
- `System.load` / `System.loadLibrary` calls;
- remote telemetry upload.

Runtime file writes are limited to Potato diagnostic/report output and normal configuration/state expected for the mod.

The native code present in the release comes from official LWJGL Shaderc Windows dependencies resolved by Gradle; see [BUILDING.md](../BUILDING.md).

## Final 0.9.0 renderer status

The final tested Sodium + Iris profile uses the **OpenGL compatibility backend**. Vulkan work remains safety-gated and is not advertised as the active gameplay renderer in 0.9.0.

Partial visible Vulkan ownership is intentionally forbidden: if mandatory renderer gates are not satisfied, Potato fails open to OpenGL.
