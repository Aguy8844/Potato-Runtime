package dev.ordovicium.potato.client;

import dev.ordovicium.potato.render.vulkan.VulkanGate10ImmediatePath;
import dev.ordovicium.potato.render.vulkan.VulkanRuntimeManager;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * NeoForge-native Gate-10 GUI boundary capture.
 *
 * <p>Both HUD and Screen rendering stay completely untouched. ScreenEvent.Post
 * proves the logical Screen boundary, but it is deliberately NOT the final pixel
 * capture boundary: Minecraft can still flush deferred GuiGraphics text/tooltip
 * batches later in GameRenderer.render. Patch 116 arms at ScreenEvent.Post and
 * performs the GPU-only MainTarget capture from RenderFrameEvent.Post, after the
 * complete GameRenderer frame has rendered but before normal window presentation.</p>
 */
@EventBusSubscriber(
        value = Dist.CLIENT,
        modid = "potato_runtime"
)
public final class Gate10GuiBoundaryEvents {

    private static final AtomicLong HUD_BEGIN_NANOS =
            new AtomicLong();

    private static final AtomicLong SCREEN_BEGIN_NANOS =
            new AtomicLong();

    private static final AtomicBoolean SCREEN_FINAL_CAPTURE_PENDING =
            new AtomicBoolean();

    private static final AtomicBoolean INTERACTIVE_FRAME_CAPTURE_PENDING =
            new AtomicBoolean();

    /*
     * Patch 132: autonomous Gate-10 bootstrap. Before the Gate-11 live stream
     * exists, complete world frames qualify the exact same native-resolution
     * MainTarget handoff without requiring the player to open any Screen.
     */
    private static final AtomicBoolean AUTOMATIC_WORLD_FRAME_CAPTURE_PENDING =
            new AtomicBoolean();

    private static final LongAdder INTERACTIVE_FRAME_CAPTURE_ARM_COUNT =
            new LongAdder();

    private static final LongAdder AUTOMATIC_WORLD_FRAME_CAPTURE_ARM_COUNT =
            new LongAdder();

    private static final LongAdder AUTOMATIC_WORLD_FRAME_CAPTURE_RUN_COUNT =
            new LongAdder();

    private static final LongAdder AUTOMATIC_WORLD_FRAME_CAPTURE_STALE_DROP_COUNT =
            new LongAdder();

    private static final LongAdder INTERACTIVE_FRAME_CAPTURE_RUN_COUNT =
            new LongAdder();

    private static final LongAdder INTERACTIVE_FRAME_CAPTURE_STALE_DROP_COUNT =
            new LongAdder();

    private static final LongAdder SCREEN_FINAL_CAPTURE_ARM_COUNT =
            new LongAdder();

    private static final LongAdder SCREEN_FINAL_CAPTURE_RUN_COUNT =
            new LongAdder();

    private static final LongAdder SCREEN_FINAL_CAPTURE_STALE_DROP_COUNT =
            new LongAdder();

    private static final LongAdder HUD_UNMATCHED_BEGIN_COUNT =
            new LongAdder();

    private static final LongAdder SCREEN_UNMATCHED_BEGIN_COUNT =
            new LongAdder();

    private Gate10GuiBoundaryEvents() {
    }

    @SubscribeEvent
    public static void onHudPre(
            RenderGuiEvent.Pre event
    ) {
        begin(
                HUD_BEGIN_NANOS,
                HUD_UNMATCHED_BEGIN_COUNT
        );
    }

    @SubscribeEvent
    public static void onHudPost(
            RenderGuiEvent.Post event
    ) {
        long elapsed =
                end(
                        HUD_BEGIN_NANOS
                );

        if (elapsed >= 0L) {
            VulkanGate10ImmediatePath
                    .observeGuiHudFrame(
                            elapsed
                    );
        }

        VulkanRuntimeManager
                .tickGate11VisibleReplacementRehearsal();
    }

    /**
     * Patch 120 outer-frame producer boundary.
     *
     * <p>Gate 10 is still bootstrapped exclusively by real Screen events.
     * Only after the Gate-11 bounded live stream is already active do we move
     * begin/end ownership to RenderFrameEvent.Pre/Post. That lets the same
     * Vulkan candidate follow clicks that change Screens and, critically, an
     * Escape/Done transition back to normal world gameplay.</p>
     */
    @SubscribeEvent
    public static void onRenderFramePre(
            RenderFrameEvent.Pre event
    ) {
        boolean interactiveCapture =
                VulkanRuntimeManager
                        .gate11InteractiveWholeFrameCaptureActive();

        boolean automaticWorldBootstrap =
                false;

        if (!interactiveCapture) {
            Minecraft minecraft =
                    Minecraft.getInstance();

            automaticWorldBootstrap =
                    minecraft != null
                            && minecraft.level != null;
        }

        if (!interactiveCapture
                && !automaticWorldBootstrap) {
            return;
        }

        if (interactiveCapture) {
            if (INTERACTIVE_FRAME_CAPTURE_PENDING.getAndSet(true)) {
                INTERACTIVE_FRAME_CAPTURE_STALE_DROP_COUNT.increment();
            }

            INTERACTIVE_FRAME_CAPTURE_ARM_COUNT.increment();
        } else {
            if (AUTOMATIC_WORLD_FRAME_CAPTURE_PENDING.getAndSet(true)) {
                AUTOMATIC_WORLD_FRAME_CAPTURE_STALE_DROP_COUNT.increment();
            }

            AUTOMATIC_WORLD_FRAME_CAPTURE_ARM_COUNT.increment();
        }

        if (SCREEN_FINAL_CAPTURE_PENDING.getAndSet(false)) {
            SCREEN_FINAL_CAPTURE_STALE_DROP_COUNT.increment();
        }

        VulkanRuntimeManager
                .beginGate10VisibleScreenRehearsal();
    }

    @SubscribeEvent
    public static void onScreenPre(
            ScreenEvent.Render.Pre event
    ) {
        /*
         * RenderFrameEvent.Post is expected once per rendered frame. If another
         * mod aborts that outer frame after ScreenEvent.Post, never carry an old
         * capture request into a new Screen frame. Vulkan beginFrame() itself
         * fail-opens by clearing the previous frameArmed state.
         */
        if (SCREEN_FINAL_CAPTURE_PENDING.getAndSet(false)) {
            SCREEN_FINAL_CAPTURE_STALE_DROP_COUNT.increment();
        }

        begin(
                SCREEN_BEGIN_NANOS,
                SCREEN_UNMATCHED_BEGIN_COUNT
        );

        /*
         * Once the live Gate-11 stream is active, RenderFrameEvent.Pre already
         * owns beginFrame for this exact outer frame. Do not double-arm from
         * ScreenEvent.Pre. Before that point the proven Screen-only bootstrap
         * remains byte-for-byte equivalent in behavior.
         */
        if (!INTERACTIVE_FRAME_CAPTURE_PENDING.get()
                && !AUTOMATIC_WORLD_FRAME_CAPTURE_PENDING.get()) {
            VulkanRuntimeManager
                    .beginGate10VisibleScreenRehearsal();
        }
    }

    @SubscribeEvent
    public static void onScreenPost(
            ScreenEvent.Render.Post event
    ) {
        long elapsed =
                end(
                        SCREEN_BEGIN_NANOS
                );

        if (elapsed >= 0L) {
            VulkanGate10ImmediatePath
                    .observeGuiScreenFrame(
                            elapsed
                    );
        }

        /*
         * During interactive Gate-11 capture, RenderFrameEvent.Post owns the
         * final-pixel end for the whole frame. The Screen-only pending flag is
         * retained only for pre-qualification/bootstrap frames.
         */
        if (!INTERACTIVE_FRAME_CAPTURE_PENDING.get()
                && !AUTOMATIC_WORLD_FRAME_CAPTURE_PENDING.get()) {
            if (SCREEN_FINAL_CAPTURE_PENDING.getAndSet(true)) {
                SCREEN_FINAL_CAPTURE_STALE_DROP_COUNT.increment();
            }

            SCREEN_FINAL_CAPTURE_ARM_COUNT.increment();
        }
    }

    /**
     * Final-pixel seam for Screen capture.
     *
     * <p>NeoForge documents RenderFrameEvent.Post as firing after the current
     * GameRenderer.render invocation. At this point deferred GuiGraphics text,
     * including tooltip glyph batches, has had the rest of the render pass to
     * reach MainTarget. We still do not wait for the GPU on the CPU: the existing
     * Gate-10 external-memory/semaphore path remains unchanged.</p>
     */
    @SubscribeEvent
    public static void onRenderFramePost(
            RenderFrameEvent.Post event
    ) {
        /*
         * Patch 130 transition barrier:
         * finish every Vulkan final-pixel capture for this frame first. Only
         * after GameRenderer.render has completed may a deferred presentation-
         * window Screen click mutate Minecraft lifecycle state.
         */
        if (INTERACTIVE_FRAME_CAPTURE_PENDING.compareAndSet(true, false)) {
            INTERACTIVE_FRAME_CAPTURE_RUN_COUNT.increment();

            VulkanRuntimeManager
                    .endGate10VisibleScreenRehearsal();
        } else if (AUTOMATIC_WORLD_FRAME_CAPTURE_PENDING.compareAndSet(true, false)) {
            AUTOMATIC_WORLD_FRAME_CAPTURE_RUN_COUNT.increment();

            VulkanRuntimeManager
                    .endGate10VisibleScreenRehearsal();
        } else if (SCREEN_FINAL_CAPTURE_PENDING.compareAndSet(true, false)) {
            SCREEN_FINAL_CAPTURE_RUN_COUNT.increment();

            VulkanRuntimeManager
                    .endGate10VisibleScreenRehearsal();
        }

        /*
         * Patch 129 used RenderFrameEvent.Pre here conceptually, which is still
         * inside the frame whose render-level decision has already been made.
         * Save and Quit can set minecraft.gameMode=null. Dispatching it before
         * that frame finishes caused GameRenderer.renderItemInHand() to observe
         * a torn lifecycle state and crash.
         *
         * Post is the safe seam: the old frame is complete, then at most one
         * queued Screen action is applied. The next frame sees the new world/
         * screen state from its beginning.
         */
        VulkanRuntimeManager
                .flushGate11DeferredScreenInput();

        /*
         * Patch 131: HUD events stop being a reliable pump after leaving a
         * world. Keep Gate-11 lifecycle/presentation state advancing from the
         * outer frame boundary so a successful Save-and-Quit can immediately
         * hide the gameplay Vulkan window and reveal the OpenGL TitleScreen.
         */
        VulkanRuntimeManager
                .tickGate11VisibleReplacementRehearsal();
    }

    public static long interactiveFrameCaptureArmCount() {
        return INTERACTIVE_FRAME_CAPTURE_ARM_COUNT.sum();
    }

    public static long interactiveFrameCaptureRunCount() {
        return INTERACTIVE_FRAME_CAPTURE_RUN_COUNT.sum();
    }

    public static long interactiveFrameCaptureStaleDropCount() {
        return INTERACTIVE_FRAME_CAPTURE_STALE_DROP_COUNT.sum();
    }

    public static long automaticWorldFrameCaptureArmCount() {
        return AUTOMATIC_WORLD_FRAME_CAPTURE_ARM_COUNT.sum();
    }

    public static long automaticWorldFrameCaptureRunCount() {
        return AUTOMATIC_WORLD_FRAME_CAPTURE_RUN_COUNT.sum();
    }

    public static long automaticWorldFrameCaptureStaleDropCount() {
        return AUTOMATIC_WORLD_FRAME_CAPTURE_STALE_DROP_COUNT.sum();
    }

    public static boolean automaticWorldFrameCapturePending() {
        return AUTOMATIC_WORLD_FRAME_CAPTURE_PENDING.get();
    }

    public static boolean interactiveFrameCapturePending() {
        return INTERACTIVE_FRAME_CAPTURE_PENDING.get();
    }

    public static long screenFinalCaptureArmCount() {
        return SCREEN_FINAL_CAPTURE_ARM_COUNT.sum();
    }

    public static long screenFinalCaptureRunCount() {
        return SCREEN_FINAL_CAPTURE_RUN_COUNT.sum();
    }

    public static long screenFinalCaptureStaleDropCount() {
        return SCREEN_FINAL_CAPTURE_STALE_DROP_COUNT.sum();
    }

    public static boolean screenFinalCapturePending() {
        return SCREEN_FINAL_CAPTURE_PENDING.get();
    }

    public static long hudUnmatchedBeginCount() {
        return HUD_UNMATCHED_BEGIN_COUNT.sum();
    }

    public static long screenUnmatchedBeginCount() {
        return SCREEN_UNMATCHED_BEGIN_COUNT.sum();
    }

    private static void begin(
            AtomicLong slot,
            LongAdder unmatched
    ) {
        long now =
                System.nanoTime();

        long previous =
                slot.getAndSet(
                        now
                );

        if (previous != 0L) {
            unmatched.increment();
        }
    }

    private static long end(
            AtomicLong slot
    ) {
        long start =
                slot.getAndSet(
                        0L
                );

        if (start == 0L) {
            return -1L;
        }

        return Math.max(
                0L,
                System.nanoTime() - start
        );
    }
}
