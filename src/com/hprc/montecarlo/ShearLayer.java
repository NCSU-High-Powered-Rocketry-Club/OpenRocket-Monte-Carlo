package com.hprc.montecarlo;

/**
 * Smooth wind shear layer: below layer => ~0 delta, above layer => ~deltaTop.
 * Uses tanh step vs altitude for numerical smoothness.
 */
final class ShearLayer {
    final double centerAlt_m;
    final double thickness_m;
    final Vec2 deltaTop; // (u,v) m/s

    ShearLayer(double centerAlt_m, double thickness_m, Vec2 deltaTop) {
        this.centerAlt_m = centerAlt_m;
        this.thickness_m = Math.max(1.0, thickness_m);
        this.deltaTop = deltaTop != null ? deltaTop : new Vec2(0, 0);
    }

    Vec2 deltaAtAltitude(double alt_m) {
        double half = 0.5 * thickness_m;
        if (half <= 0) return new Vec2(0, 0);

        // s ~ 0 below, s ~ 1 above
        double s = 0.5 * (1.0 + Math.tanh((alt_m - centerAlt_m) / half));
        return deltaTop.scale(s);
    }
}
