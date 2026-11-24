package org.bitferry.gui;

import org.bitferry.Bitferry;
import org.bitferry.Manager;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Tests for the simulation mode checkbox alongside verbosity, its persistence in
/// window settings, and its propagation through AppContext and Manager.
class SimulationModeTest {

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
  void appContextSimulationStateAndReloadPreservation() throws Exception {
    AppContext context = new AppContext();
    assertFalse(context.simulate());
    assertFalse(context.manager().simulate);

    context.simulate(true);
    assertTrue(context.simulate());
    assertTrue(context.manager().simulate);

    // Verify simulate mode survives manager reload
    CountDownLatch reloaded = new CountDownLatch(1);
    context.onModelChanged(reloaded::countDown);
    context.reload();
    assertTrue(reloaded.await(10, TimeUnit.SECONDS), "context reload timed out");
    assertTrue(context.simulate());
    assertTrue(context.manager().simulate);

    context.simulate(false);
    assertFalse(context.simulate());
    assertFalse(context.manager().simulate);
  }

  @Test
  void windowSettingsSimulationMode() throws Exception {
    WindowSettings settings = WindowSettings.load();
    boolean initial = settings.simulate();

    CountDownLatch stageReady = new CountDownLatch(1);
    AtomicReference<Stage> stageRef = new AtomicReference<>();
    Platform.runLater(() -> {
      stageRef.set(new Stage());
      stageReady.countDown();
    });
    assertTrue(stageReady.await(5, TimeUnit.SECONDS));
    Stage stage = stageRef.get();

    try {
      settings.capture(stage, 0.62, true, true);
      assertTrue(settings.simulate());
      assertTrue(settings.verbose());

      WindowSettings reloaded = WindowSettings.load();
      assertTrue(reloaded.simulate());
      assertTrue(reloaded.verbose());

      settings.capture(stage, 0.62, false, false);
      assertFalse(settings.simulate());
      assertFalse(settings.verbose());
    } finally {
      // Restore clean default state and release the stage (the leaked windows
      // accumulate across the suite otherwise)
      settings.capture(stage, 0.62, false, false);
      CountDownLatch closed = new CountDownLatch(1);
      Platform.runLater(() -> {
        stage.close();
        closed.countDown();
      });
      assertTrue(closed.await(5, TimeUnit.SECONDS), "the stage failed to close");
    }
  }

  @Test
  void mainControllerSimulateCheckboxWiringAndGating() throws Exception {
    CountDownLatch loaded = new CountDownLatch(1);
    AtomicReference<MainController> controllerRef = new AtomicReference<>();
    AtomicReference<CheckBox> simulateBoxRef = new AtomicReference<>();
    AtomicReference<CheckBox> verboseBoxRef = new AtomicReference<>();
    AtomicReference<Hyperlink> homeSiteRef = new AtomicReference<>();
    AtomicReference<AppContext> contextRef = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    Platform.runLater(() -> {
      try {
        AppContext context = new AppContext();
        contextRef.set(context);

        FXMLLoader loader = new FXMLLoader(Main.class.getResource("/org/bitferry/gui/main.fxml"));
        loader.load();
        MainController controller = loader.getController();
        controllerRef.set(controller);

        WindowSettings settings = WindowSettings.load();
        controller.init(context, settings);

        var simField = MainController.class.getDeclaredField("simulate");
        simField.setAccessible(true);
        CheckBox simulateBox = (CheckBox) simField.get(controller);
        simulateBoxRef.set(simulateBox);

        var verbField = MainController.class.getDeclaredField("verbose");
        verbField.setAccessible(true);
        CheckBox verboseBox = (CheckBox) verbField.get(controller);
        verboseBoxRef.set(verboseBox);

        var homeField = MainController.class.getDeclaredField("homeSite");
        homeField.setAccessible(true);
        homeSiteRef.set((Hyperlink) homeField.get(controller));
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        loaded.countDown();
      }
    });

    assertTrue(loaded.await(10, TimeUnit.SECONDS), "loading main.fxml timed out");
    if (failure.get() != null) throw new IllegalStateException("failed to load main.fxml", failure.get());

    MainController controller = controllerRef.get();
    CheckBox simulate = simulateBoxRef.get();
    CheckBox verbose = verboseBoxRef.get();
    Hyperlink homeSite = homeSiteRef.get();
    AppContext context = contextRef.get();

    assertNotNull(simulate, "simulate checkbox must be injected by FXML");
    assertNotNull(verbose, "verbose checkbox must be injected by FXML");
    assertNotNull(homeSite, "project home hyperlink must be injected by FXML");
    assertEquals(Bitferry.PROJECT_HOME, homeSite.getText());
    assertEquals("https://bitferry.org", homeSite.getText());
    assertEquals("Simulate", simulate.getText());

    // Verify simulate is placed right alongside verbose in the same toolbar HBox
    assertInstanceOf(HBox.class, verbose.getParent());
    assertSame(verbose.getParent(), simulate.getParent());
    HBox toolbar = (HBox) verbose.getParent();
    int verboseIdx = toolbar.getChildren().indexOf(verbose);
    int simulateIdx = toolbar.getChildren().indexOf(simulate);
    assertEquals(verboseIdx + 1, simulateIdx, "simulate checkbox must be placed right after verbose");

    // Test toggling simulate via fire()
    boolean initialSelected = simulate.isSelected();
    CountDownLatch toggled = new CountDownLatch(1);
    Platform.runLater(() -> {
      try {
        simulate.fire();
        toggled.countDown();
      } catch (Throwable t) {
        failure.set(t);
      }
    });
    assertTrue(toggled.await(5, TimeUnit.SECONDS));
    if (failure.get() != null) throw new IllegalStateException("toggling simulate failed", failure.get());

    assertEquals(!initialSelected, controller.simulateSelected());
    assertEquals(!initialSelected, context.simulate());
    assertEquals(!initialSelected, context.manager().simulate);

    // Test gating during active run (setActionsDisabled)
    CountDownLatch locked = new CountDownLatch(1);
    Platform.runLater(() -> {
      try {
        var disabledMethod = MainController.class.getDeclaredMethod("setActionsDisabled", boolean.class);
        disabledMethod.setAccessible(true);
        var menuField = MainController.class.getDeclaredField("moreMenu");
        menuField.setAccessible(true);
        var moreMenu = (MenuButton) menuField.get(controller);
        assertNotNull(moreMenu);
        assertEquals("⋮", moreMenu.getText());
        assertEquals(1, moreMenu.getItems().size());
        assertEquals("Volume search paths…", moreMenu.getItems().get(0).getText());

        disabledMethod.invoke(controller, true);
        assertTrue(simulate.isDisable(), "simulate checkbox must be disabled during active run");
        assertTrue(moreMenu.isDisable(), "moreMenu dropdown must be disabled during active run");

        disabledMethod.invoke(controller, false);
        assertFalse(simulate.isDisable(), "simulate checkbox must be re-enabled after run");
        assertFalse(moreMenu.isDisable(), "moreMenu dropdown must be re-enabled after run");
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        locked.countDown();
      }
    });
    assertTrue(locked.await(5, TimeUnit.SECONDS));
    if (failure.get() != null) throw new IllegalStateException("gating simulate failed", failure.get());
  }
}
