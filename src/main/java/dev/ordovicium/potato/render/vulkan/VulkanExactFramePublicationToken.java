package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.joml.Matrix4f;

/**
 * Exact publication token for a visible SOLID layer.
 *
 * <p>The token binds three facts that earlier experimental cutover generations
 * treated separately: the camera matrices/position, the ordered visible-section
 * set, and the exact MeshData generation resident in the DEVICE_LOCAL region
 * arena. Visible Vulkan ownership must never be armed unless one token proves
 * all three for the same layer.</p>
 *
 * <p>Normal release gameplay only captures the cheap camera token. The visible
 * set and per-section residency proof are evaluated only inside the existing
 * developer-only visible-Vulkan preflight, so Patch 085 does not add a
 * full-section scan to ordinary OpenGL gameplay.</p>
 */
public final class VulkanExactFramePublicationToken {
    private static final long FNV_OFFSET =
            0xcbf29ce484222325L;
    private static final long FNV_PRIME =
            0x100000001b3L;

    private static long nextFrameSequence;
    private static long activeFrameSequence;
    private static long activeCameraFingerprint;
    private static long activeVisibilityFingerprint;
    private static int activeVisibleCandidateCount;
    private static int activeGenerationCheckCount;
    private static int activeGenerationMatchCount;
    private static int activeGenerationMismatchCount;
    private static boolean active;
    private static boolean visibilitySealed;

    private static long cameraSnapshotCount;
    private static long visibilitySealCount;
    private static long generationCheckCount;
    private static long generationMatchCount;
    private static long generationMismatchCount;
    private static long readyCommitProofCount;
    private static long rejectedCommitProofCount;
    private static long visibleOwnershipQueuedCount;
    private static long completedLayerCount;
    private static long abandonedLayerCount;
    private static long lastReadyFrameSequence;
    private static long lastCompletedFrameSequence;
    private static long lastCameraFingerprint;
    private static long lastVisibilityFingerprint;
    private static int lastVisibleCandidateCount;

    private VulkanExactFramePublicationToken() {
    }

    public static void beginSolidLayer(
            Matrix4f modelView,
            Matrix4f projection,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        if (active) {
            abandonedLayerCount++;
        }

        active = true;
        visibilitySealed = false;
        activeVisibleCandidateCount = 0;
        activeGenerationCheckCount = 0;
        activeGenerationMatchCount = 0;
        activeGenerationMismatchCount = 0;
        activeVisibilityFingerprint = 0L;

        activeFrameSequence =
                ++nextFrameSequence;

        activeCameraFingerprint =
                cameraFingerprint(
                        modelView,
                        projection,
                        cameraX,
                        cameraY,
                        cameraZ
                );

        cameraSnapshotCount++;
        lastCameraFingerprint =
                activeCameraFingerprint;
    }

    public static long currentFrameSequence() {
        return active
                ? activeFrameSequence
                : 0L;
    }

    public static long currentCameraFingerprint() {
        return active
                ? activeCameraFingerprint
                : 0L;
    }

    public static long lastCompletedFrameSequence() {
        return lastCompletedFrameSequence;
    }

    public static long visibilitySeed() {
        return FNV_OFFSET;
    }

    public static long mixVisibleSection(
            long fingerprint,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
        long mixed = fingerprint;
        mixed = mix(mixed, sectionX);
        mixed = mix(mixed, sectionY);
        mixed = mix(mixed, sectionZ);
        return mixed;
    }

    public static void sealVisibility(
            long fingerprint,
            int candidateCount
    ) {
        if (!active) {
            return;
        }

        activeVisibilityFingerprint =
                fingerprint;
        activeVisibleCandidateCount =
                Math.max(0, candidateCount);
        visibilitySealed = true;
        visibilitySealCount++;

        lastVisibilityFingerprint =
                fingerprint;
        lastVisibleCandidateCount =
                activeVisibleCandidateCount;
    }

    public static boolean observeMeshGeneration(
            long currentMeshGeneration,
            long completedResidentGeneration
    ) {
        if (!active) {
            return false;
        }

        activeGenerationCheckCount++;
        generationCheckCount++;

        boolean matches =
                currentMeshGeneration > 0L
                        && completedResidentGeneration
                        == currentMeshGeneration;

        if (matches) {
            activeGenerationMatchCount++;
            generationMatchCount++;
        } else {
            activeGenerationMismatchCount++;
            generationMismatchCount++;
        }

        return matches;
    }

    public static boolean readyForVisibleCommit(
            int expectedCandidateCount
    ) {
        boolean ready =
                active
                        && visibilitySealed
                        && expectedCandidateCount > 0
                        && activeVisibleCandidateCount
                        == expectedCandidateCount
                        && activeGenerationCheckCount
                        == expectedCandidateCount
                        && activeGenerationMatchCount
                        == expectedCandidateCount
                        && activeGenerationMismatchCount == 0;

        if (ready) {
            readyCommitProofCount++;
            lastReadyFrameSequence =
                    activeFrameSequence;
        } else {
            rejectedCommitProofCount++;
        }

        return ready;
    }

    public static void onVisibleOwnershipQueued() {
        if (active) {
            visibleOwnershipQueuedCount++;
        }
    }

    public static void endSolidLayer() {
        if (!active) {
            return;
        }

        completedLayerCount++;
        lastCompletedFrameSequence =
                activeFrameSequence;
        active = false;
        visibilitySealed = false;
        activeVisibleCandidateCount = 0;
        activeGenerationCheckCount = 0;
        activeGenerationMatchCount = 0;
        activeGenerationMismatchCount = 0;
        activeVisibilityFingerprint = 0L;
    }

    public static void enrich(JsonObject report) {
        report.addProperty(
                "vulkanExactFrameTokenInstalled",
                true
        );
        report.addProperty(
                "vulkanExactFrameTokenMode",
                "CAMERA_VISIBLE_SET_EXACT_MESH_GENERATION"
        );
        report.addProperty(
                "vulkanExactFrameTokenCameraSnapshotCount",
                cameraSnapshotCount
        );
        report.addProperty(
                "vulkanExactFrameTokenVisibilitySealCount",
                visibilitySealCount
        );
        report.addProperty(
                "vulkanExactFrameTokenGenerationCheckCount",
                generationCheckCount
        );
        report.addProperty(
                "vulkanExactFrameTokenGenerationMatchCount",
                generationMatchCount
        );
        report.addProperty(
                "vulkanExactFrameTokenGenerationMismatchCount",
                generationMismatchCount
        );
        report.addProperty(
                "vulkanExactFrameTokenReadyCommitProofCount",
                readyCommitProofCount
        );
        report.addProperty(
                "vulkanExactFrameTokenRejectedCommitProofCount",
                rejectedCommitProofCount
        );
        report.addProperty(
                "vulkanExactFrameTokenVisibleOwnershipQueuedCount",
                visibleOwnershipQueuedCount
        );
        report.addProperty(
                "vulkanExactFrameTokenCompletedLayerCount",
                completedLayerCount
        );
        report.addProperty(
                "vulkanExactFrameTokenAbandonedLayerCount",
                abandonedLayerCount
        );
        report.addProperty(
                "vulkanExactFrameTokenLastReadyFrameSequence",
                lastReadyFrameSequence
        );
        report.addProperty(
                "vulkanExactFrameTokenLastCompletedFrameSequence",
                lastCompletedFrameSequence
        );
        report.addProperty(
                "vulkanExactFrameTokenLastCameraFingerprint",
                Long.toUnsignedString(lastCameraFingerprint)
        );
        report.addProperty(
                "vulkanExactFrameTokenLastVisibilityFingerprint",
                Long.toUnsignedString(lastVisibilityFingerprint)
        );
        report.addProperty(
                "vulkanExactFrameTokenLastVisibleCandidateCount",
                lastVisibleCandidateCount
        );
        report.addProperty(
                "vulkanExactFrameTokenNormalReleaseFullVisibleScan",
                false
        );
        report.addProperty(
                "vulkanExactFrameTokenVisibleOwnership",
                visibleOwnershipQueuedCount > 0
        );
        report.addProperty(
                "vulkanExactFrameTokenVerified",
                cameraSnapshotCount > 0
                        && completedLayerCount > 0
                        && abandonedLayerCount == 0
        );
        report.addProperty(
                "vulkanExactFrameTokenNextMilestone",
                "POTATO_ENGINE_GPU_INDIRECT_SECTION_DRAW"
        );

        VulkanSurfaceClusterVisibility.enrich(
                report
        );
    }

    private static long cameraFingerprint(
            Matrix4f modelView,
            Matrix4f projection,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        long fingerprint = FNV_OFFSET;

        fingerprint =
                mixMatrix(
                        fingerprint,
                        modelView
                );

        fingerprint =
                mixMatrix(
                        fingerprint,
                        projection
                );

        fingerprint =
                mix(
                        fingerprint,
                        Double.doubleToRawLongBits(cameraX)
                );
        fingerprint =
                mix(
                        fingerprint,
                        Double.doubleToRawLongBits(cameraY)
                );
        fingerprint =
                mix(
                        fingerprint,
                        Double.doubleToRawLongBits(cameraZ)
                );

        return fingerprint;
    }

    private static long mixMatrix(
            long fingerprint,
            Matrix4f matrix
    ) {
        if (matrix == null) {
            return mix(
                    fingerprint,
                    0x7ff8000000000000L
            );
        }

        long mixed = fingerprint;
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m00()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m01()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m02()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m03()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m10()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m11()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m12()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m13()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m20()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m21()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m22()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m23()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m30()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m31()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m32()));
        mixed = mix(mixed, Float.floatToRawIntBits(matrix.m33()));
        return mixed;
    }

    private static long mix(
            long fingerprint,
            long value
    ) {
        long mixed = fingerprint;

        mixed ^= value;
        mixed *= FNV_PRIME;
        mixed ^= (value >>> 32);
        mixed *= FNV_PRIME;

        return mixed;
    }
}
