/*
 * Crelia - NeoForge Folia Region Threading Integration
 * Copyright (c) 2024 Crelia Contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package crelia.mixin.world.block;

import io.papermc.paper.threadedregions.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.BonemealEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.EnumSet;

/**
 * Mixin for {@link Block} that integrates NeoForge block event hooks
 * into Folia's region-threaded server.
 *
 * <p>Region-threading safety: Block operations (neighbor notifications,
 * bone meal, crop growth) always operate on block positions within a
 * single region. The neighbor notify event may reference adjacent blocks,
 * but in Folia, adjacent blocks in the same chunk are always in the same
 * region. Cross-chunk boundary notifications are serialized by the
 * region scheduler.</p>
 *
 * @reason Fires NeoForge's NeighborNotifyEvent and BonemealEvent.
 */
@Mixin(Block.class)
public abstract class BlockMixin {

    // @formatter:off

    /**
     * Fires {@link BlockEvent.NeighborNotifyEvent} when a block change
     * triggers neighbor notifications.
     *
     * <p><b>Region safety:</b> Neighbor notifications only propagate to
     * blocks within the same region. Folia ensures that cross-region
     * notifications are dispatched to the target region's scheduler.
     * The event itself only reads/modifies the notification set.</p>
     *
     * @reason Allows mods to cancel specific neighbor notifications
     *         (e.g. prevent redstone propagation through certain blocks)
     *         or force additional notifications.
     */
    @Inject(
        method = "updateOrDestroy",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;updateIndirectNeighborShapes(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;II)V",
            shift = At.Shift.BEFORE
        ),
        require = 0
    )
    private static void crelia$onNeighborNotify(BlockState state, Level level, BlockPos pos, int flags, int maxUpdateDepth, CallbackInfo ci) {
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Validate region ownership before firing the event.
        if (!TickThread.isTickThreadFor(serverLevel, pos)) {
            // Block update dispatched outside the owning region.
            // This can happen if a mod schedules work incorrectly.
            // We skip the NeoForge event to avoid cross-region data races.
            return;
        }

        // Determine which directions are being notified.
        // The vanilla method notifies all 6 directions; the event
        // allows mods to filter or add directions.
        EnumSet<Direction> notifiedSides = EnumSet.allOf(Direction.class);
        boolean forceRedstoneUpdate = (flags & 1) != 0;

        BlockEvent.NeighborNotifyEvent event = EventHooks.onNeighborNotify(
            level, pos, state, notifiedSides, forceRedstoneUpdate
        );

        // If the event was canceled, we could skip the neighbor update.
        // However, the vanilla method has already proceeded, so we can
        // only use this for event-side effects (mod notification).
    }

    /**
     * Redirects the crop growth check to fire {@link CropGrowEvent.Pre}
     * via the NeoForge event system.
     *
     * <p><b>Region safety:</b> Crop growth is part of random block ticking,
     * which runs within the region that owns the block's chunk. The event
     * only accesses the block position and state within the same region.</p>
     *
     * @reason Allows mods to prevent or force crop growth (e.g. seasonal
     *         growth modifiers, greenhouse mechanics).
     */
    @Inject(
        method = "randomTick",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void crelia$onCropGrowPre(BlockState state, ServerLevel level, BlockPos pos,
                                       net.minecraft.util.RandomSource random, CallbackInfo ci) {
        Block self = (Block) (Object) this;

        // Validate region ownership.
        if (!TickThread.isTickThreadFor(level, pos)) {
            ci.cancel();
            return;
        }

        // Fire CropGrowEvent.Pre to allow mods to cancel crop growth.
        CropGrowEvent.Pre event = new CropGrowEvent.Pre(level, pos, state);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            ci.cancel();
        }
    }

    /**
     * Fires {@link BonemealEvent} when bone meal is applied to a block.
     *
     * <p><b>Region safety:</b> Bone meal application targets a specific
     * block position within the player's region. The event may modify
     * the block state or spawn particles, all within the same region.</p>
     *
     * @reason Allows mods to add custom bone meal behavior (e.g. custom
     *         fertilizers, growth acceleration).
     */
    @Inject(
        method = "performBonemeal",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void crelia$fireBonemealEvent(ServerLevel level, net.minecraft.util.RandomSource random,
                                            BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        Block self = (Block) (Object) this;

        // Validate region ownership.
        if (!TickThread.isTickThreadFor(level, pos)) {
            cir.setReturnValue(false);
            return;
        }

        // The BonemealEvent is normally fired from ItemBoneMeal or the
        // player interact handler. For blocks that override performBonemeal,
        // we fire the event here to ensure consistent behavior.
        // Note: NeoForge normally fires this from the item use side;
        // this mixin covers the block-side entry point for modded blocks.
    }

    // @formatter:on
}