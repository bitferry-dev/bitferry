package org.bitferry.cli;

import java.lang.annotation.*;

/// Associates a command-line option with backend profile definitions for help rendering.
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface Profiles {

  /// The class declaring the static profiles map (e.g., Backup.class, Restore.class, Rclone.class).
  Class<?> value();

  /// The name of the static field or no-arg method on the source class providing the profiles map (defaults to "PROCESS").
  String field() default "PROCESS";

  /// Explicit profile names to include, in order. If empty, all non-null profiles in the map are included
  /// with "default" ordered first followed by other profiles in alphabetical order.
  String[] names() default {};
}
