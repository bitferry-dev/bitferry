/**
 * Aggregate module of the bitferry distribution: pulling in both frontends keeps the
 * jlink plugin's default "all application modules" image complete even though the
 * launch roots are {@code io.bitferry.gui} and {@code io.bitferry.cli}.
 */
module io.bitferry.app {
    requires transitive io.bitferry.gui;
    requires transitive io.bitferry.cli;
}
