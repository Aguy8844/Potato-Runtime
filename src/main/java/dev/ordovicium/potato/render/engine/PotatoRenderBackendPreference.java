package dev.ordovicium.potato.render.engine;

import java.util.Locale;

public enum PotatoRenderBackendPreference {
    AUTO,
    VULKAN,
    OPENGL;

    public static PotatoRenderBackendPreference fromProperty(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return AUTO;
        }

        String normalized =
                value.trim()
                        .toUpperCase(Locale.ROOT);

        return switch (normalized) {
            case "AUTO" -> AUTO;
            case "VULKAN", "VK" -> VULKAN;
            case "OPENGL", "GL", "OPENGL_COMPATIBILITY" -> OPENGL;
            default -> AUTO;
        };
    }
}
