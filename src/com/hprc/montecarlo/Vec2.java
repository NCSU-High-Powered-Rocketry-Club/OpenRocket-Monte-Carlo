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

    // ---------------------------------------------------------------------
    // Angle conventions
    // ---------------------------------------------------------------------

    /**
     * Convert OpenRocket's scalar wind (speed, direction) into an ENU vector.
     *
     * OpenRocket convention (per UI/docs):
     *  - direction is where the wind is COMING FROM
     *  - angle is in radians, 0 = North, pi/2 = East, increasing clockwise
     *
     * This returns the wind VELOCITY vector (where the air is moving TO) in ENU:
     *  u = East (m/s), v = North (m/s)
     */
    static Vec2 fromOpenRocketWind(double speed, double windFromDirRad) {
        // Wind comes FROM dirFromRad, therefore it blows TOWARD dirFromRad + pi.
        // Compass bearing uses: east = s*sin(bearing), north = s*cos(bearing)
        double east = -speed * Math.sin(windFromDirRad);
        double north = -speed * Math.cos(windFromDirRad);
        return new Vec2(east, north);
    }

    /**
     * Convert an ENU wind velocity vector into OpenRocket's (speed, direction-from).
     * @return speed>=0 and dirFromRad wrapped to [0, 2pi)
     */
    static OpenRocketWind toOpenRocketWind(Vec2 windToENU) {
        if (windToENU == null) return new OpenRocketWind(0.0, 0.0);

        double speed = windToENU.mag();
        if (speed <= 0.0 || !Double.isFinite(speed)) return new OpenRocketWind(0.0, 0.0);

        // bearing_to: 0=N, pi/2=E, clockwise; for ENU, bearing_to = atan2(east, north)
        double bearingTo = Math.atan2(windToENU.u, windToENU.v);
        double dirFrom = wrapRad(bearingTo - Math.PI);
        return new OpenRocketWind(speed, dirFrom);
    }

    static double wrapRad(double a) {
        double x = a;
        while (x < 0.0) x += 2.0 * Math.PI;
        while (x >= 2.0 * Math.PI) x -= 2.0 * Math.PI;
        return x;
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

    /** OpenRocket scalar wind: speed + "coming from" direction (compass radians). */
    static final class OpenRocketWind {
        final double speed;
        final double windFromDirRad;

        OpenRocketWind(double speed, double windFromDirRad) {
            this.speed = speed;
            this.windFromDirRad = windFromDirRad;
        }
    }
}
