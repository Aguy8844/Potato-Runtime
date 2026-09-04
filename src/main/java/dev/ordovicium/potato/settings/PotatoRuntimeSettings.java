package dev.ordovicium.potato.settings;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.lod.PotatoLodProfile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

/**
 * Internal runtime policy for Potato Runtime.
 *
 * <p>The old advanced Potato Settings screen is retired from the release-facing
 * UI. Existing potato-runtime.properties files remain readable for compatibility,
 * but the effective runtime snapshot is now derived from the startup-latched
 * OFF / ON / DYNAMIC switch. This keeps user testing reproducible while the
 * underlying hardware and renderer governors remain automatic.</p>
 */
public final class PotatoRuntimeSettings {
    private static final long MIB = 1024L * 1024L;

    private static final Path CONFIG_PATH =
            Path.of(
                    "config",
                    "potato-runtime.properties"
            );

    private static final boolean DEVELOPER_CONTROLS_AVAILABLE =
            detectDevelopmentEnvironment();

    private static volatile Snapshot snapshot = load();

    private PotatoRuntimeSettings() {
    }

    public static Snapshot snapshot() {
        return effectiveSnapshot(
                snapshot,
                PotatoRuntimeMode.startupMode()
        );
    }

    public static boolean developerControlsAvailable() {
        return DEVELOPER_CONTROLS_AVAILABLE;
    }

    public static synchronized void save(
            PotatoPerformanceMode mode,
            boolean dynamicChunks,
            int targetFps,
            int minChunks,
            int maxChunks,
            PotatoLodProfile lodProfile,
            int nativeBudgetPercent,
            PotatoDeveloperHardwareProfile developerProfile
    ) {
        PotatoDeveloperHardwareProfile safeDeveloperProfile =
                developerControlsAvailable()
                        ? nonNull(
                                developerProfile,
                                PotatoDeveloperHardwareProfile.REAL_HARDWARE
                        )
                        : PotatoDeveloperHardwareProfile.REAL_HARDWARE;

        Snapshot next = new Snapshot(
                nonNull(
                        mode,
                        PotatoPerformanceMode.AUTO_DYNAMIC
                ),
                dynamicChunks,
                clamp(targetFps, 30, 240),
                clamp(minChunks, 2, 64),
                clamp(maxChunks, 2, 64),
                nonNull(
                        lodProfile,
                        PotatoLodProfile.ADAPTIVE
                ),
                clamp(nativeBudgetPercent, 25, 150),
                safeDeveloperProfile
        ).normalized();

        Properties properties = new Properties();
        properties.setProperty(
                "mode",
                next.mode().name()
        );
        properties.setProperty(
                "dynamicChunks",
                Boolean.toString(
                        next.dynamicChunks()
                )
        );
        properties.setProperty(
                "targetFps",
                Integer.toString(
                        next.targetFps()
                )
        );
        properties.setProperty(
                "minChunks",
                Integer.toString(
                        next.minChunks()
                )
        );
        properties.setProperty(
                "maxChunks",
                Integer.toString(
                        next.maxChunks()
                )
        );
        properties.setProperty(
                "lodProfile",
                next.lodProfile().name()
        );
        properties.setProperty(
                "nativeBudgetPercent",
                Integer.toString(
                        next.nativeBudgetPercent()
                )
        );

        if (developerControlsAvailable()) {
            properties.setProperty(
                    "developerHardwareProfile",
                    next.developerHardwareProfile()
                            .name()
            );
        }

        try {
            Path parent = CONFIG_PATH.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (OutputStream output =
                         Files.newOutputStream(CONFIG_PATH)) {
                properties.store(
                        output,
                        "Potato Runtime settings"
                );
            }
        } catch (IOException ignored) {
            /*
             * A read-only config directory must not take down rendering.
             * The settings still apply to the current process.
             */
        }

        snapshot = next;
    }

    public static HardwarePlan hardwarePlan() {
        Snapshot current = snapshot();
        HardwarePlan base = baseHardwarePlan(current);

        if (current.mode() == PotatoPerformanceMode.POTATO) {
            base = base.capTo(
                    potatoPlan(
                            base.source() + "+POTATO_PRESET"
                    )
            );
        } else if (current.mode()
                == PotatoPerformanceMode.BALANCED) {
            base = base.capTo(
                    balancedPlan(
                            base.source() + "+BALANCED_PRESET"
                    )
            );
        }

        if (current.nativeBudgetPercent() != 100) {
            base = base.scaledNativeBudgets(
                    current.nativeBudgetPercent()
            );
        }

        return base;
    }

    public static int staticAdmissionBudget() {
        return hardwarePlan().staticAdmissions();
    }

    public static int visibleResidencyWarmSweeps() {
        return hardwarePlan().warmSweeps();
    }

    public static long visibleDeferredBudgetBytes() {
        return hardwarePlan().deferredBytes();
    }

    public static int visibleDeferredEntryLimit() {
        return hardwarePlan().deferredEntries();
    }

    public static int visiblePromotionsPerSweep() {
        return hardwarePlan().promotionsPerSweep();
    }

    public static long visiblePromotionBytesPerSweep() {
        return hardwarePlan().promotionBytesPerSweep();
    }

    public static int effectiveMinimumChunks() {
        Snapshot current = snapshot();

        return clamp(
                current.minChunks(),
                2,
                effectiveMaximumChunks()
        );
    }

    public static int effectiveMaximumChunks() {
        Snapshot current = snapshot();
        int configured =
                Math.max(
                        current.minChunks(),
                        current.maxChunks()
                );

        if (current.mode() == PotatoPerformanceMode.CUSTOM
                || current.mode() == PotatoPerformanceMode.QUALITY) {
            return clamp(configured, 2, 64);
        }

        return clamp(
                Math.min(
                        configured,
                        hardwarePlan().automaticMaxChunks()
                ),
                2,
                64
        );
    }

    public static boolean dynamicChunksEffective() {
        Snapshot current = snapshot();

        return current.dynamicChunks()
                && current.mode()
                != PotatoPerformanceMode.QUALITY;
    }

    public static void enrich(JsonObject report) {
        if (report == null) {
            return;
        }

        Snapshot current = snapshot();
        HardwarePlan plan = hardwarePlan();

        report.addProperty(
                "potatoSettingsInstalled",
                true
        );
        report.addProperty(
                "potatoRuntimeModeStartup",
                PotatoRuntimeMode.startupMode().name()
        );
        report.addProperty(
                "potatoRuntimeModeSelected",
                PotatoRuntimeMode.selectedMode().name()
        );
        report.addProperty(
                "potatoRuntimeModeRestartRequired",
                PotatoRuntimeMode.restartRequired()
        );
        report.addProperty(
                "potatoRuntimeModeEnabled",
                PotatoRuntimeMode.startupMode().enabled()
        );
        report.addProperty(
                "potatoRuntimeModeDynamic",
                PotatoRuntimeMode.startupMode().dynamic()
        );
        report.addProperty(
                "potatoAdvancedSettingsUiExposed",
                false
        );
        report.addProperty(
                "potatoLegacyDetailedSettingsIgnored",
                true
        );
        report.addProperty(
                "potatoSettingsMode",
                current.mode().name()
        );
        report.addProperty(
                "potatoSettingsDynamicChunksConfigured",
                current.dynamicChunks()
        );
        report.addProperty(
                "potatoSettingsDynamicChunksEffective",
                dynamicChunksEffective()
        );
        report.addProperty(
                "potatoSettingsTargetFps",
                current.targetFps()
        );
        report.addProperty(
                "potatoSettingsConfiguredMinChunks",
                current.minChunks()
        );
        report.addProperty(
                "potatoSettingsConfiguredMaxChunks",
                current.maxChunks()
        );
        report.addProperty(
                "potatoSettingsEffectiveMinChunks",
                effectiveMinimumChunks()
        );
        report.addProperty(
                "potatoSettingsEffectiveMaxChunks",
                effectiveMaximumChunks()
        );
        report.addProperty(
                "potatoSettingsLodProfile",
                current.lodProfile().name()
        );
        report.addProperty(
                "potatoSettingsNativeBudgetPercent",
                current.nativeBudgetPercent()
        );
        report.addProperty(
                "potatoSettingsDeveloperControlsAvailable",
                developerControlsAvailable()
        );
        report.addProperty(
                "potatoSettingsDeveloperHardwareProfile",
                current.developerHardwareProfile()
                        .name()
        );
        report.addProperty(
                "potatoSettingsDeveloperHardwareSimulated",
                current.developerHardwareProfile()
                        .simulated()
        );
        report.addProperty(
                "potatoSettingsHardwarePlanSource",
                plan.source()
        );
        report.addProperty(
                "potatoSettingsHardwarePlanVramMiB",
                plan.planningVramMiB()
        );
        report.addProperty(
                "potatoSettingsHardwarePlanStaticAdmissions",
                plan.staticAdmissions()
        );
        report.addProperty(
                "potatoSettingsHardwarePlanDeferredBytes",
                plan.deferredBytes()
        );
        report.addProperty(
                "potatoSettingsHardwarePlanDeferredEntries",
                plan.deferredEntries()
        );
        report.addProperty(
                "potatoSettingsHardwarePlanPromotionsPerSweep",
                plan.promotionsPerSweep()
        );
        report.addProperty(
                "potatoSettingsHardwarePlanPromotionBytesPerSweep",
                plan.promotionBytesPerSweep()
        );
        report.addProperty(
                "potatoSettingsHardwarePlanWarmSweeps",
                plan.warmSweeps()
        );
        report.addProperty(
                "potatoSettingsNativeBudgetChangesRequireRestart",
                true
        );
    }

    private static Snapshot effectiveSnapshot(
            Snapshot legacy,
            PotatoRuntimeMode runtimeMode
    ) {
        PotatoRuntimeMode mode =
                runtimeMode != null
                        ? runtimeMode
                        : PotatoRuntimeMode.DYNAMIC;

        PotatoDeveloperHardwareProfile hardware =
                PotatoDeveloperHardwareProfile.REAL_HARDWARE;

        return switch (mode) {
            case OFF ->
                    new Snapshot(
                            PotatoPerformanceMode.CUSTOM,
                            false,
                            60,
                            2,
                            64,
                            PotatoLodProfile.OFF,
                            100,
                            hardware
                    );
            case ON ->
                    new Snapshot(
                            PotatoPerformanceMode.CUSTOM,
                            false,
                            60,
                            6,
                            64,
                            PotatoLodProfile.ADAPTIVE,
                            100,
                            hardware
                    );
            case DYNAMIC ->
                    new Snapshot(
                            PotatoPerformanceMode.AUTO_DYNAMIC,
                            true,
                            60,
                            6,
                            64,
                            PotatoLodProfile.ADAPTIVE,
                            100,
                            hardware
                    );
        };
    }

    private static Snapshot load() {
        Snapshot defaults = defaultSnapshot();

        if (!Files.isRegularFile(CONFIG_PATH)) {
            return defaults;
        }

        Properties properties = new Properties();

        try (InputStream input =
                     Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
        } catch (IOException ignored) {
            return defaults;
        }

        PotatoDeveloperHardwareProfile developerProfile =
                developerControlsAvailable()
                        ? PotatoDeveloperHardwareProfile.parse(
                                properties.getProperty(
                                        "developerHardwareProfile"
                                ),
                                PotatoDeveloperHardwareProfile.REAL_HARDWARE
                        )
                        : PotatoDeveloperHardwareProfile.REAL_HARDWARE;

        return new Snapshot(
                PotatoPerformanceMode.parse(
                        properties.getProperty("mode"),
                        defaults.mode()
                ),
                parseBoolean(
                        properties,
                        "dynamicChunks",
                        defaults.dynamicChunks()
                ),
                parseInt(
                        properties,
                        "targetFps",
                        defaults.targetFps(),
                        30,
                        240
                ),
                parseInt(
                        properties,
                        "minChunks",
                        defaults.minChunks(),
                        2,
                        64
                ),
                parseInt(
                        properties,
                        "maxChunks",
                        defaults.maxChunks(),
                        2,
                        64
                ),
                parseLodProfile(
                        properties.getProperty(
                                "lodProfile"
                        ),
                        defaults.lodProfile()
                ),
                parseInt(
                        properties,
                        "nativeBudgetPercent",
                        defaults.nativeBudgetPercent(),
                        25,
                        150
                ),
                developerProfile
        ).normalized();
    }

    private static Snapshot defaultSnapshot() {
        return new Snapshot(
                PotatoPerformanceMode.AUTO_DYNAMIC,
                true,
                60,
                6,
                32,
                PotatoLodProfile.ADAPTIVE,
                100,
                PotatoDeveloperHardwareProfile.REAL_HARDWARE
        );
    }

    private static HardwarePlan baseHardwarePlan(
            Snapshot current
    ) {
        PotatoDeveloperHardwareProfile developer =
                developerControlsAvailable()
                        ? current.developerHardwareProfile()
                        : PotatoDeveloperHardwareProfile.REAL_HARDWARE;

        return switch (developer) {
            case POTATO_512_MIB ->
                    new HardwarePlan(
                            "DEV_SIM_512_MIB",
                            512,
                            1024,
                            16L * MIB,
                            512,
                            8,
                            256L * 1024L,
                            90,
                            10
                    );
            case LOW_2_GIB ->
                    new HardwarePlan(
                            "DEV_SIM_2_GIB",
                            2048,
                            4096,
                            48L * MIB,
                            2048,
                            16,
                            768L * 1024L,
                            120,
                            18
                    );
            case HIGH_8_GIB ->
                    highPlan("DEV_SIM_8_GIB");
            case REAL_HARDWARE ->
                    automaticHardwarePlan();
        };
    }

    private static HardwarePlan automaticHardwarePlan() {
        int processors =
                Math.max(
                        1,
                        Runtime.getRuntime()
                                .availableProcessors()
                );

        long jvmMaxMiB =
                Math.max(
                        1L,
                        Runtime.getRuntime()
                                .maxMemory()
                                / MIB
                );

        /*
         * Keep this bucket intentionally conservative. Potato Engine already
         * performs the backend/device capability decision. This policy layer
         * only needs a stable resource class and must not overcommit native
         * memory on integrated or very small systems.
         */
        if (processors <= 4
                || jvmMaxMiB < 3072L) {
            return potatoPlan("AUTO_POTATO");
        }

        if (processors <= 8
                || jvmMaxMiB < 4096L) {
            return new HardwarePlan(
                    "AUTO_LOW",
                    2048,
                    4096,
                    48L * MIB,
                    2048,
                    16,
                    768L * 1024L,
                    120,
                    18
            );
        }

        if (processors <= 12
                || jvmMaxMiB < 6144L) {
            return balancedPlan("AUTO_BALANCED");
        }

        return highPlan("AUTO_HIGH");
    }

    private static HardwarePlan potatoPlan(String source) {
        return new HardwarePlan(
                source,
                512,
                2048,
                24L * MIB,
                1024,
                12,
                512L * 1024L,
                90,
                12
        );
    }

    private static HardwarePlan balancedPlan(String source) {
        return new HardwarePlan(
                source,
                4096,
                8192,
                96L * MIB,
                3072,
                32,
                1536L * 1024L,
                150,
                24
        );
    }

    private static HardwarePlan highPlan(String source) {
        return new HardwarePlan(
                source,
                8192,
                16384,
                128L * MIB,
                4096,
                48,
                2L * MIB,
                180,
                32
        );
    }

    private static boolean detectDevelopmentEnvironment() {
        if (Boolean.getBoolean("potato.developer")) {
            return true;
        }

        try {
            Class<?> environment =
                    Class.forName(
                            "net.neoforged.fml.loading.FMLEnvironment"
                    );

            return !environment
                    .getField("production")
                    .getBoolean(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static PotatoLodProfile parseLodProfile(
            String value,
            PotatoLodProfile fallback
    ) {
        if (value == null) {
            return fallback;
        }

        try {
            return PotatoLodProfile.valueOf(
                    value.trim()
                            .toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(
            Properties properties,
            String key,
            boolean fallback
    ) {
        String value = properties.getProperty(key);

        if (value == null) {
            return fallback;
        }

        return Boolean.parseBoolean(value.trim());
    }

    private static int parseInt(
            Properties properties,
            String key,
            int fallback,
            int minimum,
            int maximum
    ) {
        String value = properties.getProperty(key);

        if (value == null) {
            return fallback;
        }

        try {
            return clamp(
                    Integer.parseInt(value.trim()),
                    minimum,
                    maximum
            );
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static <T> T nonNull(T value, T fallback) {
        return value != null
                ? value
                : fallback;
    }

    private static int clamp(
            int value,
            int minimum,
            int maximum
    ) {
        return Math.max(
                minimum,
                Math.min(
                        maximum,
                        value
                )
        );
    }

    public record Snapshot(
            PotatoPerformanceMode mode,
            boolean dynamicChunks,
            int targetFps,
            int minChunks,
            int maxChunks,
            PotatoLodProfile lodProfile,
            int nativeBudgetPercent,
            PotatoDeveloperHardwareProfile developerHardwareProfile
    ) {
        private Snapshot normalized() {
            int normalizedMinimum = clamp(
                    minChunks,
                    2,
                    64
            );
            int normalizedMaximum = clamp(
                    Math.max(
                            normalizedMinimum,
                            maxChunks
                    ),
                    normalizedMinimum,
                    64
            );

            return new Snapshot(
                    nonNull(
                            mode,
                            PotatoPerformanceMode.AUTO_DYNAMIC
                    ),
                    dynamicChunks,
                    clamp(targetFps, 30, 240),
                    normalizedMinimum,
                    normalizedMaximum,
                    nonNull(
                            lodProfile,
                            PotatoLodProfile.ADAPTIVE
                    ),
                    clamp(nativeBudgetPercent, 25, 150),
                    nonNull(
                            developerHardwareProfile,
                            PotatoDeveloperHardwareProfile.REAL_HARDWARE
                    )
            );
        }
    }

    public record HardwarePlan(
            String source,
            int planningVramMiB,
            int staticAdmissions,
            long deferredBytes,
            int deferredEntries,
            int promotionsPerSweep,
            long promotionBytesPerSweep,
            int warmSweeps,
            int automaticMaxChunks
    ) {
        private HardwarePlan capTo(HardwarePlan cap) {
            return new HardwarePlan(
                    source + "+CAP_" + cap.source,
                    Math.min(
                            positiveOrMax(planningVramMiB),
                            positiveOrMax(cap.planningVramMiB)
                    ),
                    Math.min(
                            staticAdmissions,
                            cap.staticAdmissions
                    ),
                    Math.min(
                            deferredBytes,
                            cap.deferredBytes
                    ),
                    Math.min(
                            deferredEntries,
                            cap.deferredEntries
                    ),
                    Math.min(
                            promotionsPerSweep,
                            cap.promotionsPerSweep
                    ),
                    Math.min(
                            promotionBytesPerSweep,
                            cap.promotionBytesPerSweep
                    ),
                    Math.min(
                            warmSweeps,
                            cap.warmSweeps
                    ),
                    Math.min(
                            automaticMaxChunks,
                            cap.automaticMaxChunks
                    )
            );
        }

        private HardwarePlan scaledNativeBudgets(int percent) {
            int safePercent = clamp(percent, 25, 150);

            return new HardwarePlan(
                    source + "+CUSTOM_" + safePercent + "PCT",
                    planningVramMiB,
                    clamp(
                            scaleInt(
                                    staticAdmissions,
                                    safePercent
                            ),
                            256,
                            16384
                    ),
                    Math.max(
                            8L * MIB,
                            Math.min(
                                    256L * MIB,
                                    scaleLong(
                                            deferredBytes,
                                            safePercent
                                    )
                            )
                    ),
                    clamp(
                            scaleInt(
                                    deferredEntries,
                                    safePercent
                            ),
                            256,
                            16384
                    ),
                    clamp(
                            scaleInt(
                                    promotionsPerSweep,
                                    safePercent
                            ),
                            4,
                            256
                    ),
                    Math.max(
                            256L * 1024L,
                            Math.min(
                                    8L * MIB,
                                    scaleLong(
                                            promotionBytesPerSweep,
                                            safePercent
                                    )
                            )
                    ),
                    clamp(
                            warmSweeps,
                            30,
                            600
                    ),
                    automaticMaxChunks
            );
        }

        private static int positiveOrMax(int value) {
            return value > 0
                    ? value
                    : Integer.MAX_VALUE;
        }

        private static int scaleInt(
                int value,
                int percent
        ) {
            return (int) Math.max(
                    1L,
                    (long) value * percent / 100L
            );
        }

        private static long scaleLong(
                long value,
                int percent
        ) {
            return Math.max(
                    1L,
                    value * percent / 100L
            );
        }
    }
}
