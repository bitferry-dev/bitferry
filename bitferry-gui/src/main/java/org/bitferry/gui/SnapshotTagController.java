package org.bitferry.gui;

import org.bitferry.*;
import org.bitferry.endpoint.*;
import org.bitferry.task.*;
import javafx.application.*;
import javafx.fxml.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/// Controller for `snapshot_tag.fxml`: displays local backup task scope candidates
/// immediately and extracts snapshot tags from the target Restic repository asynchronously.
public final class SnapshotTagController {

  private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  record TagEntry(String tag, String label) {
    @Override
    public String toString() {
      return label;
    }
  }

  @FXML
  private VBox root;

  @FXML
  private ProgressIndicator progress;

  @FXML
  private Label statusLabel;

  @FXML
  private Button scanBtn;

  @FXML
  private ListView<TagEntry> tagList;

  private AppContext context;
  private Endpoint repository;
  private String password;
  private String initialTag;
  private Runnable onConfirm = () -> {};

  private final Set<String> discoveredTags = new LinkedHashSet<>();
  private final AtomicLong scanSeq = new AtomicLong();
  private volatile CompletableFuture<Void> activeScan = CompletableFuture.completedFuture(null);

  public SnapshotTagController() {}

  @FXML
  private void initialize() {
    scanBtn.setMinWidth(Region.USE_PREF_SIZE);
    Icons.equip(scanBtn, Icons.Symbol.REFRESH);
    tagList.setOnMouseClicked(event -> {
      if (event.getClickCount() == 2 && tagList.getSelectionModel().getSelectedItem() != null) {
        onConfirm.run();
      }
    });
  }

  void setOnConfirm(Runnable onConfirm) {
    this.onConfirm = onConfirm != null ? onConfirm : () -> {};
  }

  void init(AppContext context, Endpoint repository, String password, String initialTag) {
    this.context = context;
    this.repository = repository;
    this.password = password;
    this.initialTag = initialTag != null ? initialTag.trim() : "";

    populateLocalCandidates(null);
    startScan();
  }

  private void populateLocalCandidates(List<Snapshot> snapshots) {
    List<TagEntry> entries = new ArrayList<>();
    entries.add(new TagEntry("", "(default) — auto-derive backup task or newest " + Tags.MARKER + " snapshot"));

    Map<String, Snapshot> tagToSnapshot = new LinkedHashMap<>();
    if (snapshots != null) {
      for (Snapshot s : snapshots) {
        for (String tag : s.tags()) {
          if (!Tags.MARKER.equals(tag)) {
            tagToSnapshot.putIfAbsent(tag, s);
          }
        }
      }
    }

    Set<String> addedTags = new LinkedHashSet<>();
    if (context != null && repository != null) {
      for (String cTag : Restore.scopeCandidates(context.manager(), repository)) {
        Task t = context.manager().tasks().get(cTag);
        StringBuilder desc = new StringBuilder();
        if (t instanceof Backup b) {
          desc.append("local backup: ").append(b.source().describe()).append(" → ").append(b.destination().describe());
        } else {
          desc.append("local backup task");
        }
        Snapshot latest = tagToSnapshot.get(cTag);
        if (latest != null) {
          String time = formatSnapshotTime(latest.time());
          if (!time.isEmpty()) desc.append(", latest: ").append(time);
        }
        entries.add(new TagEntry(cTag, cTag + "  (" + desc + ")"));
        addedTags.add(cTag);
      }
    }

    for (var entry : tagToSnapshot.entrySet()) {
      String tag = entry.getKey();
      if (addedTags.add(tag)) {
        Snapshot s = entry.getValue();
        String pathDesc = s.paths().isEmpty() ? "" : String.join(", ", s.paths());
        String hostDesc = s.hostname() != null && !s.hostname().isEmpty() ? " on " + s.hostname() : "";
        String timeDesc = formatSnapshotTime(s.time());
        String shortDesc = s.shortId() != null && !s.shortId().isEmpty() ? s.shortId() : "";
        StringBuilder details = new StringBuilder();
        if (!pathDesc.isEmpty()) details.append(pathDesc);
        if (!hostDesc.isEmpty()) details.append(hostDesc);
        if (!shortDesc.isEmpty()) {
          if (!details.isEmpty()) details.append(", ");
          details.append(shortDesc);
        }
        if (!timeDesc.isEmpty()) {
          if (!details.isEmpty()) details.append(", ");
          details.append(timeDesc);
        }
        String label = details.isEmpty() ? tag : tag + "  (" + details + ")";
        entries.add(new TagEntry(tag, label));
      }
    }

    if (snapshots != null) {
      discoveredTags.addAll(tagToSnapshot.keySet());
    }

    TagEntry prevSelected = tagList.getSelectionModel().getSelectedItem();
    String preferredTag = prevSelected != null ? prevSelected.tag() : initialTag;
    tagList.getItems().setAll(entries);

    TagEntry toSelect = entries.stream()
      .filter(e -> e.tag().equals(preferredTag))
      .findFirst()
      .orElse(entries.getFirst());
    tagList.getSelectionModel().select(toSelect);
  }

  private void startScan() {
    if (repository == null) {
      setScanning(false, "Repository endpoint is missing — showing local entries only.");
      scanBtn.setDisable(true);
      activeScan = CompletableFuture.completedFuture(null);
      return;
    }
    if (password == null || password.isEmpty()) {
      setScanning(false, "No repository password set — enter password in Security to scan repository tags.");
      scanBtn.setDisable(true);
      activeScan = CompletableFuture.completedFuture(null);
      return;
    }
    if (context == null) {
      setScanning(false, "No application context available.");
      activeScan = CompletableFuture.completedFuture(null);
      return;
    }

    long seq = scanSeq.incrementAndGet();
    setScanning(true, "Extracting snapshot tags from repository...");

    Manager mgr = context.manager();
    activeScan = CompletableFuture.runAsync(() -> {
      try {
        List<Snapshot> snapshots = Restore.repositorySnapshots(mgr, repository, password);
        Platform.runLater(() -> {
          if (scanSeq.get() != seq) return;
          populateLocalCandidates(snapshots);
          long tagCount = snapshots.stream()
            .flatMap(s -> s.tags().stream())
            .filter(t -> !Tags.MARKER.equals(t))
            .distinct()
            .count();
          setScanning(false, "Found " + snapshots.size() + " snapshot(s), " + tagCount + " tag(s).");
        });
      } catch (RuntimeException e) {
        Platform.runLater(() -> {
          if (scanSeq.get() != seq) return;
          setScanning(false, "Repository scan failed: " + e.getMessage());
        });
      }
    }, context.operations());
  }

  private void setScanning(boolean scanning, String status) {
    progress.setVisible(scanning);
    progress.setManaged(scanning);
    scanBtn.setDisable(scanning);
    statusLabel.setText(status);
  }

  @FXML
  private void onScan() {
    startScan();
  }

  VBox pane() {
    return root;
  }

  String selectedTag() {
    TagEntry selected = tagList.getSelectionModel().getSelectedItem();
    return selected != null ? selected.tag() : initialTag;
  }

  Set<String> discoveredTags() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(discoveredTags));
  }

  CompletableFuture<Void> activeScan() {
    return activeScan;
  }

  private static String formatSnapshotTime(String iso) {
    if (iso == null || iso.isEmpty()) return "";
    try {
      return OffsetDateTime.parse(iso).format(TIME_FMT);
    } catch (Exception e) {
      return iso.length() >= 16 ? iso.substring(0, 16).replace('T', ' ') : iso;
    }
  }

  static SnapshotTagController load(AppContext context, Endpoint repository, String password, String initialTag) {
    try {
      FXMLLoader loader = new FXMLLoader(SnapshotTagController.class.getResource("/org/bitferry/gui/snapshot_tag.fxml"));
      loader.load();
      SnapshotTagController controller = loader.getController();
      controller.init(context, repository, password, initialTag);
      return controller;
    } catch (IOException e) {
      throw new BitferryError("Snapshot tag dialog loading failure", e);
    }
  }
}
