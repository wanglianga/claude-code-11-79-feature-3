package com.ctrial.disclosure;

import org.apache.commons.math3.optim.PointValuePair;
import org.apache.commons.math3.optim.linear.*;
import org.apache.commons.math3.optim.nonlinear.scalar.GoalType;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Exact interval enumeration for a linear objective over non-negative integer
 * unknowns constrained by linear equations. This is the engine behind the
 * differencing attack simulator and the constraint-group tracker.
 *
 * <p>Every equation has the form {@code sum(coeff[v]*x[v]) = rhs} with an
 * explicit {@code tolerance} (the uncertainty a published mechanism admits).
 * Each variable is a non-negative integer with a public upper bound. To bound an
 * objective we minimise and maximise it over the feasible polytope; with
 * integral data the LP optimum is integral, so no separate integer step is
 * needed.
 *
 * <p>This class only ever receives equations, public bounds and an objective. It
 * has no access to true counts.
 */
@Component
public class IntervalSolver {

    public record Equation(String source, Map<String, Integer> coeffs, double rhs, double tolerance,
                           boolean publicTotal) {
    }

    public record Interval(double lo, double hi) {
        public double width() {
            return hi - lo;
        }
    }

    public Optional<Interval> interval(List<Equation> equations, Map<String, Integer> upper, String target) {
        return bounds(equations, upper, Map.of(target, 1));
    }

    /** Feasible range of {@code sum(objective[v]*x[v])}, clamped to non-negative integers. */
    public Optional<Interval> bounds(List<Equation> equations, Map<String, Integer> upper,
                                     Map<String, Integer> objective) {
        LinkedHashSet<String> vars = new LinkedHashSet<>(objective.keySet());
        for (Equation e : equations) {
            vars.addAll(e.coeffs().keySet());
        }
        List<String> index = new ArrayList<>(vars);
        int n = index.size();
        Map<String, Integer> pos = new HashMap<>();
        for (int i = 0; i < n; i++) {
            pos.put(index.get(i), i);
        }
        double[] obj = new double[n];
        for (Map.Entry<String, Integer> e : objective.entrySet()) {
            Integer p = pos.get(e.getKey());
            if (p != null) {
                obj[p] += e.getValue();
            }
        }

        Collection<LinearConstraint> constraints = new ArrayList<>();
        for (String v : index) {
            double ub = upper.getOrDefault(v, Integer.MAX_VALUE);
            double[] unit = new double[n];
            unit[pos.get(v)] = 1.0;
            constraints.add(new LinearConstraint(unit, Relationship.LEQ, ub));
        }
        for (Equation e : equations) {
            double[] coeff = new double[n];
            for (Map.Entry<String, Integer> c : e.coeffs().entrySet()) {
                Integer p = pos.get(c.getKey());
                if (p != null) {
                    coeff[p] += c.getValue();
                }
            }
            double tol = Math.max(0.0, e.tolerance());
            constraints.add(new LinearConstraint(coeff, Relationship.LEQ, e.rhs() + tol + 1e-6));
            constraints.add(new LinearConstraint(coeff, Relationship.GEQ, e.rhs() - tol - 1e-6));
        }
        LinearObjectiveFunction fn = new LinearObjectiveFunction(obj, 0.0);
        SimplexSolver solver = new SimplexSolver(1e-9, 200, 1e-9);
        try {
            PointValuePair hi = solver.optimize(fn, new LinearConstraintSet(constraints),
                    GoalType.MAXIMIZE, null);
            PointValuePair lo = solver.optimize(fn, new LinearConstraintSet(constraints),
                    GoalType.MINIMIZE, null);
            double hiV = Math.floor(hi.getValue() + 1e-6);
            double loV = Math.max(Math.ceil(lo.getValue() - 1e-6), 0);
            if (loV > hiV) {
                loV = Math.max(lo.getValue(), 0);
                hiV = Math.max(hi.getValue(), loV);
            }
            return Optional.of(new Interval(loV, hiV));
        } catch (RuntimeException ex) {
            // infeasible or unbounded: the published subset is not jointly satisfiable
            return Optional.empty();
        }
    }
}
