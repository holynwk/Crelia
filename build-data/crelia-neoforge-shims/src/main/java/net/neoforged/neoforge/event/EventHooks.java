package net.neoforged.neoforge.event;

import java.util.List;
import java.util.function.BooleanSupplier;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Crelia-NeoForge: Compile-only shim for net.neoforged.neoforge.event.EventHooks.
 * At runtime, the real NeoForge EventHooks is loaded by FML class loader.
 * This shim uses java.lang.Object to avoid MC-type dependencies during compilation.
 */
public class EventHooks {

    // === Tick Events ===

    public static EntityTickEvent.Pre fireEntityTickPre(Object entity) {
        return new EntityTickEvent.Pre(entity);
    }

    public static void fireEntityTickPost(Object entity) {}

    public static void firePlayerTickPre(Object player) {}

    public static void firePlayerTickPost(Object player) {}

    public static void fireLevelTickPre(Object level, BooleanSupplier haveTime) {}

    public static void fireLevelTickPost(Object level, BooleanSupplier haveTime) {}

    public static void fireServerTickPre(BooleanSupplier haveTime, Object server) {}

    public static void fireServerTickPost(BooleanSupplier haveTime, Object server) {}

    // === Player Lifecycle Events ===

    public static void firePlayerLoggedIn(Object player) {}

    public static void firePlayerLoggedOut(Object player) {}

    public static void firePlayerLoadingEvent(Object player) {}

    public static void firePlayerSavingEvent(Object player) {}

    public static void firePlayerRespawnPositionEvent(Object player, Object pos) {}

    public static void firePlayerRespawnEvent(Object player, boolean endConquered) {}

    public static void firePlayerChangedDimensionEvent(Object player, Object from, Object to) {}

    public static void onPermissionChanged(Object player, Object permission, boolean granted) {}

    // === Explosion Events ===

    public static boolean onExplosionStart(Object level, Object explosion) {
        return false;
    }

    public static void onExplosionDetonate(Object level, Object explosion, List<?> entities, List<?> blocks) {}

    public static float getExplosionKnockback(Object entity, Object explosion, float knockback) {
        return knockback;
    }

    // === LivingEntity Events ===

    public static float onLivingHeal(Object entity, float amount) {
        return amount;
    }

    public static void onLivingDamagePre(Object entity, Object source, float amount) {}

    public static void onLivingDamagePost(Object entity, Object source, float amount, float originalAmount) {}

    // === Effect Events ===

    public static boolean onEffectRemoved(Object entity, Object effect, Object entityRemoveReason) {
        return false;
    }

    // === Entity Events ===

    public static void onEntityStruckByLightning(Object entity, Object lightning) {}

    public static boolean onLivingConvert(Object entity, Object newEntity) {
        return false;
    }

    public static void onLivingConvertPost(Object entity, Object newEntity) {}

    public static boolean onEntityTeleportCommand(Object entity, double x, double y, double z, Object level) {
        return false;
    }

    public static void onEnderTeleport(Object entity, double targetX, double targetY, double targetZ, float attackDamage) {}

    public static void onEnderPearlLand(Object pearl, Object hitResult, float damage) {}

    // === Item Events ===

    public static boolean onProjectileImpact(Object projectile, Object result) {
        return false;
    }

    public static boolean fireItemPickupPre(Object player, Object itemEntity) {
        return false;
    }

    public static void fireItemPickupPost(Object player, Object itemEntity) {}

    public static void onItemExpire(Object itemEntity) {}

    public static void onItemTooltip(Object itemStack, Object player, Object tooltipList, Object flag, Object context) {}

    public static boolean onItemUseStart(Object entity, Object item, int duration) {
        return false;
    }

    public static void onItemUseTick(Object entity, Object item, int duration) {}

    public static void onItemUseStop(Object entity, Object item, int duration, int remaining) {}

    public static void onItemUseFinish(Object entity, Object item, int duration, ItemStackConsumedContext stack) {}

    // === Projectile Events ===

    public static void onArrowNock(Object bow, Object player, Object ammo, Object hand) {}

    public static void onArrowLoose(Object bow, Object level, Object player, int charge, Object ammo, float velocity) {}

    // === Tracking Events ===

    public static void onStartEntityTracking(Object tracker, Object tracked) {}

    public static void onStopEntityTracking(Object tracker, Object tracked) {}

    // === Entity Spawn ===

    public static boolean checkSpawnPlacements(Object type, Object level, Object spawnReason, Object pos, Object random) {
        return true;
    }

    public static boolean checkSpawnPosition(Object type, Object level, Object spawnReason, Object pos, Object random) {
        return true;
    }

    public static boolean checkSpawnPositionSpawner(Object type, Object level, Object pos, Object random) {
        return true;
    }

    public static int getMaxSpawnClusterSize(Object type) {
        return -1;
    }

    // === Player Display ===

    public static Object getPlayerDisplayName(Object player) {
        return ((net.minecraft.network.chat.Component) ((net.minecraft.world.entity.player.Player) player).getDisplayName());
    }

    public static Object getPlayerTabListDisplayName(Object player) {
        return null;
    }

    // === Sound Events ===

    public static void onPlaySoundAtEntity(Object level, Object entity, Object event, Object category, float volume, float pitch) {}

    public static void onPlaySoundAtPosition(Object level, double x, double y, double z, Object holder, Object category, float volume, float pitch) {}

    // === Block Events ===

    public static boolean fireBlockGrowFeature(Object level, Object pos, Object state, Object feature) {
        return false;
    }

    public static void onNeighborNotify(Object level, Object pos, Object state, Object direction, Object notifiedBlocks) {}

    // === Chunk Events ===

    public static void fireChunkTicketLevelUpdated(Object level, Object chunkPos, int oldLevel, int newLevel) {}

    public static void fireChunkWatch(Object player, Object chunk, Object level) {}

    public static void fireChunkSent(Object player, Object chunk, Object level) {}

    public static void fireChunkUnWatch(Object player, Object chunkPos, Object level) {}

    // === Piston Events ===

    public static boolean onPistonMovePre(Object level, Object pos, Object direction, boolean extending) {
        return false;
    }

    public static void onPistonMovePost(Object level, Object pos, Object direction, boolean extending) {}

    // === Sleep Events ===

    public static void onSleepFinished(Object level, Object pos, long newTime, boolean skipNight) {}

    public static boolean canPlayerStartSleeping(Object player, Object pos) {
        return true;
    }

    public static void onPlayerWakeup(Object player, boolean updateLevel) {}

    // === Player Fall ===

    public static void onPlayerFall(Object player, float distance, float multiplier) {}

    // === Brewing Events ===

    public static boolean onPotionAttemptBrew(Object brewingStand, Object ingredientList) {
        return true;
    }

    public static void onPotionBrewed(Object brewingStand, Object ingredientList) {}

    public static void onPlayerBrewedPotion(Object player, Object stack) {}

    // === Enchanting Events ===

    public static void onEnchantmentLevelSet(Object player, Object container, int slot, int cost, Object itemStack, Object enchantments) {}

    // === Portal Events ===

    public static void onTrySpawnPortal(Object level, Object pos, Object size) {}

    // === LivingEntity Misc ===

    public static boolean canEntityGrief(Object entity) {
        return true;
    }

    public static void onMobSplit(Object slime, int newSize) {}

    public static void onAnimalTame(Object animal, Object tamer) {}

    // === Loot ===

    public static boolean loadLootTable(Object resourceManager, Object lootDataId, Object lootTable) {
        return true;
    }

    // === Fluid ===

    public static boolean canCreateFluidSource(Object state, Object level, Object pos, Object block) {
        return false;
    }

    // === Player Tools ===

    public static boolean doPlayerHarvestCheck(Object player, Object state, boolean canHarvest) {
        return canHarvest;
    }

    public static float getBreakSpeed(Object player, Object state, float original, Object pos) {
        return original;
    }

    public static void onPlayerDestroyItem(Object player, Object stack, Object hand) {}

    // === Player Mount ===

    public static boolean canMountEntity(Object entity, Object vehicle, boolean isRiding) {
        return true;
    }

    // === Experience ===

    public static int getExperienceDrop(Object entity, Object player, int originalXp) {
        return originalXp;
    }

    // === Resource Reload ===

    public static void onResourceReload(Object serverResources) {}

    // === Command Register ===

    public static void onCommandRegister(Object commands, boolean dedicated) {}

    // === Game Rules ===

    public static void onGameRuleChanged(Object rules, Object key, Object type, Object value) {}

    // === Stat ===

    public static void onStatAward(Object player, Object stat, int value) {}

    // === Advancement ===

    public static void onAdvancementEarnedEvent(Object player, Object advancement) {}

    public static void onAdvancementProgressedEvent(Object player, Object advancement, Object criterionName) {}

    // === Crop Grow ===

    public static boolean canCropGrow(Object block, Object level, Object pos, Object state) {
        return true;
    }

    public static void fireCropGrowPost(Object block, Object level, Object pos, Object state) {}

    // === Entity Enter Section ===

    public static void onEntityEnterSection(Object entity, Object from, Object to) {}

    // === Custom Spawners ===

    public static void getCustomSpawners(Object level, List<Object> spawners, boolean spawnAnimals, boolean spawnMonsters) {}

    // === Difficulty ===

    public static void onDifficultyChange(Object server, Object newDifficulty, Object oldDifficulty) {}

    // === Inner class for item use finish context
    public static class ItemStackConsumedContext {
        public net.minecraft.world.item.ItemStack itemStack;
        public ItemStackConsumedContext(net.minecraft.world.item.ItemStack is) { this.itemStack = is; }
    }
}
