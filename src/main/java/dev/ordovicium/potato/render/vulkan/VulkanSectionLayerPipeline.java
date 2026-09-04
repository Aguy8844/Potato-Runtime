package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Minimal first real Minecraft section-geometry Vulkan pipeline.
 *
 * <p>Only BLOCK position data is consumed. Textures, lightmap, normals,
 * transparency semantics and vanilla fragment shading remain intentionally
 * outside Patch 037.</p>
 */
final class VulkanSectionLayerPipeline
        implements AutoCloseable {

    private static final String VERTEX_SHADER =
            "assets/potato_runtime/shaders/vulkan/section_layer_debug.vert";

    private static final String FRAGMENT_SHADER =
            "assets/potato_runtime/shaders/vulkan/section_layer_debug.frag";

    static final int PUSH_CONSTANT_BYTES =
            20 * Float.BYTES;

    private final VkDevice device;
    private final int colorFormat;

    private long pipelineLayout;
    private long pipeline;

    private VulkanSectionLayerPipeline(
            VkDevice device,
            int colorFormat,
            long pipelineLayout,
            long pipeline
    ) {
        this.device = device;
        this.colorFormat = colorFormat;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    static VulkanSectionLayerPipeline create(
            VkDevice device,
            int colorFormat,
            JsonObject report
    ) {
        try (MemoryStack stack = MemoryStack.stackPush();
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
                            "SECTION_LAYER_VERTEX"
                    );

            long fragmentModule = NULL;

            try {
                fragmentModule =
                        createShaderModule(
                                device,
                                fragment.bytes(),
                                stack,
                                "SECTION_LAYER_FRAGMENT"
                        );

                long layout =
                        createPipelineLayout(
                                device,
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
                                    stack,
                                    report
                            );

                    report.addProperty(
                            "sectionLayerDrawPrototypePipelineCreated",
                            true
                    );

                    return new VulkanSectionLayerPipeline(
                            device,
                            colorFormat,
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

    long pipeline() {
        return pipeline;
    }

    long layout() {
        return pipelineLayout;
    }

    int colorFormat() {
        return colorFormat;
    }

    private static long createShaderModule(
            VkDevice device,
            java.nio.ByteBuffer spirv,
            MemoryStack stack,
            String stage
    ) {
        VkShaderModuleCreateInfo createInfo =
                VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default()
                        .pCode(
                                spirv
                        );

        LongBuffer pointer =
                stack.mallocLong(1);

        int result =
                vkCreateShaderModule(
                        device,
                        createInfo,
                        null,
                        pointer
                );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_" + stage + "_SHADER_MODULE",
                    "vkCreateShaderModule failed with VkResult "
                            + result
            );
        }

        long module =
                pointer.get(0);

        if (module == NULL) {
            throw new VulkanProbeException(
                    "CREATE_" + stage + "_SHADER_MODULE",
                    "vkCreateShaderModule returned NULL."
            );
        }

        return module;
    }

    private static long createPipelineLayout(
            VkDevice device,
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
                )
                .offset(0)
                .size(
                        PUSH_CONSTANT_BYTES
                );

        VkPipelineLayoutCreateInfo createInfo =
                VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pPushConstantRanges(
                                range
                        );

        LongBuffer pointer =
                stack.mallocLong(1);

        int result =
                vkCreatePipelineLayout(
                        device,
                        createInfo,
                        null,
                        pointer
                );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_SECTION_LAYER_PIPELINE_LAYOUT",
                    "vkCreatePipelineLayout failed with VkResult "
                            + result
            );
        }

        long layout =
                pointer.get(0);

        if (layout == NULL) {
            throw new VulkanProbeException(
                    "CREATE_SECTION_LAYER_PIPELINE_LAYOUT",
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
            MemoryStack stack,
            JsonObject report
    ) {
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
                        stack.UTF8("main")
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
                        stack.UTF8("main")
                );

        VkVertexInputBindingDescription.Buffer binding =
                VkVertexInputBindingDescription.calloc(
                        1,
                        stack
                );

        binding.get(0)
                .binding(0)
                .stride(32)
                .inputRate(
                        VK_VERTEX_INPUT_RATE_VERTEX
                );

        VkVertexInputAttributeDescription.Buffer attribute =
                VkVertexInputAttributeDescription.calloc(
                        1,
                        stack
                );

        attribute.get(0)
                .location(0)
                .binding(0)
                .format(
                        VK_FORMAT_R32G32B32_SFLOAT
                )
                .offset(0);

        VkPipelineVertexInputStateCreateInfo vertexInput =
                VkPipelineVertexInputStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .pVertexBindingDescriptions(
                                binding
                        )
                        .pVertexAttributeDescriptions(
                                attribute
                        );

        VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .topology(
                                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST
                        )
                        .primitiveRestartEnable(false);

        VkPipelineViewportStateCreateInfo viewportState =
                VkPipelineViewportStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .viewportCount(1)
                        .scissorCount(1);

        VkPipelineRasterizationStateCreateInfo rasterization =
                VkPipelineRasterizationStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .depthClampEnable(false)
                        .rasterizerDiscardEnable(false)
                        .polygonMode(
                                VK_POLYGON_MODE_FILL
                        )
                        .cullMode(
                                VK_CULL_MODE_NONE
                        )
                        .frontFace(
                                VK_FRONT_FACE_COUNTER_CLOCKWISE
                        )
                        .depthBiasEnable(false)
                        .lineWidth(1.0f);

        VkPipelineMultisampleStateCreateInfo multisample =
                VkPipelineMultisampleStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .rasterizationSamples(
                                VK_SAMPLE_COUNT_1_BIT
                        )
                        .sampleShadingEnable(false);

        VkPipelineDepthStencilStateCreateInfo depthStencil =
                VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .depthTestEnable(false)
                        .depthWriteEnable(false)
                        .depthCompareOp(
                                VK_COMPARE_OP_ALWAYS
                        )
                        .depthBoundsTestEnable(false)
                        .stencilTestEnable(false);

        VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                VkPipelineColorBlendAttachmentState.calloc(
                        1,
                        stack
                );

        blendAttachment.get(0)
                .blendEnable(false)
                .colorWriteMask(
                        VK_COLOR_COMPONENT_R_BIT
                                | VK_COLOR_COMPONENT_G_BIT
                                | VK_COLOR_COMPONENT_B_BIT
                                | VK_COLOR_COMPONENT_A_BIT
                );

        VkPipelineColorBlendStateCreateInfo colorBlend =
                VkPipelineColorBlendStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .logicOpEnable(false)
                        .pAttachments(
                                blendAttachment
                        );

        IntBuffer dynamicStates =
                stack.ints(
                        VK_DYNAMIC_STATE_VIEWPORT,
                        VK_DYNAMIC_STATE_SCISSOR
                );

        VkPipelineDynamicStateCreateInfo dynamicState =
                VkPipelineDynamicStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .pDynamicStates(
                                dynamicStates
                        );

        IntBuffer attachmentFormats =
                stack.ints(
                        colorFormat
                );

        VkPipelineRenderingCreateInfo renderingInfo =
                VkPipelineRenderingCreateInfo.calloc(stack)
                        .sType$Default()
                        .colorAttachmentCount(1)
                        .pColorAttachmentFormats(
                                attachmentFormats
                        )
                        .depthAttachmentFormat(
                                VK_FORMAT_UNDEFINED
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
                .subpass(0);

        LongBuffer pointer =
                stack.mallocLong(1);

        int result =
                vkCreateGraphicsPipelines(
                        device,
                        NULL,
                        pipelineInfo,
                        null,
                        pointer
                );

        report.addProperty(
                "vkCreateSectionLayerGraphicsPipelineResult",
                result
        );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_SECTION_LAYER_GRAPHICS_PIPELINE",
                    "vkCreateGraphicsPipelines failed with VkResult "
                            + result
            );
        }

        long pipeline =
                pointer.get(0);

        if (pipeline == NULL) {
            throw new VulkanProbeException(
                    "CREATE_SECTION_LAYER_GRAPHICS_PIPELINE",
                    "vkCreateGraphicsPipelines returned NULL."
            );
        }

        report.addProperty(
                "sectionLayerDrawPrototypeVertexStrideBytes",
                32
        );
        report.addProperty(
                "sectionLayerDrawPrototypePositionFormat",
                "VK_FORMAT_R32G32B32_SFLOAT"
        );
        report.addProperty(
                "sectionLayerDrawPrototypeTopology",
                "VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST"
        );
        report.addProperty(
                "sectionLayerDrawPrototypeCullingEnabled",
                false
        );
        report.addProperty(
                "sectionLayerDrawPrototypeDepthTestEnabled",
                false
        );
        report.addProperty(
                "sectionLayerDrawPrototypeTexturingEnabled",
                false
        );
        report.addProperty(
                "sectionLayerDrawPrototypeDebugFragmentColor",
                "MAGENTA"
        );
        report.addProperty(
                "sectionLayerDrawPrototypePushConstantBytes",
                PUSH_CONSTANT_BYTES
        );

        return pipeline;
    }

    @Override
    public synchronized void close() {
        if (pipeline != NULL) {
            vkDestroyPipeline(
                    device,
                    pipeline,
                    null
            );

            pipeline = NULL;
        }

        if (pipelineLayout != NULL) {
            vkDestroyPipelineLayout(
                    device,
                    pipelineLayout,
                    null
            );

            pipelineLayout = NULL;
        }
    }
}
