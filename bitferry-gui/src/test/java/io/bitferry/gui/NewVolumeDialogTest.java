package io.bitferry.gui;

import io.bitferry.Bitferry;
import io.bitferry.Volume;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

  @AfterEach
  void resetPrompter() {
    Dialogs.dialogPrompter = javafx.scene.control.Dialog::showAndWait;
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
}
