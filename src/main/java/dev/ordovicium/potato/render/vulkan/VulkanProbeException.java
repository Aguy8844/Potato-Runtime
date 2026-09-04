package dev.ordovicium.potato.render.vulkan;

/**
 * Internal control-flow exception for a Vulkan probe stage that failed in a
 * known and reportable way.
 */
final class VulkanProbeException extends RuntimeException {
    private final String stage;

    VulkanProbeException(String stage, String message) {
        super(message);
        this.stage = stage;
    }

    String stage() {
        return stage;
    }
}