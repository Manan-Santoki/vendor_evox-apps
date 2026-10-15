/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.msantoki.evoxapps;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Settings > System > Bundled apps. */
public class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Sync sync;
    private LinearLayout appList;
    private TextView lastCheck;
    private Button checkButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sync = new Sync(this);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout root = column();
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        root.addView(text(getString(R.string.intro), 14, false));

        LinearLayout autoRow = row();
        LinearLayout autoText = column();
        autoText.addView(text(getString(R.string.auto_update), 16, true));
        autoText.addView(text(getString(R.string.auto_update_summary), 14, false));
        autoRow.addView(autoText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch auto = new Switch(this);
        auto.setChecked(sync.autoUpdate());
        auto.setOnCheckedChangeListener((b, on) -> getSharedPreferences(Sync.PREFS, MODE_PRIVATE)
                .edit().putBoolean("auto_update", on).apply());
        autoRow.addView(auto);
        root.addView(autoRow);

        LinearLayout checkRow = row();
        lastCheck = text("", 14, false);
        checkRow.addView(lastCheck, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        checkButton = new Button(this);
        checkButton.setText(R.string.check_now);
        checkButton.setOnClickListener(v -> refresh(true));
        checkRow.addView(checkButton);
        root.addView(checkRow);

        appList = column();
        root.addView(appList);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        refresh(false);
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void refresh(boolean online) {
        checkButton.setEnabled(false);
        checkButton.setText(R.string.checking);
        worker.execute(() -> {
            List<AppEntry> entries = sync.entries(online);
            Sync.Status[] statuses = new Sync.Status[entries.size()];
            for (int i = 0; i < statuses.length; i++) statuses[i] = sync.status(entries.get(i));
            runOnUiThread(() -> show(statuses));
        });
    }

    private void show(Sync.Status[] statuses) {
        if (isFinishing()) return;
        checkButton.setEnabled(true);
        checkButton.setText(R.string.check_now);
        long last = sync.lastCheck();
        lastCheck.setText(last == 0 ? getString(R.string.never_checked)
                : getString(R.string.last_check, DateUtils.getRelativeTimeSpanString(last)));

        appList.removeAllViews();
        for (Sync.Status s : statuses) {
            LinearLayout r = row();
            LinearLayout t = column();
            t.addView(text(s.entry.name, 16, true));
            t.addView(text(describe(s), 14, false));
            r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (s.state == Sync.State.NOT_INSTALLED || s.state == Sync.State.REMOVED
                    || s.state == Sync.State.UPDATE_AVAILABLE) {
                Button b = new Button(this);
                b.setText(s.state == Sync.State.UPDATE_AVAILABLE ? R.string.update : R.string.install);
                b.setOnClickListener(v -> {
                    b.setEnabled(false);
                    b.setText(R.string.installing);
                    worker.execute(() -> {
                        sync.installNow(s.entry);
                        runOnUiThread(() -> refresh(false));
                    });
                });
                r.addView(b);
            }
            appList.addView(r);
        }
    }

    private String describe(Sync.Status s) {
        switch (s.state) {
            case INSTALLED: return getString(R.string.state_installed, s.installedVersion);
            case UPDATE_AVAILABLE: return getString(R.string.state_update, s.entry.versionName);
            case REMOVED: return getString(R.string.state_removed);
            case EXTERNAL: return getString(R.string.state_external);
            default: return getString(R.string.state_not_installed);
        }
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(0, dp(12), 0, dp(12));
        return l;
    }

    private TextView text(String s, int sp, boolean title) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(title ? android.R.attr.textColorPrimary
                : android.R.attr.textColorSecondary, tv, true);
        v.setTextColor(getColorStateList(tv.resourceId));
        return v;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }
}
