@echo off
rem bitferry-gui launcher for the distro jar archive; the packager must place their
rem OpenJFX jars into the lib dir (or extend the module path via JAVA_OPTS).
set DIR=%~dp0
if defined JAVA_HOME (set "JAVACMD=%JAVA_HOME%\bin\java.exe") else (set "JAVACMD=java")
"%JAVACMD%" %JAVA_OPTS% --enable-native-access=javafx.graphics --module-path "%DIR%..\lib" -m bitferry.gui/bitferry.gui.BitferryGui %*
