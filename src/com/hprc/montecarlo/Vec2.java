package com.hprc.montecarlo;

/**
 * Minimal 2D vector helper used for wind delta math (u,v) in m/s.
 *
 * We intentionally keep this tiny to avoid pulling in OR's Coordinate/Vector types.
 */
final class Vec2 {
    final double u;
    final double v;

    Vec2(double u, double v) {
        this.u = u;
        this.v = v;
    }

    Vec2 add(Vec2 o) {
        return new Vec2(this.u + o.u, this.v + o.v);
    }

    Vec2 scale(double s) {
        return new Vec2(this.u * s, this.v * s);
    }

    double mag() {
        return Math.sqrt(u * u + v * v);
    }

    static Vec2 fromPolar(double speed, double dirRad) {
        return new Vec2(speed * Math.cos(dirRad), speed * Math.sin(dirRad));
    }

    static Polar toPolar(Vec2 w) {
        double speed = w.mag();
        double dir = Math.atan2(w.v, w.u);
        return new Polar(speed, dir);
    }

    static final class Polar {
        final double speed;
        final double dirRad;
        Polar(double speed, double dirRad) {
            this.speed = speed;
            this.dirRad = dirRad;
        }
    }
}
