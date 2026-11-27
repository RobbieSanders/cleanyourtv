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
    private static final int BG=0xff0b1420, PANEL=0xff182635, WHITE=0xfff5f8fc, MUTED=0xffa8bdd0, ACCENT=0xffa3ddff;
    private static final int OVERLAY_PERMISSION=201;
    private int gridPage=0, selectedIndex=0;
    private boolean authForm=false, primaryAdded=false, controlsHidden=false;
    private final List<EditText> numericFields=new ArrayList<>();
    private EditText numericTarget;
    private boolean keypadAdded=false;
    private int singleFocus=0;
    private boolean confirmDown=false;
    private TextView audioStatus;
    private int testRequest=0;
    private LinearLayout singleBar;
    private TextView singleHeading;
    private VivintClient.Camera activeCamera;
    private boolean activeHd=true, activeAudio=false;
    private VivintClient.Overview overview;
    private long overviewAt=0;
    private final ArrayList<VivintClient.Camera> allCameras=new ArrayList<>();
    private final Runnable hideControls=()->{
        if("single".equals(this.screen)&&singleBar!=null&&this.singleCanvas!=null){
            singleBar.setVisibility(View.GONE);controlsHidden=true;this.singleCanvas.requestFocus();
        }
    };
    private String singleReturn="grid";
    private long permissionTripUntil=0, ignoreConfirmUntil=0;
    private boolean permissionTrip=false;
    private View singleCanvas;
    private final ArrayList<View> tileButtons=new ArrayList<>();
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
    private long sessionUntil=0,viewUntil=0,settingsUntil=0;
    private boolean notificationEntry=false,notificationOnly=false;
    private String settingsCategory="Cameras";
    private BroadcastReceiver screenOff;
    private final Runnable idle=()->{if(alerts.get("idleLock",false)&&!notificationOnly)lock("Camera access locked.");else expireView();};
    private final Runnable settingsExpiry=()->{
        if("settings".equals(this.screen)||"changePin".equals(this.screen)||"manage".equals(this.screen)){
            settingsUntil=0;generation++;busy=false;showGrid();
        }
    };
    private SharedPreferences securityPrefs(){return getSharedPreferences("view_security",MODE_PRIVATE);}
    private String pinMode(){return securityPrefs().getString("mode","balanced");}
    private int pinHours(){return securityPrefs().getInt("hours",168);}
    private boolean sleepLock(){return "strict".equals(pinMode())||securityPrefs().getBoolean("sleep",false);}
    private boolean notificationBypass(){return !"strict".equals(pinMode())&&securityPrefs().getBoolean("notification",true);}
    private long normalGrant(){return vault.rememberedUntil(pinMode(),pinHours());}
    private void receiveIntent(Intent intent){
        targetCamera=AlertService.consume(intent);notificationEntry=targetCamera!=null;
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        vault=new SessionVault(this);alerts=new AlertPreferences(this);
        receiveIntent(getIntent());stopped=true;
        screenOff=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
            permissionTrip=false;permissionTripUntil=0;settingsUntil=0;
            if(sleepLock())vault.revokeAccess();
            suspendView();
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }};
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenOff,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff,filter);
        if(vault.exists()&&alerts.enabled())AlertService.start(this);
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);receiveIntent(intent);
        suspendView();
        if(visible){stopped=false;entry("");}
    }
    @Override protected void onResume(){
        super.onResume();visible=true;
        boolean returning=permissionTrip&&SystemClock.elapsedRealtime()<=permissionTripUntil&&sessionUntil>System.currentTimeMillis()&&vault.usable();
        permissionTrip=false;permissionTripUntil=0;
        if(returning){stopped=false;showSettings();}
        else if(stopped){stopped=false;entry("");}
        else if(unlockedScreen())resetIdle();
    }
    private void suspendView(){
        invalidate();screen="entry";sessionUntil=0;viewUntil=0;settingsUntil=0;notificationOnly=false;stopped=true;
        FrameLayout blank=new FrameLayout(this);blank.setBackgroundColor(BG);setContentView(blank);
    }
    @Override protected void onPause(){
        visible=false;
        if(permissionTrip){releaseAll();ui.removeCallbacks(idle);}
        else{
            if("strict".equals(pinMode()))vault.revokeAccess();
            targetCamera=null;notificationEntry=false;suspendView();
        }
        super.onPause();
    }
    @Override protected void onStop(){
        super.onStop();stopped=true;
        if(permissionTrip){releaseAll();ui.removeCallbacks(idle);}
        else if(!"entry".equals(screen))suspendView();
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
        return sessionUntil>0&&("settingsPin".equals(screen)||"grid".equals(screen)||"single".equals(screen)||"list".equals(screen)||"settings".equals(screen)||"changePin".equals(screen)||"refresh".equals(screen)||"activity".equals(screen)||"manage".equals(screen));
    }
    private void resetIdle(){
        ui.removeCallbacks(idle);
        long left=Math.min(sessionUntil,viewUntil)-System.currentTimeMillis();
        if(left<=0||!vault.exists()){expireView();return;}
        ui.postDelayed(idle,alerts.get("idleLock",false)?Math.min(left,SecurityPolicy.IDLE_MS):left);
    }
    private void invalidate(){
        generation++;busy=false;ui.removeCallbacks(idle);ui.removeCallbacks(settingsExpiry);releaseAll();
        cameras.clear();allCameras.clear();overview=null;overviewAt=0;
        if(pending!=null){pending.clear();pending=null;}
    }
    private void expireView(){
        boolean exit=notificationOnly;
        invalidate();sessionUntil=0;viewUntil=0;settingsUntil=0;notificationOnly=false;notificationEntry=false;targetCamera=null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(exit){finish();return;}
        if(!isFinishing()&&!isDestroyed())entry("PIN authorization expired.");
    }
    private void lock(String message){
        permissionTrip=false;permissionTripUntil=0;vault.revokeAccess();
        invalidate();sessionUntil=0;viewUntil=0;settingsUntil=0;notificationOnly=false;notificationEntry=false;targetCamera=null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if(!isFinishing()&&!isDestroyed())entry(message);
    }
    private void entry(String message){
        if(!vault.exists()||!vault.usable()){notificationEntry=false;targetCamera=null;AlertService.stop(this);showLogin(message);return;}
        long normal=normalGrant();
        long alert=notificationEntry&&notificationBypass()?vault.notificationUntil():0;
        if(normal>0||alert>0){
            notificationOnly=normal==0;
            viewUntil=normal>0?normal:Math.min(alert,System.currentTimeMillis()+SecurityPolicy.IDLE_MS);
            sessionUntil=vault.sessionEnd();screen="loading";busy=true;
            page("Home Security","Opening your cameras…",false);
            int gen=generation;
            worker.execute(()->{
                try{List<VivintClient.Camera> found=vault.use(VivintClient::cameras);
                    ui.post(()->{if(!current(gen))return;setCameras(found);busy=false;openRequested();});
                }catch(Exception e){ui.post(()->{if(!current(gen))return;busy=false;notificationOnly=false;
                    if(vault.exists())showPin(safe(e));else showLogin(safe(e));});}
            });return;
        }
        showPin(message);
    }
    private void grantAfterPin(long until){
        sessionUntil=until;long remembered=normalGrant();
        viewUntil=remembered>0?remembered:until;notificationOnly=false;
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
        authForm=false;primaryAdded=false;numericFields.clear();numericTarget=null;keypadAdded=false;
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff142333,BG,0xff080e16}));
        root.setPadding(dp(18),dp(8),dp(18),dp(8));
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
    private GradientDrawable glass(){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff1d3042,0xff142331,0xff101b28});
        d.setCornerRadius(dp(14));d.setStroke(dp(1),0xff34485a);return d;
    }
    private void authPage(String title,String detail,String stage){
        authForm=true;primaryAdded=false;numericFields.clear();numericTarget=null;keypadAdded=false;
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);shell.setGravity(Gravity.CENTER_VERTICAL);
        shell.setPadding(dp(30),dp(10),dp(30),dp(10));
        shell.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff213648,0xff101c2a,0xff090f18}));
        LinearLayout brand=new LinearLayout(this);brand.setOrientation(LinearLayout.VERTICAL);brand.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-1,0.86f);bp.rightMargin=dp(28);shell.addView(brand,bp);
        ImageView mark=new ImageView(this);mark.setImageResource(R.drawable.ic_security);
        mark.setPadding(dp(10),dp(10),dp(10),dp(10));mark.setBackground(glass());
        brand.addView(mark,new LinearLayout.LayoutParams(dp(64),dp(64)));
        TextView word=text("Home\nSecurity",34,WHITE);word.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));word.setPadding(0,dp(14),0,0);brand.addView(word);
        TextView tagline=text("YOUR HOME. ALWAYS IN VIEW.",11,ACCENT);tagline.setLetterSpacing(.13f);tagline.setPadding(0,dp(9),0,dp(12));brand.addView(tagline);
        brand.addView(text("Private live cameras.\nProtected access.",15,MUTED));
        TextView footer=text("●  Encrypted session  ·  Read-only access",11,ACCENT);footer.setPadding(0,dp(20),0,0);brand.addView(footer);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);shell.addView(scroll,new LinearLayout.LayoutParams(0,-2,1.14f));
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(23),dp(17),dp(23),dp(17));
        root.setBackground(glass());scroll.addView(root,new ScrollView.LayoutParams(-1,-2));
        TextView step=text(stage.toUpperCase(Locale.ROOT),10,ACCENT);step.setLetterSpacing(.13f);root.addView(step);
        TextView heading=text(title,27,WHITE);heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));heading.setPadding(0,dp(3),0,0);root.addView(heading);
        status=text(detail,13,MUTED);status.setPadding(0,dp(5),0,dp(9));root.addView(status);
        setContentView(shell);
    }
    private StateListDrawable inputBackground(){
        StateListDrawable d=new StateListDrawable();
        GradientDrawable focused=shape(0xff122233,ACCENT);focused.setStroke(dp(2),ACCENT);
        d.addState(new int[]{android.R.attr.state_focused},focused);
        d.addState(new int[]{},shape(0xff142435,0xff526a80));return d;
    }
    private StateListDrawable primaryBackground(){
        StateListDrawable d=new StateListDrawable();
        GradientDrawable focused=shape(0xffd8f3ff,0xffffffff);focused.setStroke(dp(2),0xffffffff);
        d.addState(new int[]{android.R.attr.state_focused},focused);
        d.addState(new int[]{android.R.attr.state_pressed},shape(0xff8cc5e9,ACCENT));
        d.addState(new int[]{},shape(0xffb0ddf7,0xffb0ddf7));return d;
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
        e.setTextSize(16);e.setBackground(inputBackground());e.setPadding(dp(13),0,dp(13),0);
        e.setSelectAllOnFocus(false);e.setTypeface(android.graphics.Typeface.create("sans-serif",0));
        if(authForm&&(type&InputType.TYPE_MASK_CLASS)==InputType.TYPE_CLASS_NUMBER){
            e.setShowSoftInputOnFocus(false);
            e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(hint.toLowerCase(Locale.ROOT).contains("verification")?10:6)});
            e.setLetterSpacing(.2f);numericFields.add(e);
            if(numericTarget==null)numericTarget=e;
            e.setOnFocusChangeListener((view,hasFocus)->{if(hasFocus)numericTarget=e;});
            e.setOnClickListener(view->{numericTarget=e;});
        }
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(46));ep.topMargin=dp(7);
        root.addView(e,ep);return e;
    }
    private Button makeButton(String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(WHITE);b.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));
        b.setBackground(focusBackground());b.setFocusable(true);b.setPadding(dp(10),dp(4),dp(10),dp(4));
        b.setOnClickListener(v->action.run());return b;
    }
    private void appendDigit(String digit){
        EditText e=numericTarget;
        if(e==null)return;
        if("Clear".equals(digit)){e.setText("");return;}
        if("⌫".equals(digit)){int n=e.length();if(n>0)e.getText().delete(n-1,n);return;}
        if("Next".equals(digit)){
            int i=numericFields.indexOf(e);
            if(i+1<numericFields.size()){numericTarget=numericFields.get(i+1);numericTarget.requestFocus();}
            return;
        }
        e.append(digit);
        if(e.length()==6&&numericFields.size()>1){
            int i=numericFields.indexOf(e);if(i+1<numericFields.size())numericTarget=numericFields.get(i+1);
        }
    }
    private void addKeypad(){
        if(keypadAdded||numericFields.isEmpty())return;
        keypadAdded=true;
        String[][] keys={{"1","2","3","⌫"},{"4","5","6","Clear"},{"7","8","9","0"}};
        for(String[] line:keys){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(34));rp.topMargin=dp(4);root.addView(row,rp);
            for(String key:line){
                Button k=makeButton(key,()->appendDigit(key));k.setTextSize(16);k.setMinHeight(0);k.setMinimumHeight(0);
                LinearLayout.LayoutParams kp=new LinearLayout.LayoutParams(0,-1,1);kp.setMargins(dp(2),0,dp(2),0);row.addView(k,kp);
            }
        }
    }
    private void hideKeyboard(){
        View focus=getCurrentFocus();
        if(focus!=null){android.view.inputmethod.InputMethodManager im=getSystemService(android.view.inputmethod.InputMethodManager.class);
            if(im!=null)im.hideSoftInputFromWindow(focus.getWindowToken(),0);}
    }
    private Button button(String label,Runnable action){
        if(authForm&&!primaryAdded)addKeypad();
        Button b=makeButton(label,()->{hideKeyboard();action.run();});
        if(authForm&&!primaryAdded){
            primaryAdded=true;b.setBackground(primaryBackground());b.setTextColor(0xff102031);b.setTextSize(16);
        }
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(authForm?43:46));p.topMargin=dp(8);root.addView(b,p);return b;
    }
    private LinearLayout toolbar(){
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);root.addView(bar,new LinearLayout.LayoutParams(-1,dp(44)));return bar;
    }
    private Button tool(LinearLayout bar,String label,Runnable action){
        Button b=makeButton(label,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-1,1);p.setMargins(dp(3),0,dp(3),0);bar.addView(b,p);return b;
    }
    private void showLogin(String message){
        screen="login";busy=false;sessionUntil=0;
        authPage("Welcome home.",message.isEmpty()?"Sign in to your Vivint account.":"Sign-in · "+message,"Account access");
        EditText user=field("Vivint email",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass=field("Vivint password",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("Secure sign in  ›",()->{
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
                    setCameras(found);showEnrollment(client);
                });
            }catch(Exception e){
                client.clear();String error=safe(e);
                ui.post(()->{if(current(g)){pending=null;showLogin(error);}});
            }
        });
    }
    private void showMfa(VivintClient client){
        screen="mfa";busy=false;
        authPage("Verify your identity.","Enter the code from your latest Vivint message.","Two-step verification");
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
        authPage("Make it yours.","Choose a six-digit PIN. Your Vivint password will not be saved.","Protect your cameras");
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
                        pending=null;busy=false;grantAfterPin(until);
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
        authPage("Welcome back.",message.isEmpty()?"Enter your six-digit PIN to unlock live cameras.":message,"Protected access");
        EditText pin=field("Security PIN",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        button("Unlock cameras  ›",()->{
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
                        setCameras(found);grantAfterPin(until);busy=false;
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
    private String cameraKey(VivintClient.Camera c){return c.key;}
    private SharedPreferences cameraPrefs(){return getSharedPreferences("camera_layout",MODE_PRIVATE);}
    private boolean hidden(VivintClient.Camera c){return cameraPrefs().getBoolean("hidden:"+cameraKey(c),false);}
    private boolean favorite(VivintClient.Camera c){return cameraPrefs().getBoolean("favorite:"+cameraKey(c),false);}
    private void setCameras(List<VivintClient.Camera> found){
        allCameras.clear();allCameras.addAll(found);applyLayout();
        String last=cameraPrefs().getString("lastCamera","");
        for(int i=0;i<cameras.size();i++)if(last.equals(cameras.get(i).key)){selectedIndex=i;gridPage=i/capacity();break;}
    }
    private void applyLayout(){
        allCameras.sort(Comparator.comparingInt((VivintClient.Camera c)->favorite(c)?0:1)
            .thenComparingInt(c->cameraPrefs().getInt("order:"+cameraKey(c),Integer.MAX_VALUE)));
        cameras=new ArrayList<>();
        for(VivintClient.Camera c:allCameras)if(!hidden(c))cameras.add(c);
    }
    private int capacity(){
        return cameraPrefs().getInt("layout",4)==1?1:4;
    }
    private void showManage(){
        if(settingsUntil<=SystemClock.elapsedRealtime()){settingsCategory="Cameras";verifySettings();return;}
        if(!unlocked("manage","Camera organization","Favorite cameras appear first. Hidden cameras remain available in All cameras.",true))return;
        ui.removeCallbacks(settingsExpiry);ui.postDelayed(settingsExpiry,Math.max(1,settingsUntil-SystemClock.elapsedRealtime()));
        section("Dashboard layout");
        LinearLayout layouts=toolbar();
        tool(layouts,capacity()==1?"● Focus":"Focus",()->{cameraPrefs().edit().putInt("layout",1).apply();gridPage=0;showManage();});
        tool(layouts,capacity()==4?"● Quad":"Quad",()->{cameraPrefs().edit().putInt("layout",4).apply();gridPage=0;showManage();});
        section("Your cameras");
        for(int i=0;i<allCameras.size();i++){
            final int index=i;VivintClient.Camera camera=allCameras.get(i);
            root.addView(text(camera.name,17,WHITE));
            LinearLayout bar=toolbar();
            tool(bar,favorite(camera)?"★ Favorite":"☆ Favorite",()->{cameraPrefs().edit().putBoolean("favorite:"+cameraKey(camera),!favorite(camera)).apply();applyLayout();showManage();});
            tool(bar,hidden(camera)?"Show":"Hide",()->{cameraPrefs().edit().putBoolean("hidden:"+cameraKey(camera),!hidden(camera)).apply();applyLayout();showManage();});
            Button up=tool(bar,"↑ Move",()->moveCamera(index,-1));up.setEnabled(i>0);
            Button down=tool(bar,"↓ Move",()->moveCamera(index,1));down.setEnabled(i+1<allCameras.size());
        }
        button("Done",this::showGrid);
    }
    private void moveCamera(int index,int delta){
        int other=index+delta;if(other<0||other>=allCameras.size())return;
        if(favorite(allCameras.get(index))!=favorite(allCameras.get(other))){status.setText("Reorder within the same favorite group.");return;}
        Collections.swap(allCameras,index,other);
        SharedPreferences.Editor edit=cameraPrefs().edit();
        for(int i=0;i<allCameras.size();i++)edit.putInt("order:"+cameraKey(allCameras.get(i)),i);
        edit.apply();applyLayout();showManage();
    }
    private void openRequested(){
        String requested=targetCamera;targetCamera=null;
        if(requested!=null)for(VivintClient.Camera c:allCameras)if(requested.equals(c.name)){singleReturn=notificationEntry?"exit":"grid";showSingle(c,preferredHd(c));return;}
        if(notificationOnly){notificationOnly=false;sessionUntil=0;viewUntil=0;showPin("Camera not found. Unlock to view all cameras.");return;}
        notificationEntry=false;showGrid();
    }
    private boolean unlocked(String name,String title,String description,boolean scroll){
        if(sessionUntil<=System.currentTimeMillis()||viewUntil<=System.currentTimeMillis()||!vault.exists()){expireView();return false;}
        if(notificationOnly&&!"single".equals(name)){finish();return false;}
        releaseAll();screen=name;busy=false;page(title,description,scroll);
        if(!"settings".equals(name)&&!"manage".equals(name)&&!"changePin".equals(name))ui.removeCallbacks(settingsExpiry);
        if(securityPrefs().getBoolean("keepAwake",true)&&("grid".equals(name)||"single".equals(name)))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        resetIdle();
        return true;
    }
    private int pages(){return Math.max(1,(cameras.size()+capacity()-1)/capacity());}
    private void changePage(int delta){
        int next=gridPage+delta;if(next<0||next>=pages())return;
        gridPage=next;selectedIndex=gridPage*capacity();showGrid();
    }
    private void showGrid(){
        gridPage=Math.max(0,Math.min(gridPage,pages()-1));
        if(!unlocked("grid","Home Security",cameras.size()+" cameras"+(overview==null?"":"  ·  "+overview.arm+" (last checked)")+(pages()>1?"  ·  Page "+(gridPage+1)+" of "+pages():""),false))return;
        LinearLayout bar=toolbar();
        Button list=tool(bar,"All cameras",this::showList);
        tool(bar,"Activity",this::showActivity);
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
        int columns=capacity()==1?1:2;
        int rows=capacity()==1?1:2;
        for(int row=0;row<rows;row++){
            LinearLayout line=new LinearLayout(this);line.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(line,new LinearLayout.LayoutParams(-1,0,1));
            for(int col=0;col<columns;col++){
                int local=row*columns+col,index=gridPage*capacity()+local;
                LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setPadding(dp(6),dp(5),dp(6),dp(6));
                tile.setBackground(glass());
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
                TextView name=text((favorite(camera)?"★  ":"")+camera.name,15,WHITE);
                name.setPadding(dp(9),0,dp(8),0);name.setGravity(Gravity.CENTER_VERTICAL);name.setSingleLine(true);
                tile.addView(name,new LinearLayout.LayoutParams(-1,dp(28)));
                TextView caption=text("",11,MUTED);caption.setVisibility(View.GONE);caption.setSingleLine(true);tile.addView(caption);
                tile.setId(View.generateViewId());tile.setFocusable(true);tile.setClickable(true);
                tile.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                tile.setContentDescription(camera.name+" camera");tile.setOnClickListener(view->openFromGrid(index));
                tile.setOnLongClickListener(view->{cameraOptions(camera,index);return true;});
                tileButtons.add(tile);frame.setFocusable(false);
                tile.setOnFocusChangeListener((view,focused)->{
                    GradientDrawable border=shape(focused?0xff263e50:0xff101823,focused?ACCENT:0xff29394a);
                    border.setStroke(dp(focused?3:1),focused?ACCENT:0xff29394a);tile.setBackground(border);
                    if(focused){selectedIndex=index;cameraPrefs().edit().putString("lastCamera",camera.key).apply();}
                });
                playback.postDelayed(()->{
                    if(videoGeneration==id&&"grid".equals(screen))start(camera,false,layout,caption);
                },local*450L);
            }
        }
        for(int i=0;i<tileButtons.size();i++){
            View b=tileButtons.get(i);
            if(columns==2&&i%2==0&&i+1<tileButtons.size())b.setNextFocusRightId(tileButtons.get(i+1).getId());
            if(i%2==1)b.setNextFocusLeftId(tileButtons.get(i-1).getId());
            if(i+2<tileButtons.size())b.setNextFocusDownId(tileButtons.get(i+2).getId());
            if(i>=2)b.setNextFocusUpId(tileButtons.get(i-2).getId());
            else{if(list.getId()==View.NO_ID)list.setId(View.generateViewId());b.setNextFocusUpId(list.getId());}
        }
        if(!tileButtons.isEmpty()){
            int focus=Math.max(0,Math.min(selectedIndex-gridPage*capacity(),tileButtons.size()-1));tileButtons.get(focus).requestFocus();
        }else list.requestFocus();
        loadOverview(false);
    }
    private void cameraOptions(VivintClient.Camera camera,int index){
        new android.app.AlertDialog.Builder(this).setTitle(camera.name)
            .setItems(new String[]{"Open HD","Open SD","Refresh camera dashboard"},(dialog,which)->{
                if(which==2){refreshCameras();return;}
                selectedIndex=index;singleReturn="grid";showSingle(camera,which==0);
            }).setNegativeButton("Cancel",null).show();
    }
    private void openFromGrid(int index){
        if(!"grid".equals(screen)||index<0||index>=cameras.size()||SystemClock.uptimeMillis()<ignoreConfirmUntil)return;
        selectedIndex=index;singleReturn="grid";VivintClient.Camera camera=cameras.get(index);showSingle(camera,preferredHd(camera));
    }
    private void showList(){
        if(!unlocked("list","All cameras",allCameras.size()+" available cameras  ·  Choose a camera to view live",true))return;
        for(VivintClient.Camera c:allCameras){
            button((favorite(c)?"★  ":"")+c.name+(hidden(c)?"  ·  Hidden":"")+"   ›",()->{
                int index=cameras.indexOf(c);if(index>=0){selectedIndex=index;gridPage=index/capacity();}
                singleReturn="list";showSingle(c,preferredHd(c));
            });
        }
        button("Organize cameras",()->{settingsCategory="Cameras";showSettings();});
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
                    setCameras(found);gridPage=0;selectedIndex=0;busy=false;showGrid();
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
        if("exit".equals(singleReturn)||notificationOnly){finish();return;}
        if("list".equals(singleReturn))showList();else showGrid();
    }
    private boolean preferredHd(VivintClient.Camera camera){
        return getPreferences(MODE_PRIVATE).getBoolean("singleHD:"+camera.key,true);
    }
    private void rememberQuality(VivintClient.Camera camera,boolean hd){
        getPreferences(MODE_PRIVATE).edit().putBoolean("singleHD:"+camera.key,hd).apply();
    }
    private Button qualityButton;
    private void showSingle(VivintClient.Camera camera,boolean hd){singleFocus=0;showSingle(camera,hd,false);}
    private void showSingle(VivintClient.Camera camera,boolean hd,boolean audio){
        if(!unlocked("single",camera.name,"Live camera",false))return;
        activeCamera=camera;activeHd=hd;activeAudio=audio;controlsHidden=false;
        ignoreConfirmUntil=SystemClock.uptimeMillis()+250;
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setPadding(dp(6),dp(5),dp(6),dp(5));bar.setBackground(glass());singleBar=bar;
        tool(bar,"‹ Back",this::leaveSingle);
        Button prev=tool(bar,"‹ Previous",()->{singleFocus=1;stepCamera(-1);});prev.setEnabled(!notificationOnly);prev.setFocusable(!notificationOnly);
        Button next=tool(bar,"Next ›",()->{singleFocus=2;stepCamera(1);});next.setEnabled(!notificationOnly);next.setFocusable(!notificationOnly);
        qualityButton=tool(bar,hd?"Quality: HD":"Quality: SD",()->{singleFocus=3;showSingle(camera,!activeHd,activeAudio);});
        tool(bar,audio?"Mute":"Audio",()->{singleFocus=4;showSingle(camera,activeHd,!activeAudio);});
        tool(bar,"Reconnect",()->{singleFocus=5;showSingle(camera,activeHd,activeAudio);});
        tool(bar,"Hide controls",()->{singleFocus=6;hideControls.run();});
        audioStatus=status;
        FrameLayout frame=new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);frame.setFocusable(true);frame.setFocusableInTouchMode(true);frame.setId(View.generateViewId());
        frame.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        frame.setContentDescription(camera.name+" live video. Left and right switch cameras; Up or OK shows controls.");
        VLCVideoLayout layout=new VLCVideoLayout(this);layout.setFocusable(false);layout.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        frame.addView(layout,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout.LayoutParams controls=new FrameLayout.LayoutParams(-1,dp(56),Gravity.BOTTOM);controls.setMargins(dp(12),0,dp(12),dp(12));frame.addView(bar,controls);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,0,1);p.topMargin=dp(4);root.addView(frame,p);
        singleCanvas=frame;singleHeading=status;
        for(int i=0;i<bar.getChildCount();i++){
            View child=bar.getChildAt(i);child.setId(View.generateViewId());
            final int index=i;
            child.setOnFocusChangeListener((v,focused)->{if(focused)singleFocus=index;});
        }
        for(int i=0;i<bar.getChildCount();i++){
            View child=bar.getChildAt(i);
            int left=(i+bar.getChildCount()-1)%bar.getChildCount(),right=(i+1)%bar.getChildCount();
            while(!bar.getChildAt(left).isFocusable())left=(left+bar.getChildCount()-1)%bar.getChildCount();
            while(!bar.getChildAt(right).isFocusable())right=(right+1)%bar.getChildCount();
            child.setNextFocusLeftId(bar.getChildAt(left).getId());
            child.setNextFocusRightId(bar.getChildAt(right).getId());
            child.setNextFocusUpId(frame.getId());child.setNextFocusDownId(frame.getId());
        }
        frame.setNextFocusUpId(bar.getChildAt(Math.min(singleFocus,bar.getChildCount()-1)).getId());
        bar.getChildAt(Math.min(singleFocus,bar.getChildCount()-1)).requestFocus();
        final int id=videoGeneration;
        playback.postDelayed(()->{
            if(id==videoGeneration&&"single".equals(screen))start(camera,hd,layout,status,0,audio);
        },650);
    }
    private void revealControls(){
        if(singleBar==null)return;
        singleBar.setVisibility(View.VISIBLE);controlsHidden=false;
        if(singleBar.getChildCount()>0)singleBar.getChildAt(Math.min(singleFocus,singleBar.getChildCount()-1)).requestFocus();
    }
    private void stepCamera(int delta){
        if(notificationOnly||!"single".equals(screen)||activeCamera==null||allCameras.isEmpty())return;
        int index=allCameras.indexOf(activeCamera);
        int next=(Math.max(0,index)+delta+allCameras.size())%allCameras.size();
        VivintClient.Camera camera=allCameras.get(next);
        int visibleIndex=cameras.indexOf(camera);
        if(visibleIndex>=0){selectedIndex=visibleIndex;gridPage=visibleIndex/capacity();}
        showSingle(camera,preferredHd(camera),false);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int code=event.getKeyCode();
        boolean confirm=code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER||code==KeyEvent.KEYCODE_NUMPAD_ENTER;
        if(confirm&&("single".equals(screen)||"grid".equals(screen))){
            if(event.getAction()==KeyEvent.ACTION_DOWN){
                if(event.getRepeatCount()>0){
                    if(event.getRepeatCount()==1&&"grid".equals(screen)&&getCurrentFocus()!=null&&getCurrentFocus().isLongClickable()){
                        confirmDown=false;getCurrentFocus().performLongClick();
                    }
                    return true;
                }
                confirmDown=true;
                return true;
            }
            if(event.getAction()==KeyEvent.ACTION_UP){
                boolean valid=confirmDown;confirmDown=false;
                if(!valid||event.isCanceled())return true;
                View focus=getCurrentFocus();
                if("single".equals(screen)&&focus==singleCanvas){revealControls();return true;}
                if(focus!=null&&focus.isEnabled()&&focus.isClickable())focus.performClick();
                return true;
            }
        }
        if("single".equals(screen)&&getCurrentFocus()==singleCanvas){
            if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT||code==KeyEvent.KEYCODE_DPAD_UP){
                if(event.getAction()==KeyEvent.ACTION_UP){
                    if(code==KeyEvent.KEYCODE_DPAD_UP)revealControls();
                    else stepCamera(code==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);
                }
                return true;
            }
        }
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
        boolean output,closed,frames,audioReady;
        Media media;
        Slot(MediaPlayer player){this.player=player;}
        void close(){
            if(closed)return;closed=true;
            try{player.setEventListener(null);}catch(RuntimeException ignored){}
            try{player.stop();}catch(RuntimeException ignored){}
            try{player.detachViews();}catch(RuntimeException ignored){}
            try{player.release();}catch(RuntimeException ignored){}
            if(media!=null){try{media.release();}catch(RuntimeException ignored){}media=null;}
        }
    }
    private void start(VivintClient.Camera camera,boolean hd,VLCVideoLayout layout,TextView label){
        start(camera,hd,layout,label,0,false);
    }
    private void reconnect(VivintClient.Camera camera,boolean hd,VLCVideoLayout layout,TextView label,int attempt,boolean audio,int id,Slot slot){
        if(id!=videoGeneration)return;
        if(slot!=null){
            if(slot.closed)return;
            slot.close();players.remove(slot);
        }
        boolean single="single".equals(screen);
        if(attempt>=(single?3:1)){
            label.setText("Video unavailable · Try switching quality");label.setVisibility(View.VISIBLE);return;
        }
        boolean alternate=camera.streams.stream().anyMatch(s->s.label.startsWith(hd?"SD":"HD"));
        boolean nextHd=single&&attempt%2==0&&alternate?!hd:hd;
        label.setText(single?"Reconnecting video · "+(nextHd?"HD":"SD")+"…":"Reconnecting…");label.setVisibility(View.VISIBLE);
        ViewGroup container=layout.getParent() instanceof ViewGroup?(ViewGroup)layout.getParent():null;
        VLCVideoLayout fresh=new VLCVideoLayout(this);fresh.setFocusable(false);fresh.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if(container!=null){container.removeView(layout);container.addView(fresh,0,new FrameLayout.LayoutParams(-1,-1));}
        playback.postDelayed(()->{
            if(id==videoGeneration&&!stopped&&unlockedScreen()){
                if(single){activeHd=nextHd;if(qualityButton!=null)qualityButton.setText(nextHd?"Quality: HD":"Quality: SD");}
                start(camera,nextHd,container==null?layout:fresh,label,attempt+1,audio);
            }
        },900);
    }
    private void start(VivintClient.Camera camera,boolean hd,VLCVideoLayout layout,TextView label,int attempt,boolean audio){
        VivintClient.Stream stream=selectStream(camera,hd);
        String prefix="single".equals(screen)?camera.name+" · ":"";
        if(stream==null){label.setText(prefix+"Unavailable");return;}
        final int id=videoGeneration;
        Slot created=null;
        try{
            if(vlc==null){
                ArrayList<String> opts=new ArrayList<>();
                opts.add("--quiet");
                opts.add("--network-caching=1000");opts.add("--no-video-title-show");
                vlc=new LibVLC(this,opts);
            }
            Slot slot=new Slot(new MediaPlayer(vlc));created=slot;players.add(slot);
            slot.player.attachViews(layout,null,false,false);
            slot.player.setEventListener(event->{
                int type=event.type;
                int outputs=type==MediaPlayer.Event.Vout?event.getVoutCount():0;
                ui.post(()->{
                    if(id!=videoGeneration||slot.closed)return;
                    if(type==MediaPlayer.Event.Vout&&outputs>0){
                        slot.output=true;
                        if("single".equals(screen)&&!slot.frames)label.setText("Waiting for video…");
                        else if(!"single".equals(screen))label.setVisibility(View.GONE);
                    }
                    if(audio&&(type==MediaPlayer.Event.Playing||type==MediaPlayer.Event.Vout)){
                        playback.postDelayed(()->{
                            if(id!=videoGeneration||slot.closed)return;
                            try{
                                int tracks=slot.player.getAudioTracksCount();
                                if(tracks>0){
                                    slot.player.setVolume(100);
                                    int current=slot.player.getAudioTrack();
                                    MediaPlayer.TrackDescription[] available=slot.player.getAudioTracks();
                                    if(current<0&&available!=null)for(MediaPlayer.TrackDescription track:available)if(track.id>=0){slot.player.setAudioTrack(track.id);break;}
                                    slot.audioReady=slot.player.getAudioTrack()>=0;
                                    if(slot.frames)label.setText(slot.audioReady?"Live · Audio on":"Live · Audio track unavailable");
                                }else if(slot.frames)label.setText("Live · No audio track reported by camera");
                            }catch(RuntimeException e){if(slot.frames)label.setText("Live · Audio status unavailable");}
                        },2500);
                    }
                    if(type==MediaPlayer.Event.EncounteredError||type==MediaPlayer.Event.EndReached)
                        reconnect(camera,hd,layout,label,attempt,audio,id,slot);
                });
            });
            Media media=new Media(vlc,Uri.parse(stream.address));slot.media=media;
            {
                media.setHWDecoderEnabled(attempt<2,false);
                media.addOption(":rtsp-tcp");media.addOption(":rtsp-user="+stream.username);
                media.addOption(":rtsp-pwd="+stream.password);
                if(!audio)media.addOption(":no-audio");
                else media.addOption(":audio");
                slot.player.setMedia(media);
            }
            slot.player.setVolume(audio?100:0);
            slot.player.play();
            if("single".equals(screen)){
                final long began=SystemClock.uptimeMillis();
                playback.postDelayed(new Runnable(){
                    long previous=-1;
                    long lastFrame=began;
                    @Override public void run(){
                        if(id!=videoGeneration||slot.closed)return;
                        long displayed=displayedPictures(slot);
                        long now=SystemClock.uptimeMillis();
                        if(displayed>0&&displayed!=previous){
                            lastFrame=now;
                            if(!slot.frames){slot.frames=true;rememberQuality(camera,hd);}
                            label.setText("Live"+(audio?(slot.audioReady?" · Audio on":" · Audio requested"):" · Muted"));
                        }
                        previous=displayed;
                        if(displayed<0&&slot.output){
                            // Some LibVLC builds do not expose media statistics.
                            // Do not assume frames are displayed; retain manual quality recovery.
                            label.setText("Video connected"+(slot.audioReady?" · Audio on":""));
                            return;
                        }
                        if(now-lastFrame>12000){
                            reconnect(camera,hd,layout,label,attempt,audio,id,slot);return;
                        }
                        playback.postDelayed(this,2000);
                    }
                },2500);
            }else playback.postDelayed(()->{
                if(id==videoGeneration&&!slot.closed&&!slot.output)
                    reconnect(camera,hd,layout,label,attempt,audio,id,slot);
            },20000);
        }catch(Exception|LinkageError e){
            if(created!=null){created.close();players.remove(created);}
            reconnect(camera,hd,layout,label,attempt,audio,id,null);
        }
    }
    private long displayedPictures(Slot slot){
        try{
            Object stats=slot.media.getClass().getMethod("getStats").invoke(slot.media);
            if(stats==null)return 0;
            return ((Number)stats.getClass().getField("displayedPictures").get(stats)).longValue();
        }catch(Exception|LinkageError e){return -1;}
    }
    private void releaseAll(){
        videoGeneration++;playback.removeCallbacksAndMessages(null);ui.removeCallbacks(hideControls);
        qualityButton=null;singleCanvas=null;singleBar=null;singleHeading=null;activeCamera=null;activeAudio=false;controlsHidden=false;tileButtons.clear();
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
        if(notificationOnly){finish();return;}
        if(settingsUntil<=SystemClock.elapsedRealtime()){verifySettings();return;}
        if(!unlocked("settings","Settings","Home Security · Premium Glass",false))return;
        ui.removeCallbacks(settingsExpiry);ui.postDelayed(settingsExpiry,Math.max(1,settingsUntil-SystemClock.elapsedRealtime()));
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.HORIZONTAL);root.addView(shell,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(this);nav.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(dp(180),-1);np.rightMargin=dp(12);shell.addView(nav,np);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);shell.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(12),dp(5),dp(12),dp(8));content.setBackground(glass());scroll.addView(content);
        Button chosen=null;
        for(String category:new String[]{"Cameras","Appearance","Security","Notifications","Storage","Account","About"}){
            Button b=makeButton((settingsCategory.equals(category)?"●  ":"")+category,()->{settingsCategory=category;showSettings();});
            b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(42));bp.bottomMargin=dp(4);nav.addView(b,bp);
            if(settingsCategory.equals(category))chosen=b;
        }
        Button done=makeButton("‹ Cameras",this::showGrid);nav.addView(done,new LinearLayout.LayoutParams(-1,dp(42)));
        root=content;section(settingsCategory);
        switch(settingsCategory){
            case "Cameras":
                button("Refresh cameras ("+allCameras.size()+" found)",this::refreshCameras);
                button("Organize cameras / layout",this::showManage);
                root.addView(text("Single-camera quality is remembered when video frames are confirmed.",14,MUTED));
                break;
            case "Appearance":
                toggleSecurity("Keep screen awake on live cameras","keepAwake",true);
                root.addView(text("Premium Glass · Deep blue / charcoal · High-contrast blue focus",14,MUTED));
                break;
            case "Security":
                root.addView(text("PIN mode: "+pinMode()+" · "+pinHours()+" hours",16,ACCENT));
                button("Change PIN protection mode",this::chooseMode);
                button("Remember authorization duration",this::chooseDuration);
                toggleSecurity("Open notification cameras without PIN","notification",true);
                toggleSecurity("Require PIN after TV sleep","sleep",false);
                toggle("Lock after five minutes of inactivity","idleLock",false,this::resetIdle);
                button("Change security PIN",this::showChangePin);
                button("Lock now",()->lock("Camera access locked."));
                break;
            case "Notifications":
                toggle("Do not disturb","dnd",false,null);
                toggle("Enable camera alerts (experimental)","enabled",false,()->{
                    if(alerts.enabled()){
                        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
                            beginPermissionTrip();requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},100);
                        }
                        AlertService.start(this);
                    }else AlertService.stop(this);
                });
                toggle("When Armed Stay","stay",true,null);toggle("When Armed Away","away",true,null);toggle("When Disarmed","disarmed",false,null);
                toggle("Person lingering / loitering","linger",true,null);toggle("Doorbell pressed","doorbell",true,null);toggle("General motion","motion",false,null);
                toggle("Interactive top-right popup","overlay",false,()->{if(alerts.get("overlay",false)&&!Settings.canDrawOverlays(this))openOverlaySettings();});
                button("Overlay permission settings",this::openOverlaySettings);button("Test notification",this::testNotification);
                root.addView(text("Overlay permission: "+(Settings.canDrawOverlays(this)?"Granted":"Not granted")+" · Alerts poll about every 30 seconds.",13,MUTED));
                break;
            case "Storage":
                button("Camera recording & storage health",this::showRecordingDiagnostics);
                button("Smart Drive compatibility",this::showRecordingStatus);
                root.addView(text("Recorded playback is not yet implemented.",14,MUTED));break;
            case "Account":
                long days=Math.max(1,(sessionUntil-System.currentTimeMillis()+86399999L)/86400000L);
                root.addView(text("Full Vivint sign-in expires in "+days+" day(s).",16,ACCENT));
                button("Forget session / Sign out",this::confirmSignOut);break;
            default:root.addView(text("Home Security 0.11 · Premium Glass 2.0\nRead-only Vivint integration · Local live video · Encrypted session",15,MUTED));
        }
        if(chosen!=null)chosen.requestFocus();
    }
    private void verifySettings(){
        if(sessionUntil<=System.currentTimeMillis()||viewUntil<=System.currentTimeMillis()){expireView();return;}
        releaseAll();screen="settingsPin";busy=false;
        authPage("Protected settings","Enter your PIN to change security, account or camera preferences.","Settings access");
        EditText pin=field("Security PIN",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        button("Continue",()->{
            if(busy)return;String value=pin.getText().toString();
            if(!value.matches("[0-9]{6}")){status.setText("Enter your six-digit PIN.");return;}
            pin.setText("");busy=true;int gen=generation;
            worker.execute(()->{
                try{long until=vault.checkPin(value);ui.post(()->{
                    if(!current(gen)||!"settingsPin".equals(screen))return;
                    grantAfterPin(until);settingsUntil=SystemClock.elapsedRealtime()+300000L;busy=false;showSettings();
                });}catch(Exception e){ui.post(()->{if(current(gen)&&"settingsPin".equals(screen)){busy=false;status.setText(safe(e));if(!vault.exists()||safe(e).contains("Locked")||safe(e).contains("PIN locked"))lock(safe(e));}});}
            });
        });
        button("Cancel",this::showGrid);pin.requestFocus();resetIdle();
    }
    private void toggleSecurity(String title,String key,boolean def){
        Switch s=new Switch(this);s.setText(title);s.setTextColor(WHITE);s.setTextSize(15);s.setPadding(dp(10),dp(3),dp(4),dp(3));s.setBackground(focusBackground());
        s.setChecked(securityPrefs().getBoolean(key,def));s.setFocusable(true);s.setEnabled(!"strict".equals(pinMode())||"keepAwake".equals(key));
        root.addView(s,new LinearLayout.LayoutParams(-1,dp(46)));
        s.setOnCheckedChangeListener((view,on)->securityPrefs().edit().putBoolean(key,on).apply());
    }
    private void chooseMode(){
        String[] labels={"Convenience · Live viewing without PIN","Balanced · Remember PIN","Strict · PIN each entry"};
        String[] modes={"convenience","balanced","strict"};int selected=Arrays.asList(modes).indexOf(pinMode());
        new android.app.AlertDialog.Builder(this).setTitle("PIN protection").setSingleChoiceItems(labels,selected,(dialog,index)->{
            securityPrefs().edit().putString("mode",modes[index]).apply();dialog.dismiss();grantAfterPin(sessionUntil);showSettings();
        }).setNegativeButton("Cancel",null).show();
    }
    private void chooseDuration(){
        int[] hours={1,12,24,168,720};String[] labels={"1 hour","12 hours","24 hours","7 days","30 days"};
        int selected=-1;for(int i=0;i<hours.length;i++)if(hours[i]==pinHours())selected=i;
        new android.app.AlertDialog.Builder(this).setTitle("Remember PIN authorization").setSingleChoiceItems(labels,selected,(dialog,index)->{
            securityPrefs().edit().putInt("hours",hours[index]).apply();dialog.dismiss();grantAfterPin(sessionUntil);showSettings();
        }).setNegativeButton("Cancel",null).show();
    }
    private void testNotification(){
        if(allCameras.isEmpty()){status.setText("No available cameras.");return;}
        final int request=++testRequest;
        final String camera=allCameras.get(0).name;
        getSharedPreferences("alert_diagnostics",MODE_PRIVATE).edit().putString("test","Starting test…").apply();
        if(!AlertService.test(this,camera)){status.setText("Could not start notification service.");return;}
        status.setText("Testing notification and overlay…");
        for(int i=1;i<=5;i++){
            ui.postDelayed(()->{
                if(request!=testRequest||!"settings".equals(screen)||!visible)return;
                String result=getSharedPreferences("alert_diagnostics",MODE_PRIVATE).getString("test","No result.");
                status.setText(result);
            },i*1000L);
        }
    }
    private boolean overviewLoading=false;
    private void loadOverview(boolean force){
        if(overviewLoading||!unlockedScreen()||(!force&&overview!=null&&SystemClock.elapsedRealtime()-overviewAt<60000))return;
        overviewLoading=true;int g=generation;
        worker.execute(()->{
            try{
                VivintClient.Overview value=vault.use(VivintClient::overview);
                ui.post(()->{
                    overviewLoading=false;if(!current(g))return;
                    overview=value;overviewAt=SystemClock.elapsedRealtime();
                    if("grid".equals(screen))status.setText(cameras.size()+" cameras  ·  "+value.arm+" (last checked)"+(pages()>1?"  ·  Page "+(gridPage+1)+" of "+pages():""));
                    if("activity".equals(screen))renderActivity();
                });
            }catch(Exception e){ui.post(()->{overviewLoading=false;if(current(g)&&"activity".equals(screen))status.setText("Recent activity could not be loaded. Try refresh.");});}
        });
    }
    private void showActivity(){
        if(!unlocked("activity","Recent activity","Read-only Vivint history · Select an event to view its camera live.",true))return;
        renderActivity();loadOverview(true);
    }
    private void renderActivity(){
        if(!"activity".equals(screen))return;
        page("Recent activity",overview==null?"Loading activity…":overview.arm+"  ·  History as of "+android.text.format.DateFormat.format("h:mm a",System.currentTimeMillis()-(SystemClock.elapsedRealtime()-overviewAt)),true);
        button("Refresh activity",()->loadOverview(true));
        if(overview!=null){
            if(!overview.historyAvailable)root.addView(text("History unavailable for one or more panels. Refresh to try again.",15,MUTED));
            else if(overview.events.isEmpty())root.addView(text("No recent recognized camera or alarm events.",15,MUTED));
            for(VivintClient.History event:overview.events){
                String time=android.text.format.DateFormat.format("MMM d  ·  h:mm a",event.time).toString();
                VivintClient.Camera match=null;
                for(VivintClient.Camera camera:allCameras)if(camera.name.equalsIgnoreCase(event.camera)){match=camera;break;}
                final VivintClient.Camera target=match;
                if(target!=null)button(event.kind+"  ·  "+target.name+"  ·  "+time,()->{singleReturn="grid";showSingle(target,true);});
                else{TextView row=text(event.kind+"  ·  "+time,15,MUTED);row.setPadding(dp(10),dp(10),0,dp(10));root.addView(row);}
            }
        }
        button("Back to cameras",this::showGrid);
    }
    private void showRecordingDiagnostics(){
        if(busy)return;
        busy=true;status.setText("Reading recording capability fields…");
        int g=generation;
        worker.execute(()->{
            try{
                String report=vault.use(VivintClient::recordingDiagnostics);
                ui.post(()->{
                    if(!current(g)||!"settings".equals(screen))return;
                    busy=false;
                    TextView details=new TextView(this);details.setText(report);details.setTextColor(WHITE);
                    details.setTextSize(12);details.setTypeface(android.graphics.Typeface.MONOSPACE);details.setPadding(dp(15),dp(12),dp(15),dp(12));
                    ScrollView scroll=new ScrollView(this);scroll.setFocusable(true);scroll.addView(details);
                    new android.app.AlertDialog.Builder(this).setTitle("Recording capability diagnostics")
                        .setView(scroll).setPositiveButton("Close",null).show();
                    status.setText("Read-only recording capability report ready.");
                });
            }catch(Exception e){ui.post(()->{if(current(g)&&"settings".equals(screen)){busy=false;status.setText("Recording diagnostics unavailable.");}});}
        });
    }
    private void showRecordingStatus(){
        if(busy)return;
        busy=true;status.setText("Checking for a compatible Smart Drive…");int g=generation;
        worker.execute(()->{
            try{
                VivintClient.Overview value=vault.use(VivintClient::overview);
                ui.post(()->{
                    if(!current(g)||!"settings".equals(screen))return;
                    busy=false;overview=value;overviewAt=SystemClock.elapsedRealtime();
                    status.setText(value.dvr?"Smart Drive service found. Secure recorded playback still requires implementation.":"No compatible Smart Drive service reported by Vivint. Live video is available.");
                });
            }catch(Exception e){ui.post(()->{if(current(g)&&"settings".equals(screen)){busy=false;status.setText("Recording compatibility could not be checked.");}});}
        });
    }
    private void showChangePin(){
        if(settingsUntil<=SystemClock.elapsedRealtime()){settingsCategory="Security";verifySettings();return;}
        if(!unlocked("changePin","Change PIN","Verify your current PIN, then choose a new six-digit PIN. Your 30-day session stays unchanged.",true))return;
        ui.removeCallbacks(settingsExpiry);ui.postDelayed(settingsExpiry,Math.max(1,settingsUntil-SystemClock.elapsedRealtime()));
        authPage("Change your PIN.","Your 30-day Vivint session will stay unchanged.","Protected access");
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
                        busy=false;grantAfterPin(until);settingsUntil=SystemClock.elapsedRealtime()+300000L;showSettings();status.setText("PIN changed successfully. Your session expiration is unchanged.");
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
        if("list".equals(screen)||"settings".equals(screen)||"activity".equals(screen)||"manage".equals(screen)){showGrid();return;}
        if("settingsPin".equals(screen)){if(!busy)showGrid();return;}
        if("changePin".equals(screen)){if(!busy)showSettings();return;}
        if("refresh".equals(screen)){generation++;busy=false;showGrid();return;}
        if("grid".equals(screen)){finish();return;}
        if("mfa".equals(screen)||"enroll".equals(screen)){lock("");return;}
        super.onBackPressed();
    }

}
