#!/bin/sh
# Build the CLI and run it from the local build tree (the installed distribution).
# The arguments are passed through to the bitferry command line unchanged.
#
# Running the installed launcher keeps the real terminal attached (unlike the gradle
# run task, whose daemon stdio breaks the password prompting and the terminal width
# detection), so the interactive flows work as intended.

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -- )

"$DIR/gradlew" -p "$DIR" --console=plain -q :bitferry-cli:installDist || exit 1

exec "$DIR/bitferry-cli/build/install/bitferry/bin/bitferry" "$@"
