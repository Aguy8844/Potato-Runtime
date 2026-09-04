package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Scoped modifier-state bridge for the transitional Gate-11 presentation
 * window.
 *
 * <p>The visible GLFW_NO_API window receives the real mouse event and its
 * modifier mask, while Minecraft still stores the hidden OpenGL lifecycle
 * window handle. Minecraft 1.21.1 inventory code does not consistently route
 * Shift through Screen.hasShiftDown(): AbstractContainerScreen also polls
 * InputConstants.isKeyDown(...) directly. The bridge therefore exposes the
 * captured event mask at both boundaries while one deferred Screen event is
 * being dispatched.</p>
 *
 * <p>No GLFW key state is mutated and no synthetic keyboard input is emitted.
 * Scope state is render-thread-local and nest-safe so re-entrant Screen work
 * cannot overwrite the outer event mask.</p>
 */
public final class VulkanGate11ModifierStateBridge {
    private static final int MAX_SCOPE_DEPTH = 8;

    private static final ThreadLocal<ScopeState> ACTIVE_STATE =
            new ThreadLocal<>();

    private static long lifecycleOwnerHandle;
    private static long scopeBeginCount;
    private static long scopeEndCount;
    private static long nestedScopeCount;
    private static long scopeLeakCount;
    private static long unbalancedScopeEndCount;
    private static long shiftQueryCount;
    private static long controlQueryCount;
    private static long altQueryCount;
    private static long helperTrueOverrideCount;
    private static long directKeyStateQueryCount;
    private static long directKeyStateOverrideCount;
    private static long directShiftTrueOverrideCount;
    private static long directControlTrueOverrideCount;
    private static long directAltTrueOverrideCount;
    private static long directForeignWindowBypassCount;
    private static int peakScopeDepth;
    private static int lastModifierMask;
    private static int lastDirectKey;

    private VulkanGate11ModifierStateBridge() {
    }

    static synchronized void resetSession(
            long ownerHandle
    ) {
        ACTIVE_STATE.remove();
        lifecycleOwnerHandle = ownerHandle;
        scopeBeginCount = 0L;
        scopeEndCount = 0L;
        nestedScopeCount = 0L;
        scopeLeakCount = 0L;
        unbalancedScopeEndCount = 0L;
        shiftQueryCount = 0L;
        controlQueryCount = 0L;
        altQueryCount = 0L;
        helperTrueOverrideCount = 0L;
        directKeyStateQueryCount = 0L;
        directKeyStateOverrideCount = 0L;
        directShiftTrueOverrideCount = 0L;
        directControlTrueOverrideCount = 0L;
        directAltTrueOverrideCount = 0L;
        directForeignWindowBypassCount = 0L;
        peakScopeDepth = 0;
        lastModifierMask = 0;
        lastDirectKey = -1;
    }

    static void beginScreenDispatch(
            int modifiers
    ) {
        ScopeState state =
                ACTIVE_STATE.get();

        if (state == null) {
            state = new ScopeState();
            ACTIVE_STATE.set(state);
        }

        if (state.depth >= MAX_SCOPE_DEPTH) {
            synchronized (VulkanGate11ModifierStateBridge.class) {
                scopeLeakCount++;
            }

            throw new IllegalStateException(
                    "Gate-11 modifier scope depth exceeded "
                            + MAX_SCOPE_DEPTH
            );
        }

        if (state.depth > 0) {
            synchronized (VulkanGate11ModifierStateBridge.class) {
                nestedScopeCount++;
            }
        }

        state.modifierStack[state.depth] = modifiers;
        state.depth++;

        synchronized (VulkanGate11ModifierStateBridge.class) {
            scopeBeginCount++;
            peakScopeDepth = Math.max(
                    peakScopeDepth,
                    state.depth
            );
            lastModifierMask = modifiers;
        }
    }

    static void endScreenDispatch() {
        ScopeState state =
                ACTIVE_STATE.get();

        if (state == null || state.depth <= 0) {
            ACTIVE_STATE.remove();

            synchronized (VulkanGate11ModifierStateBridge.class) {
                unbalancedScopeEndCount++;
                scopeLeakCount++;
            }

            return;
        }

        state.depth--;
        state.modifierStack[state.depth] = 0;

        synchronized (VulkanGate11ModifierStateBridge.class) {
            scopeEndCount++;
        }

        if (state.depth == 0) {
            ACTIVE_STATE.remove();
        }
    }

    public static Boolean shiftDownOverride() {
        Integer modifiers =
                activeModifiers();

        if (modifiers == null) {
            return null;
        }

        boolean down =
                (modifiers & GLFW_MOD_SHIFT) != 0;

        synchronized (VulkanGate11ModifierStateBridge.class) {
            shiftQueryCount++;

            if (down) {
                helperTrueOverrideCount++;
            }
        }

        return down;
    }

    public static Boolean controlDownOverride() {
        Integer modifiers =
                activeModifiers();

        if (modifiers == null) {
            return null;
        }

        boolean down =
                (modifiers & GLFW_MOD_CONTROL) != 0;

        synchronized (VulkanGate11ModifierStateBridge.class) {
            controlQueryCount++;

            if (down) {
                helperTrueOverrideCount++;
            }
        }

        return down;
    }

    public static Boolean altDownOverride() {
        Integer modifiers =
                activeModifiers();

        if (modifiers == null) {
            return null;
        }

        boolean down =
                (modifiers & GLFW_MOD_ALT) != 0;

        synchronized (VulkanGate11ModifierStateBridge.class) {
            altQueryCount++;

            if (down) {
                helperTrueOverrideCount++;
            }
        }

        return down;
    }

    /**
     * Intercepts only modifier-key polls against Minecraft's real lifecycle
     * owner while a deferred Screen event is actively dispatched.
     *
     * @return null when vanilla InputConstants.isKeyDown must execute.
     */
    public static Boolean directKeyDownOverride(
            long window,
            int key
    ) {
        int modifierBit =
                modifierBitForKey(key);

        if (modifierBit == 0) {
            return null;
        }

        Integer modifiers =
                activeModifiers();

        if (modifiers == null) {
            return null;
        }

        long expectedWindow;

        synchronized (VulkanGate11ModifierStateBridge.class) {
            expectedWindow = lifecycleOwnerHandle;
        }

        if (expectedWindow == 0L
                || window != expectedWindow) {
            synchronized (VulkanGate11ModifierStateBridge.class) {
                directForeignWindowBypassCount++;
            }

            return null;
        }

        boolean down =
                (modifiers & modifierBit) != 0;

        synchronized (VulkanGate11ModifierStateBridge.class) {
            directKeyStateQueryCount++;
            directKeyStateOverrideCount++;
            lastDirectKey = key;

            if (down) {
                if (modifierBit == GLFW_MOD_SHIFT) {
                    directShiftTrueOverrideCount++;
                } else if (modifierBit == GLFW_MOD_CONTROL) {
                    directControlTrueOverrideCount++;
                } else if (modifierBit == GLFW_MOD_ALT) {
                    directAltTrueOverrideCount++;
                }
            }
        }

        return down;
    }

    private static Integer activeModifiers() {
        ScopeState state =
                ACTIVE_STATE.get();

        if (state == null || state.depth <= 0) {
            return null;
        }

        return state.modifierStack[
                state.depth - 1
        ];
    }

    private static int modifierBitForKey(
            int key
    ) {
        return switch (key) {
            case GLFW_KEY_LEFT_SHIFT,
                 GLFW_KEY_RIGHT_SHIFT -> GLFW_MOD_SHIFT;
            case GLFW_KEY_LEFT_CONTROL,
                 GLFW_KEY_RIGHT_CONTROL -> GLFW_MOD_CONTROL;
            case GLFW_KEY_LEFT_ALT,
                 GLFW_KEY_RIGHT_ALT -> GLFW_MOD_ALT;
            case GLFW_KEY_LEFT_SUPER,
                 GLFW_KEY_RIGHT_SUPER -> GLFW_MOD_SUPER;
            default -> 0;
        };
    }

    static synchronized boolean healthy() {
        ScopeState state =
                ACTIVE_STATE.get();

        return scopeLeakCount == 0L
                && unbalancedScopeEndCount == 0L
                && scopeBeginCount == scopeEndCount
                && (state == null || state.depth == 0);
    }

    static synchronized void enrich(
            JsonObject report
    ) {
        if (report == null) {
            return;
        }

        report.addProperty(
                "gate11WindowDeferredModifierBridgeInstalled",
                true
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeMode",
                "EXACT_MOUSE_EVENT_MODS_SCREEN_HELPER_PLUS_INPUTCONSTANTS_DIRECT_POLL"
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeScopedToScreenDispatch",
                true
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeDirectInputConstantsPoll",
                true
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeLifecycleOwnerHandleBound",
                lifecycleOwnerHandle != 0L
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeMutatesNativeKeyState",
                false
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeInjectsSyntheticKeyEvents",
                false
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeScopeBeginCount",
                scopeBeginCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeScopeEndCount",
                scopeEndCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeNestedScopeCount",
                nestedScopeCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgePeakScopeDepth",
                peakScopeDepth
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeShiftQueryCount",
                shiftQueryCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeControlQueryCount",
                controlQueryCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeAltQueryCount",
                altQueryCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeHelperTrueOverrideCount",
                helperTrueOverrideCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeDirectKeyStateQueryCount",
                directKeyStateQueryCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeDirectKeyStateOverrideCount",
                directKeyStateOverrideCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeDirectShiftTrueOverrideCount",
                directShiftTrueOverrideCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeDirectControlTrueOverrideCount",
                directControlTrueOverrideCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeDirectAltTrueOverrideCount",
                directAltTrueOverrideCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeDirectForeignWindowBypassCount",
                directForeignWindowBypassCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeScopeLeakCount",
                scopeLeakCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeUnbalancedScopeEndCount",
                unbalancedScopeEndCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeLastModifierMask",
                lastModifierMask
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeLastDirectKey",
                lastDirectKey
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeHealthy",
                healthy()
        );
    }

    private static final class ScopeState {
        private final int[] modifierStack =
                new int[MAX_SCOPE_DEPTH];

        private int depth;
    }
}
