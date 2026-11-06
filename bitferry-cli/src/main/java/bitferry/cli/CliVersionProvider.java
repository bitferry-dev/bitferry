package bitferry.cli;

import bitferry.Version;
import picocli.CommandLine;

/** Supplies the --version output from the version the Gradle build baked into bitferry. */
public final class CliVersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
        return new String[]{"bitferry " + Version.get()};
    }
}
