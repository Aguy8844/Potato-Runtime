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
 * Graphics pipeline for Potato surface-tile GPU decoding.
 *
 * <p>No conventional vertex input exists. The vertex shader expands one
 * merged rectangle from gl_VertexIndex; the fragment shader reconstructs
 * exact per-tile material/shading from the descriptor set.</p>
 */
final class VulkanSurfaceTileDecodePipeline
        implements AutoCloseable {

    private static final String VERTEX_SHADER =
            "assets/potato_runtime/shaders/vulkan/surface_tile_decode.vert";

    private static final String FRAGMENT_SHADER =
            "assets/potato_runtime/shaders/vulkan/surface_tile_decode.frag";

    private final VkDevice device;

    private long pipelineLayout = NULL;
    private long pipeline = NULL;

    private VulkanSurfaceTileDecodePipeline(
            VkDevice device
    ) {
        this.device = device;
    }

    static VulkanSurfaceTileDecodePipeline create(
            VkDevice device,
            int colorFormat,
            long descriptorSetLayout,
            JsonObject report
    ) {
        if (descriptorSetLayout == NULL) {
            throw new VulkanProbeException(
                    "SURFACE_TILE_TEXTURED_PIPELINE",
                    "Descriptor-set layout is null."
            );
        }

        VulkanSurfaceTileDecodePipeline result =
                new VulkanSurfaceTileDecodePipeline(
                        device
                );

        try {
            result.createGraphicsPipeline(
                    colorFormat,
                    descriptorSetLayout,
                    report
            );

            return result;
        } catch (Throwable throwable) {
            result.close();
            throw throwable;
        }
    }

    long pipeline() {
        return pipeline;
    }

    long layout() {
        return pipelineLayout;
    }

    boolean created() {
        return pipeline != NULL
                && pipelineLayout != NULL;
    }

    private void createGraphicsPipeline(
            int colorFormat,
            long descriptorSetLayout,
            JsonObject report
    ) {
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
                            "SURFACE_TILE_TEXTURED_VERTEX"
                    );

            long fragmentModule = NULL;

            try {
                fragmentModule =
                        createShaderModule(
                                fragment.bytes(),
                                stack,
                                "SURFACE_TILE_TEXTURED_FRAGMENT"
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

                report.addProperty(
                        "surfaceTileTexturedPipelineLayoutCreateResult",
                        result
                );

                if (result != VK_SUCCESS) {
                    throw new VulkanProbeException(
                            "CREATE_SURFACE_TILE_TEXTURED_PIPELINE_LAYOUT",
                            "vkCreatePipelineLayout failed with VkResult "
                                    + result
                    );
                }

                pipelineLayout =
                        layoutPointer.get(
                                0
                        );

                VkPipelineShaderStageCreateInfo.Buffer stages =
                        VkPipelineShaderStageCreateInfo.calloc(
                                2,
                                stack
                        );

                stages.get(0)
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

                stages.get(1)
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

                VkPipelineDepthStencilStateCreateInfo depthStencil =
                        VkPipelineDepthStencilStateCreateInfo.calloc(
                                stack
                        )
                                .sType$Default()
                                .depthTestEnable(
                                        false
                                )
                                .depthWriteEnable(
                                        false
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

                LongBuffer pipelinePointer =
                        stack.mallocLong(
                                1
                        );

                result =
                        vkCreateGraphicsPipelines(
                                device,
                                NULL,
                                pipelineInfo,
                                null,
                                pipelinePointer
                        );

                report.addProperty(
                        "surfaceTileTexturedGraphicsPipelineCreateResult",
                        result
                );

                if (result != VK_SUCCESS) {
                    throw new VulkanProbeException(
                            "CREATE_SURFACE_TILE_TEXTURED_PIPELINE",
                            "vkCreateGraphicsPipelines failed with VkResult "
                                    + result
                    );
                }

                pipeline =
                        pipelinePointer.get(
                                0
                        );

                if (!created()) {
                    throw new VulkanProbeException(
                            "CREATE_SURFACE_TILE_TEXTURED_PIPELINE",
                            "Graphics pipeline creation returned a null Vulkan handle."
                    );
                }
            } finally {
                if (fragmentModule != NULL) {
                    vkDestroyShaderModule(
                            device,
                            fragmentModule,
                            null
                    );
                }

                if (vertexModule != NULL) {
                    vkDestroyShaderModule(
                            device,
                            vertexModule,
                            null
                    );
                }
            }
        }
    }

    private long createShaderModule(
            java.nio.ByteBuffer spirv,
            MemoryStack stack,
            String stage
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
                    "CREATE_" + stage + "_SHADER_MODULE",
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
                    "CREATE_" + stage + "_SHADER_MODULE",
                    "vkCreateShaderModule returned NULL."
            );
        }

        return module;
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