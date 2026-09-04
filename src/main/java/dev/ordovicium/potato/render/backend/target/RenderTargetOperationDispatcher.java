package dev.ordovicium.potato.render.backend.target;

import dev.ordovicium.potato.render.backend.RuntimePerformancePolicy;

/**
 * RenderTarget operation seam.
 *
 * <p>Normal Potato runtime mirrors only rare resize events while OpenGL remains
 * authoritative. The old full hidden-frame mirror can be re-enabled explicitly
 * for development with -Dpotato.dev.hiddenFrameMirror=true.</p>
 */
public final class RenderTargetOperationDispatcher {
    private static final Object LOCK =
            new Object();

    private static volatile RenderTargetOperationSink sink;

    private RenderTargetOperationDispatcher() {
    }

    public static void install(
            RenderTargetOperationSink newSink
    ) {
        if (newSink == null) {
            throw new IllegalArgumentException(
                    "RenderTargetOperationSink must not be null."
            );
        }

        synchronized (LOCK) {
            if (sink != null) {
                throw new IllegalStateException(
                        "A RenderTarget operation sink is already installed."
                );
            }

            sink = newSink;
        }
    }

    public static void uninstall(
            RenderTargetOperationSink expectedSink
    ) {
        synchronized (LOCK) {
            if (sink == expectedSink) {
                sink = null;
            }
        }
    }

    public static boolean active() {
        return sink != null;
    }

    public static boolean hiddenFrameMirrorEnabled() {
        return RuntimePerformancePolicy
                .hiddenFrameMirrorEnabled();
    }

    public static void setClearColor(
            RenderTargetBackendState state,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        if (!hiddenFrameMirrorEnabled()) {
            return;
        }

        RenderTargetOperationSink current =
                sinkForMain(state);

        if (current != null) {
            current.onSetClearColor(
                    state.width(),
                    state.height(),
                    red,
                    green,
                    blue,
                    alpha
            );
        }
    }

    public static void resize(
            RenderTargetBackendState state,
            int width,
            int height
    ) {
        RenderTargetOperationSink current =
                sinkForMain(state);

        if (current != null) {
            current.onResize(
                    width,
                    height,
                    state.useDepth()
            );
        }
    }

    public static void bindRead(
            RenderTargetBackendState state
    ) {
        if (!hiddenFrameMirrorEnabled()) {
            return;
        }

        RenderTargetOperationSink current =
                sinkForMain(state);

        if (current != null) {
            current.onBindRead(
                    state.width(),
                    state.height()
            );
        }
    }

    public static void unbindRead(
            RenderTargetBackendState state
    ) {
        if (!hiddenFrameMirrorEnabled()) {
            return;
        }

        RenderTargetOperationSink current =
                sinkForMain(state);

        if (current != null) {
            current.onUnbindRead(
                    state.width(),
                    state.height()
            );
        }
    }

    public static void bindWrite(
            RenderTargetBackendState state,
            boolean updateViewport
    ) {
        if (!hiddenFrameMirrorEnabled()) {
            return;
        }

        RenderTargetOperationSink current =
                sinkForMain(state);

        if (current != null) {
            current.onBindWrite(
                    state.width(),
                    state.height(),
                    updateViewport
            );
        }
    }

    public static void clear(
            RenderTargetBackendState state,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        if (!hiddenFrameMirrorEnabled()) {
            return;
        }

        RenderTargetOperationSink current =
                sinkForMain(state);

        if (current != null) {
            current.onClear(
                    state.width(),
                    state.height(),
                    state.useDepth(),
                    red,
                    green,
                    blue,
                    alpha
            );
        }
    }

    public static void blitToScreen(
            RenderTargetBackendState state,
            int destinationWidth,
            int destinationHeight,
            boolean disableBlend
    ) {
        if (!hiddenFrameMirrorEnabled()) {
            return;
        }

        RenderTargetOperationSink current =
                sinkForMain(state);

        if (current != null) {
            current.onBlitToScreen(
                    state.width(),
                    state.height(),
                    destinationWidth,
                    destinationHeight,
                    disableBlend
            );
        }
    }

    public static void copyDepth(
            RenderTargetBackendState state
    ) {
        if (!hiddenFrameMirrorEnabled()) {
            return;
        }

        RenderTargetOperationSink current =
                sinkForMain(state);

        if (current != null) {
            current.onCopyDepth(
                    state.width(),
                    state.height()
            );
        }
    }

    private static RenderTargetOperationSink sinkForMain(
            RenderTargetBackendState state
    ) {
        if (state == null
                || state.role()
                != RenderTargetRole.MAIN) {
            return null;
        }

        return sink;
    }
}
