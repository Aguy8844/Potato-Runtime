package dev.ordovicium.potato.render.lod;

/**
 * Narrow bridge implemented by Minecraft's VertexBuffer mixin.
 *
 * <p>The asynchronous CPU builder never touches OpenGL. Finished results are
 * handed back through this bridge on Minecraft's render thread.</p>
 */
public interface PotatoLodProxyBridge {

    void potato$installLodBuild(
            long generation,
            PotatoLodBuildResult result
    );

    boolean potato$drawLodProxy(
            int requestedTier
    );

    int potato$currentLodTier();
}
