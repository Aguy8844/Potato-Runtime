package dev.ordovicium.potato.render.backend;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.glfw.GLFW.glfwGetCurrentContext;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Verifies Potato's first real high-level renderer initialization bypass.
 *
 * <p>Minecraft's RenderSystem.initRenderer call is redirected into
 * {@link RendererInitializationDispatcher}. Observer mixins independently count
 * any accidental calls to RenderSystem.initRenderer or GLX._init.</p>
 */
public final class RendererInitializationRehearsal {
    private static final Object LOCK =
            new Object();

    private static boolean intercepted;
    private static int baselineRenderSystemInitObservedCount;
    private static int baselineGlxInitObservedCount;

    private static int requestedDebugVerbosity;
    private static boolean requestedSynchronousDebug;

    private static String threadBefore = "";
    private static String threadAfter = "";

    private static long contextBefore;
    private static long contextAfter;

    private static boolean capabilitiesBeforePresent;
    private static boolean capabilitiesAfterPresent;
    private static int capabilitiesBeforeIdentity;
    private static int capabilitiesAfterIdentity;
    private static boolean capabilitiesSameInstance;

    private static String apiDescriptionBefore = "";
    private static String apiDescriptionAfter = "";
    private static boolean apiDescriptionPopulated;

    private RendererInitializationRehearsal() {
    }

    public static void executeDispatchedTransition(
            int debugVerbosity,
            boolean synchronousDebug
    ) {
        GLCapabilities beforeCapabilities =
                currentCapabilitiesOrNull();

        String beforeDescription =
                normalize(
                        RenderSystem.getApiDescription()
                );

        long beforeContext =
                glfwGetCurrentContext();

        synchronized (LOCK) {
            intercepted = true;

            requestedDebugVerbosity =
                    debugVerbosity;
            requestedSynchronousDebug =
                    synchronousDebug;

            threadBefore =
                    Thread.currentThread().getName();

            contextBefore =
                    beforeContext;

            capabilitiesBeforePresent =
                    beforeCapabilities != null;
            capabilitiesBeforeIdentity =
                    identityOf(beforeCapabilities);

            apiDescriptionBefore =
                    beforeDescription;
        }

        RendererInitializationDispatcher
                .initializeOpenGlTransition(
                        debugVerbosity,
                        synchronousDebug
                );

        GLCapabilities afterCapabilities =
                currentCapabilitiesOrNull();

        String afterDescription =
                normalize(
                        RenderSystem.getApiDescription()
                );

        long afterContext =
                glfwGetCurrentContext();

        synchronized (LOCK) {
            threadAfter =
                    Thread.currentThread().getName();

            contextAfter =
                    afterContext;

            capabilitiesAfterPresent =
                    afterCapabilities != null;
            capabilitiesAfterIdentity =
                    identityOf(afterCapabilities);

            capabilitiesSameInstance =
                    beforeCapabilities != null
                            && beforeCapabilities
                            == afterCapabilities;

            apiDescriptionAfter =
                    afterDescription;

            apiDescriptionPopulated =
                    !afterDescription.isBlank();
        }
    }

    public static void observeRenderSystemInitRendererCall() {
        synchronized (LOCK) {
            baselineRenderSystemInitObservedCount++;
        }
    }

    public static void observeGlxInitCall() {
        synchronized (LOCK) {
            baselineGlxInitObservedCount++;
        }
    }

    public static boolean dispatchVerified() {
        synchronized (LOCK) {
            return intercepted
                    && RendererInitializationDispatcher
                    .dispatchCount() == 1
                    && RendererInitializationDispatcher
                    .dispatchReturnedNormally()
                    && baselineRenderSystemInitObservedCount == 0
                    && baselineGlxInitObservedCount == 0
                    && contextBefore != NULL
                    && contextBefore == contextAfter
                    && capabilitiesBeforePresent
                    && capabilitiesAfterPresent
                    && capabilitiesSameInstance
                    && !threadBefore.isBlank()
                    && threadBefore.equals(threadAfter)
                    && RendererInitializationDispatcher
                    .cpuInfoAttempted()
                    && RendererInitializationDispatcher
                    .openGlDebugBridgeUsed()
                    && RendererInitializationDispatcher
                    .apiDescriptionInstalled()
                    && apiDescriptionPopulated;
        }
    }

    public static void enrich(
            JsonObject report
    ) {
        synchronized (LOCK) {
            report.addProperty(
                    "rendererInitIntercepted",
                    intercepted
            );

            report.addProperty(
                    "rendererInitDispatcherUsed",
                    RendererInitializationDispatcher
                            .dispatchCount() == 1
            );
            report.addProperty(
                    "rendererInitDispatcherCallCount",
                    RendererInitializationDispatcher
                            .dispatchCount()
            );
            report.addProperty(
                    "rendererInitDispatcherReturnedNormally",
                    RendererInitializationDispatcher
                            .dispatchReturnedNormally()
            );

            report.addProperty(
                    "rendererInitBaselineRenderSystemInitObservedCount",
                    baselineRenderSystemInitObservedCount
            );
            report.addProperty(
                    "rendererInitBaselineGlxInitObservedCount",
                    baselineGlxInitObservedCount
            );
            report.addProperty(
                    "rendererInitOriginalRenderSystemInitRendererCalled",
                    baselineRenderSystemInitObservedCount > 0
            );
            report.addProperty(
                    "rendererInitOriginalGlxInitCalled",
                    baselineGlxInitObservedCount > 0
            );

            report.addProperty(
                    "rendererInitRequestedDebugVerbosity",
                    requestedDebugVerbosity
            );
            report.addProperty(
                    "rendererInitRequestedSynchronousDebug",
                    requestedSynchronousDebug
            );

            report.addProperty(
                    "rendererInitThreadBefore",
                    threadBefore
            );
            report.addProperty(
                    "rendererInitThreadAfter",
                    threadAfter
            );
            report.addProperty(
                    "rendererInitThreadPreserved",
                    !threadBefore.isBlank()
                            && threadBefore.equals(threadAfter)
            );

            report.addProperty(
                    "rendererInitContextBeforeNonZero",
                    contextBefore != NULL
            );
            report.addProperty(
                    "rendererInitContextAfterNonZero",
                    contextAfter != NULL
            );
            report.addProperty(
                    "rendererInitContextPreservedExactly",
                    contextBefore != NULL
                            && contextBefore == contextAfter
            );

            report.addProperty(
                    "rendererInitCapabilitiesPresentBefore",
                    capabilitiesBeforePresent
            );
            report.addProperty(
                    "rendererInitCapabilitiesPresentAfter",
                    capabilitiesAfterPresent
            );
            report.addProperty(
                    "rendererInitCapabilitiesIdentityBefore",
                    capabilitiesBeforeIdentity
            );
            report.addProperty(
                    "rendererInitCapabilitiesIdentityAfter",
                    capabilitiesAfterIdentity
            );
            report.addProperty(
                    "rendererInitCapabilitiesSameInstance",
                    capabilitiesSameInstance
            );

            report.addProperty(
                    "rendererInitApiDescriptionBefore",
                    apiDescriptionBefore
            );
            report.addProperty(
                    "rendererInitApiDescriptionAfter",
                    apiDescriptionAfter
            );
            report.addProperty(
                    "rendererInitApiDescriptionPopulated",
                    apiDescriptionPopulated
            );
            report.addProperty(
                    "rendererInitApiDescriptionChanged",
                    !apiDescriptionBefore.equals(
                            apiDescriptionAfter
                    )
            );
            report.addProperty(
                    "rendererInitApiDescriptionSource",
                    "OPENGL_TRANSITION_METADATA"
            );

            report.addProperty(
                    "rendererInitCpuInfoAttempted",
                    RendererInitializationDispatcher
                            .cpuInfoAttempted()
            );
            report.addProperty(
                    "rendererInitCpuInfoInstalled",
                    RendererInitializationDispatcher
                            .cpuInfoInstalled()
            );
            report.addProperty(
                    "rendererInitCpuInfoValue",
                    RendererInitializationDispatcher
                            .cpuInfoValue()
            );

            String cpuError =
                    RendererInitializationDispatcher
                            .cpuInfoError();

            if (!cpuError.isBlank()) {
                report.addProperty(
                        "rendererInitCpuInfoError",
                        cpuError
                );
            }

            report.addProperty(
                    "rendererInitOpenGlDebugTransitionBridgeUsed",
                    RendererInitializationDispatcher
                            .openGlDebugBridgeUsed()
            );
            report.addProperty(
                    "rendererInitApiDescriptionInstalledByDispatcher",
                    RendererInitializationDispatcher
                            .apiDescriptionInstalled()
            );

            report.addProperty(
                    "rendererInitDispatchVerified",
                    dispatchVerified()
            );

            report.addProperty(
                    "rendererInitActuallyBypassed",
                    baselineRenderSystemInitObservedCount == 0
                            && baselineGlxInitObservedCount == 0
            );
            report.addProperty(
                    "rendererInitHighLevelOwnershipMovedToPotato",
                    true
            );

            report.addProperty(
                    "rendererInitOpenGlTransitionBridgeCount",
                    RendererInitializationDispatcher
                            .openGlDebugBridgeUsed()
                            ? 2
                            : 1
            );
            report.addProperty(
                    "rendererInitOpenGlTransitionBridges",
                    "GlDebug.enableDebugCallback, GLX.getOpenGLVersionString"
            );

            report.addProperty(
                    "rendererInitVulkanDispatchImplemented",
                    false
            );
            report.addProperty(
                    "rendererInitNoApiReady",
                    false
            );
        }
    }

    private static GLCapabilities currentCapabilitiesOrNull() {
        try {
            return GL.getCapabilities();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private static int identityOf(
            Object value
    ) {
        return value == null
                ? 0
                : System.identityHashCode(value);
    }

    private static String normalize(
            String value
    ) {
        return value == null
                ? ""
                : value;
    }
}