@echo off
rem bitferry CLI launcher for the distro jar archive; engines travel via the
rem RCLONE/RESTIC environment variables.
set DIR=%~dp0
if defined JAVA_HOME (set "JAVACMD=%JAVA_HOME%\bin\java.exe") else (set "JAVACMD=java")
"%JAVACMD%" %JAVA_OPTS% --module-path "%DIR%..\lib" -m bitferry.cli/bitferry.cli.BitferryCli %*
