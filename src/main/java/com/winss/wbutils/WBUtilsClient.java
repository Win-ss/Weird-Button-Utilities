package com.winss.wbutils;

import com.winss.wbutils.command.CameraResetCommand;
import com.winss.wbutils.command.QuestCommand;
import com.winss.wbutils.command.ShopCommand;
import com.winss.wbutils.command.WBUtilsCommand;
import com.winss.wbutils.config.ConfigManager;
import com.winss.wbutils.features.AuthService;
import com.winss.wbutils.features.AutoRejoin;
import com.winss.wbutils.features.CameraReset;
import com.winss.wbutils.features.DoorSpirit;
import com.winss.wbutils.features.KillTracker;
import com.winss.wbutils.features.HousingDetector;
import com.winss.wbutils.features.KothProtector;
import com.winss.wbutils.features.ModUserManager;
import com.winss.wbutils.features.QuestHelper;
import com.winss.wbutils.features.RPSTracker;
import com.winss.wbutils.features.AutoRPS;
import com.winss.wbutils.features.ShopHelper;
import com.winss.wbutils.features.Unwrap;
import com.winss.wbutils.features.BootlistTracker;
import com.winss.wbutils.features.StatSpy;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WBUtilsClient implements ClientModInitializer {
    public static final String MOD_ID = "wbutils";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static String getVersion() {
        return FabricLoader.getInstance()
                .getModContainer(MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
    
    private static ConfigManager configManager;
    private static KothProtector kothProtector;
    private static HousingDetector housingDetector;
    private static KillTracker killTracker;
    private static DoorSpirit doorSpirit;
    private static RPSTracker rpsTracker;
    private static AutoRPS autoRPS;
    private static AutoRejoin autoRejoin;
    private static Unwrap unwrap;
    private static ModUserManager modUserManager;
    private static BootlistTracker bootlistTracker;
    private static StatSpy statSpy;

    @Override
    public void onInitializeClient() {
        LOGGER.info("WBUtils is initializing...");
        
        configManager = new ConfigManager();
        configManager.load();
        
        kothProtector = new KothProtector();
        housingDetector = new HousingDetector();
        killTracker = new KillTracker();
        doorSpirit = new DoorSpirit();
        rpsTracker = new RPSTracker();
        autoRPS = new AutoRPS();
        autoRejoin = new AutoRejoin();
        unwrap = new Unwrap();
        modUserManager = new ModUserManager();
        bootlistTracker = new BootlistTracker();
        statSpy = new StatSpy();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            housingDetector.onClientTick();
            AuthService.checkConnectivityTick();
            autoRejoin.onClientTick();
            autoRejoin.refreshDisconnectMessagesIfNeeded();
            
            boolean featuresActive = !configManager.getConfig().requireHousing || housingDetector.isInDptb2Housing();
            
            if (featuresActive) {
                kothProtector.onClientTick();
                killTracker.onClientTick();
                doorSpirit.onClientTick();
                autoRPS.onClientTick();
                unwrap.onClientTick();
                modUserManager.onClientTick();
                bootlistTracker.onClientTick();
                statSpy.onClientTick();
            }
            QuestHelper.onClientTick();
            ShopHelper.onClientTick();
            CameraReset.onClientTick();
        });
        
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            WBUtilsCommand.register(dispatcher);
            QuestCommand.register(dispatcher);
            ShopCommand.register(dispatcher);
            CameraResetCommand.register(dispatcher);
        });
        
        LOGGER.info("WBUtils initialized successfully!");
    }
    
    public static ConfigManager getConfigManager() {
        return configManager;
    }

    public static KothProtector getKothProtector() {
        return kothProtector;
    }
    
    public static HousingDetector getHousingDetector() {
        return housingDetector;
    }
    
    public static KillTracker getKillTracker() {
        return killTracker;
    }
    
    public static DoorSpirit getDoorSpirit() {
        return doorSpirit;
    }
    
    public static RPSTracker getRPSTracker() {
        return rpsTracker;
    }
    
    public static AutoRPS getAutoRPS() {
        return autoRPS;
    }
    
    public static AutoRejoin getAutoRejoin() {
        return autoRejoin;
    }
    
    public static Unwrap getUnwrap() {
        return unwrap;
    }
    
    public static ModUserManager getModUserManager() {
        return modUserManager;
    }
    
    public static BootlistTracker getBootlistTracker() {
        return bootlistTracker;
    }
    
    public static StatSpy getStatSpy() {
        return statSpy;
    }
}
