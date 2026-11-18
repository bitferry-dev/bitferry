package io.bitferry.gui;

import io.bitferry.*;
import io.bitferry.endpoint.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import static org.junit.jupiter.api.Assertions.*;

/// The endpoint specifier built by the task form controller's smart browse button:
/// the bare path when the picked directory is encompassed by a registered volume
/// (it parses back into the same volume endpoint), the explicit local: form outside
/// them (the bare form would be rejected by the parser).
class EndpointSpecifierTest {

  @TempDir
  Path dir;

  Manager manager;
  Volume outer;
  Volume inner;

  @BeforeEach
  void setUp() throws Exception {
    manager = new Manager();
    Files.createDirectories(dir.resolve("outer"));
    outer = new Volume(manager, dir.resolve("outer"));
    // The nested volume: the most concrete encompassing volume must win
    Files.createDirectories(dir.resolve("outer").resolve("nested"));
    inner = new Volume(manager, dir.resolve("outer").resolve("nested"));
  }

  @Test
  void encompassedDirectoriesResolveIntoTheBareForm() {
    Path inside = dir.resolve("outer").resolve("data");
    String specifier = TaskFormController.endpointSpecifier(manager, inside);
    assertEquals(Bitferry.canonicalize(inside).toString(), specifier);
    // The bare form parses back into the same endpoint descriptor
    assertEquals(
      manager.endpoint(Bitferry.canonicalize(inside)).describe(),
      Endpoints.parse(manager, specifier).describe());
  }

  @Test
  void nestedVolumesResolveIntoTheMostConcreteOne() {
    Path inside = dir.resolve("outer").resolve("nested").resolve("data");
    String specifier = TaskFormController.endpointSpecifier(manager, inside);
    assertEquals(Bitferry.canonicalize(inside).toString(), specifier);
    VolumeEndpoint endpoint = (VolumeEndpoint) Endpoints.parse(manager, specifier);
    assertEquals(inner.tag(), endpoint.vtag());
  }

  @Test
  void outsideDirectoriesResolveIntoTheLocalForm() {
    Path outside = dir.resolve("plain");
    String specifier = TaskFormController.endpointSpecifier(manager, outside);
    assertEquals("local:" + Bitferry.canonicalize(outside), specifier);
    assertInstanceOf(LocalEndpoint.class, Endpoints.parse(manager, specifier));
  }
}
