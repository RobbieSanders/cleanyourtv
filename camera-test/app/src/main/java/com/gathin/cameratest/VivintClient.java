package com.gathin.cameratest;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
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
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private String verifier, state, username, password, token, mfaType;

    static final class Camera {
        final String name;
        final String url;
        Camera(String name, String url) { this.name = name; this.url = url; }
    }
    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }
    private static String random() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
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
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept","application/json");
        for (java.util.Map.Entry<String,List<String>> entry : cookies.get(uri, java.util.Collections.emptyMap()).entrySet()) {
            for (String value : entry.getValue()) c.addRequestProperty(entry.getKey(),value);
        }
        if (authorized) {
            if (token==null || token.isEmpty()) throw new Exception("Sign in first.");
            c.setRequestProperty("Authorization","Bearer "+token);
        }
        try {
            if (body!=null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type",contentType);
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                try (java.io.OutputStream out=c.getOutputStream()) { out.write(bytes); }
                java.util.Arrays.fill(bytes,(byte)0);
            }
            int status=c.getResponseCode();
            cookies.put(uri,c.getHeaderFields());
            if (status>=300 && status<400) {
                String location=c.getHeaderField("Location");
                if (location==null) throw new Exception("Unexpected redirect.");
                return object("location",location);
            }
            InputStream in=status>=400 ? c.getErrorStream() : c.getInputStream();
            String raw="";
            if (in!=null) {
                try (InputStream stream=in; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    byte[] buf=new byte[8192]; int n;
                    while ((n=stream.read(buf))!=-1) {
                        if (out.size()+n>8*1024*1024) throw new Exception("Response too large.");
                        out.write(buf,0,n);
                    }
                    raw=new String(out.toByteArray(),StandardCharsets.UTF_8);
                }
            }
            JSONObject result;
            try { result=new JSONObject(raw); }
            catch (Exception e) {
                if (status>=400) throw new Exception("Vivint returned HTTP "+status+".");
                return new JSONObject();
            }
            if (status>=400) {
                if (!authorized && (result.has("validate") || result.has("mfa") ||
                    "Multi-factor authentication required".equals(result.optString("msg")))) return result;
                throw new Exception("Vivint returned HTTP "+status+". Check sign-in or account permissions.");
            }
            return result;
        } finally { c.disconnect(); }
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
        if(response.has("validate") || "Multi-factor authentication required".equals(response.optString("msg"))) {
            mfaType="code"; return false;
        }
        if(response.has("mfa")) { mfaType="mfa"; return false; }
        JSONObject result=finish(response);
        token=result.optString("access_token","");
        if(token.isEmpty()) throw new Exception("Vivint did not return an access token.");
        password=null; username=null; verifier=null; state=null; mfaType=null;
        return true;
    }
    boolean login(String user,String pass) throws Exception {
        username=user; password=pass; verifier=random(); state=random();
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
    private static String stream(JSONObject camera,JSONObject credentials) {
        if(camera.optBoolean("cpri",false)) return null;
        for(String key:new String[]{"cius","ciu"}) {
            JSONArray a=camera.optJSONArray(key);
            if(a==null || a.length()==0) continue;
            try {
                URI raw=URI.create(a.getString(0));
                String user=credentials.optString("n",""), pass=credentials.optString("pswd","");
                if(!"rtsp".equalsIgnoreCase(raw.getScheme()) || !privateIpv4(raw.getHost()) ||
                    user.isEmpty() || pass.isEmpty() || raw.getUserInfo()!=null) continue;
                String auth=enc(user).replace("+","%20")+":"+enc(pass).replace("+","%20");
                String path=raw.getRawPath()==null?"":raw.getRawPath();
                return "rtsp://"+auth+"@"+raw.getHost()+(raw.getPort()==-1?"":":"+raw.getPort())+
                    path+(raw.getRawQuery()==null?"":"?"+raw.getRawQuery());
            } catch(Exception ignored) {}
        }
        return null;
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
                    String url=stream(d,creds);
                    if(url!=null) result.add(new Camera(d.optString("n","Camera "+found),url));
                }
            }
        }
        if(result.isEmpty()) throw new Exception("No usable local streams returned. Camera privacy or account compatibility may be the cause.");
        token=null; cookies.getCookieStore().removeAll();
        return result;
    }
    void clear() {
        token=null; username=null; password=null; verifier=null; state=null; mfaType=null;
        cookies.getCookieStore().removeAll();
    }
}
