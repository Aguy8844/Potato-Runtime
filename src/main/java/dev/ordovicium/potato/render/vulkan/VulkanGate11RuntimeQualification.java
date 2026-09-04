package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;

/**
 * Final shutdown-time qualification for Potato Engine Gate 11.
 *
 * <p>This class never treats a milestone flag as proof. It derives Gate 11
 * only from telemetry produced by the current process after the bounded
 * NO_API Vulkan presentation stream, final MainTarget capture, input relay,
 * and runtime teardown have all completed successfully.</p>
 */
final class VulkanGate11RuntimeQualification {

    private static final long MINIMUM_PRESENTED_FRAMES =
            120L;

    private static final long MINIMUM_VISIBLE_DURATION_MILLIS =
            5_000L;

    private static boolean qualifiedInCurrentProcess;

    private VulkanGate11RuntimeQualification() {
    }

    static synchronized boolean qualify(
            JsonObject report
    ) {
        if (report == null) {
            return false;
        }

        boolean gate10Qualified =
                bool(
                        report,
                        "gate10RuntimeQualificationPassed"
                )
                        && bool(
                        report,
                        "potatoEngineVulkanGuiEntityParticleReady"
                );

        boolean noApiCandidateQualified =
                bool(
                        report,
                        "gate11NoApiHandoffRehearsalPassed"
                )
                        && bool(
                        report,
                        "gate11NoApiHandoffRehearsalCandidateUsesNoApi"
                )
                        && bool(
                        report,
                        "gate11NoApiHandoffRehearsalSurfaceCreated"
                )
                        && bool(
                        report,
                        "gate11NoApiHandoffRehearsalPresentationSupported"
                )
                        && bool(
                        report,
                        "gate11NoApiHandoffRehearsalLogicalSizeMatchesMainWindow"
                )
                        && bool(
                        report,
                        "gate11NoApiHandoffRehearsalFramebufferSizeMatchesMainWindow"
                )
                        && bool(
                        report,
                        "gate11NoApiHandoffRehearsalCurrentContextPreservedExactly"
                )
                        && bool(
                        report,
                        "gate11NoApiHandoffRehearsalBorrowedRuntimeSurface"
                )
                        && bool(
                        report,
                        "gate11NoApiHandoffRehearsalPersistentSwapchainPrepared"
                )
                        && !bool(
                        report,
                        "gate11CreatesSecondGameplayVkSurface"
                )
                        && number(
                        report,
                        "gate11NoApiHandoffRehearsalCreateSurfaceResult"
                ) == 0L
                        && !bool(
                        report,
                        "gate11NoApiHandoffRehearsalMutatesMinecraftWindow"
                )
                        && !bool(
                        report,
                        "gate11NoApiHandoffRehearsalGameplayGpuWait"
                );

        boolean shadowSwapchainQualified =
                bool(
                        report,
                        "gate11ShadowSwapchainRehearsalPassed"
                )
                        && bool(
                        report,
                        "gate11ShadowSwapchainCreated"
                )
                        && bool(
                        report,
                        "gate11ShadowSwapchainAcquireSucceeded"
                )
                        && bool(
                        report,
                        "gate11ShadowSwapchainCallsVkQueuePresentKHR"
                )
                        && bool(
                        report,
                        "gate11ShadowSwapchainPresentAccepted"
                )
                        && bool(
                        report,
                        "gate11ShadowSwapchainBorrowedRuntimeSurface"
                )
                        && bool(
                        report,
                        "gate11ShadowSwapchainBorrowedPersistentRuntimeSwapchain"
                )
                        && !bool(
                        report,
                        "gate11CreatesSecondGameplaySwapchain"
                )
                        && bool(
                        report,
                        "gate11ShadowSwapchainMainOpenGlContextPreservedExactly"
                )
                        && number(
                        report,
                        "gate11ShadowSwapchainCreateSwapchainResult"
                ) == 0L
                        && number(
                        report,
                        "gate11ShadowSwapchainAcquireResult"
                ) == 0L
                        && number(
                        report,
                        "gate11ShadowSwapchainPresentResult"
                ) == 0L
                        && !bool(
                        report,
                        "gate11ShadowSwapchainMutatesMinecraftWindow"
                )
                        && !bool(
                        report,
                        "gate11ShadowSwapchainMutatesOpenGlDraws"
                )
                        && !bool(
                        report,
                        "gate11ShadowSwapchainGameplayFenceWait"
                )
                        && !bool(
                        report,
                        "gate11ShadowSwapchainGameplayQueueWaitIdle"
                )
                        && !bool(
                        report,
                        "gate11ShadowSwapchainGameplayDeviceWaitIdle"
                );

        boolean realContentQualified =
                bool(
                        report,
                        "gate11RealContentFrameClaimed"
                )
                        && bool(
                        report,
                        "gate11RealContentFrameSizeMatchesSwapchain"
                )
                        && bool(
                        report,
                        "gate11RealContentFrameFormatMatchesSwapchain"
                )
                        && bool(
                        report,
                        "gate11RealContentPresented"
                )
                        && bool(
                        report,
                        "gate11RealContentSourceReturnedToColorAttachmentLayout"
                )
                        && "GPU_BLIT_FLIP_Y_ONLY".equals(
                        text(
                                report,
                                "gate11RealContentOrientationCorrection"
                        )
                )
                        && number(
                        report,
                        "gate11RealContentOrientationRotationDegrees"
                ) == 0L
                        && "PASSED_GPU_ONLY_REAL_MINECRAFT_CONTENT_PRESENT_FLIP_Y_CORRECTED".equals(
                        text(
                                report,
                                "gate11RealContentFailureReason"
                        )
                )
                        && !bool(
                        report,
                        "gate11RealContentCpuReadback"
                )
                        && !bool(
                        report,
                        "gate11RealContentGameplayGpuWait"
                );

        boolean visibleWindowQualified =
                bool(
                        report,
                        "gate11VisibleReplacementRehearsalPassed"
                )
                        && bool(
                        report,
                        "gate11VisibleReplacementRehearsalShown"
                )
                        && bool(
                        report,
                        "gate11VisibleReplacementRehearsalHiddenAfter"
                )
                        && bool(
                        report,
                        "gate11VisibleReplacementRehearsalCurrentContextPreservedAfterShow"
                )
                        && bool(
                        report,
                        "gate11VisibleReplacementRehearsalCurrentContextPreservedAfterHide"
                )
                        && bool(
                        report,
                        "gate11VisibleReplacementRehearsalMainWindowHandlePreserved"
                )
                        && bool(
                        report,
                        "gate11VisibleReplacementRehearsalUsesRealMinecraftContent"
                )
                        && bool(
                        report,
                        "gate11VisibleReplacementRevealDeferredUntilPrewarm"
                )
                        && number(
                        report,
                        "gate11VisibleReplacementPrewarmPresentCount"
                ) >= number(
                        report,
                        "gate11VisibleReplacementPrewarmRequiredPresents"
                )
                        && !bool(
                        report,
                        "gate11VisibleReplacementDeveloperTitleExposed"
                )
                        && number(
                        report,
                        "gate11VisibleReplacementRehearsalDurationMillis"
                ) >= MINIMUM_VISIBLE_DURATION_MILLIS
                        && ("PASSED_BOUNDED_LIVE_NO_API_VULKAN_UI_PRESENTATION_STREAM".equals(
                        text(
                                report,
                                "gate11VisibleReplacementRehearsalFailureReason"
                        )
                )
                        || ("PASSED_PRODUCTION_SINGLE_VISIBLE_VULKAN_PRESENTATION_SESSION".equals(
                        text(
                                report,
                                "gate11VisibleReplacementRehearsalFailureReason"
                        )
                )
                        && bool(
                        report,
                        "gate11ProductionSingleVisibleSessionEnabled"
                )
                        && bool(
                        report,
                        "gate11ProductionPresentationFocusableStyleApplied"
                )
                        && bool(
                        report,
                        "gate11ProductionPresentationOpenGlOwnerHidden"
                )
                        && bool(
                        report,
                        "gate11ProductionPresentationCandidateFocused"
                )
                        && !bool(
                        report,
                        "gate11ProductionPresentationFailOpenTriggered"
                )
                        && bool(
                        report,
                        "gate11ProductionPresentationFallbackRestored"
                )))
                        && !bool(
                        report,
                        "gate11VisibleReplacementRehearsalGameplayGpuWait"
                );

        long claims =
                number(
                        report,
                        "gate11LiveUiPresentationFrameClaimCount"
                );

        long submits =
                number(
                        report,
                        "gate11LiveUiPresentationFrameSubmitCount"
                );

        long presents =
                number(
                        report,
                        "gate11LiveUiPresentationFramePresentCount"
                );

        long acknowledgements =
                number(
                        report,
                        "gate11LiveUiPresentationFrameSubmitAckCount"
                );

        long recoverableOutOfDatePresents =
                number(
                        report,
                        "gate11LiveUiPresentationRecoverableOutOfDatePresentCount"
                );

        long acquireOutOfDateCount =
                number(
                        report,
                        "gate11LiveUiPresentationAcquireOutOfDateCount"
                );

        long presentOutOfDateCount =
                number(
                        report,
                        "gate11LiveUiPresentationPresentOutOfDateCount"
                );

        long acquireSuboptimalCount =
                number(
                        report,
                        "gate11LiveUiPresentationAcquireSuboptimalCount"
                );

        long presentSuboptimalCount =
                number(
                        report,
                        "gate11LiveUiPresentationPresentSuboptimalCount"
                );

        long swapchainRefreshSuccessCount =
                number(
                        report,
                        "gate11LiveUiPresentationSwapchainRefreshSuccessCount"
                );

        boolean resizeRecoveryQualified =
                number(
                        report,
                        "gate11LiveUiPresentationSwapchainRefreshFailureCount"
                ) == 0L
                        && recoverableOutOfDatePresents
                        == presentOutOfDateCount
                        && (acquireOutOfDateCount
                        + presentOutOfDateCount
                        + acquireSuboptimalCount
                        + presentSuboptimalCount == 0L
                        || swapchainRefreshSuccessCount > 0L)
                        && !bool(
                        report,
                        "gate11LiveUiPresentationResizeRecoveryUsesGameplayGpuWait"
                );

        boolean consumerCountsMatch =
                claims >= MINIMUM_PRESENTED_FRAMES
                        && claims == submits
                        && submits
                        == presents
                        + recoverableOutOfDatePresents
                        && submits == acknowledgements;

        boolean liveConsumerQualified =
                bool(
                        report,
                        "gate11LiveUiPresentationInstalled"
                )
                        && bool(
                        report,
                        "gate11LiveUiPresentationResourcesCreated"
                )
                        && bool(
                        report,
                        "gate11LiveUiPresentationRetired"
                )
                        && consumerCountsMatch
                        && number(
                        report,
                        "gate11LiveUiPresentationFailureCount"
                ) == 0L
                        && resizeRecoveryQualified
                        && number(
                        report,
                        "gate11LiveUiPresentationLastAcquireResult"
                ) == 0L
                        && number(
                        report,
                        "gate11LiveUiPresentationLastQueueSubmitResult"
                ) == 0L
                        && number(
                        report,
                        "gate11LiveUiPresentationLastPresentResult"
                ) == 0L
                        && ("BOUNDED_VISIBLE_STREAM_COMPLETE".equals(
                        text(
                                report,
                                "gate11LiveUiPresentationFailureReason"
                        )
                )
                        || "PRODUCTION_SESSION_SHUTDOWN".equals(
                        text(
                                report,
                                "gate11LiveUiPresentationFailureReason"
                        )
                )
                        || "WORLD_SESSION_ENDED_OPENGL_MENU_HANDOFF".equals(
                        text(
                                report,
                                "gate11LiveUiPresentationFailureReason"
                        )
                ))
                        && bool(
                        report,
                        "gate11LiveUiPresentationUsesFreshFinalScreenFrames"
                )
                        && bool(
                        report,
                        "gate11LiveUiPresentationFontGlyphsComeFromFreshMainTargetFrames"
                )
                        && !bool(
                        report,
                        "gate11LiveUiPresentationCpuReadback"
                )
                        && !bool(
                        report,
                        "gate11LiveUiPresentationGameplayFenceWait"
                )
                        && !bool(
                        report,
                        "gate11LiveUiPresentationGameplayQueueWaitIdle"
                )
                        && !bool(
                        report,
                        "gate11LiveUiPresentationGameplayDeviceWaitIdle"
                );

        long producerPublishes =
                number(
                        report,
                        "gate10Gate11LiveUiStreamFramePublishCount"
                );

        long producerClaims =
                number(
                        report,
                        "gate10Gate11LiveUiStreamFrameClaimCount"
                );

        long producerAcknowledgements =
                number(
                        report,
                        "gate10Gate11LiveUiStreamFrameSubmitAckCount"
                );

        long producerShutdownTailDiscards =
                number(
                        report,
                        "gate10Gate11LiveUiStreamShutdownUnclaimedTailDiscardCount"
                );

        String producerStopReason =
                text(
                        report,
                        "gate10Gate11LiveUiStreamStopReason"
                );

        boolean producerShutdownTailBalanced =
                ("PRODUCTION_SESSION_SHUTDOWN".equals(
                        producerStopReason
                )
                        || "WORLD_SESSION_ENDED_OPENGL_MENU_HANDOFF".equals(
                        producerStopReason
                ))
                        && producerShutdownTailDiscards >= 0L
                        && producerShutdownTailDiscards <= 1L
                        && producerPublishes
                        == producerClaims
                        + producerShutdownTailDiscards;

        boolean producerCountsMatch =
                producerPublishes >= MINIMUM_PRESENTED_FRAMES
                        && producerClaims == producerAcknowledgements
                        && (producerPublishes == producerClaims
                        || producerShutdownTailBalanced);

        boolean liveProducerQualified =
                producerCountsMatch
                        && number(
                        report,
                        "gate10Gate11LiveUiStreamRetireCount"
                ) >= 1L
                        && !bool(
                        report,
                        "gate10Gate11LiveUiStreamFrameClaimedAwaitingSubmit"
                )
                        && !bool(
                        report,
                        "gate10Gate11LiveUiStreamReturnSemaphorePending"
                )
                        && ("BOUNDED_VISIBLE_STREAM_COMPLETE".equals(
                        text(
                                report,
                                "gate10Gate11LiveUiStreamStopReason"
                        )
                )
                        || "PRODUCTION_SESSION_SHUTDOWN".equals(
                        text(
                                report,
                                "gate10Gate11LiveUiStreamStopReason"
                        )
                )
                        || "WORLD_SESSION_ENDED_OPENGL_MENU_HANDOFF".equals(
                        text(
                                report,
                                "gate10Gate11LiveUiStreamStopReason"
                        )
                ))
                        && !bool(
                        report,
                        "gate10Gate11LiveUiStreamCpuReadback"
                )
                        && !bool(
                        report,
                        "gate10Gate11LiveUiStreamGameplayGpuWait"
                );

        boolean finalPixelSourceQualified =
                bool(
                        report,
                        "gate10VisibleScreenRehearsalQualifiedFramePublishedForGate11"
                )
                        && bool(
                        report,
                        "gate10VisibleScreenRehearsalMainTargetRealPixelsCopied"
                )
                        && bool(
                        report,
                        "gate10VisibleScreenRehearsalRealContentRoundTripComplete"
                )
                        && bool(
                        report,
                        "gate10VisibleScreenRehearsalFullResolutionTargetSelected"
                )
                        && bool(
                        report,
                        "gate10VisibleScreenRehearsalTargetMatchesLastCapturedMainTarget"
                )
                        && number(
                        report,
                        "gate10VisibleScreenRehearsalFailureCount"
                ) == 0L
                        && !bool(
                        report,
                        "gate10VisibleScreenRehearsalGameplayFenceWait"
                )
                        && !bool(
                        report,
                        "gate10VisibleScreenRehearsalGameplayQueueWaitIdle"
                )
                        && !bool(
                        report,
                        "gate10VisibleScreenRehearsalGameplayDeviceWaitIdle"
                )
                        && !bool(
                        report,
                        "gate10VisibleScreenRehearsalCpuPixelReadback"
                );

        boolean inputRelayQualified =
                bool(
                        report,
                        "gate11WindowLifecycleRouterInstalled"
                )
                        && bool(
                        report,
                        "gate11WindowLifecycleOwnerHandleNonZero"
                )
                        && bool(
                        report,
                        "gate11WindowLifecycleOwnerCursorPosCallbackCaptured"
                )
                        && bool(
                        report,
                        "gate11WindowLifecycleOwnerMouseButtonCallbackCaptured"
                )
                        && bool(
                        report,
                        "gate11WindowLifecycleOwnerScrollCallbackCaptured"
                )
                        && bool(
                        report,
                        "gate11WindowLifecycleOwnerFocusCallbackCaptured"
                )
                        && number(
                        report,
                        "gate11WindowLogicalFocusRelayEventCount"
                ) > 0L
                        && number(
                        report,
                        "gate11WindowLogicalFocusRelayFailureCount"
                ) == 0L
                        && bool(
                        report,
                        "gate11WindowLogicalFocusRelayTargetsMinecraftLifecycleCallback"
                )
                        && bool(
                        report,
                        "gate11WindowLifecycleOwnerKeyCallbackCaptured"
                )
                        && bool(
                        report,
                        "gate11WindowLifecycleOwnerCharModsCallbackCaptured"
                )
                        && bool(
                        report,
                        "gate11WindowKeyboardRelayVerified"
                )
                        && bool(
                        report,
                        "gate11WindowDeferredModifierBridgeInstalled"
                )
                        && bool(
                        report,
                        "gate11WindowDeferredModifierBridgeScopedToScreenDispatch"
                )
                        && bool(
                        report,
                        "gate11WindowDeferredModifierBridgeIntegrationProbePassed"
                )
                        && bool(
                        report,
                        "gate11WindowDeferredModifierBridgeHealthy"
                )
                        && number(
                        report,
                        "gate11WindowDeferredModifierBridgeScopeLeakCount"
                ) == 0L
                        && !bool(
                        report,
                        "gate11WindowDeferredModifierBridgeMutatesNativeKeyState"
                )
                        && !bool(
                        report,
                        "gate11WindowDeferredModifierBridgeInjectsSyntheticKeyEvents"
                )
                        && number(
                        report,
                        "gate11WindowKeyboardRelayKeyEventCount"
                ) > 0L
                        && number(
                        report,
                        "gate11WindowKeyboardRelayEscapePressCount"
                ) > 0L
                        && number(
                        report,
                        "gate11WindowKeyboardRelayDropCount"
                ) == 0L
                        && number(
                        report,
                        "gate11WindowKeyboardPostCloseTailIgnoredCount"
                ) >= 0L
                        && number(
                        report,
                        "gate11WindowCharPostCloseTailIgnoredCount"
                ) >= 0L
                        && number(
                        report,
                        "gate11WindowPointerRelayCursorEventCount"
                ) > 0L
                        && number(
                        report,
                        "gate11WindowPointerRelayMouseButtonEventCount"
                ) > 0L
                        && number(
                        report,
                        "gate11WindowMouseButtonCoordinateSyncAttemptCount"
                ) == number(
                        report,
                        "gate11WindowPointerRelayMouseButtonEventCount"
                )
                        && number(
                        report,
                        "gate11WindowMouseButtonCoordinateSyncSuccessCount"
                ) == number(
                        report,
                        "gate11WindowPointerRelayMouseButtonEventCount"
                )
                        && number(
                        report,
                        "gate11WindowNativeOwnerCursorWarpCount"
                ) == 0L
                        && number(
                        report,
                        "gate11WindowPointerRelayDropCount"
                ) == 0L
                        && bool(
                        report,
                        "gate11WindowPointerRelayVerified"
                )
                        && bool(
                        report,
                        "gate11WindowCursorStateParityVerified"
                )
                        && number(
                        report,
                        "gate11WindowMenuCursorRelayEventCount"
                ) > 0L
                        && number(
                        report,
                        "gate11WindowCursorModeMismatchCount"
                ) == 0L
                        && bool(
                        report,
                        "gate11ShadowSwapchainNativeNoActivateApplied"
                )
                        && bool(
                        report,
                        "gate11ShadowSwapchainNativeNoActivateFrameRefreshApplied"
                )
                        && number(
                        report,
                        "gate11WindowDirectScreenMousePressAttemptCount"
                ) > 0L
                        && number(
                        report,
                        "gate11WindowDirectScreenMousePressAcceptedCount"
                ) > 0L
                        && number(
                        report,
                        "gate11WindowDirectScreenMouseDispatchFailureCount"
                ) == 0L
                        && number(
                        report,
                        "gate11WindowDeferredScreenMouseOverflowDropCount"
                ) == 0L
                        && number(
                        report,
                        "gate11WindowDeferredScreenMousePendingCount"
                ) == 0L
                        && number(
                        report,
                        "gate11WindowPresentationFocusGainCount"
                ) == number(
                        report,
                        "gate11WindowPresentationFocusLossCount"
                )
                        && number(
                        report,
                        "gate11WindowFocusReturnAttemptCount"
                ) == number(
                        report,
                        "gate11WindowFocusReturnSuccessCount"
                )
                        && !bool(
                        report,
                        "gate11WindowRouterHidesMinecraftMainWindow"
                )
                        && !bool(
                        report,
                        "gate11WindowRouterMutatesMinecraftStoredHandle"
                )
                        && !bool(
                        report,
                        "gate11WindowRouterSuppressesGlfwSwapBuffers"
                )
                        && !bool(
                        report,
                        "gate11WindowRouterGameplayGpuWait"
                )
                        && !bool(
                        report,
                        "gate11WindowPointerRelayChangesWindowOwnership"
                )
                        && !bool(
                        report,
                        "gate11WindowPointerRelayInjectsKeyboardEvents"
                )
                        && !bool(
                        report,
                        "gate11WindowKeyboardRelayChangesWindowOwnership"
                )
                        && bool(
                        report,
                        "gate11WindowKeyboardRelayTargetsMinecraftLifecycleCallbacks"
                )
                        && text(
                        report,
                        "gate11WindowLifecycleRouterLastFailure"
                ).isBlank();

        boolean minecraftWindowInvariantQualified =
                bool(
                        report,
                        "minecraftWindowHandleNonZero"
                )
                        && bool(
                        report,
                        "minecraftWindowUsesOpenGL"
                )
                        && !bool(
                        report,
                        "minecraftWindowUsesNoApi"
                )
                        && bool(
                        report,
                        "minecraftCurrentContextHandleNonZero"
                )
                        && bool(
                        report,
                        "minecraftCurrentContextMatchesWindow"
                );

        boolean cleanLifecycleQualified =
                bool(
                        report,
                        "gate11WindowLifecycleRouterClosed"
                )
                        && bool(
                        report,
                        "gate11ShadowSwapchainResourcesDestroyed"
                )
                        && bool(
                        report,
                        "gate11ShadowSwapchainClosed"
                )
                        && bool(
                        report,
                        "persistentFrameSessionClosed"
                )
                        && bool(
                        report,
                        "persistentVulkanRuntimeClosed"
                )
                        && bool(
                        report,
                        "persistentVulkanRuntimeShutdownComplete"
                )
                        && !bool(
                        report,
                        "secondaryNoApiPresentationVisible"
                );

        boolean passed =
                gate10Qualified
                        && noApiCandidateQualified
                        && shadowSwapchainQualified
                        && realContentQualified
                        && visibleWindowQualified
                        && liveConsumerQualified
                        && liveProducerQualified
                        && finalPixelSourceQualified
                        && inputRelayQualified
                        && minecraftWindowInvariantQualified
                        && cleanLifecycleQualified;

        boolean previouslyQualified =
                qualifiedInCurrentProcess;

        if (passed) {
            qualifiedInCurrentProcess =
                    true;
        }

        boolean ready =
                qualifiedInCurrentProcess;

        report.addProperty(
                "gate11RuntimeQualificationInstalled",
                true
        );
        report.addProperty(
                "gate11RuntimeQualificationMode",
                "FRESH_PROCESS_GAMEPLAY_VULKAN_OPENGL_MENU_HANDOFF_DEFERRED_INPUT_PIXEL_AND_CLOSE_SAFE_PROOF"
        );
        report.addProperty(
                "gate11RuntimeQualificationUsesCurrentRuntimeTelemetryOnly",
                true
        );
        report.addProperty(
                "gate11RuntimeQualificationMinimumPresentedFrames",
                MINIMUM_PRESENTED_FRAMES
        );
        report.addProperty(
                "gate11RuntimeQualificationMinimumVisibleDurationMillis",
                MINIMUM_VISIBLE_DURATION_MILLIS
        );
        report.addProperty(
                "gate11RuntimeQualificationGate10Qualified",
                gate10Qualified
        );
        report.addProperty(
                "gate11RuntimeQualificationNoApiCandidateQualified",
                noApiCandidateQualified
        );
        report.addProperty(
                "gate11RuntimeQualificationShadowSwapchainQualified",
                shadowSwapchainQualified
        );
        report.addProperty(
                "gate11RuntimeQualificationRealContentQualified",
                realContentQualified
        );
        report.addProperty(
                "gate11RuntimeQualificationVisibleWindowQualified",
                visibleWindowQualified
        );
        report.addProperty(
                "gate11RuntimeQualificationConsumerCountsMatch",
                consumerCountsMatch
        );
        report.addProperty(
                "gate11RuntimeQualificationResizeRecoveryQualified",
                resizeRecoveryQualified
        );
        report.addProperty(
                "gate11RuntimeQualificationRecoverableOutOfDatePresentCount",
                recoverableOutOfDatePresents
        );
        report.addProperty(
                "gate11RuntimeQualificationProducerCountsMatch",
                producerCountsMatch
        );
        report.addProperty(
                "gate11RuntimeQualificationProducerShutdownTailDiscardCount",
                producerShutdownTailDiscards
        );
        report.addProperty(
                "gate11RuntimeQualificationProducerShutdownTailBalanced",
                producerShutdownTailBalanced
        );
        report.addProperty(
                "gate11RuntimeQualificationLiveConsumerQualified",
                liveConsumerQualified
        );
        report.addProperty(
                "gate11RuntimeQualificationLiveProducerQualified",
                liveProducerQualified
        );
        report.addProperty(
                "gate11RuntimeQualificationFinalPixelSourceQualified",
                finalPixelSourceQualified
        );
        report.addProperty(
                "gate11RuntimeQualificationInputRelayQualified",
                inputRelayQualified
        );
        report.addProperty(
                "gate11RuntimeQualificationMinecraftWindowInvariantQualified",
                minecraftWindowInvariantQualified
        );
        report.addProperty(
                "gate11RuntimeQualificationCleanLifecycleQualified",
                cleanLifecycleQualified
        );
        report.addProperty(
                "gate11RuntimeQualificationGate8Required",
                false
        );
        report.addProperty(
                "gate11RuntimeQualificationGate8Observed",
                bool(
                        report,
                        "potatoEngineVulkanWorldDrawReady"
                )
        );
        report.addProperty(
                "gate11RuntimeQualificationPassedThisEvaluation",
                passed
        );
        report.addProperty(
                "gate11RuntimeQualificationStickyReady",
                ready
        );
        report.addProperty(
                "gate11RuntimeQualificationReason",
                reason(
                        ready,
                        previouslyQualified,
                        gate10Qualified,
                        noApiCandidateQualified,
                        shadowSwapchainQualified,
                        realContentQualified,
                        visibleWindowQualified,
                        liveConsumerQualified,
                        liveProducerQualified,
                        finalPixelSourceQualified,
                        inputRelayQualified,
                        minecraftWindowInvariantQualified,
                        cleanLifecycleQualified
                )
        );

        report.addProperty(
                "potatoEngineVulkanMainWindowPresentationReady",
                ready
        );

        return ready;
    }

    private static String reason(
            boolean ready,
            boolean previouslyQualified,
            boolean gate10Qualified,
            boolean noApiCandidateQualified,
            boolean shadowSwapchainQualified,
            boolean realContentQualified,
            boolean visibleWindowQualified,
            boolean liveConsumerQualified,
            boolean liveProducerQualified,
            boolean finalPixelSourceQualified,
            boolean inputRelayQualified,
            boolean minecraftWindowInvariantQualified,
            boolean cleanLifecycleQualified
    ) {
        if (ready && previouslyQualified) {
            return "STICKY_ALREADY_QUALIFIED_IN_CURRENT_PROCESS";
        }
        if (ready) {
            return "PASSED_FRESH_GATE11_RUNTIME_QUALIFICATION";
        }
        if (!gate10Qualified) {
            return "WAITING_GATE10_FRESH_QUALIFICATION";
        }
        if (!noApiCandidateQualified) {
            return "NO_API_CANDIDATE_QUALIFICATION_INCOMPLETE";
        }
        if (!shadowSwapchainQualified) {
            return "NO_API_SWAPCHAIN_PRESENT_QUALIFICATION_INCOMPLETE";
        }
        if (!realContentQualified) {
            return "REAL_MINECRAFT_FRAME_ORIENTATION_QUALIFICATION_INCOMPLETE";
        }
        if (!visibleWindowQualified) {
            return "VISIBLE_NO_API_STREAM_QUALIFICATION_INCOMPLETE";
        }
        if (!liveConsumerQualified) {
            return "LIVE_VULKAN_PRESENT_CONSUMER_QUALIFICATION_INCOMPLETE";
        }
        if (!liveProducerQualified) {
            return "FINAL_FRAME_PRODUCER_ACK_QUALIFICATION_INCOMPLETE";
        }
        if (!finalPixelSourceQualified) {
            return "FINAL_MAINTARGET_PIXEL_QUALIFICATION_INCOMPLETE";
        }
        if (!inputRelayQualified) {
            return "POINTER_RELAY_QUALIFICATION_INCOMPLETE";
        }
        if (!minecraftWindowInvariantQualified) {
            return "MINECRAFT_WINDOW_OR_GL_CONTEXT_CHANGED";
        }
        if (!cleanLifecycleQualified) {
            return "WAITING_CLEAN_RUNTIME_LIFECYCLE_CLOSE";
        }
        return "QUALIFICATION_INCOMPLETE";
    }

    private static boolean bool(
            JsonObject report,
            String name
    ) {
        try {
            return report.has(name)
                    && report.get(name) != null
                    && !report.get(name).isJsonNull()
                    && report.get(name).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long number(
            JsonObject report,
            String name
    ) {
        try {
            return report.has(name)
                    && report.get(name) != null
                    && !report.get(name).isJsonNull()
                    ? report.get(name).getAsLong()
                    : 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static String text(
            JsonObject report,
            String name
    ) {
        try {
            return report.has(name)
                    && report.get(name) != null
                    && !report.get(name).isJsonNull()
                    ? report.get(name).getAsString()
                    : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
