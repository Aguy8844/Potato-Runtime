package dev.ordovicium.potato.mixin.client;

import dev.ordovicium.potato.render.visibility.ChunkWorkBudgetPolicy;
import dev.ordovicium.potato.render.visibility.PotatoPredictiveStreamingRuntime;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame-budgets visible section compilation and promotes a tiny motion-predicted
 * set before fast travel can expose it.
 *
 * <p>The player-local 3x3 safety ring remains the highest priority. Patch 148a
 * adds a bounded look-ahead corridor based on camera velocity. This is client
 * render-mesh scheduling only: no server ticket, simulation-distance or world
 * ticking mutation is performed.</p>
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererChunkWorkBudgetMixin {

    @Unique
    private int potato$compileCameraChunkX;

    @Unique
    private int potato$compileCameraChunkZ;

    @Unique
    private int potato$nearFieldSyncRebuildsThisPass;

    @Unique
    private int potato$predictiveSyncRebuildsThisPass;

    @Inject(
            method = "compileSections(Lnet/minecraft/client/Camera;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$beginCompileBudget(
            Camera camera,
            CallbackInfo callbackInfo
    ) {
        ChunkWorkBudgetPolicy.beginCompilePass();
        potato$nearFieldSyncRebuildsThisPass = 0;
        potato$predictiveSyncRebuildsThisPass = 0;

        if (camera != null
                && camera.getPosition() != null) {
            double cameraX = camera.getPosition().x;
            double cameraY = camera.getPosition().y;
            double cameraZ = camera.getPosition().z;

            potato$compileCameraChunkX =
                    ((int) Math.floor(cameraX)) >> 4;
            potato$compileCameraChunkZ =
                    ((int) Math.floor(cameraZ)) >> 4;

            PotatoPredictiveStreamingRuntime.updateCamera(
                    cameraX,
                    cameraY,
                    cameraZ
            );
        }
    }

    @Redirect(
            method = "compileSections(Lnet/minecraft/client/Camera;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;rebuildSectionSync(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;Lnet/minecraft/client/renderer/chunk/RenderRegionCache;)V"
            ),
            require = 1
    )
    private void potato$budgetSynchronousRebuild(
            SectionRenderDispatcher dispatcher,
            SectionRenderDispatcher.RenderSection section,
            RenderRegionCache regionCache
    ) {
        if (potato$consumeNearFieldPriority(section)) {
            dispatcher.rebuildSectionSync(section, regionCache);
            ChunkWorkBudgetPolicy.onNearFieldPrioritySync();
            return;
        }

        if (potato$consumePredictivePriority(section)) {
            dispatcher.rebuildSectionSync(section, regionCache);
            ChunkWorkBudgetPolicy.onPredictivePrioritySync();
            return;
        }

        if (ChunkWorkBudgetPolicy.allowSynchronousRebuild()) {
            dispatcher.rebuildSectionSync(section, regionCache);
            return;
        }

        section.rebuildSectionAsync(dispatcher, regionCache);
    }

    @Redirect(
            method = "compileSections(Lnet/minecraft/client/Camera;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;rebuildSectionAsync(Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;Lnet/minecraft/client/renderer/chunk/RenderRegionCache;)V"
            ),
            require = 1
    )
    private void potato$prioritizePredictedAsyncRebuild(
            SectionRenderDispatcher.RenderSection section,
            SectionRenderDispatcher dispatcher,
            RenderRegionCache regionCache
    ) {
        if (potato$consumeNearFieldPriority(section)) {
            dispatcher.rebuildSectionSync(section, regionCache);
            ChunkWorkBudgetPolicy.onNearFieldPrioritySync();
            return;
        }

        PotatoPredictiveStreamingRuntime.PriorityBand priority =
                potato$priority(section);

        if (priority
                == PotatoPredictiveStreamingRuntime.PriorityBand.PREDICTED_NEAR
                && potato$consumePredictivePriorityBudget()) {
            dispatcher.rebuildSectionSync(section, regionCache);
            ChunkWorkBudgetPolicy.onPredictivePrioritySync();
            return;
        }

        if (potato$isNearField(section)) {
            ChunkWorkBudgetPolicy.onNearFieldPriorityAsyncFallback();
        } else if (priority
                == PotatoPredictiveStreamingRuntime.PriorityBand.PREDICTED_NEAR) {
            ChunkWorkBudgetPolicy.onPredictivePriorityAsyncFallback();
        } else if (priority
                == PotatoPredictiveStreamingRuntime.PriorityBand.PREDICTED_FAR) {
            ChunkWorkBudgetPolicy.onPredictiveFarAsyncObserved();
        }

        section.rebuildSectionAsync(dispatcher, regionCache);
    }

    @Unique
    private boolean potato$consumeNearFieldPriority(
            SectionRenderDispatcher.RenderSection section
    ) {
        if (!potato$isNearField(section)) {
            return false;
        }

        if (potato$nearFieldSyncRebuildsThisPass
                >= ChunkWorkBudgetPolicy.nearFieldPriorityMaximumSyncPerPass()) {
            return false;
        }

        potato$nearFieldSyncRebuildsThisPass++;
        return true;
    }

    @Unique
    private boolean potato$consumePredictivePriority(
            SectionRenderDispatcher.RenderSection section
    ) {
        return potato$priority(section)
                == PotatoPredictiveStreamingRuntime.PriorityBand.PREDICTED_NEAR
                && potato$consumePredictivePriorityBudget();
    }

    @Unique
    private boolean potato$consumePredictivePriorityBudget() {
        int maximum =
                PotatoPredictiveStreamingRuntime
                        .predictiveSyncBudgetPerCompilePass();

        if (maximum <= 0
                || potato$predictiveSyncRebuildsThisPass >= maximum) {
            return false;
        }

        potato$predictiveSyncRebuildsThisPass++;
        return true;
    }

    @Unique
    private PotatoPredictiveStreamingRuntime.PriorityBand potato$priority(
            SectionRenderDispatcher.RenderSection section
    ) {
        if (section == null) {
            return PotatoPredictiveStreamingRuntime.PriorityBand.NORMAL;
        }

        int sectionChunkX = section.getOrigin().getX() >> 4;
        int sectionChunkZ = section.getOrigin().getZ() >> 4;

        return PotatoPredictiveStreamingRuntime.classifyChunk(
                sectionChunkX,
                sectionChunkZ
        );
    }

    @Unique
    private boolean potato$isNearField(
            SectionRenderDispatcher.RenderSection section
    ) {
        if (section == null) {
            return false;
        }

        int sectionChunkX = section.getOrigin().getX() >> 4;
        int sectionChunkZ = section.getOrigin().getZ() >> 4;
        int radius = ChunkWorkBudgetPolicy.nearFieldPriorityRadiusChunks();

        return Math.abs(sectionChunkX - potato$compileCameraChunkX) <= radius
                && Math.abs(sectionChunkZ - potato$compileCameraChunkZ) <= radius;
    }
}
