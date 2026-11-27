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
    private static final int BG=0xff090d15, PANEL=0xff151d2a, WHITE=0xfff5f8fc, MUTED=0xff9eaec3, ACCENT=0xff9bdfff;
    private static final int OVERLAY_PERMISSION=201;
    private int gridPage=0, selectedIndex=0;
    private String singleReturn="grid";
    private long permissionTripUntil=0, ignoreConfirmUntil=0;
    private boolean permissionTrip=false;
    private View singleCanvas;
    private final ArrayList<Button> tileButtons=new ArrayList<>();
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
        screenOff=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){permissionTrip=false;permissionTripUntil=0;lock("Camera access locked.");}};
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
        boolean returning=permissionTrip&&SystemClock.elapsedRealtime()<=permissionTripUntil&&sessionUntil>System.currentTimeMillis()&&vault.usable();
        permissionTrip=false;permissionTripUntil=0;
        if(returning){stopped=false;showSettings();}
        else if(stopped){stopped=false;entry("");}
        else if(unlockedScreen())resetIdle();
    }
    @Override protected void onPause(){
        visible=false;
        if(permissionTrip){releaseAll();ui.removeCallbacks(idle);}
        else if(unlockedScreen()||busy||"mfa".equals(screen)||"enroll".equals(screen))lock("Camera access locked.");
        super.onPause();
    }
    @Override protected void onStop(){
        super.onStop();stopped=true;
        if(permissionTrip){releaseAll();ui.removeCallbacks(idle);}
        else{invalidate();screen="entry";sessionUntil=0;}
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void beginPermissionTrip(){
        permissionTrip=true;permissionTripUntil=SystemClock.elapsedRealtime()+120000L;
    }
    private void openOverlaySettings(){
        beginPermissionTrip();
        try{startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())),OVERLAY_PERMISSION);}
        catch(RuntimeException e){permissionTrip=false;permissionTripUntil=0;status.setText("Overlay permission settings are not available on this TV.");}
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==100&&alerts.enabled()&&vault.exists())AlertService.start(this);
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
        return sessionUntil>0&&("grid".equals(screen)||"single".equals(screen)||"list".equals(screen)||"settings".equals(screen)||"changePin".equals(screen)||"refresh".equals(screen));
    }
    private void resetIdle(){
        ui.removeCallbacks(idle);
        long left=sessionUntil-System.currentTimeMillis();
        if(left<=0||!vault.exists()){lock("Session expired. Sign in again.");return;}
        ui.postDelayed(idle,alerts.get("idleLock",false)?Math.min(left,SecurityPolicy.IDLE_MS):left);
    }
    private void invalidate(){
        generation++;busy=false;ui.removeCallbacks(idle);releaseAll();
        cameras.clear();
        if(pending!=null){pending.clear();pending=null;}
    }
    private void lock(String message){
        permissionTrip=false;permissionTripUntil=0;
        invalidate();sessionUntil=0;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(!isFinishing()&&!isDestroyed())entry(message);
    }
    private void entry(String message){
        if(vault.exists()&&vault.usable())showPin(message);
        else{AlertService.stop(this);showLogin(message);}
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String content,int size,int color){
        TextView t=new TextView(this);t.setText(content);t.setTextSize(size);t.setTextColor(color);return t;
    }
    private GradientDrawable shape(int color,int stroke){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));d.setStroke(dp(1),stroke);return d;
    }
    private StateListDrawable focusBackground(){
        StateListDrawable d=new StateListDrawable();
        GradientDrawable focused=shape(0xff263b50,ACCENT);focused.setStroke(dp(2),ACCENT);
        d.addState(new int[]{android.R.attr.state_focused},focused);
        d.addState(new int[]{android.R.attr.state_pressed},shape(0xff304b61,ACCENT));
        d.addState(new int[]{},shape(PANEL,0xff2a384c));
        return d;
    }
    private void page(String title,String detail,boolean scroll){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff172332,BG,0xff0d131e}));
        root.setPadding(dp(26),dp(12),dp(26),dp(12));
        if(scroll){ScrollView view=new ScrollView(this);view.setFillViewport(true);view.addView(root);setContentView(view);}
        else setContentView(root);
        LinearLayout headingRow=new LinearLayout(this);headingRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.ic_security);
        LinearLayout.LayoutParams markParams=new LinearLayout.LayoutParams(dp(30),dp(30));markParams.rightMargin=dp(10);headingRow.addView(mark,markParams);
        TextView heading=text(title,25,WHITE);heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));
        heading.setSingleLine(true);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        headingRow.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        TextView brand=text("HOME  /  SECURITY",11,ACCENT);brand.setLetterSpacing(0.10f);headingRow.addView(brand);
        root.addView(headingRow);
        status=text(detail,13,MUTED);status.setPadding(0,dp(4),0,dp(8));root.addView(status);
    }
    private void section(String title){
        TextView t=text(title.toUpperCase(Locale.ROOT),12,ACCENT);
        t.setLetterSpacing(0.12f);t.setPadding(dp(5),dp(14),0,dp(4));root.addView(t);
    }
    private EditText field(String hint,int type){
        EditText e=new EditText(this);e.setTextColor(WHITE);e.setHintTextColor(MUTED);e.setHint(hint);
        e.setSingleLine(true);e.setInputType(type);
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(e,new LinearLayout.LayoutParams(-1,dp(50)));return e;
    }
    private Button makeButton(String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(WHITE);b.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
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
    private boolean current(int g){return g==generation&&visible&&!isFinishing()&&!isDestroyed()&&!stopped;}
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
    private boolean unlocked(String name,String title,String description,boolean scroll){
        if(sessionUntil<=System.currentTimeMillis()||!vault.exists()){lock("Session expired. Sign in again.");return false;}
        releaseAll();screen=name;busy=false;page(title,description,scroll);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);resetIdle();
        return true;
    }
    private int pages(){return Math.max(1,(cameras.size()+3)/4);}
    private void changePage(int delta){
        int next=gridPage+delta;if(next<0||next>=pages())return;
        gridPage=next;selectedIndex=gridPage*4;showGrid();
    }
    private void showGrid(){
        gridPage=Math.max(0,Math.min(gridPage,pages()-1));
        if(!unlocked("grid","Home Security",cameras.size()+" cameras  ·  SD overview  ·  Page "+(gridPage+1)+" of "+pages(),false))return;
        LinearLayout bar=toolbar();
        Button list=tool(bar,"All cameras",this::showList);
        tool(bar,"Refresh",this::refreshCameras);
        tool(bar,"Settings",this::showSettings);
        tool(bar,"Lock",()->lock("Camera access locked."));
        if(pages()>1){
            Button previous=tool(bar,"‹ Previous",()->changePage(-1));previous.setEnabled(gridPage>0);previous.setFocusable(gridPage>0);
            Button next=tool(bar,"Next ›",()->changePage(1));next.setEnabled(gridPage+1<pages());next.setFocusable(gridPage+1<pages());
        }
        LinearLayout grid=new LinearLayout(this);grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,0,1);gp.topMargin=dp(9);root.addView(grid,gp);
        tileButtons.clear();
        final int id=videoGeneration;
        for(int row=0;row<2;row++){
            LinearLayout line=new LinearLayout(this);line.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(line,new LinearLayout.LayoutParams(-1,0,1));
            for(int col=0;col<2;col++){
                int local=row*2+col,index=gridPage*4+local;
                LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(dp(6),dp(5),dp(6),dp(6));
                tile.setBackground(shape(0xff101823,0xff29394a));
                LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-1,1);tp.setMargins(dp(4),dp(4),dp(4),dp(4));line.addView(tile,tp);
                if(index>=cameras.size()){
                    TextView empty=text("No additional camera",14,MUTED);empty.setGravity(Gravity.CENTER);
                    tile.addView(empty,new LinearLayout.LayoutParams(-1,-1));continue;
                }
                VivintClient.Camera camera=cameras.get(index);
                FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(Color.BLACK);
                tile.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
                VLCVideoLayout layout=new VLCVideoLayout(this);layout.setFocusable(false);layout.setFocusableInTouchMode(false);layout.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                frame.addView(layout,new FrameLayout.LayoutParams(-1,-1));
                Button open=makeButton(camera.name+"   ›",()->openFromGrid(index));
                open.setId(View.generateViewId());open.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
                open.setSingleLine(true);open.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tile.addView(open,new LinearLayout.LayoutParams(-1,dp(36)));
                TextView caption=text("Connecting…",11,MUTED);caption.setPadding(dp(8),dp(2),0,0);caption.setSingleLine(true);
                tile.addView(caption);
                tileButtons.add(open);
                frame.setFocusable(false);frame.setClickable(true);frame.setOnClickListener(view->openFromGrid(index));
                open.setOnFocusChangeListener((view,focused)->{
                    tile.setBackground(shape(focused?0xff1c2b3b:0xff101823,focused?ACCENT:0xff29394a));
                    if(focused)selectedIndex=index;
                });
                playback.postDelayed(()->{
                    if(videoGeneration==id&&"grid".equals(screen))start(camera,false,layout,caption);
                },local*450L);
            }
        }
        for(int i=0;i<tileButtons.size();i++){
            Button b=tileButtons.get(i);
            if(i%2==0&&i+1<tileButtons.size())b.setNextFocusRightId(tileButtons.get(i+1).getId());
            if(i%2==1)b.setNextFocusLeftId(tileButtons.get(i-1).getId());
            if(i+2<tileButtons.size())b.setNextFocusDownId(tileButtons.get(i+2).getId());
            if(i>=2)b.setNextFocusUpId(tileButtons.get(i-2).getId());
            else{if(list.getId()==View.NO_ID)list.setId(View.generateViewId());b.setNextFocusUpId(list.getId());}
        }
        if(!tileButtons.isEmpty()){
            int focus=Math.max(0,Math.min(selectedIndex-gridPage*4,tileButtons.size()-1));tileButtons.get(focus).requestFocus();
        }else list.requestFocus();
    }
    private void openFromGrid(int index){
        if(!"grid".equals(screen)||index<0||index>=cameras.size()||SystemClock.uptimeMillis()<ignoreConfirmUntil)return;
        selectedIndex=index;singleReturn="grid";showSingle(cameras.get(index),true);
    }
    private void showList(){
        if(!unlocked("list","All cameras",cameras.size()+" available cameras  ·  Choose a camera to view live",true))return;
        for(int i=0;i<cameras.size();i++){
            final int index=i;VivintClient.Camera c=cameras.get(i);
            button(c.name+"   ›",()->{
                selectedIndex=index;gridPage=index/4;singleReturn="list";showSingle(c,true);
            });
        }
        button("Refresh cameras",this::refreshCameras);
        button("Camera dashboard",this::showGrid);button("Settings",this::showSettings);button("Lock",()->lock(""));
    }
    private void refreshCameras(){
        if(busy||!unlocked("refresh","Refreshing cameras","Checking your Vivint account for new cameras…",true))return;
        busy=true;int g=generation;
        button("Cancel",()->{generation++;busy=false;showGrid();});
        worker.execute(()->{
            try{
                List<VivintClient.Camera> found=vault.use(VivintClient::cameras);
                ui.post(()->{
                    if(!current(g)||!"refresh".equals(screen))return;
                    cameras=found;gridPage=0;selectedIndex=0;busy=false;showGrid();
                });
            }catch(Exception e){
                String error=safe(e);
                ui.post(()->{
                    if(!current(g)||!"refresh".equals(screen))return;
                    busy=false;
                    if(!vault.exists()){lock(error);return;}
                    status.setText(error);
                    button("Try again",this::refreshCameras);
                });
            }
        });
    }
    private void leaveSingle(){
        if(SystemClock.uptimeMillis()<ignoreConfirmUntil)return;
        if("list".equals(singleReturn))showList();else showGrid();
    }
    private void showSingle(VivintClient.Camera camera,boolean hd){
        if(!unlocked("single",camera.name,(hd?"HD":"SD")+"  ·  Live local camera",false))return;
        ignoreConfirmUntil=SystemClock.uptimeMillis()+550;
        LinearLayout bar=toolbar();
        Button back=tool(bar,"‹ Cameras",this::leaveSingle);back.setId(View.generateViewId());
        tool(bar,hd?"Switch to SD":"Switch to HD",()->{
            if(SystemClock.uptimeMillis()>=ignoreConfirmUntil)showSingle(camera,!hd);
        });
        tool(bar,"Lock",()->lock(""));
        FrameLayout frame=new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);frame.setFocusable(true);frame.setFocusableInTouchMode(true);frame.setId(View.generateViewId());
        frame.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        frame.setNextFocusUpId(back.getId());back.setNextFocusDownId(frame.getId());
        frame.setContentDescription(camera.name+" live video. Press Up for controls or Back to return.");
        frame.setOnKeyListener((view,key,event)->{
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER)return true;
            return false;
        });
        VLCVideoLayout layout=new VLCVideoLayout(this);layout.setFocusable(false);layout.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        frame.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,0,1);p.topMargin=dp(9);root.addView(frame,p);
        singleCanvas=frame;frame.requestFocus();
        final int id=videoGeneration;
        playback.postDelayed(()->{
            if(id==videoGeneration&&"single".equals(screen))start(camera,hd,layout,status);
        },250);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int code=event.getKeyCode();
        if("single".equals(screen)&&(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_NUMPAD_ENTER)
            &&(SystemClock.uptimeMillis()<ignoreConfirmUntil||event.getRepeatCount()>0))return true;
        return super.dispatchKeyEvent(event);
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
        String prefix="single".equals(screen)?camera.name+" · ":"";
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
        videoGeneration++;playback.removeCallbacksAndMessages(null);singleCanvas=null;tileButtons.clear();
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
        if(!unlocked("settings","Settings","Manage your security, camera access and notifications.",true))return;
        section("Account & privacy");
        long days=Math.max(1,(sessionUntil-System.currentTimeMillis()+86399999L)/86400000L);
        root.addView(text("Full Vivint sign-in expires in "+days+" day(s).",16,ACCENT));
        button("Change security PIN",this::showChangePin);
        toggle("Lock after five minutes of inactivity","idleLock",false,this::resetIdle);
        button("Lock now",()->lock("Camera access locked."));
        section("Camera management");
        button("Refresh cameras ("+cameras.size()+" found)",this::refreshCameras);
        section("Notifications");
        toggle("Enable camera alerts (experimental)","enabled",false,()->{
            if(alerts.enabled()){
                if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                    beginPermissionTrip();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},100);
                }
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
                openOverlaySettings();
            }
        });
        root.addView(text("Alerts check recent authorized Vivint history about every 30 seconds while the TV is awake. Only recognizable events matching the alarm-state filters are shown. A popup dismisses itself in 10 seconds; View requires the PIN.",14,MUTED));
        button("Overlay permission settings",this::openOverlaySettings);
        button("Return to cameras",this::showGrid);
        button("Forget session / Sign out",this::confirmSignOut);
        button("Lock",()->lock(""));
    }
    private void showChangePin(){
        if(!unlocked("changePin","Change PIN","Verify your current PIN, then choose a new six-digit PIN. Your 30-day session stays unchanged.",true))return;
        EditText old=field("Current PIN",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText next=field("New six-digit PIN",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        EditText confirm=field("Confirm new PIN",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        button("Save new PIN",()->{
            if(busy)return;
            String before=old.getText().toString(),after=next.getText().toString(),again=confirm.getText().toString();
            if(!before.matches("[0-9]{6}")||!after.matches("[0-9]{6}")||!after.equals(again)){status.setText("Enter your current PIN and matching new six-digit PINs.");return;}
            old.setText("");next.setText("");confirm.setText("");busy=true;
            status.setText("Updating PIN…");int g=generation;
            worker.execute(()->{
                try{
                    long until=vault.changePin(before,after);
                    ui.post(()->{
                        if(!current(g)||!"changePin".equals(screen))return;
                        busy=false;sessionUntil=until;showSettings();status.setText("PIN changed successfully. Your session expiration is unchanged.");
                    });
                }catch(Exception e){
                    String error=safe(e);
                    ui.post(()->{
                        if(!current(g)||!"changePin".equals(screen))return;
                        busy=false;
                        if(!vault.exists()||error.contains("Locked for five minutes")||error.contains("PIN locked"))lock(error);
                        else status.setText(error);
                    });
                }
            });
        });
        button("Cancel",()->{if(!busy)showSettings();});old.requestFocus();
    }
    @Override public void onBackPressed(){
        if("single".equals(screen)){leaveSingle();return;}
        if("list".equals(screen)||"settings".equals(screen)){showGrid();return;}
        if("changePin".equals(screen)){if(!busy)showSettings();return;}
        if("refresh".equals(screen)){generation++;busy=false;showGrid();return;}
        if("grid".equals(screen)){finish();return;}
        if("mfa".equals(screen)||"enroll".equals(screen)){lock("");return;}
        super.onBackPressed();
    }

}
