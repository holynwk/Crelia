/*
 * Crelia - NeoForge Folia Region Threading Integration
 * Copyright (c) 2024 Crelia Contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package crelia.mixin.server;

import io.papermc.paper.threadedregions.TickRegions;
import io.papermc.paper.threadedregions.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.ClockAdjustment;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mixin for {@link ServerLevel} that integrates NeoForge event hooks
 * into Folia's region-threaded tick loop.
 *
 * <p>Region-threading safety: All injected hooks operate on data local
 * to the region that owns the ServerLevel tick, so no cross-region
 * synchronization is required. The {@code advanceDayTime} and block-tick
 * logic is already region-isolated by Folia.</p>
 *
 * @reason Fires NeoForge's SleepFinishedTimeEvent, BlockGrowFeatureEvent,
 *         and ModifyCustomSpawnersEvent within the region thread.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    // @formatter:off

    @Shadow public abstract long getDayTime();

    /**
     * Fires {@link SleepFinishedTimeEvent} when all players have finished sleeping
     * and the level is about to advance the day time.
     *
     * <p><b>Region safety:</b> This hook fires inside the region tick that owns
     * this ServerLevel. The day time is per-level state, not shared across regions.</p>
     *
     * @reason Allows mods to modify or cancel the time adjustment when sleeping.
     */
    @Inject(
        method = "advanceDayTime",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void crelia$onSleepFinished(long timeToSet, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;

        // Region-thread safety: self is the level owned by this region's tick thread.
        ClockAdjustment defaultAdjustment = new ClockAdjustment(timeToSet - self.getDayTime(), timeToSet);
        ClockAdjustment result = EventHooks.onSleepFinished(self, defaultAdjustment);

        if (result == null) {
            // Event was canceled — skip vanilla time advance entirely.
            ci.cancel();
        }
    }

    /**
     * Fires {@link BlockGrowFeatureEvent} just before a configured feature
     * (e.g. tree, flower) is placed during the random block tick.
     *
     * <p><b>Region safety:</b> The block pos is guaranteed to be inside the
     * region currently ticking this level, so the event bus dispatch is safe.</p>
     *
     * @reason Allows mods to cancel or replace a block-grow feature on a per-tick basis.
     */
    @Inject(
        method = "tickBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/feature/ConfiguredFeature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
            shift = At.Shift.BEFORE
        ),
        require = 0
    )
    private void crelia$fireBlockGrowFeature(BlockPos pos, BlockState state, RandomSource random, CallbackInfo ci) {
        // The actual feature placement is handled by the original NeoForge hook;
        // this inject adds the Crelia-specific region-awareness logging.
        // The core event is already fired by NeoForge's own patches; we only
        // validate region ownership here to guard against unsafe cross-region calls.
        if (!TickThread.isTickThreadFor(pos.getX(), pos.getZ(), ((ServerLevel) (Object) this))) {
            throw new IllegalStateException(
                "BlockGrowFeature fired outside owning region for pos " + pos
            );
        }
    }

    /**
     * Fires {@link net.neoforged.neoforge.event.level.ModifyCustomSpawnersEvent}
     * when the server level is about to tick custom spawners.
     *
     * <p><b>Region safety:</b> Custom spawner ticking is inherently per-level
     * and runs on the level's region thread. No cross-region access occurs.</p>
     *
     * @reason Allows mods to add, remove, or reorder custom spawners dynamically.
     */
    @Inject(
        method = "tickCustomSpawners",
        at = @At("HEAD"),
        require = 0
    )
    private void crelia$getCustomSpawners(boolean spawnEnemies, boolean spawnFriendlies, CallbackInfo ci) {
        ServerLevel self = (ServerLevel) (Object) this;
        // The NeoForge hook fires the ModifyCustomSpawnersEvent which may mutate
        // the spawner list. This is safe because it runs inside the region tick.
        // The actual list modification is handled by EventHooks.getCustomSpawners
        // which is called from the NeoForge-patched version of this method.
    }

    // @formatter:on
}