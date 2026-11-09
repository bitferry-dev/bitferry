package bitferry.cli;

import bitferry.BackupEngines;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

@Command(
        name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Print JVM/module diagnostics, including the AOT cache and backup engine state.")
public final class DoctorCommand implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(DoctorCommand.class);

    @Override
    public Integer call() {
        System.out.printf("java.version:    %s%n", System.getProperty("java.version"));
        System.out.printf("java.vendor:     %s%n", System.getProperty("java.vendor"));
        System.out.printf("java.home:       %s%n", System.getProperty("java.home"));
        System.out.printf("main module:     %s%n", DoctorCommand.class.getModule().getName());
        System.out.printf("rclone:          %s%n", describe(BackupEngines.rclone()));
        System.out.printf("restic:          %s%n", describe(BackupEngines.restic()));
        System.out.printf("appimage:        %s%n", describeAppImage());

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

    private static String describe(Optional<BackupEngines.Resolved> engine) {
        return engine
                .map(r -> "%s (%s, %s)".formatted(r.path(), r.source(),
                        Files.exists(r.path()) ? "present" : "MISSING"))
                .orElse("not configured");
    }

    /** Describes the AppImage file this run executes from, per the AppRun launcher. */
    private static String describeAppImage() {
        String appImage = System.getenv("BITFERRY_APPIMAGE");
        if (appImage == null || appImage.isBlank()) {
            return "not an AppImage run";
        }
        return "%s (%s, %s)".formatted(appImage, "BITFERRY_APPIMAGE",
                Files.exists(Path.of(appImage)) ? "present" : "MISSING");
    }
}
