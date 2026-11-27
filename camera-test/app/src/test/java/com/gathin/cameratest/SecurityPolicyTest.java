package com.gathin.cameratest;
import org.junit.Test;
import static org.junit.Assert.*;
public class SecurityPolicyTest {
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
