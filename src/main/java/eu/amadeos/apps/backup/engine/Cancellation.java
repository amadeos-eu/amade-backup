package eu.amadeos.apps.backup.engine;

import java.util.ArrayList;
import java.util.List;

/** Lets the user stop a running backup, restore or sync from another thread. */
public final class Cancellation {
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean cancelled;

    public static Cancellation none() {
        return new Cancellation();
    }

    public void cancel() {
        List<Runnable> run;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            run = List.copyOf(listeners);
        }
        run.forEach(Runnable::run);
    }

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Runs {@code action} on cancelling, or at once if that happened already. */
    public void onCancel(Runnable action) {
        synchronized (this) {
            if (!cancelled) {
                listeners.add(action);
                return;
            }
        }
        action.run();
    }

    public synchronized void remove(Runnable action) {
        listeners.remove(action);
    }

    public void throwIfCancelled() throws EngineException {
        if (isCancelled()) throw new EngineException(EngineException.Kind.CANCELLED, "Cancelled");
    }
}
