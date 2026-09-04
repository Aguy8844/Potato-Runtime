package dev.ordovicium.potato.render.backend.draw;

import com.google.gson.JsonObject;
import net.minecraft.client.renderer.ShaderInstance;

/**
 * Diagnostics-only consumer for the Patch 033 VertexBuffer lifecycle contract.
 *
 * <p>No geometry is retained and no GPU operation is performed.</p>
 */
public final class DrawSubmissionContractRehearsal
        implements DrawSubmissionSink {

    private final JsonObject report;

    private long bufferCreatedCount;
    private long uploadCount;
    private long shaderDrawCount;
    private long plainDrawCount;
    private long closeCount;

    private long sectionLayerDrawCount;
    private long sectionLayerShaderPresentCount;
    private long sectionLayerMatrixSnapshotCount;
    private long sectionLayerNonZeroChunkOffsetCount;
    private long sectionLayerStaticUsageCount;
    private long sectionLayerDynamicUsageCount;
    private long sectionLayerQuadCount;
    private long sectionLayerShaderFormatMatchCount;
    private long sectionLayerShaderFormatMismatchCount;
    private long sectionLayerRenderThreadCount;
    private long sectionLayerNonRenderThreadCount;

    private String lastSectionLayerRenderType =
            "";
    private String lastSectionLayerShader =
            "";
    private String lastSectionLayerFormat =
            "";
    private String lastSectionLayerMode =
            "";
    private String lastSectionLayerIndexType =
            "";

    private int lastSectionLayerVertexStrideBytes;
    private int lastSectionLayerVertexCount;
    private int lastSectionLayerIndexCount;

    private float lastSectionLayerChunkOffsetX;
    private float lastSectionLayerChunkOffsetY;
    private float lastSectionLayerChunkOffsetZ;

    private long uploadVertexBytesObserved;
    private long uploadIndexBytesObserved;

    private long explicitIndexUploadCount;
    private long sequentialIndexUploadCount;

    private long drawWithUploadCount;
    private long drawWithoutUploadCount;

    private long renderThreadEventCount;
    private long nonRenderThreadEventCount;

    private long matrixSnapshotCount;
    private long shaderPresentCount;
    private long shaderMissingCount;

    private boolean observedDynamicUsage;
    private boolean observedStaticUsage;

    private int maxVertexCount;
    private int maxIndexCount;
    private int maxVertexBytes;
    private int maxIndexBytes;

    private String firstEventThread =
            "";
    private String lastEventThread =
            "";

    private String lastUsage =
            "";
    private String lastFormat =
            "";
    private String lastMode =
            "";
    private String lastIndexType =
            "";
    private String lastShaderClass =
            "";
    private String lastShaderVertexFormat =
            "";

    private long lastUploadGeneration;
    private int lastVertexCount;
    private int lastIndexCount;
    private int lastVertexBytes;
    private int lastIndexBytes;
    private boolean lastExplicitIndexBuffer;

    public DrawSubmissionContractRehearsal(
            JsonObject report
    ) {
        this.report = report;
    }

    @Override
    public synchronized void onBufferCreated(
            DrawBufferBackendState state
    ) {
        bufferCreatedCount++;

        observeThread();
        observeUsage(
                state.usageName()
        );
    }

    @Override
    public synchronized void onUpload(
            DrawBufferBackendState state,
            DrawGeometryView geometry
    ) {
        uploadCount++;

        observeThread();
        observeUsage(
                state.usageName()
        );

        int vertexBytes =
                geometry.vertexBytes()
                        .remaining();

        int indexBytes =
                geometry.indexBytes() == null
                        ? 0
                        : geometry.indexBytes()
                                .remaining();

        uploadVertexBytesObserved +=
                Math.max(
                        0,
                        vertexBytes
                );

        uploadIndexBytesObserved +=
                Math.max(
                        0,
                        indexBytes
                );

        if (geometry.indexBytes() == null) {
            sequentialIndexUploadCount++;
        } else {
            explicitIndexUploadCount++;
        }

        maxVertexCount =
                Math.max(
                        maxVertexCount,
                        geometry.vertexCount()
                );

        maxIndexCount =
                Math.max(
                        maxIndexCount,
                        geometry.indexCount()
                );

        maxVertexBytes =
                Math.max(
                        maxVertexBytes,
                        vertexBytes
                );

        maxIndexBytes =
                Math.max(
                        maxIndexBytes,
                        indexBytes
                );

        lastUploadGeneration =
                state.uploadGeneration();

        lastVertexCount =
                geometry.vertexCount();

        lastIndexCount =
                geometry.indexCount();

        lastVertexBytes =
                vertexBytes;

        lastIndexBytes =
                indexBytes;

        lastExplicitIndexBuffer =
                geometry.indexBytes()
                        != null;

        lastFormat =
                String.valueOf(
                        geometry.format()
                );

        lastMode =
                geometry.mode()
                        .name();

        lastIndexType =
                geometry.indexType()
                        .name();
    }

    @Override
    public synchronized void onShaderDraw(
            DrawBufferBackendState state,
            DrawShaderContext context
    ) {
        shaderDrawCount++;

        observeThread();
        observeUsage(
                state.usageName()
        );

        observeDrawCorrelation(
                state
        );

        if (context.modelView() != null
                && context.projection() != null) {
            matrixSnapshotCount++;
        }

        ShaderInstance shader =
                context.shader();

        if (shader == null) {
            shaderMissingCount++;
            lastShaderClass = "";
            lastShaderVertexFormat = "";
        } else {
            shaderPresentCount++;

            lastShaderClass =
                    shader.getClass()
                            .getName();

            lastShaderVertexFormat =
                    String.valueOf(
                            shader.getVertexFormat()
                    );
        }
    }

    @Override
    public synchronized void onPlainDraw(
            DrawBufferBackendState state
    ) {
        plainDrawCount++;

        observeThread();
        observeUsage(
                state.usageName()
        );

        observeDrawCorrelation(
                state
        );
    }

    @Override
    public synchronized void onSectionLayerDraw(
            DrawBufferBackendState state,
            SectionLayerDrawContext context
    ) {
        sectionLayerDrawCount++;

        String thread =
                Thread.currentThread()
                        .getName();

        if ("Render thread".equals(thread)) {
            sectionLayerRenderThreadCount++;
        } else {
            sectionLayerNonRenderThreadCount++;
        }

        if (context.hasShader()) {
            sectionLayerShaderPresentCount++;

            lastSectionLayerShader =
                    context.shader()
                            .getClass()
                            .getName();

            if (state.format() != null
                    && context.shader()
                    .getVertexFormat()
                    .equals(state.format())) {
                sectionLayerShaderFormatMatchCount++;
            } else {
                sectionLayerShaderFormatMismatchCount++;
            }
        } else {
            lastSectionLayerShader = "";
        }

        if (context.hasMatrices()) {
            sectionLayerMatrixSnapshotCount++;
        }

        if (context.hasNonZeroChunkOffset()) {
            sectionLayerNonZeroChunkOffsetCount++;
        }

        if ("STATIC".equals(
                state.usageName()
        )) {
            sectionLayerStaticUsageCount++;
        }

        if ("DYNAMIC".equals(
                state.usageName()
        )) {
            sectionLayerDynamicUsageCount++;
        }

        if (state.mode()
                == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS) {
            sectionLayerQuadCount++;
        }

        lastSectionLayerRenderType =
                String.valueOf(
                        context.renderType()
                );

        lastSectionLayerFormat =
                state.formatDescription();

        lastSectionLayerMode =
                state.modeName();

        lastSectionLayerIndexType =
                state.indexTypeName();

        lastSectionLayerVertexStrideBytes =
                state.vertexStrideBytes();

        lastSectionLayerVertexCount =
                state.vertexCount();

        lastSectionLayerIndexCount =
                state.indexCount();

        lastSectionLayerChunkOffsetX =
                context.chunkOffsetX();

        lastSectionLayerChunkOffsetY =
                context.chunkOffsetY();

        lastSectionLayerChunkOffsetZ =
                context.chunkOffsetZ();
    }

    @Override
    public synchronized void onClose(
            DrawBufferBackendState state
    ) {
        closeCount++;

        observeThread();
        observeUsage(
                state.usageName()
        );
    }

    public synchronized boolean verified() {
        return uploadCount > 0
                && shaderDrawCount > 0
                && drawWithUploadCount > 0
                && uploadVertexBytesObserved > 0
                && shaderPresentCount > 0
                && matrixSnapshotCount > 0
                && renderThreadEventCount > 0
                && nonRenderThreadEventCount == 0;
    }

    public synchronized boolean sectionLayerContextVerified() {
        return sectionLayerDrawCount > 0
                && sectionLayerShaderPresentCount > 0
                && sectionLayerMatrixSnapshotCount > 0
                && sectionLayerNonZeroChunkOffsetCount > 0
                && sectionLayerStaticUsageCount > 0
                && sectionLayerQuadCount > 0
                && sectionLayerShaderFormatMatchCount > 0
                && sectionLayerNonRenderThreadCount == 0;
    }

    public synchronized void enrich() {
        report.addProperty(
                "drawSubmissionContractInstalled",
                true
        );
        report.addProperty(
                "drawSubmissionContractMode",
                "VERTEX_BUFFER_UPLOAD_DRAW_CLOSE_REHEARSAL"
        );

        report.addProperty(
                "drawSubmissionBufferCreatedCount",
                bufferCreatedCount
        );
        report.addProperty(
                "drawSubmissionUploadCount",
                uploadCount
        );
        report.addProperty(
                "drawSubmissionShaderDrawCount",
                shaderDrawCount
        );
        report.addProperty(
                "drawSubmissionPlainDrawCount",
                plainDrawCount
        );
        report.addProperty(
                "drawSubmissionCloseCount",
                closeCount
        );

        report.addProperty(
                "drawSubmissionUploadVertexBytesObserved",
                uploadVertexBytesObserved
        );
        report.addProperty(
                "drawSubmissionUploadIndexBytesObserved",
                uploadIndexBytesObserved
        );
        report.addProperty(
                "drawSubmissionExplicitIndexUploadCount",
                explicitIndexUploadCount
        );
        report.addProperty(
                "drawSubmissionSequentialIndexUploadCount",
                sequentialIndexUploadCount
        );

        report.addProperty(
                "drawSubmissionDrawWithUploadCount",
                drawWithUploadCount
        );
        report.addProperty(
                "drawSubmissionDrawWithoutUploadCount",
                drawWithoutUploadCount
        );

        report.addProperty(
                "drawSubmissionRenderThreadEventCount",
                renderThreadEventCount
        );
        report.addProperty(
                "drawSubmissionNonRenderThreadEventCount",
                nonRenderThreadEventCount
        );

        report.addProperty(
                "drawSubmissionMatrixSnapshotCount",
                matrixSnapshotCount
        );
        report.addProperty(
                "drawSubmissionShaderPresentCount",
                shaderPresentCount
        );
        report.addProperty(
                "drawSubmissionShaderMissingCount",
                shaderMissingCount
        );

        report.addProperty(
                "drawSubmissionObservedDynamicUsage",
                observedDynamicUsage
        );
        report.addProperty(
                "drawSubmissionObservedStaticUsage",
                observedStaticUsage
        );

        report.addProperty(
                "drawSubmissionMaxVertexCount",
                maxVertexCount
        );
        report.addProperty(
                "drawSubmissionMaxIndexCount",
                maxIndexCount
        );
        report.addProperty(
                "drawSubmissionMaxVertexBytes",
                maxVertexBytes
        );
        report.addProperty(
                "drawSubmissionMaxIndexBytes",
                maxIndexBytes
        );

        report.addProperty(
                "drawSubmissionFirstEventThread",
                firstEventThread
        );
        report.addProperty(
                "drawSubmissionLastEventThread",
                lastEventThread
        );

        report.addProperty(
                "drawSubmissionLastUsage",
                lastUsage
        );
        report.addProperty(
                "drawSubmissionLastFormat",
                lastFormat
        );
        report.addProperty(
                "drawSubmissionLastMode",
                lastMode
        );
        report.addProperty(
                "drawSubmissionLastIndexType",
                lastIndexType
        );
        report.addProperty(
                "drawSubmissionLastShaderClass",
                lastShaderClass
        );
        report.addProperty(
                "drawSubmissionLastShaderVertexFormat",
                lastShaderVertexFormat
        );

        report.addProperty(
                "drawSubmissionLastUploadGeneration",
                lastUploadGeneration
        );
        report.addProperty(
                "drawSubmissionLastVertexCount",
                lastVertexCount
        );
        report.addProperty(
                "drawSubmissionLastIndexCount",
                lastIndexCount
        );
        report.addProperty(
                "drawSubmissionLastVertexBytes",
                lastVertexBytes
        );
        report.addProperty(
                "drawSubmissionLastIndexBytes",
                lastIndexBytes
        );
        report.addProperty(
                "drawSubmissionLastExplicitIndexBuffer",
                lastExplicitIndexBuffer
        );

        report.addProperty(
                "drawSubmissionRawGeometryAvailableAtUpload",
                uploadCount > 0
                        && uploadVertexBytesObserved > 0
        );
        report.addProperty(
                "drawSubmissionRawGeometryRetainedAfterUpload",
                false
        );
        report.addProperty(
                "drawSubmissionUploadOccursBeforeMeshDataClose",
                true
        );
        report.addProperty(
                "drawSubmissionOpenGlStillExecutes",
                true
        );
        report.addProperty(
                "drawSubmissionVulkanGpuExecutionEnabled",
                false
        );
        report.addProperty(
                "drawSubmissionVertexBufferLifecycleBoundary",
                true
        );
        report.addProperty(
                "sectionLayerDrawContextObservedCount",
                sectionLayerDrawCount
        );
        report.addProperty(
                "sectionLayerDrawContextShaderPresentCount",
                sectionLayerShaderPresentCount
        );
        report.addProperty(
                "sectionLayerDrawContextMatrixSnapshotCount",
                sectionLayerMatrixSnapshotCount
        );
        report.addProperty(
                "sectionLayerDrawContextNonZeroChunkOffsetCount",
                sectionLayerNonZeroChunkOffsetCount
        );
        report.addProperty(
                "sectionLayerDrawContextStaticUsageCount",
                sectionLayerStaticUsageCount
        );
        report.addProperty(
                "sectionLayerDrawContextDynamicUsageCount",
                sectionLayerDynamicUsageCount
        );
        report.addProperty(
                "sectionLayerDrawContextQuadCount",
                sectionLayerQuadCount
        );
        report.addProperty(
                "sectionLayerDrawContextShaderFormatMatchCount",
                sectionLayerShaderFormatMatchCount
        );
        report.addProperty(
                "sectionLayerDrawContextShaderFormatMismatchCount",
                sectionLayerShaderFormatMismatchCount
        );
        report.addProperty(
                "sectionLayerDrawContextRenderThreadCount",
                sectionLayerRenderThreadCount
        );
        report.addProperty(
                "sectionLayerDrawContextNonRenderThreadCount",
                sectionLayerNonRenderThreadCount
        );

        report.addProperty(
                "sectionLayerDrawContextLastRenderType",
                lastSectionLayerRenderType
        );
        report.addProperty(
                "sectionLayerDrawContextLastShader",
                lastSectionLayerShader
        );
        report.addProperty(
                "sectionLayerDrawContextLastFormat",
                lastSectionLayerFormat
        );
        report.addProperty(
                "sectionLayerDrawContextLastMode",
                lastSectionLayerMode
        );
        report.addProperty(
                "sectionLayerDrawContextLastIndexType",
                lastSectionLayerIndexType
        );
        report.addProperty(
                "sectionLayerDrawContextLastVertexStrideBytes",
                lastSectionLayerVertexStrideBytes
        );
        report.addProperty(
                "sectionLayerDrawContextLastVertexCount",
                lastSectionLayerVertexCount
        );
        report.addProperty(
                "sectionLayerDrawContextLastIndexCount",
                lastSectionLayerIndexCount
        );
        report.addProperty(
                "sectionLayerDrawContextLastChunkOffsetX",
                lastSectionLayerChunkOffsetX
        );
        report.addProperty(
                "sectionLayerDrawContextLastChunkOffsetY",
                lastSectionLayerChunkOffsetY
        );
        report.addProperty(
                "sectionLayerDrawContextLastChunkOffsetZ",
                lastSectionLayerChunkOffsetZ
        );

        report.addProperty(
                "sectionLayerDrawContextCensusOwner",
                "LevelRenderer.renderSectionLayer"
        );
        report.addProperty(
                "sectionLayerDrawContextRenderTypeStateAlreadySetup",
                true
        );
        report.addProperty(
                "sectionLayerDrawContextShaderAppliedBeforeSectionLoop",
                true
        );
        report.addProperty(
                "sectionLayerDrawContextChunkOffsetIsPerSection",
                true
        );
        report.addProperty(
                "sectionLayerDrawContextTextureStateStillOpenGlTransition",
                true
        );
        report.addProperty(
                "sectionLayerDrawContextVulkanDrawExecutionEnabled",
                false
        );
        report.addProperty(
                "sectionLayerDrawContextVerified",
                sectionLayerContextVerified()
        );

        report.addProperty(
                "drawSubmissionContractVerified",
                verified()
        );
    }

    private void observeDrawCorrelation(
            DrawBufferBackendState state
    ) {
        if (state.uploaded()
                && state.uploadGeneration() > 0) {
            drawWithUploadCount++;
        } else {
            drawWithoutUploadCount++;
        }

        lastUploadGeneration =
                state.uploadGeneration();

        lastVertexCount =
                state.vertexCount();

        lastIndexCount =
                state.indexCount();

        lastVertexBytes =
                state.vertexBytes();

        lastIndexBytes =
                state.indexBytes();

        lastExplicitIndexBuffer =
                state.explicitIndexBuffer();

        lastFormat =
                state.formatDescription();

        lastMode =
                state.modeName();

        lastIndexType =
                state.indexTypeName();
    }

    private void observeUsage(
            String usage
    ) {
        lastUsage =
                usage == null
                        ? "UNKNOWN"
                        : usage;

        if ("DYNAMIC".equals(
                lastUsage
        )) {
            observedDynamicUsage = true;
        }

        if ("STATIC".equals(
                lastUsage
        )) {
            observedStaticUsage = true;
        }
    }

    private void observeThread() {
        String thread =
                Thread.currentThread()
                        .getName();

        if (firstEventThread.isBlank()) {
            firstEventThread =
                    thread;
        }

        lastEventThread =
                thread;

        if ("Render thread".equals(
                thread
        )) {
            renderThreadEventCount++;
        } else {
            nonRenderThreadEventCount++;
        }
    }
}
