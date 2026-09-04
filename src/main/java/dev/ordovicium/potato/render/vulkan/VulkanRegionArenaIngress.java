package dev.ordovicium.potato.render.vulkan;

import dev.ordovicium.potato.render.backend.draw.DrawGeometryView;

/**
 * Cheap backend-neutral ingress from Minecraft VertexBuffer uploads into the
 * active Vulkan region arena.
 *
 * <p>The ingress exists independently from visible Vulkan ownership. When the
 * Vulkan runtime is absent or the arena failed open, calls are no-ops.</p>
 */
public final class VulkanRegionArenaIngress {
    private static final Object LOCK = new Object();

    private static volatile VulkanDeviceLocalRegionArena active;

    private VulkanRegionArenaIngress() {
    }

    static void register(VulkanDeviceLocalRegionArena arena) {
        synchronized (LOCK) {
            active = arena;
        }
    }

    static void unregister(VulkanDeviceLocalRegionArena arena) {
        synchronized (LOCK) {
            if (active == arena) {
                active = null;
            }
        }
    }

    public static void onUpload(
            Object owner,
            long sourceGeneration,
            DrawGeometryView geometry
    ) {
        VulkanDeviceLocalRegionArena arena =
                active;

        if (arena == null || geometry == null) {
            return;
        }

        arena.stage(
                owner,
                sourceGeneration,
                geometry.vertexBytes()
        );
    }

    public static void refreshCompletedTransfers() {
        VulkanDeviceLocalRegionArena arena =
                active;

        if (arena != null) {
            arena.pollTransferCompletions();
        }
    }

    public static long completedSourceGeneration(
            Object owner
    ) {
        VulkanDeviceLocalRegionArena arena =
                active;

        if (arena == null || owner == null) {
            return 0L;
        }

        return arena.completedSourceGeneration(owner);
    }

    public static ResidentSpan touchVisibleAndGetResidentSpan(
            Object owner
    ) {
        VulkanDeviceLocalRegionArena arena =
                active;

        if (arena == null || owner == null) {
            return null;
        }

        VulkanDeviceLocalRegionArena.ResidentSpan span =
                arena.touchVisibleAndResidentSpan(
                        owner
                );

        if (span == null) {
            return null;
        }

        return new ResidentSpan(
                span.offsetBytes(),
                span.usedBytes(),
                span.sourceGeneration()
        );
    }

    public static void submitIndirectCandidateBatch(
            int residentCandidateCount,
            long frameSequence,
            long cameraFingerprint
    ) {
        VulkanDeviceLocalRegionArena arena =
                active;

        if (arena != null) {
            arena.trySubmitIndirectCandidateBatch(
                    residentCandidateCount,
                    frameSequence,
                    cameraFingerprint
            );
        }
    }

    public static boolean indirectDrawExecutionObserved() {
        VulkanDeviceLocalRegionArena arena =
                active;

        return arena != null
                && arena.indirectDrawExecutionObserved();
    }

    public static boolean indirectDrawVerified() {
        VulkanDeviceLocalRegionArena arena =
                active;

        return arena != null
                && arena.indirectDrawVerified();
    }

    public static void onClose(Object owner) {
        VulkanDeviceLocalRegionArena arena =
                active;

        if (arena != null) {
            arena.release(owner);
        }
    }

    public record ResidentSpan(
            long offsetBytes,
            int usedBytes,
            long sourceGeneration
    ) {
    }
}
