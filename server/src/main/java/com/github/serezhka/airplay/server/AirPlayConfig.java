package com.github.serezhka.airplay.server;

import lombok.Data;

@Data
public class AirPlayConfig {
    private String serverName = "AirPlay Receiver";
    private int width = 1920;
    private int height = 1080;
    private int fps = 60;
    private boolean mirrorOnly;
}
