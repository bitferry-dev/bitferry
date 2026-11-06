package bitferry;

import java.time.Duration;
import java.time.Instant;

/** Proof of a completed crossing for one {@link Cargo} item. */
public record Manifest(Cargo cargo, Route route, Instant departedAt, Instant arrivedAt, String manifestId) {

    public Manifest {
        if (manifestId == null || manifestId.isBlank()) {
            throw new IllegalArgumentException("manifest id must not be blank");
        }
    }

    public Duration crossingDuration() {
        return Duration.between(departedAt, arrivedAt);
    }

    public String summary() {
        return "%s %-12s %s took %s".formatted(route(), manifestId(), cargo().id(), crossingDuration());
    }

    @Override
    public String toString() {
        return "Manifest[" + summary() + "]";
    }
}
