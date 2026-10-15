/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.msantoki.evoxapps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Installs whatever the ROM bundles straight away, then updates run on a schedule
        SyncJobService.runNow(context, false);
        SyncJobService.schedulePeriodic(context);
    }
}
