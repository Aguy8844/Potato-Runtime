package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Descriptor ownership for the Potato surface-tile textured decode draw.
 *
 * <p>Bindings are deliberately local to this pipeline:
 * 0 = merged rectangle SSBO,
 * 1 = exact tile-attribute SSBO,
 * 2 = BLOCK atlas sampler,
 * 3 = 16x16 lightmap sampler.</p>
 */
final class VulkanSurfaceTileDescriptorSet
        implements AutoCloseable {

    static final int RECTANGLE_BINDING = 0;
    static final int TILE_BINDING = 1;
    static final int ATLAS_BINDING = 2;
    static final int LIGHTMAP_BINDING = 3;

    private final VkDevice device;

    private long layout = NULL;
    private long pool = NULL;
    private long set = NULL;

    private long atlasSampler = NULL;
    private long lightmapSampler = NULL;

    private VulkanSurfaceTileDescriptorSet(
            VkDevice device
    ) {
        this.device = device;
    }

    static VulkanSurfaceTileDescriptorSet create(
            VkDevice device,
            long rectangleBuffer,
            long rectangleBytes,
            long tileBuffer,
            long tileBytes,
            VulkanBlockTextureUploadPrototype textures,
            JsonObject report
    ) {
        VulkanSurfaceTileDescriptorSet result =
                new VulkanSurfaceTileDescriptorSet(
                        device
                );

        try {
            result.createResources(
                    rectangleBuffer,
                    rectangleBytes,
                    tileBuffer,
                    tileBytes,
                    textures,
                    report
            );

            return result;
        } catch (Throwable throwable) {
            result.close();
            throw throwable;
        }
    }

    long layout() {
        return layout;
    }

    long set() {
        return set;
    }

    boolean verified() {
        return layout != NULL
                && pool != NULL
                && set != NULL
                && atlasSampler != NULL
                && lightmapSampler != NULL;
    }

    private void createResources(
            long rectangleBuffer,
            long rectangleBytes,
            long tileBuffer,
            long tileBytes,
            VulkanBlockTextureUploadPrototype textures,
            JsonObject report
    ) {
        if (rectangleBuffer == NULL
                || tileBuffer == NULL
                || rectangleBytes <= 0L
                || tileBytes <= 0L
                || textures == null
                || !textures.verified()) {

            throw new VulkanProbeException(
                    "SURFACE_TILE_TEXTURED_DESCRIPTORS",
                    "Surface SSBO or BLOCK texture resources are incomplete."
            );
        }

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {

            atlasSampler =
                    createNearestSampler(
                            stack,
                            "Atlas",
                            report
                    );

            lightmapSampler =
                    createNearestSampler(
                            stack,
                            "Lightmap",
                            report
                    );

            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(
                            4,
                            stack
                    );

            bindings.get(0)
                    .binding(
                            RECTANGLE_BINDING
                    )
                    .descriptorType(
                            VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    )
                    .descriptorCount(
                            1
                    )
                    .stageFlags(
                            VK_SHADER_STAGE_VERTEX_BIT
                                    | VK_SHADER_STAGE_FRAGMENT_BIT
                    );

            bindings.get(1)
                    .binding(
                            TILE_BINDING
                    )
                    .descriptorType(
                            VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    )
                    .descriptorCount(
                            1
                    )
                    .stageFlags(
                            VK_SHADER_STAGE_FRAGMENT_BIT
                    );

            bindings.get(2)
                    .binding(
                            ATLAS_BINDING
                    )
                    .descriptorType(
                            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                    )
                    .descriptorCount(
                            1
                    )
                    .stageFlags(
                            VK_SHADER_STAGE_FRAGMENT_BIT
                    );

            bindings.get(3)
                    .binding(
                            LIGHTMAP_BINDING
                    )
                    .descriptorType(
                            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                    )
                    .descriptorCount(
                            1
                    )
                    .stageFlags(
                            VK_SHADER_STAGE_FRAGMENT_BIT
                    );

            VkDescriptorSetLayoutCreateInfo layoutInfo =
                    VkDescriptorSetLayoutCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .pBindings(
                                    bindings
                            );

            LongBuffer layoutPointer =
                    stack.mallocLong(
                            1
                    );

            int result =
                    vkCreateDescriptorSetLayout(
                            device,
                            layoutInfo,
                            null,
                            layoutPointer
                    );

            report.addProperty(
                    "surfaceTileTexturedDescriptorSetLayoutCreateResult",
                    result
            );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_SURFACE_TILE_TEXTURED_DESCRIPTOR_LAYOUT",
                        "vkCreateDescriptorSetLayout failed with VkResult "
                                + result
                );
            }

            layout =
                    layoutPointer.get(
                            0
                    );

            VkDescriptorPoolSize.Buffer poolSizes =
                    VkDescriptorPoolSize.calloc(
                            2,
                            stack
                    );

            poolSizes.get(0)
                    .type(
                            VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    )
                    .descriptorCount(
                            2
                    );

            poolSizes.get(1)
                    .type(
                            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                    )
                    .descriptorCount(
                            2
                    );

            VkDescriptorPoolCreateInfo poolInfo =
                    VkDescriptorPoolCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .maxSets(
                                    1
                            )
                            .pPoolSizes(
                                    poolSizes
                            );

            LongBuffer poolPointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkCreateDescriptorPool(
                            device,
                            poolInfo,
                            null,
                            poolPointer
                    );

            report.addProperty(
                    "surfaceTileTexturedDescriptorPoolCreateResult",
                    result
            );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_SURFACE_TILE_TEXTURED_DESCRIPTOR_POOL",
                        "vkCreateDescriptorPool failed with VkResult "
                                + result
                );
            }

            pool =
                    poolPointer.get(
                            0
                    );

            VkDescriptorSetAllocateInfo allocateInfo =
                    VkDescriptorSetAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .descriptorPool(
                                    pool
                            )
                            .pSetLayouts(
                                    stack.longs(
                                            layout
                                    )
                            );

            LongBuffer setPointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkAllocateDescriptorSets(
                            device,
                            allocateInfo,
                            setPointer
                    );

            report.addProperty(
                    "surfaceTileTexturedDescriptorSetAllocateResult",
                    result
            );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "ALLOCATE_SURFACE_TILE_TEXTURED_DESCRIPTOR_SET",
                        "vkAllocateDescriptorSets failed with VkResult "
                                + result
                );
            }

            set =
                    setPointer.get(
                            0
                    );

            VkDescriptorBufferInfo.Buffer rectangleInfo =
                    VkDescriptorBufferInfo.calloc(
                            1,
                            stack
                    );

            rectangleInfo.get(0)
                    .buffer(
                            rectangleBuffer
                    )
                    .offset(
                            0L
                    )
                    .range(
                            rectangleBytes
                    );

            VkDescriptorBufferInfo.Buffer tileInfo =
                    VkDescriptorBufferInfo.calloc(
                            1,
                            stack
                    );

            tileInfo.get(0)
                    .buffer(
                            tileBuffer
                    )
                    .offset(
                            0L
                    )
                    .range(
                            tileBytes
                    );

            VkDescriptorImageInfo.Buffer atlasInfo =
                    VkDescriptorImageInfo.calloc(
                            1,
                            stack
                    );

            atlasInfo.get(0)
                    .sampler(
                            atlasSampler
                    )
                    .imageView(
                            textures.atlasImageView()
                    )
                    .imageLayout(
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                    );

            VkDescriptorImageInfo.Buffer lightmapInfo =
                    VkDescriptorImageInfo.calloc(
                            1,
                            stack
                    );

            lightmapInfo.get(0)
                    .sampler(
                            lightmapSampler
                    )
                    .imageView(
                            textures.lightmapImageView()
                    )
                    .imageLayout(
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                    );

            VkWriteDescriptorSet.Buffer writes =
                    VkWriteDescriptorSet.calloc(
                            4,
                            stack
                    );

            writes.get(0)
                    .sType$Default()
                    .dstSet(
                            set
                    )
                    .dstBinding(
                            RECTANGLE_BINDING
                    )
                    .descriptorCount(
                            1
                    )
                    .descriptorType(
                            VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    )
                    .pBufferInfo(
                            rectangleInfo
                    );

            writes.get(1)
                    .sType$Default()
                    .dstSet(
                            set
                    )
                    .dstBinding(
                            TILE_BINDING
                    )
                    .descriptorCount(
                            1
                    )
                    .descriptorType(
                            VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    )
                    .pBufferInfo(
                            tileInfo
                    );

            writes.get(2)
                    .sType$Default()
                    .dstSet(
                            set
                    )
                    .dstBinding(
                            ATLAS_BINDING
                    )
                    .descriptorCount(
                            1
                    )
                    .descriptorType(
                            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                    )
                    .pImageInfo(
                            atlasInfo
                    );

            writes.get(3)
                    .sType$Default()
                    .dstSet(
                            set
                    )
                    .dstBinding(
                            LIGHTMAP_BINDING
                    )
                    .descriptorCount(
                            1
                    )
                    .descriptorType(
                            VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                    )
                    .pImageInfo(
                            lightmapInfo
                    );

            vkUpdateDescriptorSets(
                    device,
                    writes,
                    null
            );
        }

        if (!verified()) {
            throw new VulkanProbeException(
                    "SURFACE_TILE_TEXTURED_DESCRIPTORS",
                    "Descriptor creation returned a null Vulkan handle."
            );
        }

        report.addProperty(
                "surfaceTileTexturedRectangleDescriptorBinding",
                RECTANGLE_BINDING
        );
        report.addProperty(
                "surfaceTileTexturedTileDescriptorBinding",
                TILE_BINDING
        );
        report.addProperty(
                "surfaceTileTexturedAtlasDescriptorBinding",
                ATLAS_BINDING
        );
        report.addProperty(
                "surfaceTileTexturedLightmapDescriptorBinding",
                LIGHTMAP_BINDING
        );
    }

    private long createNearestSampler(
            MemoryStack stack,
            String label,
            JsonObject report
    ) {
        VkSamplerCreateInfo samplerInfo =
                VkSamplerCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .magFilter(
                                VK_FILTER_NEAREST
                        )
                        .minFilter(
                                VK_FILTER_NEAREST
                        )
                        .mipmapMode(
                                VK_SAMPLER_MIPMAP_MODE_NEAREST
                        )
                        .addressModeU(
                                VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
                        )
                        .addressModeV(
                                VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
                        )
                        .addressModeW(
                                VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
                        )
                        .mipLodBias(
                                0.0f
                        )
                        .anisotropyEnable(
                                false
                        )
                        .maxAnisotropy(
                                1.0f
                        )
                        .compareEnable(
                                false
                        )
                        .compareOp(
                                VK_COMPARE_OP_ALWAYS
                        )
                        .minLod(
                                0.0f
                        )
                        .maxLod(
                                0.0f
                        )
                        .borderColor(
                                VK_BORDER_COLOR_INT_OPAQUE_BLACK
                        )
                        .unnormalizedCoordinates(
                                false
                        );

        LongBuffer pointer =
                stack.mallocLong(
                        1
                );

        int result =
                vkCreateSampler(
                        device,
                        samplerInfo,
                        null,
                        pointer
                );

        report.addProperty(
                "surfaceTileTextured"
                        + label
                        + "SamplerCreateResult",
                result
        );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_SURFACE_TILE_TEXTURED_"
                            + label.toUpperCase(
                                    java.util.Locale.ROOT
                            )
                            + "_SAMPLER",
                    "vkCreateSampler failed with VkResult "
                            + result
            );
        }

        return pointer.get(
                0
        );
    }

    @Override
    public void close() {
        if (pool != NULL) {
            vkDestroyDescriptorPool(
                    device,
                    pool,
                    null
            );

            pool = NULL;
            set = NULL;
        }

        if (layout != NULL) {
            vkDestroyDescriptorSetLayout(
                    device,
                    layout,
                    null
            );

            layout = NULL;
        }

        if (lightmapSampler != NULL) {
            vkDestroySampler(
                    device,
                    lightmapSampler,
                    null
            );

            lightmapSampler = NULL;
        }

        if (atlasSampler != NULL) {
            vkDestroySampler(
                    device,
                    atlasSampler,
                    null
            );

            atlasSampler = NULL;
        }
    }
}