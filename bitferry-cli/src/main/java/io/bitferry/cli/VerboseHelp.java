package io.bitferry.cli;

import java.lang.reflect.*;
import java.util.*;
import picocli.CommandLine;
import picocli.CommandLine.Help;
import picocli.CommandLine.Help.ColorScheme;
import picocli.CommandLine.Help.IOptionRenderer;
import picocli.CommandLine.IHelpFactory;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/// Custom help factory and renderer that expands profile contents in option descriptions
/// only when verbose mode (-v, --verbose) is specified. The help layout adapts to the
/// terminal window width through picocli's own auto-width machinery (enabled here).
public final class VerboseHelp implements IHelpFactory {

  public static final VerboseHelp INSTANCE = new VerboseHelp();

  private VerboseHelp() {}

  @Override
  public Help create(CommandSpec commandSpec, ColorScheme colorScheme) {
    commandSpec.usageMessage().autoWidth(true);
    return new CustomHelp(commandSpec, colorScheme, isVerbose(commandSpec));
  }

  /// Check whether verbose mode was requested on any command in the parse result hierarchy.
  public static boolean isVerbose(CommandSpec commandSpec) {
    if (commandSpec == null || commandSpec.commandLine() == null) {
      return false;
    }
    CommandLine root = commandSpec.commandLine();
    while (root.getParent() != null) {
      root = root.getParent();
    }
    CommandLine.ParseResult pr = root.getParseResult();
    if (pr == null) {
      return false;
    }
    for (CommandLine cl : pr.asCommandLineList()) {
      CommandLine.ParseResult subPr = cl.getParseResult();
      if (subPr != null && (subPr.hasMatchedOption("-v") || subPr.hasMatchedOption("--verbose"))) {
        return true;
      }
    }
    return false;
  }

  /// Resolve extra profile description lines for the given option.
  public static List<String> profileLines(CommandSpec commandSpec, OptionSpec opt) {
    if (opt == null) return List.of();
    Profiles annotation = findProfilesAnnotation(commandSpec, opt);
    if (annotation == null) return List.of();
    Map<String, ?> map = resolveProfilesMap(annotation);
    if (map == null || map.isEmpty()) return List.of();

    List<String> keys;
    if (annotation.names().length > 0) {
      keys = Arrays.asList(annotation.names());
    } else {
      keys = map.keySet().stream()
        .filter(Objects::nonNull)
        .sorted((a, b) -> "default".equals(a) ? -1 : "default".equals(b) ? 1 : a.compareTo(b))
        .toList();
    }

    int maxPrefixLen = keys.stream()
      .mapToInt(k -> ("@" + k + ":").length())
      .max()
      .orElse(0);

    List<String> extra = new ArrayList<>();
    for (String key : keys) {
      Object raw = map.get(key);
      if (raw == null) continue;
      String prefix = "@" + key + ":";
      String padded = String.format("%-" + maxPrefixLen + "s", prefix);
      if (raw instanceof List<?> list) {
        if (list.isEmpty()) {
          extra.add("  @|bold,cyan " + prefix + "|@");
        } else {
          String text = list.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(" "));
          extra.add("  @|bold,cyan " + padded + "|@ " + text);
        }
      } else if (raw instanceof io.bitferry.task.Cadence c) {
        extra.add("  @|bold,cyan " + padded + "|@ " + c.format());
      } else {
        extra.add("  @|bold,cyan " + padded + "|@ " + raw);
      }
    }
    return extra;
  }

  /// Find the @Profiles annotation bound to the option's underlying field or method.
  public static Profiles findProfilesAnnotation(CommandSpec commandSpec, OptionSpec opt) {
    if (opt == null) return null;
    if (opt.userObject() instanceof AnnotatedElement element) {
      Profiles ann = element.getAnnotation(Profiles.class);
      if (ann != null) return ann;
    }
    if (commandSpec != null && commandSpec.userObject() != null) {
      List<String> optNames = Arrays.asList(opt.names());
      for (Class<?> cl = commandSpec.userObject().getClass(); cl != null && cl != Object.class; cl = cl.getSuperclass()) {
        for (Field f : cl.getDeclaredFields()) {
          Profiles ann = f.getAnnotation(Profiles.class);
          if (ann != null) {
            CommandLine.Option optAnn = f.getAnnotation(CommandLine.Option.class);
            if (optAnn != null && Arrays.stream(optAnn.names()).anyMatch(optNames::contains)) {
              return ann;
            }
          }
        }
      }
    }
    return null;
  }

  /// Retrieve the profiles map from the class and field specified in the @Profiles annotation.
  @SuppressWarnings("unchecked")
  public static Map<String, ?> resolveProfilesMap(Profiles annotation) {
    if (annotation == null) return null;
    Class<?> source = annotation.value();
    String fieldName = annotation.field();
    try {
      Field f = source.getField(fieldName);
      Object val = f.get(null);
      if (val instanceof Map<?, ?> map) {
        return (Map<String, ?>) map;
      }
    } catch (NoSuchFieldException e) {
      try {
        Method m = source.getMethod(fieldName);
        Object val = m.invoke(null);
        if (val instanceof Map<?, ?> map) {
          return (Map<String, ?>) map;
        }
      } catch (Exception ignored) {}
    } catch (Exception ignored) {}
    return null;
  }

  private static class CustomHelp extends Help {

    private final boolean verbose;

    public CustomHelp(CommandSpec commandSpec, ColorScheme colorScheme, boolean verbose) {
      super(commandSpec, colorScheme);
      this.verbose = verbose;
    }

    @Override
    public IOptionRenderer createDefaultOptionRenderer() {
      IOptionRenderer defaultRenderer = super.createDefaultOptionRenderer();
      return (opt, paramLabelRenderer, colorScheme) -> {
        if (verbose) {
          List<String> extra = profileLines(commandSpec(), opt);
          if (!extra.isEmpty()) {
            List<String> combined = new ArrayList<>();
            if (opt.description() != null) {
              combined.addAll(Arrays.asList(opt.description()));
            }
            combined.addAll(extra);
            opt = opt.toBuilder().description(combined.toArray(new String[0])).build();
          }
        }
        return defaultRenderer.render(opt, paramLabelRenderer, colorScheme);
      };
    }
  }
}
