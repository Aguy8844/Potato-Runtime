package dev.ordovicium.potato.render.engine;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record PotatoRenderEngineDecision(
        PotatoRenderBackendPreference preference,
        PotatoRenderBackendId preferredBackend,
        PotatoRenderBackendId activeBackend,
        PotatoHardwareClass hardwareClass,
        boolean vulkanEligible,
        boolean vulkanActivationReady,
        int readinessPassed,
        int readinessTotal,
        int readinessPercent,
        String reason,
        String selectedDeviceName,
        String selectedDeviceType,
        boolean selectedDeviceTranslationLayer,
        long selectedDeviceLocalMemoryMiB,
        int logicalProcessors,
        long jvmMaxMiB,
        Map<PotatoRenderReadinessGate, Boolean> readiness
) {
    public PotatoRenderEngineDecision {
        EnumMap<PotatoRenderReadinessGate, Boolean> copy =
                new EnumMap<>(
                        PotatoRenderReadinessGate.class
                );

        if (readiness != null) {
            copy.putAll(
                    readiness
            );
        }

        readiness =
                Collections.unmodifiableMap(
                        copy
                );
    }

    public boolean migrationGateActive() {
        return preferredBackend
                == PotatoRenderBackendId.VULKAN
                && activeBackend
                != PotatoRenderBackendId.VULKAN;
    }
}
