package bitferry.cli;

import bitferry.*;
import bitferry.endpoint.*;
import bitferry.task.*;
import java.util.*;
import java.util.concurrent.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.*;

/// Base command for creating Rclone tasks (copy, update, synchronize, equalize).
public abstract class RcloneTaskCommand implements Callable<Integer>, HasCommonOptions {

  // Explicit no-arg constructor: the command classes are instantiated
  // reflectively by picocli.
  protected RcloneTaskCommand() {}

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

  @Option(names = {"-X", "--execute"}, description = "Extra backend execution options (comma-separated) or '-' for none.")
  String execute;

  @Option(names = "--include", split = ",", description = "Include path specifications (comma-separated list).")
  List<String> include;

  @Option(names = "--exclude", split = ",", description = "Exclude path specifications (comma-separated list).")
  List<String> exclude;

  @Option(names = "-i", split = ",", description = "Include file extensions (comma-separated list).")
  List<String> includeExts;

  @Option(names = "-x", split = ",", description = "Exclude file extensions (comma-separated list).")
  List<String> excludeExts;

  @Option(names = "-e", description = "Encrypt files in destination using default profile.")
  boolean encryptDefault;

  @Option(names = "-d", description = "Decrypt source files using default profile.")
  boolean decryptDefault;

  @Option(names = {"-E", "--encrypt"}, description = "Encrypt files in destination using specified profile or options.")
  String encrypt;

  @Option(names = {"-D", "--decrypt"}, description = "Decrypt source files using specified profile or options.")
  String decrypt;

  @Option(names = "-u", description = "Apply extended (unicode) encryption profile options.")
  boolean extended;

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
    if (encryptRequested && decryptRequested) throw new BitferryError("invalid encryption options: the encryption and decryption are mutually exclusive");
    if (extended && !encryptRequested && !decryptRequested) throw new BitferryError("invalid encryption options: the extended profile modifier requires the encryption to be requested");
    String profile = encrypt != null ? encrypt : decrypt != null ? decrypt : extended ? "extended" : "default";
    CommonOptions opts = CliUtils.resolveOptions(spec, options);
    Manager m = CliUtils.restoreManager(opts);
    // The bare paths encompassed by the intact volumes are attached to them
    Endpoint src = Endpoints.parse(m, source);
    Endpoint dst = Endpoints.parse(m, destination);
    CliUtils.ensureUniqueTask(m, src, dst);
    List<String> inc = CliUtils.parsePatterns(include, includeExts);
    List<String> exc = CliUtils.parsePatterns(exclude, excludeExts);
    Task task = taskFactory().create(m, src, dst, execute, inc, exc);
    if (encryptRequested || decryptRequested) {
      if (!(task instanceof Rclone rclone)) throw new BitferryError("encryption is supported for the rclone tasks only");
      // The password is obtained after the endpoints are validated
      String password = CliUtils.obtainPassword();
      if (encryptRequested) {
        rclone.encrypt(password, null, Rclone.Encryption.options(profile));
      } else {
        rclone.decrypt(password, null, Rclone.Encryption.options(profile));
      }
    }
    if (m.volumes().values().stream().noneMatch(v -> task.refersVolume(v.tag()))) {
      throw new BitferryError("task #" + task.tag() + " does not belong to any registered volume; it cannot be persisted");
    }
    m.commit();
    return 0;
  }
}
