package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.PotatoRuntime;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Locale;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Minimal Vulkan physical-device snapshot captured at the NeoForge handoff.
 *
 * <p>This exists before Potato's full presentation probe because Minecraft's
 * {@code Window} constructor immediately asks OpenGL for a maximum texture
 * dimension. A Vulkan main-window path cannot make that GL query.</p>
 */
public final class VulkanEarlyDeviceLimits {
    private static final long SCORE_DISCRETE_GPU = 1_000_000L;
    private static final long SCORE_INTEGRATED_GPU = 700_000L;
    private static final long SCORE_VIRTUAL_GPU = 400_000L;
    private static final long SCORE_OTHER = 200_000L;
    private static final long SCORE_CPU = 100_000L;

    private static final long TRANSLATION_LAYER_PENALTY = 250_000L;
    private static final long MAX_MEMORY_SCORE_MIB = 99_999L;

    private static final Object LOCK = new Object();

    private static boolean attempted;
    private static boolean success;
    private static boolean instanceDestroyed;
    private static boolean contextPreserved;

    private static int deviceCount;
    private static int selectedDeviceIndex = -1;
    private static String selectedDeviceName = "";
    private static long selectedDeviceScore = Long.MIN_VALUE;
    private static int selectedMaxImageDimension2D;
    private static long contextBefore;
    private static long contextAfter;
    private static String error = "";

    private static boolean isolatedWorkerUsed;
    private static boolean isolatedWorkerStarted;
    private static boolean isolatedWorkerJoined;
    private static boolean callerInterruptedWhileJoining;
    private static String isolatedWorkerThreadName = "";
    private static String captureStage = "NOT_STARTED";
    private static String failureStage = "";

    private static boolean rawHandleProbeUsed;
    private static boolean rawInstanceCreated;
    private static boolean rawFunctionResolutionComplete;
    private static boolean lwjglInstanceWrapperBypassed;

    private VulkanEarlyDeviceLimits() {
    }

    public static void capture() {
        synchronized (LOCK) {
            if (attempted) {
                return;
            }

            attempted = true;
            captureStage = "DISPATCHING_ISOLATED_WORKER";
        }

        /*
         * This call happens while NeoForge/Minecraft still owns an active
         * bootstrap MemoryStack scope on the Render thread. LWJGL's Vulkan
         * bootstrap helpers themselves use the thread-local MemoryStack
         * internally (for example VK.getInstanceVersionSupported() and
         * VkInstance capability discovery). Reusing that already-consumed
         * caller stack can therefore fail even when Potato's own large structs
         * are heap allocated.
         *
         * A fresh short-lived thread gives those internal LWJGL helpers a clean
         * thread-local native stack. This is a synchronous STARTUP operation,
         * not a gameplay wait.
         */
        long before =
                org.lwjgl.glfw.GLFW.glfwGetCurrentContext();

        Thread worker = null;
        boolean interrupted = false;

        try {
            worker =
                    new Thread(
                            VulkanEarlyDeviceLimits::captureOnIsolatedWorker,
                            "Potato Vulkan Early Limits"
                    );

            worker.setDaemon(true);
            worker.start();

            synchronized (LOCK) {
                isolatedWorkerStarted = true;
            }

            for (;;) {
                try {
                    worker.join();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }

            synchronized (LOCK) {
                isolatedWorkerJoined = true;
            }
        } catch (Throwable throwable) {
            synchronized (LOCK) {
                failureStage = captureStage;
                captureStage = "FAILED";
                error =
                        throwable.getClass().getName()
                                + ": "
                                + String.valueOf(
                                        throwable.getMessage()
                                );
                success = false;
            }

            PotatoRuntime.LOGGER.warn(
                    "[Potato/Vulkan] Could not dispatch isolated early Vulkan probe; OpenGL baseline fallback remains available.",
                    throwable
            );
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }

            long after =
                    org.lwjgl.glfw.GLFW.glfwGetCurrentContext();

            synchronized (LOCK) {
                callerInterruptedWhileJoining =
                        interrupted;
                contextBefore = before;
                contextAfter = after;
                contextPreserved =
                        before == after;
            }
        }
    }

    private static void captureOnIsolatedWorker() {
        synchronized (LOCK) {
            isolatedWorkerUsed = true;
            isolatedWorkerThreadName =
                    Thread.currentThread().getName();
            captureStage = "WORKER_STARTED";
            rawHandleProbeUsed = true;
            lwjglInstanceWrapperBypassed = true;
        }

        /*
         * Do not construct LWJGL's VkInstance wrapper here.
         *
         * LWJGL 3.3.3's VkInstance constructor eagerly enumerates every
         * physical device's extension properties while building instance
         * capabilities. That implementation uses the default thread-local
         * MemoryStack and may exceed its 64 KiB default capacity on drivers
         * exposing a large extension set.
         *
         * Early limits only need Vulkan 1.0 core commands. Keep this bootstrap
         * probe deliberately tiny: create a raw VkInstance handle, resolve the
         * four core commands we need through vkGetInstanceProcAddr, execute
         * them through JNI, then destroy the raw instance. The later full
         * Potato Vulkan runtime still owns normal LWJGL capability objects.
         */
        long instanceHandle = NULL;
        long destroyInstanceFunction = NULL;

        VkInstanceCreateInfo createInfo = null;
        IntBuffer countBuffer = null;
        PointerBuffer devices = null;

        try {
            synchronized (LOCK) {
                captureStage =
                        "ALLOCATING_RAW_INSTANCE_CREATE_INFO";
            }

            createInfo =
                    VkInstanceCreateInfo.calloc()
                            .sType$Default();

            PointerBuffer instancePointer =
                    memCallocPointer(1);

            try {
                synchronized (LOCK) {
                    captureStage =
                            "CREATING_RAW_INSTANCE";
                }

                int result =
                        vkCreateInstance(
                                createInfo,
                                null,
                                instancePointer
                        );

                if (result != VK_SUCCESS) {
                    throw new IllegalStateException(
                            "vkCreateInstance failed with VkResult "
                                    + result
                    );
                }

                instanceHandle =
                        instancePointer.get(0);
            } finally {
                memFree(instancePointer);
            }

            if (instanceHandle == NULL) {
                throw new IllegalStateException(
                        "vkCreateInstance returned a null instance handle."
                );
            }

            synchronized (LOCK) {
                rawInstanceCreated = true;
                captureStage =
                        "RESOLVING_RAW_INSTANCE_COMMANDS";
            }

            destroyInstanceFunction =
                    resolveInstanceFunction(
                            instanceHandle,
                            "vkDestroyInstance"
                    );

            long enumeratePhysicalDevicesFunction =
                    resolveInstanceFunction(
                            instanceHandle,
                            "vkEnumeratePhysicalDevices"
                    );

            long getPhysicalDevicePropertiesFunction =
                    resolveInstanceFunction(
                            instanceHandle,
                            "vkGetPhysicalDeviceProperties"
                    );

            long getPhysicalDeviceMemoryPropertiesFunction =
                    resolveInstanceFunction(
                            instanceHandle,
                            "vkGetPhysicalDeviceMemoryProperties"
                    );

            synchronized (LOCK) {
                rawFunctionResolutionComplete = true;
                captureStage =
                        "ENUMERATING_PHYSICAL_DEVICES";
            }

            countBuffer =
                    memCallocInt(1);

            int result =
                    callPPPI(
                            instanceHandle,
                            memAddress(countBuffer),
                            NULL,
                            enumeratePhysicalDevicesFunction
                    );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "raw vkEnumeratePhysicalDevices(count) failed with VkResult "
                                + result
                );
            }

            int count =
                    countBuffer.get(0);

            if (count <= 0) {
                throw new IllegalStateException(
                        "No Vulkan physical devices were reported."
                );
            }

            devices =
                    memAllocPointer(count);

            countBuffer.put(
                    0,
                    count
            );

            result =
                    callPPPI(
                            instanceHandle,
                            memAddress(countBuffer),
                            memAddress(devices),
                            enumeratePhysicalDevicesFunction
                    );

            if (result != VK_SUCCESS
                    && result != VK_INCOMPLETE) {
                throw new IllegalStateException(
                        "raw vkEnumeratePhysicalDevices(list) failed with VkResult "
                                + result
                );
            }

            int returnedCount =
                    Math.min(
                            count,
                            countBuffer.get(0)
                    );

            synchronized (LOCK) {
                deviceCount = returnedCount;
                captureStage =
                        "SCORING_PHYSICAL_DEVICES";
            }

            int bestIndex = -1;
            String bestName = "";
            long bestScore = Long.MIN_VALUE;
            int bestLimit = 0;

            for (int index = 0;
                 index < returnedCount;
                 index++) {

                long physicalDeviceHandle =
                        devices.get(index);

                if (physicalDeviceHandle == NULL) {
                    continue;
                }

                VkPhysicalDeviceProperties properties =
                        VkPhysicalDeviceProperties.malloc();

                VkPhysicalDeviceMemoryProperties memory =
                        VkPhysicalDeviceMemoryProperties.malloc();

                try {
                    callPPV(
                            physicalDeviceHandle,
                            properties.address(),
                            getPhysicalDevicePropertiesFunction
                    );

                    callPPV(
                            physicalDeviceHandle,
                            memory.address(),
                            getPhysicalDeviceMemoryPropertiesFunction
                    );

                    long deviceLocalBytes = 0L;

                    for (int heapIndex = 0;
                         heapIndex
                                 < memory.memoryHeapCount();
                         heapIndex++) {

                        VkMemoryHeap heap =
                                memory.memoryHeaps(
                                        heapIndex
                                );

                        if ((heap.flags()
                                & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT)
                                != 0) {
                            deviceLocalBytes +=
                                    heap.size();
                        }
                    }

                    String name =
                            properties.deviceNameString();

                    boolean translationLayer =
                            isSuspectedTranslationLayer(
                                    name
                            );

                    long score =
                            score(
                                    properties.deviceType(),
                                    deviceLocalBytes,
                                    translationLayer
                            );

                    int maxImageDimension2D =
                            properties.limits()
                                    .maxImageDimension2D();

                    if (score > bestScore) {
                        bestIndex = index;
                        bestName = name;
                        bestScore = score;
                        bestLimit =
                                maxImageDimension2D;
                    }
                } finally {
                    memory.free();
                    properties.free();
                }
            }

            if (bestIndex < 0
                    || bestLimit <= 0) {
                throw new IllegalStateException(
                        "No usable Vulkan physical-device limit snapshot was selected."
                );
            }

            synchronized (LOCK) {
                selectedDeviceIndex =
                        bestIndex;
                selectedDeviceName =
                        bestName;
                selectedDeviceScore =
                        bestScore;
                selectedMaxImageDimension2D =
                        bestLimit;
                success = true;
                captureStage =
                        "COMPLETE";
            }

            PotatoRuntime.LOGGER.info(
                    "[Potato/Vulkan] Early raw-handle device limit snapshot: {} | maxImageDimension2D={}.",
                    bestName,
                    bestLimit
            );
        } catch (Throwable throwable) {
            synchronized (LOCK) {
                failureStage = captureStage;
                captureStage = "FAILED";
                error =
                        throwable.getClass().getName()
                                + ": "
                                + String.valueOf(
                                        throwable.getMessage()
                                );
                success = false;
            }

            PotatoRuntime.LOGGER.warn(
                    "[Potato/Vulkan] Early raw-handle physical-device limit snapshot failed; OpenGL baseline fallback remains available.",
                    throwable
            );
        } finally {
            if (devices != null) {
                memFree(devices);
            }

            if (countBuffer != null) {
                memFree(countBuffer);
            }

            if (createInfo != null) {
                createInfo.free();
            }

            if (instanceHandle != NULL) {
                try {
                    if (destroyInstanceFunction == NULL) {
                        destroyInstanceFunction =
                                resolveInstanceFunction(
                                        instanceHandle,
                                        "vkDestroyInstance"
                                );
                    }

                    callPPV(
                            instanceHandle,
                            NULL,
                            destroyInstanceFunction
                    );

                    synchronized (LOCK) {
                        instanceDestroyed = true;
                    }
                } catch (Throwable throwable) {
                    synchronized (LOCK) {
                        error =
                                error
                                        + " | instanceDestroy="
                                        + throwable;
                    }
                }
            }
        }
    }

    private static long resolveInstanceFunction(
            long instanceHandle,
            String name
    ) {
        ByteBuffer encodedName =
                memUTF8(
                        name
                );

        try {
            long address =
                    nvkGetInstanceProcAddr(
                            instanceHandle,
                            memAddress(
                                    encodedName
                            )
                    );

            if (address == NULL) {
                throw new IllegalStateException(
                        "vkGetInstanceProcAddr returned NULL for "
                                + name
                );
            }

            return address;
        } finally {
            memFree(
                    encodedName
            );
        }
    }

    static boolean destroyRawInstanceHandle(
            long instanceHandle
    ) {
        if (instanceHandle == NULL) {
            return true;
        }

        try {
            long destroyInstance =
                    resolveInstanceFunction(
                            instanceHandle,
                            "vkDestroyInstance"
                    );

            callPPV(
                    instanceHandle,
                    NULL,
                    destroyInstance
            );

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean available() {
        synchronized (LOCK) {
            return success
                    && selectedMaxImageDimension2D > 0;
        }
    }

    public static int maxImageDimension2D() {
        synchronized (LOCK) {
            return selectedMaxImageDimension2D;
        }
    }

    public static String selectedDeviceName() {
        synchronized (LOCK) {
            return selectedDeviceName;
        }
    }

    public static void enrich(
            JsonObject report
    ) {
        synchronized (LOCK) {
            report.addProperty(
                    "earlyVulkanLimitsAttempted",
                    attempted
            );
            report.addProperty(
                    "earlyVulkanLimitsAvailable",
                    available()
            );
            report.addProperty(
                    "earlyVulkanLimitsDeviceCount",
                    deviceCount
            );
            report.addProperty(
                    "earlyVulkanLimitsSelectedDeviceIndex",
                    selectedDeviceIndex
            );
            report.addProperty(
                    "earlyVulkanLimitsSelectedDeviceName",
                    selectedDeviceName
            );
            report.addProperty(
                    "earlyVulkanLimitsSelectedDeviceScore",
                    selectedDeviceScore
            );
            report.addProperty(
                    "earlyVulkanMaxImageDimension2D",
                    selectedMaxImageDimension2D
            );
            report.addProperty(
                    "earlyVulkanLimitsInstanceDestroyed",
                    instanceDestroyed
            );
            report.addProperty(
                    "earlyVulkanLimitsContextPreserved",
                    contextPreserved
            );
            report.addProperty(
                    "earlyVulkanLimitsContextBeforeNonZero",
                    contextBefore != NULL
            );
            report.addProperty(
                    "earlyVulkanLimitsContextAfterNonZero",
                    contextAfter != NULL
            );
            report.addProperty(
                    "earlyVulkanLimitsIsolatedWorkerUsed",
                    isolatedWorkerUsed
            );
            report.addProperty(
                    "earlyVulkanLimitsIsolatedWorkerStarted",
                    isolatedWorkerStarted
            );
            report.addProperty(
                    "earlyVulkanLimitsIsolatedWorkerJoined",
                    isolatedWorkerJoined
            );
            report.addProperty(
                    "earlyVulkanLimitsCallerInterruptedWhileJoining",
                    callerInterruptedWhileJoining
            );
            report.addProperty(
                    "earlyVulkanLimitsIsolatedWorkerThreadName",
                    isolatedWorkerThreadName
            );
            report.addProperty(
                    "earlyVulkanLimitsCaptureStage",
                    captureStage
            );
            report.addProperty(
                    "earlyVulkanLimitsRawHandleProbeUsed",
                    rawHandleProbeUsed
            );
            report.addProperty(
                    "earlyVulkanLimitsRawInstanceCreated",
                    rawInstanceCreated
            );
            report.addProperty(
                    "earlyVulkanLimitsRawFunctionResolutionComplete",
                    rawFunctionResolutionComplete
            );
            report.addProperty(
                    "earlyVulkanLimitsLwjglInstanceWrapperBypassed",
                    lwjglInstanceWrapperBypassed
            );

            if (!failureStage.isBlank()) {
                report.addProperty(
                        "earlyVulkanLimitsFailureStage",
                        failureStage
                );
            }

            if (!error.isBlank()) {
                report.addProperty(
                        "earlyVulkanLimitsError",
                        error
                );
            }
        }
    }

    private static long score(
            int deviceType,
            long deviceLocalBytes,
            boolean translationLayer
    ) {
        long base = switch (deviceType) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ->
                    SCORE_DISCRETE_GPU;
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ->
                    SCORE_INTEGRATED_GPU;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU ->
                    SCORE_VIRTUAL_GPU;
            case VK_PHYSICAL_DEVICE_TYPE_CPU ->
                    SCORE_CPU;
            default ->
                    SCORE_OTHER;
        };

        long memoryMiB =
                deviceLocalBytes
                        / (1024L * 1024L);

        long memoryScore =
                Math.min(
                        memoryMiB,
                        MAX_MEMORY_SCORE_MIB
                );

        return base
                + memoryScore
                - (translationLayer
                ? TRANSLATION_LAYER_PENALTY
                : 0L);
    }

    private static boolean isSuspectedTranslationLayer(
            String deviceName
    ) {
        String normalized =
                deviceName.toLowerCase(
                        Locale.ROOT
                );

        return normalized.startsWith(
                "microsoft direct3d12"
        )
                || normalized.contains(
                "basic render driver"
        )
                || normalized.contains(
                "llvmpipe"
        )
                || normalized.contains(
                "swiftshader"
        );
    }
}