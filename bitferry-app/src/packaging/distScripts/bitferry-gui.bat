@echo off
rem bitferry-gui launcher for the distro jar archive; the packager must place their
rem OpenJFX jars into the lib dir (or extend the module path via JAVA_OPTS).
set DIR=%~dp0
if defined JAVA_HOME (set "JAVACMD=%JAVA_HOME%\bin\java.exe") else (set "JAVACMD=java")
rem The JavaFX graphics, the jansi console and the JNA dispatch natives extract into
rem (or load from) the archive's lib\tmp subdir (see the CLI launcher note)
"%JAVACMD%" %JAVA_OPTS% --enable-native-access=javafx.graphics,org.fusesource.jansi,com.sun.jna "-Djansi.tmpdir=%DIR%..\lib\tmp" "-Djna.tmpdir=%DIR%..\lib\tmp" --module-path "%DIR%..\lib" -m io.bitferry.gui/io.bitferry.gui.Main %*
