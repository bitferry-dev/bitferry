package io.bitferry.task;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

/// The include-filter capability mixin: the tasks filtering their content by inclusion
/// declare the include state and expose it through this interface. The rclone-backed
/// operations ([Rclone] family) and the restic restore ([Restore]) carry the capability;
/// the restic backup ([Backup]) omits it structurally - restic backup filters by
/// exclusion only, so the tasks of that operation hold neither the include state nor
/// the accessors, and the capability test is the plain type check (no runtime predicates).
///
/// <p>The interface carries the whole behavior (the unmodifiable view, the mutation
/// bodies, the descriptor writer) as the default methods over the single abstract
/// [includeState] accessor - the backing mutable list the carrying classes expose.
/// Java interfaces hold no instance state, so the list field stays with the classes;
/// every implementer is a [Task], which the mutation bookkeeping relies on (the
/// {@code touch()} coupling through the same-package cast).
public interface IncludeFilters {

  /// The include patterns (unmodifiable view).
  default List<String> include() {
    return Collections.unmodifiableList(includeState());
  }

  /// Replace the include patterns (marks the task out-of-date).
  default void setInclude(Collection<String> include) {
    List<String> state = includeState();
    state.clear();
    if (include != null) {
      state.addAll(include);
    }
    ((Task) this).touch();
  }

  /// Drop the include patterns (marks the task out-of-date).
  default void clearInclude() {
    includeState().clear();
    ((Task) this).touch();
  }

  /// The descriptor form: omitted when empty (the mapper's NON_NULL inclusion).
  @JsonProperty("include")
  default List<String> storeInclude() {
    return includeState().isEmpty() ? null : includeState();
  }

  /// The backing mutable storage: the carrying classes expose their list field here.
  List<String> includeState();
}
