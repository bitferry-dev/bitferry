package bitferry;

/** The crossing served by the ferry. */
public record Route(String origin, String destination) {

    public Route {
        if (origin == null || origin.isBlank() || destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("route endpoints must not be blank");
        }
    }

    @Override
    public String toString() {
        return origin + "->" + destination;
    }
}
