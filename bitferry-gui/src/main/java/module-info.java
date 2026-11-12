module bitferry.gui {
    requires bitferry;
    requires javafx.controls;
    // The FXML machinery loading the UI resources and the controllers
    requires javafx.fxml;
    requires java.logging;

    // javafx.graphics instantiates the Application subclass reflectively;
    // javafx.fxml loads the controllers and injects their @FXML members
    opens bitferry.gui to javafx.graphics, javafx.fxml;
}
