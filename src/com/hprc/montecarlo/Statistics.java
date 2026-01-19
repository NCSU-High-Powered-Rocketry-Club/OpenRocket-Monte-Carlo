package com.hprc.montecarlo;

import java.util.Collection;

public class Statistics {
    public static class Sample {
        private final double mean;
        private final double standardDeviation;

        private Sample(double mean, double standardDeviation) {
            this.mean = mean;
            this.standardDeviation = standardDeviation;
        }

        /**
         * Returns the mean value of the data
         */
        public double getMean() {
            return mean;
        }

        /**
         * Returns the standard devation of the data
         */
        public double getStandardDeviation() {
            return standardDeviation;
        }

        @Override
        public String toString() {
            return "mean " + mean + " stddev " + standardDeviation;
        }
    }

    /**
     * Calculates Mean and StdDev in a single pass to reduce memory allocation overhead.
     * Uses a shifted-sum approach to maintain numerical stability.
     */
    public static Sample calculateSample(Collection<Double> values) {
        if (values == null || values.size() < 2) {
            throw new IllegalArgumentException("At least 2 values must be provided");
        }

        double n = 0;
        double sum = 0.0;
        double sumSq = 0.0;
        Double first = null;

        // One-pass calculation with shift to avoid catastrophic cancellation on large values
        for (Double v : values) {
            if (v == null || !Double.isFinite(v)) continue;
            
            // Use the first non-null value as the offset/shift
            if (first == null) {
                first = v;
            }

            double d = v - first;
            sum += d;
            sumSq += d * d;
            n++;
        }

        if (n < 2) {
             // Fallback or throw if almost all values were null/NaN
             throw new IllegalArgumentException("At least 2 valid finite values must be provided");
        }

        // Reconstruct mean from offset
        double mean = first + (sum / n);
        
        // Variance = (SumSq - (Sum^2)/n) / (n - 1)
        double variance = (sumSq - (sum * sum) / n) / (n - 1.0);
        
        // StdDev
        double standardDeviation = Math.sqrt(Math.max(0.0, variance));

        return new Sample(mean, standardDeviation);
    }
}
