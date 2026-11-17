/// The Bitferry core library implementing the domain model: the volumes, the
/// tasks, the endpoints, the password vault and the `.bitferry` storage.
module bitferry {
  // The storage (de)serialization travels through the Jackson machinery whose
  // types (JsonNode, ObjectMapper) surface in the exported API
  requires transitive tools.jackson.databind;
  // The Jackson annotations the model classes are decorated with
  requires com.fasterxml.jackson.annotation;
  // The OS keyring adapter backing the keyring password vault
  requires com.microsoft.credentialstorage;
  // The drive type probing of the Windows fixed-media classification
  requires com.sun.jna.platform;
  // The D-Bus machinery of the Linux media-change watcher (the udisks2 signals)
  requires org.freedesktop.dbus;
  requires org.freedesktop.dbus.transport.jre;
  // The compact console log formatter shared by the frontends
  requires java.logging;

  exports bitferry;
  exports bitferry.endpoint;
  exports bitferry.task;
  exports bitferry.vault;

  // The Jackson machinery reflects over the model classes (the creators, the
  // properties) when running on the module path (jlink/jpackage images)
  opens bitferry to tools.jackson.databind;
  opens bitferry.endpoint to tools.jackson.databind;
  opens bitferry.task to tools.jackson.databind;
}
