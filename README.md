# bitferry distribution

A Gradle build that assembles [bitferry](bitferry), a small JPMS library, plus two frontends —
a picocli CLI and a JavaFX GUI — into **one platform-specific runtime tree** with CLI and GUI
launchers, **AOT-trained** with the JDK 25 [AOT cache](https://openjdk.org/jeps/514) (Project Leyden).

- **Toolchain**: Java 25, Azul **Zulu** — auto-provisioned through the [foojay resolver](https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention); no local JDK 25 install needed.
- **Runtime images**: [beryx badass-jlink](https://github.com/beryx/badass-jlink-plugin) drives `jlink` and `jpackage`.
- **Packaging**: a `jlink` tree, a `jpackage` **app-image** with native launchers, and — on Windows — a per-user **MSI installer** built with [WiX](https://wixtoolset.org). No archives.
- **Bundled engines**: the [rclone](https://rclone.org) and [restic](https://restic.net) executables the frontends drive ship inside the image — downloaded per target platform and SHA-256-verified at build time (see below).

## Modules

| Gradle project  | JPMS module    | What it is                                                       |
|-----------------|---------------|------------------------------------------------------------------|
| `bitferry`      | `bitferry`    | the library: ferry dispatch API, logging via SLF4J (API only)    |
| `bitferry-cli`  | `bitferry.cli`| CLI frontend: picocli commands `run`, `doctor`; logback backend  |
| `bitferry-gui`  | `bitferry.gui`| JavaFX frontend; logback backend                                 |
| `bitferry-app`  | `bitferry.app`| distribution: jlink/jpackage/AOT assembly (aggregate module)     |

Module names use dots — hyphens are not legal in Java module names.

## Build

```bash
./gradlew :bitferry-app:dist                 # everything for the build host platform
./gradlew :bitferry-app:jlink                # just the jlink tree
./gradlew :bitferry-app:jpackageImage        # jpackage app-image with native launchers
./gradlew :bitferry-app:msi                  # per-user Windows MSI (WiX; Windows host only)
./gradlew :bitferry-app:dist -Ptarget=windows-x64   # cross-build (see below)
```

`dist` for a native target produces:

```
bitferry-app/build/image/bitferry-<platform>/     # jlink tree
    bin/bitferry, bin/bitferry-gui                # launcher scripts (+ .bat on Windows)
    bin/rclone, bin/restic                        # bundled backup engines
    bin/java                                      # the runtime JVM
    lib/aot/cli.aot, lib/aot/gui.aot              # trained AOT caches

bitferry-app/build/jpackage/bitferry/             # jpackage app-image (native platform only)
    Windows: bitferry.exe, bitferry-gui.exe       # native launchers (CLI console / GUI)
    Linux:   bin/bitferry, bin/bitferry-gui
    bin/rclone, bin/restic                        # bundled backup engines
    app/*.cfg (Windows) / lib/app/*.cfg (Linux)   # launcher configs
    runtime/ (Windows) / lib/runtime/ (Linux)     # the jlink tree, caches included
    lib/aot/…                                     # caches mirrored for $APPDIR lookup

bitferry-app/build/installer/                     # Windows only
    bitferry-<version>-windows-x64.msi            # per-user installer (WiX)
```

### Bundled backup engines

The frontends drive **rclone** and **restic**, so `fetchTools` downloads both for the target
platform (`-Ptarget`-aware, like the JDKs), verifies them against SHA-256 hashes pinned in
`bitferry-app/build.gradle` — written after cross-checking the official `SHA256SUMS` of each
release — and places them into `<image>/bin`, which every deliverable carries (the jlink tree
directly, the app-image via `copyToolsToJpackageImage`, the MSI via its `bin\` harvest).
Archives are cached in `~/.gradle/jdks/bitferry/tools/`, keyed by the pinned versions.

Every launcher (jlink script and jpackage config) passes `-Dbitferry.rclone` /
`-Dbitferry.restic` pointing at the bundled executables. jpackage launchers cannot define
environment variables — the cfg format only knows `app.mainmodule`, `app.mainjar`,
`app.mainclass`, `app.classpath`, `arguments` and `java-options` — so the packaged
locations travel as system properties, expanded by the launcher binary itself and
therefore intact under AppImage/Flatpak/Snap wrappers on Linux. `bitferry.BackupEngines`
resolves the engines with those properties taking priority over the `RCLONE` / `RESTIC`
environment variables: bare `java -m` runs against a jlink tree set no java options and
fall back to the variables. `bitferry doctor` prints the resolved locations and their
origin.

To bump a tool: change the version in `toolVersions`, update its ten pinned hashes with the
new release's `SHA256SUMS`, and rebuild — the new archive URL fails the cache key and
re-downloads.

### Windows installer

`msi` packages the jpackage app-image with [WiX](https://wixtoolset.org) (`wix build`,
v4+ CLI; the sources live in `src/packaging/wix/`). Prerequisites: a Windows host (jpackage
and WiX both run natively only) and the `wix` .NET tool on PATH
(`dotnet tool install --global wix`, needs the .NET SDK). Building with WiX v7 also
requires accepting its EULA once — the build passes `-acceptEula wix7`; see the
[OSMF terms](https://docs.firegiant.com/wix/osmf/) (organizations above the OSMF revenue
threshold must sponsor the project).

The MSI is meant for user-guided installs: double-clicking it shows only the basic MSI
progress and completion UI — no wizard, license or readme dialogs. It installs the
app-image tree to `%LOCALAPPDATA%\Programs\Bitferry` for the current user (no elevation),
adds that directory — where `bitferry.exe` lives — to the user PATH, and adds a Start
Menu shortcut launching `bitferry-gui.exe`. Uninstall removes everything it laid down. Note
that WiX assigns a fresh ProductCode to every build, so uninstall automation must read
the code from the built MSI (the Property table) rather than reusing a memorized GUID.

### AOT training

`trainCliAot` / `trainGuiAot` run the **image's own JVM** with `-XX:AOTCacheOutput`,
exercising each frontend (`run` with sample cargo; the GUI in `--train` mode, which shows
a window, dispatches a fleet and exits on first frame). The caches land in `lib/aot/`
inside the image tree, and every launcher passes `-XX:AOTCache=…` pointing there.
`bitferry doctor` prints the resolved cache path and whether it is in use.

Measured on the reference machine (Zulu 25, Linux x64): CLI startup `--version`
**~140 ms with cache vs ~360 ms without**.

Headless build hosts: GUI training cannot open a window (Monocle is not published in the
OpenJFX Maven artifacts), so the run is allowed to fail at toolkit init and still records
the startup classes. For full coverage run the build under a display or `xvfb-run`.
Force it with `-PaotGuiHeadless=force|never`.

### Cross-building

`-Ptarget=windows-x64|linux-x64|linux-aarch64|macos-x64|macos-aarch64` (default `native` = build host)
downloads the matching Zulu 25 JDK via `jdkDownload` and links a tree for that platform
with the platform's JavaFX natives from Maven Central. JDKs are cached in
`~/.gradle/jdks/bitferry/` (shared across checkouts, outside the project tree).

Three things cannot be cross-built and require running the build **on** the target platform:

- **AOT caches** — produced by executing the target JVM (`-Ptarget=native` on a Windows host trains them).
- **jpackage app-images** — `jpackage` must run on the target OS, so cross targets produce the jlink tree only.
- **The Windows MSI** — `msi` runs WiX on the build host, so it is only wired up for native Windows builds.

## Development

```bash
./gradlew :bitferry-cli:run --args="run --from a --to b --cargo tea:80"
./gradlew :bitferry-gui:run
```

Logging: the library logs through SLF4J; both frontends ship logback with a `logback.xml`
in their resources (logs to stderr, `bitferry.*` at DEBUG).

Dependency versions live in `gradle/libs.versions.toml`; the JavaFX version is pinned
there too. The toolchain major version is pinned as `bitferry.java` in `gradle.properties`,
and the exact Zulu build used for cross-build jmods is pinned in `bitferry-app/build.gradle`
(which verifies the two agree — the exact update build of the auto-provisioned toolchain
is whatever matching Zulu the foojay resolver returns).
