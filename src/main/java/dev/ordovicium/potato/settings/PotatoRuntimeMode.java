package dev.ordovicium.potato.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Properties;

/**
 * Release-facing Potato Runtime switch.
 *
 * <p>The selected value is persisted immediately, while the startup value is
 * latched once per process. That boundary is intentional: OFF can then decide
 * Mixin application before Minecraft classes are transformed, so clean A/B
 * testing never tries to hot-unapply bytecode or renderer ownership.</p>
 */
public enum PotatoRuntimeMode {
    DYNAMIC("DYNAMIC", true, true),
    ON("ON", true, false),
    OFF("OFF", false, false);

    private static final Path CONFIG_PATH =
            Path.of(
                    "config",
                    "potato-runtime-mode.properties"
            );

    private static final PotatoRuntimeMode STARTUP_MODE =
            readPersistedMode();

    private static volatile PotatoRuntimeMode selectedMode =
            STARTUP_MODE;

    private final String displayName;
    private final boolean enabled;
    private final boolean dynamic;

    PotatoRuntimeMode(
            String displayName,
            boolean enabled,
            boolean dynamic
    ) {
        this.displayName = displayName;
        this.enabled = enabled;
        this.dynamic = dynamic;
    }

    public String displayName() {
        return displayName;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean dynamic() {
        return dynamic;
    }

    public PotatoRuntimeMode next() {
        return switch (this) {
            case DYNAMIC -> ON;
            case ON -> OFF;
            case OFF -> DYNAMIC;
        };
    }

    public static PotatoRuntimeMode startupMode() {
        return STARTUP_MODE;
    }

    public static PotatoRuntimeMode selectedMode() {
        return selectedMode;
    }

    public static boolean restartRequired() {
        return selectedMode != STARTUP_MODE;
    }

    public static synchronized PotatoRuntimeMode cycleSelectedMode() {
        PotatoRuntimeMode next =
                selectedMode.next();

        select(next);
        return next;
    }

    public static synchronized void select(
            PotatoRuntimeMode mode
    ) {
        PotatoRuntimeMode safe =
                mode != null
                        ? mode
                        : DYNAMIC;

        persist(safe);
        selectedMode = safe;
    }

    private static PotatoRuntimeMode readPersistedMode() {
        if (!Files.isRegularFile(CONFIG_PATH)) {
            return DYNAMIC;
        }

        Properties properties = new Properties();

        try (InputStream input =
                     Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
        } catch (IOException ignored) {
            return DYNAMIC;
        }

        String raw =
                properties.getProperty(
                        "mode",
                        "DYNAMIC"
                );

        try {
            return PotatoRuntimeMode.valueOf(
                    raw.trim()
                            .toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException ignored) {
            return DYNAMIC;
        }
    }

    private static void persist(
            PotatoRuntimeMode mode
    ) {
        Properties properties = new Properties();
        properties.setProperty(
                "mode",
                mode.name()
        );

        try {
            Path parent = CONFIG_PATH.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path temporary =
                    CONFIG_PATH.resolveSibling(
                            CONFIG_PATH.getFileName()
                                    + ".tmp"
                    );

            try (OutputStream output =
                         Files.newOutputStream(temporary)) {
                properties.store(
                        output,
                        "Potato Runtime mode"
                );
            }

            try {
                Files.move(
                        temporary,
                        CONFIG_PATH,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        CONFIG_PATH,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException ignored) {
            /*
             * A read-only config directory must never take down Minecraft.
             * The current selected value still changes for this UI session.
             */
        }
    }
}
