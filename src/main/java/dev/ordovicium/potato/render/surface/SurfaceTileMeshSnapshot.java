package dev.ordovicium.potato.render.surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Immutable CPU snapshot of one exact section-local Potato surface mesh.
 *
 * <p>The topology stream contains one fixed-size rectangle descriptor per
 * merged surface rectangle. The tile stream contains one fixed-size exact
 * attribute record for every original 1x1 block face represented by those
 * rectangles.</p>
 *
 * <p>This is deliberately backend-neutral. Vulkan consumes the snapshot
 * through the surface prototype dispatcher.</p>
 */
public final class SurfaceTileMeshSnapshot {

    public static final int RECTANGLE_DESCRIPTOR_STRIDE_BYTES =
            8 * Integer.BYTES;

    public static final int TILE_ATTRIBUTE_STRIDE_BYTES =
            16 * Integer.BYTES;

    private final ByteBuffer rectangleDescriptors;
    private final ByteBuffer tileAttributes;

    private final int sourceFaceCount;
    private final int rectangleCount;
    private final int tileCount;
    private final int maximumRectangleArea;

    public SurfaceTileMeshSnapshot(
            ByteBuffer rectangleDescriptors,
            ByteBuffer tileAttributes,
            int sourceFaceCount,
            int rectangleCount,
            int tileCount,
            int maximumRectangleArea
    ) {
        if (rectangleDescriptors == null
                || tileAttributes == null) {

            throw new IllegalArgumentException(
                    "Surface tile snapshot buffers must not be null."
            );
        }

        if (!rectangleDescriptors.isDirect()
                || !tileAttributes.isDirect()) {

            throw new IllegalArgumentException(
                    "Surface tile snapshot buffers must be direct."
            );
        }

        if (sourceFaceCount <= 0
                || rectangleCount <= 0
                || tileCount <= 0) {

            throw new IllegalArgumentException(
                    "Surface tile snapshot counts must be positive."
            );
        }

        this.rectangleDescriptors =
                rectangleDescriptors
                        .asReadOnlyBuffer()
                        .order(
                                ByteOrder.nativeOrder()
                        );

        this.tileAttributes =
                tileAttributes
                        .asReadOnlyBuffer()
                        .order(
                                ByteOrder.nativeOrder()
                        );

        this.sourceFaceCount =
                sourceFaceCount;

        this.rectangleCount =
                rectangleCount;

        this.tileCount =
                tileCount;

        this.maximumRectangleArea =
                maximumRectangleArea;
    }

    public ByteBuffer rectangleDescriptors() {
        return rectangleDescriptors
                .duplicate()
                .order(
                        ByteOrder.nativeOrder()
                );
    }

    public ByteBuffer tileAttributes() {
        return tileAttributes
                .duplicate()
                .order(
                        ByteOrder.nativeOrder()
                );
    }

    public int sourceFaceCount() {
        return sourceFaceCount;
    }

    public int rectangleCount() {
        return rectangleCount;
    }

    public int tileCount() {
        return tileCount;
    }

    public int maximumRectangleArea() {
        return maximumRectangleArea;
    }

    public int rectangleDescriptorBytes() {
        return rectangleDescriptors
                .remaining();
    }

    public int tileAttributeBytes() {
        return tileAttributes
                .remaining();
    }

    public double topologyReductionPercent() {
        return sourceFaceCount == 0
                ? 0.0
                : (
                sourceFaceCount
                        - rectangleCount
        )
                * 100.0
                / sourceFaceCount;
    }
}
