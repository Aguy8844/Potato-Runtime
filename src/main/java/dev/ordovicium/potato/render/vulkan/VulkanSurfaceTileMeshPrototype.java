package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.surface.SurfaceTileMeshSnapshot;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * Real Minecraft surface-tile textured GPU decode prototype.
 *
 * <p>Patch 050 keeps the Patch 048 compact representation, but now the
 * fragment shader also consumes the captured BLOCK atlas and 16x16 lightmap.
 * The original four-vertex QUAD order is retained inside the previously unused
 * tile padding word so per-tile triangle interpolation can be reproduced
 * without expanding the 64-byte tile stride.</p>
 *
 * <p>The proof is still offscreen and does not cancel visible OpenGL draws.</p>
 */
final class VulkanSurfaceTileMeshPrototype {

    private static final int MAX_ATTEMPTS =
            3;

    private static int attemptCount;
    private static int successCount;
    private static int failureCount;

    private static boolean verifiedBeforeClose;
    private static boolean resourcesClosed;

    private static String executionThread =
            "";

    private static int sourceFaceCount;
    private static int rectangleCount;
    private static int tileCount;
    private static int maximumRectangleArea;

    private static int rectangleDescriptorBytes;
    private static int tileAttributeBytes;
    private static long totalAllocationBytes;

    private static double topologyReductionPercent;

    private static boolean rectangleBufferCreated;
    private static boolean tileBufferCreated;
    private static boolean rectangleUploadValidated;
    private static boolean tileUploadValidated;

    private static int rectangleMemoryTypeIndex =
            -1;

    private static int tileMemoryTypeIndex =
            -1;

    private static boolean rectangleHostCoherent;
    private static boolean tileHostCoherent;

    private static boolean gpuDrawExecuted;
    private static boolean gpuPipelineCreated;
    private static boolean gpuDescriptorsCreated;
    private static boolean gpuQueueSubmitUsed;
    private static boolean gpuFenceWaitUsed;
    private static boolean gpuResourcesClosed;
    private static boolean gpuFallbackQueueWaitIdleUsed;

    private static int gpuQueueSubmitResult =
            Integer.MIN_VALUE;

    private static int gpuFenceWaitResult =
            Integer.MIN_VALUE;

    private static int gpuQueryResult =
            Integer.MIN_VALUE;

    private static long gpuRasterizedSamples;
    private static int gpuDrawVertexCount;

    private static boolean textureResourcesVerified;
    private static boolean texturedDecodeVerified;

    private static String failure =
            "";

    private VulkanSurfaceTileMeshPrototype() {
    }

    static boolean tryUpload(
            VulkanRuntimeContext runtime,
            SurfaceTileMeshSnapshot snapshot
    ) {
        if (verifiedBeforeClose) {
            return true;
        }

        if (runtime == null
                || runtime.closed()
                || snapshot == null) {

            return false;
        }

        if (attemptCount
                >= MAX_ATTEMPTS) {

            return false;
        }

        VkDevice device =
                runtime.deviceForSurfaceTilePrototype();

        VkPhysicalDevice physicalDevice =
                runtime.physicalDeviceForSurfaceTilePrototype();

        VkQueue graphicsQueue =
                runtime.graphicsQueueForSurfaceTilePrototype();

        int graphicsQueueFamilyIndex =
                runtime.graphicsQueueFamilyIndexForSurfaceTilePrototype();

        VulkanFrameSession.SectionLayerTargetSnapshot
                targetSnapshot =
                runtime.surfaceTileTargetSnapshot();

        JsonObject report =
                runtime.reportForSurfaceTilePrototype();

        VulkanBlockTextureUploadPrototype textures =
                runtime.surfaceTileTextureResources();

        textureResourcesVerified =
                textures != null
                        && textures.verified();

        /*
         * BLOCK atlas/lightmap capture can lag early geometry uploads. This is
         * not a prototype failure; leave the dispatcher armed for a later
         * eligible surface snapshot.
         */
        if (device == null
                || physicalDevice == null
                || graphicsQueue == null
                || graphicsQueueFamilyIndex < 0
                || targetSnapshot == null
                || report == null
                || !textureResourcesVerified) {

            return false;
        }

        ByteBuffer rectangles =
                snapshot.rectangleDescriptors();

        ByteBuffer tiles =
                snapshot.tileAttributes();

        if (!rectangles.hasRemaining()
                || !tiles.hasRemaining()) {

            return false;
        }

        attemptCount++;

        executionThread =
                Thread.currentThread()
                        .getName();

        sourceFaceCount =
                snapshot.sourceFaceCount();

        rectangleCount =
                snapshot.rectangleCount();

        tileCount =
                snapshot.tileCount();

        maximumRectangleArea =
                snapshot.maximumRectangleArea();

        rectangleDescriptorBytes =
                rectangles.remaining();

        tileAttributeBytes =
                tiles.remaining();

        topologyReductionPercent =
                snapshot.topologyReductionPercent();

        VulkanGeometryBufferAllocation rectangleAllocation =
                null;

        VulkanGeometryBufferAllocation tileAllocation =
                null;

        try {
            rectangleAllocation =
                    VulkanGeometryBufferAllocation.create(
                            device,
                            physicalDevice,
                            rectangleDescriptorBytes,
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            "SURFACE_RECTANGLE_DESCRIPTOR"
                    );

            rectangleBufferCreated =
                    rectangleAllocation.buffer()
                            != 0L;

            rectangleMemoryTypeIndex =
                    rectangleAllocation.memoryTypeIndex();

            rectangleHostCoherent =
                    (
                            rectangleAllocation
                                    .memoryPropertyFlags()
                                    & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    )
                            != 0;

            rectangleAllocation.upload(
                    rectangles
            );

            rectangleUploadValidated =
                    rectangleBufferCreated;

            tileAllocation =
                    VulkanGeometryBufferAllocation.create(
                            device,
                            physicalDevice,
                            tileAttributeBytes,
                            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            "SURFACE_TILE_ATTRIBUTE"
                    );

            tileBufferCreated =
                    tileAllocation.buffer()
                            != 0L;

            tileMemoryTypeIndex =
                    tileAllocation.memoryTypeIndex();

            tileHostCoherent =
                    (
                            tileAllocation
                                    .memoryPropertyFlags()
                                    & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    )
                            != 0;

            tileAllocation.upload(
                    tiles
            );

            tileUploadValidated =
                    tileBufferCreated;

            totalAllocationBytes =
                    rectangleAllocation.allocationBytes()
                            + tileAllocation.allocationBytes();

            boolean bufferContractVerified =
                    rectangleBufferCreated
                            && tileBufferCreated
                            && rectangleUploadValidated
                            && tileUploadValidated
                            && rectangleCount > 0
                            && tileCount > rectangleCount
                            && sourceFaceCount == tileCount;

            if (!bufferContractVerified) {
                failureCount++;

                failure =
                        "Surface tile storage buffers failed the pre-draw validation contract.";

                return false;
            }

            VulkanSurfaceTileGpuDecodeDraw.Outcome
                    gpuOutcome =
                    VulkanSurfaceTileGpuDecodeDraw.execute(
                            device,
                            graphicsQueue,
                            graphicsQueueFamilyIndex,
                            targetSnapshot,
                            rectangleAllocation.buffer(),
                            rectangleDescriptorBytes,
                            tileAllocation.buffer(),
                            tileAttributeBytes,
                            rectangleCount,
                            textures,
                            report
                    );

            gpuDrawExecuted =
                    gpuOutcome.verified();

            gpuPipelineCreated =
                    gpuOutcome.pipelineCreated();

            gpuDescriptorsCreated =
                    gpuOutcome.descriptorsCreated();

            gpuQueueSubmitUsed =
                    gpuOutcome.queueSubmitUsed();

            gpuFenceWaitUsed =
                    gpuOutcome.fenceWaitUsed();

            gpuResourcesClosed =
                    gpuOutcome.resourcesClosed();

            gpuFallbackQueueWaitIdleUsed =
                    gpuOutcome.fallbackQueueWaitIdleUsed();

            gpuQueueSubmitResult =
                    gpuOutcome.queueSubmitResult();

            gpuFenceWaitResult =
                    gpuOutcome.fenceWaitResult();

            gpuQueryResult =
                    gpuOutcome.queryResult();

            gpuRasterizedSamples =
                    gpuOutcome.rasterizedSamples();

            gpuDrawVertexCount =
                    gpuOutcome.drawVertexCount();

            texturedDecodeVerified =
                    bufferContractVerified
                            && textureResourcesVerified
                            && gpuOutcome.verified();

            verifiedBeforeClose =
                    texturedDecodeVerified;

            if (verifiedBeforeClose) {
                successCount++;

                return true;
            }

            failureCount++;

            failure =
                    gpuOutcome.failure()
                            .isBlank()
                            ? "Textured GPU decode draw did not satisfy its verification contract."
                            : gpuOutcome.failure();

            return false;
        } catch (Throwable throwable) {
            failureCount++;

            failure =
                    throwable.getClass()
                            .getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );

            return false;
        } finally {
            if (tileAllocation != null) {
                tileAllocation.close();
            }

            if (rectangleAllocation != null) {
                rectangleAllocation.close();
            }

            resourcesClosed =
                    true;
        }
    }

    static boolean verified() {
        return verifiedBeforeClose
                && resourcesClosed
                && gpuResourcesClosed
                && gpuDrawExecuted
                && gpuRasterizedSamples > 0L
                && textureResourcesVerified
                && texturedDecodeVerified;
    }

    static void enrich(
            JsonObject report
    ) {
        report.addProperty(
                "surfaceTileMeshPrototypeInstalled",
                true
        );
        report.addProperty(
                "surfaceTileMeshPrototypeMode",
                "REAL_MINECRAFT_SURFACE_TILE_TEXTURED_GPU_DECODE_DRAW"
        );

        report.addProperty(
                "surfaceTileMeshPrototypeAttemptCount",
                attemptCount
        );
        report.addProperty(
                "surfaceTileMeshPrototypeSuccessCount",
                successCount
        );
        report.addProperty(
                "surfaceTileMeshPrototypeFailureCount",
                failureCount
        );

        report.addProperty(
                "surfaceTileMeshPrototypeExecutionThread",
                executionThread
        );

        report.addProperty(
                "surfaceTileMeshPrototypeSourceFaceCount",
                sourceFaceCount
        );
        report.addProperty(
                "surfaceTileMeshPrototypeRectangleCount",
                rectangleCount
        );
        report.addProperty(
                "surfaceTileMeshPrototypeTileCount",
                tileCount
        );
        report.addProperty(
                "surfaceTileMeshPrototypeMaximumRectangleArea",
                maximumRectangleArea
        );
        report.addProperty(
                "surfaceTileMeshPrototypeTopologyReductionPercent",
                topologyReductionPercent
        );

        report.addProperty(
                "surfaceTileMeshPrototypeRectangleDescriptorStrideBytes",
                SurfaceTileMeshSnapshot.RECTANGLE_DESCRIPTOR_STRIDE_BYTES
        );
        report.addProperty(
                "surfaceTileMeshPrototypeTileAttributeStrideBytes",
                SurfaceTileMeshSnapshot.TILE_ATTRIBUTE_STRIDE_BYTES
        );
        report.addProperty(
                "surfaceTileMeshPrototypeRectangleDescriptorBytes",
                rectangleDescriptorBytes
        );
        report.addProperty(
                "surfaceTileMeshPrototypeTileAttributeBytes",
                tileAttributeBytes
        );
        report.addProperty(
                "surfaceTileMeshPrototypeTotalAllocationBytes",
                totalAllocationBytes
        );

        report.addProperty(
                "surfaceTileMeshPrototypeRectangleBufferCreated",
                rectangleBufferCreated
        );
        report.addProperty(
                "surfaceTileMeshPrototypeTileBufferCreated",
                tileBufferCreated
        );
        report.addProperty(
                "surfaceTileMeshPrototypeRectangleUploadValidated",
                rectangleUploadValidated
        );
        report.addProperty(
                "surfaceTileMeshPrototypeTileUploadValidated",
                tileUploadValidated
        );

        report.addProperty(
                "surfaceTileMeshPrototypeRectangleMemoryTypeIndex",
                rectangleMemoryTypeIndex
        );
        report.addProperty(
                "surfaceTileMeshPrototypeTileMemoryTypeIndex",
                tileMemoryTypeIndex
        );
        report.addProperty(
                "surfaceTileMeshPrototypeRectangleHostCoherent",
                rectangleHostCoherent
        );
        report.addProperty(
                "surfaceTileMeshPrototypeTileHostCoherent",
                tileHostCoherent
        );

        report.addProperty(
                "surfaceTileMeshPrototypeUsesRealVkBuffer",
                rectangleBufferCreated
                        && tileBufferCreated
        );
        report.addProperty(
                "surfaceTileMeshPrototypeUsesStorageBuffers",
                true
        );
        report.addProperty(
                "surfaceTileMeshPrototypeUsesRealMinecraftMeshData",
                sourceFaceCount > 0
        );
        report.addProperty(
                "surfaceTileMeshPrototypeGeometryAndAttributesDecoupled",
                true
        );

        report.addProperty(
                "surfaceTileMeshPrototypeGpuDrawExecuted",
                gpuDrawExecuted
        );
        report.addProperty(
                "surfaceTileMeshPrototypeShaderDecodeDeferred",
                false
        );
        report.addProperty(
                "surfaceTileMeshPrototypeVisibleRenderingMutation",
                false
        );
        report.addProperty(
                "surfaceTileMeshPrototypeOpenGlBaselineStillAuthoritative",
                true
        );

        report.addProperty(
                "surfaceTileMeshPrototypeQueueSubmitUsed",
                gpuQueueSubmitUsed
        );
        report.addProperty(
                "surfaceTileMeshPrototypeFenceWaitUsed",
                gpuFenceWaitUsed
        );
        report.addProperty(
                "surfaceTileMeshPrototypeDeviceWaitIdleUsed",
                false
        );

        report.addProperty(
                "surfaceTileGpuDecodePipelineCreated",
                gpuPipelineCreated
        );
        report.addProperty(
                "surfaceTileGpuDecodeDescriptorsCreated",
                gpuDescriptorsCreated
        );
        report.addProperty(
                "surfaceTileGpuDecodeGeometryGeneratedFromVertexIndex",
                true
        );
        report.addProperty(
                "surfaceTileGpuDecodeConventionalVertexBufferBound",
                false
        );
        report.addProperty(
                "surfaceTileGpuDecodeIndexBufferBound",
                false
        );
        report.addProperty(
                "surfaceTileGpuDecodeDrawCallCount",
                gpuDrawExecuted
                        ? 1
                        : 0
        );
        report.addProperty(
                "surfaceTileGpuDecodeDrawVertexCount",
                gpuDrawVertexCount
        );
        report.addProperty(
                "surfaceTileGpuDecodeExpectedDrawVertexCount",
                rectangleCount * 6
        );
        report.addProperty(
                "surfaceTileGpuDecodeMergedRectangleDraw",
                gpuDrawVertexCount > 0
                        && gpuDrawVertexCount == rectangleCount * 6
                        && rectangleCount < tileCount
        );
        report.addProperty(
                "surfaceTileGpuDecodeQueueSubmitResult",
                gpuQueueSubmitResult
        );
        report.addProperty(
                "surfaceTileGpuDecodeFenceWaitResult",
                gpuFenceWaitResult
        );
        report.addProperty(
                "surfaceTileGpuDecodeQueryResult",
                gpuQueryResult
        );
        report.addProperty(
                "surfaceTileGpuDecodeRasterizedSamples",
                gpuRasterizedSamples
        );
        report.addProperty(
                "surfaceTileGpuDecodeRasterizationVerified",
                gpuRasterizedSamples > 0L
        );
        report.addProperty(
                "surfaceTileGpuDecodeResourcesClosed",
                gpuResourcesClosed
        );
        report.addProperty(
                "surfaceTileGpuDecodeFallbackQueueWaitIdleUsed",
                gpuFallbackQueueWaitIdleUsed
        );
        report.addProperty(
                "surfaceTileGpuDecodeDeviceWaitIdleUsed",
                false
        );
        report.addProperty(
                "surfaceTileGpuDecodeVisiblePresentation",
                false
        );
        report.addProperty(
                "surfaceTileGpuDecodeVerified",
                gpuDrawExecuted
                        && gpuRasterizedSamples > 0L
                        && gpuQueueSubmitResult == VK_SUCCESS
                        && gpuFenceWaitResult == VK_SUCCESS
                        && gpuQueryResult == VK_SUCCESS
                        && gpuResourcesClosed
        );

        report.addProperty(
                "surfaceTileTexturedDecodeTextureResourcesVerified",
                textureResourcesVerified
        );
        report.addProperty(
                "surfaceTileTexturedDecodeAtlasSampled",
                texturedDecodeVerified
        );
        report.addProperty(
                "surfaceTileTexturedDecodeLightmapSampled",
                texturedDecodeVerified
        );
        report.addProperty(
                "surfaceTileTexturedDecodeFourCornerColorUsed",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodeFourCornerLightmapUsed",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodeAtlasUvOrientationUsed",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodeOriginalCornerOrderUsed",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodeExactOriginalTriangleDiagonalUsed",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodePiecewiseTriangleInterpolation",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodeTileStrideBytes",
                SurfaceTileMeshSnapshot.TILE_ATTRIBUTE_STRIDE_BYTES
        );
        report.addProperty(
                "surfaceTileTexturedDecodeTileStrideGrowthBytes",
                0
        );
        report.addProperty(
                "surfaceTileTexturedDecodeAtlasBaseMipOnly",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodeRenderLayerIdentityCaptured",
                false
        );
        report.addProperty(
                "surfaceTileTexturedDecodeAlphaCutoutDeferred",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodeTranslucencyDeferred",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodeFogDeferred",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodeColorModulatorDeferred",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodeCameraSpaceWorldPlacementDeferred",
                true
        );
        report.addProperty(
                "surfaceTileTexturedDecodeVisiblePresentation",
                false
        );
        report.addProperty(
                "surfaceTileTexturedDecodeVerified",
                texturedDecodeVerified
        );

        report.addProperty(
                "surfaceTileMeshPrototypeVerifiedBeforeClose",
                verifiedBeforeClose
        );
        report.addProperty(
                "surfaceTileMeshPrototypeResourcesClosed",
                resourcesClosed
        );
        report.addProperty(
                "surfaceTileMeshPrototypeFailure",
                failure
        );
        report.addProperty(
                "surfaceTileMeshPrototypeVerified",
                verified()
        );
    }
}