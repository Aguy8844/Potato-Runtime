package dev.ordovicium.potato.render.resource;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.zip.CRC32;

/**
 * Backend-neutral owner of Minecraft BLOCK CPU resource snapshots.
 *
 * <p>Patch 040 captures resources only. It performs no OpenGL readback and
 * creates no Vulkan resources.</p>
 */
public final class BlockResourceCapture {
    private static final Object LOCK =
            new Object();

    private static BlockVertexLayoutSnapshot layout;
    private static BlockAtlasSnapshot atlas;
    private static LightmapSnapshot lightmap;

    private static long atlasCaptureAttemptCount;
    private static long atlasGeneration;

    private static long lightmapCaptureAttemptCount;
    private static long lightmapGeneration;
    private static long lightmapNoChangeCount;

    private static long failureCount;
    private static String lastFailure =
            "";

    private BlockResourceCapture() {
    }

    public static void captureBlockAtlas(
            TextureAtlas textureAtlas,
            SpriteLoader.Preparations preparations
    ) {
        try {
            ensureLayout();

            int width =
                    preparations.width();

            int height =
                    preparations.height();

            int mipLevel =
                    preparations.mipLevel();

            Map<ResourceLocation, TextureAtlasSprite> textures =
                    textureAtlas.getTextures();

            if (width <= 0
                    || height <= 0) {
                throw new IllegalStateException(
                        "Block atlas has invalid extent "
                                + width
                                + "x"
                                + height
                );
            }

            if (textures == null
                    || textures.isEmpty()) {
                throw new IllegalStateException(
                        "Block atlas texture map is empty."
                );
            }

            int byteCount =
                    rgbaByteCount(
                            width,
                            height
                    );

            byte[] rgba =
                    new byte[byteCount];

            int animatedSheetSpriteCount =
                    0;

            for (TextureAtlasSprite sprite
                    : textures.values()) {

                if (sprite == null) {
                    continue;
                }

                SpriteContents contents =
                        sprite.contents();

                if (contents == null) {
                    continue;
                }

                int spriteWidth =
                        contents.width();

                int spriteHeight =
                        contents.height();

                int atlasX =
                        sprite.getX();

                int atlasY =
                        sprite.getY();

                if (spriteWidth <= 0
                        || spriteHeight <= 0) {
                    continue;
                }

                if (atlasX < 0
                        || atlasY < 0
                        || atlasX + spriteWidth > width
                        || atlasY + spriteHeight > height) {
                    throw new IllegalStateException(
                            "Sprite lies outside stitched atlas: "
                                    + atlasX
                                    + ","
                                    + atlasY
                                    + " "
                                    + spriteWidth
                                    + "x"
                                    + spriteHeight
                                    + " in "
                                    + width
                                    + "x"
                                    + height
                    );
                }

                NativeImage original =
                        contents.getOriginalImage();

                if (original != null
                        && (original.getWidth() != spriteWidth
                        || original.getHeight() != spriteHeight)) {
                    animatedSheetSpriteCount++;
                }

                for (int y = 0;
                     y < spriteHeight;
                     y++) {

                    for (int x = 0;
                         x < spriteWidth;
                         x++) {

                        int packedAbgr =
                                sprite.getPixelRGBA(
                                        0,
                                        x,
                                        y
                                );

                        int destinationPixel =
                                (atlasY + y)
                                        * width
                                        + atlasX
                                        + x;

                        writeAbgrAsRgba(
                                rgba,
                                destinationPixel * 4,
                                packedAbgr
                        );
                    }
                }
            }

            long crc =
                    crc32(
                            rgba
                    );

            synchronized (LOCK) {
                atlasCaptureAttemptCount++;
                atlasGeneration++;

                atlas =
                        new BlockAtlasSnapshot(
                                atlasGeneration,
                                width,
                                height,
                                mipLevel,
                                textures.size(),
                                animatedSheetSpriteCount,
                                rgba,
                                crc,
                                Thread.currentThread()
                                        .getName(),
                                System.nanoTime()
                        );
            }
        } catch (Throwable throwable) {
            recordFailure(
                    "BLOCK_ATLAS_CAPTURE",
                    throwable
            );

            throw throwable;
        }
    }

    public static void captureLightmap(
            NativeImage pixels
    ) {
        try {
            ensureLayout();

            if (pixels == null) {
                throw new IllegalStateException(
                        "Lightmap NativeImage is null."
                );
            }

            int width =
                    pixels.getWidth();

            int height =
                    pixels.getHeight();

            int byteCount =
                    rgbaByteCount(
                            width,
                            height
                    );

            byte[] rgba =
                    new byte[byteCount];

            for (int y = 0;
                 y < height;
                 y++) {

                for (int x = 0;
                     x < width;
                     x++) {

                    int packedAbgr =
                            pixels.getPixelRGBA(
                                    x,
                                    y
                            );

                    int pixel =
                            y * width + x;

                    writeAbgrAsRgba(
                            rgba,
                            pixel * 4,
                            packedAbgr
                    );
                }
            }

            long crc =
                    crc32(
                            rgba
                    );

            synchronized (LOCK) {
                lightmapCaptureAttemptCount++;

                if (lightmap != null
                        && lightmap.width() == width
                        && lightmap.height() == height
                        && lightmap.crc32() == crc) {
                    lightmapNoChangeCount++;
                    return;
                }

                lightmapGeneration++;

                lightmap =
                        new LightmapSnapshot(
                                lightmapGeneration,
                                width,
                                height,
                                rgba,
                                crc,
                                Thread.currentThread()
                                        .getName(),
                                System.nanoTime()
                        );
            }
        } catch (Throwable throwable) {
            recordFailure(
                    "LIGHTMAP_CAPTURE",
                    throwable
            );

            throw throwable;
        }
    }

    public static void recordExternalFailure(
            String stage,
            Throwable throwable
    ) {
        recordFailure(
                stage,
                throwable
        );
    }

    public static BlockVertexLayoutSnapshot
    blockVertexLayout() {
        ensureLayout();

        synchronized (LOCK) {
            return layout;
        }
    }

    public static BlockAtlasSnapshot
    blockAtlasSnapshot() {
        synchronized (LOCK) {
            return atlas;
        }
    }

    public static LightmapSnapshot
    lightmapSnapshot() {
        synchronized (LOCK) {
            return lightmap;
        }
    }

    public static boolean verified() {
        ensureLayout();

        synchronized (LOCK) {
            boolean layoutVerified =
                    layout != null
                            && layout
                            .verifiedForMinecraft1211();

            boolean atlasVerified =
                    atlas != null
                            && atlas.width() > 0
                            && atlas.height() > 0
                            && atlas.spriteCount() > 0
                            && atlas.byteSize()
                            == rgbaByteCount(
                                    atlas.width(),
                                    atlas.height()
                            );

            boolean lightmapVerified =
                    lightmap != null
                            && lightmap.width() == 16
                            && lightmap.height() == 16
                            && lightmap.byteSize() == 1024;

            return layoutVerified
                    && atlasVerified
                    && lightmapVerified
                    && atlasGeneration > 0
                    && lightmapGeneration > 0
                    && failureCount == 0;
        }
    }

    public static void enrich(
            JsonObject report
    ) {
        if (report == null) {
            return;
        }

        ensureLayout();

        synchronized (LOCK) {
            report.addProperty(
                    "blockResourceCaptureInstalled",
                    true
            );

            report.addProperty(
                    "blockResourceCaptureOpenGlReadbackUsed",
                    false
            );
            report.addProperty(
                    "blockResourceCaptureCreatesVulkanResources",
                    false
            );
            report.addProperty(
                    "blockResourceCaptureRetainsMojangNativeImage",
                    false
            );
            report.addProperty(
                    "blockResourceCaptureOwnsCpuCopies",
                    true
            );
            report.addProperty(
                    "lightmapCaptureAtActualVanillaUploadBoundary",
                    true
            );
            report.addProperty(
                    "lightmapCaptureNoOpUpdateCallsSkippedBeforePixelCopy",
                    true
            );

            report.addProperty(
                    "blockResourceCaptureFailureCount",
                    failureCount
            );

            if (!lastFailure.isBlank()) {
                report.addProperty(
                        "blockResourceCaptureLastFailure",
                        lastFailure
                );
            }

            if (layout != null) {
                report.addProperty(
                        "blockVertexLayoutCaptured",
                        true
                );

                report.addProperty(
                        "blockVertexLayoutStrideBytes",
                        layout.strideBytes()
                );

                report.addProperty(
                        "blockVertexLayoutPositionOffset",
                        layout.positionOffset()
                );
                report.addProperty(
                        "blockVertexLayoutPositionType",
                        layout.positionType()
                );
                report.addProperty(
                        "blockVertexLayoutPositionBytes",
                        layout.positionBytes()
                );

                report.addProperty(
                        "blockVertexLayoutColorOffset",
                        layout.colorOffset()
                );
                report.addProperty(
                        "blockVertexLayoutColorType",
                        layout.colorType()
                );
                report.addProperty(
                        "blockVertexLayoutColorBytes",
                        layout.colorBytes()
                );

                report.addProperty(
                        "blockVertexLayoutUv0Offset",
                        layout.uv0Offset()
                );
                report.addProperty(
                        "blockVertexLayoutUv0Type",
                        layout.uv0Type()
                );
                report.addProperty(
                        "blockVertexLayoutUv0Bytes",
                        layout.uv0Bytes()
                );

                report.addProperty(
                        "blockVertexLayoutUv2Offset",
                        layout.uv2Offset()
                );
                report.addProperty(
                        "blockVertexLayoutUv2Type",
                        layout.uv2Type()
                );
                report.addProperty(
                        "blockVertexLayoutUv2Bytes",
                        layout.uv2Bytes()
                );

                report.addProperty(
                        "blockVertexLayoutNormalOffset",
                        layout.normalOffset()
                );
                report.addProperty(
                        "blockVertexLayoutNormalType",
                        layout.normalType()
                );
                report.addProperty(
                        "blockVertexLayoutNormalBytes",
                        layout.normalBytes()
                );

                report.addProperty(
                        "blockVertexLayoutTrailingPaddingBytes",
                        layout.trailingPaddingBytes()
                );

                report.addProperty(
                        "blockVertexLayoutVerified",
                        layout.verifiedForMinecraft1211()
                );
            } else {
                report.addProperty(
                        "blockVertexLayoutCaptured",
                        false
                );
                report.addProperty(
                        "blockVertexLayoutVerified",
                        false
                );
            }

            report.addProperty(
                    "blockAtlasCaptureAttemptCount",
                    atlasCaptureAttemptCount
            );
            report.addProperty(
                    "blockAtlasGeneration",
                    atlasGeneration
            );

            if (atlas != null) {
                report.addProperty(
                        "blockAtlasCaptured",
                        true
                );
                report.addProperty(
                        "blockAtlasWidth",
                        atlas.width()
                );
                report.addProperty(
                        "blockAtlasHeight",
                        atlas.height()
                );
                report.addProperty(
                        "blockAtlasDeclaredMipLevel",
                        atlas.declaredMipLevel()
                );
                report.addProperty(
                        "blockAtlasCapturedMipLevelCount",
                        1
                );
                report.addProperty(
                        "blockAtlasBaseMipOnly",
                        atlas.baseMipOnly()
                );
                report.addProperty(
                        "blockAtlasSpriteCount",
                        atlas.spriteCount()
                );
                report.addProperty(
                        "blockAtlasAnimatedSheetSpriteCount",
                        atlas.animatedSheetSpriteCount()
                );
                report.addProperty(
                        "blockAtlasRgbaBytes",
                        atlas.byteSize()
                );
                report.addProperty(
                        "blockAtlasCrc32",
                        Long.toUnsignedString(
                                atlas.crc32()
                        )
                );
                report.addProperty(
                        "blockAtlasCaptureThread",
                        atlas.captureThread()
                );
                report.addProperty(
                        "blockAtlasAnimationFramePolicy",
                        atlas.animationFramePolicy()
                );
                report.addProperty(
                        "blockAtlasPixelPacking",
                        "NativeImage_ABGR32_TO_OWNED_RGBA8"
                );
                report.addProperty(
                        "blockAtlasOpenGlReadbackUsed",
                        false
                );
            } else {
                report.addProperty(
                        "blockAtlasCaptured",
                        false
                );
            }

            report.addProperty(
                    "lightmapCaptureAttemptCount",
                    lightmapCaptureAttemptCount
            );
            report.addProperty(
                    "lightmapGeneration",
                    lightmapGeneration
            );
            report.addProperty(
                    "lightmapNoChangeCaptureCount",
                    lightmapNoChangeCount
            );

            if (lightmap != null) {
                report.addProperty(
                        "lightmapCaptured",
                        true
                );
                report.addProperty(
                        "lightmapWidth",
                        lightmap.width()
                );
                report.addProperty(
                        "lightmapHeight",
                        lightmap.height()
                );
                report.addProperty(
                        "lightmapRgbaBytes",
                        lightmap.byteSize()
                );
                report.addProperty(
                        "lightmapCrc32",
                        Long.toUnsignedString(
                                lightmap.crc32()
                        )
                );
                report.addProperty(
                        "lightmapCaptureThread",
                        lightmap.captureThread()
                );
                report.addProperty(
                        "lightmapPixelPacking",
                        "NativeImage_ABGR32_TO_OWNED_RGBA8"
                );
                report.addProperty(
                        "lightmapOpenGlReadbackUsed",
                        false
                );
            } else {
                report.addProperty(
                        "lightmapCaptured",
                        false
                );
            }

            report.addProperty(
                    "blockResourceCaptureVerified",
                    verified()
            );
        }
    }

    private static void ensureLayout() {
        synchronized (LOCK) {
            if (layout != null) {
                return;
            }

            VertexFormat format =
                    DefaultVertexFormat.BLOCK;

            VertexFormatElement position =
                    VertexFormatElement.POSITION;

            VertexFormatElement color =
                    VertexFormatElement.COLOR;

            VertexFormatElement uv0 =
                    VertexFormatElement.UV0;

            VertexFormatElement uv2 =
                    VertexFormatElement.UV2;

            VertexFormatElement normal =
                    VertexFormatElement.NORMAL;

            int normalEnd =
                    format.getOffset(
                            normal
                    )
                            + normal.byteSize();

            layout =
                    new BlockVertexLayoutSnapshot(
                            format.getVertexSize(),

                            format.getOffset(
                                    position
                            ),
                            position.type()
                                    .name(),
                            position.byteSize(),

                            format.getOffset(
                                    color
                            ),
                            color.type()
                                    .name(),
                            color.byteSize(),

                            format.getOffset(
                                    uv0
                            ),
                            uv0.type()
                                    .name(),
                            uv0.byteSize(),

                            format.getOffset(
                                    uv2
                            ),
                            uv2.type()
                                    .name(),
                            uv2.byteSize(),

                            format.getOffset(
                                    normal
                            ),
                            normal.type()
                                    .name(),
                            normal.byteSize(),

                            format.getVertexSize()
                                    - normalEnd
                    );
        }
    }

    private static int rgbaByteCount(
            int width,
            int height
    ) {
        if (width <= 0
                || height <= 0) {
            throw new IllegalArgumentException(
                    "Invalid RGBA extent "
                            + width
                            + "x"
                            + height
            );
        }

        long bytes =
                (long) width
                        * (long) height
                        * 4L;

        if (bytes > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "RGBA snapshot is too large for one Java byte[]: "
                            + bytes
                            + " bytes"
            );
        }

        return (int) bytes;
    }

    private static void writeAbgrAsRgba(
            byte[] destination,
            int offset,
            int packedAbgr
    ) {
        /*
         * NativeImage.getPixelRGBA uses Minecraft's ABGR32 packed integer
         * representation. In little-endian native memory this corresponds to
         * RGBA byte order. Store explicit RGBA bytes so Patch 041 has no
         * endianness ambiguity.
         */
        destination[offset] =
                (byte) (
                        packedAbgr
                                & 0xFF
                );

        destination[offset + 1] =
                (byte) (
                        (packedAbgr >>> 8)
                                & 0xFF
                );

        destination[offset + 2] =
                (byte) (
                        (packedAbgr >>> 16)
                                & 0xFF
                );

        destination[offset + 3] =
                (byte) (
                        (packedAbgr >>> 24)
                                & 0xFF
                );
    }

    private static long crc32(
            byte[] bytes
    ) {
        CRC32 crc =
                new CRC32();

        crc.update(
                bytes,
                0,
                bytes.length
        );

        return crc.getValue();
    }

    private static void recordFailure(
            String stage,
            Throwable throwable
    ) {
        synchronized (LOCK) {
            failureCount++;

            lastFailure =
                    stage
                            + ": "
                            + throwable.getClass()
                                    .getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            );
        }
    }
}