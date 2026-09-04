package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Descriptor resources for the first textured Minecraft BLOCK Vulkan draw.
 *
 * <p>Binding 0 maps Potato's block atlas (vanilla Sampler0 semantics).
 * Binding 1 maps Potato's 16x16 lightmap (vanilla Sampler2 semantics).</p>
 */
final class VulkanBlockTextureDescriptorSet
        implements AutoCloseable {

    static final int ATLAS_BINDING =
            0;

    static final int LIGHTMAP_BINDING =
            1;

    private final VkDevice device;
    private final JsonObject report;

    private long descriptorSetLayout = NULL;
    private long descriptorPool = NULL;
    private long descriptorSet = NULL;

    private long atlasSampler = NULL;
    private long lightmapSampler = NULL;

    private boolean created;
    private boolean verifiedBeforeClose;
    private boolean teardownVerified;
    private boolean closed;

    VulkanBlockTextureDescriptorSet(
            VkDevice device,
            JsonObject report
    ) {
        this.device = device;
        this.report = report;
    }

    synchronized void ensureCreated(
            VulkanBlockTextureUploadPrototype textures
    ) {
        if (created
                || closed) {
            return;
        }

        if (textures == null
                || !textures.verified()) {
            throw new VulkanProbeException(
                    "TEXTURED_SECTION_DESCRIPTORS",
                    "BLOCK texture VkImages are not verified."
            );
        }

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {

            atlasSampler =
                    createNearestSampler(
                            stack,
                            "ATLAS"
                    );

            lightmapSampler =
                    createNearestSampler(
                            stack,
                            "LIGHTMAP"
                    );

            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.calloc(
                            2,
                            stack
                    );

            bindings.get(0)
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

            bindings.get(1)
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
                            VK_SHADER_STAGE_VERTEX_BIT
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
                    "texturedSectionDescriptorSetLayoutCreateResult",
                    result
            );

            if (result
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_TEXTURED_SECTION_DESCRIPTOR_SET_LAYOUT",
                        "vkCreateDescriptorSetLayout failed with VkResult "
                                + result
                );
            }

            descriptorSetLayout =
                    layoutPointer.get(
                            0
                    );

            VkDescriptorPoolSize.Buffer poolSizes =
                    VkDescriptorPoolSize.calloc(
                            1,
                            stack
                    );

            poolSizes.get(0)
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
                    "texturedSectionDescriptorPoolCreateResult",
                    result
            );

            if (result
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_TEXTURED_SECTION_DESCRIPTOR_POOL",
                        "vkCreateDescriptorPool failed with VkResult "
                                + result
                );
            }

            descriptorPool =
                    poolPointer.get(
                            0
                    );

            VkDescriptorSetAllocateInfo allocateInfo =
                    VkDescriptorSetAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .descriptorPool(
                                    descriptorPool
                            )
                            .pSetLayouts(
                                    stack.longs(
                                            descriptorSetLayout
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
                    "texturedSectionDescriptorSetAllocateResult",
                    result
            );

            if (result
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "ALLOCATE_TEXTURED_SECTION_DESCRIPTOR_SET",
                        "vkAllocateDescriptorSets failed with VkResult "
                                + result
                );
            }

            descriptorSet =
                    setPointer.get(
                            0
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
                            2,
                            stack
                    );

            writes.get(0)
                    .sType$Default()
                    .dstSet(
                            descriptorSet
                    )
                    .dstBinding(
                            ATLAS_BINDING
                    )
                    .dstArrayElement(
                            0
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

            writes.get(1)
                    .sType$Default()
                    .dstSet(
                            descriptorSet
                    )
                    .dstBinding(
                            LIGHTMAP_BINDING
                    )
                    .dstArrayElement(
                            0
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

        created =
                descriptorSetLayout != NULL
                        && descriptorPool != NULL
                        && descriptorSet != NULL
                        && atlasSampler != NULL
                        && lightmapSampler != NULL;

        enrich();

        if (!created) {
            throw new VulkanProbeException(
                    "TEXTURED_SECTION_DESCRIPTORS",
                    "Descriptor creation completed with NULL resources."
            );
        }

        verifiedBeforeClose =
                liveVerified();
    }

    synchronized boolean verified() {
        return verifiedBeforeClose
                || liveVerified();
    }

    private boolean liveVerified() {
        return created
                && !closed
                && descriptorSetLayout != NULL
                && descriptorPool != NULL
                && descriptorSet != NULL
                && atlasSampler != NULL
                && lightmapSampler != NULL;
    }

    synchronized long descriptorSetLayout() {
        return descriptorSetLayout;
    }

    synchronized long descriptorSet() {
        return descriptorSet;
    }

    synchronized void enrich() {
        report.addProperty(
                "texturedSectionDescriptorResourcesCreated",
                created
        );
        report.addProperty(
                "texturedSectionDescriptorResourcesVerified",
                verified()
        );
        report.addProperty(
                "texturedSectionDescriptorResourcesVerifiedBeforeClose",
                verifiedBeforeClose
                        || liveVerified()
        );
        report.addProperty(
                "texturedSectionDescriptorResourcesClosed",
                closed
        );
        report.addProperty(
                "texturedSectionDescriptorTeardownVerified",
                teardownVerified
        );
        report.addProperty(
                "texturedSectionDescriptorSetLayoutNonZero",
                descriptorSetLayout != NULL
        );
        report.addProperty(
                "texturedSectionDescriptorPoolNonZero",
                descriptorPool != NULL
        );
        report.addProperty(
                "texturedSectionDescriptorSetNonZero",
                descriptorSet != NULL
        );
        report.addProperty(
                "texturedSectionAtlasSamplerNonZero",
                atlasSampler != NULL
        );
        report.addProperty(
                "texturedSectionLightmapSamplerNonZero",
                lightmapSampler != NULL
        );
        report.addProperty(
                "texturedSectionAtlasDescriptorBinding",
                ATLAS_BINDING
        );
        report.addProperty(
                "texturedSectionLightmapDescriptorBinding",
                LIGHTMAP_BINDING
        );
        report.addProperty(
                "texturedSectionDescriptorType",
                "VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER"
        );
        report.addProperty(
                "texturedSectionAtlasSamplerFilter",
                "NEAREST_BASE_MIP_ONLY"
        );
        report.addProperty(
                "texturedSectionLightmapSamplerFilter",
                "NEAREST_16X16"
        );
    }

    private long createNearestSampler(
            MemoryStack stack,
            String label
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
                "texturedSection"
                        + label
                        + "SamplerCreateResult",
                result
        );

        if (result
                != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_TEXTURED_SECTION_"
                            + label
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
    public synchronized void close() {
        if (closed) {
            return;
        }

        verifiedBeforeClose =
                verifiedBeforeClose
                        || liveVerified();

        closed = true;

        if (descriptorPool != NULL) {
            vkDestroyDescriptorPool(
                    device,
                    descriptorPool,
                    null
            );

            descriptorPool = NULL;
            descriptorSet = NULL;
        }

        if (descriptorSetLayout != NULL) {
            vkDestroyDescriptorSetLayout(
                    device,
                    descriptorSetLayout,
                    null
            );

            descriptorSetLayout = NULL;
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

        teardownVerified =
                descriptorSetLayout == NULL
                        && descriptorPool == NULL
                        && descriptorSet == NULL
                        && atlasSampler == NULL
                        && lightmapSampler == NULL;
    }
}
