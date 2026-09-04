package dev.ordovicium.potato.settings;

/**
 * Developer-only hardware emulation profile.
 *
 * <p>The simulated profiles are intentionally explicit instead of pretending
 * that the physical GPU changed. They feed Potato's native residency and view
 * policy at the next startup and make low-end testing reproducible on a strong
 * development machine.</p>
 */
public enum PotatoDeveloperHardwareProfile {
    REAL_HARDWARE("Real hardware", 0),
    POTATO_512_MIB("Potato - 512 MiB VRAM", 512),
    LOW_2_GIB("Low - 2 GiB VRAM", 2048),
    HIGH_8_GIB("High - 8 GiB VRAM", 8192);

    private final String displayName;
    private final int emulatedVramMiB;

    PotatoDeveloperHardwareProfile(
            String displayName,
            int emulatedVramMiB
    ) {
        this.displayName = displayName;
        this.emulatedVramMiB = emulatedVramMiB;
    }

    public String displayName() {
        return displayName;
    }

    public int emulatedVramMiB() {
        return emulatedVramMiB;
    }

    public boolean simulated() {
        return this != REAL_HARDWARE;
    }

    public PotatoDeveloperHardwareProfile next() {
        PotatoDeveloperHardwareProfile[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public static PotatoDeveloperHardwareProfile parse(
            String value,
            PotatoDeveloperHardwareProfile fallback
    ) {
        if (value == null) {
            return fallback;
        }

        try {
            return PotatoDeveloperHardwareProfile.valueOf(
                    value.trim().toUpperCase(java.util.Locale.ROOT)
            );
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
