package dev.ordovicium.potato.render.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Render-thread-owned OpenGL proxy for one Minecraft section VertexBuffer.
 *
 * <p>The proxy deliberately reuses Minecraft's already-bound BLOCK shader,
 * atlas/lightmap textures and ChunkOffset uniform. Only the VAO/VBO/EBO are
 * substituted for the draw. Proxy installation restores the previous GL
 * bindings, and the caller immediately rebinds the authoritative vanilla
 * VertexBuffer after every visible proxy draw.</p>
 */
public final class PotatoOpenGlLodProxy
        implements AutoCloseable {

    private TierResource tier1;
    private TierResource tier2;

    private final int sourceQuadCount;

    private boolean closed;

    private PotatoOpenGlLodProxy(
            int sourceQuadCount,
            TierResource tier1,
            TierResource tier2
    ) {
        this.sourceQuadCount =
                sourceQuadCount;

        this.tier1 =
                tier1;

        this.tier2 =
                tier2;
    }

    public static PotatoOpenGlLodProxy create(
            PotatoLodBuildResult result
    ) {
        RenderSystem.assertOnRenderThread();

        if (result == null
                || !result.usable()) {
            return null;
        }

        TierResource tier1 =
                null;

        TierResource tier2 =
                null;

        try {
            boolean singleTier =
                    PotatoLodRuntime.preferSingleTierProxy(
                            result
                    );

            /*
             * Stage 2 prioritizes the far-field tier because it yields the
             * largest draw reduction per byte.
             */
            if (result.tier2() != null) {
                tier2 =
                        TierResource.create(
                                2,
                                result.tier2()
                        );
            }

            if (!singleTier
                    && result.tier1() != null) {
                tier1 =
                        TierResource.create(
                                1,
                                result.tier1()
                        );
            }

            if (tier2 == null
                    && result.tier1() != null) {
                tier1 =
                        TierResource.create(
                                1,
                                result.tier1()
                        );
            }

            if (singleTier
                    && tier2 != null
                    && result.tier1() != null) {
                PotatoLodRuntime.onSingleTierCompaction();
            }

            if (tier1 == null
                    && tier2 == null) {
                return null;
            }

            PotatoOpenGlLodProxy proxy =
                    new PotatoOpenGlLodProxy(
                            result.sourceQuadCount(),
                            tier1,
                            tier2
                    );

            PotatoLodRuntime.registerProxy(
                    proxy
            );

            return proxy;
        } catch (Throwable throwable) {
            if (tier1 != null) {
                tier1.close();
            }

            if (tier2 != null) {
                tier2.close();
            }

            PotatoLodRuntime.onProxyInstallFailure(
                    throwable
            );

            return null;
        }
    }

    /**
     * @return actual drawn tier, or 0 when the requested quality is not ready.
     */
    public int drawBest(
            int requestedTier
    ) {
        RenderSystem.assertOnRenderThread();

        if (closed
                || requestedTier <= 0) {
            return 0;
        }

        if (requestedTier >= 2
                && tier2 != null) {
            tier2.draw();

            PotatoLodRuntime.onVisibleProxyDraw(
                    2,
                    sourceQuadCount,
                    tier2.outputQuadCount
            );

            return 2;
        }

        if (tier1 != null) {
            tier1.draw();

            PotatoLodRuntime.onVisibleProxyDraw(
                    1,
                    sourceQuadCount,
                    tier1.outputQuadCount
            );

            return 1;
        }

        if (tier2 != null
                && PotatoLodRuntime.allowTier2FallbackForTier1()) {
            tier2.draw();

            PotatoLodRuntime.onTier2FallbackForTier1();

            PotatoLodRuntime.onVisibleProxyDraw(
                    2,
                    sourceQuadCount,
                    tier2.outputQuadCount
            );

            return 2;
        }

        return 0;
    }

    public boolean hasTier(
            int tier
    ) {
        if (closed) {
            return false;
        }

        if (tier >= 2) {
            return tier2 != null;
        }

        return tier1 != null;
    }

    long compactToFarTier() {
        RenderSystem.assertOnRenderThread();

        if (closed
                || tier1 == null
                || tier2 == null) {
            return 0L;
        }

        long reclaimed =
                tier1.gpuBytes;

        tier1.close();
        tier1 = null;

        return reclaimed;
    }

    public long gpuBytes() {
        long total =
                0L;

        if (tier1 != null) {
            total +=
                    tier1.gpuBytes;
        }

        if (tier2 != null) {
            total +=
                    tier2.gpuBytes;
        }

        return total;
    }

    @Override
    public void close() {
        RenderSystem.assertOnRenderThread();

        if (closed) {
            return;
        }

        closed =
                true;

        if (tier1 != null) {
            tier1.close();
            tier1 = null;
        }

        if (tier2 != null) {
            tier2.close();
            tier2 = null;
        }

        PotatoLodRuntime.unregisterProxy(
                this
        );
    }

    private static final class TierResource
            implements AutoCloseable {

        private final int tier;
        private final int vao;
        private final int vertexBuffer;
        private final int indexBuffer;
        private final int indexCount;
        private final int outputQuadCount;
        private final long gpuBytes;

        private boolean closed;

        private TierResource(
                int tier,
                int vao,
                int vertexBuffer,
                int indexBuffer,
                int indexCount,
                int outputQuadCount,
                long gpuBytes
        ) {
            this.tier =
                    tier;

            this.vao =
                    vao;

            this.vertexBuffer =
                    vertexBuffer;

            this.indexBuffer =
                    indexBuffer;

            this.indexCount =
                    indexCount;

            this.outputQuadCount =
                    outputQuadCount;

            this.gpuBytes =
                    gpuBytes;
        }

        static TierResource create(
                int tier,
                PotatoLodBuildResult.Tier data
        ) {
            RenderSystem.assertOnRenderThread();

            long gpuBytes =
                    data.gpuBytes();

            if (!PotatoLodRuntime.tryReserveProxyBytes(
                    gpuBytes
            )) {
                PotatoLodRuntime.onProxyBudgetRejected(
                        tier,
                        gpuBytes
                );

                return null;
            }

            int previousVao =
                    GL11C.glGetInteger(
                            GL30C.GL_VERTEX_ARRAY_BINDING
                    );

            int previousArrayBuffer =
                    GL11C.glGetInteger(
                            GL15C.GL_ARRAY_BUFFER_BINDING
                    );

            int previousElementBuffer =
                    GL11C.glGetInteger(
                            GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING
                    );

            int vao =
                    0;

            int vertexBuffer =
                    0;

            int indexBuffer =
                    0;

            ByteBuffer vertexUpload =
                    null;

            ByteBuffer indexUpload =
                    null;

            try {
                vao =
                        GL30C.glGenVertexArrays();

                vertexBuffer =
                        GL15C.glGenBuffers();

                indexBuffer =
                        GL15C.glGenBuffers();

                GL30C.glBindVertexArray(
                        vao
                );

                GL15C.glBindBuffer(
                        GL15C.GL_ARRAY_BUFFER,
                        vertexBuffer
                );

                vertexUpload =
                        MemoryUtil.memAlloc(
                                data.vertexBytes()
                                        .length
                        );

                vertexUpload.put(
                        data.vertexBytes()
                )
                        .flip();

                GL15C.glBufferData(
                        GL15C.GL_ARRAY_BUFFER,
                        vertexUpload,
                        GL15C.GL_STATIC_DRAW
                );

                DefaultVertexFormat.BLOCK
                        .setupBufferState();

                GL15C.glBindBuffer(
                        GL15C.GL_ELEMENT_ARRAY_BUFFER,
                        indexBuffer
                );

                indexUpload =
                        MemoryUtil.memAlloc(
                                data.indexBytes()
                                        .length
                        );

                indexUpload.put(
                        data.indexBytes()
                )
                        .flip();

                GL15C.glBufferData(
                        GL15C.GL_ELEMENT_ARRAY_BUFFER,
                        indexUpload,
                        GL15C.GL_STATIC_DRAW
                );

                GL30C.glBindVertexArray(
                        0
                );

                GL15C.glBindBuffer(
                        GL15C.GL_ARRAY_BUFFER,
                        0
                );

                PotatoLodRuntime.onProxyTierInstalled(
                        tier,
                        data.sourceQuadCount(),
                        data.outputQuadCount(),
                        data.quadReductionPercent(),
                        gpuBytes
                );

                return new TierResource(
                        tier,
                        vao,
                        vertexBuffer,
                        indexBuffer,
                        data.indexCount(),
                        data.outputQuadCount(),
                        gpuBytes
                );
            } catch (Throwable throwable) {
                if (indexBuffer != 0) {
                    GL15C.glDeleteBuffers(
                            indexBuffer
                    );
                }

                if (vertexBuffer != 0) {
                    GL15C.glDeleteBuffers(
                            vertexBuffer
                    );
                }

                if (vao != 0) {
                    GL30C.glDeleteVertexArrays(
                            vao
                    );
                }

                PotatoLodRuntime.releaseProxyBytes(
                        gpuBytes
                );

                throw throwable;
            } finally {
                GL30C.glBindVertexArray(
                        previousVao
                );

                GL15C.glBindBuffer(
                        GL15C.GL_ARRAY_BUFFER,
                        previousArrayBuffer
                );

                if (previousVao == 0) {
                    GL15C.glBindBuffer(
                            GL15C.GL_ELEMENT_ARRAY_BUFFER,
                            previousElementBuffer
                    );
                }

                if (vertexUpload != null) {
                    MemoryUtil.memFree(
                            vertexUpload
                    );
                }

                if (indexUpload != null) {
                    MemoryUtil.memFree(
                            indexUpload
                    );
                }
            }
        }

        void draw() {
            if (closed) {
                return;
            }

            GL30C.glBindVertexArray(
                    vao
            );

            GL11C.glDrawElements(
                    GL11C.GL_TRIANGLES,
                    indexCount,
                    GL11C.GL_UNSIGNED_SHORT,
                    0L
            );
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }

            closed =
                    true;

            GL15C.glDeleteBuffers(
                    indexBuffer
            );

            GL15C.glDeleteBuffers(
                    vertexBuffer
            );

            GL30C.glDeleteVertexArrays(
                    vao
            );

            PotatoLodRuntime.releaseProxyBytes(
                    gpuBytes
            );

            PotatoLodRuntime.onProxyTierClosed(
                    tier,
                    gpuBytes
            );
        }
    }
}
