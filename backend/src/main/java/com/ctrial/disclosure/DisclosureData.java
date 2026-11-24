package com.ctrial.disclosure;

import com.ctrial.model.LocalAggregateFact;
import com.ctrial.model.DisclosureSurface;
import com.ctrial.repo.LocalAggregateFactRepo;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Read-only access to the <b>local domain</b> aggregate facts. Everything that
 * needs true counts or denominators goes through here; the attacker-facing
 * simulator deliberately does not.
 */
@Service
public class DisclosureData {

    private final LocalAggregateFactRepo factRepo;

    public DisclosureData(LocalAggregateFactRepo factRepo) {
        this.factRepo = factRepo;
    }

    public List<LocalAggregateFact> facts() {
        return factRepo.findAllByOrderByCenterAscAgeBandAscPeriodAsc();
    }

    /** Enrolled denominator per atom at {@code analysisDay}. */
    public Map<String, Integer> denominators(int analysisDay) {
        return CellSpace.denominators(facts(), analysisDay);
    }

    public Map<String, Integer> trueCounts(int analysisDay) {
        return CellSpace.trueCounts(facts(), analysisDay);
    }

    /** Denominators for a set of atoms at a day; missing atoms map to 0. */
    public Map<String, Integer> denomFor(Collection<String> atoms, int analysisDay) {
        Map<String, Integer> all = denominators(analysisDay);
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String a : atoms) {
            out.put(a, all.getOrDefault(a, 0));
        }
        return out;
    }

    /**
     * Publicly-known enrollment upper bound per atom. The attacker is told only
     * the per-centre enrollment scale; we spread that over a centre's atoms as
     * a generous (safe) upper bound so the solver never assumes more knowledge
     * than the public record supports.
     */
    public Map<String, Integer> publicUpperBounds(DisclosureSurface surface) {
        Map<String, Integer> upper = new LinkedHashMap<>();
        Map<String, Integer> centreEnrollment = parseCentreEnrollment(surface.getPublicEnrollment());
        for (CellSpace.Atom a : CellSpace.atoms()) {
            int centreTotal = centreEnrollment.getOrDefault(a.center(), 0);
            upper.put(a.key(), Math.max(centreTotal, 0));
        }
        return upper;
    }

    public static Map<String, Integer> parseCentreEnrollment(String csv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(";")) {
            String[] kv = part.split("=");
            if (kv.length == 2) {
                try {
                    out.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                } catch (NumberFormatException ignored) {
                    // skip malformed entries
                }
            }
        }
        return out;
    }

    public static String centreEnrollmentCsv(Map<String, Integer> m) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : new TreeMap<>(m).entrySet()) {
            parts.add(e.getKey() + "=" + e.getValue());
        }
        return String.join(";", parts);
    }
}
