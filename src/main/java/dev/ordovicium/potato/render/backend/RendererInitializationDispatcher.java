package dev.ordovicium.potato.render.backend;

import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlDebug;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ordovicium.potato.mixin.client.accessor.GLXAccessor;
import dev.ordovicium.potato.mixin.client.accessor.RenderSystemAccessor;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

import java.util.Locale;

/**
 * Owns the high-level renderer-initialization responsibility at Potato's
 * backend boundary.
 *
 * <p>Patch 021 installs only the OpenGL transition branch. The important change
 * is ownership: Minecraft no longer invokes RenderSystem.initRenderer. Potato
 * performs the known responsibilities explicitly, so a Vulkan branch can later
 * replace them as one coherent backend operation.</p>
 */
public final class RendererInitializationDispatcher {
    private static final Object LOCK =
            new Object();

    private static int dispatchCount;
    private static boolean dispatchReturnedNormally;

    private static boolean cpuInfoAttempted;
    private static boolean cpuInfoInstalled;
    private static String cpuInfoValue = "";
    private static String cpuInfoError = "";

    private static boolean openGlDebugBridgeUsed;
    private static boolean apiDescriptionInstalled;
    private static String apiDescriptionValue = "";

    private RendererInitializationDispatcher() {
    }

    public static void initializeOpenGlTransition(
            int debugVerbosity,
            boolean synchronousDebug
    ) {
        synchronized (LOCK) {
            dispatchCount++;
        }

        boolean returnedNormally = false;

        try {
            installCpuInfo();

            /*
             * This is explicitly backend-specific. It remains only because the
             * downstream Minecraft renderer is still OpenGL in Patch 021.
             */
            GlDebug.enableDebugCallback(
                    debugVerbosity,
                    synchronousDebug
            );

            synchronized (LOCK) {
                openGlDebugBridgeUsed = true;
            }

            String apiDescription =
                    GLX.getOpenGLVersionString();

            RenderSystemAccessor
                    .potato$setApiDescription(
                            apiDescription
                    );

            synchronized (LOCK) {
                apiDescriptionValue =
                        apiDescription == null
                                ? ""
                                : apiDescription;

                apiDescriptionInstalled =
                        !apiDescriptionValue.isBlank()
                                && apiDescriptionValue.equals(
                                RenderSystem.getApiDescription()
                        );
            }

            returnedNormally = true;
        } finally {
            synchronized (LOCK) {
                dispatchReturnedNormally =
                        returnedNormally;
            }
        }
    }

    private static void installCpuInfo() {
        synchronized (LOCK) {
            cpuInfoAttempted = true;
        }

        try {
            CentralProcessor processor =
                    new SystemInfo()
                            .getHardware()
                            .getProcessor();

            String value =
                    String.format(
                                    Locale.ROOT,
                                    "%dx %s",
                                    processor.getLogicalProcessorCount(),
                                    processor.getProcessorIdentifier()
                                            .getName()
                            )
                            .replaceAll(
                                    "\\s+",
                                    " "
                            );

            GLXAccessor.potato$setCpuInfo(value);

            String installed =
                    GLXAccessor.potato$getCpuInfo();

            synchronized (LOCK) {
                cpuInfoValue =
                        installed == null
                                ? ""
                                : installed;

                cpuInfoInstalled =
                        value.equals(installed);
            }
        } catch (Throwable throwable) {
            /*
             * Vanilla GLX._init deliberately swallows CPU-info failures.
             * Preserve that exact non-fatal contract.
             */
            synchronized (LOCK) {
                cpuInfoError =
                        throwable.getClass().getName()
                                + ": "
                                + String.valueOf(
                                        throwable.getMessage()
                                );
            }
        }
    }

    public static int dispatchCount() {
        synchronized (LOCK) {
            return dispatchCount;
        }
    }

    public static boolean dispatchReturnedNormally() {
        synchronized (LOCK) {
            return dispatchReturnedNormally;
        }
    }

    public static boolean cpuInfoAttempted() {
        synchronized (LOCK) {
            return cpuInfoAttempted;
        }
    }

    public static boolean cpuInfoInstalled() {
        synchronized (LOCK) {
            return cpuInfoInstalled;
        }
    }

    public static String cpuInfoValue() {
        synchronized (LOCK) {
            return cpuInfoValue;
        }
    }

    public static String cpuInfoError() {
        synchronized (LOCK) {
            return cpuInfoError;
        }
    }

    public static boolean openGlDebugBridgeUsed() {
        synchronized (LOCK) {
            return openGlDebugBridgeUsed;
        }
    }

    public static boolean apiDescriptionInstalled() {
        synchronized (LOCK) {
            return apiDescriptionInstalled;
        }
    }

    public static String apiDescriptionValue() {
        synchronized (LOCK) {
            return apiDescriptionValue;
        }
    }
}