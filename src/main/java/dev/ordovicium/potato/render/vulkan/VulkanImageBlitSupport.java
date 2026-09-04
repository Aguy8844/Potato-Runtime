package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFormatProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Validates the format-feature requirements for Potato's offscreen -> swapchain
 * blit path.
 */
final class VulkanImageBlitSupport {
    private VulkanImageBlitSupport() {
    }

    static void verify(
            VkPhysicalDevice physicalDevice,
            int sourceFormat,
            int destinationFormat,
            JsonObject report
    ) {
        report.addProperty(
                "stage",
                "VERIFY_OFFSCREEN_SWAPCHAIN_BLIT_SUPPORT"
        );

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties source =
                    VkFormatProperties.calloc(stack);

            VkFormatProperties destination =
                    VkFormatProperties.calloc(stack);

            vkGetPhysicalDeviceFormatProperties(
                    physicalDevice,
                    sourceFormat,
                    source
            );

            vkGetPhysicalDeviceFormatProperties(
                    physicalDevice,
                    destinationFormat,
                    destination
            );

            boolean sourceBlitSupported =
                    (source.optimalTilingFeatures()
                            & VK_FORMAT_FEATURE_BLIT_SRC_BIT) != 0;

            boolean destinationBlitSupported =
                    (destination.optimalTilingFeatures()
                            & VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0;

            report.addProperty(
                    "offscreenBlitSourceFormat",
                    sourceFormat
            );
            report.addProperty(
                    "offscreenBlitDestinationFormat",
                    destinationFormat
            );
            report.addProperty(
                    "offscreenBlitSourceSupported",
                    sourceBlitSupported
            );
            report.addProperty(
                    "offscreenBlitDestinationSupported",
                    destinationBlitSupported
            );
            report.addProperty(
                    "offscreenBlitFormatConversion",
                    sourceFormat != destinationFormat
            );
            report.addProperty(
                    "offscreenBlitFilter",
                    "NEAREST"
            );

            if (!sourceBlitSupported) {
                throw new VulkanProbeException(
                        "VERIFY_OFFSCREEN_SWAPCHAIN_BLIT_SUPPORT",
                        "Offscreen color format does not expose VK_FORMAT_FEATURE_BLIT_SRC_BIT."
                );
            }

            if (!destinationBlitSupported) {
                throw new VulkanProbeException(
                        "VERIFY_OFFSCREEN_SWAPCHAIN_BLIT_SUPPORT",
                        "Swapchain format does not expose VK_FORMAT_FEATURE_BLIT_DST_BIT."
                );
            }

            report.addProperty(
                    "offscreenSwapchainBlitSupportVerified",
                    true
            );
        }
    }
}
