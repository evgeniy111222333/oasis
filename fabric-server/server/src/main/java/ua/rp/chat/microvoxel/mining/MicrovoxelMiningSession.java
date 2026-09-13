package ua.rp.chat.microvoxel.mining;

import ua.rp.chat.microvoxel.MicrovoxelKey;

import java.util.UUID;

/**
 * Immutable per-player mining state. One session owns exactly one target cell of one volume;
 * any retarget, revision change, material change or release resets progress, exactly like
 * vanilla restarts the destroy timer when the target block changes.
 */
public record MicrovoxelMiningSession(
        UUID playerId,
        UUID worldId,
        MicrovoxelKey key,
        int cell,
        String material,
        int revision,
        float requiredTicks,
        float progress,
        long lastTick,
        int lastStage,
        boolean toolOk,
        boolean feedbackSent) {

    public MicrovoxelMiningSession withProgress(float newProgress, long newLastTick, int newStage) {
        return new MicrovoxelMiningSession(playerId, worldId, key, cell, material, revision,
                requiredTicks, newProgress, newLastTick, newStage, toolOk, feedbackSent);
    }

    public MicrovoxelMiningSession withFeedbackSent() {
        return new MicrovoxelMiningSession(playerId, worldId, key, cell, material, revision,
                requiredTicks, progress, lastTick, lastStage, toolOk, true);
    }
}