package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;

import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * Immutable set of Vulkan features negotiated by Potato's current backend
 * stage.
 *
 * <p>Feature support is queried before logical-device creation. Required
 * features are never enabled optimistically.</p>
 */
record VulkanFeatureSet(
        boolean dynamicRendering
) {
    static VulkanFeatureSet queryRequired(
            VkPhysicalDevice physicalDevice,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty("stage", "QUERY_VULKAN_13_FEATURES");

        VkPhysicalDeviceVulkan13Features vulkan13 =
                VkPhysicalDeviceVulkan13Features.calloc(stack)
                        .sType$Default();

        VkPhysicalDeviceFeatures2 features2 =
                VkPhysicalDeviceFeatures2.calloc(stack)
                        .sType$Default()
                        .pNext(vulkan13.address());

        vkGetPhysicalDeviceFeatures2(
                physicalDevice,
                features2
        );

        boolean dynamicRendering =
                vulkan13.dynamicRendering();

        report.addProperty(
                "dynamicRenderingSupported",
                dynamicRendering
        );

        if (!dynamicRendering) {
            throw new VulkanProbeException(
                    "QUERY_VULKAN_13_FEATURES",
                    "Selected physical device does not support Vulkan dynamicRendering."
            );
        }

        return new VulkanFeatureSet(true);
    }

    VkPhysicalDeviceVulkan13Features createEnableChain(
            MemoryStack stack
    ) {
        VkPhysicalDeviceVulkan13Features enabled =
                VkPhysicalDeviceVulkan13Features.calloc(stack)
                        .sType$Default();

        enabled.dynamicRendering(dynamicRendering);

        return enabled;
    }
}