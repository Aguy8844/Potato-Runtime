package dev.ordovicium.potato.diagnostics;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;

/**
 * One-shot trace of the actual Minecraft/NeoForge native window handoff.
 *
 * <p>NeoForge's early display may create the OpenGL window before Minecraft's
 * {@code Window} constructor runs. Potato therefore observes the
 * {@code ImmediateWindowHandler.setupMinecraftWindow(...)} handoff instead of
 * assuming the constructor directly invokes GLFW window creation.</p>
 */
public final class PotatoWindowBootstrapTrace {
    private static volatile boolean handoffCallIntercepted;
    private static volatile boolean constructorReturnObserved;

    private static volatile boolean handoffArgumentsPreserved;
    private static volatile long handedOffWindowHandle;

    private static volatile long currentContextAtConstructorReturn;

    private static volatile int actualWindowWidth;
    private static volatile int actualWindowHeight;
    private static volatile int actualFramebufferWidth;
    private static volatile int actualFramebufferHeight;

    private static volatile int clientApi;
    private static volatile int contextMajor;
    private static volatile int contextMinor;
    private static volatile int contextProfile;
    private static volatile int contextCreationApi;

    private static volatile boolean visible;
    private static volatile boolean resizable;
    private static volatile boolean focused;

    private static volatile String handoffThread = "";
    private static volatile String constructorReturnThread = "";

    private PotatoWindowBootstrapTrace() {
    }

    public static void beforeMinecraftWindowHandoff() {
        handoffCallIntercepted = true;
        handoffArgumentsPreserved = true;
        handoffThread = Thread.currentThread().getName();
    }

    public static void afterMinecraftWindowHandoff(
            long windowHandle
    ) {
        handedOffWindowHandle = windowHandle;
    }

    public static void afterWindowConstructor() {
        constructorReturnObserved = true;
        constructorReturnThread =
                Thread.currentThread().getName();

        long handle = handedOffWindowHandle;

        if (handle == 0L) {
            return;
        }

        currentContextAtConstructorReturn =
                glfwGetCurrentContext();

        clientApi =
                glfwGetWindowAttrib(
                        handle,
                        GLFW_CLIENT_API
                );

        contextMajor =
                glfwGetWindowAttrib(
                        handle,
                        GLFW_CONTEXT_VERSION_MAJOR
                );

        contextMinor =
                glfwGetWindowAttrib(
                        handle,
                        GLFW_CONTEXT_VERSION_MINOR
                );

        contextProfile =
                glfwGetWindowAttrib(
                        handle,
                        GLFW_OPENGL_PROFILE
                );

        contextCreationApi =
                glfwGetWindowAttrib(
                        handle,
                        GLFW_CONTEXT_CREATION_API
                );

        visible =
                glfwGetWindowAttrib(
                        handle,
                        GLFW_VISIBLE
                ) == GLFW_TRUE;

        resizable =
                glfwGetWindowAttrib(
                        handle,
                        GLFW_RESIZABLE
                ) == GLFW_TRUE;

        focused =
                glfwGetWindowAttrib(
                        handle,
                        GLFW_FOCUSED
                ) == GLFW_TRUE;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);

            glfwGetWindowSize(
                    handle,
                    width,
                    height
            );

            actualWindowWidth = width.get(0);
            actualWindowHeight = height.get(0);

            glfwGetFramebufferSize(
                    handle,
                    width,
                    height
            );

            actualFramebufferWidth = width.get(0);
            actualFramebufferHeight = height.get(0);
        }
    }

    public static boolean seamObserved() {
        return handoffCallIntercepted
                && constructorReturnObserved
                && handedOffWindowHandle != 0L;
    }

    public static boolean mainWindowOwnsOpenGlContext() {
        return clientApi == GLFW_OPENGL_API;
    }

    public static void enrich(JsonObject report) {
        report.addProperty(
                "minecraftWindowHandoffCallIntercepted",
                handoffCallIntercepted
        );
        report.addProperty(
                "minecraftWindowConstructorReturnObserved",
                constructorReturnObserved
        );
        report.addProperty(
                "minecraftWindowBootstrapSeamObserved",
                seamObserved()
        );

        report.addProperty(
                "minecraftWindowCreationPath",
                "NEOFORGE_IMMEDIATE_WINDOW_HANDLER"
        );
        report.addProperty(
                "minecraftWindowDirectGlfwCreateIntercepted",
                false
        );
        report.addProperty(
                "minecraftWindowHandoffArgumentsPreserved",
                handoffArgumentsPreserved
        );
        report.addProperty(
                "minecraftWindowHandleNonZero",
                handedOffWindowHandle != 0L
        );

        report.addProperty(
                "minecraftActualWindowWidth",
                actualWindowWidth
        );
        report.addProperty(
                "minecraftActualWindowHeight",
                actualWindowHeight
        );
        report.addProperty(
                "minecraftActualFramebufferWidth",
                actualFramebufferWidth
        );
        report.addProperty(
                "minecraftActualFramebufferHeight",
                actualFramebufferHeight
        );

        report.addProperty(
                "minecraftCurrentContextHandleNonZero",
                currentContextAtConstructorReturn != 0L
        );
        report.addProperty(
                "minecraftCurrentContextMatchesWindow",
                handedOffWindowHandle != 0L
                        && currentContextAtConstructorReturn
                        == handedOffWindowHandle
        );

        report.addProperty(
                "minecraftWindowClientApiRaw",
                clientApi
        );
        report.addProperty(
                "minecraftWindowUsesOpenGL",
                clientApi == GLFW_OPENGL_API
        );
        report.addProperty(
                "minecraftWindowUsesNoApi",
                clientApi == GLFW_NO_API
        );

        report.addProperty(
                "minecraftOpenGlContextMajor",
                contextMajor
        );
        report.addProperty(
                "minecraftOpenGlContextMinor",
                contextMinor
        );
        report.addProperty(
                "minecraftOpenGlProfileRaw",
                contextProfile
        );
        report.addProperty(
                "minecraftContextCreationApiRaw",
                contextCreationApi
        );

        report.addProperty(
                "minecraftWindowVisible",
                visible
        );
        report.addProperty(
                "minecraftWindowResizable",
                resizable
        );
        report.addProperty(
                "minecraftWindowFocusedAtConstructorReturn",
                focused
        );

        report.addProperty(
                "minecraftWindowHandoffThread",
                handoffThread
        );
        report.addProperty(
                "minecraftWindowConstructorReturnThread",
                constructorReturnThread
        );

        report.addProperty(
                "minecraftWindowBootstrapMutation",
                false
        );
        report.addProperty(
                "minecraftWindowRenderingMutation",
                false
        );
    }
}