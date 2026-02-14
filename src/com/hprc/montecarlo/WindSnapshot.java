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

    /** Snapshots for the active multi-level wind model(s), if we can access them. */
    private final List<ModelSnapshot> mlModels;

    private WindSnapshot(SimulationConditions conditions,
                         SimulationOptions options,
                         Object windModel,
                         boolean multiLevelActive,
                         ScalarSnapshot scalar,
                         List<ModelSnapshot> mlModels) {
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
        List<ModelSnapshot> mlSnaps = new ArrayList<>();
        if (multi) {
            Map<Object, Boolean> seen = new IdentityHashMap<>();

            addMultiLevelSnapshotIfPossible(mlSnaps, seen, wm);
            addMultiLevelSnapshotIfPossible(mlSnaps, seen, invokeObject(conditions, "getMultiLevelWindModel"));
            addMultiLevelSnapshotIfPossible(mlSnaps, seen, invokeObject(opts, "getMultiLevelWindModel"));
        }

        // Always capture a scalar baseline if we can.  This gives us a fallback path for
        // multi-level wind profiles we cannot introspect/mutate at the level list.
        double baseSpeed = readScalarSpeed(conditions, opts, wm);
        double baseDir = readScalarDirection(conditions, opts, wm);
        double baseStd = readScalarStdDev(conditions, opts, wm);
        ScalarSnapshot scalarSnap = new ScalarSnapshot(baseSpeed, baseDir, baseStd);

        return new WindSnapshot(conditions, opts, wm, multi, scalarSnap, mlSnaps);
    }

    void applyDelta(RunWindDisturbanceProfile profile, double t_s, double rocketAlt_m) {
        if (profile == null) return;

        if (multiLevelActive && mlModels != null && !mlModels.isEmpty()) {
            for (ModelSnapshot snap : mlModels) {
                snap.apply(profile, t_s);
            }
            return;
        }

        // Scalar (or multi-level fallback)
        if (scalar != null) {
            Vec2 delta = profile.deltaWindXY(t_s, rocketAlt_m);

            Vec2 base = Vec2.fromOpenRocketWind(scalar.baseSpeed_mps, scalar.baseDir_rad);
            Vec2 total = base.add(delta);
            Vec2.OpenRocketWind p = Vec2.toOpenRocketWind(total);

            double speed = Math.max(0.0, p.speed);
            double dir = p.windFromDirRad;

            setScalarWindSpeedEverywhere(conditions, options, windModel, speed);
            setScalarWindDirectionEverywhere(conditions, options, windModel, dir);

            // Keep baseline turbulence std-dev unchanged
            if (Double.isFinite(scalar.baseStdDev_mps)) {
                setScalarWindStdDevEverywhere(conditions, options, windModel, scalar.baseStdDev_mps);
            }
        }
    }

    void restore() {
        if (multiLevelActive && mlModels != null && !mlModels.isEmpty()) {
            for (ModelSnapshot snap : mlModels) {
                snap.restore();
            }
            return;
        }

        if (scalar != null) {
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

    private static void addMultiLevelSnapshotIfPossible(
            List<ModelSnapshot> out,
            Map<Object, Boolean> seen,
            Object modelObj
    ) {
        if (out == null || seen == null || modelObj == null) return;
        if (seen.containsKey(modelObj)) return;
        seen.put(modelObj, Boolean.TRUE);

        if (modelObj instanceof MultiLevelPinkNoiseWindModel ml) {
            out.add(new MLModelSnapshot(ml));
            return;
        }

        GenericMLModelSnapshot g = new GenericMLModelSnapshot(modelObj);
        if (g.isUsable()) {
            out.add(g);
        }
    }

    private interface ModelSnapshot {
        void apply(RunWindDisturbanceProfile profile, double t_s);
        void restore();
    }

    private static final class MLModelSnapshot implements ModelSnapshot {
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

        @Override
        public void apply(RunWindDisturbanceProfile profile, double t_s) {
            if (model == null || profile == null) return;

            for (LevelBaseline b : levels) {
                double alt = b.altitude_m;

                Vec2 base = Vec2.fromOpenRocketWind(b.baseSpeed_mps, b.baseDir_rad);
                Vec2 delta = profile.deltaWindXY(t_s, alt);
                Vec2 total = base.add(delta);

                Vec2.OpenRocketWind p = Vec2.toOpenRocketWind(total);
                double speed = Math.max(0.0, p.speed);
                double dir = p.windFromDirRad;

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

        @Override
        public void restore() {
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

    /**
     * Generic multi-level wind model snapshot for imported profiles or other OR wind models.
     *
     * We look for a "getLevels"-like method on the wind model and "getAltitude/getSpeed/getDirection"
     * plus "setSpeed/setDirection" on each level object.  If any of these are missing, we simply
     * do not apply deltas for that model.
     */
    private static final class GenericMLModelSnapshot implements ModelSnapshot {
        final Object model;
        final List<GenericLevelBaseline> levels = new ArrayList<>();

        GenericMLModelSnapshot(Object model) {
            this.model = model;
            if (model == null) return;

            for (Object lvl : getLevelObjects(model)) {
                GenericLevelBaseline b = GenericLevelBaseline.tryCreate(lvl);
                if (b != null) levels.add(b);
            }
        }

        boolean isUsable() {
            return model != null && !levels.isEmpty();
        }

        @Override
        public void apply(RunWindDisturbanceProfile profile, double t_s) {
            if (profile == null || levels.isEmpty()) return;

            for (GenericLevelBaseline b : levels) {
                double alt = b.altitude_m;

                Vec2 base = Vec2.fromOpenRocketWind(b.baseSpeed_mps, b.baseDir_rad);
                Vec2 delta = profile.deltaWindXY(t_s, alt);
                Vec2 total = base.add(delta);

                Vec2.OpenRocketWind p = Vec2.toOpenRocketWind(total);
                double speed = Math.max(0.0, p.speed);
                double dir = p.windFromDirRad;

                b.setSpeed(speed);
                b.setDirection(dir);

                if (Double.isFinite(b.baseStdDev_mps)) {
                    b.setStdDev(Math.max(0.0, b.baseStdDev_mps));
                }
            }
        }

        @Override
        public void restore() {
            if (levels.isEmpty()) return;
            for (GenericLevelBaseline b : levels) {
                b.setSpeed(Math.max(0.0, b.baseSpeed_mps));
                b.setDirection(b.baseDir_rad);
                if (Double.isFinite(b.baseStdDev_mps)) {
                    b.setStdDev(Math.max(0.0, b.baseStdDev_mps));
                }
            }
        }

        private static List<Object> getLevelObjects(Object model) {
            if (model == null) return List.of();

            Object v = null;
            // common candidates
            v = invokeObject(model, "getLevels");
            if (v == null) v = invokeObject(model, "getWindLevels");
            if (v == null) v = invokeObject(model, "getLevelModels");
            if (v == null) v = invokeObject(model, "getWindLevelModels");

            List<Object> out = new ArrayList<>();
            if (v instanceof Iterable<?> it) {
                for (Object o : it) if (o != null) out.add(o);
            } else if (v != null && v.getClass().isArray()) {
                int n = java.lang.reflect.Array.getLength(v);
                for (int i = 0; i < n; i++) {
                    Object o = java.lang.reflect.Array.get(v, i);
                    if (o != null) out.add(o);
                }
            }
            return out;
        }

        private static final class GenericLevelBaseline {
            final Object level;
            final double altitude_m;
            final double baseSpeed_mps;
            final double baseDir_rad;
            final double baseStdDev_mps;

            GenericLevelBaseline(Object level, double altitude_m, double baseSpeed_mps, double baseDir_rad, double baseStdDev_mps) {
                this.level = level;
                this.altitude_m = altitude_m;
                this.baseSpeed_mps = baseSpeed_mps;
                this.baseDir_rad = baseDir_rad;
                this.baseStdDev_mps = baseStdDev_mps;
            }

            static GenericLevelBaseline tryCreate(Object lvl) {
                if (lvl == null) return null;

                // If we can't set speed/direction, this level isn't usable.
                boolean canSetSpeed = hasSetter(lvl, "setSpeed") || hasSetter(lvl, "setWindSpeed");
                boolean canSetDir = hasSetter(lvl, "setDirection") || hasSetter(lvl, "setWindDirection");
                if (!canSetSpeed || !canSetDir) return null;

                double alt = invokeDouble(lvl, "getAltitude", Double.NaN);
                if (!Double.isFinite(alt)) alt = invokeDouble(lvl, "getAltitude_m", Double.NaN);
                if (!Double.isFinite(alt)) alt = invokeDouble(lvl, "altitude", Double.NaN);
                if (!Double.isFinite(alt)) alt = 0.0;

                double spd = invokeDouble(lvl, "getSpeed", Double.NaN);
                if (!Double.isFinite(spd)) spd = invokeDouble(lvl, "getWindSpeed", Double.NaN);
                if (!Double.isFinite(spd)) spd = 0.0;

                double dir = invokeDouble(lvl, "getDirection", Double.NaN);
                if (!Double.isFinite(dir)) dir = invokeDouble(lvl, "getWindDirection", Double.NaN);
                if (!Double.isFinite(dir)) dir = 0.0;

                double std = invokeDouble(lvl, "getStandardDeviation", Double.NaN);
                if (!Double.isFinite(std)) std = invokeDouble(lvl, "getWindStandardDeviation", Double.NaN);
                if (!Double.isFinite(std)) std = Double.NaN;

                return new GenericLevelBaseline(lvl, alt, spd, dir, std);
            }

            void setSpeed(double speedMps) {
                if (!tryInvokeVoidDouble(level, "setSpeed", speedMps)) {
                    tryInvokeVoidDouble(level, "setWindSpeed", speedMps);
                }
            }

            void setDirection(double dirRad) {
                if (!tryInvokeVoidDouble(level, "setDirection", dirRad)) {
                    tryInvokeVoidDouble(level, "setWindDirection", dirRad);
                }
            }

            void setStdDev(double stdMps) {
                if (!tryInvokeVoidDouble(level, "setStandardDeviation", stdMps)) {
                    if (!tryInvokeVoidDouble(level, "setWindStandardDeviation", stdMps)) {
                        tryInvokeVoidDouble(level, "setStdDev", stdMps);
                    }
                }
            }

            private static boolean hasSetter(Object target, String methodName) {
                if (target == null) return false;
                try {
                    target.getClass().getMethod(methodName, double.class);
                    return true;
                } catch (Exception ignored) {
                    try {
                        target.getClass().getMethod(methodName, Double.class);
                        return true;
                    } catch (Exception ignored2) {
                        return false;
                    }
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

        // If a multi-level model object is present, treat as multi-level even if
        // WindModelType's toString() doesn't include "multi".
        if (invokeObject(conditions, "getMultiLevelWindModel") != null) return true;
        if (invokeObject(opts, "getMultiLevelWindModel") != null) return true;

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
