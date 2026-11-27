package com.gathin.cameratest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())&&new AlertPreferences(context).enabled()&&new SessionVault(context).exists())
            AlertService.start(context);
    }
}
