package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Graphics pipeline used by Potato's offscreen render-target prototype.
 *
 * <p>The pipeline is render-pass-free and declares the offscreen color/depth
 * attachment formats through {@link VkPipelineRenderingCreateInfo}. Vertex
 * positions and colors come from {@code gl_VertexIndex}, so no vertex buffer or
 * descriptor setup is needed for this transition primitive.</p>
 */
final class VulkanTrianglePipeline implements AutoCloseable {
    private static final String VERTEX_SHADER =
            "assets/potato_runtime/shaders/vulkan/probe/triangle.vert";

    private static final String FRAGMENT_SHADER =
            "assets/potato_runtime/shaders/vulkan/probe/triangle.frag";

    private final VkDevice device;

    private long pipelineLayout;
    private long pipeline;

    private VulkanTrianglePipeline(
            VkDevice device,
            long pipelineLayout,
            long pipeline
    ) {
        this.device = device;
        this.pipelineLayout = pipelineLayout;
        this.pipeline = pipeline;
    }

    static VulkanTrianglePipeline create(
            VkDevice device,
            int colorFormat,
            int depthFormat,
            boolean useDepth,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty("stage", "COMPILE_SHADERS");

        try (
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
                        )
        ) {
            long vertexModule =
                    createShaderModule(
                            device,
                            vertex.bytes(),
                            stack,
                            "vertex"
                    );

            long fragmentModule = NULL;

            try {
                fragmentModule =
                        createShaderModule(
                                device,
                                fragment.bytes(),
                                stack,
                                "fragment"
                        );

                report.addProperty(
                        "shaderModulesCreated",
                        true
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
                                    depthFormat,
                                    useDepth,
                                    stack,
                                    report
                            );

                    report.addProperty(
                            "graphicsPipelineCreated",
                            true
                    );

                    return new VulkanTrianglePipeline(
                            device,
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

                report.addProperty(
                        "shaderModulesDestroyedAfterPipelineCreation",
                        true
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

    private static long createShaderModule(
            VkDevice device,
            java.nio.ByteBuffer spirv,
            MemoryStack stack,
            String role
    ) {
        VkShaderModuleCreateInfo createInfo =
                VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default()
                        .pCode(spirv);

        LongBuffer pointer = stack.mallocLong(1);

        int result = vkCreateShaderModule(
                device,
                createInfo,
                null,
                pointer
        );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_SHADER_MODULES",
                    "vkCreateShaderModule("
                            + role
                            + ") failed with VkResult "
                            + result
            );
        }

        long module = pointer.get(0);

        if (module == NULL) {
            throw new VulkanProbeException(
                    "CREATE_SHADER_MODULES",
                    "vkCreateShaderModule("
                            + role
                            + ") returned NULL."
            );
        }

        return module;
    }

    private static long createPipelineLayout(
            VkDevice device,
            MemoryStack stack
    ) {
        VkPushConstantRange.Buffer pushConstantRange =
                VkPushConstantRange.calloc(1, stack);

        pushConstantRange.get(0)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                .offset(0)
                .size(Float.BYTES);

        VkPipelineLayoutCreateInfo createInfo =
                VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pPushConstantRanges(pushConstantRange);

        LongBuffer pointer = stack.mallocLong(1);

        int result = vkCreatePipelineLayout(
                device,
                createInfo,
                null,
                pointer
        );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_PIPELINE_LAYOUT",
                    "vkCreatePipelineLayout failed with VkResult "
                            + result
            );
        }

        long layout = pointer.get(0);

        if (layout == NULL) {
            throw new VulkanProbeException(
                    "CREATE_PIPELINE_LAYOUT",
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
            boolean useDepth,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty("stage", "CREATE_GRAPHICS_PIPELINE");

        VkPipelineShaderStageCreateInfo.Buffer shaderStages =
                VkPipelineShaderStageCreateInfo.calloc(2, stack);

        shaderStages.get(0)
                .sType$Default()
                .stage(VK_SHADER_STAGE_VERTEX_BIT)
                .module(vertexModule)
                .pName(stack.UTF8("main"));

        shaderStages.get(1)
                .sType$Default()
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(fragmentModule)
                .pName(stack.UTF8("main"));

        VkPipelineVertexInputStateCreateInfo vertexInput =
                VkPipelineVertexInputStateCreateInfo.calloc(stack)
                        .sType$Default();

        VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
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
                        .polygonMode(VK_POLYGON_MODE_FILL)
                        .cullMode(VK_CULL_MODE_NONE)
                        .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                        .depthBiasEnable(false)
                        .lineWidth(1.0f);

        VkPipelineMultisampleStateCreateInfo multisample =
                VkPipelineMultisampleStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
                        .sampleShadingEnable(false);

        VkPipelineDepthStencilStateCreateInfo depthStencil =
                VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .depthTestEnable(useDepth)
                        .depthWriteEnable(useDepth)
                        .depthCompareOp(VK_COMPARE_OP_LESS)
                        .depthBoundsTestEnable(false)
                        .stencilTestEnable(false)
                        .minDepthBounds(0.0f)
                        .maxDepthBounds(1.0f);

        VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                VkPipelineColorBlendAttachmentState.calloc(1, stack);

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
                        .pAttachments(blendAttachment);

        IntBuffer dynamicStates = stack.ints(
                VK_DYNAMIC_STATE_VIEWPORT,
                VK_DYNAMIC_STATE_SCISSOR
        );

        VkPipelineDynamicStateCreateInfo dynamicState =
                VkPipelineDynamicStateCreateInfo.calloc(stack)
                        .sType$Default()
                        .pDynamicStates(dynamicStates);

        IntBuffer attachmentFormats =
                stack.ints(colorFormat);

        VkPipelineRenderingCreateInfo renderingInfo =
                VkPipelineRenderingCreateInfo.calloc(stack)
                        .sType$Default()
                        .colorAttachmentCount(1)
                        .pColorAttachmentFormats(
                                attachmentFormats
                        )
                        .depthAttachmentFormat(
                                useDepth
                                        ? depthFormat
                                        : VK_FORMAT_UNDEFINED
                        )
                        .stencilAttachmentFormat(
                                VK_FORMAT_UNDEFINED
                        );

        VkGraphicsPipelineCreateInfo.Buffer pipelineInfo =
                VkGraphicsPipelineCreateInfo.calloc(1, stack);

        pipelineInfo.get(0)
                .sType$Default()
                .pNext(renderingInfo.address())
                .pStages(shaderStages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly)
                .pViewportState(viewportState)
                .pRasterizationState(rasterization)
                .pMultisampleState(multisample)
                .pDepthStencilState(depthStencil)
                .pColorBlendState(colorBlend)
                .pDynamicState(dynamicState)
                .layout(layout)
                .renderPass(NULL)
                .subpass(0);

        LongBuffer pipelinePointer = stack.mallocLong(1);

        int result = vkCreateGraphicsPipelines(
                device,
                NULL,
                pipelineInfo,
                null,
                pipelinePointer
        );

        report.addProperty(
                "vkCreateGraphicsPipelinesResult",
                result
        );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_GRAPHICS_PIPELINE",
                    "vkCreateGraphicsPipelines failed with VkResult "
                            + result
            );
        }

        long pipeline = pipelinePointer.get(0);

        if (pipeline == NULL) {
            throw new VulkanProbeException(
                    "CREATE_GRAPHICS_PIPELINE",
                    "vkCreateGraphicsPipelines returned NULL."
            );
        }

        report.addProperty(
                "dynamicRenderingPipeline",
                true
        );
        report.addProperty(
                "pipelineColorAttachmentFormat",
                colorFormat
        );
        report.addProperty(
                "pipelineDepthAttachmentFormat",
                useDepth
                        ? depthFormat
                        : VK_FORMAT_UNDEFINED
        );
        report.addProperty(
                "pipelineUsesDepthAttachment",
                useDepth
        );
        report.addProperty(
                "pipelineDepthTestEnabled",
                useDepth
        );
        report.addProperty(
                "pipelineDepthWriteEnabled",
                useDepth
        );
        report.addProperty(
                "pipelineTargetsOffscreenAttachments",
                true
        );

        return pipeline;
    }

    @Override
    public void close() {
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