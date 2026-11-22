# Bitferry - file synchronization/backup automation

<div align="right"><i>Ein Backup ist kein Backup</i></div><br><br>

Bitferry is a task-based file synchronization and backup automation tool built on top of [Rclone](https://rclone.org) and [Restic](https://restic.net) utilities.

What makes Bitferry different from just running the backend tools directly is the way it tracks the data locations.

The core idea that drives Bitferry is the conversion of full (absolute) endpoints' paths into the volume-relative ones, where the volume is a data file which is put along the endpoint's data and denotes the root of the directory hierarchy. This makes data position-independent which means that Bitferry is then able to restore the tasks' source-destination endpoint connections in spite of the volume location changes, which is a likely scenario in case of portable storage (different UNIX mount points, Windows drives etc.).


## Use cases

* Maintain an update-only files copy in a separate location on the same site

* Maintain offline secure two way file synchronization between two offsites

* Maintain an incremental files backup on a portable medium with multiple offsite copies of the repository


## Features

* Multiplatform (Windows / UNIX) operation

* Automated task-based data processing

* One way / two way data synchronization

* Recursive directory copy / update / synchronize

* Incremental directory backup with snapshotting and retention

* File/repository password-based end-to-end encryption

* Command line and graphical frontends

* Operating system keyring / per-volume vault password storage

* Online cloud storage relay

* Offline portable storage (USB flash, HDDs, SSDs etc.) relay with the automatic media change detection


## Purpose

Suppose you have a (large amount of) data which needs to be either distributed across many storages or simply backed up. For example, consider a terabyte private media archive one can not afford to lose.

As the data gets periodically updated, there is a need for regular synchronization. When the use of online cloud storage is not an option due to storage space or security reasons, the good ol' offline backing up comes back into play.

A sane backup strategy mandates the data copies to be physically separated - be it a next room (building, city or planet) computer or just an external drive. Or, even better, the two computers' storages - a primary, where all activity takes place, a mirror storage which holds the backup, and a portable storage (USB flash disk, external HDD or SSD - whatever) which serves as both an intermediate storage and a means of propagating the changes between the primary and the mirror.

In a more complex scenario there may be multiple one-way or two-way point-to-point data transfer routes between the storages, employing the portable storage as a "shuttle" or a "ferry" - which is exactly where the Bitferry name comes from.

This time-honored practice of moving data by physically carrying it around is affectionately known as the [sneakernet](https://en.wikipedia.org/wiki/Sneakernet). Its most cinematic incarnation is perhaps the 1995 cyberpunk movie *[Johnny Mnemonic](https://en.wikipedia.org/wiki/Johnny_Mnemonic_(film))*, where the protagonist is a human data courier carrying sensitive information in his head - well, Bitferry does essentially the same, except it is a file on a USB stick rather than an overloaded brain.

All in all, every such data route boils down to copying or synchronizing the contents of two local directories or maintaining an incremental backup of them. However, since the portable storage is involved, the actual file paths may change between the runs as a storage can be mounted under different mount points on *NIX systems or change the disk drive on Windows systems. Besides, the portable storage may be compromised while carrying confidential data which calls for the end-to-end encryption on the data routes.

While the [Rclone](https://rclone.org) and [Restic](https://restic.net) themselves are great tools for local file synchronization and backup, typing the command lines to be executed in this case becomes tedious and error prone where the possible cost of error is a backup corruption due to wrong paths or misspelled flags.

This is where the [Bitferry](https://github.com/okhlybov/bitferry) comes in.
It is designed to automatize the Rclone and Restic operations by memorizing the task definitions and detecting the proper source and destination locations wherever they are, regardless of where the involved storages appear the next time.


## Implementation

The Bitferry itself is written in the [Java](https://openjdk.org) programming language organized as a set of the Java platform modules: the core library along with the two frontends - the command line interface and the graphical one. Being a Java code, the Bitferry requires the platform-specific Java runtime, version 25 or higher, which however is bundled with every packaged distribution so no separate runtime installation is needed.

The source code is hosted on [GitHub](https://github.com/okhlybov/bitferry) and the binary releases in form of the platform-specific packages (the Windows MSI installer, the Linux AppImage, the portable zip/tar.gz archives and the plain runtime image trees) are produced by the [Gradle](https://gradle.org) build.

In addition, the platform-specific [Rclone](https://github.com/rclone/rclone/releases) and [Restic](https://github.com/restic/restic/releases) executables ship inside the packaged distributions. The standalone deployments resolve them through the `RCLONE` and `RESTIC` environment variables or through the `PATH` directory list.


## Kickstart

### Install Bitferry

Download the release package for your platform from the [releases](https://github.com/okhlybov/bitferry/releases) page and install it:

* Windows -- run the MSI installer. It installs the application for the current user (no administrator rights required), puts the `bitferry` command line launcher on the user `PATH` and adds the Start Menu shortcut launching the graphical frontend

* Linux -- make the downloaded AppImage executable and run it (just don't forget to make it executable with `chmod +x` before running). The optional `install` argument integrates it with the desktop environment and the `PATH` (the `uninstall` argument undoes that), while running the AppImage directly keeps working as is

* The other platforms -- unpack the runtime image tree and run the `bitferry` launcher from its `bin` directory

Every package carries its own Java runtime along with the bundled backend tools, so nothing else needs to be installed. The following examples assume the `bitferry` launcher is accessible through the `PATH` directory list.

### Run the graphical frontend

* Windows -- launch the `Bitferry` Start Menu shortcut, or run `bitferry-gui` from the command line

* Linux -- launch the `Bitferry` desktop menu entry (created by the AppImage `install`), run the AppImage with the `--gui` argument, or run `bitferry --gui` from the command line

### Prepare source Bitferry volume for a mounted local filesystem

```shell
bitferry create volume /data
```

### Prepare destination Bitferry volume for a mounted portable storage

```shell
bitferry create volume /mnt/usb-drive
```

The volumes are discovered automatically upon the start at the mounted filesystem roots, in the user's home directory and in the additional locations maintained through the GUI or the `BITFERRY_PATH` environment variable.

### Ensure the volumes are intact

```shell
bitferry
```

```
# Intact volumes
  71410d3d    /data
  db43d979    /mnt/usb-drive
```

### Create a (Rclone) replication task

```shell
bitferry create task replicate /data /mnt/usb-drive/backup
```

The task family offers the copy, update, replicate (one way synchronization) and equalize (two way synchronization) operations on top of Rclone along with the incremental backup and restore ones on top of Restic. Passing the `-e`/`--encrypt` flag encrypts the destination with the password prompted interactively.

### Review the changes

```shell
bitferry
```

```
# Intact volumes
  71410d3d    /data
  db43d979    /mnt/usb-drive

# Intact tasks
  80709087    replicate :71410d3d: --> :db43d979:backup
```

### Perform a dry run of the specific task

```shell
bitferry execute -vn 807
```

Here `-n` requests the dry run (make no on-disk changes) and `-v` produces the verbose output; `807` is the partial task tag matched against the full `80709087` one.

<details>
<summary>...</summary>

```
[1/1] 80709087  replicate :71410d3d: --> :db43d979:backup
rclone sync --filter "- .bitferry*" --filter "- .bitferry*/**" --config /dev/null --verbose --progress --dry-run --metadata /data /mnt/usb-drive/backup
2026/11/22 10:55:23 NOTICE: LICENSE: Skipped copy as --dry-run is set (size 14)
2026/11/22 10:55:23 NOTICE: NOTES.txt: Skipped copy as --dry-run is set (size 15)
2026/11/22 10:55:23 NOTICE: README.md: Skipped copy as --dry-run is set (size 30)
Transferred:            3 / 3, 100%
Elapsed time:         0.0s

    ok success

All 1 task(s) executed successfully.
```

</details>

### Process all intact tasks in sequence

```shell
bitferry execute -v
```

<details>
<summary>...</summary>

```
[1/1] 80709087  replicate :71410d3d: --> :db43d979:backup
rclone sync --filter "- .bitferry*" --filter "- .bitferry*/**" --config /dev/null --verbose --progress --metadata /data /mnt/usb-drive/backup
2026/11/22 10:55:26 INFO  : LICENSE: Copied (new)
2026/11/22 10:55:26 INFO  : NOTES.txt: Copied (new)
2026/11/22 10:55:26 INFO  : README.md: Copied (new)
Transferred:            3 / 3, 100%
Elapsed time:         0.0s

    ok success

All 1 task(s) executed successfully.
```

</details>

### Observe the result

```shell
ls -l /mnt/usb-drive/backup
```

<details>
<summary>...</summary>

```
-rw-r--r-- 1 user user 14 nov 22 10:55 LICENSE
-rw-r--r-- 1 user user 15 nov 22 10:55 NOTES.txt
-rw-r--r-- 1 user user 30 nov 22 10:55 README.md
```

</details>

### Examine the detailed usage instructions

```shell
bitferry --help
```

<details>
<summary>...</summary>

```
Usage: bitferry [-hnqvV] [--debug] [--force] [COMMAND]
File synchronization/backup automation tool.
      --debug     Be extra verbose (produce the most output including exception
                    traces).
      --force     Allow potentially dangerous operations.
  -h, --help      Show this help message and exit.
  -n, --dry-run   Make no on-disk changes (simulation mode).
  -q, --quiet     Be quiet (produce the least output).
  -v, --verbose   Be verbose (produce more output).
  -V, --version   Print version information and exit.
Commands:
  info, i     Print information on available volumes and tasks.
  create, c   Create volume/task entity.
  delete, d   Delete volume/task entities.
  modify, m   Modify volume/task entities.
  execute, x  Execute tasks matched by specified (partial) tags.
  serve, s    Perform service tasks (integrity check, retention, pruning) on
                matched tasks or repositories.
  repair      Execute backend repair operations on the matched tasks (e.g.
                removing the stale repository locks).
```

</details>

## The rest is about to come

*Cheers!*

Oleg A. Khlybov <fougas@mail.ru>
