package dev.ordovicium.potato.mixin.client;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ordovicium.potato.render.vulkan.VulkanGate10ImmediatePath;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;

import java.util.function.Predicate;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.shaders.Uniform;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendBridge;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.backend.draw.DrawSubmissionDispatcher;
import dev.ordovicium.potato.render.backend.draw.SectionLayerFrameContext;
import dev.ordovicium.potato.render.lod.PotatoLodProxyBridge;
import dev.ordovicium.potato.render.vulkan.PotatoMeshGenerationBridge;
import dev.ordovicium.potato.render.vulkan.VulkanExactFramePublicationToken;
import dev.ordovicium.potato.render.vulkan.VulkanRegionArenaIngress;
import dev.ordovicium.potato.render.vulkan.VulkanSurfaceClusterVisibility;
import dev.ordovicium.potato.render.vulkan.VulkanRuntimeManager;
import dev.ordovicium.potato.render.vulkan.VulkanVisiblePublicationGate;
import dev.ordovicium.potato.render.vulkan.VulkanVisibleSealedEpoch;
import dev.ordovicium.potato.render.vulkan.VulkanVisibleGeometryResidency;
import dev.ordovicium.potato.render.vulkan.VulkanVisibleSolidCutoverPolicy;
import dev.ordovicium.potato.render.vulkan.VulkanVisiblePreflightGovernor;
import dev.ordovicium.potato.render.vulkan.VulkanVisibleNearReadyRetryController;
import dev.ordovicium.potato.render.vulkan.VulkanRegionArenaSurvey;
import dev.ordovicium.potato.render.lod.PotatoLodRuntime;
import dev.ordovicium.potato.settings.PotatoAdaptiveViewController;
import dev.ordovicium.potato.render.visibility.HierarchicalWorldClusterEngine;
import dev.ordovicium.potato.render.visibility.PotatoTemporalOcclusionBridge;
import dev.ordovicium.potato.render.visibility.PotatoTemporalOcclusionFrame;
import dev.ordovicium.potato.render.visibility.PotatoTemporalOcclusionRuntime;
import dev.ordovicium.potato.render.visibility.PotatoViewTurnRelief;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Production-shaped section-layer seam.
 *
 * <p>Patch 058 keeps Minecraft's shader/texture state authoritative but may
 * substitute distant SOLID VertexBuffer draws with a Potato LOD proxy. The
 * proxy draw is local to this redirect and the original VertexBuffer is rebound
 * immediately afterwards so later vanilla draws see the expected GL state.</p>
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererSectionLayerDrawMixin {

    @Shadow
    private ObjectArrayList<SectionRenderDispatcher.RenderSection>
            visibleSections;

    @Unique
    private RenderType potato$currentRenderType;

    @Unique
    private Matrix4f potato$currentModelView;

    @Unique
    private Matrix4f potato$currentProjection;

    @Unique
    private double potato$currentCameraX;

    @Unique
    private double potato$currentCameraY;

    @Unique
    private double potato$currentCameraZ;

    @Unique
    private float potato$currentChunkOffsetX;

    @Unique
    private float potato$currentChunkOffsetY;

    @Unique
    private float potato$currentChunkOffsetZ;

    @Unique
    private boolean potato$sectionLayerFrameDispatched;

    @Unique
    private boolean potato$vulkanVisibleLayerOwnership;

    @Unique
    private boolean potato$vulkanFallbackSubmissionSuppressed;

    @Unique
    private static final int potato$MAX_VISIBLE_PREFLIGHT_DRAWS =
            4096;

    /**
     * Patch 088 moves visible SOLID ownership behind one centralized release
     * policy. The policy is enabled by default, may be forced off with
     * -Dpotato.vulkan.visibleSolidAtomic=false, and does not permit a visible
     * attempt until the hidden vkCmdDrawIndirect warmup has produced the
     * required clean raster proofs.
     */

    @Unique
    private final DrawBufferBackendState[] potato$visiblePreflightStates =
            new DrawBufferBackendState[
                    potato$MAX_VISIBLE_PREFLIGHT_DRAWS
            ];

    @Unique
    private final float[] potato$visiblePreflightOffsets =
            new float[
                    potato$MAX_VISIBLE_PREFLIGHT_DRAWS * 3
            ];

    @Unique
    private boolean potato$lodLayerFrameActive;

    @Unique
    private boolean potato$hierarchyObservationActive;

    @Unique
    private long potato$layerStartNanos;

    @Inject(
            method = "renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$captureLayerArguments(
            RenderType renderType,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelView,
            Matrix4f projection,
            CallbackInfo callbackInfo
    ) {
        potato$layerStartNanos =
                System.nanoTime();

        potato$currentRenderType =
                renderType;

        potato$currentModelView =
                modelView;

        potato$currentProjection =
                projection;

        potato$currentCameraX =
                cameraX;

        potato$currentCameraY =
                cameraY;

        potato$currentCameraZ =
                cameraZ;

        potato$currentChunkOffsetX =
                0.0f;

        potato$currentChunkOffsetY =
                0.0f;

        potato$currentChunkOffsetZ =
                0.0f;

        potato$sectionLayerFrameDispatched =
                false;

        potato$vulkanVisibleLayerOwnership =
                false;

        potato$vulkanFallbackSubmissionSuppressed =
                false;

        potato$lodLayerFrameActive =
                false;

        if (renderType == RenderType.solid()) {
            /*
             * Patch 085 begins the exact publication token from the immutable
             * arguments selected for this SOLID layer. Matrix values are
             * fingerprinted immediately; no mutable Matrix4f reference is
             * retained by the token.
             */
            VulkanExactFramePublicationToken.beginSolidLayer(
                    modelView,
                    projection,
                    cameraX,
                    cameraY,
                    cameraZ
            );

            /*
             * Patch 086 begins the coarse surface-cluster frame on the same
             * exact camera token. This is metadata/indirect planning only;
             * OpenGL remains authoritative and no visible draw is cancelled.
             */
            VulkanSurfaceClusterVisibility.beginSolidLayer();

            PotatoAdaptiveViewController.onSolidFrame();

            PotatoViewTurnRelief.observeSolidLayer(
                    modelView
            );
        }

        potato$hierarchyObservationActive =
                renderType == RenderType.solid()
                        && HierarchicalWorldClusterEngine.beginLayer(
                        renderType,
                        modelView,
                        projection,
                        cameraX,
                        cameraY,
                        cameraZ
                );
    }

    /**
     * Vanilla has selected/applied the exact RenderType shader. Capture global
     * shader state once and feed both the headless Vulkan observer and the LOD
     * policy from the same immutable layer snapshot.
     */
    @Inject(
            method = "renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ShaderInstance;apply()V",
                    shift = At.Shift.AFTER
            ),
            require = 1
    )
    private void potato$beginProductionSectionLayerFrame(
            RenderType renderType,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelView,
            Matrix4f projection,
            CallbackInfo callbackInfo
    ) {
        /*
         * Patch 080 release fast path: normal gameplay deliberately keeps
         * visible SOLID ownership on OpenGL until the frame-state publication
         * token and real region renderer exist. Do not enter the Vulkan
         * section-frame dispatcher for a SOLID layer that cannot possibly be
         * committed visibly.
         */
        /*
         * Patch 128: only a layer with a real visible Vulkan ownership
         * path may enter the production section-frame dispatcher.
         *
         * SOLID already has the exact-token + sealed-epoch atomic cutover.
         * CUTOUT was historically useful as a hidden raster proof, but while
         * OpenGL remains authoritative it only duplicates landscape work.
         * Keep CUTOUT/CUTOUT_MIPPED/translucent on their vanilla/OpenGL
         * authority until each receives its own visible fail-open cutover.
         */
        boolean drawFrameWanted =
                potato$currentRenderType == RenderType.solid()
                        && VulkanVisibleSolidCutoverPolicy.enabled()
                        && DrawSubmissionDispatcher
                        .wantsSectionLayerFrame(
                                potato$currentRenderType
                        );

        /*
         * Patch 140c: every current Potato per-section optimization is SOLID
         * only. Do not snapshot matrices/shader globals for CUTOUT,
         * CUTOUT_MIPPED or TRANSLUCENT just to have the LOD runtime reject the
         * layer immediately. Dense foliage made that dead transition tax
         * visible in broad, fully-loaded views.
         */
        boolean lodFrameWanted =
                potato$currentRenderType == RenderType.solid()
                        && PotatoLodRuntime.enabled();

        if (!drawFrameWanted
                && !lodFrameWanted) {
            return;
        }

        ShaderInstance shader =
                RenderSystem.getShader();

        SectionLayerFrameContext context =
                SectionLayerFrameContext.snapshot(
                        potato$currentRenderType,
                        potato$currentModelView,
                        potato$currentProjection,
                        shader,
                        potato$currentCameraX,
                        potato$currentCameraY,
                        potato$currentCameraZ
                );

        if (drawFrameWanted) {
            DrawSubmissionDispatcher.sectionLayerBegin(
                    context
            );

            potato$sectionLayerFrameDispatched =
                    true;

            potato$tryCommitVisibleVulkanSolid(
                    potato$currentRenderType
            );

            /*
             * Patch 073 does not submit a second hidden Vulkan copy of a SOLID
             * layer when OpenGL already owns the fallback. During startup and
             * render-distance churn this removes millions of duplicate pins and
             * draw records while preserving the exact vanilla frame.
             */
            if (potato$currentRenderType == RenderType.solid()
                    && !potato$vulkanVisibleLayerOwnership) {
                if (potato$sectionLayerFrameDispatched) {
                    DrawSubmissionDispatcher.sectionLayerEnd();
                    potato$sectionLayerFrameDispatched = false;
                }

                potato$vulkanFallbackSubmissionSuppressed = true;
                VulkanVisiblePublicationGate
                        .onFallbackSubmissionSuppressed();
            }
        }

        if (lodFrameWanted) {
            PotatoLodRuntime.beginLayer(
                    context
            );

            potato$lodLayerFrameActive =
                    true;
        }

        /*
         * Temporal occlusion is also a SOLID-only contract in this generation.
         * Since context creation above is now SOLID-only, this call cannot
         * accidentally enter the query path for foliage/translucency.
         */
        PotatoTemporalOcclusionRuntime.beginLayer(
                context
        );
    }

    /**
     * Patch 067 performs a layer-atomic SOLID ownership preflight. No OpenGL
     * draw is suppressed unless every visible non-empty SOLID section has a
     * current Vulkan geometry mirror and the Vulkan->OpenGL GPU interop commit
     * has been queued successfully. Any uncertainty leaves the original loop
     * completely authoritative.
     */
    @Unique
    private void potato$tryCommitVisibleVulkanSolid(
            RenderType renderType
    ) {
        if (renderType != RenderType.solid()) {
            return;
        }

        /*
         * Patch 088: visible ownership is no longer an experimental property,
         * but it is still proof-gated. 087 supplied a real graphics-queue
         * vkCmdDrawIndirect canary. We require a bounded warmup of clean
         * positive raster proofs before even scanning the visible layer.
         * Failure or explicit disablement leaves OpenGL fully authoritative.
         */
        if (!VulkanVisibleSolidCutoverPolicy.readyForVisibleAttempt()) {
            return;
        }

        /*
         * Patch 093: the 092 32-chunk run executed 14,298 complete visible
         * sweeps for only 10 eligible commits. Rewalking hundreds/thousands of
         * sections on every SOLID layer is exactly the kind of CPU tax a
         * potato-class machine cannot afford. The governor never suppresses
         * OpenGL; it only decides whether this frame is worth paying for a
         * complete atomic Vulkan ownership proof. A skipped proof therefore
         * means the existing OpenGL path remains authoritative for the frame.
         */
        boolean nearReadyRetry =
                VulkanVisibleNearReadyRetryController
                        .consumeForcedRetry();

        if (!nearReadyRetry
                && !VulkanVisiblePreflightGovernor.beginAttempt()) {
            return;
        }

        /*
         * One non-blocking transfer harvest per visible preflight is enough.
         * Per-section generation queries below are pure map reads.
         */
        VulkanRegionArenaIngress.refreshCompletedTransfers();

        long visibilityFingerprint =
                VulkanExactFramePublicationToken.visibilitySeed();

        long sealedEpochFingerprint =
                VulkanVisibleSealedEpoch.seed();

        int capacity =
                Math.min(
                        potato$MAX_VISIBLE_PREFLIGHT_DRAWS,
                        VulkanRuntimeManager
                                .visibleSolidDrawCapacity()
                );

        if (capacity <= 0) {
            VulkanVisiblePublicationGate
                    .onResourcePreflightReject();

            VulkanVisiblePublicationGate
                    .onFullLayerSweep(
                            0,
                            0,
                            0,
                            true
                    );

            if (nearReadyRetry) {
                VulkanVisibleNearReadyRetryController
                        .onForcedRetryAborted();
            } else {
                VulkanVisiblePreflightGovernor.onCapacityReject();
            }
            return;
        }

        int candidateCount =
                0;

        int storedReadyCount =
                0;

        int readyCount =
                0;

        int pendingCount =
                0;

        boolean preflightRejected =
                false;

        VulkanVisibleGeometryResidency
                .beginVisibleSweep();

        /*
         * Patch 075 intentionally completes this entire scan even after the
         * first unstable/missing resource. The old early-return behavior made
         * publication proof effectively serial: at a 32-chunk view distance,
         * only the first failing section progressed each layer. A sudden 180°
         * turn could therefore expose hundreds of sections that had never been
         * allowed to mature despite already being resident.
         *
         * All visible publication pairs now age in parallel. Atomic ownership
         * is still unchanged: one bad section rejects the whole Vulkan SOLID
         * commit for this frame.
         */
        for (SectionRenderDispatcher.RenderSection section
                : visibleSections) {
            SectionRenderDispatcher.CompiledSection compiled =
                    section.getCompiled();

            if (compiled.isEmpty(renderType)) {
                continue;
            }

            candidateCount++;

            BlockPos origin =
                    section.getOrigin();

            visibilityFingerprint =
                    VulkanExactFramePublicationToken.mixVisibleSection(
                            visibilityFingerprint,
                            origin.getX(),
                            origin.getY(),
                            origin.getZ()
                    );

            VertexBuffer vertexBuffer =
                    section.getBuffer(renderType);

            long currentMeshGeneration =
                    ((PotatoMeshGenerationBridge)
                            (Object) vertexBuffer)
                            .potato$meshGeneration();

            long completedResidentGeneration =
                    VulkanRegionArenaIngress
                            .completedSourceGeneration(
                                    (Object) vertexBuffer
                            );

            boolean exactResidentGeneration =
                    VulkanExactFramePublicationToken
                            .observeMeshGeneration(
                                    currentMeshGeneration,
                                    completedResidentGeneration
                            );

            DrawBufferBackendState state =
                    ((DrawBufferBackendBridge)
                            (Object) vertexBuffer)
                            .potato$drawBackendStateIfPresent();

            if (state != null) {
                VulkanRegionArenaSurvey.associate(
                        state,
                        section.getOrigin()
                );
            }

            long stateUploadGeneration =
                    state == null
                            ? 0L
                            : state.uploadGeneration();

            sealedEpochFingerprint =
                    VulkanVisibleSealedEpoch.mixSection(
                            sealedEpochFingerprint,
                            origin.getX(),
                            origin.getY(),
                            origin.getZ(),
                            System.identityHashCode(compiled),
                            currentMeshGeneration,
                            completedResidentGeneration,
                            stateUploadGeneration
                    );

            boolean visibleResidencyReady =
                    state != null
                            && VulkanVisibleGeometryResidency
                            .prepareVisible(
                                    state
                            );

            boolean sectionPendingMismatch =
                    VulkanVisiblePublicationGate
                            .observeVisibleSection(
                                    section,
                                    compiled,
                                    state
                            );

            if (sectionPendingMismatch) {
                pendingCount++;
            }

            boolean stateReady =
                    state != null
                            && visibleResidencyReady
                            && exactResidentGeneration
                            && !sectionPendingMismatch
                            && VulkanRuntimeManager
                            .visibleSolidStateReady(state);

            if (!stateReady) {
                preflightRejected =
                        true;
                continue;
            }

            readyCount++;

            if (storedReadyCount >= capacity) {
                preflightRejected =
                        true;
                continue;
            }

            potato$visiblePreflightStates[
                    storedReadyCount
            ] =
                    state;

            int offsetBase =
                    storedReadyCount * 3;

            potato$visiblePreflightOffsets[
                    offsetBase
            ] =
                    (float) (origin.getX()
                            - potato$currentCameraX);

            potato$visiblePreflightOffsets[
                    offsetBase + 1
            ] =
                    (float) (origin.getY()
                            - potato$currentCameraY);

            potato$visiblePreflightOffsets[
                    offsetBase + 2
            ] =
                    (float) (origin.getZ()
                            - potato$currentCameraZ);

            storedReadyCount++;
        }

        VulkanExactFramePublicationToken.sealVisibility(
                visibilityFingerprint,
                candidateCount
        );

        boolean layerReady =
                !preflightRejected
                        && candidateCount >= 2
                        && candidateCount <= capacity
                        && readyCount == candidateCount
                        && storedReadyCount == candidateCount;

        VulkanVisiblePublicationGate
                .onFullLayerSweep(
                        candidateCount,
                        readyCount,
                        pendingCount,
                        !layerReady
                );

        if (!layerReady) {
            VulkanVisibleSealedEpoch.onRejectedSweep();

            potato$clearVisiblePreflightScratch(
                    storedReadyCount
            );

            VulkanVisiblePublicationGate
                    .onResourcePreflightReject();

            VulkanVisibleNearReadyRetryController
                    .observeRejectedSweep(
                            nearReadyRetry,
                            candidateCount,
                            readyCount,
                            pendingCount
                    );

            if (!nearReadyRetry) {
                VulkanVisiblePreflightGovernor.onLayerReject(
                        candidateCount,
                        readyCount
                );
            }
            return;
        }

        VulkanVisibleNearReadyRetryController
                .onLayerReady(
                        nearReadyRetry,
                        candidateCount
                );

        /*
         * Patch 120: a complete current-generation sweep is not immediately
         * granted ownership. The exact whole-layer generation fingerprint must
         * first become/stay ACTIVE in the sealed epoch controller. A changed
         * visible set or mesh generation builds a new epoch while this frame
         * remains fully OpenGL.
         */
        if (!VulkanVisibleSealedEpoch.offerCompleteSweep(
                sealedEpochFingerprint,
                candidateCount
        )) {
            potato$clearVisiblePreflightScratch(
                    storedReadyCount
            );

            VulkanVisiblePublicationGate
                    .onResourcePreflightReject();

            if (nearReadyRetry) {
                VulkanVisibleNearReadyRetryController
                        .onForcedDownstreamReject();
            } else {
                VulkanVisiblePreflightGovernor.onPrepareReject(
                        candidateCount
                );
            }
            return;
        }

        /*
         * The old 079 stop-gate is intentionally retired here. Patch 085 now
         * binds camera/projection, visible-set fingerprint and every current
         * mesh generation into one exact publication token. The checks below
         * must still pass before any OpenGL SOLID draw can be suppressed.
         */

        if (!VulkanExactFramePublicationToken
                .readyForVisibleCommit(
                        candidateCount
                )) {
            potato$clearVisiblePreflightScratch(
                    storedReadyCount
            );

            VulkanVisiblePublicationGate
                    .onResourcePreflightReject();

            if (nearReadyRetry) {
                VulkanVisibleNearReadyRetryController
                        .onForcedDownstreamReject();
            } else {
                VulkanVisiblePreflightGovernor.onExactTokenReject(
                        candidateCount
                );
            }
            return;
        }

        if (!VulkanVisiblePublicationGate
                .allowVisibleCommit(
                        false
                )
                || !VulkanRuntimeManager
                .prepareVisibleSolidOwnership(renderType)) {
            potato$clearVisiblePreflightScratch(
                    storedReadyCount
            );
            if (nearReadyRetry) {
                VulkanVisibleNearReadyRetryController
                        .onForcedDownstreamReject();
            } else {
                VulkanVisiblePreflightGovernor.onPrepareReject(
                        candidateCount
                );
            }
            return;
        }

        for (int index = 0;
             index < storedReadyCount;
             index++) {

            DrawBufferBackendState state =
                    potato$visiblePreflightStates[
                            index
                    ];

            int offsetBase =
                    index * 3;

            DrawSubmissionDispatcher.sectionLayerDrawFast(
                    state,
                    potato$visiblePreflightOffsets[
                            offsetBase
                    ],
                    potato$visiblePreflightOffsets[
                            offsetBase + 1
                    ],
                    potato$visiblePreflightOffsets[
                            offsetBase + 2
                    ]
            );

            potato$visiblePreflightStates[
                    index
            ] =
                    null;
        }

        if (!VulkanRuntimeManager
                .armVisibleSolidOwnership(storedReadyCount)) {
            DrawSubmissionDispatcher.sectionLayerEnd();
            potato$sectionLayerFrameDispatched = false;
            if (nearReadyRetry) {
                VulkanVisibleNearReadyRetryController
                        .onForcedDownstreamReject();
            } else {
                VulkanVisiblePreflightGovernor.onArmReject(
                        candidateCount
                );
            }
            return;
        }

        DrawSubmissionDispatcher.sectionLayerEnd();
        potato$sectionLayerFrameDispatched =
                false;

        potato$vulkanVisibleLayerOwnership =
                VulkanRuntimeManager
                        .visibleSolidCommitQueued();

        if (potato$vulkanVisibleLayerOwnership) {
            VulkanExactFramePublicationToken
                    .onVisibleOwnershipQueued();
            VulkanVisibleSealedEpoch.onVisibleCommitSuccess(
                    sealedEpochFingerprint,
                    candidateCount
            );
            VulkanVisibleNearReadyRetryController
                    .onVisibleCommitSuccess(
                            nearReadyRetry
                    );
            VulkanVisiblePreflightGovernor.onCommitSuccess(
                    candidateCount
            );
        } else {
            if (nearReadyRetry) {
                VulkanVisibleNearReadyRetryController
                        .onForcedDownstreamReject();
            } else {
                VulkanVisiblePreflightGovernor.onCommitReject(
                        candidateCount
                );
            }
        }
    }

    @Unique
    private void potato$clearVisiblePreflightScratch(
            int count
    ) {
        int bounded =
                Math.max(
                        0,
                        Math.min(
                                count,
                                potato$visiblePreflightStates.length
                        )
                );

        for (int index = 0;
             index < bounded;
             index++) {
            potato$visiblePreflightStates[
                    index
            ] =
                    null;
        }
    }

    /**
     * Patch 070 keeps the proven ChunkOffset owned-layer fast
     * path. Once Vulkan has atomically committed SOLID, vanilla still walks its
     * section list for method cleanup, but it no longer performs a per-section
     * OpenGL uniform write or hierarchy bookkeeping for draws that will never
     * execute.
     */
    @Redirect(
            method = "renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/shaders/Uniform;set(FFF)V",
                    ordinal = 0
            ),
            require = 1
    )
    private void potato$handleSectionChunkOffset(
            Uniform uniform,
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
        if (potato$vulkanVisibleLayerOwnership) {
            return;
        }

        potato$currentChunkOffsetX =
                chunkOffsetX;

        potato$currentChunkOffsetY =
                chunkOffsetY;

        potato$currentChunkOffsetZ =
                chunkOffsetZ;

        if (potato$hierarchyObservationActive) {
            HierarchicalWorldClusterEngine.observeSection(
                    potato$currentChunkOffsetX,
                    potato$currentChunkOffsetY,
                    potato$currentChunkOffsetZ,
                    potato$currentCameraX,
                    potato$currentCameraY,
                    potato$currentCameraZ
            );
        }

        uniform.set(
                chunkOffsetX,
                chunkOffsetY,
                chunkOffsetZ
        );
    }

    @Redirect(
            method = "renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;bind()V"
            ),
            require = 1
    )
    private void potato$skipOwnedSolidOpenGlBind(
            VertexBuffer vertexBuffer
    ) {
        if (!potato$vulkanVisibleLayerOwnership) {
            vertexBuffer.bind();
        }
    }

    @Redirect(
            method = "renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V"
            ),
            require = 1
    )
    private void potato$dispatchLeanSectionDraw(
            VertexBuffer vertexBuffer
    ) {
        if (potato$vulkanVisibleLayerOwnership) {
            return;
        }

        /*
         * Patch 140c loaded-view fast path. CUTOUT, CUTOUT_MIPPED and
         * TRANSLUCENT currently have no Potato LOD, temporal-occlusion or
         * visible Vulkan ownership semantics. Running every one of those
         * section draws through SOLID-only sidecars paid millions of useless
         * interface calls/counter updates in foliage-heavy scenes. Preserve
         * exact vanilla rendering and leave immediately.
         */
        if (potato$currentRenderType != RenderType.solid()) {
            vertexBuffer.draw();
            return;
        }

        if (VulkanSurfaceClusterVisibility
                .shouldObserveSectionDraw()) {
            /*
             * Patch 140a gates before the mesh-generation sidecar read. The
             * post-proof surface-cluster planner samples sparsely, so ordinary
             * OpenGL SOLID draws no longer pay an interface call and a Vulkan
             * observer call on every visible section of every frame.
             */
            VulkanSurfaceClusterVisibility.observeSectionDraw(
                    (Object) vertexBuffer,
                    ((PotatoMeshGenerationBridge)
                            (Object) vertexBuffer)
                            .potato$meshGeneration(),
                    potato$currentChunkOffsetX,
                    potato$currentChunkOffsetY,
                    potato$currentChunkOffsetZ
            );
        }

        PotatoLodProxyBridge lodBridge =
                (PotatoLodProxyBridge)
                        (Object) vertexBuffer;

        int previousTier =
                lodBridge.potato$currentLodTier();

        int desiredTier =
                PotatoLodRuntime.selectDetailTierFast(
                        potato$currentChunkOffsetX,
                        potato$currentChunkOffsetY,
                        potato$currentChunkOffsetZ,
                        previousTier
                );

        desiredTier =
                PotatoViewTurnRelief.applyTier(
                        potato$currentChunkOffsetX,
                        potato$currentChunkOffsetY,
                        potato$currentChunkOffsetZ,
                        desiredTier
                );

        PotatoTemporalOcclusionBridge occlusionBridge =
                (PotatoTemporalOcclusionBridge)
                        (Object) vertexBuffer;

        PotatoTemporalOcclusionFrame occlusionFrame =
                PotatoTemporalOcclusionRuntime.currentFrame();

        boolean occlusionCandidate =
                PotatoTemporalOcclusionRuntime.isCandidate(
                        vertexBuffer,
                        desiredTier,
                        potato$currentChunkOffsetX,
                        potato$currentChunkOffsetY,
                        potato$currentChunkOffsetZ
                );

        if (occlusionCandidate
                && occlusionBridge.potato$shouldSkipTemporalOcclusion(
                occlusionFrame
        )) {
            return;
        }

        /*
         * Patch 140c: do not even read the backend sidecar unless this layer
         * has a live production Vulkan section frame. In the current fail-open
         * generation almost every SOLID layer closes that frame immediately,
         * so the old unconditional state read was pure hot-path overhead.
         */
        if (potato$sectionLayerFrameDispatched
                && !potato$vulkanFallbackSubmissionSuppressed) {
            DrawBufferBackendState state =
                    ((DrawBufferBackendBridge)
                            (Object) vertexBuffer)
                            .potato$drawBackendStateIfPresent();

            if (state != null
                    && DrawSubmissionDispatcher
                    .wantsSectionLayerDraw(state)) {
                DrawSubmissionDispatcher.sectionLayerDrawFast(
                        state,
                        potato$currentChunkOffsetX,
                        potato$currentChunkOffsetY,
                        potato$currentChunkOffsetZ
                );
            }
        }

        boolean queryStarted =
                occlusionCandidate
                        && PotatoTemporalOcclusionRuntime.tryAcquireQuerySlot()
                        && occlusionBridge.potato$beginTemporalOcclusionQuery(
                        occlusionFrame
                );

        try {
            if (lodBridge.potato$drawLodProxy(desiredTier)) {
                vertexBuffer.bind();
                return;
            }

            PotatoLodRuntime.onBaselineFallback(desiredTier);
            vertexBuffer.draw();
        } finally {
            if (queryStarted) {
                occlusionBridge.potato$endTemporalOcclusionQuery();
            }
        }
    }

    @Inject(
            method = "renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$endProductionSectionLayerFrame(
            RenderType renderType,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelView,
            Matrix4f projection,
            CallbackInfo callbackInfo
    ) {
        if (potato$sectionLayerFrameDispatched) {
            DrawSubmissionDispatcher.sectionLayerEnd();
        }

        long potato$layerElapsedNanos =
                Math.max(
                        0L,
                        System.nanoTime()
                                - potato$layerStartNanos
                );

        if (potato$lodLayerFrameActive) {
            PotatoLodRuntime.endLayer(
                    potato$layerElapsedNanos
            );
        }

        /*
         * Four primitive updates per section layer give the next runtime an
         * exact SOLID/CUTOUT/CUTOUT_MIPPED/TRANSLUCENT cost split without any
         * per-section instrumentation.
         */
        PotatoLodRuntime.observeSectionLayerTiming(
                renderType,
                potato$layerElapsedNanos
        );

        PotatoTemporalOcclusionRuntime.endLayer();

        if (renderType == RenderType.solid()) {
            PotatoViewTurnRelief.endSolidLayer();
            VulkanSurfaceClusterVisibility.endSolidLayer();
            VulkanExactFramePublicationToken.endSolidLayer();
        }

        if (potato$hierarchyObservationActive) {
            HierarchicalWorldClusterEngine.endLayer();
        }

        potato$hierarchyObservationActive =
                false;

        potato$currentRenderType =
                null;

        potato$currentModelView =
                null;

        potato$currentProjection =
                null;

        potato$currentChunkOffsetX =
                0.0f;

        potato$currentChunkOffsetY =
                0.0f;

        potato$currentChunkOffsetZ =
                0.0f;

        potato$sectionLayerFrameDispatched =
                false;

        potato$vulkanVisibleLayerOwnership =
                false;

        potato$vulkanFallbackSubmissionSuppressed =
                false;

        potato$lodLayerFrameActive =
                false;

        /*
         * 057b accidentally re-entered HierarchicalWorldClusterEngine here
         * after already ending the layer. Patch 058 leaves it closed.
         */
    }

    /**
     * Gate 10 Stage 1: observe the exact vanilla entity submission seam while
     * preserving the original OpenGL renderer call byte-for-byte in behavior.
     */
    @Redirect(
            method = "renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V"
            ),
            require = 1
    )
    private void potato$observeGate10EntityRender(
            EntityRenderDispatcher dispatcher,
            Entity entity,
            double x,
            double y,
            double z,
            float yRot,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight
    ) {
        long startNanos =
                System.nanoTime();

        try {
            dispatcher.render(
                    entity,
                    x,
                    y,
                    z,
                    yRot,
                    partialTick,
                    poseStack,
                    bufferSource,
                    packedLight
            );
        } finally {
            VulkanGate10ImmediatePath.observeEntityRender(
                    System.nanoTime()
                            - startNanos
            );
        }
    }

    /**
     * Gate 10 Stage 1: observe every particle pass selected by renderLevel.
     * No particle pass is skipped, reordered or replaced in 091.
     */
    @Redirect(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/particle/ParticleEngine;render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V"
            ),
            require = 1
    )
    private void potato$observeGate10ParticlePass(
            ParticleEngine particleEngine,
            LightTexture lightTexture,
            Camera camera,
            float partialTick,
            Frustum frustum,
            Predicate<ParticleRenderType> predicate
    ) {
        long startNanos =
                System.nanoTime();

        /*
         * Patch 135 release-candidate particle parity:
         * Minecraft's Fabulous path intentionally renders particles into the
         * dedicated particlesTarget after copying MainTarget depth. The normal
         * path renders them directly into MainTarget. The Vulkan presentation
         * stream must never leave a different framebuffer bound before either
         * vanilla ParticleEngine pass.
         *
         * This is fail-open parity repair only. Particle rendering itself stays
         * completely vanilla/OpenGL-authoritative in this generation.
         */
        Minecraft minecraft =
                Minecraft.getInstance();

        RenderTarget expectedParticleTarget =
                Minecraft.useShaderTransparency()
                        ? ((LevelRenderer) (Object) this)
                        .getParticlesTarget()
                        : null;

        if (expectedParticleTarget == null
                && minecraft != null) {
            expectedParticleTarget =
                    minecraft.getMainRenderTarget();
        }

        VulkanGate10ImmediatePath
                .verifyParticleOutputTarget(
                        expectedParticleTarget
                );

        try {
            particleEngine.render(
                    lightTexture,
                    camera,
                    partialTick,
                    frustum,
                    predicate
            );
        } finally {
            VulkanGate10ImmediatePath.observeParticlePass(
                    System.nanoTime()
                            - startNanos
            );
        }
    }}
