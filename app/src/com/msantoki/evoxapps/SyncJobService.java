/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.msantoki.evoxapps;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.TimeUnit;

/** Installs bundled apps after boot and checks for updates periodically. */
public class SyncJobService extends JobService {
    private static final int JOB_NOW = 1;
    private static final int JOB_PERIODIC = 2;

    /** Run once right away: installs from the ROM without needing a network. */
    static void runNow(Context context, boolean online) {
        JobInfo.Builder b = new JobInfo.Builder(JOB_NOW, new ComponentName(context, SyncJobService.class));
        if (online) b.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        context.getSystemService(JobScheduler.class).schedule(b.build());
    }

    static void schedulePeriodic(Context context) {
        long hours = context.getResources().getInteger(R.integer.check_interval_hours);
        JobInfo job = new JobInfo.Builder(JOB_PERIODIC, new ComponentName(context, SyncJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.HOURS.toMillis(hours))
                .setPersisted(true)
                .build();
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js.getPendingJob(JOB_PERIODIC) == null) js.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        boolean online = params.getJobId() == JOB_PERIODIC
                || params.getNetwork() != null;
        new Thread(() -> {
            boolean ok = new Sync(this).run(online);
            jobFinished(params, !ok && params.getJobId() == JOB_PERIODIC);
        }, "EvoXAppsSync").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
