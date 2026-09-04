package dev.ordovicium.potato.settings;

/**
 * User-facing Potato Runtime policy mode.
 *
 * <p>AUTO_DYNAMIC is the default: it keeps the user's configured view distance
 * as an upper bound, then changes the live render distance conservatively from
 * measured frame pacing. Fixed presets remain available, while CUSTOM exposes
 * the individual controls used by the adaptive policy.</p>
 */
public enum PotatoPerformanceMode {
    AUTO_DYNAMIC("Auto / Dynamic"),
    QUALITY("Quality"),
    BALANCED("Balanced"),
    POTATO("Potato"),
    CUSTOM("Custom");

    private final String displayName;

    PotatoPerformanceMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public PotatoPerformanceMode next() {
        PotatoPerformanceMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static PotatoPerformanceMode parse(
            String value,
            PotatoPerformanceMode fallback
    ) {
        if (value == null) {
            return fallback;
        }

        try {
            return PotatoPerformanceMode.valueOf(
                    value.trim().toUpperCase(java.util.Locale.ROOT)
            );
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
