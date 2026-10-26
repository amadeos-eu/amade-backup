package eu.amadeos.apps.backup.engine;

/** A backup engine could not do what was asked; {@link #kind()} says why, the message has the details. */
public class EngineException extends Exception {
    public enum Kind {
        /** The engine's program is not installed. */
        PROGRAM_MISSING,
        /** There is no repository at the place. */
        NO_REPOSITORY,
        /** The password does not open the repository. */
        WRONG_PASSWORD,
        /** Another program works on the repository right now. */
        LOCKED,
        /** Stopped by the user. */
        CANCELLED,
        /** Anything else, e.g. a full disk. */
        FAILED;

        public String key() {
            return name().toLowerCase().replace('_', '-');
        }
    }

    private final Kind kind;

    public EngineException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public EngineException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
