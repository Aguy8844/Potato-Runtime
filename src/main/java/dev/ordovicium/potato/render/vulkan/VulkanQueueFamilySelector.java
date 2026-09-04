package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;

import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Inspects every queue family and selects graphics/presentation capabilities
 * independently.
 *
 * <p>The full topology is always reported. Selection prefers one shared
 * graphics+present family when available, but diagnostics never stop early.</p>
 */
final class VulkanQueueFamilySelector {
    private VulkanQueueFamilySelector() {
    }

    static Selection select(
            VkPhysicalDevice physicalDevice,
            long surface,
            MemoryStack stack,
            JsonObject report
    ) {
        IntBuffer countBuffer = stack.ints(0);

        vkGetPhysicalDeviceQueueFamilyProperties(
                physicalDevice,
                countBuffer,
                null
        );

        int familyCount = countBuffer.get(0);
        report.addProperty("queueFamilyCount", familyCount);

        if (familyCount <= 0) {
            throw new VulkanProbeException(
                    "QUERY_QUEUE_FAMILY_COUNT",
                    "Selected GPU exposes no Vulkan queue families."
            );
        }

        VkQueueFamilyProperties.Buffer families =
                VkQueueFamilyProperties.malloc(familyCount, stack);

        vkGetPhysicalDeviceQueueFamilyProperties(
                physicalDevice,
                countBuffer,
                families
        );

        JsonArray familiesJson = new JsonArray();
        report.add("queueFamilies", familiesJson);

        int firstGraphics = -1;
        int firstPresent = -1;
        int sharedGraphicsPresent = -1;
        int firstTransfer = -1;
        int firstDedicatedTransfer = -1;

        IntBuffer surfaceSupport = stack.mallocInt(1);

        for (int index = 0; index < familyCount; index++) {
            VkQueueFamilyProperties family = families.get(index);
            int flags = family.queueFlags();

            boolean graphics = (flags & VK_QUEUE_GRAPHICS_BIT) != 0;
            boolean compute = (flags & VK_QUEUE_COMPUTE_BIT) != 0;
            boolean transfer = (flags & VK_QUEUE_TRANSFER_BIT) != 0;

            surfaceSupport.put(0, VK_FALSE);

            int surfaceResult = vkGetPhysicalDeviceSurfaceSupportKHR(
                    physicalDevice,
                    index,
                    surface,
                    surfaceSupport
            );

            if (surfaceResult != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "QUERY_SURFACE_SUPPORT",
                        "vkGetPhysicalDeviceSurfaceSupportKHR failed for queue family "
                                + index
                                + " with VkResult "
                                + surfaceResult
                );
            }

            boolean present = surfaceSupport.get(0) == VK_TRUE;

            JsonObject json = new JsonObject();
            json.addProperty("index", index);
            json.addProperty("queueCount", family.queueCount());
            json.addProperty("flagsRaw", flags);
            json.addProperty("graphics", graphics);
            json.addProperty("compute", compute);
            json.addProperty("transfer", transfer);
            json.addProperty("present", present);
            json.addProperty("timestampValidBits", family.timestampValidBits());

            familiesJson.add(json);

            if (firstGraphics < 0 && graphics && family.queueCount() > 0) {
                firstGraphics = index;
            }

            if (firstPresent < 0 && present && family.queueCount() > 0) {
                firstPresent = index;
            }

            if (sharedGraphicsPresent < 0
                    && graphics
                    && present
                    && family.queueCount() > 0) {
                sharedGraphicsPresent = index;
            }

            if (firstTransfer < 0
                    && transfer
                    && family.queueCount() > 0) {
                firstTransfer = index;
            }

            if (firstDedicatedTransfer < 0
                    && transfer
                    && !graphics
                    && !compute
                    && family.queueCount() > 0) {
                firstDedicatedTransfer = index;
            }
        }

        if (firstGraphics < 0) {
            throw new VulkanProbeException(
                    "QUERY_QUEUE_FAMILIES",
                    "Selected GPU exposes no graphics-capable Vulkan queue family."
            );
        }

        if (firstPresent < 0) {
            throw new VulkanProbeException(
                    "QUERY_SURFACE_SUPPORT",
                    "Selected GPU exposes no queue family capable of presenting to the probe surface."
            );
        }

        int graphicsFamily;
        int presentFamily;

        if (sharedGraphicsPresent >= 0) {
            graphicsFamily = sharedGraphicsPresent;
            presentFamily = sharedGraphicsPresent;
        } else {
            graphicsFamily = firstGraphics;
            presentFamily = firstPresent;
        }

        boolean shared = graphicsFamily == presentFamily;

        int transferFamily =
                firstDedicatedTransfer >= 0
                        ? firstDedicatedTransfer
                        : firstTransfer >= 0
                        ? firstTransfer
                        : graphicsFamily;

        boolean transferDedicated =
                transferFamily != graphicsFamily
                        && transferFamily != presentFamily;

        report.addProperty(
                "selectedGraphicsQueueFamilyIndex",
                graphicsFamily
        );
        report.addProperty(
                "selectedPresentQueueFamilyIndex",
                presentFamily
        );
        report.addProperty(
                "selectedTransferQueueFamilyIndex",
                transferFamily
        );
        report.addProperty(
                "graphicsAndPresentShareQueueFamily",
                shared
        );
        report.addProperty(
                "transferQueueDedicated",
                transferDedicated
        );
        report.addProperty(
                "transferQueueSharesGraphicsFamily",
                transferFamily == graphicsFamily
        );

        return new Selection(
                graphicsFamily,
                presentFamily,
                transferFamily
        );
    }

    record Selection(
            int graphicsFamilyIndex,
            int presentFamilyIndex,
            int transferFamilyIndex
    ) {
        boolean sharedFamily() {
            return graphicsFamilyIndex == presentFamilyIndex;
        }

        boolean transferSharesGraphicsFamily() {
            return transferFamilyIndex == graphicsFamilyIndex;
        }

        boolean transferSharesPresentFamily() {
            return transferFamilyIndex == presentFamilyIndex;
        }

        boolean dedicatedTransferFamily() {
            return !transferSharesGraphicsFamily()
                    && !transferSharesPresentFamily();
        }
    }
}