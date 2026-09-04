package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

/**
 * GPU-only Vulkan -> OpenGL presentation bridge for the first visible Potato
 * world cutover.
 *
 * <p>The Minecraft window and MainTarget remain OpenGL-owned. Vulkan renders
 * SOLID terrain into dedicated exportable color/depth images. OpenGL imports
 * those allocations with EXT_memory_object_win32 and synchronizes with two
 * external binary semaphores. The color image is alpha-composited over the
 * already rendered Minecraft background while the Vulkan depth image is sampled
 * and written through gl_FragDepth in the same fullscreen pass. Later vanilla
 * layers/entities therefore consume the Vulkan terrain depth without a format-
 * sensitive framebuffer depth blit.</p>
 *
 * <p>No CPU readback, glFinish, vkWaitForFences or queue-idle operation occurs
 * per frame. A blocking drain is allowed only when the shared target is resized
 * or destroyed.</p>
 */
final class VulkanOpenGlPresentationBridge implements AutoCloseable {
    private static final boolean VISIBLE_SOLID_ENABLED =
            Boolean.parseBoolean(
                    System.getProperty(
                            "potato.vulkan.visibleSolid",
                            "true"
                    )
            );

    private static final int GL_HANDLE_TYPE_OPAQUE_WIN32 =
            org.lwjgl.opengl.EXTMemoryObjectWin32
                    .GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;

    private static final int GL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32 =
            org.lwjgl.opengl.EXTSemaphoreWin32
                    .GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;

    private static final String VERTEX_SHADER = """
            #version 330 core
            out vec2 potatoUv;
            void main() {
                vec2 uv = vec2(
                    float((gl_VertexID << 1) & 2),
                    float(gl_VertexID & 2)
                );
                potatoUv = uv;
                gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FRAGMENT_SHADER = """
            #version 330 core
            uniform sampler2D potatoColor;
            uniform sampler2D potatoDepth;
            in vec2 potatoUv;
            out vec4 potatoFragColor;
            void main() {
                vec2 uv = vec2(potatoUv.x, 1.0 - potatoUv.y);
                vec4 color = texture(potatoColor, uv);
                if (color.a <= 0.0001) {
                    discard;
                }
                potatoFragColor = color;
                gl_FragDepth = texture(potatoDepth, uv).r;
            }
            """;

    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final int graphicsQueueFamilyIndex;
    private final JsonObject report;

    private SharedTarget target;

    private long vkReadySemaphore = NULL;
    private long glReleasedSemaphore = NULL;

    private int glVkReadySemaphore;
    private int glReleasedSemaphoreObject;

    private int glProgram;
    private int glVao;
    private int glColorSamplerLocation = -1;
    private int glDepthSamplerLocation = -1;

    private boolean extensionGateEvaluated;
    private boolean extensionGatePassed;
    private boolean glImported;
    private boolean samplerBindingsInitialized;
    private boolean firstVulkanSubmission = true;
    private boolean glReleaseAvailable;
    private boolean firstCompositeValidated;
    private boolean disabledAfterFailure;
    private boolean closed;

    private long extensionGateCheckCount;
    private long prepareAttemptCount;
    private long prepareSuccessCount;
    private long visibleCommitQueuedCount;
    private long resizeCount;
    private long failureCount;
    private int targetGeneration;

    private String lastFailure = "";

    VulkanOpenGlPresentationBridge(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int graphicsQueueFamilyIndex,
            JsonObject report
    ) {
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.graphicsQueueFamilyIndex = graphicsQueueFamilyIndex;
        this.report = report;

        report.addProperty(
                "vulkanOpenGlPresentationBridgeInstalled",
                true
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeMode",
                "WIN32_EXTERNAL_MEMORY_SEMAPHORE_SINGLE_PASS_COLOR_DEPTH_SOLID_LAYER"
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeCpuReadback",
                false
        );
    }

    synchronized boolean prepare(
            int width,
            int height
    ) {
        prepareAttemptCount++;

        if (closed
                || disabledAfterFailure
                || !VISIBLE_SOLID_ENABLED
                || width <= 0
                || height <= 0) {
            enrich();
            return false;
        }

        try {
            if (!checkExtensionGate()) {
                enrich();
                return false;
            }

            if (target != null
                    && target.width == width
                    && target.height == height
                    && glImported) {
                prepareSuccessCount++;
                enrich();
                return true;
            }

            if (target != null) {
                drainForRecreation();
                destroyInteropObjects();
                resizeCount++;
            }

            createSharedTarget(
                    width,
                    height
            );
            createExternalSemaphores();
            importIntoOpenGl();
            ensureCompositeProgram();

            firstVulkanSubmission = true;
            glReleaseAvailable = false;
            firstCompositeValidated = false;
            prepareSuccessCount++;

            enrich();
            return true;
        } catch (Throwable throwable) {
            fail(throwable);
            return false;
        }
    }

    synchronized boolean ready() {
        return !closed
                && !disabledAfterFailure
                && extensionGatePassed
                && target != null
                && glImported
                && vkReadySemaphore != NULL
                && glReleasedSemaphore != NULL
                && glVkReadySemaphore != 0
                && glReleasedSemaphoreObject != 0
                && glProgram != 0
                && glVao != 0;
    }

    synchronized TargetView targetView() {
        if (!ready()) {
            return null;
        }

        return new TargetView(
                target.color.image,
                target.color.view,
                target.depth.image,
                target.depth.view,
                target.width,
                target.height,
                target.color.format,
                target.depth.format,
                target.generation
        );
    }

    synchronized boolean shouldWaitForOpenGlRelease() {
        return ready()
                && !firstVulkanSubmission
                && glReleaseAvailable;
    }

    synchronized long vulkanWaitSemaphore() {
        return shouldWaitForOpenGlRelease()
                ? glReleasedSemaphore
                : NULL;
    }

    synchronized long vulkanSignalSemaphore() {
        return ready()
                ? vkReadySemaphore
                : NULL;
    }

    synchronized void recordAcquireForVulkan(
            VkCommandBuffer commandBuffer
    ) {
        if (!ready()
                || commandBuffer == null) {
            return;
        }

        if (firstVulkanSubmission) {
            transitionFirstUse(
                    commandBuffer
            );
            return;
        }

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barriers =
                    VkImageMemoryBarrier.calloc(
                            2,
                            stack
                    );

            configureAcquireBarrier(
                    barriers.get(0),
                    target.color.image,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                            | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
            );

            configureAcquireBarrier(
                    barriers.get(1),
                    target.depth.image,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_ASPECT_DEPTH_BIT,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
            );

            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,
                    0,
                    null,
                    null,
                    barriers
            );
        }
    }

    synchronized void recordReleaseToOpenGl(
            VkCommandBuffer commandBuffer
    ) {
        if (!ready()
                || commandBuffer == null) {
            return;
        }

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barriers =
                    VkImageMemoryBarrier.calloc(
                            2,
                            stack
                    );

            configureReleaseBarrier(
                    barriers.get(0),
                    target.color.image,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
            );

            configureReleaseBarrier(
                    barriers.get(1),
                    target.depth.image,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_ASPECT_DEPTH_BIT,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
            );

            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,
                    VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    0,
                    null,
                    null,
                    barriers
            );
        }
    }

    synchronized void onVulkanSubmitted() {
        if (!ready()) {
            return;
        }

        firstVulkanSubmission = false;
        glReleaseAvailable = false;
    }

    /**
     * Enqueue the OpenGL half of the interop handoff. The EXT semaphore wait
     * is GPU-side; this method never waits for Vulkan on the CPU.
     */
    synchronized boolean enqueueComposite() {
        if (!ready()) {
            return false;
        }

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            IntBuffer viewport =
                    stack.mallocInt(4);

            glGetIntegerv(
                    GL_VIEWPORT,
                    viewport
            );

            int viewportX = viewport.get(0);
            int viewportY = viewport.get(1);
            int viewportWidth = viewport.get(2);
            int viewportHeight = viewport.get(3);

            if (viewportX != 0
                    || viewportY != 0
                    || viewportWidth != target.width
                    || viewportHeight != target.height) {
                report.addProperty(
                        "vulkanOpenGlPresentationBridgeViewportMismatch",
                        true
                );
                return false;
            }

            int previousProgram =
                    glGetInteger(
                            GL_CURRENT_PROGRAM
                    );
            int previousVao =
                    glGetInteger(
                            GL_VERTEX_ARRAY_BINDING
                    );
            int previousActiveTexture =
                    glGetInteger(
                            GL_ACTIVE_TEXTURE
                    );
            int previousDrawFbo =
                    glGetInteger(
                            GL_DRAW_FRAMEBUFFER_BINDING
                    );
            int previousDepthFunc =
                    glGetInteger(
                            GL_DEPTH_FUNC
                    );
            boolean previousDepthMask =
                    glGetInteger(
                            GL_DEPTH_WRITEMASK
                    ) != 0;

            boolean blendEnabled =
                    glIsEnabled(
                            GL_BLEND
                    );
            boolean depthEnabled =
                    glIsEnabled(
                            GL_DEPTH_TEST
                    );
            boolean cullEnabled =
                    glIsEnabled(
                            GL_CULL_FACE
                    );
            boolean scissorEnabled =
                    glIsEnabled(
                            GL_SCISSOR_TEST
                    );

            int previousBlendSrcRgb =
                    glGetInteger(
                            GL_BLEND_SRC_RGB
                    );
            int previousBlendDstRgb =
                    glGetInteger(
                            GL_BLEND_DST_RGB
                    );
            int previousBlendSrcAlpha =
                    glGetInteger(
                            GL_BLEND_SRC_ALPHA
                    );
            int previousBlendDstAlpha =
                    glGetInteger(
                            GL_BLEND_DST_ALPHA
                    );
            int previousBlendEquationRgb =
                    glGetInteger(
                            GL_BLEND_EQUATION_RGB
                    );
            int previousBlendEquationAlpha =
                    glGetInteger(
                            GL_BLEND_EQUATION_ALPHA
                    );

            glActiveTexture(
                    GL_TEXTURE0
            );

            int previousTexture0 =
                    glGetInteger(
                            GL_TEXTURE_BINDING_2D
                    );

            glActiveTexture(
                    GL_TEXTURE1
            );

            int previousTexture1 =
                    glGetInteger(
                            GL_TEXTURE_BINDING_2D
                    );

            IntBuffer textures =
                    stack.ints(
                            target.glColorTexture,
                            target.glDepthTexture
                    );

            /*
             * LWJGL's EXT_semaphore wrapper dereferences buffers.remaining()
             * even when the native numBufferBarriers value is zero.
             *
             * Use a real stack-backed IntBuffer with limit=0 instead of Java
             * null. Native OpenGL still receives zero buffer barriers.
             */
            IntBuffer bufferBarriers =
                    stack.mallocInt(
                            1
                    );
            bufferBarriers.limit(
                    0
            );

            /*
             * EXT_semaphore srcLayouts describe the layout left by the
             * external API, not the layout OpenGL intends to use next.
             *
             * Vulkan releases both shared images without changing them away
             * from their attachment-optimal layouts. Tell GL that exact truth;
             * the driver performs any internal transition needed for shader
             * sampling after the semaphore wait.
             */
            IntBuffer waitLayouts =
                    stack.ints(
                            GL_LAYOUT_COLOR_ATTACHMENT_EXT,
                            GL_LAYOUT_DEPTH_STENCIL_ATTACHMENT_EXT
                    );

            if (!firstCompositeValidated) {
                int staleError =
                        glGetError();

                report.addProperty(
                        "vulkanOpenGlPresentationBridgePreCommitGlError",
                        staleError
                );
            }

            glWaitSemaphoreEXT(
                    glVkReadySemaphore,
                    bufferBarriers,
                    textures,
                    waitLayouts
            );

            validateFirstCompositeStage(
                    "WAIT_SEMAPHORE"
            );

            glBindFramebuffer(
                    GL_DRAW_FRAMEBUFFER,
                    previousDrawFbo
            );

            /*
             * One fullscreen pass now transfers BOTH color and terrain depth.
             *
             * The old framebuffer depth blit required matching depth formats
             * and returned GL_INVALID_OPERATION on the real Minecraft
             * MainTarget. Sampling the imported D32 Vulkan texture and writing
             * gl_FragDepth is format-agnostic on the destination side and also
             * removes a second framebuffer operation from every visible frame.
             */
            glEnable(
                    GL_DEPTH_TEST
            );
            glDepthFunc(
                    GL_ALWAYS
            );
            glDepthMask(
                    true
            );
            glDisable(
                    GL_CULL_FACE
            );
            glDisable(
                    GL_SCISSOR_TEST
            );
            glEnable(
                    GL_BLEND
            );
            glBlendEquationSeparate(
                    GL_FUNC_ADD,
                    GL_FUNC_ADD
            );
            glBlendFuncSeparate(
                    GL_SRC_ALPHA,
                    GL_ONE_MINUS_SRC_ALPHA,
                    GL_ONE,
                    GL_ONE_MINUS_SRC_ALPHA
            );

            glUseProgram(
                    glProgram
            );

            if (!samplerBindingsInitialized) {
                if (glColorSamplerLocation >= 0) {
                    glUniform1i(
                            glColorSamplerLocation,
                            0
                    );
                }

                if (glDepthSamplerLocation >= 0) {
                    glUniform1i(
                            glDepthSamplerLocation,
                            1
                    );
                }

                samplerBindingsInitialized =
                        true;
            }

            glBindVertexArray(
                    glVao
            );

            glActiveTexture(
                    GL_TEXTURE0
            );
            glBindTexture(
                    GL_TEXTURE_2D,
                    target.glColorTexture
            );

            glActiveTexture(
                    GL_TEXTURE1
            );
            glBindTexture(
                    GL_TEXTURE_2D,
                    target.glDepthTexture
            );

            glDrawArrays(
                    GL_TRIANGLES,
                    0,
                    3
            );

            validateFirstCompositeStage(
                    "COLOR_DEPTH_COMPOSITE"
            );

            IntBuffer signalLayouts =
                    stack.ints(
                            GL_LAYOUT_COLOR_ATTACHMENT_EXT,
                            GL_LAYOUT_DEPTH_STENCIL_ATTACHMENT_EXT
                    );

            glSignalSemaphoreEXT(
                    glReleasedSemaphoreObject,
                    bufferBarriers,
                    textures,
                    signalLayouts
            );

            /*
             * EXT_semaphore defines SignalSemaphoreEXT as flushing the GL
             * command stream. An additional glFlush here is redundant.
             */
            validateFirstCompositeStage(
                    "SIGNAL_SEMAPHORE"
            );

            glBindFramebuffer(
                    GL_DRAW_FRAMEBUFFER,
                    previousDrawFbo
            );

            glActiveTexture(
                    GL_TEXTURE1
            );
            glBindTexture(
                    GL_TEXTURE_2D,
                    previousTexture1
            );
            glActiveTexture(
                    GL_TEXTURE0
            );
            glBindTexture(
                    GL_TEXTURE_2D,
                    previousTexture0
            );
            glActiveTexture(
                    previousActiveTexture
            );
            glBindVertexArray(
                    previousVao
            );
            glUseProgram(
                    previousProgram
            );

            glDepthFunc(
                    previousDepthFunc
            );
            glDepthMask(
                    previousDepthMask
            );

            glBlendEquationSeparate(
                    previousBlendEquationRgb,
                    previousBlendEquationAlpha
            );
            glBlendFuncSeparate(
                    previousBlendSrcRgb,
                    previousBlendDstRgb,
                    previousBlendSrcAlpha,
                    previousBlendDstAlpha
            );

            restoreCapability(
                    GL_BLEND,
                    blendEnabled
            );
            restoreCapability(
                    GL_DEPTH_TEST,
                    depthEnabled
            );
            restoreCapability(
                    GL_CULL_FACE,
                    cullEnabled
            );
            restoreCapability(
                    GL_SCISSOR_TEST,
                    scissorEnabled
            );

            firstCompositeValidated = true;
            glReleaseAvailable = true;
            visibleCommitQueuedCount++;

            report.addProperty(
                    "vulkanOpenGlPresentationBridgeLastCommitQueued",
                    true
            );

            enrich();
            return true;
        } catch (Throwable throwable) {
            fail(throwable);
            return false;
        }
    }

    synchronized void enrich() {
        report.addProperty(
                "vulkanOpenGlPresentationBridgeEnabledByProperty",
                VISIBLE_SOLID_ENABLED
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeExtensionGateEvaluated",
                extensionGateEvaluated
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeExtensionGateCheckCount",
                extensionGateCheckCount
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeExtensionGatePassed",
                extensionGatePassed
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeGlImported",
                glImported
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeReady",
                ready()
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgePrepareAttemptCount",
                prepareAttemptCount
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgePrepareSuccessCount",
                prepareSuccessCount
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeVisibleCommitQueuedCount",
                visibleCommitQueuedCount
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeResizeCount",
                resizeCount
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeFailureCount",
                failureCount
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeDisabledAfterFailure",
                disabledAfterFailure
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeCpuReadback",
                false
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgePerFrameCpuWait",
                false
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeExplicitEmptyBufferBarrierList",
                true
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeWaitLayoutsMatchVulkanRelease",
                true
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeRedundantExplicitGlFlush",
                false
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeColorDepthSinglePass",
                true
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeDepthTransferMode",
                "FULLSCREEN_SHADER_GL_FRAG_DEPTH"
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeFramebufferDepthBlitUsed",
                false
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgePerCommitUniformLookup",
                false
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeFirstCompositeValidated",
                firstCompositeValidated
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeGlReleaseAvailable",
                glReleaseAvailable
        );

        if (target != null) {
            report.addProperty(
                    "vulkanOpenGlPresentationBridgeWidth",
                    target.width
            );
            report.addProperty(
                    "vulkanOpenGlPresentationBridgeHeight",
                    target.height
            );
            report.addProperty(
                    "vulkanOpenGlPresentationBridgeTargetGeneration",
                    target.generation
            );
            report.addProperty(
                    "vulkanOpenGlPresentationBridgeColorFormat",
                    target.color.format
            );
            report.addProperty(
                    "vulkanOpenGlPresentationBridgeDepthFormat",
                    target.depth.format
            );
        }

        if (!lastFailure.isBlank()) {
            report.addProperty(
                    "vulkanOpenGlPresentationBridgeLastFailure",
                    lastFailure
            );
        }
    }

    private boolean checkExtensionGate() {
        if (extensionGatePassed) {
            return true;
        }

        extensionGateEvaluated = true;
        extensionGateCheckCount++;

        if (!System.getProperty(
                "os.name",
                ""
        ).toLowerCase().contains(
                "windows"
        )) {
            report.addProperty(
                    "vulkanOpenGlPresentationBridgePlatformSupported",
                    false
            );
            return false;
        }

        boolean deviceExtensionsEnabled =
                report.has(
                        "vulkanOpenGlWin32InteropDeviceExtensionsEnabled"
                )
                        && report.get(
                        "vulkanOpenGlWin32InteropDeviceExtensionsEnabled"
                ).getAsBoolean();

        if (!deviceExtensionsEnabled) {
            return false;
        }

        GLCapabilities capabilities =
                GL.getCapabilities();

        boolean glMemory =
                capabilities.GL_EXT_memory_object;
        boolean glMemoryWin32 =
                capabilities.GL_EXT_memory_object_win32;
        boolean glSemaphore =
                capabilities.GL_EXT_semaphore;
        boolean glSemaphoreWin32 =
                capabilities.GL_EXT_semaphore_win32;

        report.addProperty(
                "openGlExtMemoryObjectAvailable",
                glMemory
        );
        report.addProperty(
                "openGlExtMemoryObjectWin32Available",
                glMemoryWin32
        );
        report.addProperty(
                "openGlExtSemaphoreAvailable",
                glSemaphore
        );
        report.addProperty(
                "openGlExtSemaphoreWin32Available",
                glSemaphoreWin32
        );

        extensionGatePassed =
                glMemory
                        && glMemoryWin32
                        && glSemaphore
                        && glSemaphoreWin32;

        report.addProperty(
                "vulkanOpenGlPresentationBridgePlatformSupported",
                extensionGatePassed
        );

        return extensionGatePassed;
    }

    private void createSharedTarget(
            int width,
            int height
    ) {
        Attachment color =
                createAttachment(
                        width,
                        height,
                        VK_FORMAT_R8G8B8A8_UNORM,
                        VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                                | VK_IMAGE_USAGE_SAMPLED_BIT
                                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                        VK_IMAGE_ASPECT_COLOR_BIT
                );

        Attachment depth = null;

        try {
            depth =
                    createAttachment(
                            width,
                            height,
                            VK_FORMAT_D32_SFLOAT,
                            VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                                    | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                            VK_IMAGE_ASPECT_DEPTH_BIT
                    );

            target =
                    new SharedTarget(
                            color,
                            depth,
                            width,
                            height,
                            ++targetGeneration
                    );
        } catch (Throwable throwable) {
            destroyAttachment(
                    depth
            );
            destroyAttachment(
                    color
            );
            throw throwable;
        }
    }

    private Attachment createAttachment(
            int width,
            int height,
            int format,
            int usage,
            int aspectMask
    ) {
        long image = NULL;
        long memory = NULL;
        long view = NULL;

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkExternalMemoryImageCreateInfo externalImageInfo =
                    VkExternalMemoryImageCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .handleTypes(
                                    VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
                            );

            VkImageCreateInfo imageInfo =
                    VkImageCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .pNext(
                                    externalImageInfo.address()
                            )
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
                            width
                    )
                    .height(
                            height
                    )
                    .depth(
                            1
                    );

            LongBuffer imagePointer =
                    stack.mallocLong(1);

            int result =
                    vkCreateImage(
                            device,
                            imageInfo,
                            null,
                            imagePointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_EXTERNAL_SHARED_IMAGE",
                        "vkCreateImage failed with VkResult "
                                + result
                );
            }

            image =
                    imagePointer.get(0);

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

            VkMemoryDedicatedAllocateInfo dedicatedInfo =
                    VkMemoryDedicatedAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .image(
                                    image
                            );

            VkExportMemoryAllocateInfo exportInfo =
                    VkExportMemoryAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .pNext(
                                    dedicatedInfo.address()
                            )
                            .handleTypes(
                                    VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
                            );

            VkMemoryAllocateInfo allocateInfo =
                    VkMemoryAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .pNext(
                                    exportInfo.address()
                            )
                            .allocationSize(
                                    requirements.size()
                            )
                            .memoryTypeIndex(
                                    memoryTypeIndex
                            );

            LongBuffer memoryPointer =
                    stack.mallocLong(1);

            result =
                    vkAllocateMemory(
                            device,
                            allocateInfo,
                            null,
                            memoryPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "ALLOCATE_EXTERNAL_SHARED_MEMORY",
                        "vkAllocateMemory failed with VkResult "
                                + result
                );
            }

            memory =
                    memoryPointer.get(0);

            result =
                    vkBindImageMemory(
                            device,
                            image,
                            memory,
                            0L
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "BIND_EXTERNAL_SHARED_MEMORY",
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
                    stack.mallocLong(1);

            result =
                    vkCreateImageView(
                            device,
                            viewInfo,
                            null,
                            viewPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_EXTERNAL_SHARED_IMAGE_VIEW",
                        "vkCreateImageView failed with VkResult "
                                + result
                );
            }

            view =
                    viewPointer.get(0);

            VkMemoryGetWin32HandleInfoKHR handleInfo =
                    VkMemoryGetWin32HandleInfoKHR.calloc(
                            stack
                    )
                            .sType$Default()
                            .memory(
                                    memory
                            )
                            .handleType(
                                    VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
                            );

            PointerBuffer handlePointer =
                    stack.mallocPointer(1);

            result =
                    vkGetMemoryWin32HandleKHR(
                            device,
                            handleInfo,
                            handlePointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "EXPORT_EXTERNAL_SHARED_MEMORY_HANDLE",
                        "vkGetMemoryWin32HandleKHR failed with VkResult "
                                + result
                );
            }

            long win32Handle =
                    handlePointer.get(0);

            if (win32Handle == NULL) {
                throw new VulkanProbeException(
                        "EXPORT_EXTERNAL_SHARED_MEMORY_HANDLE",
                        "Vulkan returned a null Win32 memory handle."
                );
            }

            return new Attachment(
                    image,
                    memory,
                    view,
                    format,
                    requirements.size(),
                    win32Handle,
                    aspectMask
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
                        (memoryTypeBits & (1 << index)) != 0;

                int flags =
                        properties.memoryTypes(index)
                                .propertyFlags();

                if (supported
                        && (flags & requiredFlags)
                        == requiredFlags) {
                    return index;
                }
            }
        }

        throw new VulkanProbeException(
                "FIND_EXTERNAL_SHARED_MEMORY_TYPE",
                "No compatible device-local memory type was found."
        );
    }

    private void createExternalSemaphores() {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            vkReadySemaphore =
                    createExportableSemaphore(
                            stack
                    );

            glReleasedSemaphore =
                    createExportableSemaphore(
                            stack
                    );

            long vkReadyHandle =
                    exportSemaphoreHandle(
                            vkReadySemaphore,
                            stack
                    );

            long glReleasedHandle =
                    exportSemaphoreHandle(
                            glReleasedSemaphore,
                            stack
                    );

            glVkReadySemaphore =
                    glGenSemaphoresEXT();

            glReleasedSemaphoreObject =
                    glGenSemaphoresEXT();

            glImportSemaphoreWin32HandleEXT(
                    glVkReadySemaphore,
                    GL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32,
                    vkReadyHandle
            );

            glImportSemaphoreWin32HandleEXT(
                    glReleasedSemaphoreObject,
                    GL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32,
                    glReleasedHandle
            );
        }
    }

    private long createExportableSemaphore(
            MemoryStack stack
    ) {
        VkExportSemaphoreCreateInfo exportInfo =
                VkExportSemaphoreCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .handleTypes(
                                VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT
                        );

        VkSemaphoreCreateInfo semaphoreInfo =
                VkSemaphoreCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .pNext(
                                exportInfo.address()
                        );

        LongBuffer pointer =
                stack.mallocLong(1);

        int result =
                vkCreateSemaphore(
                        device,
                        semaphoreInfo,
                        null,
                        pointer
                );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "CREATE_EXTERNAL_INTEROP_SEMAPHORE",
                    "vkCreateSemaphore failed with VkResult "
                            + result
            );
        }

        return pointer.get(0);
    }

    private long exportSemaphoreHandle(
            long semaphore,
            MemoryStack stack
    ) {
        VkSemaphoreGetWin32HandleInfoKHR handleInfo =
                VkSemaphoreGetWin32HandleInfoKHR.calloc(
                        stack
                )
                        .sType$Default()
                        .semaphore(
                                semaphore
                        )
                        .handleType(
                                VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT
                        );

        PointerBuffer pointer =
                stack.mallocPointer(1);

        int result =
                vkGetSemaphoreWin32HandleKHR(
                        device,
                        handleInfo,
                        pointer
                );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "EXPORT_EXTERNAL_INTEROP_SEMAPHORE",
                    "vkGetSemaphoreWin32HandleKHR failed with VkResult "
                            + result
            );
        }

        long handle =
                pointer.get(0);

        if (handle == NULL) {
            throw new VulkanProbeException(
                    "EXPORT_EXTERNAL_INTEROP_SEMAPHORE",
                    "Vulkan returned a null Win32 semaphore handle."
            );
        }

        return handle;
    }

    private void importIntoOpenGl() {
        if (target == null) {
            throw new IllegalStateException(
                    "Shared target does not exist."
            );
        }

        int previousActiveTexture =
                glGetInteger(GL_ACTIVE_TEXTURE);
        int previousTexture =
                glGetInteger(GL_TEXTURE_BINDING_2D);
        int previousReadFbo =
                glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int previousDrawFbo =
                glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);

        glActiveTexture(GL_TEXTURE0);

        target.color.glMemoryObject =
                glCreateMemoryObjectsEXT();

        glMemoryObjectParameteriEXT(
                target.color.glMemoryObject,
                GL_DEDICATED_MEMORY_OBJECT_EXT,
                GL_TRUE
        );

        glImportMemoryWin32HandleEXT(
                target.color.glMemoryObject,
                target.color.allocationBytes,
                GL_HANDLE_TYPE_OPAQUE_WIN32,
                target.color.win32Handle
        );

        target.color.glTexture =
                glGenTextures();

        glBindTexture(
                GL_TEXTURE_2D,
                target.color.glTexture
        );
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_TILING_EXT,
                GL_OPTIMAL_TILING_EXT
        );
        glTexStorageMem2DEXT(
                GL_TEXTURE_2D,
                1,
                GL_RGBA8,
                target.width,
                target.height,
                target.color.glMemoryObject,
                0L
        );
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_MIN_FILTER,
                GL_NEAREST
        );
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_MAG_FILTER,
                GL_NEAREST
        );
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_WRAP_S,
                GL_CLAMP_TO_EDGE
        );
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_WRAP_T,
                GL_CLAMP_TO_EDGE
        );

        target.depth.glMemoryObject =
                glCreateMemoryObjectsEXT();

        glMemoryObjectParameteriEXT(
                target.depth.glMemoryObject,
                GL_DEDICATED_MEMORY_OBJECT_EXT,
                GL_TRUE
        );

        glImportMemoryWin32HandleEXT(
                target.depth.glMemoryObject,
                target.depth.allocationBytes,
                GL_HANDLE_TYPE_OPAQUE_WIN32,
                target.depth.win32Handle
        );

        target.depth.glTexture =
                glGenTextures();

        glBindTexture(
                GL_TEXTURE_2D,
                target.depth.glTexture
        );
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_TILING_EXT,
                GL_OPTIMAL_TILING_EXT
        );
        glTexStorageMem2DEXT(
                GL_TEXTURE_2D,
                1,
                GL_DEPTH_COMPONENT32F,
                target.width,
                target.height,
                target.depth.glMemoryObject,
                0L
        );
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_MIN_FILTER,
                GL_NEAREST
        );
        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_MAG_FILTER,
                GL_NEAREST
        );

        target.glColorTexture =
                target.color.glTexture;
        target.glDepthTexture =
                target.depth.glTexture;

        /*
         * 068 no longer needs to attach the imported textures to an OpenGL
         * read framebuffer. Both color and depth are sampled directly by the
         * fullscreen presentation shader.
         */
        target.glFramebuffer =
                0;

        glBindFramebuffer(
                GL_READ_FRAMEBUFFER,
                previousReadFbo
        );
        glBindFramebuffer(
                GL_DRAW_FRAMEBUFFER,
                previousDrawFbo
        );
        glBindTexture(
                GL_TEXTURE_2D,
                previousTexture
        );
        glActiveTexture(
                previousActiveTexture
        );

        int error =
                glGetError();

        if (error != GL_NO_ERROR) {
            throw new VulkanProbeException(
                    "IMPORT_EXTERNAL_SHARED_TEXTURES",
                    "OpenGL external-memory import returned GL error "
                            + error
            );
        }

        glImported = true;

        report.addProperty(
                "vulkanOpenGlPresentationBridgeColorMemoryImported",
                true
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeDepthMemoryImported",
                true
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeSemaphoresImported",
                true
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeExternalReadFramebufferRequired",
                false
        );
    }

    private void ensureCompositeProgram() {
        if (glProgram != 0
                && glVao != 0) {
            return;
        }

        int vertex =
                compileShader(
                        GL_VERTEX_SHADER,
                        VERTEX_SHADER
                );

        int fragment =
                compileShader(
                        GL_FRAGMENT_SHADER,
                        FRAGMENT_SHADER
                );

        int program =
                glCreateProgram();

        try {
            glAttachShader(
                    program,
                    vertex
            );
            glAttachShader(
                    program,
                    fragment
            );
            glLinkProgram(
                    program
            );

            if (glGetProgrami(
                    program,
                    GL_LINK_STATUS
            ) == GL_FALSE) {
                throw new VulkanProbeException(
                        "LINK_EXTERNAL_PRESENTATION_SHADER",
                        glGetProgramInfoLog(
                                program
                        )
                );
            }

            glProgram =
                    program;
            glVao =
                    glGenVertexArrays();
            glColorSamplerLocation =
                    glGetUniformLocation(
                            glProgram,
                            "potatoColor"
                    );
            glDepthSamplerLocation =
                    glGetUniformLocation(
                            glProgram,
                            "potatoDepth"
                    );
        } catch (Throwable throwable) {
            glDeleteProgram(
                    program
            );
            throw throwable;
        } finally {
            glDeleteShader(
                    vertex
            );
            glDeleteShader(
                    fragment
            );
        }
    }

    private int compileShader(
            int type,
            String source
    ) {
        int shader =
                glCreateShader(
                        type
                );

        glShaderSource(
                shader,
                source
        );
        glCompileShader(
                shader
        );

        if (glGetShaderi(
                shader,
                GL_COMPILE_STATUS
        ) == GL_FALSE) {
            String log =
                    glGetShaderInfoLog(
                            shader
                    );
            glDeleteShader(
                    shader
            );

            throw new VulkanProbeException(
                    "COMPILE_EXTERNAL_PRESENTATION_SHADER",
                    log
            );
        }

        return shader;
    }

    private void transitionFirstUse(
            VkCommandBuffer commandBuffer
    ) {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barriers =
                    VkImageMemoryBarrier.calloc(
                            2,
                            stack
                    );

            configureFirstUseBarrier(
                    barriers.get(0),
                    target.color.image,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                            | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
            );

            configureFirstUseBarrier(
                    barriers.get(1),
                    target.depth.image,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_ASPECT_DEPTH_BIT,
                    VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
            );

            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_ALL_GRAPHICS_BIT,
                    0,
                    null,
                    null,
                    barriers
            );
        }
    }

    private void configureFirstUseBarrier(
            VkImageMemoryBarrier barrier,
            long image,
            int newLayout,
            int aspectMask,
            int dstAccessMask
    ) {
        barrier.sType$Default()
                .srcAccessMask(0)
                .dstAccessMask(dstAccessMask)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(graphicsQueueFamilyIndex)
                .image(image);

        barrier.subresourceRange()
                .aspectMask(aspectMask)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    private void configureAcquireBarrier(
            VkImageMemoryBarrier barrier,
            long image,
            int layout,
            int aspectMask,
            int dstAccessMask
    ) {
        barrier.sType$Default()
                .srcAccessMask(0)
                .dstAccessMask(dstAccessMask)
                .oldLayout(layout)
                .newLayout(layout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL)
                .dstQueueFamilyIndex(graphicsQueueFamilyIndex)
                .image(image);

        barrier.subresourceRange()
                .aspectMask(aspectMask)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    private void configureReleaseBarrier(
            VkImageMemoryBarrier barrier,
            long image,
            int layout,
            int aspectMask,
            int srcAccessMask
    ) {
        barrier.sType$Default()
                .srcAccessMask(srcAccessMask)
                .dstAccessMask(0)
                .oldLayout(layout)
                .newLayout(layout)
                .srcQueueFamilyIndex(graphicsQueueFamilyIndex)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_EXTERNAL)
                .image(image);

        barrier.subresourceRange()
                .aspectMask(aspectMask)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);
    }

    private void validateFirstCompositeStage(
            String stage
    ) {
        if (firstCompositeValidated) {
            return;
        }

        int error =
                glGetError();

        report.addProperty(
                "vulkanOpenGlPresentationBridgeFirstCompositeStage",
                stage
        );
        report.addProperty(
                "vulkanOpenGlPresentationBridgeFirstCompositeStageGlError",
                error
        );

        if (error != GL_NO_ERROR) {
            throw new VulkanProbeException(
                    "OPENGL_PRESENTATION_" + stage,
                    "OpenGL presentation bridge stage "
                            + stage
                            + " returned GL error "
                            + error
            );
        }
    }

    private void restoreCapability(
            int capability,
            boolean enabled
    ) {
        if (enabled) {
            glEnable(
                    capability
            );
        } else {
            glDisable(
                    capability
            );
        }
    }

    private void drainForRecreation() {
        glFinish();

        int result =
                vkDeviceWaitIdle(
                        device
                );

        report.addProperty(
                "vulkanOpenGlPresentationBridgeResizeDeviceWaitIdleResult",
                result
        );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "DRAIN_EXTERNAL_PRESENTATION_TARGET",
                    "vkDeviceWaitIdle failed with VkResult "
                            + result
            );
        }
    }

    private void fail(
            Throwable throwable
    ) {
        failureCount++;
        disabledAfterFailure = true;
        lastFailure =
                throwable.getClass().getName()
                        + ": "
                        + String.valueOf(
                        throwable.getMessage()
                );

        report.addProperty(
                "vulkanOpenGlPresentationBridgeLastCommitQueued",
                false
        );

        enrich();
    }

    private void destroyInteropObjects() {
        glImported = false;
        glReleaseAvailable = false;

        if (glVkReadySemaphore != 0) {
            glDeleteSemaphoresEXT(
                    glVkReadySemaphore
            );
            glVkReadySemaphore = 0;
        }

        if (glReleasedSemaphoreObject != 0) {
            glDeleteSemaphoresEXT(
                    glReleasedSemaphoreObject
            );
            glReleasedSemaphoreObject = 0;
        }

        if (glVao != 0) {
            glDeleteVertexArrays(
                    glVao
            );
            glVao = 0;
        }

        if (glProgram != 0) {
            glDeleteProgram(
                    glProgram
            );
            glProgram = 0;
        }

        if (target != null) {
            if (target.glFramebuffer != 0) {
                glDeleteFramebuffers(
                        target.glFramebuffer
                );
                target.glFramebuffer = 0;
            }

            destroyGlAttachment(
                    target.color
            );
            destroyGlAttachment(
                    target.depth
            );
        }

        if (vkReadySemaphore != NULL) {
            vkDestroySemaphore(
                    device,
                    vkReadySemaphore,
                    null
            );
            vkReadySemaphore = NULL;
        }

        if (glReleasedSemaphore != NULL) {
            vkDestroySemaphore(
                    device,
                    glReleasedSemaphore,
                    null
            );
            glReleasedSemaphore = NULL;
        }

        if (target != null) {
            destroyAttachment(
                    target.depth
            );
            destroyAttachment(
                    target.color
            );
            target = null;
        }
    }

    private void destroyGlAttachment(
            Attachment attachment
    ) {
        if (attachment == null) {
            return;
        }

        if (attachment.glTexture != 0) {
            glDeleteTextures(
                    attachment.glTexture
            );
            attachment.glTexture = 0;
        }

        if (attachment.glMemoryObject != 0) {
            glDeleteMemoryObjectsEXT(
                    attachment.glMemoryObject
            );
            attachment.glMemoryObject = 0;
        }
    }

    private void destroyAttachment(
            Attachment attachment
    ) {
        if (attachment == null) {
            return;
        }

        if (attachment.view != NULL) {
            vkDestroyImageView(
                    device,
                    attachment.view,
                    null
            );
        }

        if (attachment.image != NULL) {
            vkDestroyImage(
                    device,
                    attachment.image,
                    null
            );
        }

        if (attachment.memory != NULL) {
            vkFreeMemory(
                    device,
                    attachment.memory,
                    null
            );
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;

        try {
            if (target != null
                    || vkReadySemaphore != NULL
                    || glReleasedSemaphore != NULL) {
                drainForRecreation();
            }
        } catch (Throwable throwable) {
            failureCount++;
            lastFailure =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );
        }

        try {
            destroyInteropObjects();
        } catch (Throwable throwable) {
            failureCount++;
            lastFailure =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );
        }

        report.addProperty(
                "vulkanOpenGlPresentationBridgeClosed",
                true
        );

        enrich();
    }

    record TargetView(
            long colorImage,
            long colorImageView,
            long depthImage,
            long depthImageView,
            int width,
            int height,
            int colorFormat,
            int depthFormat,
            int generation
    ) {
    }

    private static final class SharedTarget {
        private final Attachment color;
        private final Attachment depth;
        private final int width;
        private final int height;
        private final int generation;

        private int glColorTexture;
        private int glDepthTexture;
        private int glFramebuffer;

        private SharedTarget(
                Attachment color,
                Attachment depth,
                int width,
                int height,
                int generation
        ) {
            this.color = color;
            this.depth = depth;
            this.width = width;
            this.height = height;
            this.generation = generation;
        }
    }

    private static final class Attachment {
        private final long image;
        private final long memory;
        private final long view;
        private final int format;
        private final long allocationBytes;
        private final long win32Handle;
        private final int aspectMask;

        private int glMemoryObject;
        private int glTexture;

        private Attachment(
                long image,
                long memory,
                long view,
                int format,
                long allocationBytes,
                long win32Handle,
                int aspectMask
        ) {
            this.image = image;
            this.memory = memory;
            this.view = view;
            this.format = format;
            this.allocationBytes = allocationBytes;
            this.win32Handle = win32Handle;
            this.aspectMask = aspectMask;
        }
    }
}
