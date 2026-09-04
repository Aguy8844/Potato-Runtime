package dev.ordovicium.potato.render.visibility;

/**
 * Per-VertexBuffer temporal occlusion state. Implemented directly by the
 * existing VertexBuffer mixin so the hot draw path needs no map lookup or
 * per-draw allocation.
 */
public interface PotatoTemporalOcclusionBridge {

    boolean potato$shouldSkipTemporalOcclusion(
            PotatoTemporalOcclusionFrame frame
    );

    boolean potato$beginTemporalOcclusionQuery(
            PotatoTemporalOcclusionFrame frame
    );

    void potato$endTemporalOcclusionQuery();

    void potato$invalidateTemporalOcclusion();

    void potato$closeTemporalOcclusion();
}
