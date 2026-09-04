package dev.ordovicium.potato.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;

/**
 * Scoped compatibility shim for LWJGL 3.3.3's Vulkan instance capability scan.
 *
 * <p>LWJGL's {@code VkInstance} constructor enumerates every device extension
 * into the thread-local {@link MemoryStack}. Modern drivers can expose enough
 * extensions to exceed LWJGL's default 64 KiB stack. Potato only replaces the
 * current thread's MemoryStack while that constructor runs and restores the
 * exact previous stack immediately afterwards.</p>
 */
final class LwjglVulkanMemoryStackScope implements AutoCloseable {
    private final ThreadLocal<MemoryStack> threadLocal;
    private final MemoryStack previous;
    private final ByteBuffer backing;
    private final MemoryStack expanded;
    private boolean closed;

    private LwjglVulkanMemoryStackScope(
            ThreadLocal<MemoryStack> threadLocal,
            MemoryStack previous,
            ByteBuffer backing,
            MemoryStack expanded
    ) {
        this.threadLocal = threadLocal;
        this.previous = previous;
        this.backing = backing;
        this.expanded = expanded;
    }

    static LwjglVulkanMemoryStackScope open(
            int minimumBytes
    ) {
        ThreadLocal<MemoryStack> threadLocal =
                memoryStackThreadLocal();

        MemoryStack previous =
                threadLocal.get();

        int expandedBytes =
                Math.max(
                        previous.getSize(),
                        minimumBytes
                );

        ByteBuffer backing =
                MemoryUtil.memAlloc(expandedBytes);
        MemoryStack expanded =
                MemoryStack.create(backing);

        threadLocal.set(expanded);

        if (threadLocal.get() != expanded) {
            MemoryUtil.memFree(backing);
            throw new VulkanProbeException(
                    "WRAP_VK_INSTANCE_STACK_SCOPE",
                    "Failed to install expanded LWJGL MemoryStack"
            );
        }

        return new LwjglVulkanMemoryStackScope(
                threadLocal,
                previous,
                backing,
                expanded
        );
    }

    int previousSizeBytes() {
        return previous.getSize();
    }

    int expandedSizeBytes() {
        return expanded.getSize();
    }

    boolean isInstalled() {
        return !closed
                && threadLocal.get() == expanded;
    }

    boolean isRestored() {
        return closed
                && threadLocal.get() == previous;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        threadLocal.set(previous);
        closed = true;
        MemoryUtil.memFree(backing);
    }

    @SuppressWarnings({
            "unchecked",
            "removal"
    })
    private static ThreadLocal<MemoryStack>
    memoryStackThreadLocal() {
        try {
            Field tlsField =
                    MemoryStack.class.getDeclaredField(
                            "TLS"
                    );

            Field unsafeField =
                    Unsafe.class.getDeclaredField(
                            "theUnsafe"
                    );
            unsafeField.setAccessible(true);

            Unsafe unsafe =
                    (Unsafe) unsafeField.get(null);

            Object staticBase =
                    unsafe.staticFieldBase(tlsField);
            long staticOffset =
                    unsafe.staticFieldOffset(tlsField);

            Object value =
                    unsafe.getObject(
                            staticBase,
                            staticOffset
                    );

            if (!(value instanceof ThreadLocal<?>)) {
                throw new IllegalStateException(
                        "LWJGL MemoryStack TLS has unexpected type"
                );
            }

            return (ThreadLocal<MemoryStack>) value;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new VulkanProbeException(
                    "WRAP_VK_INSTANCE_STACK_SCOPE",
                    "Unable to access LWJGL MemoryStack TLS: "
                            + exception
            );
        }
    }
}
