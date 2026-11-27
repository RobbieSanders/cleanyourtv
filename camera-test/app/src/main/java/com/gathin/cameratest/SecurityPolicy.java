package com.gathin.cameratest;

import java.util.Locale;

final class SecurityPolicy {
    static final long SESSION_MS=30L*24*60*60*1000;
    static final long IDLE_MS=5L*60*1000;
    static final long LOCKOUT_MS=5L*60*1000;
    static boolean valid(long issued,long lastSeen,long now){
        return issued>0 && now>=issued && now>=lastSeen-300000L && now-issued<SESSION_MS;
    }
    static String arm(int code){
        switch(code){case 3:case 5:return "Armed Stay";case 4:case 6:return "Armed Away";case 0:return "Disarmed";default:return "Unknown";}
    }
    static String kind(String icon,String message){
        String text=(icon+" "+message).toLowerCase(Locale.ROOT).replace('_',' ').replace('-',' ');
        if(text.contains("lingering")||text.contains("loitering")||text.contains("person linger")||text.contains("person loiter"))return "Person lingering";
        if(text.contains("doorbell pressed")||text.contains("doorbell rang")||text.contains("doorbell ring")||text.contains("doorbell press"))return "Doorbell pressed";
        if(text.contains("motion detected")||text.contains("person detected")||text.contains("camera motion"))return "Motion detected";
        return null;
    }
    static boolean permitted(String kind,String arm,boolean stay,boolean away,boolean disarmed,boolean lingering,boolean doorbell,boolean motion){
        boolean allowed="Armed Stay".equals(arm)?stay:"Armed Away".equals(arm)?away:"Disarmed".equals(arm)&&disarmed;
        return allowed&&("Person lingering".equals(kind)?lingering:"Doorbell pressed".equals(kind)?doorbell:"Motion detected".equals(kind)&&motion);
    }
}
