package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns swapchain-scoped resources for the frame loop.
 *
 * <p>Image views and render-finished semaphores are created once per swapchain
 * image and reused. Presentation wait semaphores are indexed by acquired image,
 * not by frame-in-flight.</p>
 */
final class VulkanSwapchainResources implements AutoCloseable {
    private final VkDevice device;
    private final JsonObject report;

    private long swapchain;
    private long[] images;
    private long[] imageViews;
    private long[] renderFinishedSemaphores;
    private boolean[] firstUse;

    private VulkanSwapchainResources(
            VkDevice device,
            JsonObject report,
            long swapchain,
            long[] images,
            long[] imageViews,
            long[] renderFinishedSemaphores
    ) {
        this.device = device;
        this.report = report;
        this.swapchain = swapchain;
        this.images = images;
        this.imageViews = imageViews;
        this.renderFinishedSemaphores = renderFinishedSemaphores;
        this.firstUse = new boolean[images.length];

        java.util.Arrays.fill(
                this.firstUse,
                true
        );
    }

    static VulkanSwapchainResources create(
            VkDevice device,
            VulkanQueueFamilySelector.Selection queues,
            long surface,
            VulkanSwapchainSupport.Configuration configuration,
            long oldSwapchain,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty("stage", "CREATE_PERSISTENT_SWAPCHAIN");
        report.addProperty(
                "oldSwapchainSupplied",
                oldSwapchain != NULL
        );

        VkSwapchainCreateInfoKHR createInfo =
                VkSwapchainCreateInfoKHR.calloc(stack)
                        .sType$Default()
                        .surface(surface)
                        .minImageCount(configuration.imageCount())
                        .imageFormat(configuration.format())
                        .imageColorSpace(configuration.colorSpace())
                        .imageArrayLayers(1)
                        .imageUsage(configuration.imageUsage())
                        .preTransform(configuration.preTransform())
                        .compositeAlpha(configuration.compositeAlpha())
                        .presentMode(configuration.presentMode())
                        .clipped(true)
                        .oldSwapchain(oldSwapchain);

        createInfo.imageExtent()
                .width(configuration.width())
                .height(configuration.height());

        if (queues.sharedFamily()) {
            createInfo.imageSharingMode(
                    VK_SHARING_MODE_EXCLUSIVE
            );
        } else {
            IntBuffer queueFamilyIndices = stack.ints(
                    queues.graphicsFamilyIndex(),
                    queues.presentFamilyIndex()
            );

            createInfo
                    .imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                    .pQueueFamilyIndices(queueFamilyIndices);
        }

        LongBuffer swapchainPointer = stack.mallocLong(1);

        int result = vkCreateSwapchainKHR(
                device,
                createInfo,
                null,
                swapchainPointer
        );

        report.addProperty("vkCreateSwapchainKHRResult", result);

        if (result != VK_SUCCESS) {
            throw failure(
                    "CREATE_PERSISTENT_SWAPCHAIN",
                    "vkCreateSwapchainKHR failed with VkResult " + result
            );
        }

        long swapchain = swapchainPointer.get(0);

        if (swapchain == NULL) {
            throw failure(
                    "CREATE_PERSISTENT_SWAPCHAIN",
                    "vkCreateSwapchainKHR returned NULL."
            );
        }

        IntBuffer imageCountBuffer = stack.ints(0);

        result = vkGetSwapchainImagesKHR(
                device,
                swapchain,
                imageCountBuffer,
                null
        );

        if (result != VK_SUCCESS) {
            vkDestroySwapchainKHR(device, swapchain, null);

            throw failure(
                    "GET_SWAPCHAIN_IMAGES",
                    "vkGetSwapchainImagesKHR(count) failed with VkResult " + result
            );
        }

        int imageCount = imageCountBuffer.get(0);

        LongBuffer imageBuffer =
                stack.mallocLong(imageCount);

        result = vkGetSwapchainImagesKHR(
                device,
                swapchain,
                imageCountBuffer,
                imageBuffer
        );

        if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
            vkDestroySwapchainKHR(device, swapchain, null);

            throw failure(
                    "GET_SWAPCHAIN_IMAGES",
                    "vkGetSwapchainImagesKHR(list) failed with VkResult " + result
            );
        }

        long[] images = new long[imageCount];
        long[] imageViews = new long[imageCount];
        long[] renderFinishedSemaphores = new long[imageCount];

        for (int index = 0; index < imageCount; index++) {
            images[index] = imageBuffer.get(index);
        }

        try {
            for (int index = 0; index < imageCount; index++) {
                imageViews[index] = createImageView(
                        device,
                        images[index],
                        configuration.format(),
                        stack
                );

                renderFinishedSemaphores[index] =
                        createSemaphore(
                                device,
                                stack
                        );
            }
        } catch (Throwable throwable) {
            destroyArraySemaphores(
                    device,
                    renderFinishedSemaphores
            );

            destroyArrayImageViews(
                    device,
                    imageViews
            );

            vkDestroySwapchainKHR(
                    device,
                    swapchain,
                    null
            );

            throw throwable;
        }

        report.addProperty("swapchainCreated", true);
        report.addProperty("swapchainImageCount", imageCount);
        report.addProperty(
                "swapchainImageViewsCreated",
                imageCount
        );
        report.addProperty(
                "presentSemaphoresCreated",
                imageCount
        );
        report.addProperty(
                "presentSemaphoreStrategy",
                "PER_SWAPCHAIN_IMAGE"
        );

        return new VulkanSwapchainResources(
                device,
                report,
                swapchain,
                images,
                imageViews,
                renderFinishedSemaphores
        );
    }

    long swapchain() {
        return swapchain;
    }

    int imageCount() {
        return images.length;
    }

    long image(int index) {
        return images[index];
    }

    long imageView(int index) {
        return imageViews[index];
    }

    long renderFinishedSemaphore(int index) {
        return renderFinishedSemaphores[index];
    }

    int oldLayoutFor(int index) {
        return firstUse[index]
                ? VK_IMAGE_LAYOUT_UNDEFINED
                : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    }

    void markPresented(int index) {
        firstUse[index] = false;
    }

    private static long createImageView(
            VkDevice device,
            long image,
            int format,
            MemoryStack stack
    ) {
        VkImageViewCreateInfo createInfo =
                VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(image)
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(format);

        createInfo
                .subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);

        LongBuffer pointer = stack.mallocLong(1);

        int result = vkCreateImageView(
                device,
                createInfo,
                null,
                pointer
        );

        if (result != VK_SUCCESS) {
            throw failure(
                    "CREATE_SWAPCHAIN_IMAGE_VIEWS",
                    "vkCreateImageView failed with VkResult " + result
            );
        }

        return pointer.get(0);
    }

    private static long createSemaphore(
            VkDevice device,
            MemoryStack stack
    ) {
        VkSemaphoreCreateInfo createInfo =
                VkSemaphoreCreateInfo.calloc(stack)
                        .sType$Default();

        LongBuffer pointer = stack.mallocLong(1);

        int result = vkCreateSemaphore(
                device,
                createInfo,
                null,
                pointer
        );

        if (result != VK_SUCCESS) {
            throw failure(
                    "CREATE_PRESENT_SEMAPHORES",
                    "vkCreateSemaphore failed with VkResult " + result
            );
        }

        return pointer.get(0);
    }

    @Override
    public void close() {
        if (renderFinishedSemaphores != null) {
            destroyArraySemaphores(
                    device,
                    renderFinishedSemaphores
            );
            renderFinishedSemaphores = null;
            report.addProperty(
                    "presentSemaphoresDestroyed",
                    true
            );
        }

        if (imageViews != null) {
            destroyArrayImageViews(
                    device,
                    imageViews
            );
            imageViews = null;
            report.addProperty(
                    "swapchainImageViewsDestroyed",
                    true
            );
        }

        if (swapchain != NULL) {
            vkDestroySwapchainKHR(
                    device,
                    swapchain,
                    null
            );
            swapchain = NULL;
            report.addProperty(
                    "swapchainDestroyed",
                    true
            );
        }

        images = null;
        firstUse = null;
    }

    private static void destroyArraySemaphores(
            VkDevice device,
            long[] semaphores
    ) {
        for (long semaphore : semaphores) {
            if (semaphore != NULL) {
                vkDestroySemaphore(
                        device,
                        semaphore,
                        null
                );
            }
        }
    }

    private static void destroyArrayImageViews(
            VkDevice device,
            long[] imageViews
    ) {
        for (long imageView : imageViews) {
            if (imageView != NULL) {
                vkDestroyImageView(
                        device,
                        imageView,
                        null
                );
            }
        }
    }

    private static VulkanProbeException failure(
            String stage,
            String message
    ) {
        return new VulkanProbeException(stage, message);
    }
}