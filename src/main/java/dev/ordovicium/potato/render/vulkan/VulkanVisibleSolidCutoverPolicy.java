package dev.ordovicium.potato.render.vulkan;

/**
 * Release policy for Potato's first visible Vulkan world layer.
 *
 * <p>The user-visible SOLID cutover is enabled by default but remains fully
 * fail-open. Setting {@code -Dpotato.vulkan.visibleSolidAtomic=false} keeps
 * OpenGL authoritative while preserving the Vulkan resource runtime.</p>
 *
 * <p>A visible attempt is not allowed until the bounded hidden indirect
 * graphics-queue warmup has produced enough clean positive raster proofs.
 * Exact camera/visibility/mesh-generation validation is still performed by
 * {@link VulkanExactFramePublicationToken} for every actual commit.</p>
 */
public final class VulkanVisibleSolidCutoverPolicy {
    public static final String PROPERTY =
            "potato.vulkan.visibleSolidAtomic";

    private static final boolean ENABLED =
            Boolean.parseBoolean(
                    System.getProperty(
                            PROPERTY,
                            "true"
                    )
            );

    private VulkanVisibleSolidCutoverPolicy() {
    }

    public static boolean enabled() {
        return ENABLED;
    }

    public static boolean readyForVisibleAttempt() {
        return ENABLED
                && VulkanRegionArenaIngress.indirectDrawVerified();
    }
}
