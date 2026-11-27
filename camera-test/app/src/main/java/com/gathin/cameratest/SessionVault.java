package com.gathin.cameratest;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import java.util.Arrays;

final class SessionVault {
    private static final String ALIAS="home-security-session-v1", DATA="encrypted";
    private static final int FLAGS=Base64.NO_WRAP;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final Object STORAGE=new Object();
    private static final Object NETWORK=new Object();
    private final SharedPreferences prefs;
    SessionVault(Context c){prefs=c.getApplicationContext().getSharedPreferences("security_vault",Context.MODE_PRIVATE);}
    boolean exists(){return prefs.contains(DATA);}
    private static String b64(byte[] b){return Base64.encodeToString(b,FLAGS);}
    private static byte[] bytes(String s){return Base64.decode(s,FLAGS);}
    private static SecretKey key(boolean create) throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        if(!create)throw new Exception("Session key unavailable.");
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build());
        return gen.generateKey();
    }
    private JSONObject read() throws Exception{
        String packed=prefs.getString(DATA,null);
        if(packed==null)throw new Exception("Sign in to Vivint again.");
        String[] parts=packed.split(":",2);
        if(parts.length!=2)throw new Exception("Session storage unavailable.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,bytes(parts[0])));
        byte[] plain=cipher.doFinal(bytes(parts[1]));
        try{return new JSONObject(new String(plain,StandardCharsets.UTF_8));}
        finally{Arrays.fill(plain,(byte)0);}
    }
    private void write(JSONObject data) throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key(true));
        byte[] plain=data.toString().getBytes(StandardCharsets.UTF_8);
        try{
            String packed=b64(cipher.getIV())+":"+b64(cipher.doFinal(plain));
            if(!prefs.edit().putString(DATA,packed).commit())throw new Exception("Could not save encrypted session.");
        }finally{Arrays.fill(plain,(byte)0);}
    }
    void clear(){
        synchronized(STORAGE){
            prefs.edit().remove(DATA).commit();
            try{KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);store.deleteEntry(ALIAS);}
            catch(Exception ignored){}
        }
    }
    private JSONObject current() throws Exception{
        JSONObject data;
        try{data=read();}catch(Exception e){clear();throw new Exception("Sign in to Vivint again.");}
        long now=System.currentTimeMillis();
        if(!SecurityPolicy.valid(data.optLong("issued"),data.optLong("lastSeen"),now)){
            clear();throw new Exception("30-day session expired. Sign in to Vivint again.");
        }
        if(now-data.optLong("lastSeen")>60000){data.put("lastSeen",now);write(data);}
        return data;
    }
    private static byte[] hash(String pin,byte[] salt)throws Exception{
        PBEKeySpec spec=new PBEKeySpec(pin.toCharArray(),salt,160000,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        finally{spec.clearPassword();}
    }
    void enroll(VivintClient client,String pin)throws Exception{
        if(!pin.matches("[0-9]{6}"))throw new Exception("Choose a six-digit PIN.");
        if(client.refresh()==null||client.refresh().isEmpty())throw new Exception("Vivint did not issue a renewable session. Sign in again.");
        byte[] salt=new byte[24],id=new byte[24];RANDOM.nextBytes(salt);RANDOM.nextBytes(id);
        byte[] verifier=hash(pin,salt);
        try{
            long now=System.currentTimeMillis();
            JSONObject d=new JSONObject().put("id",b64(id)).put("issued",now).put("lastSeen",now)
                .put("access",client.access()).put("refresh",client.refresh()).put("accessUntil",client.accessUntil())
                .put("salt",b64(salt)).put("pin",b64(verifier)).put("failed",0).put("lockedUntil",0);
            synchronized(STORAGE){write(d);}
        }finally{Arrays.fill(verifier,(byte)0);}
    }
    long checkPin(String pin)throws Exception{
        synchronized(STORAGE){
            JSONObject d=current();long now=System.currentTimeMillis(),until=d.optLong("lockedUntil");
            if(until>now)throw new Exception("PIN locked. Try again in "+Math.max(1,(until-now+59999)/60000)+" minute(s).");
            byte[] supplied=hash(pin,bytes(d.getString("salt")));
            boolean good;
            try{good=MessageDigest.isEqual(supplied,bytes(d.getString("pin")));}
            finally{Arrays.fill(supplied,(byte)0);}
            if(!good){
                int failed=d.optInt("failed")+1;
                if(failed>=5){d.put("failed",0).put("lockedUntil",now+SecurityPolicy.LOCKOUT_MS);write(d);throw new Exception("Five incorrect PINs. Locked for five minutes.");}
                d.put("failed",failed).put("lockedUntil",0);write(d);
                throw new Exception("Incorrect PIN. "+(5-failed)+" attempt(s) remaining.");
            }
            d.put("failed",0).put("lockedUntil",0);write(d);
            return d.getLong("issued")+SecurityPolicy.SESSION_MS;
        }
    }
    long changePin(String oldPin,String newPin)throws Exception{
        if(!newPin.matches("[0-9]{6}"))throw new Exception("Choose a six-digit PIN.");
        synchronized(STORAGE){
            long expires=checkPin(oldPin);
            JSONObject data=current();
            byte[] salt=new byte[24];RANDOM.nextBytes(salt);
            byte[] verifier=hash(newPin,salt);
            try{
                data.put("salt",b64(salt)).put("pin",b64(verifier)).put("failed",0).put("lockedUntil",0);
                write(data);
            }finally{Arrays.fill(verifier,(byte)0);}
            return expires;
        }
    }
    interface Operation<T>{T run(VivintClient c)throws Exception;}
    <T>T use(Operation<T> operation)throws Exception{
        synchronized(NETWORK){
            JSONObject data;
            synchronized(STORAGE){data=current();}
            String id=data.getString("id");
            VivintClient client=new VivintClient();
            try{
                client.restore(data.getString("access"),data.getString("refresh"),data.getLong("accessUntil"));
                if(client.accessUntil()<=System.currentTimeMillis()+60000){
                    try{client.renew();}
                    catch(Exception e){
                        // Refresh tokens rotate. A lost response may mean the old token was consumed.
                        // Do not replay an uncertain refresh and risk invalidating the new token.
                        synchronized(STORAGE){if(exists()&&id.equals(current().optString("id")))clear();}
                        throw new Exception("Vivint session renewal could not be confirmed. Sign in again.");
                    }
                    synchronized(STORAGE){
                        JSONObject fresh=current();
                        if(!id.equals(fresh.optString("id")))throw new Exception("Session changed. Try again.");
                        fresh.put("access",client.access()).put("refresh",client.refresh()).put("accessUntil",client.accessUntil());
                        write(fresh);
                    }
                }
                T result=operation.run(client);
                synchronized(STORAGE){if(!id.equals(current().optString("id")))throw new Exception("Session changed. Try again.");}
                return result;
            }finally{client.clear();}
        }
    }
    boolean usable(){
        synchronized(STORAGE){try{current();return true;}catch(Exception e){return false;}}
    }
}
