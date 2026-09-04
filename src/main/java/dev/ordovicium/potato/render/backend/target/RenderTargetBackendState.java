package dev.ordovicium.potato.render.backend.target;

import com.google.gson.JsonObject;

/**
 * Per-instance backend state attached to each Minecraft RenderTarget by Mixin.
 *
 * <p>Logical target state and native GPU handles are intentionally represented
 * separately. OpenGL integer IDs are diagnostic snapshots only; Vulkan will
 * never reinterpret them as backend-neutral resource identifiers.</p>
 */
public final class RenderTargetBackendState {
    private RenderTargetRole role =
            RenderTargetRole.GENERIC;

    private RenderTargetResourceOwner resourceOwner =
            RenderTargetResourceOwner.OPENGL_TRANSITION;

    private boolean useDepth;

    private int width;
    private int height;
    private int viewWidth;
    private int viewHeight;

    private int openGlFramebufferId = -1;
    private int openGlColorTextureId = -1;
    private int openGlDepthTextureId = -1;

    private int allocationGeneration;
    private int destroyGeneration;

    private int resizeRequests;
    private int createBuffersCalls;
    private int destroyBuffersCalls;

    private int bindReadCalls;
    private int unbindReadCalls;
    private int bindWriteCalls;
    private int unbindWriteCalls;
    private int clearCalls;
    private int blitCalls;
    private int copyDepthCalls;

    private boolean initialMainAllocationObserved;
    private boolean latestAllocationHasFramebuffer;
    private boolean latestAllocationHasColor;
    private boolean latestAllocationHasDepth;

    public synchronized void initialize(
            boolean useDepth
    ) {
        this.useDepth = useDepth;
    }

    public synchronized void markMain() {
        role = RenderTargetRole.MAIN;
    }

    public synchronized void observeSnapshot(
            int width,
            int height,
            int viewWidth,
            int viewHeight,
            int framebufferId,
            int colorTextureId,
            int depthTextureId
    ) {
        this.width = width;
        this.height = height;
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;

        this.openGlFramebufferId =
                framebufferId;
        this.openGlColorTextureId =
                colorTextureId;
        this.openGlDepthTextureId =
                depthTextureId;

        latestAllocationHasFramebuffer =
                framebufferId > 0;
        latestAllocationHasColor =
                colorTextureId > 0;
        latestAllocationHasDepth =
                !useDepth || depthTextureId > 0;
    }

    public synchronized void observeInitialMainAllocation() {
        initialMainAllocationObserved = true;
        allocationGeneration++;
    }

    public synchronized void observeResizeRequest() {
        resizeRequests++;
    }

    public synchronized void observeCreateBuffers() {
        createBuffersCalls++;
    }

    public synchronized void observeCreateBuffersComplete() {
        allocationGeneration++;
    }

    public synchronized void observeDestroyBuffers() {
        destroyBuffersCalls++;
    }

    public synchronized void observeDestroyBuffersComplete() {
        destroyGeneration++;
    }

    public synchronized void observeBindRead() {
        bindReadCalls++;
    }

    public synchronized void observeUnbindRead() {
        unbindReadCalls++;
    }

    public synchronized void observeBindWrite() {
        bindWriteCalls++;
    }

    public synchronized void observeUnbindWrite() {
        unbindWriteCalls++;
    }

    public synchronized void observeClear() {
        clearCalls++;
    }

    public synchronized void observeBlit() {
        blitCalls++;
    }

    public synchronized void observeCopyDepth() {
        copyDepthCalls++;
    }

    public synchronized int width() {
        return width;
    }

    public synchronized int height() {
        return height;
    }

    public synchronized boolean useDepth() {
        return useDepth;
    }

    public synchronized RenderTargetRole role() {
        return role;
    }

    public synchronized RenderTargetResourceOwner resourceOwner() {
        return resourceOwner;
    }

    public synchronized boolean initialMainOwnershipVerified() {
        return role == RenderTargetRole.MAIN
                && resourceOwner
                == RenderTargetResourceOwner.OPENGL_TRANSITION
                && initialMainAllocationObserved
                && width > 0
                && height > 0
                && viewWidth > 0
                && viewHeight > 0
                && latestAllocationHasFramebuffer
                && latestAllocationHasColor
                && latestAllocationHasDepth;
    }

    public synchronized void enrich(
            JsonObject report,
            String prefix
    ) {
        report.addProperty(
                prefix + "Role",
                role.name()
        );
        report.addProperty(
                prefix + "ResourceOwner",
                resourceOwner.name()
        );
        report.addProperty(
                prefix + "UseDepth",
                useDepth
        );

        report.addProperty(
                prefix + "Width",
                width
        );
        report.addProperty(
                prefix + "Height",
                height
        );
        report.addProperty(
                prefix + "ViewWidth",
                viewWidth
        );
        report.addProperty(
                prefix + "ViewHeight",
                viewHeight
        );

        report.addProperty(
                prefix + "OpenGlFramebufferId",
                openGlFramebufferId
        );
        report.addProperty(
                prefix + "OpenGlColorTextureId",
                openGlColorTextureId
        );
        report.addProperty(
                prefix + "OpenGlDepthTextureId",
                openGlDepthTextureId
        );

        report.addProperty(
                prefix + "FramebufferAllocated",
                latestAllocationHasFramebuffer
        );
        report.addProperty(
                prefix + "ColorAttachmentAllocated",
                latestAllocationHasColor
        );
        report.addProperty(
                prefix + "DepthAttachmentAllocated",
                latestAllocationHasDepth
        );

        report.addProperty(
                prefix + "InitialAllocationObserved",
                initialMainAllocationObserved
        );
        report.addProperty(
                prefix + "AllocationGeneration",
                allocationGeneration
        );
        report.addProperty(
                prefix + "DestroyGeneration",
                destroyGeneration
        );

        report.addProperty(
                prefix + "ResizeRequests",
                resizeRequests
        );
        report.addProperty(
                prefix + "CreateBuffersCalls",
                createBuffersCalls
        );
        report.addProperty(
                prefix + "DestroyBuffersCalls",
                destroyBuffersCalls
        );

        report.addProperty(
                prefix + "BindReadCalls",
                bindReadCalls
        );
        report.addProperty(
                prefix + "UnbindReadCalls",
                unbindReadCalls
        );
        report.addProperty(
                prefix + "BindWriteCalls",
                bindWriteCalls
        );
        report.addProperty(
                prefix + "UnbindWriteCalls",
                unbindWriteCalls
        );
        report.addProperty(
                prefix + "ClearCalls",
                clearCalls
        );
        report.addProperty(
                prefix + "BlitCalls",
                blitCalls
        );
        report.addProperty(
                prefix + "CopyDepthCalls",
                copyDepthCalls
        );

        report.addProperty(
                prefix + "InitialOwnershipVerified",
                initialMainOwnershipVerified()
        );
    }
}