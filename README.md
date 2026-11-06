# bitferry distribution

A Gradle build that assembles [bitferry](bitferry), a small JPMS library, plus two frontends —
a picocli CLI and a JavaFX GUI — into **one platform-specific runtime tree** with CLI and GUI
launchers, **AOT-trained** with the JDK 25 [AOT cache](https://openjdk.org/jeps/514) (Project Leyden).

- **Toolchain**: Java 25, Azul **Zulu** — auto-provisioned through the [foojay resolver](https://plugins.gradle.org/plugin/org.gradle.toolchains.foojay-resolver-convention); no local JDK 25 install needed.
- **Runtime images**: [beryx badass-jlink](https://github.com/beryx/badass-jlink-plugin) drives `jlink` and `jpackage`.
- **Packaging**: file trees only — a `jlink` tree and a `jpackage` **app-image** with native launchers. No installers, no archives.

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
./gradlew :bitferry-app:dist -Ptarget=windows-x64   # cross-build (see below)
```

`dist` for a native target produces:

```
bitferry-app/build/image/bitferry-<platform>/     # jlink tree
    bin/bitferry, bin/bitferry-gui                # launcher scripts (+ .bat on Windows)
    bin/java                                      # the runtime JVM
    lib/aot/cli.aot, lib/aot/gui.aot              # trained AOT caches

bitferry-app/build/jpackage/bitferry/             # jpackage app-image (native platform only)
    bin/bitferry, bin/bitferry-gui                # native launchers
    lib/runtime/…                                 # the jlink tree, caches included
    lib/aot/…                                     # caches mirrored for $APPDIR lookup
```

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

Two things cannot be cross-built and require running the build **on** the target platform:

- **AOT caches** — produced by executing the target JVM (`-Ptarget=native` on a Windows host trains them).
- **jpackage app-images** — `jpackage` must run on the target OS, so cross targets produce the jlink tree only.

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
