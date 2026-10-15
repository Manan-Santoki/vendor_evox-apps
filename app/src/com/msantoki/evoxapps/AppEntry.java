/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.msantoki.evoxapps;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** One app from index.json, either the bundled copy or the published one. */
final class AppEntry {
    final String id;
    final String name;
    final String packageName;
    final String versionName;
    final long versionCode;
    final String build;
    final String sha256;
    final long size;
    final String url;
    /** Bundled APK on /product, or null for an entry from the published index. */
    final File localFile;

    private AppEntry(JSONObject o, File localFile) throws JSONException {
        id = o.getString("id");
        name = o.getString("name");
        packageName = o.getString("package");
        versionName = o.getString("versionName");
        versionCode = o.getLong("versionCode");
        build = o.getString("build");
        sha256 = o.getString("sha256");
        size = o.optLong("size");
        url = o.optString("url", null);
        this.localFile = localFile;
    }

    /**
     * Parses an index. For the bundled index, {@code bundledDir} is where the
     * APKs named by each entry's "file" live; pass null for the published one.
     */
    static List<AppEntry> parseIndex(String json, File bundledDir) throws JSONException {
        JSONArray apps = new JSONObject(json).getJSONArray("apps");
        List<AppEntry> out = new ArrayList<>();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject o = apps.getJSONObject(i);
            File local = null;
            if (bundledDir != null) {
                local = new File(bundledDir, o.getString("file"));
                if (!local.canRead()) continue;
            }
            out.add(new AppEntry(o, local));
        }
        // MicroG first, the Google apps need it to sign in
        out.sort((a, b) -> Boolean.compare(!"microg".equals(a.id), !"microg".equals(b.id)));
        return out;
    }
}
