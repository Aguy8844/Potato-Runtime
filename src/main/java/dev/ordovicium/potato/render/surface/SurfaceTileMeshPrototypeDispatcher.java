package dev.ordovicium.potato.render.surface;

/**
 * Backend-neutral one-shot bridge for the first real Potato surface mesh
 * prototype.
 *
 * <p>The surface analyzer owns mesh construction. A renderer backend may
 * install exactly one sink. Once one snapshot is accepted, the dispatcher
 * permanently retires for the current process.</p>
 */
public final class SurfaceTileMeshPrototypeDispatcher {

    @FunctionalInterface
    public interface Sink {
        boolean submit(
                SurfaceTileMeshSnapshot snapshot
        );
    }

    private static volatile Sink sink;
    private static volatile boolean completed;

    private SurfaceTileMeshPrototypeDispatcher() {
    }

    public static void install(
            Sink newSink
    ) {
        if (newSink == null) {
            throw new IllegalArgumentException(
                    "Surface tile prototype sink must not be null."
            );
        }

        sink =
                newSink;
    }

    public static void uninstall() {
        sink =
                null;
    }

    public static boolean submit(
            SurfaceTileMeshSnapshot snapshot
    ) {
        if (completed) {
            return true;
        }

        Sink current =
                sink;

        if (current == null) {
            return false;
        }

        boolean accepted =
                current.submit(
                        snapshot
                );

        if (accepted) {
            completed =
                    true;
        }

        return accepted;
    }

    public static boolean completed() {
        return completed;
    }
}
