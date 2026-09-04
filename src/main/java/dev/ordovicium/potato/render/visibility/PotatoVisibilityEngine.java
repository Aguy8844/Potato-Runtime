package dev.ordovicium.potato.render.visibility;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.SectionLayerFrameContext;

/**
 * Camera-driven render-work classifier.
 *
 * <p>Patch 044 is intentionally non-authoritative. It does not cancel the
 * visible OpenGL draw path yet. Its job is to establish stable renderer-only
 * HOT/WARM/COLD semantics before Patch 045 uses them to prioritize/defer chunk
 * mesh work.</p>
 */
public final class PotatoVisibilityEngine
        implements AutoCloseable {

    private final JsonObject report;

    private PotatoVisibilityFrame currentFrame;

    private long layerBeginCount;
    private long layerEndCount;

    private long classificationCount;

    private long hotCount;
    private long warmCount;
    private long coldCount;

    private long exactFrustumCount;
    private long nearGuardCount;
    private long expandedGuardCount;
    private long outsideGuardCount;
    private long invalidFallbackCount;

    private long frameReplacementCount;

    private SectionVisibilityTier lastTier;
    private SectionVisibilityReason lastReason;

    private boolean closed;

    public PotatoVisibilityEngine(
            JsonObject report
    ) {
        this.report =
                report;
    }

    public void beginLayer(
            SectionLayerFrameContext context
    ) {
        if (closed) {
            return;
        }

        if (currentFrame != null) {
            frameReplacementCount++;
        }

        currentFrame =
                new PotatoVisibilityFrame(
                        context
                );

        layerBeginCount++;
    }

    public SectionVisibilityTier classify(
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
        if (closed) {
            return SectionVisibilityTier.HOT;
        }

        PotatoVisibilityFrame frame =
                currentFrame;

        if (frame == null) {
            classificationCount++;
            hotCount++;
            invalidFallbackCount++;

            lastTier =
                    SectionVisibilityTier.HOT;

            lastReason =
                    SectionVisibilityReason
                            .INVALID_FRAME_CONSERVATIVE_FALLBACK;

            return SectionVisibilityTier.HOT;
        }

        SectionVisibilityTier tier =
                frame.classify(
                        chunkOffsetX,
                        chunkOffsetY,
                        chunkOffsetZ
                );

        SectionVisibilityReason reason =
                frame.reasonFor(
                        tier,
                        chunkOffsetX,
                        chunkOffsetY,
                        chunkOffsetZ
                );

        classificationCount++;

        switch (tier) {
            case HOT ->
                    hotCount++;

            case WARM ->
                    warmCount++;

            case COLD ->
                    coldCount++;
        }

        switch (reason) {
            case EXACT_FRUSTUM_INTERSECTION ->
                    exactFrustumCount++;

            case NEAR_CAMERA_GUARD ->
                    nearGuardCount++;

            case EXPANDED_FRUSTUM_GUARD ->
                    expandedGuardCount++;

            case OUTSIDE_GUARD_BAND ->
                    outsideGuardCount++;

            case INVALID_FRAME_CONSERVATIVE_FALLBACK ->
                    invalidFallbackCount++;
        }

        lastTier =
                tier;

        lastReason =
                reason;

        return tier;
    }

    public void endLayer() {
        if (closed) {
            return;
        }

        currentFrame =
                null;

        layerEndCount++;
    }

    public boolean verified() {
        return !closed
                && layerBeginCount > 0
                && classificationCount > 0
                && hotCount > 0
                && layerEndCount > 0;
    }

    public void enrich() {
        report.addProperty(
                "visibilityEngineInstalled",
                true
        );
        report.addProperty(
                "visibilityEngineMode",
                "CAMERA_PROJECTIVE_HOT_WARM_COLD_FOUNDATION"
        );
        report.addProperty(
                "visibilityEngineLayerBeginCount",
                layerBeginCount
        );
        report.addProperty(
                "visibilityEngineLayerEndCount",
                layerEndCount
        );
        report.addProperty(
                "visibilityEngineClassificationCount",
                classificationCount
        );
        report.addProperty(
                "visibilityEngineHotCount",
                hotCount
        );
        report.addProperty(
                "visibilityEngineWarmCount",
                warmCount
        );
        report.addProperty(
                "visibilityEngineColdCount",
                coldCount
        );
        report.addProperty(
                "visibilityEngineExactFrustumCount",
                exactFrustumCount
        );
        report.addProperty(
                "visibilityEngineNearGuardCount",
                nearGuardCount
        );
        report.addProperty(
                "visibilityEngineExpandedGuardCount",
                expandedGuardCount
        );
        report.addProperty(
                "visibilityEngineOutsideGuardCount",
                outsideGuardCount
        );
        report.addProperty(
                "visibilityEngineInvalidFallbackCount",
                invalidFallbackCount
        );
        report.addProperty(
                "visibilityEngineFrameReplacementCount",
                frameReplacementCount
        );

        if (lastTier != null) {
            report.addProperty(
                    "visibilityEngineLastTier",
                    lastTier.name()
            );
        }

        if (lastReason != null) {
            report.addProperty(
                    "visibilityEngineLastReason",
                    lastReason.name()
            );
        }

        report.addProperty(
                "visibilityEngineUsesActualRenderMatrices",
                true
        );
        report.addProperty(
                "visibilityEngineUsesPlayerEntityInsteadOfCamera",
                false
        );
        report.addProperty(
                "visibilityEngineThirdPersonCameraCompatible",
                true
        );
        report.addProperty(
                "visibilityEngineSpectatorCameraCompatible",
                true
        );
        report.addProperty(
                "visibilityEnginePerSectionAllocation",
                false
        );
        report.addProperty(
                "visibilityEngineExactHotPolicy",
                "HOMOGENEOUS_CLIP_AABB_INTERSECTION"
        );
        report.addProperty(
                "visibilityEngineWarmPolicy",
                "NEAR_CAMERA_OR_EXPANDED_CLIP_GUARD_BAND"
        );
        report.addProperty(
                "visibilityEngineColdPolicy",
                "OUTSIDE_CURRENT_VIEW_AND_GUARD_BAND"
        );

        /*
         * Core safety contract:
         * visibility is a render-work concern only.
         */
        report.addProperty(
                "visibilityEngineMutatesChunkLoading",
                false
        );
        report.addProperty(
                "visibilityEngineMutatesWorldTicking",
                false
        );
        report.addProperty(
                "visibilityEngineMutatesEntitySimulation",
                false
        );
        report.addProperty(
                "visibilityEngineMutatesBlockEntitySimulation",
                false
        );
        report.addProperty(
                "visibilityEngineMutatesSaving",
                false
        );
        report.addProperty(
                "visibilityEngineCancelsVisibleOpenGlDraws",
                false
        );

        report.addProperty(
                "visibilityEngineOcclusionCullingEnabled",
                false
        );
        report.addProperty(
                "visibilityEngineLodEnabled",
                false
        );
        report.addProperty(
                "visibilityEngineChunkMeshDeferralEnabled",
                false
        );
        report.addProperty(
                "visibilityEnginePredictionGuardBandEnabled",
                true
        );

        report.addProperty(
                "visibilityEngineVerified",
                verified()
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        enrich();

        currentFrame =
                null;

        closed =
                true;

        report.addProperty(
                "visibilityEngineClosed",
                true
        );
    }
}