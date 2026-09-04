package dev.ordovicium.potato.render.backend;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Arrays;

/**
 * Machine-readable backend cutover contract derived from Patch 015c runtime
 * bytecode evidence.
 */
public final class RenderBackendBoundaryManifest {
    public static final int CONTRACT_VERSION = 1;

    private RenderBackendBoundaryManifest() {
    }

    public static void enrich(JsonObject report) {
        JsonArray boundaries = new JsonArray();

        int commonCount = 0;
        int seamCount = 0;
        int replacementCount = 0;
        int blockerCount = 0;
        int lifetimeCount = 0;

        for (RenderBackendBoundary boundary : RenderBackendBoundary.values()) {
            JsonObject entry = new JsonObject();

            entry.addProperty("id", boundary.id());
            entry.addProperty(
                    "currentOwner",
                    boundary.currentOwner()
            );
            entry.addProperty(
                    "policy",
                    boundary.policy().name()
            );
            entry.addProperty(
                    "rationale",
                    boundary.rationale()
            );

            boundaries.add(entry);

            switch (boundary.policy()) {
                case KEEP_COMMON -> commonCount++;
                case SEAM_VERIFIED -> seamCount++;
                case REPLACE_FOR_VULKAN -> replacementCount++;
                case CUTOVER_BLOCKER -> blockerCount++;
                case ADAPT_LIFETIME -> lifetimeCount++;
            }
        }

        report.addProperty(
                "backendBoundaryContractVersion",
                CONTRACT_VERSION
        );
        report.addProperty(
                "backendBoundaryCount",
                RenderBackendBoundary.values().length
        );
        report.add(
                "backendBoundaries",
                boundaries
        );

        report.addProperty(
                "backendCommonBoundaryCount",
                commonCount
        );
        report.addProperty(
                "backendVerifiedSeamCount",
                seamCount
        );
        report.addProperty(
                "backendReplacementBoundaryCount",
                replacementCount
        );
        report.addProperty(
                "backendCutoverBlockerCount",
                blockerCount
        );
        report.addProperty(
                "backendLifetimeAdaptationCount",
                lifetimeCount
        );

        report.addProperty(
                "backendCommonGlfwPreserved",
                true
        );
        report.addProperty(
                "backendInitSystemCanRemainCommon",
                true
        );

        report.addProperty(
                "earlyDisplayOpenGlOwned",
                true
        );
        report.addProperty(
                "earlyDisplayBlocksNoApiMainWindow",
                true
        );

        report.addProperty(
                "minecraftWindowHandoffSeamAvailable",
                true
        );

        report.addProperty(
                "minecraftContextBootstrapRequiresReplacement",
                true
        );
        report.addProperty(
                "rendererInitializationRequiresReplacement",
                true
        );
        report.addProperty(
                "renderTargetsAndStateRequireReplacement",
                true
        );
        report.addProperty(
                "framePresentationRequiresReplacement",
                true
        );
        report.addProperty(
                "vsyncRequiresPresentModePolicy",
                true
        );

        report.addProperty(
                "mainWindowVulkanMutationReady",
                false
        );
        report.addProperty(
                "nextBackendMilestone",
                "POTATO_MAIN_WINDOW_NO_API_HANDOFF_CUTOVER"
        );
    }

    public static boolean hasExactlyOneCutoverBlocker() {
        return Arrays.stream(RenderBackendBoundary.values())
                .filter(
                        boundary ->
                                boundary.policy()
                                        == BackendBoundaryPolicy.CUTOVER_BLOCKER
                )
                .count() == 1L;
    }

    public static RenderBackendBoundary cutoverBlocker() {
        return Arrays.stream(RenderBackendBoundary.values())
                .filter(
                        boundary ->
                                boundary.policy()
                                        == BackendBoundaryPolicy.CUTOVER_BLOCKER
                )
                .findFirst()
                .orElseThrow();
    }
}