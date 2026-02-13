package com.winss.wbutils.config;

import java.util.ArrayList;
import java.util.List;

public class ModConfig {
    // Global webhook URL - FALLBACK
    public String webhookUrl = "";
    
    // Discord user ID for death pings - FALLBACK
    public String discordUserId = "";
    
    // Auth system configuration
    public String authServerUrl = "https://wbutils.winss.xyz"; //you see i can hook this up to winss.xyz but im too lazy rn, // As you can see i alr updated it to be wbutils.winss.xyz but uhh yeah if you see this say hi
    public String authToken = "";
    public String minecraftUuid = "";
    public String minecraftName = "";
    public boolean useAuthSystem = true;
    
    // KOTH++ 
    public boolean kothProtectorEnabled = false;
    public boolean kothNotifyOnDeath = true;
    public boolean kothNotifyOnEntry = true;
    public boolean kothNotifyOnExit = true;
    public boolean kothNotifyOnDamage = true;
    public boolean kothDebugLogs = false;
    
    public boolean requireHousing = true;

    public boolean ktrackEnabled = true;
    public int ktrackTimeWindowHours = 1;
    public int ktrackEventThreshold = 3;
    public int ktrackDamageThreshold = 2;
    public double ktrackProximityDistance = 10.0;
    public int ktrackAlertCooldownMinutes = 5;
    public boolean ktrackDebugLogs = false;
    public List<String> ktrackWhitelist = new ArrayList<>();
    
    public boolean doorSpiritEnabled = true;
    public boolean debugDoorSpirit = false;
    
    // RPS cmds
    public boolean rpsTrackerEnabled = true;
    public boolean rpsShowFeedback = true;
    public boolean debugRPS = false;
    
    // AutoRPS feature
    public boolean autoRPSEnabled = false;
    public com.winss.wbutils.features.AutoRPS.Mode autoRPSMode = com.winss.wbutils.features.AutoRPS.Mode.RANDOM;
    public boolean autoRPSShowFeedback = true;
    public boolean debugAutoRPS = false;
    
    // AutoRejoin feature
    public boolean autoRejoinEnabled = false;
    public boolean debugAutoRejoin = false;
    
    // Unwrap feature
    public boolean unwrapEnabled = true;
    public boolean debugUnwrap = false;
    
    // Bootlist feature
    public boolean bootlistEnabled = false;
    public boolean debugBootlist = false;
    
    // StatSpy feature
    public boolean statSpyEnabled = true;
    public boolean debugStatSpy = false;
    
    // MayhemBlast feature
    public boolean mayhemBlastEnabled = false;
    public boolean mayhemDedicationMode = false;
    public int mayhemInactivitySeconds = 60;
    public boolean debugMayhemBlast = false;
    
    // Debug options
    public boolean debugBounty = false;
    public boolean debugDamage = false;
    public boolean debugKothState = false;
    public boolean debugKtrack = false;
    public boolean debugHttp = false;
    public boolean debugModUsers = false;
}
