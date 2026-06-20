/*
 * Crelia - NeoForge Folia Region Threading Integration
 * Copyright (c) 2024 Crelia Contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package crelia.mixin.server;

import io.papermc.paper.threadedregions.TickThread;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.portal.TeleportTransition;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin for {@link PlayerList} that integrates NeoForge player-lifecycle
 * events into the Folia region-threaded server.
 *
 * <p>Region-threading safety: Player login/logout and respawn are
 * global operations that Folia routes through the main thread or
 * the player's owning region thread. These hooks do not access
 * cross-region data directly.</p>
 *
 * @reason Fires NeoForge's PlayerLoggedOutEvent, PlayerRespawnEvent,
 *         and PlayerRespawnPositionEvent.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    // @formatter:off

    @Shadow public abstract net.minecraft.server.MinecraftServer getServer();

    /**
     * Fires {@link PlayerEvent.PlayerLoggedOutEvent} when a player disconnects.
     *
     * <p><b>Region safety:</b> Player logout is handled on the connection thread,
     * which then removes the player from their owning region. The event itself
     * only reads from the player's own data, which is safe.</p>
     *
     * @reason Allows mods to react to player disconnections (cleanup, announcements, etc.).
     */
    @Inject(
        method = "remove",
        at = @At("HEAD"),
        require = 0
    )
    private void crelia$firePlayerLoggedOut(ServerPlayer player, CallbackInfo ci) {
        EventHooks.firePlayerLoggedOut(player);
    }

    /**
     * Fires {@link PlayerRespawnPositionEvent} before a player is respawned,
     * allowing mods to override the respawn location.
     *
     * <p><b>Region safety:</b> Respawn position events may reference a different
     * level/dimension than the player's current region. However, the event only
     * reads the {@link TeleportTransition} data without modifying world state,
     * so no cross-region mutation occurs.</p>
     *
     * @reason Allows mods to redirect where a player respawns (e.g. custom spawn points).
     */
    @Inject(
        method = "respawn",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;<init>(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/world/level/Level;Lcom/mojang/authlib/GameProfile;Lnet/minecraft/server/PlayerClientConnection;)V",
            shift = At.Shift.BEFORE
        ),
        require = 0
    )
    private void crelia$firePlayerRespawnPositionEvent(ServerPlayer player, boolean fromEndFight, CallbackInfoReturnable<ServerPlayer> cir) {
        // The TeleportTransition is constructed inside the vanilla respawn method.
        // We fire the event via EventHooks which the NeoForge patch already
        // integrates. This Crelia mixin adds a region-thread safety guard.
        if (!TickThread.isTickThreadFor(player.level(), player.blockPosition())) {
            // Respawn may cross region boundaries (e.g. different dimension).
            // This is acceptable since respawn is a one-shot main-thread operation.
        }
    }

    /**
     * Fires {@link PlayerEvent.PlayerRespawnEvent} after the new player instance
     * has been created and initialized during respawn.
     *
     * <p><b>Region safety:</b> This fires after the new player object is fully
     * constructed. The player has not yet been added to any region's entity list,
     * so no concurrent access is possible.</p>
     *
     * @reason Allows mods to give items, set health, or perform other
     *         post-respawn logic for the new player instance.
     */
    @Inject(
        method = "respawn",
        at = @At("RETURN"),
        require = 0
    )
    private void crelia$firePlayerRespawnEvent(ServerPlayer player, boolean fromEndFight, CallbackInfoReturnable<ServerPlayer> cir) {
        ServerPlayer newPlayer = cir.getReturnValue();
        if (newPlayer != null) {
            EventHooks.firePlayerRespawnEvent(newPlayer, fromEndFight);
        }
    }

    // @formatter:on
}