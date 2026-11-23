package org.bitferry.gui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/// Drives the items.fxml controller: profile preset selection, item adding,
/// editing, reordering, removal, and cleaning.
class ItemListControllerTest {

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
    void loadWithInitialItemsAndProfiles() throws Exception {
        Map<String, List<String>> profiles = Map.of(
            "@default", List.of("--opt1", "--opt2"),
            "@full", List.of("--opt1", "--opt2", "--all")
        );
        ItemListController controller = load(List.of("alpha", "beta"), profiles);

        ListView<String> listView = field(controller, "listView");
        assertEquals(List.of("alpha", "beta"), listView.getItems());

        VBox profileBar = field(controller, "profileBar");
        assertTrue(profileBar.isVisible());
        assertTrue(profileBar.isManaged());

        ComboBox<String> profileCombo = field(controller, "profileCombo");
        assertTrue(profileCombo.getItems().contains("@default"));
        assertTrue(profileCombo.getItems().contains("@full"));
        assertEquals("@default", profileCombo.getValue());
        assertEquals("@default", profileCombo.getItems().get(0));
    }

    @Test
    void defaultProfileIsPreSelectedEvenWhenListedLate() throws Exception {
        Map<String, List<String>> profiles = new java.util.LinkedHashMap<>();
        profiles.put("@zebra", List.of("--zebra"));
        profiles.put("@default", List.of("--default"));
        profiles.put("@apple", List.of("--apple"));
        ItemListController controller = load(List.of(), profiles);

        ComboBox<String> profileCombo = field(controller, "profileCombo");
        assertEquals("@default", profileCombo.getValue());
        assertEquals("@default", profileCombo.getItems().get(0));
    }

    @Test
    void profileKeyWithoutAtSymbolSelectsDefault() throws Exception {
        Map<String, List<String>> profiles = Map.of(
            "default", List.of("--std"),
            "custom", List.of("--custom")
        );
        ItemListController controller = load(List.of(), profiles);

        ComboBox<String> profileCombo = field(controller, "profileCombo");
        assertEquals("@default", profileCombo.getValue());
    }

    @Test
    void fallbackToFirstWhenNoDefaultPreset() throws Exception {
        Map<String, List<String>> profiles = new java.util.LinkedHashMap<>();
        profiles.put("@first", List.of("1"));
        profiles.put("@second", List.of("2"));
        ItemListController controller = load(List.of(), profiles);

        ComboBox<String> profileCombo = field(controller, "profileCombo");
        assertEquals("@first", profileCombo.getValue());
    }

    @Test
    void loadWithoutProfilesHidesTheProfileBar() throws Exception {
        ItemListController controller = load(List.of("single"), null);

        VBox profileBar = field(controller, "profileBar");
        assertFalse(profileBar.isVisible());
        assertFalse(profileBar.isManaged());
    }

    @Test
    void profileLoadReplacesListEntries() throws Exception {
        Map<String, List<String>> profiles = Map.of(
            "default", List.of("--meta", "--sparse")
        );
        ItemListController controller = load(List.of("initial"), profiles);

        ComboBox<String> profileCombo = field(controller, "profileCombo");
        runFx(() -> {
            profileCombo.getSelectionModel().select("@default");
            invoke(controller, "onProfileLoad");
        });

        ListView<String> listView = field(controller, "listView");
        assertEquals(List.of("--meta", "--sparse"), listView.getItems());
    }

    @Test
    void addingEditingReorderingAndRemovingItems() throws Exception {
        ItemListController controller = load(List.of("first", "second"), null);
        ListView<String> listView = field(controller, "listView");
        TextField inputField = field(controller, "inputField");

        // Add a new item from inputField
        runFx(() -> {
            inputField.setText("third");
            invoke(controller, "onAdd");
        });
        assertEquals(List.of("first", "second", "third"), listView.getItems());
        assertEquals("", inputField.getText());

        // Select and move the last item up
        runFx(() -> {
            listView.getSelectionModel().select(2);
            invoke(controller, "onMoveUp");
        });
        assertEquals(List.of("first", "third", "second"), listView.getItems());

        // Move the item down
        runFx(() -> {
            listView.getSelectionModel().select(1);
            invoke(controller, "onMoveDown");
        });
        assertEquals(List.of("first", "second", "third"), listView.getItems());

        // Apply an edit to the selected item
        runFx(() -> {
            listView.getSelectionModel().select(1);
            inputField.setText("second-modified");
            invoke(controller, "onApply");
        });
        assertEquals(List.of("first", "second-modified", "third"), listView.getItems());

        // Remove the selected item
        runFx(() -> {
            listView.getSelectionModel().select(1);
            invoke(controller, "onRemove");
        });
        assertEquals(List.of("first", "third"), listView.getItems());
    }

    @Test
    void cleanedItemsFiltersBlankAndTrimsWhitespace() throws Exception {
        ItemListController controller = load(List.of("  trimmed  ", "   ", "valid", ""), null);
        assertEquals(List.of("trimmed", "valid"), controller.cleanedItems());
    }

    @Test
    void directoryModeControlsBrowseButtonVisibility() throws Exception {
        // Without directory mode: browse button is hidden
        ItemListController patternController = load(List.of("pattern"), null, null);
        Button patternBrowse = field(patternController, "browseBtn");
        assertFalse(patternBrowse.isVisible());
        assertFalse(patternBrowse.isManaged());

        // With directory mode: browse button is visible and managed
        ItemListController dirController = load(List.of("/some/path"), null, "Select Root");
        Button dirBrowse = field(dirController, "browseBtn");
        assertTrue(dirBrowse.isVisible());
        assertTrue(dirBrowse.isManaged());
    }

    @Test
    void onBrowsePicksDirectoryAndAddsToList() throws Exception {
        var originalPicker = ItemListController.directoryPicker;
        try {
            java.io.File picked = new java.io.File("/picked/root/dir");
            ItemListController.directoryPicker = (window, start) -> picked;

            ItemListController controller = load(List.of("/existing/dir"), null, "Select Root");
            ListView<String> listView = field(controller, "listView");
            TextField inputField = field(controller, "inputField");

            runFx(() -> invoke(controller, "onBrowse"));

            assertEquals(List.of("/existing/dir", picked.getAbsolutePath()), listView.getItems());
            assertEquals(picked.getAbsolutePath(), inputField.getText());

            // Picking the same directory again does not duplicate, but selects it
            runFx(() -> invoke(controller, "onBrowse"));
            assertEquals(List.of("/existing/dir", picked.getAbsolutePath()), listView.getItems());
            assertEquals(1, listView.getSelectionModel().getSelectedIndex());
        } finally {
            ItemListController.directoryPicker = originalPicker;
        }
    }

    @Test
    void onAddWhenEmptyInDirectoryModeInvokesBrowse() throws Exception {
        var originalPicker = ItemListController.directoryPicker;
        try {
            java.io.File picked = new java.io.File("/added/via/picker");
            ItemListController.directoryPicker = (window, start) -> picked;

            ItemListController controller = load(List.of(), null, "Select Root");
            ListView<String> listView = field(controller, "listView");

            runFx(() -> invoke(controller, "onAdd"));

            assertEquals(List.of(picked.getAbsolutePath()), listView.getItems());
        } finally {
            ItemListController.directoryPicker = originalPicker;
        }
    }

    private static ItemListController load(List<String> items, Map<String, List<String>> profiles) throws Exception {
        return load(items, profiles, null);
    }

    private static ItemListController load(List<String> items, Map<String, List<String>> profiles, String dirTitle) throws Exception {
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<ItemListController> ref = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ref.set(ItemListController.load(items, profiles, dirTitle));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                loaded.countDown();
            }
        });
        if (!loaded.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the controller load timed out");
        if (failure.get() != null) throw new IllegalStateException("the controller load failed", failure.get());
        return ref.get();
    }

    private static void runFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the action timed out");
        if (failure.get() != null) throw new IllegalStateException("the action failed", failure.get());
    }

    private static void invoke(Object target, String methodName) {
        try {
            var method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }
}
