module bitferry.gui {
    requires bitferry;
    requires javafx.controls;
    requires org.slf4j;
    requires ch.qos.logback.classic;

    // javafx.graphics instantiates the Application subclass reflectively
    opens bitferry.gui to javafx.graphics;
}
