package dev.ordovicium.potato.render.vulkan;

/**
 * Small Vulkan formatting helpers shared by diagnostics and bootstrap code.
 */
final class VulkanFormat {
    private static final long MIB = 1024L * 1024L;

    private VulkanFormat() {
    }

    static long bytesToMiB(long bytes) {
        return bytes / MIB;
    }

    static String apiVersion(int version) {
        int major = (version >>> 22) & 0x7F;
        int minor = (version >>> 12) & 0x3FF;
        int patch = version & 0xFFF;

        return major + "." + minor + "." + patch;
    }
}