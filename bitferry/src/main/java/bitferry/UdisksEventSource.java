package bitferry;

import java.util.List;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.interfaces.ObjectManager;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.matchrules.DBusMatchRule;
import org.freedesktop.dbus.matchrules.DBusMatchRuleBuilder;
import org.freedesktop.dbus.messages.DBusSignal;

/// The Linux media-change event source: the udisks2 ObjectManager signals
/// (InterfacesAdded/InterfacesRemoved over the system bus) and PropertiesChanged
/// signals mark the media arrival, removal, and mount transitions instantly.
/// Listening needs no polkit privileges; any failure (no D-Bus, no udisks2, a
/// dropped bus) degrades to the polling fallback quietly - the signals merely
/// say "check now", the snapshot comparison is the real detector.
final class UdisksEventSource implements AutoCloseable {

  private static final String UDISKS2_ROOT = "/org/freedesktop/UDisks2";
  private static final java.lang.System.Logger LOG = java.lang.System.getLogger(UdisksEventSource.class.getName());

  private DBusConnection connection;

  /// Connect to the system bus and register the ObjectManager signal handlers;
  /// returns false when the source is unusable on this host.
  boolean start(Runnable onChange) {
    try {
      connection = DBusConnectionBuilder.forSystemBus().withShared(false).build();
      // The handshake (Hello) may not run during the builder's connect on the lazy
      // transports: the signal registration needs the bus proxy, so register explicitly
      try {
        connection.register();
      } catch (DBusException e) {
        if (e.getMessage() == null || !e.getMessage().contains("already called")) {
          throw e;
        }
      }
      // The handler runs on the D-Bus dispatch threads: the consumer must never
      // block there, and its failures must not poison the dispatch loop
      DBusSigHandler<DBusSignal> handler = signal -> {
        try {
          onChange.run();
        } catch (RuntimeException e) {
          LOG.log(java.lang.System.Logger.Level.WARNING, "media change consumer failed: " + e);
        }
      };
      // The ObjectManager signals: InterfacesAdded and InterfacesRemoved on /org/freedesktop/UDisks2
      for (Class<? extends DBusSignal> sigClass : List.of(ObjectManager.InterfacesAdded.class, ObjectManager.InterfacesRemoved.class)) {
        DBusMatchRule rule = DBusMatchRuleBuilder.create()
          .withType(sigClass)
          .withPath(UDISKS2_ROOT)
          .build();
        connection.addSigHandler(rule, handler);
      }
      // The filesystem mount and unmount transitions update properties on the block
      // device/filesystem objects without adding or removing interfaces
      DBusMatchRule propRule = DBusMatchRuleBuilder.create()
        .withType(Properties.PropertiesChanged.class)
        .withPathNamespace(UDISKS2_ROOT)
        .build();
      connection.addSigHandler(propRule, handler);
      return true;
    } catch (DBusException | RuntimeException e) {
      LOG.log(java.lang.System.Logger.Level.DEBUG, () -> "udisks2 media events unavailable: " + e);
      close();
      return false;
    }
  }

  @Override
  public void close() {
    if (connection != null) {
      try {
        connection.close();
      } catch (Exception ignored) {
        // The shutdown best effort
      } finally {
        connection = null;
      }
    }
  }
}
