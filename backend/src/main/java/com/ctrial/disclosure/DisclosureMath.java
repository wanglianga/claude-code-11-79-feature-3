package com.ctrial.disclosure;

import com.ctrial.common.Text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Small deterministic primitives for disclosure control. Everything here is
 * seeded from content (never from wall-clock time) so that the same input
 * always yields the same output — a hard requirement for reproducible audit
 * and for <b>consistent</b> perturbation across surface versions.
 */
public final class DisclosureMath {

    private DisclosureMath() {
    }

    /** Deterministic double in [0,1) derived from a string seed. */
    public static double unit(String seed) {
        byte[] h = hash(seed);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (h[i] & 0xffL);
        }
        return ((v >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    /** Deterministic standard Laplace sample (differences of two exponentials), bounded for display. */
    public static double laplace(String seed, double scale) {
        double u = unit(seed) - 0.5;
        double sign = u < 0 ? -1 : 1;
        double mag = -scale * Math.log(1 - 2 * Math.abs(u));
        double v = sign * mag;
        return Math.max(-3 * scale, Math.min(3 * scale, v));
    }

    /** Round half-up to the nearest multiple of {@code unit}. */
    public static int roundToUnit(int value, int unit) {
        if (unit <= 1) {
            return value;
        }
        double q = value / (double) unit;
        return (int) Math.round(q) * unit;
    }

    public static byte[] hash(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha(String s) {
        return Text.sha256(s);
    }
}
