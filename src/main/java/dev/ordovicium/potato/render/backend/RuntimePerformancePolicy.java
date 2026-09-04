package dev.ordovicium.potato.render.backend;

/**
 * Runtime policy separating research instrumentation from the normal hot path.
 *
 * <p>The first release path is fast by default. Expensive hidden OpenGL ->
 * Vulkan frame mirroring may be re-enabled explicitly for development with
 * {@code -Dpotato.dev.hiddenFrameMirror=true}.</p>
 */
public final class RuntimePerformancePolicy {
    private RuntimePerformancePolicy() {
    }

    public static boolean hiddenFrameMirrorEnabled() {
        return Boolean.getBoolean(
                "potato.dev.hiddenFrameMirror"
        );
    }

    public static boolean deepDrawDiagnosticsEnabled() {
        return Boolean.getBoolean(
                "potato.dev.deepDrawDiagnostics"
        );
    }

    public static boolean releaseFastPathEnabled() {
        return !hiddenFrameMirrorEnabled()
                && !deepDrawDiagnosticsEnabled();
    }
}
