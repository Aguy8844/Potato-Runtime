package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns the Vulkan objects created by the startup probe.
 *
 * <p>Ownership is deliberately explicit. The probe may fail at any stage and
 * this object guarantees deterministic cleanup in reverse creation order.</p>
 */
final class VulkanProbeContext implements AutoCloseable {
    private final JsonObject report;

    private VkInstance instance;
    private VkDevice device;

    private boolean transferredToRuntime;
    private boolean destroyed;

    VulkanProbeContext(JsonObject report) {
        this.report = report;
    }

    VkInstance instance() {
        return instance;
    }

    VkDevice device() {
        return device;
    }

    void own(VkInstance instance) {
        this.instance = instance;
    }

    void own(VkDevice device) {
        this.device = device;
    }

    void transferOwnershipToRuntime() {
        if (destroyed) {
            throw new IllegalStateException(
                    "Cannot transfer an already destroyed Vulkan probe context."
            );
        }

        transferredToRuntime = true;

        report.addProperty(
                "vulkanCoreOwnershipTransferredToRuntime",
                true
        );
    }

    void closeRuntimeOwnedResources() {
        transferredToRuntime = false;
        close();
    }

    @Override
    public void close() {
        if (destroyed) {
            return;
        }

        if (transferredToRuntime) {
            report.addProperty(
                    "logicalDeviceRetainedByRuntime",
                    device != null
            );
            report.addProperty(
                    "instanceRetainedByRuntime",
                    instance != null
            );
            report.addProperty(
                    "logicalDeviceDestroyed",
                    false
            );
            report.addProperty(
                    "instanceDestroyed",
                    false
            );
            return;
        }

        destroyed = true;

        boolean deviceDestroyed = false;
        boolean instanceDestroyed = false;

        if (device != null) {
            try {
                int idleResult = vkDeviceWaitIdle(device);
                report.addProperty("vkDeviceWaitIdleResult", idleResult);
            } catch (Throwable throwable) {
                report.addProperty(
                        "deviceWaitIdleCleanupError",
                        String.valueOf(throwable.getMessage())
                );
            }

            try {
                vkDestroyDevice(device, null);
                deviceDestroyed = true;
            } catch (Throwable throwable) {
                report.addProperty(
                        "deviceDestroyError",
                        String.valueOf(throwable.getMessage())
                );
            }
        }

        report.addProperty("logicalDeviceDestroyed", deviceDestroyed);

        if (instance != null) {
            try {
                vkDestroyInstance(instance, null);
                instanceDestroyed = true;
            } catch (Throwable throwable) {
                report.addProperty(
                        "instanceDestroyError",
                        String.valueOf(throwable.getMessage())
                );
            }
        }

        report.addProperty("instanceDestroyed", instanceDestroyed);
    }
}