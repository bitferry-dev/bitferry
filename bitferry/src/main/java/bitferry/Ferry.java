package bitferry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The bitferry: dispatches {@link Cargo} across a {@link Route} and issues a {@link Manifest}
 * for every crossing. All activity is logged through SLF4J, so any frontend (cli, gui) gets
 * consistent logging by simply providing an SLF4J backend on the module path.
 */
public final class Ferry {
    private static final Logger log = LoggerFactory.getLogger(Ferry.class);

    /** Simulated crossing time per 100 kg of cargo, in milliseconds. */
    private static final long MILLIS_PER_100_KG = 5;

    /** Upper bound for a single simulated crossing, in milliseconds. */
    private static final long MAX_CROSSING_MILLIS = 250;

    public Manifest dispatch(Cargo cargo, Route route) {
        log.info("Loading {} for the {} leg", cargo, route);
        Instant departedAt = Instant.now();
        cross(cargo);
        Instant arrivedAt = Instant.now();
        Manifest manifest = new Manifest(cargo, route, departedAt, arrivedAt,
                UUID.randomUUID().toString().substring(0, 8));
        log.debug("Crossing complete: {}", manifest);
        log.info("Unloaded {} at {} under manifest {}", cargo.id(), route.destination(), manifest.manifestId());
        return manifest;
    }

    public List<Manifest> dispatchAll(List<Cargo> cargos, Route route) {
        log.info("Fleet of {} cargo departing on the {} route", cargos.size(), route);
        List<Manifest> manifests = new ArrayList<>(cargos.size());
        for (Cargo cargo : cargos) {
            manifests.add(dispatch(cargo, route));
        }
        log.info("Fleet of {} cargo delivered across {}", manifests.size(), route);
        return List.copyOf(manifests);
    }

    private static void cross(Cargo cargo) {
        try {
            Thread.sleep(Math.min(MAX_CROSSING_MILLIS, 10 + cargo.weightKg() / 100 * MILLIS_PER_100_KG));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
