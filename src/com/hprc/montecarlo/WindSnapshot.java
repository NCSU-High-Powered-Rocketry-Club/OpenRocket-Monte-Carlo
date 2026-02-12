package com.hprc.montecarlo;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.SimulationOptions;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures the baseline wind state (scalar or multi-level) so gust/shear deltas can be
 * applied every simulation step and then restored at the end.
 *
 * IMPORTANT:
 *  - This mutates the wind model in-place; it is only safe because MonteCarloBatchRunner
 *    deep-copies wind objects for each cloned simulation run.
 *  - Reflection is used so the plugin tolerates OpenRocket API differences.
 */
final class WindSnapshot {

    private final SimulationConditions conditions;
    private final SimulationOptions options;
    private final Object windModel;

    final boolean multiLevelActive;

    private final ScalarSnapshot scalar;
    private final List<MLModelSnapshot> mlModels;

    private WindSnapshot(SimulationConditions conditions,
                         SimulationOptions options,
                         Object windModel,
                         boolean multiLevelActive,
                         ScalarSnapshot scalar,
                         List<MLModelSnapshot> mlModels) {
        this.conditions = conditions;
        this.options = options;
        this.windModel = windModel;
        this.multiLevelActive = multiLevelActive;
        this.scalar = scalar;
        this.mlModels = mlModels;
    }

    static WindSnapshot capture(SimulationConditions conditions, SimulationOptions opts) {
        Object wm = resolveWindModel(conditions, opts);
        boolean multi = isMultiLevelActive(conditions, opts, wm);

        // Multi-level snapshots (potentially multiple distinct objects)
        List<MLModelSnapshot> mlSnaps = new ArrayList<>();
        if (multi) {
            Map<MultiLevelPinkNoiseWindModel, Boolean> seen = new IdentityHashMap<>();

            if (wm instanceof MultiLevelPinkNoiseWindModel ml && !seen.containsKey(ml)) {
                seen.put(ml, Boolean.TRUE);
                mlSnaps.add(new MLModelSnapshot(ml));
            }

            Object mlCondObj = invokeObject(conditions, "getMultiLevelWindModel");
            if (mlCondObj instanceof MultiLevelPinkNoiseWindModel ml && !seen.containsKey(ml)) {
                seen.put(ml, Boolean.TRUE);
                mlSnaps.add(new MLModelSnapshot(ml));
            }

            MultiLevelPinkNoiseWindModel mlOpts = null;
            if (opts != null) {
                try {
                    mlOpts = opts.getMultiLevelWindModel();
                } catch (Throwable ignored) {
                }
            }
            if (mlOpts != null && !seen.containsKey(mlOpts)) {
                seen.put(mlOpts, Boolean.TRUE);
                mlSnaps.add(new MLModelSnapshot(mlOpts));
            }
        }

        ScalarSnapshot scalarSnap = null;
        if (!multi) {
            double baseSpeed = readScalarSpeed(conditions, opts, wm);
            double baseDir = readScalarDirection(conditions, opts, wm);
            double baseStd = readScalarStdDev(conditions, opts, wm);
            scalarSnap = new ScalarSnapshot(baseSpeed, baseDir, baseStd);
        }

        return new WindSnapshot(conditions, opts, wm, multi, scalarSnap, mlSnaps);
    }

    void applyDelta(RunWindDisturbanceProfile profile, double t_s, double rocketAlt_m) {
        if (profile == null) return;

        if (multiLevelActive) {
            for (MLModelSnapshot snap : mlModels) {
                snap.apply(profile, t_s);
            }
        } else if (scalar != null) {
            Vec2 delta = profile.deltaWindXY(t_s, rocketAlt_m);

            Vec2 base = Vec2.fromPolar(scalar.baseSpeed_mps, scalar.baseDir_rad);
            Vec2 total = base.add(delta);
            Vec2.Polar p = Vec2.toPolar(total);

            double speed = Math.max(0.0, p.speed);
            double dir = p.dirRad;

            setScalarWindSpeedEverywhere(conditions, options, windModel, speed);
            setScalarWindDirectionEverywhere(conditions, options, windModel, dir);

            // Keep baseline turbulence std-dev unchanged
            if (Double.isFinite(scalar.baseStdDev_mps)) {
                setScalarWindStdDevEverywhere(conditions, options, windModel, scalar.baseStdDev_mps);
            }
        }
    }

    void restore() {
        if (multiLevelActive) {
            for (MLModelSnapshot snap : mlModels) {
                snap.restore();
            }
        } else if (scalar != null) {
            setScalarWindSpeedEverywhere(conditions, options, windModel, scalar.baseSpeed_mps);
            setScalarWindDirectionEverywhere(conditions, options, windModel, scalar.baseDir_rad);
            if (Double.isFinite(scalar.baseStdDev_mps)) {
                setScalarWindStdDevEverywhere(conditions, options, windModel, scalar.baseStdDev_mps);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Multi-level snapshot
    // ---------------------------------------------------------------------

    private static final class MLModelSnapshot {
        final MultiLevelPinkNoiseWindModel model;
        final List<LevelBaseline> levels = new ArrayList<>();

        MLModelSnapshot(MultiLevelPinkNoiseWindModel model) {
            this.model = model;
            if (model != null) {
                for (MultiLevelPinkNoiseWindModel.LevelWindModel lvl : model.getLevels()) {
                    levels.add(new LevelBaseline(lvl));
                }
            }
        }

        void apply(RunWindDisturbanceProfile profile, double t_s) {
            if (model == null || profile == null) return;

            for (LevelBaseline b : levels) {
                double alt = b.altitude_m;

                Vec2 base = Vec2.fromPolar(b.baseSpeed_mps, b.baseDir_rad);
                Vec2 delta = profile.deltaWindXY(t_s, alt);
                Vec2 total = base.add(delta);
                Vec2.Polar p = Vec2.toPolar(total);

                double speed = Math.max(0.0, p.speed);
                double dir = p.dirRad;

                b.level.setSpeed(speed);
                b.level.setDirection(dir);

                // Keep turbulence std-dev unchanged
                if (Double.isFinite(b.baseStdDev_mps)) {
                    try {
                        b.level.setStandardDeviation(Math.max(0.0, b.baseStdDev_mps));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        void restore() {
            if (model == null) return;

            for (LevelBaseline b : levels) {
                b.level.setSpeed(Math.max(0.0, b.baseSpeed_mps));
                b.level.setDirection(b.baseDir_rad);
                if (Double.isFinite(b.baseStdDev_mps)) {
                    try {
                        b.level.setStandardDeviation(Math.max(0.0, b.baseStdDev_mps));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private static final class LevelBaseline {
            final MultiLevelPinkNoiseWindModel.LevelWindModel level;
            final double altitude_m;
            final double baseSpeed_mps;
            final double baseDir_rad;
            final double baseStdDev_mps;

            LevelBaseline(MultiLevelPinkNoiseWindModel.LevelWindModel lvl) {
                this.level = lvl;
                this.altitude_m = invokeDouble(lvl, "getAltitude", 0.0);
                this.baseSpeed_mps = invokeDouble(lvl, "getSpeed", 0.0);
                this.baseDir_rad = invokeDouble(lvl, "getDirection", 0.0);
                this.baseStdDev_mps = safeStdDev(lvl);
            }

            private static double safeStdDev(MultiLevelPinkNoiseWindModel.LevelWindModel lvl) {
                try {
                    return lvl.getStandardDeviation();
                } catch (Throwable ignored) {
                    return Double.NaN;
                }
            }
        }
    }

    private static final class ScalarSnapshot {
        final double baseSpeed_mps;
        final double baseDir_rad;
        final double baseStdDev_mps;

        ScalarSnapshot(double baseSpeed_mps, double baseDir_rad, double baseStdDev_mps) {
            this.baseSpeed_mps = baseSpeed_mps;
            this.baseDir_rad = baseDir_rad;
            this.baseStdDev_mps = baseStdDev_mps;
        }
    }

    // ---------------------------------------------------------------------
    // Wind model discovery
    // ---------------------------------------------------------------------

    private static Object resolveWindModel(SimulationConditions conditions, SimulationOptions opts) {
        Object wm = invokeObject(conditions, "getWindModel");
        if (wm != null) return wm;
        return invokeObject(opts, "getWindModel");
    }

    private static boolean isMultiLevelActive(SimulationConditions conditions, SimulationOptions opts, Object windModel) {
        if (windModel instanceof MultiLevelPinkNoiseWindModel) return true;

        Object t = invokeObject(conditions, "getWindModelType");
        if (t == null) t = invokeObject(opts, "getWindModelType");
        if (t == null) return false;

        String name = String.valueOf(t).toLowerCase();
        return name.contains("multi") || name.contains("level");
    }

    // ---------------------------------------------------------------------
    // Scalar getters (reflection-safe)
    // ---------------------------------------------------------------------

    private static double readScalarSpeed(SimulationConditions cond, SimulationOptions opts, Object model) {
        double v = invokeDouble(model, "getWindSpeed", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(model, "getSpeed", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(model, "getAverageWindSpeed", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(model, "getAverageWindspeed", Double.NaN);

        if (!Double.isFinite(v)) v = invokeDouble(cond, "getWindSpeed", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(cond, "getAverageWindSpeed", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(opts, "getWindSpeed", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(opts, "getAverageWindSpeed", Double.NaN);

        if (!Double.isFinite(v) || v < 0.0) v = 0.0;
        return v;
    }

    private static double readScalarDirection(SimulationConditions cond, SimulationOptions opts, Object model) {
        double v = invokeDouble(model, "getWindDirection", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(model, "getDirection", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(cond, "getWindDirection", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(opts, "getWindDirection", Double.NaN);
        if (!Double.isFinite(v)) v = 0.0;
        return v;
    }

    private static double readScalarStdDev(SimulationConditions cond, SimulationOptions opts, Object model) {
        double v = invokeDouble(model, "getWindStandardDeviation", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(model, "getWindSpeedStandardDeviation", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(model, "getStandardDeviation", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(model, "getStdDev", Double.NaN);

        if (!Double.isFinite(v)) v = invokeDouble(cond, "getWindStandardDeviation", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(cond, "getWindSpeedStandardDeviation", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(opts, "getWindStandardDeviation", Double.NaN);
        if (!Double.isFinite(v)) v = invokeDouble(opts, "getWindSpeedStandardDeviation", Double.NaN);

        if (!Double.isFinite(v) || v < 0.0) v = 0.0;
        return v;
    }

    // ---------------------------------------------------------------------
    // Scalar setters (reflection-safe)
    // ---------------------------------------------------------------------

    private static void setScalarWindSpeedEverywhere(SimulationConditions cond, SimulationOptions opts, Object model, double speedMps) {
        tryInvokeVoidDouble(cond, "setWindSpeed", speedMps);
        tryInvokeVoidDouble(cond, "setAverageWindSpeed", speedMps);
        tryInvokeVoidDouble(cond, "setAverageWindspeed", speedMps);

        tryInvokeVoidDouble(opts, "setWindSpeed", speedMps);
        tryInvokeVoidDouble(opts, "setAverageWindSpeed", speedMps);
        tryInvokeVoidDouble(opts, "setAverageWindspeed", speedMps);

        if (model != null) {
            tryInvokeVoidDouble(model, "setWindSpeed", speedMps);
            tryInvokeVoidDouble(model, "setSpeed", speedMps);
            tryInvokeVoidDouble(model, "setAverageWindSpeed", speedMps);
            tryInvokeVoidDouble(model, "setAverageWindspeed", speedMps);
            tryInvokeVoidDouble(model, "setMeanWindSpeed", speedMps);
        }
    }

    private static void setScalarWindStdDevEverywhere(SimulationConditions cond, SimulationOptions opts, Object model, double stdMps) {
        tryInvokeVoidDouble(cond, "setWindStandardDeviation", stdMps);
        tryInvokeVoidDouble(cond, "setWindSpeedStandardDeviation", stdMps);
        tryInvokeVoidDouble(cond, "setStandardDeviation", stdMps);
        tryInvokeVoidDouble(cond, "setStdDev", stdMps);
        tryInvokeVoidDouble(cond, "setTurbulence", stdMps);

        tryInvokeVoidDouble(opts, "setWindStandardDeviation", stdMps);
        tryInvokeVoidDouble(opts, "setWindSpeedStandardDeviation", stdMps);
        tryInvokeVoidDouble(opts, "setStandardDeviation", stdMps);
        tryInvokeVoidDouble(opts, "setStdDev", stdMps);
        tryInvokeVoidDouble(opts, "setTurbulence", stdMps);

        if (model != null) {
            tryInvokeVoidDouble(model, "setWindStandardDeviation", stdMps);
            tryInvokeVoidDouble(model, "setStandardDeviation", stdMps);
            tryInvokeVoidDouble(model, "setStdDev", stdMps);
            tryInvokeVoidDouble(model, "setTurbulence", stdMps);
        }
    }

    private static void setScalarWindDirectionEverywhere(SimulationConditions cond, SimulationOptions opts, Object model, double dirRad) {
        tryInvokeVoidDouble(cond, "setWindDirection", dirRad);
        tryInvokeVoidDouble(cond, "setWindDirectionRad", dirRad);
        tryInvokeVoidDouble(cond, "setDirection", dirRad);

        tryInvokeVoidDouble(opts, "setWindDirection", dirRad);
        tryInvokeVoidDouble(opts, "setWindDirectionRad", dirRad);
        tryInvokeVoidDouble(opts, "setDirection", dirRad);

        if (model != null) {
            tryInvokeVoidDouble(model, "setWindDirection", dirRad);
            tryInvokeVoidDouble(model, "setDirection", dirRad);
        }
    }

    // ---------------------------------------------------------------------
    // Reflection helpers
    // ---------------------------------------------------------------------

    private static Object invokeObject(Object target, String methodName) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(methodName);
            return m.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static double invokeDouble(Object target, String methodName, double fallback) {
        if (target == null) return fallback;
        try {
            Method m = target.getClass().getMethod(methodName);
            Object v = m.invoke(target);
            if (v instanceof Number n) return n.doubleValue();
            return fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static boolean tryInvokeVoidDouble(Object target, String methodName, double value) {
        if (target == null) return false;
        try {
            Method m = target.getClass().getMethod(methodName, double.class);
            m.invoke(target, value);
            return true;
        } catch (Exception ignored) {
            try {
                Method m = target.getClass().getMethod(methodName, Double.class);
                m.invoke(target, value);
                return true;
            } catch (Exception ignored2) {
                return false;
            }
        }
    }
}
