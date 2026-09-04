package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.EnumSet;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Gate-10 Stage 4: hidden raster-pipeline proof consuming Stage-097's real
 * DEVICE_LOCAL dynamic-state packet buffer.
 *
 * <p>One private 64x64 color/depth target and four profile-matched Vulkan
 * graphics pipelines prove ENTITY / PARTICLE / HUD / SCREEN without touching
 * Minecraft's visible attachments. The vertex shader reads the exact 32-byte
 * packet for the offered domain from the DEVICE_LOCAL storage buffer and moves
 * the canary triangle outside the viewport when magic/domain/extent/profile
 * fields do not match. Positive occlusion samples therefore prove that the
 * raster stage consumed the packet written by vkCmdUpdateBuffer.</p>
 *
 * <p>Gameplay completion is strictly nonblocking: vkGetFenceStatus only. A
 * finite vkWaitForFences is reserved for close() so native resources are never
 * destroyed while in flight.</p>
 */
final class VulkanGate10DynamicRasterPrecommit
        implements AutoCloseable {

    private static final int TARGET_WIDTH =
            64;

    private static final int TARGET_HEIGHT =
            64;

    private static final int COLOR_FORMAT =
            VK_FORMAT_R8G8B8A8_UNORM;

    private static final int DEPTH_FORMAT =
            VK_FORMAT_D32_SFLOAT;

    private static final long STATE_BUFFER_BYTES =
            128L;

    private static final int PUSH_CONSTANT_BYTES =
            Integer.BYTES;

    private static final String VERTEX_SHADER =
            "assets/potato_runtime/shaders/vulkan/gate10_dynamic_raster_precommit.vert";

    private static final String FRAGMENT_SHADER =
            "assets/potato_runtime/shaders/vulkan/gate10_dynamic_raster_precommit.frag";

    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final VkQueue graphicsQueue;
    private final int graphicsQueueFamilyIndex;
    private final long stateBuffer;
    private final JsonObject report;

    private final EnumSet<VulkanGate10DynamicOwnershipContract.Domain>
            submittedDomains =
            EnumSet.noneOf(
                    VulkanGate10DynamicOwnershipContract.Domain.class
            );

    private final EnumSet<VulkanGate10DynamicOwnershipContract.Domain>
            completedDomains =
            EnumSet.noneOf(
                    VulkanGate10DynamicOwnershipContract.Domain.class
            );

    private final long[] pipelines =
            new long[
                    VulkanGate10DynamicOwnershipContract.Domain
                            .values().length
                    ];

    private long colorImage =
            NULL;

    private long colorMemory =
            NULL;

    private long colorView =
            NULL;

    private long depthImage =
            NULL;

    private long depthMemory =
            NULL;

    private long depthView =
            NULL;

    private long descriptorSetLayout =
            NULL;

    private long descriptorPool =
            NULL;

    private long descriptorSet =
            NULL;

    private long pipelineLayout =
            NULL;

    private long commandPool =
            NULL;

    private VkCommandBuffer commandBuffer;

    private long fence =
            NULL;

    private long queryPool =
            NULL;

    private boolean initialized;
    private boolean targetLayoutsInitialized;
    private boolean privateTargetsEverCreated;
    private boolean stateBufferDescriptorEverBound;
    private int pipelineEverCreatedCount;
    private boolean inFlight;
    private boolean disabledAfterFailure;
    private boolean proofRetired;
    private boolean closed;

    private VulkanGate10DynamicOwnershipContract.Domain inFlightDomain;

    private int colorMemoryTypeIndex =
            -1;

    private int depthMemoryTypeIndex =
            -1;

    private long offerCount;
    private long duplicateDomainSkipCount;
    private long busySkipCount;
    private long submissionCount;
    private long completionCount;
    private long fencePollCount;
    private long fenceNotReadyCount;
    private long queryReadCount;
    private long totalRasterizedSamples;
    private long lastRasterizedSamples;
    private long failureCount;
    private long shutdownFenceWaitCount;

    private String lastFailure =
            "";

    VulkanGate10DynamicRasterPrecommit(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            VkQueue graphicsQueue,
            int graphicsQueueFamilyIndex,
            long stateBuffer,
            JsonObject report
    ) {
        this.device =
                device;
        this.physicalDevice =
                physicalDevice;
        this.graphicsQueue =
                graphicsQueue;
        this.graphicsQueueFamilyIndex =
                graphicsQueueFamilyIndex;
        this.stateBuffer =
                stateBuffer;
        this.report =
                report;

        enrich(
                report
        );
    }

    synchronized void offer(
            VulkanGate10DynamicOwnershipContract.Domain domain
    ) {
        offerCount++;

        if (closed
                || disabledAfterFailure
                || proofRetired
                || domain == null) {
            enrich(
                    report
            );
            return;
        }

        if (!"Render thread".equals(
                Thread.currentThread().getName()
        )) {
            fail(
                    new VulkanProbeException(
                            "GATE10_RASTER_THREAD",
                            "Dynamic raster precommit was offered off the render thread."
                    )
            );
            return;
        }

        try {
            pollCompletion();

            if (closed
                    || disabledAfterFailure
                    || proofRetired) {
                enrich(
                        report
                );
                return;
            }

            if (inFlight) {
                busySkipCount++;
                enrich(
                        report
                );
                return;
            }

            if (completedDomains.contains(
                    domain
            ) || submittedDomains.contains(
                    domain
            )) {
                duplicateDomainSkipCount++;
                enrich(
                        report
                );
                return;
            }

            ensureInitialized();
            submit(
                    domain
            );
        } catch (Throwable throwable) {
            fail(
                    throwable
            );
        }

        enrich(
                report
        );
    }

    synchronized boolean proofRetired() {
        return proofRetired;
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }

        if (device == null
                || physicalDevice == null
                || graphicsQueue == null
                || graphicsQueueFamilyIndex < 0
                || stateBuffer == NULL) {
            throw new VulkanProbeException(
                    "GATE10_RASTER_INPUT",
                    "Dynamic raster precommit prerequisites are incomplete."
            );
        }

        createPrivateTargets();
        createDescriptorState();
        createPipelineState();
        createCommandState();

        initialized =
                true;

        enrich(
                report
        );
    }

    private void createPrivateTargets() {
        ImageAllocation color =
                createImage(
                        COLOR_FORMAT,
                        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                                | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                        VK_IMAGE_ASPECT_COLOR_BIT,
                        "COLOR"
                );

        colorImage =
                color.image();
        colorMemory =
                color.memory();
        colorView =
                color.view();
        colorMemoryTypeIndex =
                color.memoryTypeIndex();

        ImageAllocation depth =
                createImage(
                        DEPTH_FORMAT,
                        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                                | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                        VK_IMAGE_ASPECT_DEPTH_BIT,
                        "DEPTH"
                );

        depthImage =
                depth.image();
        depthMemory =
                depth.memory();
        depthView =
                depth.view();
        depthMemoryTypeIndex =
                depth.memoryTypeIndex();

        privateTargetsEverCreated =
                colorImage != NULL
                        && colorMemory != NULL
                        && colorView != NULL
                        && depthImage != NULL
                        && depthMemory != NULL
                        && depthView != NULL;
    }

    private ImageAllocation createImage(
            int format,
            int usage,
            int aspectMask,
            String label
    ) {
        long image =
                NULL;

        long memory =
                NULL;

        long view =
                NULL;

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo =
                    VkImageCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .imageType(
                                    VK_IMAGE_TYPE_2D
                            )
                            .format(
                                    format
                            )
                            .mipLevels(
                                    1
                            )
                            .arrayLayers(
                                    1
                            )
                            .samples(
                                    VK_SAMPLE_COUNT_1_BIT
                            )
                            .tiling(
                                    VK_IMAGE_TILING_OPTIMAL
                            )
                            .usage(
                                    usage
                            )
                            .sharingMode(
                                    VK_SHARING_MODE_EXCLUSIVE
                            )
                            .initialLayout(
                                    VK_IMAGE_LAYOUT_UNDEFINED
                            );

            imageInfo.extent()
                    .width(
                            TARGET_WIDTH
                    )
                    .height(
                            TARGET_HEIGHT
                    )
                    .depth(
                            1
                    );

            LongBuffer imagePointer =
                    stack.mallocLong(
                            1
                    );

            int result =
                    vkCreateImage(
                            device,
                            imageInfo,
                            null,
                            imagePointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_CREATE_" + label + "_IMAGE",
                        "vkCreateImage failed with VkResult "
                                + result
                );
            }

            image =
                    imagePointer.get(
                            0
                    );

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.malloc(
                            stack
                    );

            vkGetImageMemoryRequirements(
                    device,
                    image,
                    requirements
            );

            int memoryTypeIndex =
                    findMemoryType(
                            requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                    );

            VkMemoryAllocateInfo allocation =
                    VkMemoryAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .allocationSize(
                                    requirements.size()
                            )
                            .memoryTypeIndex(
                                    memoryTypeIndex
                            );

            LongBuffer memoryPointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkAllocateMemory(
                            device,
                            allocation,
                            null,
                            memoryPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_ALLOCATE_" + label + "_MEMORY",
                        "vkAllocateMemory failed with VkResult "
                                + result
                );
            }

            memory =
                    memoryPointer.get(
                            0
                    );

            result =
                    vkBindImageMemory(
                            device,
                            image,
                            memory,
                            0L
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_BIND_" + label + "_MEMORY",
                        "vkBindImageMemory failed with VkResult "
                                + result
                );
            }

            VkImageViewCreateInfo viewInfo =
                    VkImageViewCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .image(
                                    image
                            )
                            .viewType(
                                    VK_IMAGE_VIEW_TYPE_2D
                            )
                            .format(
                                    format
                            );

            viewInfo.subresourceRange()
                    .aspectMask(
                            aspectMask
                    )
                    .baseMipLevel(
                            0
                    )
                    .levelCount(
                            1
                    )
                    .baseArrayLayer(
                            0
                    )
                    .layerCount(
                            1
                    );

            LongBuffer viewPointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkCreateImageView(
                            device,
                            viewInfo,
                            null,
                            viewPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_CREATE_" + label + "_VIEW",
                        "vkCreateImageView failed with VkResult "
                                + result
                );
            }

            view =
                    viewPointer.get(
                            0
                    );

            return new ImageAllocation(
                    image,
                    memory,
                    view,
                    memoryTypeIndex
            );
        } catch (Throwable throwable) {
            if (view != NULL) {
                vkDestroyImageView(
                        device,
                        view,
                        null
                );
            }

            if (image != NULL) {
                vkDestroyImage(
                        device,
                        image,
                        null
                );
            }

            if (memory != NULL) {
                vkFreeMemory(
                        device,
                        memory,
                        null
                );
            }

            throw throwable;
        }
    }

    private int findMemoryType(
            int memoryTypeBits,
            int requiredFlags
    ) {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties properties =
                    VkPhysicalDeviceMemoryProperties.malloc(
                            stack
                    );

            vkGetPhysicalDeviceMemoryProperties(
                    physicalDevice,
                    properties
            );

            for (int index = 0;
                 index < properties.memoryTypeCount();
                 index++) {
                boolean supported =
                        (memoryTypeBits
                                & (1 << index))
                                != 0;

                int flags =
                        properties.memoryTypes(
                                index
                        ).propertyFlags();

                if (supported
                        && (flags & requiredFlags)
                        == requiredFlags) {
                    return index;
                }
            }
        }

        throw new VulkanProbeException(
                "GATE10_RASTER_FIND_MEMORY_TYPE",
                "No DEVICE_LOCAL memory type is available."
        );
    }

    private void createDescriptorState() {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binding =
                    VkDescriptorSetLayoutBinding.calloc(
                            1,
                            stack
                    );

            binding.get(
                    0
            )
                    .binding(
                            0
                    )
                    .descriptorType(
                            VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
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
                                    binding
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

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_DESCRIPTOR_LAYOUT",
                        "vkCreateDescriptorSetLayout failed with VkResult "
                                + result
                );
            }

            descriptorSetLayout =
                    layoutPointer.get(
                            0
                    );

            VkDescriptorPoolSize.Buffer poolSize =
                    VkDescriptorPoolSize.calloc(
                            1,
                            stack
                    );

            poolSize.get(
                    0
            )
                    .type(
                            VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    )
                    .descriptorCount(
                            1
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
                                    poolSize
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

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_DESCRIPTOR_POOL",
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

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_DESCRIPTOR_SET",
                        "vkAllocateDescriptorSets failed with VkResult "
                                + result
                );
            }

            descriptorSet =
                    setPointer.get(
                            0
                    );

            VkDescriptorBufferInfo.Buffer stateInfo =
                    VkDescriptorBufferInfo.calloc(
                            1,
                            stack
                    );

            stateInfo.get(
                    0
            )
                    .buffer(
                            stateBuffer
                    )
                    .offset(
                            0L
                    )
                    .range(
                            STATE_BUFFER_BYTES
                    );

            VkWriteDescriptorSet.Buffer write =
                    VkWriteDescriptorSet.calloc(
                            1,
                            stack
                    );

            write.get(
                    0
            )
                    .sType$Default()
                    .dstSet(
                            descriptorSet
                    )
                    .dstBinding(
                            0
                    )
                    .descriptorCount(
                            1
                    )
                    .descriptorType(
                            VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    )
                    .pBufferInfo(
                            stateInfo
                    );

            vkUpdateDescriptorSets(
                    device,
                    write,
                    null
            );

            stateBufferDescriptorEverBound =
                    descriptorSet != NULL;
        }
    }

    private void createPipelineState() {
        try (MemoryStack stack =
                     MemoryStack.stackPush();
             VulkanShaderCompiler.SpirvBinary vertex =
                     VulkanShaderCompiler.compileResource(
                             VERTEX_SHADER,
                             shaderc_vertex_shader,
                             report
                     );
             VulkanShaderCompiler.SpirvBinary fragment =
                     VulkanShaderCompiler.compileResource(
                             FRAGMENT_SHADER,
                             shaderc_fragment_shader,
                             report
                     )) {

            long vertexModule =
                    createShaderModule(
                            vertex.bytes(),
                            stack,
                            "VERTEX"
                    );

            long fragmentModule =
                    NULL;

            try {
                fragmentModule =
                        createShaderModule(
                                fragment.bytes(),
                                stack,
                                "FRAGMENT"
                        );

                VkPushConstantRange.Buffer pushRange =
                        VkPushConstantRange.calloc(
                                1,
                                stack
                        );

                pushRange.get(
                        0
                )
                        .stageFlags(
                                VK_SHADER_STAGE_VERTEX_BIT
                        )
                        .offset(
                                0
                        )
                        .size(
                                PUSH_CONSTANT_BYTES
                        );

                VkPipelineLayoutCreateInfo layoutInfo =
                        VkPipelineLayoutCreateInfo.calloc(
                                stack
                        )
                                .sType$Default()
                                .pSetLayouts(
                                        stack.longs(
                                                descriptorSetLayout
                                        )
                                )
                                .pPushConstantRanges(
                                        pushRange
                                );

                LongBuffer layoutPointer =
                        stack.mallocLong(
                                1
                        );

                int result =
                        vkCreatePipelineLayout(
                                device,
                                layoutInfo,
                                null,
                                layoutPointer
                        );

                if (result != VK_SUCCESS) {
                    throw new VulkanProbeException(
                            "GATE10_RASTER_PIPELINE_LAYOUT",
                            "vkCreatePipelineLayout failed with VkResult "
                                    + result
                    );
                }

                pipelineLayout =
                        layoutPointer.get(
                                0
                        );

                for (VulkanGate10DynamicOwnershipContract.Domain domain :
                        VulkanGate10DynamicOwnershipContract.Domain
                                .values()) {
                    pipelines[
                            domain.ordinal()
                    ] =
                            createPipeline(
                                    domain,
                                    vertexModule,
                                    fragmentModule,
                                    stack
                            );

                    if (pipelines[
                            domain.ordinal()
                    ] != NULL) {
                        pipelineEverCreatedCount++;
                    }
                }
            } finally {
                if (fragmentModule != NULL) {
                    vkDestroyShaderModule(
                            device,
                            fragmentModule,
                            null
                    );
                }

                vkDestroyShaderModule(
                        device,
                        vertexModule,
                        null
                );
            }
        }
    }

    private long createShaderModule(
            ByteBuffer spirv,
            MemoryStack stack,
            String label
    ) {
        VkShaderModuleCreateInfo info =
                VkShaderModuleCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .pCode(
                                spirv
                        );

        LongBuffer pointer =
                stack.mallocLong(
                        1
                );

        int result =
                vkCreateShaderModule(
                        device,
                        info,
                        null,
                        pointer
                );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "GATE10_RASTER_" + label + "_SHADER",
                    "vkCreateShaderModule failed with VkResult "
                            + result
            );
        }

        long module =
                pointer.get(
                        0
                );

        if (module == NULL) {
            throw new VulkanProbeException(
                    "GATE10_RASTER_" + label + "_SHADER",
                    "vkCreateShaderModule returned NULL."
            );
        }

        return module;
    }

    private long createPipeline(
            VulkanGate10DynamicOwnershipContract.Domain domain,
            long vertexModule,
            long fragmentModule,
            MemoryStack stack
    ) {
        VkPipelineShaderStageCreateInfo.Buffer stages =
                VkPipelineShaderStageCreateInfo.calloc(
                        2,
                        stack
                );

        stages.get(
                0
        )
                .sType$Default()
                .stage(
                        VK_SHADER_STAGE_VERTEX_BIT
                )
                .module(
                        vertexModule
                )
                .pName(
                        stack.UTF8(
                                "main"
                        )
                );

        stages.get(
                1
        )
                .sType$Default()
                .stage(
                        VK_SHADER_STAGE_FRAGMENT_BIT
                )
                .module(
                        fragmentModule
                )
                .pName(
                        stack.UTF8(
                                "main"
                        )
                );

        VkPipelineVertexInputStateCreateInfo vertexInput =
                VkPipelineVertexInputStateCreateInfo.calloc(
                        stack
                )
                        .sType$Default();

        VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                VkPipelineInputAssemblyStateCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .topology(
                                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
                        )
                        .primitiveRestartEnable(
                                false
                        );

        VkPipelineViewportStateCreateInfo viewportState =
                VkPipelineViewportStateCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .viewportCount(
                                1
                        )
                        .scissorCount(
                                1
                        );

        VkPipelineRasterizationStateCreateInfo rasterization =
                VkPipelineRasterizationStateCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .depthClampEnable(
                                false
                        )
                        .rasterizerDiscardEnable(
                                false
                        )
                        .polygonMode(
                                VK_POLYGON_MODE_FILL
                        )
                        .cullMode(
                                VK_CULL_MODE_NONE
                        )
                        .frontFace(
                                VK_FRONT_FACE_COUNTER_CLOCKWISE
                        )
                        .depthBiasEnable(
                                false
                        )
                        .lineWidth(
                                1.0f
                        );

        VkPipelineMultisampleStateCreateInfo multisample =
                VkPipelineMultisampleStateCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .rasterizationSamples(
                                VK_SAMPLE_COUNT_1_BIT
                        )
                        .sampleShadingEnable(
                                false
                        );

        boolean depthTest =
                domain == VulkanGate10DynamicOwnershipContract.Domain.ENTITY
                        || domain
                        == VulkanGate10DynamicOwnershipContract.Domain.PARTICLE;

        boolean depthWrite =
                domain == VulkanGate10DynamicOwnershipContract.Domain.ENTITY;

        VkPipelineDepthStencilStateCreateInfo depthStencil =
                VkPipelineDepthStencilStateCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .depthTestEnable(
                                depthTest
                        )
                        .depthWriteEnable(
                                depthWrite
                        )
                        .depthCompareOp(
                                VK_COMPARE_OP_LESS_OR_EQUAL
                        )
                        .depthBoundsTestEnable(
                                false
                        )
                        .stencilTestEnable(
                                false
                        );

        boolean blend =
                domain != VulkanGate10DynamicOwnershipContract.Domain.ENTITY;

        VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                VkPipelineColorBlendAttachmentState.calloc(
                        1,
                        stack
                );

        blendAttachment.get(
                0
        )
                .blendEnable(
                        blend
                )
                .srcColorBlendFactor(
                        blend
                                ? VK_BLEND_FACTOR_SRC_ALPHA
                                : VK_BLEND_FACTOR_ONE
                )
                .dstColorBlendFactor(
                        blend
                                ? VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
                                : VK_BLEND_FACTOR_ZERO
                )
                .colorBlendOp(
                        VK_BLEND_OP_ADD
                )
                .srcAlphaBlendFactor(
                        VK_BLEND_FACTOR_ONE
                )
                .dstAlphaBlendFactor(
                        blend
                                ? VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
                                : VK_BLEND_FACTOR_ZERO
                )
                .alphaBlendOp(
                        VK_BLEND_OP_ADD
                )
                .colorWriteMask(
                        VK_COLOR_COMPONENT_R_BIT
                                | VK_COLOR_COMPONENT_G_BIT
                                | VK_COLOR_COMPONENT_B_BIT
                                | VK_COLOR_COMPONENT_A_BIT
                );

        VkPipelineColorBlendStateCreateInfo colorBlend =
                VkPipelineColorBlendStateCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .logicOpEnable(
                                false
                        )
                        .pAttachments(
                                blendAttachment
                        );

        VkPipelineDynamicStateCreateInfo dynamicState =
                VkPipelineDynamicStateCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .pDynamicStates(
                                stack.ints(
                                        VK_DYNAMIC_STATE_VIEWPORT,
                                        VK_DYNAMIC_STATE_SCISSOR
                                )
                        );

        VkPipelineRenderingCreateInfo renderingInfo =
                VkPipelineRenderingCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .colorAttachmentCount(
                                1
                        )
                        .pColorAttachmentFormats(
                                stack.ints(
                                        COLOR_FORMAT
                                )
                        )
                        .depthAttachmentFormat(
                                DEPTH_FORMAT
                        )
                        .stencilAttachmentFormat(
                                VK_FORMAT_UNDEFINED
                        );

        VkGraphicsPipelineCreateInfo.Buffer pipelineInfo =
                VkGraphicsPipelineCreateInfo.calloc(
                        1,
                        stack
                );

        pipelineInfo.get(
                0
        )
                .sType$Default()
                .pNext(
                        renderingInfo.address()
                )
                .pStages(
                        stages
                )
                .pVertexInputState(
                        vertexInput
                )
                .pInputAssemblyState(
                        inputAssembly
                )
                .pViewportState(
                        viewportState
                )
                .pRasterizationState(
                        rasterization
                )
                .pMultisampleState(
                        multisample
                )
                .pDepthStencilState(
                        depthStencil
                )
                .pColorBlendState(
                        colorBlend
                )
                .pDynamicState(
                        dynamicState
                )
                .layout(
                        pipelineLayout
                )
                .renderPass(
                        NULL
                )
                .subpass(
                        0
                );

        LongBuffer pointer =
                stack.mallocLong(
                        1
                );

        int result =
                vkCreateGraphicsPipelines(
                        device,
                        NULL,
                        pipelineInfo,
                        null,
                        pointer
                );

        report.addProperty(
                "gate10DynamicRaster"
                        + domainLabel(
                        domain
                )
                        + "PipelineCreateResult",
                result
        );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "GATE10_RASTER_CREATE_"
                            + domain.name()
                            + "_PIPELINE",
                    "vkCreateGraphicsPipelines failed with VkResult "
                            + result
            );
        }

        long pipeline =
                pointer.get(
                        0
                );

        if (pipeline == NULL) {
            throw new VulkanProbeException(
                    "GATE10_RASTER_CREATE_"
                            + domain.name()
                            + "_PIPELINE",
                    "vkCreateGraphicsPipelines returned NULL."
            );
        }

        return pipeline;
    }

    private void createCommandState() {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo =
                    VkCommandPoolCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
                            )
                            .queueFamilyIndex(
                                    graphicsQueueFamilyIndex
                            );

            LongBuffer poolPointer =
                    stack.mallocLong(
                            1
                    );

            int result =
                    vkCreateCommandPool(
                            device,
                            poolInfo,
                            null,
                            poolPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_COMMAND_POOL",
                        "vkCreateCommandPool failed with VkResult "
                                + result
                );
            }

            commandPool =
                    poolPointer.get(
                            0
                    );

            VkCommandBufferAllocateInfo allocateInfo =
                    VkCommandBufferAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .commandPool(
                                    commandPool
                            )
                            .level(
                                    VK_COMMAND_BUFFER_LEVEL_PRIMARY
                            )
                            .commandBufferCount(
                                    1
                            );

            PointerBuffer commandPointer =
                    stack.mallocPointer(
                            1
                    );

            result =
                    vkAllocateCommandBuffers(
                            device,
                            allocateInfo,
                            commandPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_COMMAND_BUFFER",
                        "vkAllocateCommandBuffers failed with VkResult "
                                + result
                );
            }

            commandBuffer =
                    new VkCommandBuffer(
                            commandPointer.get(
                                    0
                            ),
                            device
                    );

            VkFenceCreateInfo fenceInfo =
                    VkFenceCreateInfo.calloc(
                            stack
                    )
                            .sType$Default();

            LongBuffer fencePointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkCreateFence(
                            device,
                            fenceInfo,
                            null,
                            fencePointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_FENCE",
                        "vkCreateFence failed with VkResult "
                                + result
                );
            }

            fence =
                    fencePointer.get(
                            0
                    );

            VkQueryPoolCreateInfo queryInfo =
                    VkQueryPoolCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .queryType(
                                    VK_QUERY_TYPE_OCCLUSION
                            )
                            .queryCount(
                                    1
                            );

            LongBuffer queryPointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkCreateQueryPool(
                            device,
                            queryInfo,
                            null,
                            queryPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_QUERY_POOL",
                        "vkCreateQueryPool failed with VkResult "
                                + result
                );
            }

            queryPool =
                    queryPointer.get(
                            0
                    );
        }
    }

    private void submit(
            VulkanGate10DynamicOwnershipContract.Domain domain
    ) {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            int result =
                    vkResetCommandBuffer(
                            commandBuffer,
                            0
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_RESET_COMMAND_BUFFER",
                        "vkResetCommandBuffer failed with VkResult "
                                + result
                );
            }

            VkCommandBufferBeginInfo beginInfo =
                    VkCommandBufferBeginInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
                            );

            result =
                    vkBeginCommandBuffer(
                            commandBuffer,
                            beginInfo
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_BEGIN_COMMAND_BUFFER",
                        "vkBeginCommandBuffer failed with VkResult "
                                + result
                );
            }

            if (!targetLayoutsInitialized) {
                VkImageMemoryBarrier.Buffer barriers =
                        VkImageMemoryBarrier.calloc(
                                2,
                                stack
                        );

                barriers.get(
                        0
                )
                        .sType$Default()
                        .srcAccessMask(
                                0
                        )
                        .dstAccessMask(
                                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                        )
                        .oldLayout(
                                VK_IMAGE_LAYOUT_UNDEFINED
                        )
                        .newLayout(
                                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                        )
                        .srcQueueFamilyIndex(
                                VK_QUEUE_FAMILY_IGNORED
                        )
                        .dstQueueFamilyIndex(
                                VK_QUEUE_FAMILY_IGNORED
                        )
                        .image(
                                colorImage
                        );

                barriers.get(
                        0
                ).subresourceRange()
                        .aspectMask(
                                VK_IMAGE_ASPECT_COLOR_BIT
                        )
                        .baseMipLevel(
                                0
                        )
                        .levelCount(
                                1
                        )
                        .baseArrayLayer(
                                0
                        )
                        .layerCount(
                                1
                        );

                barriers.get(
                        1
                )
                        .sType$Default()
                        .srcAccessMask(
                                0
                        )
                        .dstAccessMask(
                                VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                        )
                        .oldLayout(
                                VK_IMAGE_LAYOUT_UNDEFINED
                        )
                        .newLayout(
                                VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                        )
                        .srcQueueFamilyIndex(
                                VK_QUEUE_FAMILY_IGNORED
                        )
                        .dstQueueFamilyIndex(
                                VK_QUEUE_FAMILY_IGNORED
                        )
                        .image(
                                depthImage
                        );

                barriers.get(
                        1
                ).subresourceRange()
                        .aspectMask(
                                VK_IMAGE_ASPECT_DEPTH_BIT
                        )
                        .baseMipLevel(
                                0
                        )
                        .levelCount(
                                1
                        )
                        .baseArrayLayer(
                                0
                        )
                        .layerCount(
                                1
                        );

                vkCmdPipelineBarrier(
                        commandBuffer,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                        VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                                | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                        0,
                        null,
                        null,
                        barriers
                );
            }

            vkCmdResetQueryPool(
                    commandBuffer,
                    queryPool,
                    0,
                    1
            );

            vkCmdBeginQuery(
                    commandBuffer,
                    queryPool,
                    0,
                    0
            );

            VkRenderingAttachmentInfo.Buffer colorAttachment =
                    VkRenderingAttachmentInfo.calloc(
                            1,
                            stack
                    );

            colorAttachment.get(
                    0
            )
                    .sType$Default()
                    .imageView(
                            colorView
                    )
                    .imageLayout(
                            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                    )
                    .loadOp(
                            VK_ATTACHMENT_LOAD_OP_CLEAR
                    )
                    .storeOp(
                            VK_ATTACHMENT_STORE_OP_STORE
                    );

            colorAttachment.get(
                    0
            )
                    .clearValue()
                    .color()
                    .float32(
                            0,
                            0.0f
                    )
                    .float32(
                            1,
                            0.0f
                    )
                    .float32(
                            2,
                            0.0f
                    )
                    .float32(
                            3,
                            1.0f
                    );

            VkRenderingAttachmentInfo depthAttachment =
                    VkRenderingAttachmentInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .imageView(
                                    depthView
                            )
                            .imageLayout(
                                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                            )
                            .loadOp(
                                    VK_ATTACHMENT_LOAD_OP_CLEAR
                            )
                            .storeOp(
                                    VK_ATTACHMENT_STORE_OP_STORE
                            );

            depthAttachment
                    .clearValue()
                    .depthStencil()
                    .depth(
                            1.0f
                    )
                    .stencil(
                            0
                    );

            VkRenderingInfo renderingInfo =
                    VkRenderingInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .layerCount(
                                    1
                            )
                            .pColorAttachments(
                                    colorAttachment
                            )
                            .pDepthAttachment(
                                    depthAttachment
                            );

            renderingInfo.renderArea()
                    .offset()
                    .x(
                            0
                    )
                    .y(
                            0
                    );

            renderingInfo.renderArea()
                    .extent()
                    .width(
                            TARGET_WIDTH
                    )
                    .height(
                            TARGET_HEIGHT
                    );

            vkCmdBeginRendering(
                    commandBuffer,
                    renderingInfo
            );

            VkViewport.Buffer viewport =
                    VkViewport.calloc(
                            1,
                            stack
                    );

            viewport.get(
                    0
            )
                    .x(
                            0.0f
                    )
                    .y(
                            0.0f
                    )
                    .width(
                            (float) TARGET_WIDTH
                    )
                    .height(
                            (float) TARGET_HEIGHT
                    )
                    .minDepth(
                            0.0f
                    )
                    .maxDepth(
                            1.0f
                    );

            vkCmdSetViewport(
                    commandBuffer,
                    0,
                    viewport
            );

            VkRect2D.Buffer scissor =
                    VkRect2D.calloc(
                            1,
                            stack
                    );

            scissor.get(
                    0
            )
                    .offset()
                    .x(
                            0
                    )
                    .y(
                            0
                    );

            scissor.get(
                    0
            )
                    .extent()
                    .width(
                            TARGET_WIDTH
                    )
                    .height(
                            TARGET_HEIGHT
                    );

            vkCmdSetScissor(
                    commandBuffer,
                    0,
                    scissor
            );

            vkCmdBindPipeline(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipelines[
                            domain.ordinal()
                    ]
            );

            vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipelineLayout,
                    0,
                    stack.longs(
                            descriptorSet
                    ),
                    null
            );

            ByteBuffer push =
                    stack.malloc(
                            PUSH_CONSTANT_BYTES
                    ).order(
                            ByteOrder.nativeOrder()
                    );

            push.putInt(
                    0,
                    domain.ordinal()
            );

            vkCmdPushConstants(
                    commandBuffer,
                    pipelineLayout,
                    VK_SHADER_STAGE_VERTEX_BIT,
                    0,
                    push
            );

            vkCmdDraw(
                    commandBuffer,
                    3,
                    1,
                    0,
                    0
            );

            vkCmdEndRendering(
                    commandBuffer
            );

            vkCmdEndQuery(
                    commandBuffer,
                    queryPool,
                    0
            );

            result =
                    vkEndCommandBuffer(
                            commandBuffer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_END_COMMAND_BUFFER",
                        "vkEndCommandBuffer failed with VkResult "
                                + result
                );
            }

            result =
                    vkResetFences(
                            device,
                            fence
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_RESET_FENCE",
                        "vkResetFences failed with VkResult "
                                + result
                );
            }

            VkSubmitInfo.Buffer submit =
                    VkSubmitInfo.calloc(
                            1,
                            stack
                    );

            submit.get(
                    0
            )
                    .sType$Default()
                    .pCommandBuffers(
                            stack.pointers(
                                    commandBuffer.address()
                            )
                    );

            result =
                    vkQueueSubmit(
                            graphicsQueue,
                            submit,
                            fence
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_QUEUE_SUBMIT",
                        "vkQueueSubmit failed with VkResult "
                                + result
                );
            }

            targetLayoutsInitialized =
                    true;
            inFlight =
                    true;
            inFlightDomain =
                    domain;
            submittedDomains.add(
                    domain
            );
            submissionCount++;
        }
    }

    private void pollCompletion() {
        if (!inFlight
                || fence == NULL) {
            return;
        }

        fencePollCount++;

        int status =
                vkGetFenceStatus(
                        device,
                        fence
                );

        if (status == VK_NOT_READY) {
            fenceNotReadyCount++;
            return;
        }

        if (status != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "GATE10_RASTER_FENCE_STATUS",
                    "vkGetFenceStatus failed with VkResult "
                            + status
            );
        }

        completeInFlightProof();
    }

    private void completeInFlightProof() {
        if (!inFlight) {
            return;
        }

        long samples;

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            ByteBuffer queryData =
                    stack.malloc(
                            Long.BYTES
                    ).order(
                            ByteOrder.nativeOrder()
                    );

            int result =
                    vkGetQueryPoolResults(
                            device,
                            queryPool,
                            0,
                            1,
                            queryData,
                            Long.BYTES,
                            VK_QUERY_RESULT_64_BIT
                    );

            queryReadCount++;

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RASTER_QUERY_RESULT",
                        "vkGetQueryPoolResults failed with VkResult "
                                + result
                );
            }

            samples =
                    queryData.getLong(
                            0
                    );
        }

        if (samples <= 0L) {
            throw new VulkanProbeException(
                    "GATE10_RASTER_ZERO_SAMPLES",
                    "Dynamic raster precommit produced zero samples; state packet validation or pipeline rasterization failed."
            );
        }

        if (inFlightDomain != null) {
            completedDomains.add(
                    inFlightDomain
            );
        }

        completionCount++;
        totalRasterizedSamples +=
                samples;
        lastRasterizedSamples =
                samples;

        if (completedDomains.size()
                == VulkanGate10DynamicOwnershipContract.Domain
                .values().length) {
            proofRetired =
                    true;
        }

        inFlight =
                false;
        inFlightDomain =
                null;
    }

    synchronized boolean readyForVisibleScreenRehearsal() {
        return !closed
                && !disabledAfterFailure
                && proofRetired
                && failureCount == 0L
                && completedDomains.size()
                == VulkanGate10DynamicOwnershipContract.Domain
                .values().length
                && pipelineLayout != NULL
                && descriptorSet != NULL
                && pipelines[
                VulkanGate10DynamicOwnershipContract.Domain.SCREEN.ordinal()
                ] != NULL;
    }

    synchronized long visibleScreenPipeline() {
        return readyForVisibleScreenRehearsal()
                ? pipelines[
                VulkanGate10DynamicOwnershipContract.Domain.SCREEN.ordinal()
                ]
                : NULL;
    }

    synchronized long visibleScreenPipelineLayout() {
        return readyForVisibleScreenRehearsal()
                ? pipelineLayout
                : NULL;
    }

    synchronized long visibleScreenDescriptorSet() {
        return readyForVisibleScreenRehearsal()
                ? descriptorSet
                : NULL;
    }

    private void fail(
            Throwable throwable
    ) {
        failureCount++;
        disabledAfterFailure =
                true;
        lastFailure =
                throwable.getClass()
                        .getName()
                        + ": "
                        + String.valueOf(
                        throwable.getMessage()
                );

        enrich(
                report
        );
    }

    static void enrichAbsent(
            JsonObject report
    ) {
        if (report == null) {
            return;
        }

        report.addProperty(
                "gate10DynamicRasterInstalled",
                true
        );
        report.addProperty(
                "gate10DynamicRasterInitialized",
                false
        );
        report.addProperty(
                "gate10DynamicRasterPrivateTargetsEverCreated",
                false
        );
        report.addProperty(
                "gate10DynamicRasterStateBufferDescriptorEverBound",
                false
        );
        report.addProperty(
                "gate10DynamicRasterPipelineEverCreatedCount",
                0
        );
        report.addProperty(
                "gate10DynamicRasterConsumesStateBuffer",
                true
        );
        report.addProperty(
                "gate10DynamicRasterSubmissionCount",
                0
        );
        report.addProperty(
                "gate10DynamicRasterCompletionCount",
                0
        );
        report.addProperty(
                "gate10DynamicRasterAllDomainsVerified",
                false
        );
        report.addProperty(
                "gate10DynamicRasterFailureCount",
                0
        );
        report.addProperty(
                "gate10DynamicRasterVisibleOwnership",
                false
        );
        report.addProperty(
                "gate10DynamicRasterGameplayFenceWait",
                false
        );
        report.addProperty(
                "gate10DynamicRasterGameplayQueueWaitIdle",
                false
        );
        report.addProperty(
                "gate10DynamicRasterGameplayDeviceWaitIdle",
                false
        );
        report.addProperty(
                "gate10DynamicRasterMutatesOpenGlDraws",
                false
        );
    }

    synchronized void enrich(
            JsonObject target
    ) {
        if (target == null) {
            return;
        }

        int pipelineCount =
                0;

        for (long pipeline : pipelines) {
            if (pipeline != NULL) {
                pipelineCount++;
            }
        }

        target.addProperty(
                "gate10DynamicRasterInstalled",
                true
        );
        target.addProperty(
                "gate10DynamicRasterMode",
                "DEVICE_LOCAL_STATE_PACKET_CONSUMING_PRIVATE_DYNAMIC_RENDERING"
        );
        target.addProperty(
                "gate10DynamicRasterInitialized",
                initialized
        );
        target.addProperty(
                "gate10DynamicRasterPrivateTargetsEverCreated",
                privateTargetsEverCreated
        );
        target.addProperty(
                "gate10DynamicRasterStateBufferDescriptorEverBound",
                stateBufferDescriptorEverBound
        );
        target.addProperty(
                "gate10DynamicRasterPipelineEverCreatedCount",
                pipelineEverCreatedCount
        );
        target.addProperty(
                "gate10DynamicRasterTargetWidth",
                TARGET_WIDTH
        );
        target.addProperty(
                "gate10DynamicRasterTargetHeight",
                TARGET_HEIGHT
        );
        target.addProperty(
                "gate10DynamicRasterTargetColorFormat",
                COLOR_FORMAT
        );
        target.addProperty(
                "gate10DynamicRasterTargetDepthFormat",
                DEPTH_FORMAT
        );
        target.addProperty(
                "gate10DynamicRasterColorMemoryTypeIndex",
                colorMemoryTypeIndex
        );
        target.addProperty(
                "gate10DynamicRasterDepthMemoryTypeIndex",
                depthMemoryTypeIndex
        );
        target.addProperty(
                "gate10DynamicRasterStateBufferDescriptorBound",
                descriptorSet != NULL
        );
        target.addProperty(
                "gate10DynamicRasterConsumesStateBuffer",
                true
        );
        target.addProperty(
                "gate10DynamicRasterShaderValidatesPacketMagicDomainExtentProfile",
                true
        );
        target.addProperty(
                "gate10DynamicRasterPipelineCount",
                pipelineCount
        );
        target.addProperty(
                "gate10DynamicRasterEntityProfile",
                "DEPTH_TEST_WRITE_OPAQUE_CULL_NONE"
        );
        target.addProperty(
                "gate10DynamicRasterParticleProfile",
                "DEPTH_TEST_NO_WRITE_ALPHA_BLEND_CULL_NONE"
        );
        target.addProperty(
                "gate10DynamicRasterHudProfile",
                "NO_DEPTH_ALPHA_BLEND_CULL_NONE"
        );
        target.addProperty(
                "gate10DynamicRasterScreenProfile",
                "NO_DEPTH_ALPHA_BLEND_CULL_NONE"
        );
        target.addProperty(
                "gate10DynamicRasterOfferCount",
                offerCount
        );
        target.addProperty(
                "gate10DynamicRasterDuplicateDomainSkipCount",
                duplicateDomainSkipCount
        );
        target.addProperty(
                "gate10DynamicRasterBusySkipCount",
                busySkipCount
        );
        target.addProperty(
                "gate10DynamicRasterSubmissionCount",
                submissionCount
        );
        target.addProperty(
                "gate10DynamicRasterCompletionCount",
                completionCount
        );
        target.addProperty(
                "gate10DynamicRasterFencePollCount",
                fencePollCount
        );
        target.addProperty(
                "gate10DynamicRasterFenceNotReadyCount",
                fenceNotReadyCount
        );
        target.addProperty(
                "gate10DynamicRasterQueryReadCount",
                queryReadCount
        );
        target.addProperty(
                "gate10DynamicRasterTotalRasterizedSamples",
                totalRasterizedSamples
        );
        target.addProperty(
                "gate10DynamicRasterLastRasterizedSamples",
                lastRasterizedSamples
        );
        target.addProperty(
                "gate10DynamicRasterEntityCompleted",
                completedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.ENTITY
                )
        );
        target.addProperty(
                "gate10DynamicRasterParticleCompleted",
                completedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.PARTICLE
                )
        );
        target.addProperty(
                "gate10DynamicRasterHudCompleted",
                completedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.HUD
                )
        );
        target.addProperty(
                "gate10DynamicRasterScreenCompleted",
                completedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.SCREEN
                )
        );
        target.addProperty(
                "gate10DynamicRasterAllDomainsVerified",
                completedDomains.size()
                        == VulkanGate10DynamicOwnershipContract.Domain
                        .values().length
                        && failureCount == 0L
        );
        target.addProperty(
                "gate10DynamicRasterProofRetired",
                proofRetired
        );
        target.addProperty(
                "gate10DynamicRasterFailureCount",
                failureCount
        );
        target.addProperty(
                "gate10DynamicRasterDisabledAfterFailure",
                disabledAfterFailure
        );
        target.addProperty(
                "gate10DynamicRasterGameplayFenceWait",
                false
        );
        target.addProperty(
                "gate10DynamicRasterGameplayQueueWaitIdle",
                false
        );
        target.addProperty(
                "gate10DynamicRasterGameplayDeviceWaitIdle",
                false
        );
        target.addProperty(
                "gate10DynamicRasterShutdownFenceWaitCount",
                shutdownFenceWaitCount
        );
        target.addProperty(
                "gate10DynamicRasterMutatesOpenGlDraws",
                false
        );
        target.addProperty(
                "gate10DynamicRasterVisibleOwnership",
                false
        );
        target.addProperty(
                "gate10DynamicRasterNextMilestone",
                "POTATO_ENGINE_GATE10_DYNAMIC_VISIBLE_OWNERSHIP_REHEARSAL"
        );

        if (!lastFailure.isBlank()) {
            target.addProperty(
                    "gate10DynamicRasterLastFailure",
                    lastFailure
            );
        }
    }

    private static String domainLabel(
            VulkanGate10DynamicOwnershipContract.Domain domain
    ) {
        return switch (domain) {
            case ENTITY ->
                    "Entity";

            case PARTICLE ->
                    "Particle";

            case HUD ->
                    "Hud";

            case SCREEN ->
                    "Screen";
        };
    }

    private void destroyResources() {
        for (int index = 0;
             index < pipelines.length;
             index++) {
            if (pipelines[
                    index
            ] != NULL) {
                vkDestroyPipeline(
                        device,
                        pipelines[
                                index
                        ],
                        null
                );

                pipelines[
                        index
                ] =
                        NULL;
            }
        }

        if (pipelineLayout != NULL) {
            vkDestroyPipelineLayout(
                    device,
                    pipelineLayout,
                    null
            );

            pipelineLayout =
                    NULL;
        }

        if (descriptorPool != NULL) {
            vkDestroyDescriptorPool(
                    device,
                    descriptorPool,
                    null
            );

            descriptorPool =
                    NULL;
            descriptorSet =
                    NULL;
        }

        if (descriptorSetLayout != NULL) {
            vkDestroyDescriptorSetLayout(
                    device,
                    descriptorSetLayout,
                    null
            );

            descriptorSetLayout =
                    NULL;
        }

        if (queryPool != NULL) {
            vkDestroyQueryPool(
                    device,
                    queryPool,
                    null
            );

            queryPool =
                    NULL;
        }

        if (fence != NULL) {
            vkDestroyFence(
                    device,
                    fence,
                    null
            );

            fence =
                    NULL;
        }

        if (commandPool != NULL) {
            vkDestroyCommandPool(
                    device,
                    commandPool,
                    null
            );

            commandPool =
                    NULL;
            commandBuffer =
                    null;
        }

        if (colorView != NULL) {
            vkDestroyImageView(
                    device,
                    colorView,
                    null
            );

            colorView =
                    NULL;
        }

        if (depthView != NULL) {
            vkDestroyImageView(
                    device,
                    depthView,
                    null
            );

            depthView =
                    NULL;
        }

        if (colorImage != NULL) {
            vkDestroyImage(
                    device,
                    colorImage,
                    null
            );

            colorImage =
                    NULL;
        }

        if (depthImage != NULL) {
            vkDestroyImage(
                    device,
                    depthImage,
                    null
            );

            depthImage =
                    NULL;
        }

        if (colorMemory != NULL) {
            vkFreeMemory(
                    device,
                    colorMemory,
                    null
            );

            colorMemory =
                    NULL;
        }

        if (depthMemory != NULL) {
            vkFreeMemory(
                    device,
                    depthMemory,
                    null
            );

            depthMemory =
                    NULL;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed =
                true;

        if (inFlight
                && fence != NULL) {
            shutdownFenceWaitCount++;

            int result;

            try (MemoryStack stack =
                         MemoryStack.stackPush()) {
                result =
                        vkWaitForFences(
                                device,
                                stack.longs(
                                        fence
                                ),
                                true,
                                Long.MAX_VALUE
                        );
            }

            if (result == VK_SUCCESS) {
                try {
                    completeInFlightProof();
                } catch (Throwable throwable) {
                    failureCount++;
                    lastFailure =
                            throwable.getClass()
                                    .getName()
                                    + ": "
                                    + String.valueOf(
                                    throwable.getMessage()
                            );
                }
            } else {
                failureCount++;
                lastFailure =
                        "Shutdown vkWaitForFences returned VkResult "
                                + result;
            }
        }

        destroyResources();

        report.addProperty(
                "gate10DynamicRasterClosed",
                true
        );

        enrich(
                report
        );
    }

    private record ImageAllocation(
            long image,
            long memory,
            long view,
            int memoryTypeIndex
    ) {
    }
}
