package com.eveningoutpost.dexdrip;

import android.app.AlertDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.eveningoutpost.dexdrip.models.Treatments;
import com.eveningoutpost.dexdrip.utilitymodels.CarbEstimator;
import com.eveningoutpost.dexdrip.utilitymodels.Constants;
import com.eveningoutpost.dexdrip.utilitymodels.Pref;

import java.text.DecimalFormat;

public class CarbEstimationActivity extends AppCompatActivity {

    private static final String TAG = CarbEstimationActivity.class.getSimpleName();

    // Look-back windows in hours corresponding to SeekBar positions 0–7
    private static final int[] HOURS = {1, 2, 3, 4, 5, 6, 8, 12};

    private final DecimalFormat df1 = new DecimalFormat("0.#");
    private final DecimalFormat df2 = new DecimalFormat("0.##");

    private SeekBar seekBar;
    private TextView windowValueText;
    private TextView startBgText;
    private TextView endBgText;
    private TextView deltaText;
    private TextView iobStartText;
    private TextView iobEndText;
    private TextView noIobText;
    private TextView withIobText;
    private TextView errorText;
    private Button logButton;

    private CarbEstimator.Result lastResult;
    private boolean doMgdl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_carb_estimation);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.carb_est_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        doMgdl = Pref.getString("units", "mgdl").equals("mgdl");

        seekBar = findViewById(R.id.carb_est_seekbar);
        windowValueText = findViewById(R.id.carb_est_window_value);
        startBgText = findViewById(R.id.carb_est_start_bg);
        endBgText = findViewById(R.id.carb_est_end_bg);
        deltaText = findViewById(R.id.carb_est_delta);
        iobStartText = findViewById(R.id.carb_est_iob_start);
        iobEndText = findViewById(R.id.carb_est_iob_end);
        noIobText = findViewById(R.id.carb_est_no_iob);
        withIobText = findViewById(R.id.carb_est_with_iob);
        errorText = findViewById(R.id.carb_est_error);
        logButton = findViewById(R.id.carb_est_log_button);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                final int hours = HOURS[progress];
                windowValueText.setText(getResources().getQuantityString(R.plurals.carb_est_hours, hours, hours));
                runEstimation(hours);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        logButton.setEnabled(false);
        logButton.setOnClickListener(v -> confirmAndLog());

        // Trigger initial estimation with default 2-hour window
        final int initialHours = HOURS[seekBar.getProgress()];
        windowValueText.setText(getResources().getQuantityString(R.plurals.carb_est_hours, initialHours, initialHours));
        runEstimation(initialHours);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void runEstimation(final int hours) {
        logButton.setEnabled(false);
        new AsyncTask<Void, Void, CarbEstimator.Result>() {
            @Override
            protected CarbEstimator.Result doInBackground(Void... voids) {
                final long endTime = System.currentTimeMillis();
                final long startTime = endTime - (long) hours * 60 * 60 * 1000;
                return CarbEstimator.estimate(startTime, endTime);
            }

            @Override
            protected void onPostExecute(CarbEstimator.Result result) {
                lastResult = result;
                updateDisplay(result);
            }
        }.execute();
    }

    private void updateDisplay(CarbEstimator.Result result) {
        if (result.hasError()) {
            errorText.setText(result.errorMessage);
            errorText.setVisibility(android.view.View.VISIBLE);
            startBgText.setText("—");
            endBgText.setText("—");
            deltaText.setText("—");
            iobStartText.setText("—");
            iobEndText.setText("—");
            noIobText.setText("—");
            withIobText.setText("—");
            logButton.setEnabled(false);
            return;
        }

        errorText.setVisibility(android.view.View.GONE);

        final String unit = doMgdl ? " mg/dL" : " mmol/L";

        startBgText.setText(formatBg(result.bgAtStart) + unit);
        endBgText.setText(formatBg(result.bgAtEnd) + unit);

        final String deltaStr = (result.glucoseDeltaMgdl >= 0 ? "+" : "") +
                formatBg(result.glucoseDeltaMgdl) + unit;
        deltaText.setText(deltaStr);

        iobStartText.setText(df2.format(result.iobAtStart) + " U");
        iobEndText.setText(df2.format(result.iobAtEnd) + " U");

        if (result.estimatedCarbsNoIob >= 0) {
            noIobText.setText(df1.format(result.estimatedCarbsNoIob) + " g");
        } else {
            noIobText.setText(getString(R.string.carb_est_negative_note, df1.format(result.estimatedCarbsNoIob)));
        }

        if (result.estimatedCarbs >= 0) {
            withIobText.setText(df1.format(result.estimatedCarbs) + " g");
            logButton.setEnabled(true);
        } else {
            withIobText.setText(getString(R.string.carb_est_negative_note, df1.format(result.estimatedCarbs)));
            logButton.setEnabled(false);
        }
    }

    private String formatBg(double mgdl) {
        if (doMgdl) {
            return String.valueOf(Math.round(mgdl));
        } else {
            return df1.format(mgdl * Constants.MGDL_TO_MMOLL);
        }
    }

    private void confirmAndLog() {
        if (lastResult == null || lastResult.hasError()) return;
        final double carbs = Math.max(0, lastResult.estimatedCarbs);
        final String message = getString(R.string.carb_est_confirm_log, df1.format(carbs));
        new AlertDialog.Builder(this)
                .setTitle(R.string.carb_est_log_button)
                .setMessage(message)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    Treatments.create(carbs, 0, System.currentTimeMillis());
                    Toast.makeText(this,
                            getString(R.string.carb_est_logged, df1.format(carbs)),
                            Toast.LENGTH_SHORT).show();
                    logButton.setEnabled(false);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
