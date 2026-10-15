/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.msantoki.evoxapps;

import android.app.AppGlobals;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Verifies an APK and installs it silently through PackageInstaller. */
final class Installer {
    private static final String TAG = "EvoXApps";
    private static final String ACTION_RESULT = "com.msantoki.evoxapps.INSTALL_RESULT";

    private final Context context;
    private final Set<String> allowedSigners;

    Installer(Context context) {
        this.context = context;
        allowedSigners = new HashSet<>(Arrays.asList(
                context.getResources().getStringArray(R.array.allowed_signers)));
    }

    /** Throws unless the file matches the expected hash and a pinned signer. */
    void verify(File apk, AppEntry entry) throws IOException {
        String hash = sha256(apk);
        if (!hash.equalsIgnoreCase(entry.sha256)) {
            throw new IOException(entry.name + ": SHA-256 mismatch");
        }
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(
                apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (info == null || info.signingInfo == null) {
            throw new IOException(entry.name + ": not a valid APK");
        }
        if (!entry.packageName.equals(info.packageName)) {
            throw new IOException(entry.name + ": package is " + info.packageName);
        }
        for (Signature sig : info.signingInfo.getApkContentsSigners()) {
            if (!allowedSigners.contains(sha256(sig.toByteArray()))) {
                throw new IOException(entry.name + ": signed by an unknown key");
            }
        }
    }

    /** Installs or updates; blocks until the system reports the result. */
    void install(File apk, AppEntry entry) throws IOException {
        PackageInstaller pi = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(entry.packageName);
        params.setSize(apk.length());
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);

        skipVerification();
        int sessionId = pi.createSession(params);
        CountDownLatch done = new CountDownLatch(1);
        int[] status = {PackageInstaller.STATUS_FAILURE};
        String[] message = {null};
        String action = ACTION_RESULT + "." + sessionId;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                status[0] = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                message[0] = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                done.countDown();
            }
        };
        // The result comes through our own PendingIntent, so the receiver
        // doesn't need to be reachable by other apps.
        context.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        try (PackageInstaller.Session session = pi.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent result = new Intent(action).setPackage(context.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, result,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            if (!done.await(10, TimeUnit.MINUTES)) {
                throw new IOException(entry.name + ": install timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(entry.name + ": install interrupted");
        } finally {
            context.unregisterReceiver(receiver);
        }
        if (status[0] != PackageInstaller.STATUS_SUCCESS) {
            throw new IOException(entry.name + ": install failed (" + status[0] + ") " + message[0]);
        }
        Log.i(TAG, "installed " + entry.packageName + " " + entry.build);
    }

    /** Uninstall a package we replaced, for example an older KernelSU manager. */
    void uninstall(String packageName) {
        PackageInstaller pi = context.getPackageManager().getPackageInstaller();
        Intent result = new Intent(ACTION_RESULT + ".uninstall").setPackage(context.getPackageName());
        PendingIntent pending = PendingIntent.getBroadcast(context, packageName.hashCode(), result,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        pi.uninstall(packageName, pending.getIntentSender());
        Log.i(TAG, "uninstalling replaced package " + packageName);
    }

    /**
     * Skip Play Protect's install-time verification for the next session. The APK was
     * already checked against its hash and our pinned signing keys. Only the system uid
     * may ask for this, which is why the app runs as android.uid.system.
     */
    private static void skipVerification() {
        try {
            AppGlobals.getPackageManager().getPackageInstaller()
                    .disableVerificationForUid(Process.myUid());
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "could not skip install verification", e);
        }
    }

    /** True if the installed package is signed by one of the pinned keys. */
    boolean isTrusted(PackageInfo info) {
        if (info == null) return false;
        PackageManager pm = context.getPackageManager();
        for (String digest : allowedSigners) {
            if (pm.hasSigningCertificate(info.packageName, unhex(digest),
                    PackageManager.CERT_INPUT_SHA256)) {
                return true;
            }
        }
        StringBuilder found = new StringBuilder();
        if (info.signingInfo != null) {
            for (Signature sig : info.signingInfo.getApkContentsSigners()) {
                found.append(' ').append(sha256(sig.toByteArray()));
            }
        }
        Log.w(TAG, info.packageName + " is not signed by a pinned key:" + found);
        return false;
    }

    private static byte[] unhex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static String sha256(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
