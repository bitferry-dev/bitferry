package bitferry.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Print JVM/module diagnostics, including the state of the AOT cache.")
public final class DoctorCommand implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(DoctorCommand.class);

    @Override
    public Integer call() {
        System.out.printf("java.version:    %s%n", System.getProperty("java.version"));
        System.out.printf("java.vendor:     %s%n", System.getProperty("java.vendor"));
        System.out.printf("java.home:       %s%n", System.getProperty("java.home"));
        System.out.printf("main module:     %s%n", DoctorCommand.class.getModule().getName());

        List<String> inputArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        System.out.println("JVM input arguments:");
        inputArgs.forEach(arg -> System.out.printf("  %s%n", arg));

        inputArgs.stream()
                .filter(arg -> arg.startsWith("-XX:AOTCache="))
                .map(arg -> arg.substring("-XX:AOTCache=".length()))
                .findFirst()
                .ifPresentOrElse(
                        cache -> System.out.printf("AOT cache:       %s (%s)%n", cache,
                                Files.exists(Path.of(cache)) ? "present" : "MISSING"),
                        () -> System.out.println("AOT cache:       not configured"));
        log.info("doctor complete");
        return 0;
    }
}
