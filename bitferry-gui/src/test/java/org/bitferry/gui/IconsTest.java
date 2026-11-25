package org.bitferry.gui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class IconsTest {

    private static final CountDownLatch FX_READY = new CountDownLatch(1);

    @BeforeAll
    static void startFx() throws Exception {
        try {
            Platform.startup(FX_READY::countDown);
            if (!FX_READY.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the FX toolkit did not start");
        } catch (IllegalStateException ignored) {
            // Already started by another test class in this JVM
        }
    }

    @AfterAll
    static void stopFx() {}

    @Test
    void allSymbolsHaveValidPathsAndInstantiateSvgNodes() {
        for (Icons.Symbol symbol : Icons.Symbol.values()) {
            assertNotNull(symbol.path(), "symbol path should not be null: " + symbol);
            assertFalse(symbol.path().isBlank(), "symbol path should not be blank: " + symbol);

            SVGPath svg = Icons.icon(symbol);
            assertNotNull(svg);
            assertEquals(symbol.path(), svg.getContent());
        }
    }

    @Test
    void equipBindsGraphicAndFillToButton() {
        Button button = new Button("Test");
        button.setTextFill(Color.web("#2e7d32"));

        SVGPath svg = Icons.equip(button, Icons.Symbol.PLAY);
        assertNotNull(svg);
        assertSame(svg, button.getGraphic());
        assertEquals(6.0, button.getGraphicTextGap());
        assertEquals(Color.web("#2e7d32"), svg.getFill());

        // Dynamic change of button textFill reflects in SVG fill
        button.setTextFill(Color.web("#c62828"));
        assertEquals(Color.web("#c62828"), svg.getFill());
    }

    @Test
    void equipWithNullButtonReturnsNull() {
        assertNull(Icons.equip(null, Icons.Symbol.PLAY));
    }

    @Test
    void controllersHaveButtonsEquippedWithIcons() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();

        Platform.runLater(() -> {
            try {
                // 1. main.fxml (Toolbar)
                javafx.fxml.FXMLLoader mainLoader = new javafx.fxml.FXMLLoader(getClass().getResource("/org/bitferry/gui/main.fxml"));
                mainLoader.load();
                MainController mainCtrl = mainLoader.getController();
                assertButtonGraphic(mainCtrl, "executeAll", Icons.Symbol.PLAY);
                assertButtonGraphic(mainCtrl, "abort", Icons.Symbol.STOP);
                assertButtonGraphic(mainCtrl, "reload", Icons.Symbol.REFRESH);
                assertButtonGraphic(mainCtrl, "exit", Icons.Symbol.EXIT);

                // 2. tasks.fxml (Tasks sidebar)
                javafx.fxml.FXMLLoader tasksLoader = new javafx.fxml.FXMLLoader(getClass().getResource("/org/bitferry/gui/tasks.fxml"));
                tasksLoader.load();
                TasksController tasksCtrl = tasksLoader.getController();
                assertButtonGraphic(tasksCtrl, "create", Icons.Symbol.PLUS);
                assertButtonGraphic(tasksCtrl, "modify", Icons.Symbol.EDIT);
                assertButtonGraphic(tasksCtrl, "massAll", Icons.Symbol.ALL_ON);
                assertButtonGraphic(tasksCtrl, "massNone", Icons.Symbol.ALL_OFF);
                assertButtonGraphic(tasksCtrl, "massInvert", Icons.Symbol.INVERT);
                assertButtonGraphic(tasksCtrl, "execute", Icons.Symbol.PLAY);
                assertButtonGraphic(tasksCtrl, "serve", Icons.Symbol.SERVE);
                assertButtonGraphic(tasksCtrl, "repair", Icons.Symbol.WRENCH);
                assertButtonGraphic(tasksCtrl, "delete", Icons.Symbol.DELETE);

                // 3. volumes.fxml (Volumes sidebar)
                javafx.fxml.FXMLLoader volumesLoader = new javafx.fxml.FXMLLoader(getClass().getResource("/org/bitferry/gui/volumes.fxml"));
                volumesLoader.load();
                VolumesController volumesCtrl = volumesLoader.getController();
                assertButtonGraphic(volumesCtrl, "create", Icons.Symbol.DRIVE);
                assertButtonGraphic(volumesCtrl, "delete", Icons.Symbol.DELETE);

                // 4. items.fxml (Item list editor)
                javafx.fxml.FXMLLoader itemsLoader = new javafx.fxml.FXMLLoader(getClass().getResource("/org/bitferry/gui/items.fxml"));
                itemsLoader.load();
                ItemListController itemsCtrl = itemsLoader.getController();
                assertButtonGraphic(itemsCtrl, "loadBtn", Icons.Symbol.LOAD);
                assertButtonGraphic(itemsCtrl, "editBtn", Icons.Symbol.EDIT);
                assertButtonGraphic(itemsCtrl, "removeBtn", Icons.Symbol.DELETE);
                assertButtonGraphic(itemsCtrl, "upBtn", Icons.Symbol.CHEVRON_UP);
                assertButtonGraphic(itemsCtrl, "downBtn", Icons.Symbol.CHEVRON_DOWN);
                assertButtonGraphic(itemsCtrl, "browseBtn", Icons.Symbol.BROWSE);
                assertButtonGraphic(itemsCtrl, "addBtn", Icons.Symbol.PLUS);
                assertButtonGraphic(itemsCtrl, "applyBtn", Icons.Symbol.APPLY);

                // 5. task.fxml (Task creation/edit form)
                javafx.fxml.FXMLLoader taskLoader = new javafx.fxml.FXMLLoader(getClass().getResource("/org/bitferry/gui/task.fxml"));
                taskLoader.load();
                TaskFormController taskCtrl = taskLoader.getController();
                assertButtonGraphic(taskCtrl, "browseSourceSmart", Icons.Symbol.BROWSE);
                assertButtonGraphic(taskCtrl, "browseSourceLocal", Icons.Symbol.BROWSE);
                assertButtonGraphic(taskCtrl, "browseSourceVolume", Icons.Symbol.DRIVE);
                assertButtonGraphic(taskCtrl, "browseDestinationSmart", Icons.Symbol.BROWSE);
                assertButtonGraphic(taskCtrl, "browseDestinationLocal", Icons.Symbol.BROWSE);
                assertButtonGraphic(taskCtrl, "browseDestinationVolume", Icons.Symbol.DRIVE);
                assertButtonGraphic(taskCtrl, "editInclude", Icons.Symbol.EDIT);
                assertButtonGraphic(taskCtrl, "editExclude", Icons.Symbol.EDIT);
                assertButtonGraphic(taskCtrl, "editExecute", Icons.Symbol.EDIT);
                assertButtonGraphic(taskCtrl, "editSecurity", Icons.Symbol.EDIT);
                assertButtonGraphic(taskCtrl, "editServe", Icons.Symbol.EDIT);

                // 6. service.fxml (Unified repository service editor)
                javafx.fxml.FXMLLoader serviceLoader = new javafx.fxml.FXMLLoader(getClass().getResource("/org/bitferry/gui/service.fxml"));
                serviceLoader.load();
                ServiceDialogController serviceCtrl = serviceLoader.getController();
                assertButtonGraphic(serviceCtrl, "loadBtn", Icons.Symbol.LOAD);
                assertButtonGraphic(serviceCtrl, "editCheck", Icons.Symbol.EDIT);
                assertButtonGraphic(serviceCtrl, "editForget", Icons.Symbol.EDIT);
                assertButtonGraphic(serviceCtrl, "editPrune", Icons.Symbol.EDIT);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(10, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError("controller button icon check failed", failure.get());
    }

    private static void assertButtonGraphic(Object controller, String fieldName, Icons.Symbol expected) throws Exception {
        java.lang.reflect.Field field = controller.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        Button button = (Button) field.get(controller);
        assertNotNull(button, fieldName + " button should be injected");
        assertNotNull(button.getGraphic(), fieldName + " button should have a graphic");
        assertInstanceOf(SVGPath.class, button.getGraphic(), fieldName + " graphic should be SVGPath");
        SVGPath svg = (SVGPath) button.getGraphic();
        assertEquals(expected.path(), svg.getContent(), fieldName + " icon path mismatch");
    }
}
