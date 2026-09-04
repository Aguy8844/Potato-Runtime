package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Queries a surface and derives one conservative, valid swapchain
 * configuration from capabilities reported by the driver.
 */
final class VulkanSwapchainSupport {
    private static final int UNDEFINED_SURFACE_EXTENT = 0xFFFFFFFF;
    private static final int REQUIRED_IMAGE_USAGE =
            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                    | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

    private VulkanSwapchainSupport() {
    }

    static Configuration query(
            VkPhysicalDevice physicalDevice,
            long surface,
            long glfwWindow,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty("stage", "QUERY_SURFACE_CAPABILITIES");

        VkSurfaceCapabilitiesKHR capabilities =
                VkSurfaceCapabilitiesKHR.calloc(stack);

        int result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                physicalDevice,
                surface,
                capabilities
        );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "QUERY_SURFACE_CAPABILITIES",
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR failed with VkResult "
                            + result
            );
        }

        report.addProperty(
                "surfaceMinImageCount",
                capabilities.minImageCount()
        );
        report.addProperty(
                "surfaceMaxImageCount",
                capabilities.maxImageCount()
        );
        report.addProperty(
                "surfaceSupportedUsageFlags",
                capabilities.supportedUsageFlags()
        );

        boolean colorAttachmentSupported =
                (capabilities.supportedUsageFlags()
                        & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) != 0;

        boolean transferDstSupported =
                (capabilities.supportedUsageFlags()
                        & VK_IMAGE_USAGE_TRANSFER_DST_BIT) != 0;
        report.addProperty(
                "swapchainColorAttachmentUsageSupported",
                colorAttachmentSupported
        );
        report.addProperty(
                "swapchainTransferDstUsageSupported",
                transferDstSupported
        );
        report.addProperty(
                "swapchainPresentationPath",
                "TRANSFER_DST_FROM_OFFSCREEN"
        );
if ((capabilities.supportedUsageFlags() & REQUIRED_IMAGE_USAGE)
                != REQUIRED_IMAGE_USAGE) {
            throw new VulkanProbeException(
                    "QUERY_SURFACE_CAPABILITIES",
                    "Surface does not support the required swapchain image usage flags: "
                            + REQUIRED_IMAGE_USAGE
            );
        }

        int imageCount = capabilities.minImageCount() + 1;

        if (capabilities.maxImageCount() > 0
                && imageCount > capabilities.maxImageCount()) {
            imageCount = capabilities.maxImageCount();
        }

        Extent extent = chooseExtent(
                capabilities,
                glfwWindow,
                stack
        );

        SurfaceFormat surfaceFormat = chooseSurfaceFormat(
                physicalDevice,
                surface,
                stack,
                report
        );

        int presentMode = choosePresentMode(
                physicalDevice,
                surface,
                stack,
                report
        );

        int compositeAlpha = chooseCompositeAlpha(
                capabilities.supportedCompositeAlpha()
        );

        report.addProperty("selectedSwapchainImageCount", imageCount);
        report.addProperty(
                "selectedSwapchainImageUsage",
                REQUIRED_IMAGE_USAGE
        );
        report.addProperty(
                "selectedSwapchainFormat",
                surfaceFormat.format()
        );
        report.addProperty(
                "selectedSwapchainColorSpace",
                surfaceFormat.colorSpace()
        );
        report.addProperty(
                "selectedPresentMode",
                presentMode
        );
        report.addProperty(
                "selectedSwapchainWidth",
                extent.width()
        );
        report.addProperty(
                "selectedSwapchainHeight",
                extent.height()
        );
        report.addProperty(
                "selectedCompositeAlpha",
                compositeAlpha
        );

        return new Configuration(
                imageCount,
                REQUIRED_IMAGE_USAGE,
                surfaceFormat.format(),
                surfaceFormat.colorSpace(),
                presentMode,
                extent.width(),
                extent.height(),
                capabilities.currentTransform(),
                compositeAlpha
        );
    }

    private static SurfaceFormat chooseSurfaceFormat(
            VkPhysicalDevice physicalDevice,
            long surface,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty("stage", "QUERY_SURFACE_FORMATS");

        IntBuffer countBuffer = stack.ints(0);

        int result = vkGetPhysicalDeviceSurfaceFormatsKHR(
                physicalDevice,
                surface,
                countBuffer,
                null
        );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "QUERY_SURFACE_FORMATS",
                    "vkGetPhysicalDeviceSurfaceFormatsKHR(count) failed with VkResult "
                            + result
            );
        }

        int count = countBuffer.get(0);

        if (count <= 0) {
            throw new VulkanProbeException(
                    "QUERY_SURFACE_FORMATS",
                    "Surface exposes no Vulkan surface formats."
            );
        }

        VkSurfaceFormatKHR.Buffer formats =
                VkSurfaceFormatKHR.malloc(count, stack);

        result = vkGetPhysicalDeviceSurfaceFormatsKHR(
                physicalDevice,
                surface,
                countBuffer,
                formats
        );

        if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
            throw new VulkanProbeException(
                    "QUERY_SURFACE_FORMATS",
                    "vkGetPhysicalDeviceSurfaceFormatsKHR(list) failed with VkResult "
                            + result
            );
        }

        JsonArray json = new JsonArray();

        SurfaceFormat first = null;
        SurfaceFormat preferredDisplayEncodedRgba = null;
        SurfaceFormat preferredDisplayEncodedBgra = null;
        SurfaceFormat preferredSrgb = null;

        for (int index = 0; index < count; index++) {
            VkSurfaceFormatKHR candidate = formats.get(index);

            SurfaceFormat value = new SurfaceFormat(
                    candidate.format(),
                    candidate.colorSpace()
            );

            if (first == null) {
                first = value;
            }

            if (candidate.colorSpace()
                    == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                if (candidate.format() == VK_FORMAT_R8G8B8A8_UNORM) {
                    preferredDisplayEncodedRgba = value;
                } else if (candidate.format() == VK_FORMAT_B8G8R8A8_UNORM) {
                    preferredDisplayEncodedBgra = value;
                } else if (candidate.format() == VK_FORMAT_B8G8R8A8_SRGB) {
                    preferredSrgb = value;
                }
            }

            JsonObject entry = new JsonObject();
            entry.addProperty("format", candidate.format());
            entry.addProperty("colorSpace", candidate.colorSpace());
            json.add(entry);
        }

        report.add("surfaceFormats", json);

        /*
         * Gate-11 currently presents a final Minecraft frame which was already
         * rasterized by OpenGL. Those bytes are display-referred. Selecting an
         * sRGB swapchain here asks Vulkan's blit conversion to apply another
         * linear -> sRGB transfer to values which are already encoded, producing
         * the visibly washed-out/high-brightness image seen in Patch 140a.
         *
         * Until the true Vulkan MainTarget owns linear scene color, prefer an
         * UNORM swapchain (prefer the exact R8G8B8A8 capture format) while
         * keeping SRGB_NONLINEAR as the presentation color
         * space. The driver still presents to an sRGB desktop, but no second
         * image-format transfer function is applied to the captured final pixels.
         */
        SurfaceFormat selected =
                preferredDisplayEncodedRgba != null
                        ? preferredDisplayEncodedRgba
                        : preferredDisplayEncodedBgra != null
                        ? preferredDisplayEncodedBgra
                        : preferredSrgb != null
                        ? preferredSrgb
                        : first;

        report.addProperty(
                "swapchainSurfaceFormatPreference",
                "DISPLAY_ENCODED_FINAL_PIXEL_UNORM_FIRST"
        );
        report.addProperty(
                "swapchainDisplayEncodedUnormSelected",
                selected != null
                        && (selected.format() == VK_FORMAT_B8G8R8A8_UNORM
                        || selected.format() == VK_FORMAT_R8G8B8A8_UNORM)
                        && selected.colorSpace()
                        == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR
        );
        report.addProperty(
                "swapchainAvoidsDoubleSrgbTransfer",
                selected != null
                        && selected.format() != VK_FORMAT_B8G8R8A8_SRGB
        );

        return selected;
    }

    private static int choosePresentMode(
            VkPhysicalDevice physicalDevice,
            long surface,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty("stage", "QUERY_PRESENT_MODES");

        IntBuffer countBuffer = stack.ints(0);

        int result = vkGetPhysicalDeviceSurfacePresentModesKHR(
                physicalDevice,
                surface,
                countBuffer,
                null
        );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "QUERY_PRESENT_MODES",
                    "vkGetPhysicalDeviceSurfacePresentModesKHR(count) failed with VkResult "
                            + result
            );
        }

        int count = countBuffer.get(0);

        if (count <= 0) {
            throw new VulkanProbeException(
                    "QUERY_PRESENT_MODES",
                    "Surface exposes no Vulkan present modes."
            );
        }

        IntBuffer modes = stack.mallocInt(count);

        result = vkGetPhysicalDeviceSurfacePresentModesKHR(
                physicalDevice,
                surface,
                countBuffer,
                modes
        );

        if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
            throw new VulkanProbeException(
                    "QUERY_PRESENT_MODES",
                    "vkGetPhysicalDeviceSurfacePresentModesKHR(list) failed with VkResult "
                            + result
            );
        }

        JsonArray json = new JsonArray();
        boolean fifoAvailable = false;

        for (int index = 0; index < count; index++) {
            int mode = modes.get(index);
            json.add(mode);

            if (mode == VK_PRESENT_MODE_FIFO_KHR) {
                fifoAvailable = true;
            }
        }

        report.add("presentModes", json);

        if (!fifoAvailable) {
            throw new VulkanProbeException(
                    "QUERY_PRESENT_MODES",
                    "VK_PRESENT_MODE_FIFO_KHR was unexpectedly unavailable."
            );
        }

        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private static Extent chooseExtent(
            VkSurfaceCapabilitiesKHR capabilities,
            long glfwWindow,
            MemoryStack stack
    ) {
        int currentWidth = capabilities.currentExtent().width();
        int currentHeight = capabilities.currentExtent().height();

        if (currentWidth != UNDEFINED_SURFACE_EXTENT
                && currentHeight != UNDEFINED_SURFACE_EXTENT) {
            return new Extent(currentWidth, currentHeight);
        }

        IntBuffer widthBuffer = stack.mallocInt(1);
        IntBuffer heightBuffer = stack.mallocInt(1);

        glfwGetFramebufferSize(
                glfwWindow,
                widthBuffer,
                heightBuffer
        );

        int width = clamp(
                widthBuffer.get(0),
                capabilities.minImageExtent().width(),
                capabilities.maxImageExtent().width()
        );

        int height = clamp(
                heightBuffer.get(0),
                capabilities.minImageExtent().height(),
                capabilities.maxImageExtent().height()
        );

        return new Extent(width, height);
    }

    private static int chooseCompositeAlpha(int supportedFlags) {
        int[] preference = {
                VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
                VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
                VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
                VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR
        };

        for (int candidate : preference) {
            if ((supportedFlags & candidate) != 0) {
                return candidate;
            }
        }

        throw new VulkanProbeException(
                "QUERY_SURFACE_CAPABILITIES",
                "Surface reports no supported composite-alpha mode."
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    record Configuration(
            int imageCount,
            int imageUsage,
            int format,
            int colorSpace,
            int presentMode,
            int width,
            int height,
            int preTransform,
            int compositeAlpha
    ) {
    }

    private record SurfaceFormat(
            int format,
            int colorSpace
    ) {
    }

    private record Extent(
            int width,
            int height
    ) {
    }
}