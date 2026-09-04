package dev.ordovicium.potato.render.visibility;

/**
 * Horizontal world-region hierarchy used by Potato's future surface renderer.
 *
 * <p>The vertical extent is not fixed to the level size. Each cluster records
 * the actual minimum/maximum section Y observed inside its X/Z footprint.
 * This matches Minecraft terrain better than forcing cubic octree cells.</p>
 */
public enum WorldClusterLevel {
    SECTION_16(16),
    REGION_32(32),
    REGION_64(64),
    REGION_128(128),
    REGION_256(256);

    private final int blockSize;

    WorldClusterLevel(
            int blockSize
    ) {
        this.blockSize =
                blockSize;
    }

    public int blockSize() {
        return blockSize;
    }
}