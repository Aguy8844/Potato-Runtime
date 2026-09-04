package dev.ordovicium.potato.render.lod;

/**
 * CPU-built immutable OpenGL proxy payload.
 *
 * <p>Tier 1 requires the same complete per-vertex visual payload while
 * merging compatible unit faces. Tier 2 may simplify lighting/color variation
 * but still keeps atlas UV/material identity and is reserved for smaller
 * projected section footprints.</p>
 */
public record PotatoLodBuildResult(
        int sourceQuadCount,
        int mergeableQuadCount,
        int passthroughQuadCount,
        Tier tier1,
        Tier tier2,
        long buildNanos
) {
    public boolean usable() {
        return tier1 != null
                || tier2 != null;
    }

    public record Tier(
            byte[] vertexBytes,
            byte[] indexBytes,
            int outputQuadCount,
            int mergedRectangleCount,
            int passthroughQuadCount,
            int sourceQuadCount,
            double quadReductionPercent
    ) {
        public int vertexCount() {
            return outputQuadCount * 4;
        }

        public int indexCount() {
            return outputQuadCount * 6;
        }

        public long gpuBytes() {
            return (long) vertexBytes.length
                    + indexBytes.length;
        }

        public long avoidedQuads() {
            return Math.max(
                    0L,
                    (long) sourceQuadCount
                            - outputQuadCount
            );
        }
    }
}
