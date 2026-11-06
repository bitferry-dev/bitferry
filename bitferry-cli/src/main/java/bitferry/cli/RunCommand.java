package bitferry.cli;

import bitferry.Cargo;
import bitferry.Ferry;
import bitferry.Manifest;
import bitferry.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
        name = "run",
        mixinStandardHelpOptions = true,
        description = "Dispatch cargo from origin to destination and print the manifests.")
public final class RunCommand implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(RunCommand.class);

    private static final List<String> DEFAULT_CARGO = List.of("amphorae:250:olive oil", "ingots:700:bronze", "silk:60");

    @Option(names = {"--from"}, required = true, description = "Origin dock.")
    String origin;

    @Option(names = {"--to"}, required = true, description = "Destination dock.")
    String destination;

    @Option(names = {"--cargo"}, split = ",",
            description = "Cargo as id[:weightKg[:description]]. Repeatable, or comma-separated.")
    List<String> cargoSpecs = new ArrayList<>(DEFAULT_CARGO);

    @Override
    public Integer call() {
        Route route = new Route(origin, destination);
        List<Cargo> cargos = cargoSpecs.stream().map(RunCommand::parseCargo).toList();
        log.info("Dispatching {} cargo across {}", cargos.size(), route);

        Ferry ferry = new Ferry();
        List<Manifest> manifests = ferry.dispatchAll(cargos, route);

        manifests.forEach(m -> System.out.printf("  %s%n", m.summary()));
        System.out.printf("Delivered %d cargo across %s in %s total.%n",
                manifests.size(), route,
                manifests.stream().reduce(java.time.Duration.ZERO,
                        (acc, m) -> acc.plus(m.crossingDuration()), java.time.Duration::plus));
        return 0;
    }

    private static Cargo parseCargo(String spec) {
        String[] parts = spec.split(":", 3);
        String id = parts[0];
        int weight = parts.length > 1 && !parts[1].isBlank() ? Integer.parseInt(parts[1]) : 100;
        String description = parts.length > 2 ? parts[2] : "";
        return new Cargo(id, description, weight);
    }
}
