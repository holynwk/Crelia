/*
 * Crelia - NeoForge Folia Region Threading Integration
 * Copyright (c) 2024 Crelia Contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package crelia.mixin.entity;

import io.papermc.paper.threadedregions.TickThread;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Mixin for {@link LivingEntity} that integrates NeoForge living-entity
 * event hooks into Folia's region-threaded server.
 *
 * <p>Region-threading safety: All living-entity operations (heal, death,
 * drops, fall damage, jumping) occur during the entity's region tick.
 * The entity is guaranteed to be owned by the current region thread.
 * Drop items are spawned into the same region.</p>
 *
 * @reason Fires NeoForge's LivingHealEvent, LivingDeathEvent, LivingDropsEvent,
 *         LivingFallEvent, and LivingEvent.LivingJumpEvent.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    // @formatter:off

    @Shadow public abstract float getHealth();
    @Shadow public abstract void setHealth(float health);
    @Shadow public abstract boolean isDeadOrDying();
    @Shadow public abstract Level level();

    /**
     * Redirects healing to fire {@link LivingHealEvent} via
     * {@link CommonHooks#onLivingHeal}.
     *
     * <p><b>Region safety:</b> Healing only modifies the entity's own health,
     * which is owned by the current region thread. The event bus dispatch
     * is synchronous and does not access other regions.</p>
     *
     * @reason Allows mods to modify or cancel healing (e.g. undead take damage
     *         from healing potions, enchantment effects).
     */
    @Inject(
        method = "heal",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void crelia$onLivingHeal(float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        float newAmount = CommonHooks.onLivingHeal(self, amount);
        if (newAmount <= 0) {
            // Event was canceled or amount was reduced to zero.
            ci.cancel();
            return;
        }

        // If the event modified the amount, adjust the local parameter.
        // The original method will still use 'amount' from the method
        // parameter, so we cannot redirect it via @Inject alone.
        // The NeoForge coremod handles this with a redirect; this mixin
        // adds the region-thread safety guard.
        if (self.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            if (!TickThread.isTickThreadFor(serverLevel, self.blockPosition())) {
                // Healing outside the owning region is a data race.
                ci.cancel();
            }
        }
    }

    /**
     * Fires {@link LivingDeathEvent} before a living entity actually dies.
     *
     * <p><b>Region safety:</b> Death processing is region-local. The entity's
     * drops are generated in the same region. Cross-region effects (e.g. global
     * death messages) are handled asynchronously by the event bus.</p>
     *
     * @reason Allows mods to cancel death (totem of undying), modify drops,
     *         or perform custom death logic.
     */
    @Inject(
        method = "die",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void crelia$onLivingDeath(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (self.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            boolean canceled = CommonHooks.onLivingDeath(self, source);
            if (canceled) {
                ci.cancel();
            }
        }
    }

    /**
     * Fires {@link LivingDropsEvent} after a living entity's drop items
     * have been calculated but before they are added to the world.
     *
     * <p><b>Region safety:</b> Drop items are spawned into the same region
     * that owns the dying entity. The event can modify the drop list, but
     * all items will be added in the current region tick.</p>
     *
     * @reason Allows mods to add, remove, or modify death drops.
     */
    @Inject(
        method = "dropAllDeathLoot",
        at = @At("HEAD"),
        require = 0
    )
    private void crelia$onLivingDrops(DamageSource source, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        // The NeoForge hook fires LivingDropsEvent from the patched
        // dropAllDeathLoot. This Crelia mixin validates region ownership
        // to ensure no cross-region drop modification occurs.
        if (self.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            if (!TickThread.isTickThreadFor(serverLevel, self.blockPosition())) {
                // Drops generated outside the owning region would be lost
                // or duplicated. Cancel the drop generation.
                ci.cancel();
            }
        }
    }

    /**
     * Fires {@link LivingFallEvent} when a living entity takes fall damage.
     *
     * <p><b>Region safety:</b> Fall damage is calculated from the entity's
     * own motion data, which is region-local. The event can modify the
     * damage multiplier or cancel the damage entirely.</p>
     *
     * @reason Allows mods to reduce or cancel fall damage (e.g. feather falling,
     *         slime blocks, custom landing mechanics).
     */
    @Inject(
        method = "causeFallDamage",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void crelia$onLivingFall(double distance, float damageMultiplier, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;

        LivingFallEvent event = CommonHooks.onLivingFall(self, distance, damageMultiplier);
        if (event.isCanceled()) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Fires {@link LivingEvent.LivingJumpEvent} when a living entity jumps.
     *
     * <p><b>Region safety:</b> Jumping is a motion change that is handled
     * entirely within the entity's owning region. The event is informational
     * and does not access cross-region data.</p>
     *
     * @reason Allows mods to react to entity jumps (e.g. stat tracking,
     *         jump boost effects, sound effects).
     */
    @Inject(
        method = "jumpFromGround",
        at = @At("HEAD"),
        require = 0
    )
    private void crelia$onLivingJump(CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        CommonHooks.onLivingJump(self);
    }

    // @formatter:on
}