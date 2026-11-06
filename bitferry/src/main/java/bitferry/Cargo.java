package bitferry;

/** An item carried across a {@link Route}. */
public record Cargo(String id, String description, int weightKg) {

    public Cargo {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("cargo id must not be blank");
        }
        if (weightKg < 0) {
            throw new IllegalArgumentException("cargo weight must not be negative: " + weightKg);
        }
    }

    @Override
    public String toString() {
        return id + " (" + weightKg + " kg" + (description == null || description.isBlank()
                ? "" : ", " + description) + ")";
    }
}
