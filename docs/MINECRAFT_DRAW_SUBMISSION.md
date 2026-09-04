# Minecraft Draw Submission Boundary

Patch 032 is an evidence-only milestone.

No renderer behavior is changed.

The census runs javap against the exact persisted ModDevGradle runtime
classpath and maps the Minecraft 1.21.1 path across:

- BufferUploader;
- VertexBuffer;
- RenderSystem;
- GlStateManager;
- MeshData / DrawState when present;
- VertexFormat mode/index metadata when present;
- ShaderInstance / RenderType when present.

Canonical evidence:

_dropoff/state/032_minecraft_draw_submission/draw-submission-census.txt

Raw disassembly for every discovered class is stored in the same directory.

## Gate for Patch 033

Do not intercept or cancel a draw until the census identifies:

1. complete geometry ownership;
2. vertex/index upload ownership;
3. primitive mode and index type ownership;
4. shader/program ownership;
5. uniform/sampler state ownership;
6. the final OpenGL draw owner;
7. the narrowest backend-neutral seam that preserves all required semantics.

The architectural target is a backend-owned draw submission object, not a
collection of Vulkan redirects attached to individual OpenGL calls.
## Patch 033 — VertexBuffer lifecycle contract

The Patch 032 census proves that one monolithic draw hook is the wrong
abstraction.

### Upload phase

`VertexBuffer.upload(MeshData)` still has synchronous access to:

- raw vertex bytes;
- optional raw index bytes;
- VertexFormat;
- vertex count;
- index count;
- primitive mode;
- index type.

Vanilla then uploads those bytes to OpenGL and closes MeshData.

Potato therefore treats this as a resource-upload boundary.

### Draw phase

`VertexBuffer._drawWithShader(modelView, projection, shader)` owns the
per-draw matrices and ShaderInstance and invokes `VertexBuffer.draw()`.

Potato observes immediately before that terminal draw call.

Shaderless `VertexBuffer.draw()` calls are observed separately.

### Lifetime phase

`VertexBuffer.close()` becomes the backend-neutral resource-retirement seam.

### Contract

The integration layer owns only Minecraft objects and backend-neutral state.

A backend sink may:
- upload geometry during the upload callback;
- retain its own native resource sidecar keyed by DrawBufferBackendState;
- consume that resource during the draw callback;
- destroy it during close.

Patch 033 installs a diagnostics-only sink. No CPU geometry is retained and no
Vulkan GPU operation occurs.

Patch 034 may implement `VULKAN_GEOMETRY_UPLOAD_PROTOTYPE` behind this exact
contract.
## Patch 034 — Real Vulkan geometry upload prototype

Patch 034 is the first milestone where bytes produced by real Minecraft
`MeshData` are copied into real Vulkan `VkBuffer` resources.

The prototype deliberately mirrors only a bounded sample:

- at most four DYNAMIC VertexBuffer lifecycles;
- at most four STATIC VertexBuffer lifecycles;
- dynamic updates are mirrored at most once per 100 ms per selected resource;
- individual vertex/index streams above 8 MiB are skipped.

This protects the OpenGL-authoritative transition client from duplicating every
geometry upload observed by Patch 033.

### Memory model

Prototype buffers use persistently mapped HOST_VISIBLE Vulkan memory.

HOST_VISIBLE | HOST_COHERENT is preferred.

If only non-coherent host-visible memory is available, Potato flushes the whole
mapped allocation after writing.

Each upload validates several mapped bytes against the source `MeshData`
ByteBuffer.

No Vulkan queue submission and no `vkDeviceWaitIdle` occurs per geometry
upload.

### Lifetime model

`DrawBufferBackendState` remains backend-neutral.

`VulkanGeometryBufferResource` is the Vulkan-native sidecar.

`VertexBuffer.close()` releases selected Vulkan resources.

Any selected resources still alive at Minecraft shutdown are released before
the persistent VkDevice is destroyed.

### Important limitation

The Vulkan buffers are not drawn yet.

Patch 033 showed that the overwhelming majority of observed world-test draws
used the direct `VertexBuffer.draw()` path rather than `_drawWithShader(...)`.

The next milestone is therefore `PLAIN_VERTEXBUFFER_DRAW_STATE_CENSUS`, which
must resolve shader/matrix/render-state ownership for that path before Potato
attempts real Minecraft Vulkan draw execution.