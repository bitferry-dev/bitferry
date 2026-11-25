package org.bitferry.gui;

import org.bitferry.*;
import javafx.application.*;
import javafx.scene.control.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.prefs.*;

import static org.junit.jupiter.api.Assertions.*;

class NewVolumeDialogTest {

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

  @BeforeEach
  void stubDiscovery() {
    // The volume-creation tests run at the temp dirs outside the scanning policy:
    // the discovery tri-choice fires in every one of them - create without touching
    // the search roots (the dedicated tests below intercept the tri-choice itself)
    Dialogs.discoveryPrompter = alert -> Optional.of(ButtonType.NO);
  }

  @AfterEach
  void resetPrompter() {
    Dialogs.dialogPrompter = javafx.scene.control.Dialog::showAndWait;
    Dialogs.discoveryPrompter = javafx.scene.control.Dialog::showAndWait;
  }

  private void runFx(Runnable action) throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.runLater(() -> {
      try {
        action.run();
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        latch.countDown();
      }
    });
    if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("FX action timed out");
    if (failure.get() != null) throw new RuntimeException(failure.get());
  }

  private void waitOperations(AppContext context) throws Exception {
    context.operations().submit(() -> null).get(5, TimeUnit.SECONDS);
  }

  @Test
  void overwriteUnregisteredStorage_confirmedWithYes() throws Exception {
    Path tempDir = Files.createTempDirectory("bitferry-overwrite-test-");
    Path storage = tempDir.resolve(Bitferry.STORAGE);
    Files.writeString(storage, "unregistered leftover storage");

    AppContext context = new AppContext();
    LogController logs = new LogController();

    AtomicReference<Alert> captured = new AtomicReference<>();
    Dialogs.dialogPrompter = alert -> {
      captured.set(alert);
      return Optional.of(ButtonType.YES);
    };

    runFx(() -> Dialogs.createVolume(context, logs, tempDir));
    waitOperations(context);

    assertNotNull(captured.get());
    assertEquals(Alert.AlertType.WARNING, captured.get().getAlertType());
    assertEquals("Overwrite Bitferry volume", captured.get().getTitle());
    assertTrue(captured.get().getButtonTypes().contains(ButtonType.YES));
    assertTrue(captured.get().getButtonTypes().contains(ButtonType.NO));
    assertTrue(captured.get().getContentText().contains("Overwrite existing"));

    String newContent = Files.readString(storage);
    assertNotEquals("unregistered leftover storage", newContent);
    assertTrue(newContent.contains("\"bitferry\""));

    Path canonical = Bitferry.canonicalize(tempDir);
    Volume created = context.manager().liveVolumes().values().stream()
      .filter(v -> v.root().equals(canonical))
      .findFirst().orElse(null);
    assertNotNull(created);
  }

  @Test
  void overwriteUnregisteredStorage_cancelledWithNo() throws Exception {
    Path tempDir = Files.createTempDirectory("bitferry-overwrite-cancel-test-");
    Path storage = tempDir.resolve(Bitferry.STORAGE);
    Files.writeString(storage, "unregistered leftover storage");

    AppContext context = new AppContext();
    LogController logs = new LogController();

    AtomicReference<Alert> captured = new AtomicReference<>();
    Dialogs.dialogPrompter = alert -> {
      captured.set(alert);
      return Optional.of(ButtonType.NO);
    };

    runFx(() -> Dialogs.createVolume(context, logs, tempDir));
    waitOperations(context);

    assertNotNull(captured.get());
    assertEquals(Alert.AlertType.WARNING, captured.get().getAlertType());
    assertEquals("unregistered leftover storage", Files.readString(storage));

    Path canonical = Bitferry.canonicalize(tempDir);
    Volume created = context.manager().liveVolumes().values().stream()
      .filter(v -> v.root().equals(canonical))
      .findFirst().orElse(null);
    assertNull(created);
  }

  @Test
  void overwriteRegisteredVolume_confirmedWithYes() throws Exception {
    Path tempDir = Files.createTempDirectory("bitferry-overwrite-reg-test-");

    AppContext context = new AppContext();
    LogController logs = new LogController();

    Volume oldVol = Volume.create(context.manager(), tempDir);
    context.manager().commit();
    String oldTag = oldVol.tag();

    AtomicReference<Alert> captured = new AtomicReference<>();
    Dialogs.dialogPrompter = alert -> {
      captured.set(alert);
      return Optional.of(ButtonType.YES);
    };

    runFx(() -> Dialogs.createVolume(context, logs, tempDir));
    waitOperations(context);

    assertNotNull(captured.get());
    assertEquals(Alert.AlertType.WARNING, captured.get().getAlertType());

    Path canonical = Bitferry.canonicalize(tempDir);
    Volume current = context.manager().liveVolumes().values().stream()
      .filter(v -> v.root().equals(canonical))
      .findFirst().orElse(null);
    assertNotNull(current);
    assertNotEquals(oldTag, current.tag());
    assertNull(context.manager().liveVolumes().get(oldTag));
  }

  @Test
  void overwriteRegisteredVolume_cancelledWithNo() throws Exception {
    Path tempDir = Files.createTempDirectory("bitferry-overwrite-reg-cancel-");

    AppContext context = new AppContext();
    LogController logs = new LogController();

    Volume oldVol = Volume.create(context.manager(), tempDir);
    context.manager().commit();
    String oldTag = oldVol.tag();

    AtomicReference<Alert> captured = new AtomicReference<>();
    Dialogs.dialogPrompter = alert -> {
      captured.set(alert);
      return Optional.of(ButtonType.NO);
    };

    runFx(() -> Dialogs.createVolume(context, logs, tempDir));
    waitOperations(context);

    assertNotNull(captured.get());
    assertEquals(Alert.AlertType.WARNING, captured.get().getAlertType());

    Path canonical = Bitferry.canonicalize(tempDir);
    Volume current = context.manager().liveVolumes().values().stream()
      .filter(v -> v.root().equals(canonical))
      .findFirst().orElse(null);
    assertNotNull(current);
    assertEquals(oldTag, current.tag());
  }

  @Test
  void freshDirectory_createsDirectlyWithoutConfirmationDialog() throws Exception {
    Path tempDir = Files.createTempDirectory("bitferry-fresh-test-");

    AppContext context = new AppContext();
    LogController logs = new LogController();

    AtomicReference<Alert> captured = new AtomicReference<>();
    Dialogs.dialogPrompter = alert -> {
      captured.set(alert);
      return Optional.of(ButtonType.OK);
    };

    runFx(() -> Dialogs.createVolume(context, logs, tempDir));
    waitOperations(context);

    // No confirmation dialog should pop up for fresh directory
    assertNull(captured.get());

    assertTrue(Files.exists(tempDir.resolve(Bitferry.STORAGE)));
    Path canonical = Bitferry.canonicalize(tempDir);
    Volume created = context.manager().liveVolumes().values().stream()
      .filter(v -> v.root().equals(canonical))
      .findFirst().orElse(null);
    assertNotNull(created);
  }

  @Test
  void failedWritePermission_doesNotRemainRegistered() throws Exception {
    // The failure injection parks a directory at the intermediate image path: the
    // atomic write fails on every platform (EISDIR) - the directory read-only flag
    // is a POSIX-only mechanism (a no-op for the child creation on Windows). The
    // image-path variant also keeps the exists-check in createVolume silent (the
    // stubbed prompt stays uninvoked, the failure surfaces inside the runAsync)
    // WINDOWS: confirm this test goes green there (the injection and the cleanup)
    Path blockedDir = Files.createTempDirectory("bitferry-blocked-test-");
    Files.createDirectories(blockedDir.resolve(Bitferry.STORAGE_IMAGE));
    try {
      AppContext context = new AppContext();
      LogController logs = new LogController();

      Dialogs.dialogPrompter = alert -> Optional.of(ButtonType.OK);

      runFx(() -> Dialogs.createVolume(context, logs, blockedDir));
      waitOperations(context);

      Path canonical = Bitferry.canonicalize(blockedDir);
      Volume created = context.manager().liveVolumes().values().stream()
        .filter(v -> v.root().equals(canonical))
        .findFirst().orElse(null);
      assertNull(created, "Failed volume must not remain registered in liveVolumes");
      assertTrue(context.manager().volumes().values().stream()
        .noneMatch(v -> v.root().equals(canonical)), "Failed volume must not remain in manager.volumes");
    } finally {
      Files.deleteIfExists(blockedDir.resolve(Bitferry.STORAGE_IMAGE));
    }
  }

  @Test
  void creationOutsideTheScannedLocationsOffersTheTriChoice() throws Exception {
    // The temp dir is outside the scanning policy (not listed, not home, not a
    // mount root): the discovery tri-choice fires - "Create and add" creates the
    // volume AND puts the location onto the per-site scanned locations list
    Path tempDir = Files.createTempDirectory("bitferry-discovery-add-test-");
    Preferences node = Preferences.userNodeForPackage(org.bitferry.Bitferry.class);
    String savedRoots = node.get(Paths.key(), null);

    try {
      AppContext context = new AppContext();
      LogController logs = new LogController();

      AtomicReference<Alert> captured = new AtomicReference<>();
      Dialogs.discoveryPrompter = alert -> {
        captured.set(alert);
        // The dialog's own button instance: the tri-choice maps by identity
        return Optional.of(alert.getButtonTypes().stream()
          .filter(b -> "Create and add".equals(b.getText()))
          .findFirst().orElse(ButtonType.CANCEL));
      };

      runFx(() -> Dialogs.createVolume(context, logs, tempDir));
      waitOperations(context);

      assertNotNull(captured.get());
      assertEquals("Volume discovery", captured.get().getTitle());
      assertTrue(captured.get().getContentText().contains("not scanned for volumes"), () -> captured.get().getContentText());
      assertEquals(3, captured.get().getButtonTypes().size());

      Path canonical = Bitferry.canonicalize(tempDir);
      Volume created = context.manager().liveVolumes().values().stream()
        .filter(v -> v.root().equals(canonical))
        .findFirst().orElse(null);
      assertNotNull(created, "the create-and-add choice must create the volume");
      assertTrue(Paths.load().contains(canonical.toString()), () -> "the location must join the scanned locations: " + Paths.load());
    } finally {
      if (savedRoots != null) {
        node.put(Paths.key(), savedRoots);
      } else {
        node.remove(Paths.key());
      }
      node.flush();
    }
  }

  @Test
  void creationOutsideTheScannedLocations_cancelledCreatesNothing() throws Exception {
    Path tempDir = Files.createTempDirectory("bitferry-discovery-cancel-test-");
    Preferences node = Preferences.userNodeForPackage(org.bitferry.Bitferry.class);
    String savedRoots = node.get(Paths.key(), null);

    try {
      AppContext context = new AppContext();
      LogController logs = new LogController();

      Dialogs.discoveryPrompter = alert -> Optional.of(ButtonType.CANCEL);

      runFx(() -> Dialogs.createVolume(context, logs, tempDir));
      waitOperations(context);

      Path canonical = Bitferry.canonicalize(tempDir);
      assertNull(context.manager().liveVolumes().values().stream()
        .filter(v -> v.root().equals(canonical))
        .findFirst().orElse(null), "the cancelled creation must not register the volume");
      assertFalse(Files.exists(tempDir.resolve(Bitferry.STORAGE)), "the cancelled creation must write nothing");
      assertFalse(Paths.load().contains(canonical.toString()), "the cancelled creation must not touch the scanned locations");
    } finally {
      if (savedRoots != null) {
        node.put(Paths.key(), savedRoots);
      } else {
        node.remove(Paths.key());
      }
      node.flush();
    }
  }
}
