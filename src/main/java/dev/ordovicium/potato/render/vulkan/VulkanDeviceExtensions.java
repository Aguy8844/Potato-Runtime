package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Set;

import static org.lwjgl.vulkan.KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphore.VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Validates required device extensions and reports only capability signals
 * Potato actually cares about.
 */
final class VulkanDeviceExtensions {
    private static final Set<String> INTERESTING_EXTENSIONS = Set.of(
            "VK_KHR_swapchain",
            "VK_KHR_synchronization2",
            "VK_KHR_timeline_semaphore",
            "VK_EXT_memory_budget",
            "VK_EXT_descriptor_indexing",
            "VK_EXT_descriptor_buffer",
            "VK_EXT_mesh_shader",
            "VK_KHR_dynamic_rendering",
            "VK_KHR_present_wait",
            "VK_KHR_external_memory",
            "VK_KHR_external_memory_win32",
            "VK_KHR_external_semaphore",
            "VK_KHR_external_semaphore_win32"
    );

    private VulkanDeviceExtensions() {
    }

    static void requireSwapchain(
            VkPhysicalDevice physicalDevice,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty("stage", "QUERY_DEVICE_EXTENSIONS");

        IntBuffer countBuffer = stack.ints(0);

        int result = vkEnumerateDeviceExtensionProperties(
                physicalDevice,
                (ByteBuffer) null,
                countBuffer,
                null
        );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "QUERY_DEVICE_EXTENSIONS",
                    "vkEnumerateDeviceExtensionProperties(count) failed with VkResult "
                            + result
            );
        }

        int count = countBuffer.get(0);
        report.addProperty("deviceExtensionCount", count);

        if (count <= 0) {
            throw new VulkanProbeException(
                    "QUERY_DEVICE_EXTENSIONS",
                    "Selected physical device reports no Vulkan device extensions."
            );
        }

        VkExtensionProperties.Buffer properties =
                VkExtensionProperties.malloc(count);

        try {
            result = vkEnumerateDeviceExtensionProperties(
                    physicalDevice,
                    (ByteBuffer) null,
                    countBuffer,
                    properties
            );

            if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
                throw new VulkanProbeException(
                        "QUERY_DEVICE_EXTENSIONS",
                        "vkEnumerateDeviceExtensionProperties(list) failed with VkResult "
                                + result
                );
            }

            boolean swapchainAvailable = false;
            boolean externalMemoryAvailable = false;
            boolean externalMemoryWin32Available = false;
            boolean externalSemaphoreAvailable = false;
            boolean externalSemaphoreWin32Available = false;
            JsonArray capabilities = new JsonArray();

            for (int index = 0; index < count; index++) {
                String name = properties.get(index).extensionNameString();

                if (VK_KHR_SWAPCHAIN_EXTENSION_NAME.equals(name)) {
                    swapchainAvailable = true;
                }

                if (VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME.equals(name)) {
                    externalMemoryAvailable = true;
                }

                if (VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME.equals(name)) {
                    externalMemoryWin32Available = true;
                }

                if (VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME.equals(name)) {
                    externalSemaphoreAvailable = true;
                }

                if (VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME.equals(name)) {
                    externalSemaphoreWin32Available = true;
                }

                if (INTERESTING_EXTENSIONS.contains(name)) {
                    capabilities.add(name);
                }
            }

            report.addProperty(
                    "swapchainExtensionAvailable",
                    swapchainAvailable
            );
            report.add(
                    "notableDeviceExtensions",
                    capabilities
            );

            report.addProperty(
                    "vulkanExternalMemoryExtensionAvailable",
                    externalMemoryAvailable
            );
            report.addProperty(
                    "vulkanExternalMemoryWin32ExtensionAvailable",
                    externalMemoryWin32Available
            );
            report.addProperty(
                    "vulkanExternalSemaphoreExtensionAvailable",
                    externalSemaphoreAvailable
            );
            report.addProperty(
                    "vulkanExternalSemaphoreWin32ExtensionAvailable",
                    externalSemaphoreWin32Available
            );
            report.addProperty(
                    "vulkanOpenGlWin32InteropDeviceExtensionsAvailable",
                    externalMemoryAvailable
                            && externalMemoryWin32Available
                            && externalSemaphoreAvailable
                            && externalSemaphoreWin32Available
            );

            if (!swapchainAvailable) {
                throw new VulkanProbeException(
                        "QUERY_DEVICE_EXTENSIONS",
                        "Selected physical device does not expose "
                                + VK_KHR_SWAPCHAIN_EXTENSION_NAME
                );
            }
        } finally {
            properties.free();
        }
    }
}