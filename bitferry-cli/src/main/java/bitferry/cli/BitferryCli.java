package bitferry.cli;

import bitferry.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(
        name = "bitferry-cli",
        version = "bitferry-cli " + Version.VERSION,
        mixinStandardHelpOptions = true,
        description = "Command-line frontend for the bitferry ferry service.",
        subcommands = {RunCommand.class, DoctorCommand.class, CommandLine.HelpCommand.class})
public final class BitferryCli implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(BitferryCli.class);

    @Override
    public void run() {
        log.info("bitferry-cli {} ready — run 'bitferry-cli run --help' to dispatch cargo", Version.get());
    }

    public static void main(String[] args) {
        log.debug("Starting bitferry-cli, arguments: {}", (Object) args);
        int exitCode = new CommandLine(new BitferryCli()).execute(args);
        System.exit(exitCode);
    }
}
