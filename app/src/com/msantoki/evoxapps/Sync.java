/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.msantoki.evoxapps;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides what to install and installs it.
 *
 * Per app it remembers the build it installed. That gives three cases:
 *  - never installed by us: install it (first boot, or a newly added app)
 *  - installed by us but gone now: the user uninstalled it, leave it alone
 *  - installed by us with an older build: update it
 * An app the user removed is only installed again from the settings screen.
 * A package that is present but was never installed by us (for example the
 * official Reddit from the Play Store) is not touched.
 */
final class Sync {
    private static final String TAG = "EvoXApps";
    static final String PREFS = "sync";
    private static final Object LOCK = new Object();

    enum State { NOT_INSTALLED, INSTALLED, UPDATE_AVAILABLE, REMOVED, EXTERNAL }

    static final class Status {
        final AppEntry entry;
        final State state;
        final String installedVersion;

        Status(AppEntry entry, State state, String installedVersion) {
            this.entry = entry;
            this.state = state;
            this.installedVersion = installedVersion;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Installer installer;

    Sync(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        installer = new Installer(this.context);
    }

    /** Bundled entries, overridden by newer published ones when online. */
    List<AppEntry> entries(boolean online) {
        Map<String, AppEntry> byId = new LinkedHashMap<>();
        File dir = new File(context.getString(R.string.bundled_dir));
        try {
            String json = new String(Files.readAllBytes(new File(dir, "index.json").toPath()),
                    StandardCharsets.UTF_8);
            for (AppEntry e : AppEntry.parseIndex(json, dir)) byId.put(e.id, e);
        } catch (Exception e) {
            Log.i(TAG, "no bundled apps: " + e);
        }
        if (online) {
            try {
                for (AppEntry e : AppEntry.parseIndex(download(context.getString(R.string.index_url)), null)) {
                    AppEntry local = byId.get(e.id);
                    // Keep the bundled copy when it's the same build, no download needed
                    if (local == null || (!local.build.equals(e.build) && e.versionCode >= local.versionCode)) {
                        byId.put(e.id, e);
                    }
                }
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                Log.w(TAG, "could not fetch the published index: " + e);
            }
        }
        List<AppEntry> list = new ArrayList<>(byId.values());
        list.sort((a, b) -> Boolean.compare(!"microg".equals(a.id), !"microg".equals(b.id)));
        return list;
    }

    Status status(AppEntry e) {
        PackageInfo pi = installedInfo(e.packageName);
        String ours = prefs.getString("build_" + e.id, null);
        if (prefs.getBoolean("removed_" + e.id, false)) {
            return new Status(e, pi == null ? State.REMOVED : State.EXTERNAL, pi == null ? null : pi.versionName);
        }
        if (pi == null) {
            return new Status(e, ours == null ? State.NOT_INSTALLED : State.REMOVED, null);
        }
        if (ours == null) return new Status(e, State.EXTERNAL, pi.versionName);
        if (!ours.equals(e.build) && e.versionCode >= pi.getLongVersionCode()) {
            return new Status(e, State.UPDATE_AVAILABLE, pi.versionName);
        }
        return new Status(e, State.INSTALLED, pi.versionName);
    }

    /** Background run: first installs and updates. Returns false if anything failed. */
    boolean run(boolean online) {
        synchronized (LOCK) {
            boolean ok = true;
            for (AppEntry e : entries(online)) {
                Status s = status(e);
                if (s.state == State.REMOVED && prefs.getString("build_" + e.id, null) != null
                        && !prefs.getBoolean("removed_" + e.id, false)) {
                    // First time we notice the user uninstalled it
                    prefs.edit().putBoolean("removed_" + e.id, true).apply();
                    Log.i(TAG, e.packageName + " was uninstalled by the user, not reinstalling");
                    continue;
                }
                if (s.state != State.NOT_INSTALLED && s.state != State.UPDATE_AVAILABLE) continue;
                if (s.state == State.UPDATE_AVAILABLE && !autoUpdate()) continue;
                ok &= installQuietly(e);
            }
            return ok;
        }
    }

    /** From the settings screen: install or update one app, also after the user removed it. */
    boolean installNow(AppEntry e) {
        synchronized (LOCK) {
            prefs.edit().remove("removed_" + e.id).remove("build_" + e.id).apply();
            return installQuietly(e);
        }
    }

    boolean autoUpdate() {
        return prefs.getBoolean("auto_update", true);
    }

    long lastCheck() {
        return prefs.getLong("last_check", 0);
    }

    private boolean installQuietly(AppEntry e) {
        File apk = e.localFile;
        boolean temp = false;
        try {
            if (apk == null) {
                apk = new File(context.getCacheDir(), e.id + ".apk");
                temp = true;
                downloadTo(e.url, apk);
            }
            installer.verify(apk, e);
            installer.install(apk, e);
            prefs.edit().putString("build_" + e.id, e.build).remove("removed_" + e.id).apply();
            return true;
        } catch (Exception ex) {
            Log.e(TAG, "could not install " + e.name, ex);
            return false;
        } finally {
            if (temp && apk != null) apk.delete();
        }
    }

    private PackageInfo installedInfo(String pkg) {
        try {
            return context.getPackageManager().getPackageInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(60_000);
        c.setUseCaches(false);
        if (c.getResponseCode() != 200) {
            throw new IOException(url + " returned " + c.getResponseCode());
        }
        return c;
    }

    private static String download(String url) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static void downloadTo(String url, File dest) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            c.disconnect();
        }
    }
}
