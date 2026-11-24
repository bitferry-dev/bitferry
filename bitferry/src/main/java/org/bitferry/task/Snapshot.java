package org.bitferry.task;

import java.util.List;

/// A snapshot descriptor returned by restic backend repository inspection.
public record Snapshot(
  String id,
  String shortId,
  String time,
  List<String> paths,
  String hostname,
  String username,
  List<String> tags
) {
  public Snapshot {
    paths = paths != null ? List.copyOf(paths) : List.of();
    tags = tags != null ? List.copyOf(tags) : List.of();
  }
}
