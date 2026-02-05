package com.winss.wbutils.features;

import com.winss.wbutils.Messages;
import com.winss.wbutils.WBUtilsClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

import java.util.Random;
public class CameraReset {
    private static final Random RANDOM = new Random();
    
    private static float targetYaw = 0.0f;
    private static float targetPitch = 0.0f;
    
    private static final float BASE_SPEED = 5.5f;
    private static final float SPEED_VARIANCE = 2.5f;
    private static final float MICRO_JITTER = 0.12f;
    private static final float SLOWDOWN_THRESHOLD = 12.0f;
    private static final float STOP_THRESHOLD = 0.2f;
    
    private static boolean isResetting = false;
    private static float currentSpeedMultiplier = 1.0f;
    

    private static float yawDrift = 0.0f;
    private static float pitchDrift = 0.0f;
    private static int ticksSinceStart = 0;
    private static int pauseTicks = 0;
    public static void execute() {
        execute(0.0f, 0.0f);
    }
    
    public static void execute(float yaw, float pitch) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        
        if (player == null) {
            return;
        }
        
        targetYaw = normalizeAngle(yaw);
        targetPitch = Math.max(-90.0f, Math.min(90.0f, pitch));
        
        isResetting = true;
        ticksSinceStart = 0;
        pauseTicks = 0;
        
        currentSpeedMultiplier = 0.85f + RANDOM.nextFloat() * 0.3f;
        
        yawDrift = (RANDOM.nextFloat() - 0.5f) * 1.5f;
        pitchDrift = (RANDOM.nextFloat() - 0.5f) * 1.0f;
        
        WBUtilsClient.LOGGER.debug("[CameraReset] Started - Current yaw: {}, pitch: {} -> Target yaw: {}, pitch: {}", 
            player.getYaw(), player.getPitch(), targetYaw, targetPitch);
    }
    
    public static void onClientTick() {
        if (!isResetting) {
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        
        if (player == null) {
            isResetting = false;
            return;
        }
        
        ticksSinceStart++;
        
        if (pauseTicks > 0) {
            pauseTicks--;
            return;
        }
        
        if (RANDOM.nextFloat() < 0.03f) {
            pauseTicks = 1 + RANDOM.nextInt(3);
            return;
        }
        
        float currentYaw = player.getYaw();
        float currentPitch = player.getPitch();
        
        currentYaw = normalizeAngle(currentYaw);
        
        float yawDiff = targetYaw - currentYaw;
        float pitchDiff = targetPitch - currentPitch;
        
        if (yawDiff > 180) yawDiff -= 360;
        if (yawDiff < -180) yawDiff += 360;
        
        float totalDistance = (float) Math.sqrt(yawDiff * yawDiff + pitchDiff * pitchDiff);
        
        if (totalDistance < STOP_THRESHOLD) {
            player.setYaw(targetYaw);
            player.setPitch(targetPitch);
            isResetting = false;
            
            WBUtilsClient.LOGGER.debug("[CameraReset] Complete - Final yaw: {}, pitch: {} (took {} ticks)", 
                player.getYaw(), player.getPitch(), ticksSinceStart);
            return;
        }
        
        float speed = calculateHumanSpeed(totalDistance);
        
        float moveRatio = Math.min(speed / totalDistance, 1.0f);
        
        float yawMove = yawDiff * moveRatio;
        float pitchMove = pitchDiff * moveRatio;
        
        float driftFactor = Math.min(1.0f, totalDistance / 30.0f);
        yawMove += yawDrift * driftFactor * 0.15f;
        pitchMove += pitchDrift * driftFactor * 0.15f;
        
        if (RANDOM.nextFloat() < 0.2f) {
            yawDrift += (RANDOM.nextFloat() - 0.5f) * 0.8f;
            pitchDrift += (RANDOM.nextFloat() - 0.5f) * 0.5f;
            yawDrift = Math.max(-2.0f, Math.min(2.0f, yawDrift));
            pitchDrift = Math.max(-1.5f, Math.min(1.5f, pitchDrift));
        }
        
        yawMove += (RANDOM.nextFloat() - 0.5f) * MICRO_JITTER;
        pitchMove += (RANDOM.nextFloat() - 0.5f) * MICRO_JITTER;
        
        float newYaw = currentYaw + yawMove;
        float newPitch = currentPitch + pitchMove;
        
        newPitch = Math.max(-90.0f, Math.min(90.0f, newPitch));
        
        player.setYaw(newYaw);
        player.setPitch(newPitch);
        
        if (RANDOM.nextFloat() < 0.12f) {
            currentSpeedMultiplier = 0.75f + RANDOM.nextFloat() * 0.5f;
        }
    }

    private static float calculateHumanSpeed(float distance) {
        float baseSpeed = BASE_SPEED + (RANDOM.nextFloat() - 0.5f) * SPEED_VARIANCE;
        
        baseSpeed *= currentSpeedMultiplier;
        
        if (distance < SLOWDOWN_THRESHOLD) {
            float slowdownFactor = distance / SLOWDOWN_THRESHOLD;
            slowdownFactor = (float) Math.pow(slowdownFactor, 0.6);
            baseSpeed *= Math.max(0.25f, slowdownFactor);
        }
        
        if (distance > 90.0f) {
            baseSpeed *= 1.25f;
        } else if (distance > 50.0f) {
            baseSpeed *= 1.1f;
        }
        
        return baseSpeed;
    }
    
    private static float normalizeAngle(float angle) {
        angle = angle % 360;
        if (angle > 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    public static boolean isResetting() {
        return isResetting;
    }
    

    public static float getTargetYaw() {
        return targetYaw;
    }
    

    public static float getTargetPitch() {
        return targetPitch;
    }
    
    public static void cancel() {
        if (isResetting) {
            isResetting = false;
            WBUtilsClient.LOGGER.debug("[CameraReset] Cancelled");
        }
    }
}
