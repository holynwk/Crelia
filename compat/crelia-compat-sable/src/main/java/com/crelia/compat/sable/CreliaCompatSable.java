package com.crelia.compat.sable;

import com.crelia.compat.sable.handlers.CrossRegionSubLevelManager;
import com.crelia.compat.sable.physics.SablePhysicsRegionBridge;
import com.crelia.compat.sable.physics.JNIThreadSafetyAuditor;
import com.crelia.compat.sable.handlers.VehicleAssemblyRegionHandler;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.ModContainer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the Crelia compatibility layer for Sable + Create Aeronautics.
 *
 * <p>This mod bridges Sable's physics engine (backed by Rapier via JNI) and Create Aeronautics'
 * vehicle systems with Folia's regionized threading model. Sable operates its own physics
 * step on a dedicated thread, while Folia requires all world mutations to happen on the
 * owning region thread. This creates a fundamental threading conflict that must be resolved
 * through careful synchronization, cross-region message passing, and JNI thread safety
 * auditing.</p>
 *
 * <h2>Folia Region Threading Model — Implications for Sable</h2>
 * <p>Folia divides the world into {@code RegionizedWorldSection}s, each ticked by a dedicated
 * region thread. Sable's Rapier physics engine runs its simulation step on a separate JNI
 * thread. The results of physics simulation (position updates, collision events) must be
 * communicated back to the correct region threads for world mutation. Direct JNI callbacks
 * into Minecraft code from the physics thread would violate Folia's thread model.</p>
 *
 * <h2>What This Mod Handles</h2>
 * <ul>
 *   <li><b>Cross-Region Sub-Level Management</b> — Sable creates "sub-levels" (isolated
 *       physics spaces) for vehicles. When a vehicle flies across region boundaries,
 *       the sub-level must be transferred to the new region's ownership while maintaining
 *       physics continuity.</li>
 *   <li><b>Physics-Region Bridge</b> — Bridges Rapier physics step results to Folia region
 *       threads via async message queues, ensuring physics updates are applied on the
 *       correct region thread without blocking the physics simulation.</li>
 *   <li><b>JNI Thread Safety</b> — Audits all JNI callbacks from the Rapier physics engine
 *       to detect and prevent unsafe cross-thread access to Minecraft data structures.
 *       Violations are logged with stack traces for debugging.</li>
 *   <li><b>Vehicle Assembly Region Awareness</b> — Ensures vehicle assembly/disassembly
 *       operations respect region ownership, particularly for large aircraft that span
 *       multiple regions during construction.</li>
 * </ul>
 *
 * @see CrossRegionSubLevelManager
 * @see SablePhysicsRegionBridge
 * @see JNIThreadSafetyAuditor
 * @see VehicleAssemblyRegionHandler
 */
@Mod(CreliaCompatSable.MOD_ID)
public class CreliaCompatSable {

    /** Mod identifier used in {@code neoforge.mods.toml} and registration. */
    public static final String MOD_ID = "crelia_compat_sable";

    /** Logger instance for diagnostic and error output. */
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private final ModContainer modContainer;

    /**
     * Constructs the compat layer and registers lifecycle hooks.
     *
     * <p>The NeoForge mod container is stored so that we can access mod metadata
     * (version, display name) at runtime if needed for diagnostic commands.</p>
     *
     * @param modEventBus  the NeoForge mod event bus for lifecycle events
     * @param modContainer the NeoForge mod container for this compatibility module
     */
    public CreliaCompatSable(IEventBus modEventBus, ModContainer modContainer) {
        this.modContainer = modContainer;

        LOGGER.info("[CreliaCompatSable] Initializing Sable + Create Aeronautics compat for Folia regionized server");

        // Register the FMLCommonSetupEvent handler which initializes all sub-systems
        modEventBus.addListener(this::onCommonSetup);
    }

    /**
     * Common setup phase — initializes all region-aware handlers for Sable physics.
     *
     * <p>This runs once during server startup. The Sable physics engine is not yet running
     * at this point, so we set up the message queues, JNI audit hooks, and event listeners
     * that will be used once physics simulation begins.</p>
     *
     * @param event the FML common setup event
     */
    private void onCommonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("[CreliaCompatSable] Common setup: registering region-aware handlers");

        event.enqueueWork(() -> {
            // Install JNI thread safety audit hooks before the Rapier engine initializes
            JNIThreadSafetyAuditor.installHooks();

            // Initialize the physics-to-region message bridge
            SablePhysicsRegionBridge.init();

            // Register the cross-region sub-level management handler
            CrossRegionSubLevelManager.register();

            // Register the vehicle assembly region handler
            VehicleAssemblyRegionHandler.register();

            LOGGER.info("[CreliaCompatSable] All Sable region-aware handlers registered successfully");
        });
    }
}
