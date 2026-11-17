package bitferry;

import bitferry.endpoint.Endpoints;
import bitferry.endpoint.LocalEndpoint;
import bitferry.endpoint.VolumeEndpoint;
import bitferry.task.Copy;
import bitferry.task.Equalize;
import bitferry.task.Task;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnsiFormatTest {

  @TempDir
  Path dir;

  @Test
  void tagFormatting() {
    assertEquals("", AnsiFormat.tag(null));
    assertEquals("\u001B[36mabcd1234\u001B[0m", AnsiFormat.tag("abcd1234"));
  }

  @Test
  void staleTagFormatting() {
    assertEquals("", AnsiFormat.staleTag(null));
    assertEquals("\u001B[1;31mdeadbeef\u001B[0m", AnsiFormat.staleTag("deadbeef"));
  }

  @Test
  void pathFormatting() {
    assertEquals("", AnsiFormat.path(null));
    assertEquals("\u001B[34m/data/store\u001B[0m", AnsiFormat.path(Path.of("/data/store")));
    assertEquals("\u001B[34m/data/store\u001B[0m", AnsiFormat.path("/data/store"));
  }

  @Test
  void operationFormatting() {
    assertEquals("", AnsiFormat.operation(null));
    assertEquals("\u001B[1mreplicate\u001B[0m", AnsiFormat.operation("replicate"));
  }

  @Test
  void directorFormatting() {
    assertEquals("", AnsiFormat.director(null));
    assertEquals("\u001B[32m-->\u001B[0m", AnsiFormat.director("-->"));
    assertEquals("\u001B[33m<->\u001B[0m", AnsiFormat.director("<->"));
  }

  @Test
  void endpointFormatting() throws IOException {
    assertEquals("", AnsiFormat.endpoint(null));

    LocalEndpoint local = new LocalEndpoint(Path.of("/local/path"));
    assertEquals("\u001B[34m/local/path\u001B[0m", AnsiFormat.endpoint(local));

    Manager manager = new Manager();
    Volume vol = new Volume(manager, dir.resolve("vol"));

    // Intact volume endpoint
    VolumeEndpoint intact = new VolumeEndpoint(manager, vol.tag(), "backup");
    assertEquals(":\u001B[36m" + vol.tag() + "\u001B[0m:backup", AnsiFormat.endpoint(intact));

    // Stale volume endpoint (vol not registered in a fresh manager)
    Manager freshManager = new Manager();
    VolumeEndpoint stale = new VolumeEndpoint(freshManager, vol.tag(), "backup");
    assertEquals(":{\u001B[1;31m" + vol.tag() + "\u001B[0m}:backup", AnsiFormat.endpoint(stale));
  }

  @Test
  void taskFormatting() throws IOException {
    assertEquals("", AnsiFormat.task(null));

    Manager manager = new Manager();
    Volume vol1 = new Volume(manager, dir.resolve("v1"));
    Volume vol2 = new Volume(manager, dir.resolve("v2"));

    Task copyTask = new Copy(manager,
      Endpoints.parse(manager, ":" + vol1.tag() + ":src"),
      Endpoints.parse(manager, ":" + vol2.tag() + ":dst"),
      null, List.of(), List.of());

    String formattedCopy = AnsiFormat.task(copyTask);
    assertTrue(formattedCopy.contains("\u001B[1mcopy\u001B[0m"));
    assertTrue(formattedCopy.contains(":\u001B[36m" + vol1.tag() + "\u001B[0m:src"));
    assertTrue(formattedCopy.contains("\u001B[32m-->\u001B[0m"));
    assertTrue(formattedCopy.contains(":\u001B[36m" + vol2.tag() + "\u001B[0m:dst"));

    Task eqTask = new Equalize(manager,
      Endpoints.parse(manager, ":" + vol1.tag() + ":src"),
      Endpoints.parse(manager, ":" + vol2.tag() + ":dst"),
      null, List.of(), List.of());

    String formattedEq = AnsiFormat.task(eqTask);
    assertTrue(formattedEq.contains("\u001B[33m<->\u001B[0m"));
  }

  @Test
  void tasksSummaryFormatting() throws IOException {
    assertEquals("0 task(s)", AnsiFormat.tasksSummary(null));

    Manager manager = new Manager();
    assertEquals("0 task(s)", AnsiFormat.tasksSummary(manager));

    Volume vol1 = new Volume(manager, dir.resolve("v1"));
    Volume vol2 = new Volume(manager, dir.resolve("v2"));

    // 1 intact task
    new Copy(manager,
      Endpoints.parse(manager, ":" + vol1.tag() + ":"),
      Endpoints.parse(manager, ":" + vol2.tag() + ":"),
      null, List.of(), List.of());

    String summary1 = AnsiFormat.tasksSummary(manager);
    assertEquals("1 task(s) (\u001B[32m1 intact\u001B[0m)", summary1);

    // Add a stale task (referring to non-existent volume)
    Manager freshManager = new Manager();
    new Volume(freshManager, dir.resolve("f1")); // only one volume
    // Copy the task into freshManager
    Task staleTask = new Copy(freshManager,
      new VolumeEndpoint(freshManager, "deadbeef", "."),
      new VolumeEndpoint(freshManager, "feedface", "."),
      null, List.of(), List.of());

    String summaryStale = AnsiFormat.tasksSummary(freshManager);
    assertEquals("1 task(s) (\u001B[32m0 intact\u001B[0m, \u001B[1;31m1 stale\u001B[0m)", summaryStale);
  }

  @Test
  void messageAlertColors() {
    assertEquals("", AnsiFormat.error(null));
    assertEquals("\u001B[31mfailure\u001B[0m", AnsiFormat.error("failure"));

    assertEquals("", AnsiFormat.warning(null));
    assertEquals("\u001B[33mcaution\u001B[0m", AnsiFormat.warning("caution"));

    assertEquals("", AnsiFormat.success(null));
    assertEquals("\u001B[32mdone\u001B[0m", AnsiFormat.success("done"));
  }

  @Test
  void strippingAnsiCodes() {
    assertNull(AnsiFormat.strip(null));
    assertEquals("", AnsiFormat.strip(""));
    assertEquals("plain text", AnsiFormat.strip("plain text"));
    assertEquals("colorized text with tag abcd1234 and /path",
      AnsiFormat.strip("colorized text with tag \u001B[36mabcd1234\u001B[0m and \u001B[34m/path\u001B[0m"));
  }
}
