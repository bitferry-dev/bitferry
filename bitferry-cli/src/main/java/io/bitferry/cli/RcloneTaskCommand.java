package io.bitferry.cli;

import io.bitferry.*;
import io.bitferry.endpoint.*;
import io.bitferry.task.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Model.*;

/// Base command for creating Rclone tasks (copy, update, replicate, equalize).
public abstract class RcloneTaskCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  protected RcloneTaskCommand() {}

  @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit.")
  boolean help;

  @FunctionalInterface
  public interface TaskFactory {
    Task create(Manager manager, Endpoint source, Endpoint destination, String execute, Collection<String> include, Collection<String> exclude);
  }

  @Mixin
  CommonOptions options = new CommonOptions();

  @Spec
  CommandSpec spec;

  @Parameters(index = "0", description = "Source endpoint.", paramLabel = "SOURCE")
  String source;

  @Parameters(index = "1", description = "Destination endpoint.", paramLabel = "DESTINATION")
  String destination;

  @Profiles(Rclone.class)
  @Option(names = {"-x", "--execute"}, description = "Extra backend execution options (comma-separated), profile @|bold,cyan (@default)|@ or @|bold,cyan -|@ for none.", paramLabel = "PROFILE|OPTS")
  String execute;

  @Option(names = "--include", split = ",", description = "Include path specifications (comma-separated list).", paramLabel = "PATTERN")
  List<String> include;

  @Option(names = "--exclude", split = ",", description = "Exclude path specifications (comma-separated list).", paramLabel = "PATTERN")
  List<String> exclude;

  @Option(names = "--include-ext", split = ",", description = "Include file extensions (comma-separated list).", paramLabel = "EXT")
  List<String> includeExts;

  @Option(names = "--exclude-ext", split = ",", description = "Exclude file extensions (comma-separated list).", paramLabel = "EXT")
  List<String> excludeExts;

  @Option(names = "-e", description = "Encrypt files in destination using @|bold,cyan @default|@ profile.")
  boolean encryptDefault;

  @Option(names = "-d", description = "Decrypt source files using @|bold,cyan @default|@ profile.")
  boolean decryptDefault;

  @Profiles(value = Rclone.class, field = "PROFILE")
  @Option(names = "--encrypt", description = "Encrypt files in destination using specified profile @|bold,cyan (@default, @extended)|@ or options.", paramLabel = "PROFILE|OPTS")
  String encrypt;

  @Profiles(value = Rclone.class, field = "PROFILE")
  @Option(names = "--decrypt", description = "Decrypt source files using specified profile @|bold,cyan (@default, @extended)|@ or options.", paramLabel = "PROFILE|OPTS")
  String decrypt;

  @Option(names = "-u", description = "Augment the encryption options (-e, -d) with the @|bold,cyan @extended|@ profile (unicode filename encoding).")
  boolean extended;

  @Option(names = "--vault", description = "Vault provider for sensitive data: @|bold,cyan bitferry|@ (volume storage) or @|bold,cyan keyring|@ (OS keyring).", paramLabel = "PROVIDER")
  String vault;

  @Option(names = "-z", description = "Shorthand for --vault bitferry (store sensitive data in volume vault).")
  boolean bitferryVault;

  @Option(names = "-k", description = "Shorthand for --vault keyring (intended for fixed volumes; honored on the removable ones with a portability advisory).")
  boolean keyring;

  @Option(names = {"-s", "--salt"}, description = "Prompt for a secondary password / salt (required when reusing an existing rclone crypt remote created with a custom salt).")
  boolean salt;

  @Option(names = "-m", description = "Shorthand for --execution manual (exclude the task from the mass runs; run it by its tag only).")
  boolean manualExecution;

  @Option(names = "--execution", description = "Mass-run participation: @|bold,cyan manual|@ (run it by its tag only) or @|bold,cyan automatic|@ (include in the mass runs; the default).", paramLabel = "MODE")
  String execution;

  @Override
  public CommonOptions commonOptions() {
    return options;
  }

  protected abstract TaskFactory taskFactory();

  @Override
  public Integer call() {
    // The encryption is configured on the single side only; the profile options
    // are resolved against the crypt filename encoding profiles
    boolean encryptRequested = encryptDefault || encrypt != null;
    boolean decryptRequested = decryptDefault || decrypt != null;
    // The shorthand and the explicit forms of the same setting are mutually exclusive
    if (encryptDefault && encrypt != null) throw new BitferryError("Destination encryption specified twice: use either -e or --encrypt");
    if (decryptDefault && decrypt != null) throw new BitferryError("Source decryption specified twice: use either -d or --decrypt");
    if (encryptRequested && decryptRequested) throw new BitferryError("Invalid encryption options: the encryption and decryption are mutually exclusive");
    // The explicit profile selection leaves nothing for the unicode modifier to resolve
    if (extended && (encrypt != null || decrypt != null)) throw new BitferryError("Invalid encryption options: the extended profile modifier (-u) conflicts with the explicitly specified encryption profile");
    if (extended && !encryptRequested && !decryptRequested) throw new BitferryError("Invalid encryption options: the extended profile modifier requires the encryption to be requested");
    String profile = encrypt != null ? encrypt : decrypt != null ? decrypt : extended ? "@extended" : "@default";
    // The mass-run participation is validated upfront (the bogus modes fail before any probing):
    // the shorthand and the explicit forms of the same setting are mutually exclusive
    if (manualExecution && execution != null) throw new BitferryError("Execution mode specified twice: use either -m or --execution");
    Boolean manual = manualExecution ? Boolean.TRUE : CliUtils.resolveExecution(execution);
    boolean toKeyring = CliUtils.resolveKeyring(vault, bitferryVault, keyring, false);
    if (!encryptRequested && !decryptRequested && (vault != null || bitferryVault || keyring)) {
      throw new BitferryError("Vault storage option is applicable to encrypted tasks only");
    }
    if (!encryptRequested && !decryptRequested && salt) {
      throw new BitferryError("Salt option requires encryption to be requested (-e, -d, --encrypt, --decrypt)");
    }
    CommonOptions opts = CliUtils.resolveOptions(spec, options);
    Manager m = CliUtils.restoreManager(spec, opts);
    // The bare paths encompassed by the intact volumes are attached to them
    Endpoint src = Endpoints.parse(m, source);
    Endpoint dst = Endpoints.parse(m, destination);
    CliUtils.ensureUniqueTask(m, src, dst);
    List<String> inc = CliUtils.parsePatterns(include, includeExts);
    List<String> exc = CliUtils.parsePatterns(exclude, excludeExts);
    Task task = taskFactory().create(m, src, dst, execute, inc, exc);
    if (encryptRequested || decryptRequested) {
      if (!task.supportsEncryption()) throw new BitferryError("Encryption is supported for the Rclone tasks only");
      Rclone rclone = (Rclone) task;
      // The password is obtained after the endpoints are validated
      String password = CliUtils.obtainPassword();
      String password2 = salt ? CliUtils.obtainSalt() : null;
      if (encryptRequested) {
        rclone.encrypt(password, password2, Rclone.Encryption.options(profile), toKeyring);
      } else {
        rclone.decrypt(password, password2, Rclone.Encryption.options(profile), toKeyring);
      }
      CliUtils.noteKeyringStorage(spec, task, toKeyring);
    }
    if (Boolean.TRUE.equals(manual)) {
      task.setManual(true);
    }
    if (m.volumes().values().stream().noneMatch(v -> task.refersVolume(v.tag()))) {
      throw new BitferryError("Task " + task.tag() + " does not belong to any registered volume; it cannot be persisted");
    }
    m.commit(task);
    // The simulated creation writes nothing (the storages hold no such task): surface
    // it - the silent exit would read as a success (mirroring the backup advisory)
    if (m.simulate()) {
      spec.commandLine().getErr().println(Ansi.AUTO.string("@|faint the task creation simulated (nothing was written); re-run without --dry-run to create the task for real|@"));
    }
    return 0;
  }
}
