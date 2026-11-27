package com.gathin.cameratest;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.MediaType;
import java.util.concurrent.TimeUnit;
import java.util.Locale;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class VivintClient {
    private static final String ID = "https://id.vivint.com";
    private static final String API = "https://www.vivintsky.com/api";
    private static final String CALLBACK = "vivint://app/oauth_redirect";
    private static final class SessionCookies implements CookieJar {
        private final List<Cookie> values = new ArrayList<>();
        @Override public synchronized void saveFromResponse(HttpUrl url, List<Cookie> incoming) {
            for(Cookie c:incoming) {
                values.removeIf(old -> old.name().equals(c.name()) && old.domain().equals(c.domain()) && old.path().equals(c.path()));
                if(c.expiresAt()>System.currentTimeMillis()) values.add(c);
            }
        }
        @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            values.removeIf(c -> c.expiresAt()<=System.currentTimeMillis());
            List<Cookie> result=new ArrayList<>();
            for(Cookie c:values) if(c.matches(url)) result.add(c);
            result.sort((a,b)->Integer.compare(b.path().length(),a.path().length()));
            return result;
        }
        synchronized void clear(){values.clear();}
    }
    private final SessionCookies cookies = new SessionCookies();
    private final OkHttpClient http = new OkHttpClient.Builder()
        .cookieJar(cookies).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(20,TimeUnit.SECONDS).callTimeout(35,TimeUnit.SECONDS).build();
    private String verifier, state, username, password, token, mfaType;

    static final class Camera {
        final String name;
        final List<Stream> streams;
        Camera(String name, List<Stream> streams) { this.name = name; this.streams = streams; }
    }
    static final class Stream {
        final String label, address, username, password;
        Stream(String label,String address,String username,String password){
            this.label=label;this.address=address;this.username=username;this.password=password;
        }
        String media3Url() throws Exception {
            URI u=URI.create(address);
            String auth=enc(username).replace("+","%20")+":"+enc(password).replace("+","%20");
            return "rtsp://"+auth+"@"+u.getRawAuthority()+
                (u.getRawPath()==null?"":u.getRawPath())+(u.getRawQuery()==null?"":"?"+u.getRawQuery());
        }
        String endpoint(){
            URI u=URI.create(address);
            return u.getHost()+":"+(u.getPort()==-1?554:u.getPort());
        }
    }
    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
    private static String random() {
        byte[] b = new byte[40];
        new SecureRandom().nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP);
    }
    private static String stage(URI uri) {
        String p=uri.getPath();
        if(p.equals("/oauth2/auth")) return "OAuth setup";
        if(p.equals("/idp/api/submit")) return "Account sign-in";
        if(p.equals("/idp/api/validate")) return "Verification code";
        if(p.equals("/oauth2/token")) return "Token exchange";
        if(p.equals("/api/authuser")) return "Account discovery";
        if(p.startsWith("/api/systems/")) return "Device discovery";
        if(p.startsWith("/api/panel-login/")) return "Panel authorization";
        return "Authentication continuation";
    }
    private static String safeError(URI uri,int status,JSONObject result,int cookieCount) {
        String label=stage(uri)+": HTTP "+status;
        String msg=result.optString("msg",result.optString("message",""));
        if("Invalid username and/or password".equalsIgnoreCase(msg)) return label+" (Vivint rejected the email or password).";
        String code=result.optString("error","");
        String description=(msg+" "+code+" "+result.optString("error_description","")+" "+result.optString("errorMessage","")).toLowerCase(Locale.ROOT);
        if(status==429 || description.contains("too many") || description.contains("rate limit"))
            return label+" (rate limited; stop and try later).";
        if(uri.getPath().equals("/idp/api/validate")){
            String info=" [session cookies: "+cookieCount+"]";
            if(description.contains("expired")) return label+" (expired verification or session)."+info;
            if(description.contains("session") || description.contains("cookie") || description.contains("challenge"))
                return label+" (session or challenge rejected)."+info;
            if(description.contains("invalid") || description.contains("incorrect") || description.contains("wrong") || description.contains("mismatch"))
                return label+" (verification rejected)."+info;
            if(description.contains("required") || description.contains("missing"))
                return label+" (required verification data missing)."+info;
            return label+" (verification rejected; no recognized reason)."+info;
        }
        switch(code) {
            case "invalid_request": case "invalid_client": case "invalid_grant":
            case "unauthorized_client": case "unsupported_grant_type": case "invalid_scope":
            case "access_denied": case "login_required": case "interaction_required":
            case "temporarily_unavailable": case "server_error":
                return label+" ("+code+").";
            default: return label+" (no recognized error code).";
        }
    }
    private static String query(String... pairs) throws Exception {
        StringBuilder b = new StringBuilder();
        for (int i=0;i<pairs.length;i+=2) {
            if (i>0) b.append('&');
            b.append(enc(pairs[i])).append('=').append(enc(pairs[i+1]));
        }
        return b.toString();
    }
    private static JSONObject object(String... pairs) throws Exception {
        JSONObject o = new JSONObject();
        for (int i=0;i<pairs.length;i+=2) o.put(pairs[i],pairs[i+1]);
        return o;
    }
    private JSONObject request(String address, String body, String contentType, boolean authorized) throws Exception {
        URI uri = URI.create(address);
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo()!=null ||
            uri.getPort()!=-1 || !(authorized ? "www.vivintsky.com".equals(host) : "id.vivint.com".equals(host))) {
            throw new Exception("Unexpected authentication destination.");
        }
        HttpUrl url=HttpUrl.get(address);
        int cookieCount=cookies.loadForRequest(url).size();
        Request.Builder req=new Request.Builder().url(url).header("Accept","*/*");
        if(authorized){
            if(token==null || token.isEmpty()) throw new Exception("Sign in first.");
            req.header("Authorization","Bearer "+token);
        }
        if(body!=null) req.post(RequestBody.create(body,MediaType.get(contentType)));
        try(Response response=http.newCall(req.build()).execute()){
            int status=response.code();
            if(status>=300 && status<400){
                String location=response.header("Location");
                if(location==null) throw new Exception("Unexpected redirect.");
                return object("location",location);
            }
            ResponseBody data=response.body();
            String raw="";
            if(data!=null){
                try(InputStream stream=data.byteStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192]; int n;
                    while((n=stream.read(buf))!=-1){
                        if(out.size()+n>8*1024*1024) throw new Exception("Response too large.");
                        out.write(buf,0,n);
                    }
                    raw=new String(out.toByteArray(),StandardCharsets.UTF_8);
                }
            }
            JSONObject result;
            try{result=new JSONObject(raw);}
            catch(Exception e){
                if(status>=400) throw new Exception(stage(uri)+": HTTP "+status+" (non-JSON response; session cookies: "+cookieCount+").");
                return new JSONObject();
            }
            if(status>=400){
                if(!authorized && uri.getPath().equals("/idp/api/submit") && mfaType==null &&
                   (result.has("validate") || result.has("mfa") ||
                    "Multi-factor authentication required".equals(result.optString("msg",result.optString("message",""))))) return result;
                throw new Exception(safeError(uri,status,result,cookieCount));
            }
            return result;
        }
    }
    private JSONObject get(String address,boolean authorized) throws Exception {
        return request(address,null,null,authorized);
    }
    private JSONObject postJson(String address,JSONObject data) throws Exception {
        return request(address,data.toString(),"text/plain; charset=utf-8",false);
    }
    private static String param(URI uri,String name) throws Exception {
        String q=uri.getRawQuery();
        if(q==null) return "";
        for(String pair:q.split("&")) {
            String[] parts=pair.split("=",2);
            if(java.net.URLDecoder.decode(parts[0],"UTF-8").equals(name))
                return parts.length==2?java.net.URLDecoder.decode(parts[1],"UTF-8"):"";
        }
        return "";
    }
    private JSONObject finish(JSONObject response) throws Exception {
        for(int i=0;i<5;i++) {
            if (response.has("access_token")) return response;
            String next=response.optString("location",response.optString("url",""));
            if(next.isEmpty()) break;
            URI uri=URI.create(ID+"/").resolve(next);
            if ("vivint".equals(uri.getScheme())) {
                if(!"app".equals(uri.getHost()) || !"/oauth_redirect".equals(uri.getPath()) ||
                   !state.equals(param(uri,"state"))) throw new Exception("OAuth state validation failed.");
                String code=param(uri,"code");
                if(code.isEmpty()) throw new Exception("Missing authorization code.");
                return request(ID+"/oauth2/token",query("grant_type","authorization_code","client_id","ios",
                    "redirect_uri",CALLBACK,"code",code,"code_verifier",verifier),
                    "application/x-www-form-urlencoded",false);
            }
            response=get(uri.toString(),false);
        }
        return response;
    }
    private boolean accept(JSONObject response) throws Exception {
        if(response.has("validate") || "Multi-factor authentication required".equals(response.optString("msg",response.optString("message","")))) {
            mfaType="code"; return false;
        }
        if(response.has("mfa")) { mfaType="mfa"; return false; }
        JSONObject result=finish(response);
        token=result.optString("access_token","");
        if(token.isEmpty()) throw new Exception("Authentication response did not contain an access token.");
        password=null; username=null; verifier=null; state=null; mfaType=null;
        return true;
    }
    boolean login(String user,String pass) throws Exception {
        username=user; password=pass;
        do { verifier=random().replaceAll("[^a-zA-Z0-9]",""); } while(verifier.length()<43);
        state=random();
        String challenge=Base64.encodeToString(MessageDigest.getInstance("SHA-256")
            .digest(verifier.getBytes(StandardCharsets.UTF_8)),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        JSONObject initial=get(ID+"/oauth2/auth?"+query("response_type","code","client_id","ios",
            "scope","openid email devices email_verified","redirect_uri",CALLBACK,"state",state,
            "code_challenge",challenge,"code_challenge_method","S256"),false);
        if(initial.optString("location").startsWith(CALLBACK)) return accept(initial);
        return accept(postJson(ID+"/idp/api/submit?client_id=ios",object("username",user,"password",pass)));
    }
    boolean verify(String code) throws Exception {
        if(mfaType==null || password==null) throw new Exception("Start sign-in again.");
        if(!code.matches("[0-9]{4,10}")) throw new Exception("Verification code must contain only digits.");
        return accept(postJson(ID+"/idp/api/"+("code".equals(mfaType)?"validate":"submit")+"?client_id=ios",
            object(mfaType,code,"username",username,"password",password)));
    }
    private static boolean privateIpv4(String host) {
        if(host==null) return false;
        String[] p=host.split("\\.",-1);
        if(p.length!=4) return false;
        int[] x=new int[4];
        try {
            for(int i=0;i<4;i++) {
                if(!p[i].matches("[0-9]{1,3}")) return false;
                x[i]=Integer.parseInt(p[i]);
                if(x[i]>255) return false;
            }
        } catch(Exception e){ return false; }
        return x[0]==10 || (x[0]==172 && x[1]>=16 && x[1]<=31) || (x[0]==192 && x[1]==168);
    }
    private static List<Stream> streams(JSONObject camera,JSONObject credentials) {
        List<Stream> result=new ArrayList<>();
        if(camera.optBoolean("cpri",false)) return result;
        String user=credentials.optString("n",""),pass=credentials.optString("pswd","");
        if(user.isEmpty()||pass.isEmpty())return result;
        Set<String> seen=new HashSet<>();
        for(String key:new String[]{"cius","ciu"}) {
            JSONArray a=camera.optJSONArray(key);
            if(a==null)continue;
            String label=key.equals("cius")?"SD":"HD";
            for(int i=0;i<a.length();i++){
                try{
                    URI raw=URI.create(a.getString(i));
                    if(!"rtsp".equalsIgnoreCase(raw.getScheme())||!privateIpv4(raw.getHost())||
                       raw.getUserInfo()!=null||raw.getFragment()!=null)continue;
                    String address=raw.toString();
                    int suffix=address.lastIndexOf(",audio=opus");
                    if(suffix>=0 && suffix+11==address.length()){
                        String clean=address.substring(0,suffix);
                        if(seen.add(clean)) result.add(new Stream(label+" (without Opus option)",clean,user,pass));
                    }
                    if(seen.add(address))result.add(new Stream(label,address,user,pass));
                    if(result.size()>=8)return result;
                }catch(Exception ignored){}
            }
        }
        return result;
    }
    List<Camera> cameras() throws Exception {
        JSONObject account=get(API+"/authuser",true);
        JSONObject user=account.optJSONObject("u");
        JSONArray systems=user==null?null:user.optJSONArray("system");
        if(systems==null || systems.length()==0)
            throw new Exception("No compatible panel returned. This account may use Vivint's newer platform.");
        List<Camera> result=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        int found=0;
        for(int i=0;i<systems.length();i++) {
            JSONObject s=systems.optJSONObject(i);
            if(s==null) continue;
            String panel=s.optString("panid","");
            if(!panel.matches("[0-9]+")) continue;
            JSONObject response=get(API+"/systems/"+panel+"?includerules=false",true);
            JSONObject system=response.optJSONObject("system");
            JSONArray partitions=system==null?null:system.optJSONArray("par");
            if(partitions==null) continue;
            JSONObject creds=null;
            for(int j=0;j<partitions.length();j++) {
                JSONObject partition=partitions.optJSONObject(j);
                JSONArray devices=partition==null?null:partition.optJSONArray("d");
                if(devices==null) continue;
                for(int k=0;k<devices.length();k++) {
                    JSONObject d=devices.optJSONObject(k);
                    if(d==null || !(d.has("ciu") || d.has("cius"))) continue;
                    if(!seen.add(panel+":"+d.optString("_id",String.valueOf(k)))) continue;
                    found++;
                    if(d.optBoolean("cpri",false)) continue;
                    if(creds==null) creds=get(API+"/panel-login/"+panel,true);
                    List<Stream> available=streams(d,creds);
                    if(!available.isEmpty()) result.add(new Camera(d.optString("n","Camera "+found),available));
                }
            }
        }
        if(result.isEmpty()) throw new Exception("No usable local streams returned. Camera privacy or account compatibility may be the cause.");
        token=null; cookies.clear();
        return result;
    }
    void clear() {
        token=null; username=null; password=null; verifier=null; state=null; mfaType=null;
        cookies.clear();
    }
}
