package bitferry.endpoint;

import bitferry.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

class EndpointsTest {

  @TempDir
  Path dir;

  Manager manager;
  Volume volume;

  @BeforeEach
  void setUp() {
    manager = new Manager();
    volume = new Volume(manager, dir.resolve("volume"));
  }

  @Test
  void barePathOutsideVolumesIsRejected() {
    // The bare form is resolve-or-reject: the plain local endpoints are only created via the explicit `local:` prefix
    BitferryError error = assertThrows(BitferryError.class, () -> Endpoints.parse(manager, dir.resolve("some/dir").toString()));
    assertTrue(error.getMessage().contains("failed to resolve path"));
  }

  @Test
  void unresolvableBarePathIsRejected() {
    assertThrows(BitferryError.class, () -> Endpoints.parse(manager, "/no/such/path/anywhere"));
  }

  @Test
  void explicitLocalPrefixYieldsThePlainLocalFormOutsideVolumes() {
    // The only way to reference the paths outside the volumes
    Local local = assertInstanceOf(Local.class, Endpoints.parse(manager, "local:/no/such/path/anywhere"));
    assertEquals(Bitferry.canonicalize(Path.of("/no/such/path/anywhere")), local.root());
  }

  @Test
  void explicitLocalPrefixSpecifiesLocalEndpoint() {
    assertInstanceOf(Local.class, Endpoints.parse(manager, "local:" + dir.resolve("data")));
  }

  @Test
  void emptySpecifierBehavesLikeTheResolveForm() {
    // The empty path denotes the working directory resolved against the volumes;
    // the working directory is not encompassed by any volume in the test environment
    assertThrows(BitferryError.class, () -> Endpoints.parse(manager, ""));
  }

  @Test
  void barePathInsideAVolumeAttachesToIt() {
    // The bare paths are first tested against the known volumes and attached to the most concrete one
    VolumeEndpoint ve = assertInstanceOf(VolumeEndpoint.class, Endpoints.parse(manager, dir.resolve("volume").resolve("sub/dir").toString()));
    assertEquals(volume.tag(), ve.vtag());
    assertEquals("sub/dir", ve.path());

    // The volume root itself attaches with the '.' path
    VolumeEndpoint root = assertInstanceOf(VolumeEndpoint.class, Endpoints.parse(manager, dir.resolve("volume").toString()));
    assertEquals(volume.tag(), root.vtag());
    assertEquals(".", root.path());
  }

  @Test
  void barePathAttachesToTheMostConcreteNestedVolume() {
    Volume nested = new Volume(manager, dir.resolve("volume").resolve("nested"));
    VolumeEndpoint ve = assertInstanceOf(VolumeEndpoint.class, Endpoints.parse(manager, dir.resolve("volume").resolve("nested").resolve("deep").toString()));
    assertEquals(nested.tag(), ve.vtag());
    assertEquals("deep", ve.path());

    // The path inside the enclosing volume but outside the nested one attaches to the enclosing volume
    VolumeEndpoint outer = assertInstanceOf(VolumeEndpoint.class, Endpoints.parse(manager, dir.resolve("volume").resolve("other").toString()));
    assertEquals(volume.tag(), outer.vtag());
  }

  @Test
  void explicitLocalPrefixForcesThePlainLocalFormInsideAVolume() {
    Local local = assertInstanceOf(Local.class, Endpoints.parse(manager, "local:" + dir.resolve("volume").resolve("sub/dir")));
    assertEquals(Bitferry.canonicalize(dir.resolve("volume").resolve("sub/dir")), local.root());
  }

  @Test
  void generationOfTheAbsentVolumeYieldsZero() {
    // The absent volumes contribute nothing to the out-of-date generation dance:
    // e.g. the deletion of a task whose peer volume is not restored must succeed
    VolumeEndpoint ve = new VolumeEndpoint(manager, "00000000", "x");
    assertFalse(ve.isIntact());
    assertEquals(0, ve.generation());
  }

  @Test
  void resolveSpecifierResolvesAgainstTheEncompassingVolume() {
    // The '::' form resolves the path (against the working directory when relative) with respect to the volumes
    Endpoint endpoint = Endpoints.parse(manager, "::" + dir.resolve("volume").resolve("sub/dir"));
    VolumeEndpoint ve = assertInstanceOf(VolumeEndpoint.class, endpoint);
    assertEquals(volume.tag(), ve.vtag());
    assertEquals("sub/dir", ve.path());
  }

  @Test
  void emptyPathSpecifierYieldsTheDotPath() {
    VolumeEndpoint ve = assertInstanceOf(VolumeEndpoint.class, Endpoints.parse(manager, ":" + volume.tag() + ":"));
    assertEquals(volume.tag(), ve.vtag());
    assertEquals(".", ve.path());
    // The volume root's '.' path is omitted in the listings
    assertEquals(":" + volume.tag() + ":", ve.describe());
  }

  @Test
  void unresolvableResolveSpecifierIsRejected() {
    assertThrows(BitferryError.class, () -> Endpoints.parse(manager, "::/elsewhere/outside"));
  }

  @Test
  void tagSpecifierAttachesToTheMatchedVolume() {
    String part = volume.tag().substring(0, 6);
    VolumeEndpoint ve = assertInstanceOf(VolumeEndpoint.class, Endpoints.parse(manager, ":" + part + ":backup"));
    assertEquals(volume.tag(), ve.vtag());
    assertEquals("backup", ve.path());
    assertEquals(":" + volume.tag() + ":backup", ve.describe());
  }

  @Test
  void unknownTagSpecifierIsRejected() {
    assertThrows(BitferryError.class, () -> Endpoints.parse(manager, ":zzzzzzzz:backup"));
  }

  @Test
  void invalidSpecifiersAreRejected() {
    assertThrows(BitferryError.class, () -> Endpoints.parse(manager, ":just-one-colon"));
    assertThrows(BitferryError.class, () -> Endpoints.parse(manager, "rclone://remote:bucket/path"));
  }

  @Test
  void endpointsSerializeAndRestoreSymmetrically() {
    String descriptor = Endpoints.parse(manager, ":" + volume.tag() + ":backup").serialize().toString();
    assertTrue(descriptor.contains("\"volume\":\"" + volume.tag() + "\""));
    Endpoint restored = Endpoints.restore(manager, Endpoints.parse(manager, ":" + volume.tag() + ":backup").serialize());
    VolumeEndpoint ve = assertInstanceOf(VolumeEndpoint.class, restored);
    assertEquals(volume.tag(), ve.vtag());
    assertEquals("backup", ve.path());

    Endpoint local = Endpoints.parse(manager, "local:" + dir.resolve("data"));
    Endpoint restoredLocal = Endpoints.restore(manager, local.serialize());
    Local l = assertInstanceOf(Local.class, restoredLocal);
    assertEquals(local.processOpts(), l.processOpts());
  }

  @Test
  void invalidDescriptorsAreRejected() {
    var factory = tools.jackson.databind.node.JsonNodeFactory.instance;
    // The pre-annotation descriptor shapes (without the 'endpoint' discriminator) are rejected
    var two = factory.objectNode();
    two.set("local", factory.objectNode().put("root", "/x"));
    two.set("bitferry", factory.objectNode().put("volume", "x").put("path", "y"));
    assertThrows(BitferryError.class, () -> Endpoints.restore(manager, two));
    var none = factory.objectNode();
    assertThrows(BitferryError.class, () -> Endpoints.restore(manager, none));
    var incomplete = factory.objectNode().set("local", factory.objectNode());
    assertThrows(BitferryError.class, () -> Endpoints.restore(manager, incomplete));
  }
}
