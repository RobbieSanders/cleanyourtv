package com.gathin.cameratest;
import org.junit.Test;
import static org.junit.Assert.*;
public class SecurityPolicyTest {
    @Test public void rememberedAccessExpiresAndNeverExceedsSession(){
        long now=1000000000L, end=now+SecurityPolicy.SESSION_MS;
        assertEquals(now+168*3600000L,SecurityPolicy.rememberedUntil(now,end,now,"balanced",168,false));
        assertEquals(0,SecurityPolicy.rememberedUntil(now-168*3600000L,end,now,"balanced",168,false));
        assertEquals(0,SecurityPolicy.rememberedUntil(now,end,now,"balanced",168,true));
        assertEquals(0,SecurityPolicy.rememberedUntil(now,end,now,"strict",168,false));
        assertEquals(end,SecurityPolicy.rememberedUntil(now,end,now,"convenience",168,false));
        assertEquals(now+1,SecurityPolicy.rememberedUntil(now,now+1,now,"balanced",168,false));
        assertEquals(0,SecurityPolicy.rememberedUntil(now+1,end,now,"balanced",168,false));
    }
    @Test public void exactlyThirtyDaysExpires(){
        long issued=1000000L;
        assertTrue(SecurityPolicy.valid(issued,issued,issued+SecurityPolicy.SESSION_MS-1));
        assertFalse(SecurityPolicy.valid(issued,issued,issued+SecurityPolicy.SESSION_MS));
    }
    @Test public void rollbackFailsClosed(){
        assertFalse(SecurityPolicy.valid(1000000L,2000000L,1500000L));
    }
    @Test public void explicitEventAndAlarmFilters(){
        assertEquals("Person lingering",SecurityPolicy.kind("person_lingering",""));
        assertNull(SecurityPolicy.kind("armed_stay","Smart Deter enabled"));
        assertTrue(SecurityPolicy.permitted("Person lingering","Armed Stay",true,true,false,true,true,false));
        assertFalse(SecurityPolicy.permitted("Motion detected","Armed Stay",true,true,false,true,true,false));
        assertFalse(SecurityPolicy.permitted("Person lingering","Unknown",true,true,true,true,true,true));
        assertFalse(SecurityPolicy.permitted("Doorbell pressed","Disarmed",true,true,false,true,true,true));
    }
}
