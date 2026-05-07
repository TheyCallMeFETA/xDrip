package com.eveningoutpost.dexdrip.utilitymodels;

import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.Iob;
import com.eveningoutpost.dexdrip.models.Profile;
import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.models.UserError;

import java.util.List;

/**
 * Estimates carbohydrate intake by reverse-engineering observed blood glucose changes.
 *
 * Formula:
 *   glucose_rise_from_carbs = observed_delta_mmol + (iob_start - iob_end) * sensitivity
 *   estimated_carbs = glucose_rise_from_carbs * (carb_ratio / sensitivity)
 */
public class CarbEstimator {

    private static final String TAG = CarbEstimator.class.getSimpleName();

    public static class Result {
        public final double estimatedCarbs;
        public final double estimatedCarbsNoIob;
        public final double glucoseDeltaMgdl;
        public final double glucoseDeltaMmol;
        public final double iobAtStart;
        public final double iobAtEnd;
        public final double bgAtStart;
        public final double bgAtEnd;
        public final String errorMessage;

        Result(double estimatedCarbs, double estimatedCarbsNoIob,
               double glucoseDeltaMgdl, double glucoseDeltaMmol,
               double iobAtStart, double iobAtEnd,
               double bgAtStart, double bgAtEnd) {
            this.estimatedCarbs = estimatedCarbs;
            this.estimatedCarbsNoIob = estimatedCarbsNoIob;
            this.glucoseDeltaMgdl = glucoseDeltaMgdl;
            this.glucoseDeltaMmol = glucoseDeltaMmol;
            this.iobAtStart = iobAtStart;
            this.iobAtEnd = iobAtEnd;
            this.bgAtStart = bgAtStart;
            this.bgAtEnd = bgAtEnd;
            this.errorMessage = null;
        }

        Result(String errorMessage) {
            this.errorMessage = errorMessage;
            this.estimatedCarbs = 0;
            this.estimatedCarbsNoIob = 0;
            this.glucoseDeltaMgdl = 0;
            this.glucoseDeltaMmol = 0;
            this.iobAtStart = 0;
            this.iobAtEnd = 0;
            this.bgAtStart = 0;
            this.bgAtEnd = 0;
        }

        public boolean hasError() {
            return errorMessage != null;
        }
    }

    /**
     * Estimates carbs consumed between startTime and now.
     *
     * @param startTime  epoch milliseconds for the start of the window
     * @return Result containing estimated carbs and breakdown details
     */
    public static Result estimate(long startTime) {
        return estimate(startTime, System.currentTimeMillis());
    }

    /**
     * Estimates carbs consumed between startTime and endTime.
     *
     * @param startTime epoch milliseconds for start of window
     * @param endTime   epoch milliseconds for end of window
     * @return Result containing estimated carbs and breakdown details
     */
    public static Result estimate(long startTime, long endTime) {
        // Need at least 10 minutes of data
        if (endTime - startTime < 10 * 60 * 1000) {
            return new Result("Time window too short — need at least 10 minutes");
        }

        // Find BG readings bracketing the start time (within 15 min tolerance)
        final long tolerance = 15 * 60 * 1000L;
        BgReading startReading = BgReading.getForPreciseTimestamp(startTime, tolerance);
        if (startReading == null) {
            // Fall back to closest available reading before startTime
            List<BgReading> candidates = BgReading.latestForGraph(1, startTime - tolerance, startTime + tolerance);
            if (candidates != null && !candidates.isEmpty()) {
                startReading = candidates.get(0);
            }
        }

        BgReading endReading = BgReading.getForPreciseTimestamp(endTime, tolerance);
        if (endReading == null) {
            List<BgReading> candidates = BgReading.latestForGraph(1, endTime - tolerance, endTime + tolerance);
            if (candidates != null && !candidates.isEmpty()) {
                endReading = candidates.get(0);
            }
        }

        if (startReading == null) {
            return new Result("No glucose reading found near the start time");
        }
        if (endReading == null) {
            return new Result("No glucose reading found near the end time");
        }
        if (startReading.timestamp >= endReading.timestamp) {
            return new Result("Start reading is not before end reading");
        }

        final double bgStart = startReading.calculated_value; // mg/dL
        final double bgEnd = endReading.calculated_value;     // mg/dL
        final double deltaMgdl = bgEnd - bgStart;
        final double deltaMmol = deltaMgdl * Constants.MGDL_TO_MMOLL;

        // Simple estimate without IOB adjustment
        final double carbSensitivity = Profile.getCarbSensitivity(startTime); // g per mmol/L
        final double carbsNoIob = deltaMmol * carbSensitivity;

        // Compute IOB at start and end from treatment history
        final double iobStart = calculateTotalIob(startReading.timestamp);
        final double iobEnd = calculateTotalIob(endReading.timestamp);

        // Insulin that acted during window lowered BG; add it back to find carb-driven rise
        final double sensitivity = Profile.getSensitivity(startTime); // mmol/L per unit
        final double iobDelta = iobStart - iobEnd; // units of insulin absorbed during window
        final double insulinEffect = iobDelta * sensitivity; // mmol/L that insulin would have lowered BG

        final double adjustedDeltaMmol = deltaMmol + insulinEffect;
        final double estimatedCarbs = adjustedDeltaMmol * carbSensitivity;

        UserError.Log.d(TAG, String.format(
                "BG %.1f→%.1f mg/dL (Δ%.1f), IOB %.2f→%.2f U, ins effect %.2f mmol, carbs ~%.1fg (no-IOB: %.1fg)",
                bgStart, bgEnd, deltaMgdl, iobStart, iobEnd, insulinEffect, estimatedCarbs, carbsNoIob));

        return new Result(estimatedCarbs, carbsNoIob,
                deltaMgdl, deltaMmol,
                iobStart, iobEnd,
                bgStart, bgEnd);
    }

    /**
     * Sums all treatment IOB contributions at the given timestamp.
     */
    private static double calculateTotalIob(long atTime) {
        final long lookback = 10 * 60 * 60 * 1000L; // 10 hours
        List<Treatments> treatments = Treatments.latestForGraph(500, atTime - lookback, atTime + 60 * 1000L);
        if (treatments == null) return 0;
        double total = 0;
        for (Treatments t : treatments) {
            if (t.insulin > 0 && t.timestamp <= atTime) {
                Iob iob = Treatments.calcTreatmentPublic(t, atTime);
                if (iob != null) {
                    total += iob.iob;
                }
            }
        }
        return Math.max(0, total);
    }
}
