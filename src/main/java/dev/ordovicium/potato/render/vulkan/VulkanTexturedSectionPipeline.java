package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.resource.BlockVertexLayoutSnapshot;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Production BLOCK pipeline foundation.
 *
 * <p>Implements the state shared by vanilla solid/cutout chunk layers:
 * BLOCK vertex layout, atlas + lightmap descriptors, back-face culling,
 * LEQUAL depth test/write, ColorModulator and linear fog.</p>
 */
final class VulkanTexturedSectionPipeline
        implements AutoCloseable {

    private static final String VERTEX_SHADER =
            "assets/potato_runtime/shaders/vulkan/section_layer_textured.vert";

    private static final String FRAGMENT_SHADER =
            "assets/potato_runtime/shaders/vulkan/section_layer_textured.frag";

    /**
     * Vulkan guarantees at least 128 bytes of push constants.
     *
     * Layout:
     *  0..63   MVP
     * 64..79   ChunkOffset
     * 80..95   ColorModulator
     * 96..111  FogColor
     * 112      FogStart
     * 116      FogEnd
     * 120      FogShape (float encoded integer)
     * 124      Alpha cutoff
     */
    static final int PUSH_CONSTANT_BYTES =
            32 * Float.BYTES;

    private final VkDevice device;

    private final int colorFormat;
    private final int depthFormat;

    private long pipelineLayout;
    private long pipeline;

    private VulkanTexturedSectionPipeline(
            VkDevice device,
            int colorFormat,
            int depthFormat,
            long pipelineLayout,
            long pipeline
    ) {
        this.device = device;
        this.colorFormat = colorFormat;
        this.depthFormat = depthFormat;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    static VulkanTexturedSectionPipeline create(
            VkDevice device,
            int colorFormat,
            int depthFormat,
            long descriptorSetLayout,
            BlockVertexLayoutSnapshot blockLayout,
            JsonObject report
    ) {
        if (depthFormat
                == VK_FORMAT_UNDEFINED) {
            throw new VulkanProbeException(
                    "CREATE_PRODUCTION_BLOCK_PIPELINE",
                    "Production BLOCK pipeline requires a depth attachment."
            );
        }

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
                            device,
                            vertex.bytes(),
                            stack,
                            "PRODUCTION_BLOCK_VERTEX"
                    );

            long fragmentModule =
                    NULL;

            try {
                fragmentModule =
                        createShaderModule(
                                device,
                                fragment.bytes(),
                                stack,
                                "PRODUCTION_BLOCK_FRAGMENT"
                        );

                long layout =
                        createPipelineLayout(
                                device,
                                descriptorSetLayout,
                                stack
                        );

                try {
                    long pipeline =
                            createPipeline(
                                    device,
                                    layout,
                                    vertexModule,
                                    fragmentModule,
                                    colorFormat,
                                    depthFormat,
                                    blockLayout,
                                    stack,
                                    report
                            );

                    return new VulkanTexturedSectionPipeline(
                            device,
                            colorFormat,
                            depthFormat,
                            layout,
                            pipeline
                    );
                } catch (Throwable throwable) {
                    vkDestroyPipelineLayout(
                            device,
                            layout,
                            null
                    );

                    throw throwable;
                }
            } finally {
                if (fragmentModule
                        != NULL) {
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

    long pipeline() {
        return pipeline;
    }

    long layout() {
        return pipelineLayout;
    }

    int colorFormat() {
        return colorFormat;
    }

    int depthFormat() {
        return depthFormat;
    }

    private static long createShaderModule(
            VkDevice device,
            java.nio.ByteBuffer spirv,
            MemoryStack stack,
            String stage
    ) {
        VkShaderModuleCreateInfo createInfo =
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
                        createInfo,
                        null,
                        pointer
                );

        if (result
                != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_" + stage + "_SHADER_MODULE",
                    "vkCreateShaderModule failed with VkResult "
                            + result
            );
        }

        long module =
                pointer.get(
                        0
                );

        if (module
                == NULL) {
            throw new VulkanProbeException(
                    "CREATE_" + stage + "_SHADER_MODULE",
                    "vkCreateShaderModule returned NULL."
            );
        }

        return module;
    }

    private static long createPipelineLayout(
            VkDevice device,
            long descriptorSetLayout,
            MemoryStack stack
    ) {
        VkPushConstantRange.Buffer range =
                VkPushConstantRange.calloc(
                        1,
                        stack
                );

        range.get(0)
                .stageFlags(
                        VK_SHADER_STAGE_VERTEX_BIT
                                | VK_SHADER_STAGE_FRAGMENT_BIT
                )
                .offset(
                        0
                )
                .size(
                        PUSH_CONSTANT_BYTES
                );

        VkPipelineLayoutCreateInfo createInfo =
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
                                range
                        );

        LongBuffer pointer =
                stack.mallocLong(
                        1
                );

        int result =
                vkCreatePipelineLayout(
                        device,
                        createInfo,
                        null,
                        pointer
                );

        if (result
                != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_PRODUCTION_BLOCK_PIPELINE_LAYOUT",
                    "vkCreatePipelineLayout failed with VkResult "
                            + result
            );
        }

        long layout =
                pointer.get(
                        0
                );

        if (layout
                == NULL) {
            throw new VulkanProbeException(
                    "CREATE_PRODUCTION_BLOCK_PIPELINE_LAYOUT",
                    "vkCreatePipelineLayout returned NULL."
            );
        }

        return layout;
    }

    private static long createPipeline(
            VkDevice device,
            long layout,
            long vertexModule,
            long fragmentModule,
            int colorFormat,
            int depthFormat,
            BlockVertexLayoutSnapshot blockLayout,
            MemoryStack stack,
            JsonObject report
    ) {
        if (blockLayout == null
                || !blockLayout
                .verifiedForMinecraft1211()) {
            throw new VulkanProbeException(
                    "PRODUCTION_BLOCK_VERTEX_LAYOUT",
                    "Runtime BLOCK vertex layout is unavailable or unverified."
            );
        }

        VkPipelineShaderStageCreateInfo.Buffer shaderStages =
                VkPipelineShaderStageCreateInfo.calloc(
                        2,
                        stack
                );

        shaderStages.get(0)
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

        shaderStages.get(1)
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

        VkVertexInputBindingDescription.Buffer binding =
                VkVertexInputBindingDescription.calloc(
                        1,
                        stack
                );

        binding.get(0)
                .binding(
                        0
                )
                .stride(
                        blockLayout.strideBytes()
                )
                .inputRate(
                        VK_VERTEX_INPUT_RATE_VERTEX
                );

        VkVertexInputAttributeDescription.Buffer attributes =
                VkVertexInputAttributeDescription.calloc(
                        4,
                        stack
                );

        attributes.get(0)
                .location(0)
                .binding(0)
                .format(
                        VK_FORMAT_R32G32B32_SFLOAT
                )
                .offset(
                        blockLayout.positionOffset()
                );

        attributes.get(1)
                .location(1)
                .binding(0)
                .format(
                        VK_FORMAT_R8G8B8A8_UNORM
                )
                .offset(
                        blockLayout.colorOffset()
                );

        attributes.get(2)
                .location(2)
                .binding(0)
                .format(
                        VK_FORMAT_R32G32_SFLOAT
                )
                .offset(
                        blockLayout.uv0Offset()
                );

        attributes.get(3)
                .location(3)
                .binding(0)
                .format(
                        VK_FORMAT_R16G16_SINT
                )
                .offset(
                        blockLayout.uv2Offset()
                );

        VkPipelineVertexInputStateCreateInfo vertexInput =
                VkPipelineVertexInputStateCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .pVertexBindingDescriptions(
                                binding
                        )
                        .pVertexAttributeDescriptions(
                                attributes
                        );

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
                        /*
                         * The shader converts Minecraft's OpenGL clip Y to
                         * Vulkan by negating Y, reversing winding.
                         */
                        .frontFace(
                                VK_FRONT_FACE_CLOCKWISE
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

        VkPipelineDepthStencilStateCreateInfo depthStencil =
                VkPipelineDepthStencilStateCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .depthTestEnable(
                                true
                        )
                        .depthWriteEnable(
                                true
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

        VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                VkPipelineColorBlendAttachmentState.calloc(
                        1,
                        stack
                );

        blendAttachment.get(0)
                .blendEnable(
                        false
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

        IntBuffer dynamicStates =
                stack.ints(
                        VK_DYNAMIC_STATE_VIEWPORT,
                        VK_DYNAMIC_STATE_SCISSOR
                );

        VkPipelineDynamicStateCreateInfo dynamicState =
                VkPipelineDynamicStateCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .pDynamicStates(
                                dynamicStates
                        );

        IntBuffer colorFormats =
                stack.ints(
                        colorFormat
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
                                colorFormats
                        )
                        .depthAttachmentFormat(
                                depthFormat
                        )
                        .stencilAttachmentFormat(
                                VK_FORMAT_UNDEFINED
                        );

        VkGraphicsPipelineCreateInfo.Buffer pipelineInfo =
                VkGraphicsPipelineCreateInfo.calloc(
                        1,
                        stack
                );

        pipelineInfo.get(0)
                .sType$Default()
                .pNext(
                        renderingInfo.address()
                )
                .pStages(
                        shaderStages
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
                        layout
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
                "vkCreateProductionBlockGraphicsPipelineResult",
                result
        );

        if (result
                != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_PRODUCTION_BLOCK_GRAPHICS_PIPELINE",
                    "vkCreateGraphicsPipelines failed with VkResult "
                            + result
            );
        }

        long pipeline =
                pointer.get(
                        0
                );

        if (pipeline
                == NULL) {
            throw new VulkanProbeException(
                    "CREATE_PRODUCTION_BLOCK_GRAPHICS_PIPELINE",
                    "vkCreateGraphicsPipelines returned NULL."
            );
        }

        report.addProperty(
                "productionBlockShaderVertexStrideBytes",
                blockLayout.strideBytes()
        );
        report.addProperty(
                "productionBlockShaderDepthTest",
                "LEQUAL"
        );
        report.addProperty(
                "productionBlockShaderDepthWriteEnabled",
                true
        );
        report.addProperty(
                "productionBlockShaderBackFaceCullingEnabled",
                false
        );
        report.addProperty(
                "productionBlockShaderCullMode",
                "NONE_ANGLE_SAFE_CORRECTNESS_RECOVERY"
        );
        report.addProperty(
                "productionBlockShaderAngleSafeCullRecovery",
                true
        );
        report.addProperty(
                "productionBlockShaderFrontFace",
                "CLOCKWISE_AFTER_OPENGL_TO_VULKAN_Y_FLIP"
        );
        report.addProperty(
                "productionBlockShaderColorModulatorImplemented",
                true
        );
        report.addProperty(
                "productionBlockShaderLinearFogImplemented",
                true
        );
        report.addProperty(
                "productionBlockShaderFogShapeImplemented",
                true
        );
        report.addProperty(
                "productionBlockShaderLightmapImplemented",
                true
        );
        report.addProperty(
                "productionBlockShaderSolidImplemented",
                true
        );
        report.addProperty(
                "productionBlockShaderCutoutImplemented",
                true
        );
        report.addProperty(
                "productionBlockShaderCutoutMippedImplemented",
                false
        );
        report.addProperty(
                "productionBlockShaderTranslucentImplemented",
                false
        );
        report.addProperty(
                "productionBlockShaderAtlasMipFidelityComplete",
                false
        );
        report.addProperty(
                "productionBlockShaderAtlasAnimationFidelityComplete",
                false
        );
        report.addProperty(
                "productionBlockShaderPushConstantBytes",
                PUSH_CONSTANT_BYTES
        );

        return pipeline;
    }

    @Override
    public void close() {
        if (pipeline
                != NULL) {
            vkDestroyPipeline(
                    device,
                    pipeline,
                    null
            );

            pipeline =
                    NULL;
        }

        if (pipelineLayout
                != NULL) {
            vkDestroyPipelineLayout(
                    device,
                    pipelineLayout,
                    null
            );

            pipelineLayout =
                    NULL;
        }
    }
}
