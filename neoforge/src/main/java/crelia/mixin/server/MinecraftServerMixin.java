/*
 * Crelia - NeoForge Folia Region Threading Integration
 * Copyright (c) 2024 Crelia Contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package crelia.mixin.server;

import com.mojang.brigadier.CommandDispatcher;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Map;

/**
 * Mixin for {@link MinecraftServer} that integrates NeoForge server-lifecycle
 * hooks into Folia's region-threaded architecture.
 *
 * <p>Region-threading safety: Server-level operations (resource reloads,
 * command registration, game loop lifecycle) are inherently global and
 * must run on the main server thread, which Folia preserves for these
 * specific entry points. The game loop itself delegates per-level work
 * to region threads, but the loop driver remains single-threaded.</p>
 *
 * @reason Fires NeoForge's AddServerReloadListenersEvent, RegisterCommandsEvent,
 *         and ServerLifecycleHooks.expectServerStopped within the server lifecycle.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    // @formatter:off

    /**
     * Fires the {@link net.neoforged.neoforge.event.AddServerReloadListenersEvent}
     * when server resources are being (re)loaded.
     *
     * <p><b>Region safety:</b> Resource reloads run on the main thread, before
     * any region ticks process the new data. This is inherently safe.</p>
     *
     * @reason Allows mods to register their own reload listeners with proper
     *         ordering dependencies via NeoForge's ReloadListenerSort.
     */
    @Inject(
        method = "createResources",
        at = @At("RETURN"),
        require = 0
    )
    private void crelia$onResourceReload(CallbackInfoReturnable<ReloadableServerResources> cir) {
        ReloadableServerResources resources = cir.getReturnValue();
        if (resources == null) return;

        MinecraftServer self = (MinecraftServer) (Object) this;
        // The NeoForge hook adds server reload listeners and sorts them.
        // EventHooks.onResourceReload fires AddServerReloadListenersEvent.
        List<PreparableReloadListener> listeners = EventHooks.onResourceReload(
            resources,
            self.registryAccess(),
            Map.of()
        );
    }

    /**
     * Fires the {@link net.neoforged.neoforge.event.RegisterCommandsEvent}
     * when the command dispatcher is being populated.
     *
     * <p><b>Region safety:</b> Command registration is a one-time bootstrap
     * operation that runs on the main thread before any region ticks begin.</p>
     *
     * @reason Allows mods to register their own commands via Brigadier.
     */
    @Inject(
        method = "createCommandDispatcher",
        at = @At("RETURN"),
        require = 0
    )
    private void crelia$onCommandRegister(CallbackInfoReturnable<Commands> cir) {
        Commands commands = cir.getReturnValue();
        if (commands == null) return;

        MinecraftServer self = (MinecraftServer) (Object) this;
        EventHooks.onCommandRegister(
            commands.getDispatcher(),
            Commands.CommandSelection.ALL,
            self.registryAccess().createCommandBuildContext()
        );
    }

    /**
     * Fires {@link ServerLifecycleHooks#expectServerStopped()} just before
     * the server enters its main game loop.
     *
     * <p><b>Region safety:</b> This is called once during server startup
     * on the main thread, before any region threads are spawned.</p>
     *
     * @reason Signals to NeoForge mods that the server is about to enter
     *         its run loop, allowing any last initialization.
     */
    @Inject(
        method = "runServer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V",
            shift = At.Shift.BEFORE
        ),
        require = 0
    )
    private void crelia$expectServerStopped(CallbackInfo ci) {
        ServerLifecycleHooks.expectServerStopped();
    }

    // @formatter:on
}