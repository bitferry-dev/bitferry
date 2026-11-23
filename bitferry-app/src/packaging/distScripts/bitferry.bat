@echo off
rem bitferry CLI launcher for the distro jar archive; engines travel via the
rem RCLONE/RESTIC environment variables.
set DIR=%~dp0
if defined JAVA_HOME (set "JAVACMD=%JAVA_HOME%\bin\java.exe") else (set "JAVACMD=java")
rem The jansi console and the JNA dispatch natives extract into the archive's
rem lib\tmp subdir (stable across the temp cleanings, immune to the noexec system
rem temp; kept off the lib dir itself - the extraction cleanup sweeps would treat
rem the jars as stale debris, jansi-*/jna-* matching their own module jars)
"%JAVACMD%" %JAVA_OPTS% --enable-native-access=org.fusesource.jansi,com.sun.jna "-Djansi.tmpdir=%DIR%..\lib\tmp" "-Djna.tmpdir=%DIR%..\lib\tmp" --module-path "%DIR%..\lib" -m org.bitferry.cli/org.bitferry.cli.Main %*
