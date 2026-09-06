package ua.rp.chat.microvoxel;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tiny publish-subscribe hub for authoritative microvoxel mutations. Future integrations
 * (region protection, build logging, quests, rollback tools) subscribe here instead of being
 * wired into the edit engine, keeping the hot mutation path free of feature coupling.
 *
 * <p>Events fire on the server thread after the store and projection already converged.
 * Volumes passed to listeners are live store references: copy them if they must outlive the
 * call. A throwing listener never breaks the edit; the failure is logged and the remaining
 * listeners still run. A {@code null} {@code before} means creation, a {@code null}
 * {@code after} means full removal.</p>
 */
public final class MicrovoxelEvents {
    /** Receives one authoritative volume transition. */
    @FunctionalInterface
    public interface EditListener {
        void onEdit(ServerPlayer player, MicrovoxelKey key,
                    MicrovoxelVolume before, MicrovoxelVolume after);
    }

    private static final CopyOnWriteArrayList<EditListener> EDIT_LISTENERS = new CopyOnWriteArrayList<>();
    private static final Logger FALLBACK_LOG = Logger.getLogger("MicrovoxelEvents");

    private MicrovoxelEvents() {
    }

    /** Subscribes to authoritative edits. Returns a handle suitable for {@link #unsubscribe}. */
    public static EditListener subscribe(EditListener listener) {
        if (listener != null) EDIT_LISTENERS.add(listener);
        return listener;
    }

    public static void unsubscribe(EditListener listener) {
        EDIT_LISTENERS.remove(listener);
    }

    /** Publishes one transition to every subscriber, isolating listener failures. */
    public static void fireEdit(ServerPlayer player, MicrovoxelKey key,
                                MicrovoxelVolume before, MicrovoxelVolume after) {
        if (EDIT_LISTENERS.isEmpty()) return;
        for (EditListener listener : EDIT_LISTENERS) {
            try {
                listener.onEdit(player, key, before, after);
            } catch (RuntimeException failure) {
                FALLBACK_LOG.log(Level.WARNING,
                        "Microvoxel edit listener failed for " + key.x() + "," + key.y() + "," + key.z(),
                        failure);
            }
        }
    }

    /** Test-only introspection: how many listeners are currently subscribed. */
    static int listenerCount() {
        return EDIT_LISTENERS.size();
    }

    /** Removes every listener. Only ever called from tests. */
    static void clearForTests() {
        EDIT_LISTENERS.clear();
    }
}
