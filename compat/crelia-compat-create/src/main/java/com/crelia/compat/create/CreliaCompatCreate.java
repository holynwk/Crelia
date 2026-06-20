package com.crelia.compat.create;

import com.crelia.compat.create.network.RegionAwareKineticNetwork;
import com.crelia.compat.create.handlers.ContraptionRegionHandler;
import com.crelia.compat.create.handlers.RegionizedProjectileHandler;
import com.crelia.compat.create.handlers.CrossRegionExplosionHandler;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.ModContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Crelia compatibility layer for Create and Create Big Cannons.
 *
 * <p>This mod patches Create's mechanical systems to work correctly on a Folia regionized
 * server where the world is divided into independently-ticking regions, each with its own
 * region thread. Without these patches, Create's kinetic network propagation, contraption
 * movement, and projectile physics would cross region boundaries unsafely, leading to
 * race conditions, data corruption, or server crashes.</p>
 *
 * <h2>Folia Region Threading Model</h2>
 * <p>On a Folia server, each {@code RegionizedWorldSection} is ticked by a dedicated region
 * thread. Operations that access chunk data, entities, or block entities must be scheduled
 * on the correct region thread via {@code RegionizedTaskQueue} or {@code EntityScheduler}.
 * Direct cross-thread access to region data is prohibited and will cause crashes.</p>
 *
 * <h2>What This Mod Handles</h2>
 * <ul>
 *   <li><b>Kinetic Network Ticking</b> — Binds each Create kinetic network tick to the
 *       region thread that owns the network's constituent block entities.</li>
 *   <li><b>Contraption Movement</b> — Handles contraptions (trains, pistons, etc.) that
 *       span region boundaries by fragmenting updates across the relevant region threads.</li>
 *   <li><b>Projectile Ownership</b> — Assigns Create Big Cannons projectiles to the region
 *       thread responsible for the chunk where the projectile currently resides.</li>
 *   <li><b>Explosion Fan-out</b> — Routes explosion damage calculations to each affected
 *       region thread via {@code RegionizedTaskQueue}, ensuring thread-safe block/entity
 *       damage resolution.</li>
 * </ul>
 *
 * @see RegionAwareKineticNetwork
 * @see ContraptionRegionHandler
 * @see RegionizedProjectileHandler
 * @see CrossRegionExplosionHandler
 */
@Mod(CreliaCompatCreate.MOD_ID)
public class CreliaCompatCreate {

    /** Mod identifier used in {@code neoforge.mods.toml} and registration. */
    public static final String MOD_ID = "crelia_compat_create";

    /** Logger instance for diagnostic and error output. */
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final ModContainer modContainer;

    /**
     * Constructs the compat layer and registers lifecycle hooks.
     *
     * <p>The NeoForge mod container is stored so that we can access mod metadata
     * (version, display name) at runtime if needed for diagnostic commands.</p>
     *
     * @param modContainer the NeoForge mod container for this compatibility module
     */
    public CreliaCompatCreate(IEventBus modEventBus, ModContainer modContainer) {
        this.modContainer = modContainer;

        LOGGER.info("[CreliaCompatCreate] Initializing Create + Create Big Cannons compat for Folia regionized server");

        // Register the FMLCommonSetupEvent handler which initializes all sub-systems
        modEventBus.addListener(this::onCommonSetup);
    }

    /**
     * Common setup phase — initializes all region-aware handlers.
     *
     * <p>This runs once during server startup, after registries are frozen but before
     * the first world tick. At this point the regionized world sections are not yet
     * created (they are created per-world), so we register event listeners and
     * install mixins rather than trying to access region data directly.</p>
     *
     * @param event the FML common setup event
     */
    private void onCommonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[CreliaCompatCreate] Common setup: registering region-aware handlers");

        event.enqueueWork(() -> {
            // Initialize the region-aware kinetic network tracker
            RegionAwareKineticNetwork.init();

            // Register contraption cross-region movement handler
            ContraptionRegionHandler.register();

            // Register Create Big Cannons projectile region ownership handler
            RegionizedProjectileHandler.register();

            // Register cross-region explosion fan-out handler
            CrossRegionExplosionHandler.register();

            LOGGER.info("[CreliaCompatCreate] All region-aware handlers registered successfully");
        });
    }
}
