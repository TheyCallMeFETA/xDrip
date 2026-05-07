package com.eveningoutpost.dexdrip.utilitymodels;

import com.eveningoutpost.dexdrip.models.BgReading;
import com.eveningoutpost.dexdrip.models.Iob;
import com.eveningoutpost.dexdrip.models.Profile;
import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.models.UserError;
import com.eveningoutpost.dexdrip.profileeditor.ProfileEditor;
import com.eveningoutpost.dexdrip.profileeditor.ProfileItem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Analyses historical treatments and BG data to suggest adjustments to
 * Insulin Sensitivity Factor (ISF) and Insulin-to-Carb Ratio (ICR).
 *
 * ISF method  — finds insulin-only correction boluses, measures the actual
 *               BG nadir achieved, and derives an implied ISF per event.
 *
 * ICR method  — finds carb treatments, measures the actual BG peak rise
 *               (adding back any co-dosed insulin effect), and derives an
 *               implied ICR per meal.
 *
 * Both analyses use the median of collected samples and cap any suggested
 * change to ±MAX_CHANGE_FRACTION to limit risk.
 */
public class ProfileOptimizer {

    private static final String TAG = ProfileOptimizer.class.getSimpleName();

    /** Minimum samples before we will emit a suggestion. */
    public static final int MIN_SAMPLES = 3;
    /** Hard cap: a single optimisation step cannot move a value by more than this fraction. */
    public static final double MAX_CHANGE_FRACTION = 0.25;
    /** Minimum carbs (g) for a meal event to be included. */
    private static final double MIN_CARBS_G = 5.0;
    /** Minimum insulin (U) for a correction event to be included. */
    private static final double MIN_CORRECTION_UNITS = 0.3;
    /** Tolerance when looking for a BG reading near a treatment timestamp. */
    private static final long BG_TOLERANCE_MS = 20 * 60 * 1000L;
    /** Look-ahead window used when searching for post-meal peak / post-correction nadir. */
    private static final long RESPONSE_WINDOW_MS = 4 * 60 * 60 * 1000L;
    /** If IOB at the time of a correction is above this, skip it (confounding). */
    private static final double MAX_CONFOUNDING_IOB = 0.5;

    // -------------------------------------------------------------------------
    // Public result types
    // -------------------------------------------------------------------------

    public static class Suggestion {
        public final double currentValue;
        public final double suggestedValue;
        public final double percentChange;
        public final int sampleCount;
        public final String unit;
        public final boolean hasEnoughData;

        Suggestion(double currentValue, double suggestedValue, int sampleCount, String unit) {
            this.currentValue = currentValue;
            this.suggestedValue = suggestedValue;
            this.percentChange = currentValue > 0
                    ? (suggestedValue - currentValue) / currentValue * 100.0
                    : 0;
            this.sampleCount = sampleCount;
            this.unit = unit;
            this.hasEnoughData = sampleCount >= MIN_SAMPLES;
        }

        public boolean isIncrease() { return suggestedValue > currentValue; }
    }

    // -------------------------------------------------------------------------
    // ISF analysis
    // -------------------------------------------------------------------------

    /**
     * Scans the last {@code days} days of insulin-only corrections, computes the
     * implied ISF for each, and returns a suggestion capped at ±25 %.
     */
    public static Suggestion analyzeIsf(int days) {
        final long endTime = System.currentTimeMillis();
        final long startTime = endTime - (long) days * 24 * 60 * 60 * 1000L;
        final boolean doMgdl = Pref.getString("units", "mgdl").equals("mgdl");
        final double currentIsf = Profile.getSensitivity(endTime);
        final String isfUnit = doMgdl ? "mg/dL/U" : "mmol/L/U";

        List<Double> impliedIsfList = new ArrayList<>();

        List<Treatments> treatments = Treatments.latestForGraph(2000, startTime, endTime);
        if (treatments == null || treatments.isEmpty()) {
            return new Suggestion(currentIsf, currentIsf, 0, isfUnit);
        }

        for (Treatments t : treatments) {
            if (t.insulin <= MIN_CORRECTION_UNITS) continue;
            if (t.carbs > 0) continue; // skip mixed — carbs confound the drop

            // Skip if there was a recent carb treatment that would confound
            if (hasRecentCarbs(treatments, t.timestamp, 90 * 60 * 1000L)) continue;

            // Skip if IOB was already significant before this bolus (stacked)
            double iobBefore = totalIobAt(treatments, t.timestamp - 60 * 1000L);
            if (iobBefore > MAX_CONFOUNDING_IOB) continue;

            // Find the BG reading at bolus time
            double bgStart = bgNear(t.timestamp);
            if (bgStart < 0) continue;

            // Find BG nadir in the following response window
            double bgNadir = bgNadir(t.timestamp, t.timestamp + RESPONSE_WINDOW_MS);
            if (bgNadir < 0) continue;

            double bgDrop = bgStart - bgNadir; // mg/dL
            if (bgDrop < 10) continue; // BG barely moved — noise or food interference

            // Convert drop to profile units
            double dropInUnits = doMgdl ? bgDrop : bgDrop * Constants.MGDL_TO_MMOLL;
            double impliedIsf = dropInUnits / t.insulin;

            UserError.Log.d(TAG, String.format(
                    "ISF sample: drop=%.1f %s over %.2fU → implied ISF=%.2f",
                    dropInUnits, isfUnit, t.insulin, impliedIsf));

            impliedIsfList.add(impliedIsf);
        }

        if (impliedIsfList.isEmpty()) {
            return new Suggestion(currentIsf, currentIsf, 0, isfUnit);
        }

        double medianIsf = median(impliedIsfList);
        double capped = capChange(currentIsf, medianIsf);

        UserError.Log.i(TAG, String.format(
                "ISF analysis: %d samples, median=%.2f, current=%.2f, suggested=%.2f",
                impliedIsfList.size(), medianIsf, currentIsf, capped));

        return new Suggestion(currentIsf, capped, impliedIsfList.size(), isfUnit);
    }

    // -------------------------------------------------------------------------
    // ICR analysis
    // -------------------------------------------------------------------------

    /**
     * Scans the last {@code days} days of meal treatments, computes the implied
     * ICR for each, and returns a suggestion capped at ±25 %.
     */
    public static Suggestion analyzeIcr(int days) {
        final long endTime = System.currentTimeMillis();
        final long startTime = endTime - (long) days * 24 * 60 * 60 * 1000L;
        final boolean doMgdl = Pref.getString("units", "mgdl").equals("mgdl");
        final double currentIcr = Profile.getCarbRatio(endTime);
        final double currentIsf = Profile.getSensitivity(endTime);

        List<Double> impliedIcrList = new ArrayList<>();

        List<Treatments> treatments = Treatments.latestForGraph(2000, startTime, endTime);
        if (treatments == null || treatments.isEmpty()) {
            return new Suggestion(currentIcr, currentIcr, 0, "g/U");
        }

        for (Treatments t : treatments) {
            if (t.carbs < MIN_CARBS_G) continue;

            double bgStart = bgNear(t.timestamp);
            if (bgStart < 0) continue;

            // Find BG peak in the following response window
            double bgPeak = bgPeak(t.timestamp, t.timestamp + RESPONSE_WINDOW_MS);
            if (bgPeak < 0) continue;

            double bgRiseMgdl = bgPeak - bgStart;

            // Add back the BG suppression from any co-dosed insulin (ISF × insulin)
            // so we see the carb-only rise.  currentIsf is in profile units.
            double insulinSuppression; // in mg/dL
            if (doMgdl) {
                insulinSuppression = t.insulin * currentIsf;
            } else {
                insulinSuppression = t.insulin * currentIsf / Constants.MGDL_TO_MMOLL;
            }
            double carbOnlyRiseMgdl = bgRiseMgdl + insulinSuppression;

            if (carbOnlyRiseMgdl < 10) continue; // net rise too small to be reliable

            // Convert rise to profile units for consistency with sensitivity
            double riseInProfileUnits = doMgdl
                    ? carbOnlyRiseMgdl
                    : carbOnlyRiseMgdl * Constants.MGDL_TO_MMOLL;

            // implied ICR = carbs / (rise / ISF) = carbs * ISF / rise
            double impliedIcr = t.carbs * currentIsf / riseInProfileUnits;

            UserError.Log.d(TAG, String.format(
                    "ICR sample: carbs=%.1fg, rise=%.1f (carb-only), implied ICR=%.2f g/U",
                    t.carbs, riseInProfileUnits, impliedIcr));

            impliedIcrList.add(impliedIcr);
        }

        if (impliedIcrList.isEmpty()) {
            return new Suggestion(currentIcr, currentIcr, 0, "g/U");
        }

        double medianIcr = median(impliedIcrList);
        double capped = capChange(currentIcr, medianIcr);

        UserError.Log.i(TAG, String.format(
                "ICR analysis: %d samples, median=%.2f, current=%.2f, suggested=%.2f",
                impliedIcrList.size(), medianIcr, currentIcr, capped));

        return new Suggestion(currentIcr, capped, impliedIcrList.size(), "g/U");
    }

    // -------------------------------------------------------------------------
    // Profile update helpers
    // -------------------------------------------------------------------------

    /** Applies a new ISF to every time block in the active profile. */
    public static void applyIsfToAllBlocks(double newIsf) {
        applyToAllBlocks(newIsf, false);
    }

    /** Applies a new ICR to every time block in the active profile. */
    public static void applyIcrToAllBlocks(double newIcr) {
        applyToAllBlocks(newIcr, true);
    }

    private static void applyToAllBlocks(double newValue, boolean isIcr) {
        List<ProfileItem> profile = ProfileEditor.loadData(false);
        if (profile == null || profile.isEmpty()) return;

        for (ProfileItem item : profile) {
            if (isIcr) {
                item.carb_ratio = round1dp(newValue);
            } else {
                item.sensitivity = round1dp(newValue);
            }
        }

        Gson gson = new GsonBuilder()
                .excludeFieldsWithoutExposeAnnotation()
                .serializeSpecialFloatingPointValues()
                .create();
        ProfileEditor.saveProfileJson(gson.toJson(profile));
        Profile.invalidateProfile();

        UserError.Log.i(TAG, String.format(
                "Applied %s = %.2f to %d profile time block(s)",
                isIcr ? "ICR" : "ISF", newValue, profile.size()));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Returns the nearest BG reading (mg/dL) within BG_TOLERANCE_MS, or -1. */
    private static double bgNear(long timestamp) {
        List<BgReading> readings = BgReading.latestForGraph(
                1, timestamp - BG_TOLERANCE_MS, timestamp + BG_TOLERANCE_MS);
        if (readings == null || readings.isEmpty()) return -1;
        return readings.get(0).calculated_value;
    }

    /** Returns the lowest BG (mg/dL) between startMs and endMs, or -1 if no data. */
    private static double bgNadir(long startMs, long endMs) {
        List<BgReading> readings = BgReading.latestForGraphAsc(500, startMs, endMs);
        if (readings == null || readings.size() < 2) return -1;
        double nadir = Double.MAX_VALUE;
        for (BgReading r : readings) nadir = Math.min(nadir, r.calculated_value);
        return nadir == Double.MAX_VALUE ? -1 : nadir;
    }

    /** Returns the highest BG (mg/dL) between startMs and endMs, or -1 if no data. */
    private static double bgPeak(long startMs, long endMs) {
        List<BgReading> readings = BgReading.latestForGraphAsc(500, startMs, endMs);
        if (readings == null || readings.size() < 2) return -1;
        double peak = -Double.MAX_VALUE;
        for (BgReading r : readings) peak = Math.max(peak, r.calculated_value);
        return peak == -Double.MAX_VALUE ? -1 : peak;
    }

    /** Returns true if any carb treatment exists within {@code windowMs} before {@code timestamp}. */
    private static boolean hasRecentCarbs(List<Treatments> allTreatments,
                                          long timestamp, long windowMs) {
        for (Treatments t : allTreatments) {
            if (t.carbs > 0
                    && t.timestamp < timestamp
                    && t.timestamp > timestamp - windowMs) {
                return true;
            }
        }
        return false;
    }

    /** Sums IOB contributions of all treatments at {@code atTime}. */
    private static double totalIobAt(List<Treatments> treatments, long atTime) {
        double total = 0;
        for (Treatments t : treatments) {
            if (t.insulin > 0 && t.timestamp <= atTime) {
                Iob iob = Treatments.calcTreatmentPublic(t, atTime);
                if (iob != null) total += iob.iob;
            }
        }
        return Math.max(0, total);
    }

    /** Returns the median of a non-empty list. */
    static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
                : sorted.get(n / 2);
    }

    /**
     * Clamps {@code suggested} so it is never more than MAX_CHANGE_FRACTION
     * away from {@code current}.
     */
    static double capChange(double current, double suggested) {
        if (current <= 0) return suggested;
        double maxUp = current * (1 + MAX_CHANGE_FRACTION);
        double maxDown = current * (1 - MAX_CHANGE_FRACTION);
        return Math.max(maxDown, Math.min(maxUp, suggested));
    }

    private static double round1dp(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
