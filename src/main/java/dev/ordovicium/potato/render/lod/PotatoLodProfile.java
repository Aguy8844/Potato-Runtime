package dev.ordovicium.potato.render.lod;

/**
 * User-facing Potato Runtime LOD policy.
 *
 * <p>ADAPTIVE is the default. Fixed profiles keep their geometry thresholds
 * stable, while ADAPTIVE moves them conservatively from measured solid-layer
 * render pressure. None of these profiles change Minecraft's configured
 * render distance.</p>
 */
public enum PotatoLodProfile {
    OFF,
    QUALITY,
    BALANCED,
    POTATO,
    ADAPTIVE;

    public static PotatoLodProfile fromProperty(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return ADAPTIVE;
        }

        try {
            return PotatoLodProfile.valueOf(
                    value.trim()
                            .toUpperCase(
                                    java.util.Locale.ROOT
                            )
            );
        } catch (IllegalArgumentException ignored) {
            return ADAPTIVE;
        }
    }
}
