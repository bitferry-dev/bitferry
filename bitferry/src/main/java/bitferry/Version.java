package bitferry;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Version of the bitferry library. The value is the Gradle project version, baked into
 * the module's resources at build time — a single source of truth for the whole tree.
 */
public final class Version {
    private static final String VERSION = load();

    private Version() {
    }

    public static String get() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = Version.class.getResourceAsStream("/bitferry/version.properties")) {
            Properties props = new Properties();
            if (in != null) {
                props.load(in);
            }
            String version = props.getProperty("version", "").trim();
            return version.isEmpty() ? "unknown" : version;
        } catch (IOException e) {
            return "unknown";
        }
    }
}
