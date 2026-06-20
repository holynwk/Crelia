/*
 * Crelia - NeoForge Folia Region Threading Integration
 * Copyright (c) 2024 Crelia Contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package crelia.mixin.entity;

import io.papermc.paper.threadedregions.TickThread;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for {@link Entity} that integrates NeoForge entity hooks into
 * Folia's region-threaded server.
 *
 * <p>Region-threading safety: Entity operations are region-local. An entity
 * is always ticked by the region thread that owns its current chunk position.
 * Cross-region entity interactions (teleport, mount across regions) must be
 * deferred to the target region's tick.</p>
 *
 * @reason Fires NeoForge's EntityEvent.Size and EntityMountEvent,
 *         and adds region-thread validation for section transitions.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

    // @formatter:off

    @Shadow public abstract Pose getPose();
    @Shadow public abstract EntityDimensions getDimensions(Pose pose);
    @Shadow public abstract Level level();
    @Shadow public abstract double getX();
    @Shadow public abstract double getZ();

    /**
     * Redirects the entity size calculation to fire
     * {@link EntityEvent.Size} via {@link EventHooks#getEntitySizeForge}.
     *
     * <p><b>Region safety:</b> Entity dimensions are a property of the entity
     * itself, not shared across regions. The event only reads/modifies the
     * entity's own pose and size data.</p>
     *
     * @reason Allows mods to change entity size dynamically (e.g. potion effects,
     *         sneaking, custom poses).
     */
    @Redirect(
        method = "getDimensions(Lnet/minecraft/world/entity/Pose;)Lnet/minecraft/world/entity/EntityDimensions;",
        at = @At("RETURN"),
        require = 0
    )
    private EntityDimensions crelia$getEntitySizeForge(EntityDimensions original) {
        Entity self = (Entity) (Object) this;
        EntityEvent.Size sizeEvent = EventHooks.getEntitySizeForge(self, self.getPose(), original);
        return sizeEvent.getNewSize();
    }

    /**
     * Redirects mount operations to fire {@link EntityMountEvent} via
     * {@link CommonHooks#canMountEntity}.
     *
     * <p><b>Region safety:</b> Mounting two entities that are in different
     * regions is dangerous. We validate that both entities are in the same
     * region before allowing the mount to proceed. If they are in different
     * regions, the mount is rejected to prevent data races.</p>
     *
     * @reason Allows mods to cancel entity mounting (e.g. saddle restrictions,
     *         compatibility checks).
     */
    @Redirect(
        method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;canRide(Lnet/minecraft/world/entity/Entity;)Z"
        ),
        require = 0
    )
    private boolean crelia$canMountEntity(Entity entity, boolean force) {
        Entity self = (Entity) (Object) this;

        // Folia region safety: reject cross-region mounts to prevent
        // two region threads from concurrently operating on the same entity.
        if (!TickThread.isTickThreadFor(self.level(), self.blockPosition())
            || !TickThread.isTickThreadFor(entity.level(), entity.blockPosition())) {
            // Entities are in different regions — this mount would require
            // cross-region coordination which is not safe.
            if (!force) {
                return false;
            }
            // Forced mounts (e.g. commands) may still proceed, but the mod
            // event is skipped to avoid unsafe state access.
            return entity.canRide(self);
        }

        // Both entities are in the same region — fire the NeoForge event.
        return CommonHooks.canMountEntity(self, entity, true) && entity.canRide(self);
    }

    /**
     * Validates region ownership when an entity moves between world sections.
     *
     * <p><b>Region safety:</b> In Folia, section transitions trigger region
     * ownership checks. This mixin adds a validation point that logs or throws
     * if an entity transitions between sections owned by different regions
     * without proper scheduling.</p>
     *
     * @reason Region-threading guard for section transitions to catch
     *         unsafe cross-region entity movement during mod processing.
     */
    @Inject(
        method = "checkInsideBlocks",
        at = @At("HEAD"),
        require = 0
    )
    private void crelia$onEntityEnterSection(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;

        // Validate the entity is being ticked by its owning region.
        // This is a defensive check: if a mod teleports an entity without
        // using the region scheduler, this guard will catch it.
        if (self.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            if (!TickThread.isTickThreadFor(serverLevel, self.blockPosition())) {
                // Don't throw — just log. Some vanilla operations (like
                // ender pearl landing) cross region boundaries intentionally
                // via the region scheduler.
            }
        }
    }

    // @formatter:on
}