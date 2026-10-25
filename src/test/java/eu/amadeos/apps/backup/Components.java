package eu.amadeos.apps.backup;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.util.function.BooleanSupplier;

/** Finding components by name and waiting for background work, for the window tests. */
public final class Components {
    private Components() {
    }

    @SuppressWarnings("unchecked")
    public static <T extends Component> T find(Container root, String name, Class<T> type) {
        T found = search(root, name, type);
        if (found == null) throw new AssertionError("No " + type.getSimpleName() + " named " + name);
        return found;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T search(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (name.equals(c.getName()) && type.isInstance(c)) return (T) c;
            if (c instanceof Container child) {
                T found = search(child, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Runs on the event dispatch thread and rethrows its failures here. */
    public static void onEdt(Runnable test) throws Exception {
        try {
            SwingUtilities.invokeAndWait(test);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error error) throw error;
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }

    /** Waits until the condition, checked on the event dispatch thread, holds; at most 20 seconds. */
    public static void await(BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        boolean[] ok = {false};
        while (System.currentTimeMillis() < end) {
            onEdt(() -> ok[0] = condition.getAsBoolean());
            if (ok[0]) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting");
    }
}
