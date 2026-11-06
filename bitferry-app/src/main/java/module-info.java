/**
 * Aggregate module of the bitferry distribution: pulling in both frontends keeps the
 * jlink plugin's default "all application modules" image complete even though the
 * launch roots are {@code bitferry.gui} and {@code bitferry.cli}.
 */
module bitferry.app {
    requires transitive bitferry.gui;
    requires transitive bitferry.cli;
}
