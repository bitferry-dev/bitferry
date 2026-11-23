module org.bitferry.gui {
    requires org.bitferry;
    requires javafx.controls;
    // The FXML machinery loading the UI resources and the controllers
    requires javafx.fxml;
    requires java.logging;
    // The window status persistence (the per-user platform preferences store)
    requires java.prefs;

    // javafx.graphics instantiates the Application subclass reflectively;
    // javafx.fxml loads the controllers and injects their @FXML members
    opens org.bitferry.gui to javafx.graphics, javafx.fxml;
}
