package com.eveningoutpost.dexdrip;

import android.app.AlertDialog;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.eveningoutpost.dexdrip.utilitymodels.ProfileOptimizer;

import java.text.DecimalFormat;

public class ProfileOptimizationActivity extends AppCompatActivity {

    private static final int[] PERIOD_DAYS = {7, 14, 30, 60, 90};

    private final DecimalFormat df2 = new DecimalFormat("0.##");
    private final DecimalFormat dfPct = new DecimalFormat("+0.#;-0.#");

    private Spinner periodSpinner;
    private Button analyzeBtn;

    private TextView isfCurrent, isfSuggested, isfChange, isfSamples, isfStatus;
    private Button isfApply;

    private TextView icrCurrent, icrSuggested, icrChange, icrSamples, icrStatus;
    private Button icrApply;

    private ProfileOptimizer.Suggestion lastIsf;
    private ProfileOptimizer.Suggestion lastIcr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_optimization);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.prof_opt_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        periodSpinner = findViewById(R.id.prof_opt_period_spinner);
        analyzeBtn    = findViewById(R.id.prof_opt_analyze_btn);

        isfCurrent   = findViewById(R.id.prof_opt_isf_current);
        isfSuggested = findViewById(R.id.prof_opt_isf_suggested);
        isfChange    = findViewById(R.id.prof_opt_isf_change);
        isfSamples   = findViewById(R.id.prof_opt_isf_samples);
        isfStatus    = findViewById(R.id.prof_opt_isf_status);
        isfApply     = findViewById(R.id.prof_opt_isf_apply);

        icrCurrent   = findViewById(R.id.prof_opt_icr_current);
        icrSuggested = findViewById(R.id.prof_opt_icr_suggested);
        icrChange    = findViewById(R.id.prof_opt_icr_change);
        icrSamples   = findViewById(R.id.prof_opt_icr_samples);
        icrStatus    = findViewById(R.id.prof_opt_icr_status);
        icrApply     = findViewById(R.id.prof_opt_icr_apply);

        // Build spinner entries
        String[] labels = new String[PERIOD_DAYS.length];
        for (int i = 0; i < PERIOD_DAYS.length; i++) {
            labels[i] = getString(R.string.prof_opt_days_label, PERIOD_DAYS[i]);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        periodSpinner.setAdapter(adapter);
        periodSpinner.setSelection(1); // default 14 days

        analyzeBtn.setOnClickListener(v -> runAnalysis());

        isfApply.setOnClickListener(v -> confirmApply(true));
        icrApply.setOnClickListener(v -> confirmApply(false));
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private int selectedDays() {
        return PERIOD_DAYS[periodSpinner.getSelectedItemPosition()];
    }

    // -------------------------------------------------------------------------

    private void runAnalysis() {
        analyzeBtn.setEnabled(false);
        isfApply.setEnabled(false);
        icrApply.setEnabled(false);

        final int days = selectedDays();

        new AsyncTask<Void, Void, ProfileOptimizer.Suggestion[]>() {
            @Override
            protected ProfileOptimizer.Suggestion[] doInBackground(Void... v) {
                return new ProfileOptimizer.Suggestion[]{
                        ProfileOptimizer.analyzeIsf(days),
                        ProfileOptimizer.analyzeIcr(days)
                };
            }

            @Override
            protected void onPostExecute(ProfileOptimizer.Suggestion[] results) {
                lastIsf = results[0];
                lastIcr = results[1];
                analyzeBtn.setEnabled(true);
                updateIsfCard(lastIsf);
                updateIcrCard(lastIcr);
            }
        }.execute();
    }

    // -------------------------------------------------------------------------

    private void updateIsfCard(ProfileOptimizer.Suggestion s) {
        isfCurrent.setText(df2.format(s.currentValue) + " " + s.unit);

        if (!s.hasEnoughData) {
            isfSuggested.setText("—");
            isfChange.setText("—");
            isfSamples.setText(String.valueOf(s.sampleCount));
            isfStatus.setVisibility(View.VISIBLE);
            isfStatus.setText(getString(R.string.prof_opt_not_enough_data,
                    ProfileOptimizer.MIN_SAMPLES, s.sampleCount));
            isfApply.setEnabled(false);
            return;
        }

        isfSuggested.setText(df2.format(s.suggestedValue) + " " + s.unit);
        isfChange.setText(dfPct.format(s.percentChange) + "%");
        isfSamples.setText(String.valueOf(s.sampleCount));

        if (Math.abs(s.percentChange) < 2) {
            isfStatus.setVisibility(View.VISIBLE);
            isfStatus.setText(R.string.prof_opt_already_good);
            isfApply.setEnabled(false);
        } else {
            isfStatus.setVisibility(View.GONE);
            isfApply.setEnabled(true);
        }
    }

    private void updateIcrCard(ProfileOptimizer.Suggestion s) {
        icrCurrent.setText(df2.format(s.currentValue) + " " + s.unit);

        if (!s.hasEnoughData) {
            icrSuggested.setText("—");
            icrChange.setText("—");
            icrSamples.setText(String.valueOf(s.sampleCount));
            icrStatus.setVisibility(View.VISIBLE);
            icrStatus.setText(getString(R.string.prof_opt_not_enough_data,
                    ProfileOptimizer.MIN_SAMPLES, s.sampleCount));
            icrApply.setEnabled(false);
            return;
        }

        icrSuggested.setText(df2.format(s.suggestedValue) + " " + s.unit);
        icrChange.setText(dfPct.format(s.percentChange) + "%");
        icrSamples.setText(String.valueOf(s.sampleCount));

        if (Math.abs(s.percentChange) < 2) {
            icrStatus.setVisibility(View.VISIBLE);
            icrStatus.setText(R.string.prof_opt_already_good);
            icrApply.setEnabled(false);
        } else {
            icrStatus.setVisibility(View.GONE);
            icrApply.setEnabled(true);
        }
    }

    // -------------------------------------------------------------------------

    private void confirmApply(final boolean isIsf) {
        final ProfileOptimizer.Suggestion s = isIsf ? lastIsf : lastIcr;
        if (s == null || !s.hasEnoughData) return;

        final String paramName = isIsf
                ? getString(R.string.prof_opt_isf_title)
                : getString(R.string.prof_opt_icr_title);

        final String msg = getString(R.string.prof_opt_confirm_apply,
                paramName,
                df2.format(s.currentValue), s.unit,
                df2.format(s.suggestedValue), s.unit,
                dfPct.format(s.percentChange));

        new AlertDialog.Builder(this)
                .setTitle(R.string.prof_opt_confirm_title)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, (dialog, which) -> doApply(isIsf, s))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void doApply(boolean isIsf, ProfileOptimizer.Suggestion s) {
        if (isIsf) {
            ProfileOptimizer.applyIsfToAllBlocks(s.suggestedValue);
            Toast.makeText(this,
                    getString(R.string.prof_opt_applied, getString(R.string.prof_opt_isf_title),
                            df2.format(s.suggestedValue), s.unit),
                    Toast.LENGTH_LONG).show();
            isfApply.setEnabled(false);
            isfStatus.setVisibility(View.VISIBLE);
            isfStatus.setText(getString(R.string.prof_opt_applied,
                    getString(R.string.prof_opt_isf_title),
                    df2.format(s.suggestedValue), s.unit));
        } else {
            ProfileOptimizer.applyIcrToAllBlocks(s.suggestedValue);
            Toast.makeText(this,
                    getString(R.string.prof_opt_applied, getString(R.string.prof_opt_icr_title),
                            df2.format(s.suggestedValue), s.unit),
                    Toast.LENGTH_LONG).show();
            icrApply.setEnabled(false);
            icrStatus.setVisibility(View.VISIBLE);
            icrStatus.setText(getString(R.string.prof_opt_applied,
                    getString(R.string.prof_opt_icr_title),
                    df2.format(s.suggestedValue), s.unit));
        }
    }
}
