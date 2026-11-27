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
    private org.videolan.libvlc.LibVLC vlc;
    private org.videolan.libvlc.MediaPlayer vlcPlayer;
    private int playbackId=0;
    private VivintClient.Camera selectedCamera;
    private final android.os.Handler playbackHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean receivedVideo=false;
    private int generation=0;
    private String screen="login";
    private boolean busy=false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF);
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
        screen="cameras";busy=false;releasePlayer();selectedCamera=null;
        page("Local cameras",cameras.size()+" stream(s) discovered. Select one to test playback.");
        Button first=null;
        for(VivintClient.Camera camera:cameras){
            Button b=button(camera.name,()->chooseStream(camera));
            if(first==null) first=b;
        }
        button("Sign out",()->{cameras.clear();showLogin("");});
        if(first!=null)first.requestFocus();
    }
    private void chooseStream(VivintClient.Camera camera){
        releasePlayer();selectedCamera=camera;screen="streams";
        page(camera.name,"Select a local stream. VLC is recommended for this test.");
        Button first=null;
        for(VivintClient.Stream stream:camera.streams){
            Button b=button("VLC · "+stream.label+" · "+stream.endpoint(),()->playVlc(camera,stream));
            if(first==null)first=b;
            button("ExoPlayer · "+stream.label,()->playExo(camera,stream));
        }
        button("Back to cameras",this::showCameras);
        if(first!=null)first.requestFocus();
    }
    private void playerPage(VivintClient.Camera camera,VivintClient.Stream stream,String engine){
        releasePlayer();selectedCamera=camera;screen="play";receivedVideo=false;
        page(camera.name,engine+" · "+stream.label+" · Connecting to "+stream.endpoint());
        button("Back to stream options",()->chooseStream(camera)).requestFocus();
    }
    private void playbackTimeout(int id){
        playbackHandler.postDelayed(()->{
            if(id==playbackId&&"play".equals(screen)&&!receivedVideo){
                status.setText("No video after 20 seconds. Try another stream option.");
                releasePlayer();
            }
        },20000);
    }
    private void playVlc(VivintClient.Camera camera,VivintClient.Stream stream){
        playerPage(camera,stream,"VLC");
        final int id=playbackId;
        try{
            org.videolan.libvlc.util.VLCVideoLayout layout=new org.videolan.libvlc.util.VLCVideoLayout(this);
            root.addView(layout,new LinearLayout.LayoutParams(-1,dp(300)));
            ArrayList<String> opts=new ArrayList<>();
            opts.add("--quiet");opts.add("--no-audio");opts.add("--network-caching=1000");opts.add("--no-video-title-show");
            vlc=new org.videolan.libvlc.LibVLC(this,opts);
            vlcPlayer=new org.videolan.libvlc.MediaPlayer(vlc);
            vlcPlayer.attachViews(layout,null,false,false);
            vlcPlayer.setEventListener(event->{
                int type=event.type;
                runOnUiThread(()->{
                    if(id!=playbackId||!"play".equals(screen))return;
                    if(type==org.videolan.libvlc.MediaPlayer.Event.Playing)status.setText("VLC connected. Waiting for picture...");
                    if(type==org.videolan.libvlc.MediaPlayer.Event.Vout && event.getVoutCount()>0){
                        receivedVideo=true;status.setText("VLC video output active.");
                    }
                    if(type==org.videolan.libvlc.MediaPlayer.Event.EncounteredError){
                        status.setText("VLC playback failed. Try another stream or ExoPlayer.");
                        releasePlayer();
                    }
                    if(type==org.videolan.libvlc.MediaPlayer.Event.EndReached){
                        status.setText("Stream ended.");releasePlayer();
                    }
                });
            });
            org.videolan.libvlc.Media media=new org.videolan.libvlc.Media(vlc,Uri.parse(stream.address));
            try{
                media.setHWDecoderEnabled(true,false);
                media.addOption(":rtsp-tcp");
                media.addOption(":rtsp-user="+stream.username);
                media.addOption(":rtsp-pwd="+stream.password);
                media.addOption(":no-audio");
                vlcPlayer.setMedia(media);
            }finally{media.release();}
            vlcPlayer.play();playbackTimeout(id);
        }catch(Exception | LinkageError e){
            status.setText("VLC could not start ("+e.getClass().getSimpleName()+"). Try ExoPlayer.");
            releasePlayer();
        }
    }
    private static String safePlaybackError(PlaybackException e){
        for(Throwable t=e;t!=null;t=t.getCause()){
            String message=t.getMessage();
            if(message!=null){
                java.util.regex.Matcher match=java.util.regex.Pattern.compile("\\b(OPTIONS|DESCRIBE|SETUP|PLAY|PAUSE|TEARDOWN)\\s+(400|401|403|404|405|454|461|500|503)\\b").matcher(message);
                if(match.find())return match.group(1)+" "+match.group(2);
            }
            if(t instanceof java.net.ConnectException)return "Connection refused or unavailable";
            if(t instanceof java.net.SocketTimeoutException)return "Connection timed out";
            if(t instanceof java.net.NoRouteToHostException)return "No route to stream host";
        }
        return e.getErrorCodeName()+(e.getCause()==null?"":" / "+e.getCause().getClass().getSimpleName());
    }
    @androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
    private void playExo(VivintClient.Camera camera,VivintClient.Stream stream){
        playerPage(camera,stream,"ExoPlayer");
        final int id=playbackId;
        video=new PlayerView(this);video.setUseController(false);
        root.addView(video,new LinearLayout.LayoutParams(-1,dp(300)));
        try{
            player=new ExoPlayer.Builder(this).build();
            video.setPlayer(player);player.setVolume(0);
            player.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(id!=playbackId||!"play".equals(screen))return;
                    if(state==Player.STATE_READY)status.setText("Connected. Waiting for live video...");
                    if(state==Player.STATE_BUFFERING)status.setText("Buffering...");
                    if(state==Player.STATE_ENDED){status.setText("Stream ended.");releasePlayer();}
                }
                @Override public void onRenderedFirstFrame(){
                    if(id==playbackId&&"play".equals(screen)){receivedVideo=true;status.setText("Live video received.");}
                }
                @Override public void onPlayerError(PlaybackException e){
                    if(id==playbackId&&"play".equals(screen)){
                        status.setText("ExoPlayer failed: "+safePlaybackError(e));releasePlayer();
                    }
                }
            });
            player.setMediaSource(new RtspMediaSource.Factory().setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(Uri.parse(stream.media3Url()))));
            player.prepare();player.play();playbackTimeout(id);
        }catch(Exception e){status.setText("Unable to start the player.");releasePlayer();}
    }
    private void releasePlayer(){
        playbackId++;playbackHandler.removeCallbacksAndMessages(null);
        if(video!=null){video.setPlayer(null);video=null;}
        if(player!=null){player.release();player=null;}
        if(vlcPlayer!=null){
            vlcPlayer.setEventListener(null);
            vlcPlayer.stop();vlcPlayer.detachViews();vlcPlayer.release();vlcPlayer=null;
        }
        if(vlc!=null){vlc.release();vlc=null;}
    }
    @Override public void onBackPressed(){
        if("play".equals(screen)&&selectedCamera!=null){chooseStream(selectedCamera);return;}
        if("streams".equals(screen)){showCameras();return;}
        if("mfa".equals(screen)){generation++;if(client!=null)client.clear();client=null;showLogin("");return;}
        super.onBackPressed();
    }
    @Override protected void onStop(){
        super.onStop(); generation++; releasePlayer(); cameras.clear();selectedCamera=null;
        if(client!=null){client.clear();client=null;}
        busy=false;
        if(!isFinishing())showLogin("");
    }
    @Override protected void onDestroy(){
        releasePlayer(); executor.shutdownNow();super.onDestroy();
    }
}
