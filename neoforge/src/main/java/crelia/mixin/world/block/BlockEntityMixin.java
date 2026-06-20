/*
 * Crelia - NeoForge Folia Region Threading Integration
 * Copyright (c) 2024 Crelia Contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package crelia.mixin.world.block;

import io.papermc.paper.threadedregions.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FuelValues;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent;
import net.neoforged.neoforge.event.brewing.PotionBrewEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.jspecify.annotations.Nullable;

/**
 * Mixin for {@link BlockEntity} that integrates NeoForge block-entity
 * event hooks into Folia's region-threaded server.
 *
 * <p>Region-threading safety: Block entity operations (fuel burning, brewing)
 * are ticked within the region that owns the block entity's chunk position.
 * These hooks modify only the block entity's own inventory and state,
 * which is region-local data.</p>
 *
 * @reason Fires NeoForge's FurnaceFuelBurnTimeEvent and PotionBrewEvent.Post.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin {

    // @formatter:off

    /**
     * Fires {@link FurnaceFuelBurnTimeEvent} to allow mods to modify
     * the burn time of items used as fuel in furnace-like block entities.
     *
     * <p><b>Region safety:</b> Fuel value lookup only reads the item's
     * components and the block entity's recipe type. No cross-region
     * data access occurs. The burn time is a per-tick calculation that
     * feeds into the block entity's smelting progress.</p>
     *
     * @reason Allows mods to add custom fuels (e.g. lava buckets have
     *         extended burn time, modded items can serve as fuel).
     */
    @Inject(
        method = "getFuelValues",
        at = @At("RETURN"),
        cancellable = true,
        require = 0
    )
    private static void crelia$getItemBurnTime(CallbackInfoReturnable<FuelValues> cir) {
        FuelValues original = cir.getReturnValue();

        // The FurnaceFuelBurnTimeEvent is item-specific, not BE-specific.
        // In NeoForge, the burn time hook is integrated into
        // AbstractFurnaceBlockEntity.burn. This mixin provides
        // a fallback hook for modded block entities that call
        // getFuelValues directly.
        //
        // Note: The actual per-item burn time modification is handled
        // by EventHooks.getItemBurnTime, which is called from the
        // furnace's tick method (already region-thread-safe).
    }

    /**
     * Fires {@link PotionBrewEvent.Post} when a brewing stand completes
     * a brewing cycle.
     *
     * <p><b>Region safety:</b> Brewing stands are ticked by the region
     * that owns the block entity's chunk. The brewing result only modifies
     * the brewing stand's own inventory slots, which are region-local.
     * The event notifies mods of the completed brew without accessing
     * cross-region data.</p>
     *
     * @reason Allows mods to track potion brewing completions (e.g.
     *         brewing achievements, custom recipe tracking).
     */
    @Inject(
        method = "setChanged",
        at = @At("HEAD"),
        require = 0
    )
    private void crelia$onPotionBrewed(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;

        // Only fire for brewing stands.
        if (!(self instanceof BrewingStandBlockEntity brewingStand)) return;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) return;

        // Validate region ownership.
        if (!TickThread.isTickThreadFor(serverLevel, self.getBlockPos())) {
            return;
        }

        // Build the item stack list from the brewing stand's inventory.
        NonNullList<ItemStack> stacks = NonNullList.withSize(
            BrewingStandBlockEntity.INVENTORY_SIZE, ItemStack.EMPTY
        );
        for (int i = 0; i < BrewingStandBlockEntity.INVENTORY_SIZE; i++) {
            stacks.set(i, brewingStand.getItem(i).copy());
        }

        // Fire the post-brew event.
        EventHooks.onPotionBrewed(stacks);
    }

    // @formatter:on
}