package dev.ordovicium.potato.render.backend.target;

import com.google.gson.JsonObject;

/**
 * Global diagnostic view of the currently observed Minecraft main render
 * target.
 *
 * <p>The target instance owns its state. This class retains only the state
 * object required for bootstrap verification; it does not own the Minecraft
 * RenderTarget itself.</p>
 */
public final class RenderTargetOwnershipDiagnostics {
    private static volatile RenderTargetBackendState mainTargetState;

    private static volatile int renderTargetInstancesObserved;
    private static volatile int mainTargetInstancesObserved;

    private RenderTargetOwnershipDiagnostics() {
    }

    public static void observeRenderTargetInstance() {
        renderTargetInstancesObserved++;
    }

    public static void registerMainTarget(
            RenderTargetBackendState state
    ) {
        mainTargetState = state;
        mainTargetInstancesObserved++;
    }

    public static boolean mainTargetOwnershipVerified() {
        RenderTargetBackendState state =
                mainTargetState;

        return state != null
                && mainTargetInstancesObserved == 1
                && state.initialMainOwnershipVerified();
    }

    public static int mainTargetWidth() {
        RenderTargetBackendState state =
                mainTargetState;

        return state == null
                ? 0
                : state.width();
    }

    public static int mainTargetHeight() {
        RenderTargetBackendState state =
                mainTargetState;

        return state == null
                ? 0
                : state.height();
    }

    public static boolean mainTargetUsesDepth() {
        RenderTargetBackendState state =
                mainTargetState;

        return state != null
                && state.useDepth();
    }

    public static void enrich(
            JsonObject report
    ) {
        report.addProperty(
                "renderTargetOwnershipModelInstalled",
                true
        );
        report.addProperty(
                "renderTargetInstancesObserved",
                renderTargetInstancesObserved
        );
        report.addProperty(
                "mainTargetInstancesObserved",
                mainTargetInstancesObserved
        );
        report.addProperty(
                "mainTargetStateAvailable",
                mainTargetState != null
        );

        RenderTargetBackendState state =
                mainTargetState;

        if (state != null) {
            state.enrich(
                    report,
                    "mainTarget"
            );
        }

        report.addProperty(
                "mainRenderTargetOwnershipVerified",
                mainTargetOwnershipVerified()
        );

        report.addProperty(
                "mainRenderTargetActualVulkanOwnership",
                false
        );
        report.addProperty(
                "mainRenderTargetOpenGlTransitionActive",
                true
        );
        report.addProperty(
                "renderTargetBackendStatePerInstance",
                true
        );
        report.addProperty(
                "renderTargetOpenGlIdsTreatedAsBackendSpecific",
                true
        );
    }
}