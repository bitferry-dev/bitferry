# bitferry distribution

A Gradle build that assembles [bitferry](bitferry), a small JPMS library, plus two frontends —
a picocli CLI and a JavaFX GUI — into **one platform-specific runtime tree** with CLI and GUI
launchers, **AOT-trained** with the JDK 25 [AOT cache](https://openjdk.org/jeps/514) (Project Leyden).

- **Toolchain**: Java 25, Azul **Zulu** — auto-provisioned through the [foojay resolver](https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention); no local JDK 25 install needed.
- **Runtime images**: [beryx badass-jlink](https://github.com/beryx/badass-jlink-plugin) drives `jlink` and `jpackage`.
- **Packaging**: a `jlink` tree, a `jpackage` **app-image** with native launchers, a platform-neutral **zip / tar.gz** bundling the bitferry modules, their small jar dependencies (picocli, Jackson) and cross-platform launchers for distro packaging (the Java/JavaFX runtimes and the rclone/restic engines are the packager's dependencies), a per-user **MSI installer** on Windows ([WiX](https://wixtoolset.org)) and a single-file **AppImage** on Linux. No per-module archives.
- **Bundled engines**: the [rclone](https://rclone.org) and [restic](https://restic.net) executables the frontends drive ship inside the image — downloaded per target platform and SHA-256-verified at build time (see below).

## Modules

| Gradle project  | JPMS module    | What it is                                                       |
|-----------------|---------------|------------------------------------------------------------------|
| `bitferry`      | `bitferry`    | the library: volumes, tasks, vault and the `.bitferry` storage (Jackson-based) |
| `bitferry-cli`  | `bitferry.cli`| CLI frontend: picocli commands (`info`, `create`, `delete`, `execute`); java.util.logging |
| `bitferry-gui`  | `bitferry.gui`| JavaFX frontend; java.util.logging                               |
| `bitferry-app`  | `bitferry.app`| distribution: jlink/jpackage/AOT assembly (aggregate module)     |

Module names use dots — hyphens are not legal in Java module names.

## Build

```bash
./gradlew :bitferry-app:dist                 # everything for the build host platform
./gradlew :bitferry-app:jlink                # just the jlink tree
./gradlew :bitferry-app:bundle               # zip + tar.gz jar archive with launchers (distro packaging)
./gradlew :bitferry-app:jpackageImage        # jpackage app-image with native launchers
./gradlew :bitferry-app:msi                  # per-user Windows MSI (WiX; Windows host only)
./gradlew :bitferry-app:appimage             # single-file Linux AppImage (Linux host only)
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

bitferry-app/build/installer/
    bitferry-<version>-windows-x64.msi            # per-user installer (WiX; Windows host only)
    bitferry-<version>-linux-<arch>.AppImage      # single-file AppImage (Linux host only)

bitferry-app/build/distributions/                 # joint jar archive (target-independent)
    bitferry-<version>.zip, .tar.gz               # bin/ launchers (sh+bat) + lib/ with bitferry + picocli/Jackson
```

### Bundled backup engines

The frontends drive **rclone** and **restic**, so `fetchTools` downloads both for the target
platform (`-Ptarget`-aware, like the JDKs), verifies them against SHA-256 hashes pinned in
`bitferry-app/build.gradle` — written after cross-checking the official `SHA256SUMS` of each
release — and places them into `<image>/bin`, which every deliverable carries (the jlink tree
directly, the app-image via `copyToolsToJpackageImage`, the MSI via its `bin\` harvest).
Archives are cached in `~/.gradle/jdks/bitferry/tools/`, keyed by the pinned versions.

Every deployment resolves the engines at runtime through `bitferry.task.ExecutorSupport`,
in strict priority order:

1. the launcher-provided `-Dbitferry.rclone` / `-Dbitferry.restic` system properties —
   every packaged launcher passes them pointing at the bundled executables, so packaged
   deployments always drive the engines they were built with;
2. the `RCLONE` / `RESTIC` environment overrides;
3. the distribution-bundled executables found relative to the running runtime image (the
   jlink image's `bin/`, the jpackage image root, the AppImage root);
4. the bare name for the PATH lookup — a bare `java -m` run without the environment falls
   back to whatever `rclone`/`restic` is on PATH.

To bump a tool: change the version in `toolVersions`, update its five pinned hashes (one
per platform) with the new release's `SHA256SUMS`, and rebuild — the new archive URL fails
the cache key and re-downloads.

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

### Linux AppImage

`appimage` wraps the jpackage app-image into a single-file [AppImage](https://appimage.org)
(type 2) with [appimagetool](https://github.com/AppImage/appimagetool) — the Linux
counterpart of the `msi` task, so it only runs for native Linux builds. `appimagetool`
and its squashfs runtime ([type2-runtime](https://github.com/AppImage/type2-runtime), fed
via `--runtime-file`, since appimagetool otherwise downloads an unpinned runtime at build
time) are downloaded like the backup engines: pinned versions, SHA-256-verified, cached in
`~/.gradle/jdks/bitferry/tools/appimagetool/`. Building itself needs no FUSE —
appimagetool is executed through its own `--appimage-extract-and-run` fallback.

The AppImage carries the whole app-image at `usr/app` plus a static `AppRun` that
dispatches the two frontends: an argument of `-g` / `--gui` (any position) launches the
GUI, everything else — including no arguments — runs the CLI:

```bash
./build/installer/bitferry-0.1.0-linux-x64.AppImage info                    # CLI
./build/installer/bitferry-0.1.0-linux-x64.AppImage --gui                   # GUI
```

The bundled engines and AOT caches keep working from the read-only squashfs mount: the
engine resolution searches relative to the running runtime image (see "Bundled backup
engines"), so it finds `bin/rclone`/`bin/restic` inside the mounted tree. Running the
AppImage needs `libfuse2` (or its `--appimage-extract-and-run` fallback on machines
without it).

#### Installing (desktop/PATH integration)

`install`, handled by the AppRun launcher itself (like the `--gui` dispatch), performs the
per-user integration the Windows MSI does — no AppImageLauncher required:

```bash
./bitferry-0.1.0-linux-x64.AppImage install
```

This lays down a managed copy at `<XDG_DATA_HOME>/bitferry/bitferry.AppImage` (default
`~/.local/share/bitferry/`), a `~/.local/bin/bitferry` symlink putting the CLI on PATH, a
`<XDG_DATA_HOME>/applications/bitferry.desktop` menu entry (`Exec=<managed copy> --gui`,
stamped with `X-AppImage-Version` and carrying `X-AppImage-Integrate=false`, so
AppImageLauncher never duplicates the integration) and the icon under
`<XDG_DATA_HOME>/icons/hicolor/`. `uninstall` removes all of it and never touches the
AppImage file itself. Re-running `install` from a newer AppImage upgrades in place.
Installation is optional — running the AppImage directly keeps working; from a jlink tree
`bitferry install` errors naturally, since only the AppImage's launcher knows the machinery.

To bump the packaging toolchain: change `appImageToolVersion` / `appImageRuntimeTag` in
`bitferry-app/build.gradle` and update the four pinned hashes with the new release's
GitHub-published SHA-256 digests.

### AOT training

`trainCliAot` / `trainGuiAot` run the **image's own JVM** with `-XX:AOTCacheOutput`,
exercising each frontend (the CLI's default `info` action; the GUI in `--train` mode,
which shows a window and exits on first frame). The caches land in `lib/aot/`
inside the image tree, and every launcher passes `-XX:AOTCache=…` pointing there.

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

Four things cannot be cross-built and require running the build **on** the target platform:

- **AOT caches** — produced by executing the target JVM (`-Ptarget=native` on a Windows host trains them).
- **jpackage app-images** — `jpackage` must run on the target OS, so cross targets produce the jlink tree only.
- **The Windows MSI** — `msi` runs WiX on the build host, so it is only wired up for native Windows builds.
- **The Linux AppImage** — `appimage` wraps the jpackage app-image, so it is only wired up for native Linux builds.

Cross targets produce the linked jlink tree (target-specific); the jar archive in
`build/distributions/` is target-independent and identical for every target. Since only the
target platform can train its AOT caches, the tree ships as a directory and starts up
untrained until the target builds its own.

## Development

```bash
./bitferry.sh info                          # build + run the CLI from the local build tree
./gradlew :bitferry-cli:run --args="info"   # via the gradle run task (no interactive terminal)
./gradlew :bitferry-gui:run
```

The `bitferry.sh` launcher builds the CLI (`:bitferry-cli:installDist`) and runs the
installed launcher with the arguments passed through unchanged. Unlike the gradle run
task (whose daemon detaches the process console), it keeps the real terminal attached,
so the interactive password prompting works and the help layout adapts to the terminal.

Logging: the library is logging-free (the domain returns results and errors); the CLI
reports through `java.util.logging` (verbosity via the common options), and so does the
GUI placeholder.

Dependency versions live in `gradle/libs.versions.toml`; the JavaFX version is pinned
there too. The toolchain major version is pinned as `bitferry.java` in `gradle.properties`,
and the exact Zulu build used for cross-build jmods is pinned in `bitferry-app/build.gradle`
(which verifies the two agree — the exact update build of the auto-provisioned toolchain
is whatever matching Zulu the foojay resolver returns).
