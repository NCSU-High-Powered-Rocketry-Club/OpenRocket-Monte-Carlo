package com.hprc.montecarlo;

/**
 * A single smooth gust event, modeled as a 1-cosine pulse in time.
 *
 * delta(t) = peakDelta * 0.5*(1 - cos(2*pi*tau/T)), for tau in [0,T]
 */
final class GustEvent {
    final double t0_s;
    final double duration_s;
    final Vec2 peakDelta; // (u,v) m/s

    GustEvent(double t0_s, double duration_s, Vec2 peakDelta) {
        this.t0_s = t0_s;
        this.duration_s = Math.max(0.01, duration_s);
        this.peakDelta = peakDelta != null ? peakDelta : new Vec2(0, 0);
    }

    Vec2 deltaAtTime(double t_s) {
        double tau = t_s - t0_s;
        if (tau < 0.0 || tau > duration_s) return new Vec2(0, 0);

        // 0 -> 1 -> 0 smooth pulse
        double s = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * tau / duration_s));
        return peakDelta.scale(s);
    }
}
