package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ordovicium.potato.PotatoRuntime;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.Locale;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Enumerates physical Vulkan devices and selects the best native candidate for
 * Potato Runtime.
 *
 * <p>The selector intentionally prefers native Vulkan ICDs over obvious
 * translation or software devices while still preserving sensible GPU-class
 * ordering.</p>
 */
final class VulkanDeviceCatalog {
    private static final long SCORE_DISCRETE_GPU = 1_000_000L;
    private static final long SCORE_INTEGRATED_GPU = 700_000L;
    private static final long SCORE_VIRTUAL_GPU = 400_000L;
    private static final long SCORE_OTHER = 200_000L;
    private static final long SCORE_CPU = 100_000L;

    private static final long TRANSLATION_LAYER_PENALTY = 250_000L;
    private static final long MAX_MEMORY_SCORE_MIB = 99_999L;

    private VulkanDeviceCatalog() {
    }

    static Selection scan(
            VkInstance instance,
            MemoryStack stack,
            JsonObject report
    ) {
        IntBuffer countBuffer = stack.ints(0);

        int result = vkEnumeratePhysicalDevices(instance, countBuffer, null);
        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "ENUMERATE_PHYSICAL_DEVICE_COUNT",
                    "vkEnumeratePhysicalDevices(count) failed with VkResult " + result
            );
        }

        int deviceCount = countBuffer.get(0);
        report.addProperty("physicalDeviceCount", deviceCount);

        if (deviceCount <= 0) {
            throw new VulkanProbeException(
                    "ENUMERATE_PHYSICAL_DEVICE_COUNT",
                    "Vulkan loader is present, but no Vulkan physical devices were reported."
            );
        }

        PointerBuffer devicePointers = stack.mallocPointer(deviceCount);

        result = vkEnumeratePhysicalDevices(instance, countBuffer, devicePointers);
        if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
            throw new VulkanProbeException(
                    "ENUMERATE_PHYSICAL_DEVICES",
                    "vkEnumeratePhysicalDevices(list) failed with VkResult " + result
            );
        }

        JsonArray devicesJson = new JsonArray();
        report.add("devices", devicesJson);

        Selection best = null;

        for (int index = 0; index < deviceCount; index++) {
            long handle = devicePointers.get(index);

            if (handle == NULL) {
                continue;
            }

            VkPhysicalDevice physicalDevice = new VkPhysicalDevice(handle, instance);

            try (MemoryStack deviceStack = MemoryStack.stackPush()) {
                DeviceSnapshot snapshot = inspect(
                        index,
                        physicalDevice,
                        deviceStack
                );

                devicesJson.add(snapshot.json());

                PotatoRuntime.LOGGER.info(
                        "[Potato/Vulkan] GPU {}: {} | {} | Vulkan {} | local heap ~{} MiB | score {}",
                        index,
                        snapshot.name(),
                        snapshot.type(),
                        snapshot.apiVersion(),
                        snapshot.deviceLocalMemoryMiB(),
                        snapshot.score()
                );

                if (best == null || snapshot.score() > best.score()) {
                    best = new Selection(
                            physicalDevice,
                            index,
                            snapshot.name(),
                            snapshot.score()
                    );
                }
            }
        }

        if (best == null) {
            throw new VulkanProbeException(
                    "SELECT_PHYSICAL_DEVICE",
                    "No usable Vulkan physical device was selected."
            );
        }

        report.addProperty("recommendedDeviceIndex", best.index());
        report.addProperty("recommendedDeviceName", best.name());
        report.addProperty("recommendedDeviceScore", best.score());

        return best;
    }

    private static DeviceSnapshot inspect(
            int index,
            VkPhysicalDevice physicalDevice,
            MemoryStack stack
    ) {
        VkPhysicalDeviceProperties properties =
                VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(physicalDevice, properties);

        VkPhysicalDeviceMemoryProperties memory =
                VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memory);

        VkPhysicalDeviceFeatures features =
                VkPhysicalDeviceFeatures.malloc(stack);
        vkGetPhysicalDeviceFeatures(physicalDevice, features);

        String name = properties.deviceNameString();
        String type = deviceTypeName(properties.deviceType());
        String apiVersion = VulkanFormat.apiVersion(properties.apiVersion());

        long deviceLocalBytes = 0L;
        long totalHeapBytes = 0L;

        JsonArray heapsJson = new JsonArray();

        for (int heapIndex = 0; heapIndex < memory.memoryHeapCount(); heapIndex++) {
            VkMemoryHeap heap = memory.memoryHeaps(heapIndex);

            long heapBytes = heap.size();
            boolean deviceLocal =
                    (heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0;

            totalHeapBytes += heapBytes;

            if (deviceLocal) {
                deviceLocalBytes += heapBytes;
            }

            JsonObject heapJson = new JsonObject();
            heapJson.addProperty("index", heapIndex);
            heapJson.addProperty("sizeMiB", VulkanFormat.bytesToMiB(heapBytes));
            heapJson.addProperty("deviceLocal", deviceLocal);

            heapsJson.add(heapJson);
        }

        boolean translationLayer = isSuspectedTranslationLayer(name);
        long score = score(
                properties.deviceType(),
                deviceLocalBytes,
                translationLayer
        );

        JsonObject featuresJson = new JsonObject();
        featuresJson.addProperty("samplerAnisotropy", features.samplerAnisotropy());
        featuresJson.addProperty("multiDrawIndirect", features.multiDrawIndirect());
        featuresJson.addProperty(
                "drawIndirectFirstInstance",
                features.drawIndirectFirstInstance()
        );
        featuresJson.addProperty("geometryShader", features.geometryShader());
        featuresJson.addProperty("tessellationShader", features.tessellationShader());
        featuresJson.addProperty("shaderInt64", features.shaderInt64());
        featuresJson.addProperty("fillModeNonSolid", features.fillModeNonSolid());
        featuresJson.addProperty("wideLines", features.wideLines());

        JsonObject json = new JsonObject();
        json.addProperty("index", index);
        json.addProperty("name", name);
        json.addProperty("type", type);
        json.addProperty("typeRaw", properties.deviceType());
        json.addProperty("vendorId", String.format("0x%04X", properties.vendorID()));
        json.addProperty("deviceId", String.format("0x%04X", properties.deviceID()));
        json.addProperty("apiVersion", apiVersion);
        json.addProperty(
                "apiVersionRaw",
                Integer.toUnsignedLong(properties.apiVersion())
        );
        json.addProperty(
                "driverVersionRaw",
                Integer.toUnsignedLong(properties.driverVersion())
        );
        json.addProperty(
                "deviceLocalMemoryMiB",
                VulkanFormat.bytesToMiB(deviceLocalBytes)
        );
        json.addProperty(
                "totalHeapMemoryMiB",
                VulkanFormat.bytesToMiB(totalHeapBytes)
        );
        json.addProperty("suspectedTranslationLayer", translationLayer);
        json.addProperty("potatoSelectionScore", score);
        json.add("memoryHeaps", heapsJson);
        json.add("features", featuresJson);

        return new DeviceSnapshot(
                name,
                type,
                apiVersion,
                VulkanFormat.bytesToMiB(deviceLocalBytes),
                score,
                json
        );
    }

    private static long score(
            int deviceType,
            long deviceLocalBytes,
            boolean translationLayer
    ) {
        long base = switch (deviceType) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> SCORE_DISCRETE_GPU;
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> SCORE_INTEGRATED_GPU;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> SCORE_VIRTUAL_GPU;
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> SCORE_CPU;
            default -> SCORE_OTHER;
        };

        long memoryScore = Math.min(
                VulkanFormat.bytesToMiB(deviceLocalBytes),
                MAX_MEMORY_SCORE_MIB
        );

        return base
                + memoryScore
                - (translationLayer ? TRANSLATION_LAYER_PENALTY : 0L);
    }

    private static boolean isSuspectedTranslationLayer(String deviceName) {
        String normalized = deviceName.toLowerCase(Locale.ROOT);

        return normalized.startsWith("microsoft direct3d12")
                || normalized.contains("basic render driver")
                || normalized.contains("llvmpipe")
                || normalized.contains("swiftshader");
    }

    private static String deviceTypeName(int deviceType) {
        return switch (deviceType) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "DISCRETE_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "INTEGRATED_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "VIRTUAL_GPU";
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU";
            case VK_PHYSICAL_DEVICE_TYPE_OTHER -> "OTHER";
            default -> "UNKNOWN_" + deviceType;
        };
    }

    record Selection(
            VkPhysicalDevice physicalDevice,
            int index,
            String name,
            long score
    ) {
    }

    private record DeviceSnapshot(
            String name,
            String type,
            String apiVersion,
            long deviceLocalMemoryMiB,
            long score,
            JsonObject json
    ) {
    }
}