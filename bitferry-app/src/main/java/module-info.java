/**
 * Aggregate module of the bitferry distribution: pulling in both frontends keeps the
 * jlink plugin's default "all application modules" image complete even though the
 * launch roots are {@code org.bitferry.gui} and {@code org.bitferry.cli}.
 */
module org.bitferry.app {
    requires transitive org.bitferry.gui;
    requires transitive org.bitferry.cli;
}
