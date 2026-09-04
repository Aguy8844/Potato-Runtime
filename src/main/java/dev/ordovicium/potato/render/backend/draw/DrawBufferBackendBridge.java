package dev.ordovicium.potato.render.backend.draw;

/**
 * Gives relevant Minecraft VertexBuffers a backend-neutral Potato sidecar.
 *
 * <p>Patch 040d makes sidecar creation lazy. Most short-lived immediate
 * VertexBuffers never participate in the Vulkan BLOCK path and therefore
 * allocate no Potato state at all.</p>
 */
public interface DrawBufferBackendBridge {

    DrawBufferBackendState potato$drawBackendState();

    /**
     * Returns an already-created state or null.
     *
     * <p>Hot draw paths must prefer this method so merely checking a buffer
     * does not allocate backend state.</p>
     */
    DrawBufferBackendState potato$drawBackendStateIfPresent();
}