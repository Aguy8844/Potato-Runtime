package dev.ordovicium.potato.render.backend.target;

/**
 * Backend-neutral semantic sink for live Minecraft RenderTarget operations.
 *
 * <p>The OpenGL transition remains the active Minecraft renderer. A backend
 * sink receives mirrored logical operations so a replacement backend can prove
 * runtime wiring before it owns the real target.</p>
 */
public interface RenderTargetOperationSink {
    void onSetClearColor(
            int width,
            int height,
            float red,
            float green,
            float blue,
            float alpha
    );

    void onResize(
            int width,
            int height,
            boolean useDepth
    );

    void onBindRead(
            int width,
            int height
    );

    void onUnbindRead(
            int width,
            int height
    );

    void onBindWrite(
            int width,
            int height,
            boolean updateViewport
    );

    void onClear(
            int width,
            int height,
            boolean useDepth,
            float red,
            float green,
            float blue,
            float alpha
    );

    void onBlitToScreen(
            int sourceWidth,
            int sourceHeight,
            int destinationWidth,
            int destinationHeight,
            boolean disableBlend
    );

    void onCopyDepth(
            int width,
            int height
    );
}
