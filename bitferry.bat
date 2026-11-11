@echo off
rem Build the CLI and run it from the local build tree (the installed distribution).
rem The arguments are passed through to the bitferry command line unchanged.
rem
rem Running the installed launcher keeps the real console attached (unlike the
rem gradle run task, whose daemon stdio breaks the password prompting and the
rem terminal width detection), so the interactive flows work as intended.
setlocal
set "DIR=%~dp0"

call "%DIR%gradlew.bat" -p "%DIR%." --console=plain -q :bitferry-cli:installDist
if errorlevel 1 exit /b %errorlevel%

"%DIR%bitferry-cli\build\install\bitferry\bin\bitferry.bat" %*
