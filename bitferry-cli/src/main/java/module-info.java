/// The Bitferry command line interface frontend built upon picocli.
module org.bitferry.cli {
  // Transitive: the CLI API (CliUtils, the commands) exposes the Manager,
  // the Endpoint and the Verbosity types of the core module
  requires transitive org.bitferry;
  // Transitive: the CLI API exposes the picocli CommandLine machinery
  requires transitive info.picocli;
  // The jansi console: the Windows colors/unicode enabler for picocli
  requires org.fusesource.jansi;
  // The verbosity handling goes through the java.util.logging root logger
  requires java.logging;

  // The picocli machinery reflects over the command classes (annotations,
  // injections, the version provider) requiring the deep access
  exports org.bitferry.cli;
  opens org.bitferry.cli to info.picocli;
}
