package com.gathin.cameratest;

import android.content.Context;
import android.content.SharedPreferences;

final class AlertPreferences {
    final SharedPreferences prefs;
    AlertPreferences(Context c){prefs=c.getApplicationContext().getSharedPreferences("security_alert_options",Context.MODE_PRIVATE);}
    boolean get(String key,boolean fallback){return prefs.getBoolean(key,fallback);}
    void put(String key,boolean value){prefs.edit().putBoolean(key,value).apply();}
    boolean enabled(){return get("enabled",false);}
    boolean matches(VivintClient.Alert a){
        return SecurityPolicy.permitted(a.kind,a.arm,get("stay",true),get("away",true),get("disarmed",false),
            get("linger",true),get("doorbell",true),get("motion",false));
    }
}
