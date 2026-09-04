package dev.ordovicium.potato.render.backend.draw;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.nio.ByteBuffer;

/**
 * Ephemeral view of one MeshData upload.
 *
 * <p>The byte-buffer views are valid only during the synchronous dispatcher
 * callback. Sinks that need longer lifetime must explicitly copy/upload them.</p>
 */
public record DrawGeometryView(
        VertexFormat format,
        int vertexCount,
        int indexCount,
        VertexFormat.Mode mode,
        VertexFormat.IndexType indexType,
        ByteBuffer vertexBytes,
        ByteBuffer indexBytes
) {
    public static DrawGeometryView from(
            MeshData meshData
    ) {
        MeshData.DrawState state =
                meshData.drawState();

        ByteBuffer vertices =
                meshData.vertexBuffer()
                        .duplicate()
                        .asReadOnlyBuffer();

        ByteBuffer rawIndices =
                meshData.indexBuffer();

        ByteBuffer indices =
                rawIndices == null
                        ? null
                        : rawIndices
                                .duplicate()
                                .asReadOnlyBuffer();

        return new DrawGeometryView(
                state.format(),
                state.vertexCount(),
                state.indexCount(),
                state.mode(),
                state.indexType(),
                vertices,
                indices
        );
    }
}
