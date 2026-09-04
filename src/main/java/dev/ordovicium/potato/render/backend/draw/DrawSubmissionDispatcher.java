package dev.ordovicium.potato.render.backend.draw;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.MeshData;
import dev.ordovicium.potato.PotatoRuntime;
import dev.ordovicium.potato.render.backend.RuntimePerformancePolicy;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/**
 * Lock-free Render-thread routing seam for Potato's production draw backend.
 *
 * <p>Install/uninstall/failure transitions use one monitor. Normal draw
 * dispatch is a volatile sink read plus cheap preflight.</p>
 */
public final class DrawSubmissionDispatcher {
    private static final Object LOCK =
            new Object();

    private static volatile DrawSubmissionSink sink;

    private static long failureCount;
    private static volatile boolean disabledAfterFailure;

    private static String lastFailure =
            "";

    private DrawSubmissionDispatcher() {
    }

    public static void install(
            DrawSubmissionSink newSink
    ) {
        synchronized (LOCK) {
            if (newSink == null) {
                throw new IllegalArgumentException(
                        "DrawSubmissionSink cannot be null."
                );
            }

            if (sink != null) {
                throw new IllegalStateException(
                        "A draw-submission sink is already installed."
                );
            }

            sink = newSink;
            disabledAfterFailure = false;
            lastFailure = "";
        }
    }

    public static void uninstall(
            DrawSubmissionSink expected
    ) {
        synchronized (LOCK) {
            if (sink == expected) {
                sink = null;
            }
        }
    }

    public static boolean active() {
        return sink != null
                && !disabledAfterFailure;
    }

    public static boolean performanceFastPathEnabled() {
        return RuntimePerformancePolicy
                .releaseFastPathEnabled();
    }

    public static boolean wantsNewStaticBlockUpload() {
        DrawSubmissionSink current =
                current();

        if (current == null) {
            return false;
        }

        try {
            return current.wantsNewStaticBlockUpload();
        } catch (Throwable throwable) {
            disable(
                    current,
                    throwable
            );

            return false;
        }
    }

    public static boolean wantsUpload(
            DrawBufferBackendState state
    ) {
        DrawSubmissionSink current =
                current();

        return current != null
                && safeWants(
                        current,
                        state,
                        WantKind.UPLOAD
                );
    }

    public static boolean wantsSectionLayerFrame(
            RenderType renderType
    ) {
        DrawSubmissionSink current =
                current();

        if (current == null) {
            return false;
        }

        try {
            return current.wantsSectionLayerFrame(
                    renderType
            );
        } catch (Throwable throwable) {
            disable(
                    current,
                    throwable
            );

            return false;
        }
    }

    public static boolean wantsSectionLayerDraw(
            DrawBufferBackendState state
    ) {
        DrawSubmissionSink current =
                current();

        return current != null
                && safeWants(
                        current,
                        state,
                        WantKind.SECTION_LAYER
                );
    }

    public static void bufferCreated(
            DrawBufferBackendState state
    ) {
        DrawSubmissionSink current =
                current();

        if (current == null
                || !safeWants(
                current,
                state,
                WantKind.BUFFER_CREATED
        )) {
            return;
        }

        invoke(
                current,
                target ->
                        target.onBufferCreated(
                                state
                        )
        );
    }

    public static void upload(
            DrawBufferBackendState state,
            MeshData meshData
    ) {
        DrawSubmissionSink current =
                current();

        if (current == null
                || !safeWants(
                current,
                state,
                WantKind.UPLOAD
        )) {
            return;
        }

        DrawGeometryView geometry =
                DrawGeometryView.from(
                        meshData
                );

        state.observeUpload(
                geometry
        );

        invoke(
                current,
                target ->
                        target.onUpload(
                                state,
                                geometry
                        )
        );
    }

    public static void shaderDraw(
            DrawBufferBackendState state,
            Matrix4f modelView,
            Matrix4f projection,
            ShaderInstance shader
    ) {
        DrawSubmissionSink current =
                current();

        if (current == null
                || !safeWants(
                current,
                state,
                WantKind.SHADER_DRAW
        )) {
            return;
        }

        DrawShaderContext context =
                DrawShaderContext.snapshot(
                        modelView,
                        projection,
                        shader
                );

        invoke(
                current,
                target ->
                        target.onShaderDraw(
                                state,
                                context
                        )
        );
    }

    public static void plainDraw(
            DrawBufferBackendState state
    ) {
        DrawSubmissionSink current =
                current();

        if (current == null
                || !safeWants(
                current,
                state,
                WantKind.PLAIN_DRAW
        )) {
            return;
        }

        invoke(
                current,
                target ->
                        target.onPlainDraw(
                                state
                        )
        );
    }

    public static void sectionLayerBegin(
            SectionLayerFrameContext context
    ) {
        DrawSubmissionSink current =
                current();

        if (current == null) {
            return;
        }

        invoke(
                current,
                target ->
                        target.onSectionLayerBegin(
                                context
                        )
        );
    }

    public static void sectionLayerDrawFast(
            DrawBufferBackendState state,
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
        DrawSubmissionSink current =
                current();

        if (current == null
                || !safeWants(
                current,
                state,
                WantKind.SECTION_LAYER
        )) {
            return;
        }

        invoke(
                current,
                target ->
                        target.onSectionLayerDrawFast(
                                state,
                                chunkOffsetX,
                                chunkOffsetY,
                                chunkOffsetZ
                        )
        );
    }

    public static void sectionLayerEnd() {
        DrawSubmissionSink current =
                current();

        if (current == null) {
            return;
        }

        invoke(
                current,
                DrawSubmissionSink::onSectionLayerEnd
        );
    }

    public static void sectionLayerDraw(
            DrawBufferBackendState state,
            SectionLayerDrawContext context
    ) {
        DrawSubmissionSink current =
                current();

        if (current == null
                || !safeWants(
                current,
                state,
                WantKind.SECTION_LAYER
        )) {
            return;
        }

        invoke(
                current,
                target ->
                        target.onSectionLayerDraw(
                                state,
                                context
                        )
        );
    }

    public static void close(
            DrawBufferBackendState state
    ) {
        DrawSubmissionSink current =
                current();

        if (current == null
                || !safeWants(
                current,
                state,
                WantKind.CLOSE
        )) {
            return;
        }

        state.observeClose();

        invoke(
                current,
                target ->
                        target.onClose(
                                state
                        )
        );
    }

    public static void enrich(
            JsonObject report
    ) {
        if (report == null) {
            return;
        }

        synchronized (LOCK) {
            report.addProperty(
                    "drawSubmissionDispatcherActive",
                    sink != null
                            && !disabledAfterFailure
            );
            report.addProperty(
                    "drawSubmissionDispatcherFailureCount",
                    failureCount
            );
            report.addProperty(
                    "drawSubmissionDispatcherDisabledAfterFailure",
                    disabledAfterFailure
            );
            report.addProperty(
                    "drawSubmissionRuntimeFastPathEnabled",
                    performanceFastPathEnabled()
            );
            report.addProperty(
                    "drawSubmissionUploadPreflightEnabled",
                    true
            );
            report.addProperty(
                    "drawSubmissionSectionLayerPreflightEnabled",
                    true
            );
            report.addProperty(
                    "drawSubmissionLayerFrameContextEnabled",
                    true
            );
            report.addProperty(
                    "drawSubmissionPerDrawMatrixSnapshotEnabled",
                    false
            );
            report.addProperty(
                    "drawSubmissionGlobalShaderDrawCaptureEnabled",
                    false
            );
            report.addProperty(
                    "drawSubmissionPlainDrawCaptureEnabled",
                    false
            );
            report.addProperty(
                    "drawSubmissionPerEventGlobalLockUsed",
                    false
            );

            if (!lastFailure.isBlank()) {
                report.addProperty(
                        "drawSubmissionDispatcherLastFailure",
                        lastFailure
                );
            }
        }
    }

    private static DrawSubmissionSink current() {
        if (disabledAfterFailure) {
            return null;
        }

        return sink;
    }

    private static boolean safeWants(
            DrawSubmissionSink current,
            DrawBufferBackendState state,
            WantKind kind
    ) {
        try {
            return switch (kind) {
                case BUFFER_CREATED ->
                        current.wantsBufferCreated(state);
                case UPLOAD ->
                        current.wantsUpload(state);
                case SHADER_DRAW ->
                        current.wantsShaderDraw(state);
                case PLAIN_DRAW ->
                        current.wantsPlainDraw(state);
                case SECTION_LAYER ->
                        current.wantsSectionLayerDraw(state);
                case CLOSE ->
                        current.wantsClose(state);
            };
        } catch (Throwable throwable) {
            disable(
                    current,
                    throwable
            );

            return false;
        }
    }

    private static void invoke(
            DrawSubmissionSink current,
            SinkOperation operation
    ) {
        try {
            operation.accept(
                    current
            );
        } catch (Throwable throwable) {
            disable(
                    current,
                    throwable
            );
        }
    }

    private static void disable(
            DrawSubmissionSink current,
            Throwable throwable
    ) {
        synchronized (LOCK) {
            failureCount++;
            disabledAfterFailure = true;

            lastFailure =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            );

            if (sink == current) {
                sink = null;
            }
        }

        PotatoRuntime.LOGGER.error(
                "[Potato/Draw] Draw-submission bridge failed; subsystem disabled and OpenGL baseline preserved.",
                throwable
        );
    }

    private enum WantKind {
        BUFFER_CREATED,
        UPLOAD,
        SHADER_DRAW,
        PLAIN_DRAW,
        SECTION_LAYER,
        CLOSE
    }

    @FunctionalInterface
    private interface SinkOperation {
        void accept(
                DrawSubmissionSink sink
        );
    }
}