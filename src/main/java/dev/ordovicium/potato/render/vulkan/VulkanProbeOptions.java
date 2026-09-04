package dev.ordovicium.potato.render.vulkan;

import java.util.Locale;

/**
 * Explicit opt-in controls for development-only Vulkan visualization.
 *
 * <p>The production default is always non-interactive and hidden. Visual
 * verification must be requested through the environment so a release build
 * cannot unexpectedly flash debug windows during normal Minecraft startup.</p>
 */
record VulkanProbeOptions(
        boolean visibleFrameVerification,
        int visibleDurationMillis,
        int probeWindowWidth,
        int probeWindowHeight
) {
    private static final String ENV_VISIBLE =
            "POTATO_VULKAN_PROBE_VISIBLE";
    private static final String ENV_DURATION =
            "POTATO_VULKAN_PROBE_VISIBLE_MS";

    private static final int HIDDEN_WIDTH = 64;
    private static final int HIDDEN_HEIGHT = 64;

    private static final int VISIBLE_WIDTH = 640;
    private static final int VISIBLE_HEIGHT = 360;

    private static final int DEFAULT_VISIBLE_DURATION_MS = 1500;
    private static final int MIN_VISIBLE_DURATION_MS = 250;
    private static final int MAX_VISIBLE_DURATION_MS = 5000;

    static VulkanProbeOptions fromEnvironment() {
        boolean visible = parseBoolean(
                System.getenv(ENV_VISIBLE)
        );

        int duration = parseDuration(
                System.getenv(ENV_DURATION)
        );

        return new VulkanProbeOptions(
                visible,
                duration,
                visible ? VISIBLE_WIDTH : HIDDEN_WIDTH,
                visible ? VISIBLE_HEIGHT : HIDDEN_HEIGHT
        );
    }

    private static boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    private static int parseDuration(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_VISIBLE_DURATION_MS;
        }

        try {
            int parsed = Integer.parseInt(value.trim());

            return Math.max(
                    MIN_VISIBLE_DURATION_MS,
                    Math.min(parsed, MAX_VISIBLE_DURATION_MS)
            );
        } catch (NumberFormatException ignored) {
            return DEFAULT_VISIBLE_DURATION_MS;
        }
    }
}