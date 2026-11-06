module bitferry.cli {
    requires bitferry;
    requires info.picocli;
    requires org.slf4j;
    requires ch.qos.logback.classic;
    requires java.management;

    // picocli reflectively accesses @Command/@Option members of this module
    opens bitferry.cli;
}
