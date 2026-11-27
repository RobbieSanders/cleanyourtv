package com.gathin.cameratest;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    static volatile boolean visible=false;
    private static final int BG=0xff0b111b, PANEL=0xff172432, WHITE=0xfff5f8fc, MUTED=0xffb2c6d6, ACCENT=0xff9edcff;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Handler playback=new Handler(Looper.getMainLooper());
    private final List<Slot> players=new ArrayList<>();
    private List<VivintClient.Camera> cameras=new ArrayList<>();
    private LibVLC vlc;
    private SessionVault vault;
    private AlertPreferences alerts;
    private VivintClient pending;
    private LinearLayout root;
    private TextView status;
    private String screen="entry", targetCamera;
    private volatile int generation=0;
    private int videoGeneration=0;
    private boolean busy=false, stopped=false;
    private long sessionUntil=0;
    private BroadcastReceiver screenOff;
    private final Runnable idle=()->lock("Camera access locked.");

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        vault=new SessionVault(this);alerts=new AlertPreferences(this);
        targetCamera=getIntent().getStringExtra("camera");
        screenOff=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){lock("Camera access locked.");}};
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenOff,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff,filter);
        entry("");
        if(vault.exists()&&alerts.enabled())AlertService.start(this);
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);targetCamera=intent.getStringExtra("camera");
        lock("Unlock to view your cameras.");
    }
    @Override protected void onResume(){
        super.onResume();visible=true;
        if(stopped){stopped=false;entry("");}
    }
    @Override protected void onPause(){
        visible=false;
        if(unlockedScreen())lock("Camera access locked.");
        super.onPause();
    }
    @Override protected void onStop(){
        super.onStop();stopped=true;invalidate();
        screen="entry";sessionUntil=0;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override protected void onDestroy(){
        invalidate();worker.shutdownNow();ui.removeCallbacksAndMessages(null);
        if(screenOff!=null)try{unregisterReceiver(screenOff);}catch(RuntimeException ignored){}
        super.onDestroy();
    }
    @Override public void onUserInteraction(){
        super.onUserInteraction();
        if(unlockedScreen())resetIdle();
    }
    private boolean unlockedScreen(){
        return sessionUntil>0&&("grid".equals(screen)||"single".equals(screen)||"list".equals(screen)||"settings".equals(screen));
    }
    private void resetIdle(){
        ui.removeCallbacks(idle);
        long left=sessionUntil-System.currentTimeMillis();
        if(left<=0){lock("30-day session expired. Sign in again.");return;}
        ui.postDelayed(idle,Math.min(left,SecurityPolicy.IDLE_MS));
    }
    private void invalidate(){
        generation++;busy=false;ui.removeCallbacks(idle);releaseAll();
        cameras.clear();
        if(pending!=null){pending.clear();pending=null;}
    }
    private void lock(String message){
        invalidate();sessionUntil=0;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(!isFinishing()&&!isDestroyed())entry(message);
    }
    private void entry(String message){
        if(vault.exists())showPin(message);else showLogin(message);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String content,int size,int color){
        TextView t=new TextView(this);t.setText(content);t.setTextSize(size);t.setTextColor(color);return t;
    }
    private GradientDrawable shape(int color,int stroke){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(9));d.setStroke(dp(2),stroke);return d;
    }
    private StateListDrawable focusBackground(){
        StateListDrawable d=new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused},shape(0xff243c50,ACCENT));
        d.addState(new int[]{android.R.attr.state_pressed},shape(0xff304b61,ACCENT));
        d.addState(new int[]{},shape(PANEL,0xff334759));
        return d;
    }
    private void page(String title,String detail,boolean scroll){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        root.setPadding(dp(30),dp(18),dp(30),dp(16));
        if(scroll){ScrollView view=new ScrollView(this);view.setFillViewport(true);view.addView(root);setContentView(view);}
        else setContentView(root);
        TextView heading=text(title,25,WHITE);heading.setTypeface(null,android.graphics.Typeface.BOLD);root.addView(heading);
        status=text(detail,15,MUTED);status.setPadding(0,dp(4),0,dp(8));root.addView(status);
    }
    private EditText field(String hint,int type){
        EditText e=new EditText(this);e.setTextColor(WHITE);e.setHintTextColor(MUTED);e.setHint(hint);
        e.setSingleLine(true);e.setInputType(type);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(e,new LinearLayout.LayoutParams(-1,dp(50)));return e;
    }
    private Button makeButton(String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(WHITE);
        b.setBackground(focusBackground());b.setFocusable(true);b.setPadding(dp(10),dp(4),dp(10),dp(4));
        b.setOnClickListener(v->action.run());return b;
    }
    private Button button(String label,Runnable action){
        Button b=makeButton(label,action);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(46));p.topMargin=dp(6);root.addView(b,p);return b;
    }
    private LinearLayout toolbar(){
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);root.addView(bar,new LinearLayout.LayoutParams(-1,dp(44)));return bar;
    }
    private Button tool(LinearLayout bar,String label,Runnable action){
        Button b=makeButton(label,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(3),0,dp(3),0);bar.addView(b,p);return b;
    }
    private void showLogin(String message){
        screen="login";busy=false;sessionUntil=0;
        page("Home Security",message.isEmpty()?"Sign in to Vivint. A new full login is required at least every 30 days.":message,true);
        EditText user=field("Vivint email",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass=field("Vivint password",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("Sign in",()->{
            if(busy)return;
            String u=user.getText().toString().trim(),p=pass.getText().toString();
            if(u.isEmpty()||p.isEmpty()){status.setText("Enter your email and password.");return;}
            user.setText("");pass.setText("");
            pending=new VivintClient();VivintClient client=pending;
            runAuth(client,()->client.login(u,p));
        });
        button("Exit",this::finish);user.requestFocus();
    }
    private interface AuthAction{boolean run()throws Exception;}
    private String safe(Exception e){
        return e.getClass()==Exception.class&&e.getMessage()!=null?e.getMessage():"Network or device error ("+e.getClass().getSimpleName()+").";
    }
    private boolean current(int g){return g==generation&&!isFinishing()&&!isDestroyed()&&!stopped;}
    private void runAuth(VivintClient client,AuthAction action){
        if(busy)return;
        busy=true;status.setText("Connecting securely to Vivint...");
        final int g=generation;
        worker.execute(()->{
            try{
                if(!action.run()){
                    ui.post(()->{if(current(g))showMfa(client);else client.clear();});return;
                }
                List<VivintClient.Camera> found=client.cameras();
                ui.post(()->{
                    if(!current(g)){client.clear();return;}
                    cameras=found;showEnrollment(client);
                });
            }catch(Exception e){
                client.clear();String error=safe(e);
                ui.post(()->{if(current(g)){pending=null;showLogin(error);}});
            }
        });
    }
    private void showMfa(VivintClient client){
        screen="mfa";busy=false;
        page("Verify Vivint sign-in","Enter the code from your latest Vivint message.",true);
        EditText code=field("Verification code",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        button("Verify",()->{
            if(busy)return;
            String value=code.getText().toString().trim();
            if(!value.matches("[0-9]{4,10}")){status.setText("Enter the numeric verification code.");return;}
            code.setText("");runAuth(client,()->client.verify(value));
        });
        button("Cancel",()->lock(""));code.requestFocus();
    }
    private void showEnrollment(VivintClient client){
        screen="enroll";busy=false;
        page("Protect your cameras","Choose a six-digit PIN. Vivint session tokens are encrypted on this TV; your password is not saved.",true);
        EditText pin=field("New six-digit PIN",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText confirm=field("Confirm PIN",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        button("Activate 30-day session",()->{
            if(busy)return;
            String p=pin.getText().toString(),q=confirm.getText().toString();
            if(!p.matches("[0-9]{6}")||!p.equals(q)){status.setText("Enter matching six-digit PINs.");return;}
            pin.setText("");confirm.setText("");busy=true;status.setText("Securing your session...");
            int g=generation;
            worker.execute(()->{
                try{
                    if(g!=generation)throw new Exception("Sign-in cancelled.");
                    vault.enroll(client,p);client.clear();
                    long until=vault.checkPin(p);
                    ui.post(()->{
                        if(!current(g))return;
                        pending=null;busy=false;sessionUntil=until;
                        if(alerts.enabled())AlertService.start(this);
                        openRequested();
                    });
                }catch(Exception e){
                    client.clear();String error=safe(e);
                    ui.post(()->{if(current(g)){pending=null;cameras.clear();entry(error);}});
                }
            });
        });
        button("Cancel",()->lock(""));pin.requestFocus();
    }
    private void showPin(String message){
        screen="pin";busy=false;
        page("Home Security",message.isEmpty()?"Enter your six-digit PIN to unlock live cameras.":message,true);
        EditText pin=field("Security PIN",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        button("Unlock",()->{
            if(busy)return;
            String value=pin.getText().toString();
            if(!value.matches("[0-9]{6}")){status.setText("Enter your six-digit PIN.");return;}
            pin.setText("");busy=true;status.setText("Unlocking cameras...");
            int g=generation;
            worker.execute(()->{
                try{
                    long until=vault.checkPin(value);
                    List<VivintClient.Camera> found=vault.use(VivintClient::cameras);
                    ui.post(()->{
                        if(!current(g))return;
                        cameras=found;sessionUntil=until;busy=false;
                        if(alerts.enabled())AlertService.start(this);
                        openRequested();
                    });
                }catch(Exception e){
                    String error=safe(e);
                    ui.post(()->{
                        if(!current(g))return;
                        if(!vault.exists()){AlertService.stop(this);showLogin(error);}
                        else showPin(error);
                    });
                }
            });
        });
        button("Sign out / Forgot PIN",this::confirmSignOut);
        button("Exit",this::finish);pin.requestFocus();
    }
    private void confirmSignOut(){
        new android.app.AlertDialog.Builder(this).setTitle("Forget this TV's session?")
            .setMessage("This stops monitoring and requires your Vivint password and verification again. It does not change your Vivint account.")
            .setNegativeButton("Cancel",null).setPositiveButton("Sign out",(d,w)->{
                invalidate();sessionUntil=0;alerts.put("enabled",false);AlertService.stop(this);vault.clear();showLogin("Signed out.");
            }).show();
    }
    private void openRequested(){
        String requested=targetCamera;targetCamera=null;
        if(requested!=null)for(VivintClient.Camera c:cameras)if(requested.equals(c.name)){showSingle(c,true);return;}
        showGrid();
    }
    private void unlocked(String name,String title,String description,boolean scroll){
        releaseAll();screen=name;busy=false;page(title,description,scroll);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);resetIdle();
    }
    private void showGrid(){
        if(sessionUntil<=System.currentTimeMillis()){lock("Session expired.");return;}
        unlocked("grid","Home Security","Live local cameras · SD overview · Select a camera for HD",false);
        LinearLayout bar=toolbar();
        tool(bar,"Camera list",this::showList);
        tool(bar,"Settings",this::showSettings);
        tool(bar,"Lock",()->lock("Camera access locked."));
        LinearLayout grid=new LinearLayout(this);grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,0,1);gp.topMargin=dp(8);root.addView(grid,gp);
        final int id=videoGeneration;
        for(int row=0;row<2;row++){
            LinearLayout line=new LinearLayout(this);line.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(line,new LinearLayout.LayoutParams(-1,0,1));
            for(int col=0;col<2;col++){
                int index=row*2+col;
                LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(dp(6),dp(4),dp(6),dp(5));
                tile.setBackground(focusBackground());
                LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-1,1);tp.setMargins(dp(3),dp(3),dp(3),dp(3));line.addView(tile,tp);
                if(index>=cameras.size()){tile.addView(text("No camera",16,MUTED));continue;}
                VivintClient.Camera camera=cameras.get(index);
                TextView title=text(camera.name+" · Connecting",16,WHITE);title.setSingleLine(true);tile.addView(title);
                VLCVideoLayout layout=new VLCVideoLayout(this);layout.setFocusable(false);layout.setFocusableInTouchMode(false);
                tile.addView(layout,new LinearLayout.LayoutParams(-1,0,1));
                tile.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                tile.setFocusable(true);tile.setClickable(true);tile.setContentDescription("View "+camera.name);
                tile.setOnClickListener(v->showSingle(camera,true));
                if(index==0)tile.requestFocus();
                playback.postDelayed(()->{
                    if(videoGeneration==id&&"grid".equals(screen))start(camera,false,layout,title);
                },index*350L);
            }
        }
    }
    private void showList(){
        unlocked("list","Cameras","Choose a camera to view individually. This uses less TV memory.",true);
        for(VivintClient.Camera c:cameras)button(c.name,()->showSingle(c,true));
        button("Four-camera view",this::showGrid);button("Settings",this::showSettings);button("Lock",()->lock(""));
    }
    private void showSingle(VivintClient.Camera camera,boolean hd){
        unlocked("single",camera.name,(hd?"HD":"SD")+" · Connecting to local camera",false);
        LinearLayout bar=toolbar();
        tool(bar,"Back to cameras",this::showGrid).requestFocus();
        tool(bar,hd?"Switch to SD":"Switch to HD",()->showSingle(camera,!hd));
        tool(bar,"Lock",()->lock(""));
        VLCVideoLayout layout=new VLCVideoLayout(this);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,0,1);p.topMargin=dp(8);root.addView(layout,p);
        start(camera,hd,layout,status);
    }
    private VivintClient.Stream selectStream(VivintClient.Camera camera,boolean hd){
        String wanted=hd?"HD":"SD";
        for(VivintClient.Stream s:camera.streams)if(s.label.startsWith(wanted)&&!s.label.contains("without Opus"))return s;
        for(VivintClient.Stream s:camera.streams)if(s.label.startsWith(wanted))return s;
        return camera.streams.isEmpty()?null:camera.streams.get(0);
    }
    private static final class Slot {
        MediaPlayer player;
        boolean output,closed;
        Slot(MediaPlayer player){this.player=player;}
        void close(){
            if(closed)return;closed=true;
            try{player.setEventListener(null);}catch(RuntimeException ignored){}
            try{player.stop();}catch(RuntimeException ignored){}
            try{player.detachViews();}catch(RuntimeException ignored){}
            try{player.release();}catch(RuntimeException ignored){}
        }
    }
    private void start(VivintClient.Camera camera,boolean hd,VLCVideoLayout layout,TextView label){
        VivintClient.Stream stream=selectStream(camera,hd);
        String prefix=camera.name+" · ";
        if(stream==null){label.setText(prefix+"Unavailable");return;}
        final int id=videoGeneration;
        Slot created=null;
        try{
            if(vlc==null){
                ArrayList<String> opts=new ArrayList<>();
                opts.add("--quiet");opts.add("--no-audio");opts.add("--network-caching=1000");opts.add("--no-video-title-show");
                vlc=new LibVLC(this,opts);
            }
            Slot slot=new Slot(new MediaPlayer(vlc));created=slot;players.add(slot);
            slot.player.attachViews(layout,null,false,false);
            slot.player.setEventListener(event->{
                int type=event.type;
                int outputs=type==MediaPlayer.Event.Vout?event.getVoutCount():0;
                ui.post(()->{
                    if(id!=videoGeneration||slot.closed)return;
                    if(type==MediaPlayer.Event.Vout&&outputs>0){slot.output=true;label.setText(prefix+stream.label+" · Live output");}
                    if(type==MediaPlayer.Event.EncounteredError||type==MediaPlayer.Event.EndReached){
                        label.setText(prefix+"Stream unavailable");slot.close();players.remove(slot);
                    }
                });
            });
            Media media=new Media(vlc,Uri.parse(stream.address));
            try{
                media.setHWDecoderEnabled(true,false);
                media.addOption(":rtsp-tcp");media.addOption(":rtsp-user="+stream.username);
                media.addOption(":rtsp-pwd="+stream.password);media.addOption(":no-audio");
                slot.player.setMedia(media);
            }finally{media.release();}
            slot.player.play();
            playback.postDelayed(()->{
                if(id==videoGeneration&&!slot.closed&&!slot.output){
                    label.setText(prefix+"No video · Try single view");slot.close();players.remove(slot);
                }
            },20000);
        }catch(Exception|LinkageError e){
            if(created!=null){created.close();players.remove(created);}
            label.setText(prefix+"Playback unavailable");
        }
    }
    private void releaseAll(){
        videoGeneration++;playback.removeCallbacksAndMessages(null);
        for(Slot s:players)s.close();players.clear();
        if(vlc!=null){try{vlc.release();}catch(RuntimeException ignored){}vlc=null;}
    }
    private void toggle(String title,String key,boolean def,Runnable changed){
        Switch s=new Switch(this);s.setText(title);s.setTextSize(16);s.setTextColor(WHITE);
        s.setPadding(dp(10),dp(4),dp(10),dp(4));s.setBackground(focusBackground());
        s.setChecked(alerts.get(key,def));s.setFocusable(true);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(46));p.topMargin=dp(6);root.addView(s,p);
        s.setOnCheckedChangeListener((v,on)->{alerts.put(key,on);if(changed!=null)changed.run();});
    }
    private void showSettings(){
        unlocked("settings","Security settings","Alerts are silent and never automatically open video. Camera access locks after five minutes of inactivity.",true);
        long days=Math.max(1,(sessionUntil-System.currentTimeMillis()+86399999L)/86400000L);
        root.addView(text("Full Vivint sign-in expires in "+days+" day(s).",16,ACCENT));
        toggle("Enable camera alerts (experimental)","enabled",false,()->{
            if(alerts.enabled()){
                if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},100);
                AlertService.start(this);
            }else AlertService.stop(this);
        });
        toggle("When Armed Stay","stay",true,null);
        toggle("When Armed Away","away",true,null);
        toggle("When Disarmed","disarmed",false,null);
        toggle("Person lingering / loitering","linger",true,null);
        toggle("Doorbell pressed","doorbell",true,null);
        toggle("General motion","motion",false,null);
        toggle("Interactive top-right popup (temporarily takes remote focus)","overlay",false,()->{
            if(alerts.get("overlay",false)&&!Settings.canDrawOverlays(this)){
                try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
                catch(RuntimeException e){status.setText("Overlay permission settings are not available on this TV.");}
            }
        });
        root.addView(text("Alerts check recent authorized Vivint history about every 30 seconds while the TV is awake. Only recognizable events matching the alarm-state filters are shown. A popup dismisses itself in 10 seconds; View requires the PIN.",14,MUTED));
        button("Overlay permission settings",()->{
            try{startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));}
            catch(RuntimeException e){status.setText("Overlay settings unavailable.");}
        });
        button("Return to cameras",this::showGrid);
        button("Forget session / Sign out",this::confirmSignOut);
        button("Lock",()->lock(""));
    }
    @Override public void onBackPressed(){
        if("single".equals(screen)||"list".equals(screen)||"settings".equals(screen)){showGrid();return;}
        if("grid".equals(screen)){lock("");return;}
        if("mfa".equals(screen)||"enroll".equals(screen)){lock("");return;}
        super.onBackPressed();
    }
}
