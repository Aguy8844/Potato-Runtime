package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;

/**
 * One explicit ownership boundary for Gate 10 dynamic rendering.
 *
 * <p>Stage 095 intentionally resolves every domain to OpenGL. This is not a
 * placeholder readiness boolean: it is the fail-open policy that the later
 * Vulkan immediate renderer must ask before suppressing any vanilla draw.</p>
 */
public final class VulkanGate10DynamicOwnershipContract {

    public enum Domain {
        ENTITY,
        PARTICLE,
        HUD,
        SCREEN
    }

    public enum Authority {
        OPENGL,
        VULKAN
    }

    private VulkanGate10DynamicOwnershipContract() {
    }

    public static Authority authority(
            Domain domain
    ) {
        if (domain == null) {
            return Authority.OPENGL;
        }

        return Authority.OPENGL;
    }

    public static boolean maySuppressOpenGl(
            Domain domain
    ) {
        return authority(domain)
                == Authority.VULKAN;
    }

    public static void enrich(
            JsonObject report
    ) {
        if (report == null) {
            return;
        }

        report.addProperty(
                "gate10DynamicOwnershipContractInstalled",
                true
        );
        report.addProperty(
                "gate10DynamicOwnershipContractMode",
                "PER_DOMAIN_FAIL_OPEN_OPENGL_AUTHORITY_STAGE1"
        );
        report.addProperty(
                "gate10DynamicOwnershipEntityAuthority",
                authority(Domain.ENTITY).name()
        );
        report.addProperty(
                "gate10DynamicOwnershipParticleAuthority",
                authority(Domain.PARTICLE).name()
        );
        report.addProperty(
                "gate10DynamicOwnershipHudAuthority",
                authority(Domain.HUD).name()
        );
        report.addProperty(
                "gate10DynamicOwnershipScreenAuthority",
                authority(Domain.SCREEN).name()
        );
        report.addProperty(
                "gate10DynamicOwnershipUnknownDomainFailOpen",
                true
        );
        report.addProperty(
                "gate10DynamicOwnershipOpenGlSuppressionEnabled",
                false
        );
    }
}
