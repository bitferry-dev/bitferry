module bitferry.gui {
    requires bitferry;
    requires javafx.controls;
    requires java.logging;

    // javafx.graphics instantiates the Application subclass reflectively
    opens bitferry.gui to javafx.graphics;
}
