package com.gathin.cameratest;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class AlertService extends Service {
    private static final int RUNNING=40,EVENT=41;
    private static final String STATUS_CHANNEL="security_status",ALERT_CHANNEL="security_events";
    private static final String DISMISS="com.gathin.cameratest.DISMISS";
    private final Handler ui=new Handler(Looper.getMainLooper());
    private ScheduledExecutorService worker;
    private final Set<String> seen=new LinkedHashSet<>();
    private long started;
    private volatile boolean running;
    private long nextPollAllowed;
    private AlertPreferences options;
    private SessionVault vault;
    private WindowManager windows;
    private View overlay;
    private Runnable expiry;
    private BroadcastReceiver screenReceiver;
    static void start(Context c){
        try{c.startForegroundService(new Intent(c,AlertService.class));}catch(RuntimeException ignored){}
    }
    static void stop(Context c){
        c.stopService(new Intent(c,AlertService.class));
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        if(nm!=null){nm.cancel(EVENT);nm.cancel(RUNNING);}
    }
    private NotificationManager nm(){return getSystemService(NotificationManager.class);}
    private PendingIntent open(String camera){
        Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if(camera!=null)i.putExtra("camera",camera);
        return PendingIntent.getActivity(this,camera==null?100:101,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public void onCreate(){
        super.onCreate();
        options=new AlertPreferences(this);vault=new SessionVault(this);
        windows=getSystemService(WindowManager.class);
        NotificationChannel status=new NotificationChannel(STATUS_CHANNEL,"Security monitoring",NotificationManager.IMPORTANCE_LOW);
        status.setSound(null,null);status.enableVibration(false);
        NotificationChannel events=new NotificationChannel(ALERT_CHANNEL,"Security alerts",NotificationManager.IMPORTANCE_HIGH);
        events.setSound(null,null);events.enableVibration(false);events.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm().createNotificationChannel(status);nm().createNotificationChannel(events);
        screenReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){hideOverlay();nm().cancel(EVENT);}};
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver,filter);
    }
    private Notification status(String label){
        return new Notification.Builder(this,STATUS_CHANNEL).setSmallIcon(com.gathin.cameratest.R.drawable.ic_security)
            .setContentTitle("Home Security").setContentText(label).setContentIntent(open(null))
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i!=null&&DISMISS.equals(i.getAction())){
            hideOverlay();nm().cancel(EVENT);
            if(worker==null)stopSelf();
            return START_NOT_STICKY;
        }
        if(!options.enabled()||!vault.exists()){stopSelf();return START_NOT_STICKY;}
        startForeground(RUNNING,status("Monitoring selected camera events"));
        if(worker==null){
            started=System.currentTimeMillis();running=true;
            worker=Executors.newSingleThreadScheduledExecutor();
            worker.scheduleWithFixedDelay(this::poll,0,30,TimeUnit.SECONDS);
        }
        return START_STICKY;
    }
    private boolean awake(){
        PowerManager power=getSystemService(PowerManager.class);
        KeyguardManager keyguard=getSystemService(KeyguardManager.class);
        return power!=null&&power.isInteractive()&&(keyguard==null||!keyguard.isKeyguardLocked());
    }
    private void poll(){
        if(!running)return;
        if(!options.enabled()||!vault.usable()){ui.post(this::stopSelf);return;}
        if(!awake()||System.currentTimeMillis()<nextPollAllowed)return;
        try{
            List<VivintClient.Alert> events=vault.use(VivintClient::alerts);
            events.sort(Comparator.comparingLong(a->a.time));
            VivintClient.Alert newest=null;
            for(VivintClient.Alert a:events){
                if(!seen.add(a.key))continue;
                if(a.time>=started && options.matches(a))newest=a;
            }
            while(seen.size()>300){Iterator<String> it=seen.iterator();it.next();it.remove();}
            if(newest!=null){
                VivintClient.Alert event=newest;
                ui.post(()->{if(running&&awake()&&options.enabled()&&vault.exists()&&!MainActivity.visible)showEvent(event);});
            }
        }catch(Exception e){
            nextPollAllowed=System.currentTimeMillis()+120000L;
            if(!vault.exists())ui.post(this::stopSelf);
        }
    }
    private void showEvent(VivintClient.Alert a){
        PendingIntent dismiss=PendingIntent.getService(this,102,new Intent(this,AlertService.class).setAction(DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_security).setContentTitle("Home Security: "+a.kind)
            .setContentText(a.camera+" · "+a.arm).setContentIntent(open(a.camera))
            .addAction(new Notification.Action.Builder(null,"View",open(a.camera)).build())
            .addAction(new Notification.Action.Builder(null,"Dismiss",dismiss).build())
            .setTimeoutAfter(10000).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
        nm().notify(EVENT,n);
        if(options.get("overlay",false)&&Settings.canDrawOverlays(this))showOverlay(a);
    }
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void showOverlay(VivintClient.Alert a){
        hideOverlay();
        try{
            LinearLayout box=new LinearLayout(this){
                @Override public boolean dispatchKeyEvent(KeyEvent e){
                    if(e.getKeyCode()==KeyEvent.KEYCODE_BACK){if(e.getAction()==KeyEvent.ACTION_UP){hideOverlay();nm().cancel(EVENT);}return true;}
                    return super.dispatchKeyEvent(e);
                }
            };
            box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),dp(12),dp(16),dp(12));
            GradientDrawable bg=new GradientDrawable();bg.setColor(0xff172130);bg.setCornerRadius(dp(12));bg.setStroke(dp(1),0xff6e899e);box.setBackground(bg);
            TextView heading=new TextView(this);heading.setText("Home Security");heading.setTextColor(0xff9edcff);heading.setTextSize(15);box.addView(heading);
            TextView detail=new TextView(this);detail.setText(a.kind+"\n"+a.camera+" · "+a.arm);detail.setTextColor(Color.WHITE);detail.setTextSize(18);box.addView(detail);
            LinearLayout actions=new LinearLayout(this);
            Button no=new Button(this);no.setText("Dismiss");no.setOnClickListener(v->{hideOverlay();nm().cancel(EVENT);});
            Button yes=new Button(this);yes.setText("View");yes.setOnClickListener(v->{
                hideOverlay();nm().cancel(EVENT);
                try{open(a.camera).send();}catch(PendingIntent.CanceledException ignored){}
            });
            actions.addView(no,new LinearLayout.LayoutParams(0,dp(48),1));
            actions.addView(yes,new LinearLayout.LayoutParams(0,dp(48),1));box.addView(actions);
            WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(360),WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_SECURE,PixelFormat.TRANSLUCENT);
            p.gravity=Gravity.TOP|Gravity.RIGHT;p.x=dp(24);p.y=dp(24);
            windows.addView(box,p);overlay=box;no.requestFocus();
            expiry=()->{hideOverlay();nm().cancel(EVENT);};ui.postDelayed(expiry,10000);
        }catch(RuntimeException e){hideOverlay();}
    }
    private void hideOverlay(){
        if(expiry!=null){ui.removeCallbacks(expiry);expiry=null;}
        if(overlay!=null){try{windows.removeView(overlay);}catch(RuntimeException ignored){}overlay=null;}
    }
    @Override public void onDestroy(){
        running=false;
        if(worker!=null){worker.shutdownNow();worker=null;}
        hideOverlay();ui.removeCallbacksAndMessages(null);
        if(screenReceiver!=null)try{unregisterReceiver(screenReceiver);}catch(RuntimeException ignored){}
        nm().cancel(EVENT);stopForeground(true);super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}
