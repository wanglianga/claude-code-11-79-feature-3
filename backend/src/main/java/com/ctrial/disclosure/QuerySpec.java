package com.ctrial.disclosure;

import com.ctrial.model.DisclosureSurface;

import java.time.Instant;
import java.util.*;

/**
 * One aggregate request, before it is turned into a release. Everything that
 * defines "which table am I asking for" lives here: the analysis surface, the
 * grouping dimensions, the centre and period scope, the mechanism and the
 * route. Two requests are the *same analysis face* iff their {@code faceKey}
 * is equal; the release ledger uses that to decide duplicate vs increment.
 */
public record QuerySpec(
        String surfaceCode,
        List<String> dimensions,
        String centerGranularity,   // COARSE | FINE
        List<String> centers,       // subset of the surface's centres; null = all
        List<String> periods,       // subset; null = all
        String metric,              // SERIOUS_CASE_COUNT
        String mechanism,           // SUPPRESSION | ROUNDING | LAPLACE | CONSISTENT_RANDOM
        String route,               // RESTRICTED_LATTICE | OVERLAPPING_LEDGER
        String label                // free-form human label for the sandbox / ledger
) {

    public QuerySpec {
        dimensions = dimensions == null ? List.of() : dimensions.stream()
                .map(String::toUpperCase).distinct().sorted().toList();
        metric = metric == null || metric.isBlank() ? "SERIOUS_CASE_COUNT" : metric.toUpperCase();
        centerGranularity = centerGranularity == null ? "COARSE" : centerGranularity.toUpperCase();
        mechanism = mechanism == null ? "SUPPRESSION" : mechanism.toUpperCase();
        route = route == null ? "OVERLAPPING_LEDGER" : route.toUpperCase();
    }

    public boolean has(String dim) {
        return dimensions.contains(dim);
    }

    /** Canonical identity of the analysis face; deliberately excludes mechanism so a re-mechanised query collides. */
    public String faceKey(DisclosureSurface surface) {
        return surface.getDefinitionDigest() + "#" + String.join(",", dimensions) + "#"
                + centerGranularity + "#" + centersCsv() + "#" + periodsCsv() + "#" + metric;
    }

    public String centersCsv() {
        return centers == null ? "ALL" : String.join(",", new TreeSet<>(centers));
    }

    public String periodsCsv() {
        return periods == null ? "ALL" : String.join(",", new TreeSet<>(periods));
    }

    /** The canonical filter stored in the ledger; no subject-level clause ever appears here. */
    public String filterCanonical() {
        return "centers=" + centersCsv() + "; periods=" + periodsCsv() + "; centerGranularity=" + centerGranularity
                + "; ageRecode=" + (route == null ? "" : "") + "; metric=" + metric;
    }

    public List<String> effectiveCenters(DisclosureSurface surface) {
        if (centers == null || centers.isEmpty()) {
            return surface.centerUniverse().stream().sorted().toList();
        }
        return centers.stream().sorted().toList();
    }

    public List<String> effectivePeriods(DisclosureSurface surface) {
        if (periods == null || periods.isEmpty()) {
            return surface.periodUniverse().stream().sorted().toList();
        }
        return periods.stream().sorted().toList();
    }

    public static Instant requestNow() {
        return Instant.now();
    }
}
