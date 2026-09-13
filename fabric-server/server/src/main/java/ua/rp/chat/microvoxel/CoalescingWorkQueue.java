package ua.rp.chat.microvoxel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe queue that keeps at most one scheduled lease per key.
 *
 * <p>The lease remains active after {@link #poll()}, allowing a bounded
 * consumer to {@link #requeue(Object)} unfinished work without accepting a
 * duplicate producer request. The consumer releases it with
 * {@link #complete(Object)} after all work for that key is finished.
 */
final class CoalescingWorkQueue<K> {
    private final ConcurrentLinkedQueue<K> queue = new ConcurrentLinkedQueue<>();
    private final Set<K> scheduled = ConcurrentHashMap.newKeySet();

    boolean schedule(K key) {
        if (!scheduled.add(key)) return false;
        queue.add(key);
        return true;
    }

    K poll() {
        return queue.poll();
    }

    void requeue(K key) {
        if (!scheduled.contains(key)) {
            throw new IllegalStateException("Cannot requeue completed work: " + key);
        }
        queue.add(key);
    }

    void complete(K key) {
        scheduled.remove(key);
    }

    int scheduledCount() {
        return scheduled.size();
    }
}
