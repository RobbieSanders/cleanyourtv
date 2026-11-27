package com.gathin.cameratest;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.ui.PlayerView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private VivintClient client;
    private List<VivintClient.Camera> cameras=new ArrayList<>();
    private LinearLayout root;
    private TextView status;
    private ExoPlayer player;
    private PlayerView video;
    private int generation=0;
    private String screen="login";
    private boolean busy=false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        showLogin("");
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void page(String title,String detail) {
        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12,18,28));
        root.setPadding(dp(32),dp(18),dp(32),dp(18));
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(root);
        setContentView(scroll);
        TextView heading=text(title,26); root.addView(heading);
        status=text(detail,16); status.setPadding(0,dp(5),0,dp(8)); root.addView(status);
    }
    private TextView text(String value,int size){
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(Color.WHITE); return v;
    }
    private EditText field(String hint,int type){
        EditText e=new EditText(this);
        e.setTextColor(Color.WHITE); e.setHintTextColor(0xff9caabd); e.setHint(hint);
        e.setSingleLine(true); e.setInputType(type); e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        e.setSaveEnabled(false); e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        root.addView(e,new LinearLayout.LayoutParams(-1,dp(52))); return e;
    }
    private Button button(String name,Runnable action){
        Button b=new Button(this); b.setText(name); b.setAllCaps(false); b.setFocusable(true);
        b.setOnClickListener(v->action.run()); root.addView(b,new LinearLayout.LayoutParams(-1,dp(52))); return b;
    }
    private void showLogin(String message){
        screen="login"; busy=false;
        page("Camera Test",message.isEmpty()?"Read-only test. Sign in to Vivint. Credentials stay in memory on this TV and are sent to Vivint over HTTPS.":message);
        EditText user=field("Vivint email",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass=field("Vivint password",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        button("Sign in",()->{
            if(busy) return;
            String u=user.getText().toString().trim(), p=pass.getText().toString();
            if(u.isEmpty()||p.isEmpty()){status.setText("Enter your email and password.");return;}
            pass.setText(""); user.setText("");
            client=new VivintClient();
            VivintClient local=client;
            runAuth(local,()->local.login(u,p));
        });
        button("Exit",this::finish);
        user.requestFocus();
    }
    private interface AuthAction { boolean run() throws Exception; }
    private void runAuth(VivintClient local, AuthAction action){
        if(busy) return;
        busy=true; status.setText("Connecting to Vivint...");
        int g=generation;
        executor.execute(()->{
            try{
                boolean complete=action.run();
                if(!complete){
                    runOnUiThread(()->{if(g==generation&&!isFinishing()) showMfa(local);});return;
                }
                List<VivintClient.Camera> found=local.cameras();
                runOnUiThread(()->{
                    if(g!=generation||isFinishing()) return;
                    cameras=found; client=null; showCameras();
                });
            }catch(Exception e){
                local.clear();
                String message=e.getClass()==Exception.class?e.getMessage():"Network or response error ("+e.getClass().getSimpleName()+").";
                runOnUiThread(()->{if(g==generation&&!isFinishing()){client=null;showLogin(message==null?"Sign-in failed.":message);}});
            }
        });
    }
    private void showMfa(VivintClient local){
        busy=false; screen="mfa";
        page("Verify sign-in","Enter the verification code from Vivint. Do not share it.");
        EditText code=field("Verification code",InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        button("Verify",()->{
            if(busy)return;
            String value=code.getText().toString().trim();
            if(!value.matches("[0-9]{4,10}")){status.setText("Enter the numeric code from your newest Vivint message.");return;}
            code.setText("");runAuth(local,()->local.verify(value));
        });
        button("Cancel",()->{generation++;local.clear();client=null;showLogin("");});
        code.requestFocus();
    }
    private void showCameras(){
        screen="cameras";busy=false;releasePlayer();
        page("Local cameras",cameras.size()+" stream(s) discovered. Select one to test playback.");
        Button first=null;
        for(VivintClient.Camera camera:cameras){
            Button b=button(camera.name,()->play(camera));
            if(first==null) first=b;
        }
        button("Sign out",()->{cameras.clear();showLogin("");});
        if(first!=null)first.requestFocus();
    }
    @androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
    private void play(VivintClient.Camera camera){
        releasePlayer();screen="play";
        page(camera.name,"Connecting to local RTSP stream...");
        button("Back to cameras",this::showCameras).requestFocus();
        video=new PlayerView(this);video.setUseController(false);
        root.addView(video,new LinearLayout.LayoutParams(-1,dp(300)));
        try{
            player=new ExoPlayer.Builder(this).build();
            video.setPlayer(player);player.setVolume(0);
            player.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(!"play".equals(screen))return;
                    if(state==Player.STATE_READY)status.setText("Connected. Waiting for live video...");
                    if(state==Player.STATE_BUFFERING)status.setText("Buffering...");
                    if(state==Player.STATE_ENDED)status.setText("Stream ended.");
                }
                @Override public void onRenderedFirstFrame(){if("play".equals(screen))status.setText("Live video received.");}
                @Override public void onPlayerError(PlaybackException e){
                    if("play".equals(screen))status.setText("Playback failed: "+e.getErrorCodeName()+". No credentials displayed.");
                }
            });
            player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(camera.url))));
            player.prepare();player.play();
        }catch(Exception e){status.setText("Unable to start the player.");releasePlayer();}
    }
    private void releasePlayer(){
        if(video!=null){video.setPlayer(null);video=null;}
        if(player!=null){player.release();player=null;}
    }
    @Override public void onBackPressed(){
        if("play".equals(screen)){showCameras();return;}
        if("mfa".equals(screen)){generation++;if(client!=null)client.clear();client=null;showLogin("");return;}
        super.onBackPressed();
    }
    @Override protected void onStop(){
        super.onStop(); generation++; releasePlayer(); cameras.clear();
        if(client!=null){client.clear();client=null;}
        busy=false;
        if(!isFinishing())showLogin("");
    }
    @Override protected void onDestroy(){
        releasePlayer(); executor.shutdownNow();super.onDestroy();
    }
}
