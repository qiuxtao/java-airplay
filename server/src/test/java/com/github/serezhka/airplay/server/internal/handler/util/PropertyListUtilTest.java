package com.github.serezhka.airplay.server.internal.handler.util;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.server.AirPlayConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PropertyListUtilTest {

    @Test
    void desktopModeAdvertisesMirroringWithoutMediaUrlFeatures() throws Exception {
        AirPlayConfig config = new AirPlayConfig();
        config.setMirrorOnly(true);
        config.setWidth(2560);
        config.setHeight(1440);
        config.setFps(30);

        NSDictionary info = (NSDictionary) BinaryPropertyListParser.parse(
                PropertyListUtil.prepareInfoResponse(config));

        assertEquals(130367356800L, info.get("features").toJavaObject(Long.class));
        NSDictionary display = (NSDictionary) ((com.dd.plist.NSArray) info.get("displays")).objectAtIndex(0);
        assertEquals(2560L, display.get("widthPixels").toJavaObject(Long.class));
        assertEquals(1440L, display.get("heightPixels").toJavaObject(Long.class));
        assertEquals(30L, display.get("maxFPS").toJavaObject(Long.class));
        assertEquals(30L, display.get("refreshRate").toJavaObject(Long.class));
    }
}
