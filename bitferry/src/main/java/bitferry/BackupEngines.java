package bitferry;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates the backup engines — rclone and restic — that the frontends drive.
 *
 * <p>Packaged deployments resolve the bundled executables first: every launcher (jpackage
 * app-image and jlink start scripts alike) passes their absolute locations as the
 * {@code bitferry.rclone} / {@code bitferry.restic} system properties, and those take
 * priority. Plain {@code java -m} runs against a jlink tree set no java options and fall
 * back to the {@code RCLONE} / {@code RESTIC} environment variables.
 */
public final class BackupEngines {
    /** System property carrying the location of the bundled rclone executable. */
    public static final String RCLONE_PROPERTY = "bitferry.rclone";

    /** Environment variable carrying an rclone executable location. */
    public static final String RCLONE_VARIABLE = "RCLONE";

    /** System property carrying the location of the bundled restic executable. */
    public static final String RESTIC_PROPERTY = "bitferry.restic";

    /** Environment variable carrying a restic executable location. */
    public static final String RESTIC_VARIABLE = "RESTIC";

    private BackupEngines() {
    }

    /** Locates rclone: the launcher-provided system property first, then {@code RCLONE}. */
    public static Optional<Resolved> rclone() {
        return resolve(RCLONE_PROPERTY, RCLONE_VARIABLE);
    }

    /** Locates restic: the launcher-provided system property first, then {@code RESTIC}. */
    public static Optional<Resolved> restic() {
        return resolve(RESTIC_PROPERTY, RESTIC_VARIABLE);
    }

    private static Optional<Resolved> resolve(String propertyName, String variableName) {
        String property = System.getProperty(propertyName, "").trim();
        if (!property.isEmpty()) {
            return Optional.of(new Resolved(Path.of(property), "system property " + propertyName));
        }
        String variable = System.getenv(variableName);
        if (variable != null && !variable.isBlank()) {
            return Optional.of(new Resolved(Path.of(variable.trim()), "environment variable " + variableName));
        }
        return Optional.empty();
    }

    /** A located engine executable and where the location came from. */
    public record Resolved(Path path, String source) {
    }
}
