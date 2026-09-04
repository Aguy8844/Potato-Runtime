package dev.ordovicium.potato.render.vulkan;

/**
 * Read-only source-generation view exposed by a Minecraft VertexBuffer.
 *
 * <p>The value advances before every accepted MeshData upload and on close.
 * It is intentionally backend-neutral: Vulkan uses it to prove that a
 * DEVICE_LOCAL arena slot still contains the exact mesh generation selected
 * by the current visible section.</p>
 */
public interface PotatoMeshGenerationBridge {
    long potato$meshGeneration();
}
