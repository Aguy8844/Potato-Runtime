package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.DrawSubmissionSink;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;

/**
 * Owns the Vulkan core after the startup surface/window has been retired.
 *
 * <p>This is deliberately separate from {@link VulkanRuntimeContext}: the
 * latter owns presentation-shaped resources, while this class owns only the
 * VkInstance/VkDevice/queues and bounded resource-side consumers needed during
 * the OpenGL transition period.</p>
 */
final class VulkanHeadlessResourceRuntime
        implements AutoCloseable {

    private final VulkanProbeContext core;
    private final VkPhysicalDevice physicalDevice;
    private final VkQueue graphicsQueue;
    private final VkQueue transferQueue;
    private final int graphicsQueueFamilyIndex;
    private final int transferQueueFamilyIndex;
    private final JsonObject report;

    private final VulkanGeometryUploadPrototype geometryUpload;
    private final VulkanHeadlessDrawSubmissionBackend drawSubmissionBackend;

    private final long installedAtNanos;
    private boolean closed;

    VulkanHeadlessResourceRuntime(
            VulkanProbeContext core,
            VkPhysicalDevice physicalDevice,
            VkQueue graphicsQueue,
            VkQueue transferQueue,
            int graphicsQueueFamilyIndex,
            int transferQueueFamilyIndex,
            JsonObject report
    ) {
        if (core == null
                || core.instance() == null
                || core.device() == null
                || physicalDevice == null
                || graphicsQueue == null
                || transferQueue == null
                || graphicsQueueFamilyIndex < 0
                || transferQueueFamilyIndex < 0
                || report == null) {
            throw new VulkanProbeException(
                    "CREATE_HEADLESS_VULKAN_RESOURCE_RUNTIME",
                    "Headless Vulkan core/device/queue ownership is incomplete."
            );
        }

        this.core = core;
        this.physicalDevice = physicalDevice;
        this.graphicsQueue = graphicsQueue;
        this.transferQueue = transferQueue;
        this.graphicsQueueFamilyIndex =
                graphicsQueueFamilyIndex;
        this.transferQueueFamilyIndex =
                transferQueueFamilyIndex;
        this.report = report;

        this.geometryUpload =
                new VulkanGeometryUploadPrototype(
                        core.device(),
                        physicalDevice,
                        report
                );

        this.drawSubmissionBackend =
                new VulkanHeadlessDrawSubmissionBackend(
                        geometryUpload,
                        report
                );

        this.installedAtNanos =
                System.nanoTime();

        report.addProperty(
                "headlessVulkanResourceRuntimeCreated",
                true
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeWindowless",
                true
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeSurfaceLess",
                true
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeOwnsInstance",
                true
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeOwnsLogicalDevice",
                true
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeOwnsGraphicsQueue",
                true
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeOwnsTransferQueue",
                true
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeGraphicsQueueFamilyIndex",
                graphicsQueueFamilyIndex
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeTransferQueueFamilyIndex",
                transferQueueFamilyIndex
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeTransferQueueDedicated",
                transferQueueFamilyIndex
                        != graphicsQueueFamilyIndex
        );
        report.addProperty(
                "headlessVulkanResourceRuntimePresentationOwned",
                false
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeFrameSessionOwned",
                false
        );
    }

    DrawSubmissionSink drawSubmissionSink() {
        return drawSubmissionBackend;
    }

    VkQueue graphicsQueue() {
        return closed
                ? null
                : graphicsQueue;
    }

    VkQueue transferQueue() {
        return closed
                ? null
                : transferQueue;
    }

    int graphicsQueueFamilyIndex() {
        return closed
                ? -1
                : graphicsQueueFamilyIndex;
    }

    int transferQueueFamilyIndex() {
        return closed
                ? -1
                : transferQueueFamilyIndex;
    }

    boolean alive() {
        return !closed
                && core.instance() != null
                && core.device() != null
                && physicalDevice != null
                && graphicsQueue != null
                && transferQueue != null;
    }

    boolean verified() {
        return alive()
                && drawSubmissionBackend.verified();
    }

    boolean closed() {
        return closed;
    }

    void enrich() {
        drawSubmissionBackend.enrich();

        report.addProperty(
                "headlessVulkanResourceRuntimeActive",
                alive()
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeVerified",
                verified()
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeWindowHandleRetained",
                false
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeSurfaceRetained",
                false
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeSwapchainRetained",
                false
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeFrameSessionRetained",
                false
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeFutureTransferUploadsReady",
                transferQueue != null
                        && transferQueueFamilyIndex >= 0
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        int idleResult =
                Integer.MIN_VALUE;

        try {
            idleResult =
                    vkDeviceWaitIdle(
                            core.device()
                    );
        } catch (Throwable throwable) {
            report.addProperty(
                    "headlessVulkanResourceRuntimeShutdownDeviceIdleError",
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            )
            );
        }

        report.addProperty(
                "headlessVulkanResourceRuntimeShutdownDeviceWaitIdleResult",
                idleResult
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeShutdownDeviceIdleSuccessful",
                idleResult == VK_SUCCESS
        );

        drawSubmissionBackend.close();

        long coreCloseStartNanos =
                System.nanoTime();

        core.closeRuntimeOwnedResources();

        report.addProperty(
                "headlessVulkanResourceRuntimeShutdownCoreCloseMillis",
                (System.nanoTime()
                        - coreCloseStartNanos)
                        / 1_000_000L
        );

        closed = true;

        report.addProperty(
                "headlessVulkanResourceRuntimeLifetimeMillis",
                (System.nanoTime()
                        - installedAtNanos)
                        / 1_000_000L
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeClosed",
                true
        );
    }
}
