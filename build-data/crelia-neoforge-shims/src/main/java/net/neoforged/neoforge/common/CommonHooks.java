/*
 * Crelia-NeoForge: Compile-only shim for net.neoforged.neoforge.common.CommonHooks.
 * At runtime, the real NeoForge CommonHooks is loaded by FML class loader.
 * This shim uses java.lang.Object to avoid MC-type dependencies during compilation.
 */
package net.neoforged.neoforge.common;

import java.util.List;
import java.util.Optional;

/**
 * Compile-only shim. Do NOT use at runtime — FML loads the real CommonHooks.
 */
public final class CommonHooks {
    private CommonHooks() { throw new UnsupportedOperationException("shim"); }

    // === LivingEntity Lifecycle ===

    public static boolean onLivingDeath(Object entity, Object source) {
        return false; // shim — return true to cancel death
    }

    public static void onLivingFall(Object entity, float distance, float multiplier, Object source) {
        // shim — real implementation fires LivingFallEvent
    }

    public static void onLivingJump(Object entity) {
        // shim — real implementation fires LivingJumpEvent
    }

    // === Damage System ===

    public static boolean onEntityIncomingDamage(Object entity, Object source, float amount) {
        return false; // shim — return true to cancel
    }

    public static void onLivingDamagePre(Object entity, Object source, float amount) {
        // shim
    }

    public static float onLivingDamagePost(Object entity, Object source, float amount, float newAmount) {
        return newAmount; // shim — return modified damage
    }

    public static void onArmorHurt(Object entity, Object source, float damage) {
        // shim — fires LivingShieldBlockEvent
    }

    public static void onDamageBlock(Object entity, Object source) {
        // shim — fires LivingShieldBlockEvent
    }

    public static boolean isEntityInvulnerableTo(Object entity, Object source) {
        return false; // shim — return true to bypass invulnerability
    }

    // === LivingEntity Combat ===

    public static void onLivingKnockBack(Object entity, Object source, float strength, double ratioX, double ratioZ) {
        // shim — fires LivingKnockBackEvent
    }

    public static boolean onLivingUseTotem(Object entity, Object source, Object totem) {
        return false; // shim — return true to prevent totem usage
    }

    public static void onLivingDrops(Object entity, Object source, List<Object> drops, boolean recentlyHit, int lootingLevel) {
        // shim — fires LivingDropsEvent
    }

    public static void onLivingSwapHandItems(Object entity) {
        // shim — fires LivingSwapHandItemsEvent
    }

    public static void onEffectRemoved(Object entity, Object effectInstance, Object reason) {
        // shim — fires MobEffectRemoveEvent
    }

    public static boolean canMobEffectBeApplied(Object entity, Object effectInstance) {
        return true; // shim — return false to prevent effect
    }

    public static boolean canLivingConvert(Object entity, Object newEntity) {
        return true; // shim — return false to cancel conversion
    }

    public static void onLivingConvert(Object entity, Object newEntity) {
        // shim — fires MobConvertEvent.Post
    }

    // === Entity Size ===

    public static float getEntitySizeForge(Object entity, Object pose, Object size) {
        return 0; // shim
    }

    // === Entity Visibility ===

    public static double getEntityVisibilityMultiplier(Object entity, Object lookingEntity, double baseMultiplier) {
        return baseMultiplier; // shim
    }

    public static boolean isLivingOnLadder(Object entity, Object state, Object pos) {
        return false; // shim — return true to treat as ladder
    }

    public static boolean shouldSuppressEnderManAnger(Object enderman, Object player) {
        return false; // shim — return true to suppress
    }

    // === Player Events ===

    public static void onPlayerTossEvent(Object player, Object itemStack, boolean includeThrowerName) {
        // shim — fires PlayerTossEvent
    }

    public static void onPlayerAttackTarget(Object player, Object target) {
        // shim — fires AttackEntityEvent
    }

    public static void onPlayerAttackTargetPost(Object player, Object target, boolean succeeded) {
        // shim
    }

    // === Item Interaction ===

    public static void onLeftClickBlock(Object player, Object pos, Object face, Object clickResult) {
        // shim — fires PlayerInteractEvent.LeftClickBlock
    }

    public static void onRightClickBlock(Object player, Object hand, Object pos, Object face) {
        // shim — fires PlayerInteractEvent.RightClickBlock
    }

    public static void onRightClickItem(Object player, Object hand) {
        // shim — fires PlayerInteractEvent.RightClickItem
    }

    public static void onEmptyClick(Object player, Object hand) {
        // shim — fires PlayerInteractEvent
    }

    public static void onEmptyLeftClick(Object player, Object hand) {
        // shim
    }

    public static void onInteractEntity(Object player, Object target, Object hand) {
        // shim — fires PlayerInteractEvent.EntityInteract
    }

    public static void onInteractEntityAt(Object player, Object target, Object hitVec, Object hand) {
        // shim — fires PlayerInteractEvent.EntityInteractSpecific
    }

    // === Item/Block Placement ===

    public static void onBlockPlace(Object placeContext, Object blockState) {
        // shim — fires BlockEvent.PlaceEvent
    }

    public static void onMultiBlockPlace(Object placeContext, List<Object> blockStates) {
        // shim — fires MultiBlockPlaceEvent
    }

    public static void onPlaceItemIntoWorld(Object placeContext) {
        // shim — fires BlockPlaceEvent
    }

    // === Block Breaking ===

    public static void fireBlockBreak(Object level, Object pos, Object state, Object player, boolean xp) {
        // shim — fires BreakEvent
    }

    public static void handleBlockDrops(Object level, Object pos, Object state, Object serverPlayer, Object tool, List<Object> drops, boolean silkTouch, int fortune) {
        // shim — fires BlockDropsEvent
    }

    // === Critical Hit ===

    public static boolean fireCriticalHit(Object player, Object target, boolean isCritical) {
        return isCritical; // shim — return false to cancel crit
    }

    public static boolean fireSweepAttack(Object player, Object target) {
        return true; // shim — return false to cancel sweep
    }

    // === Farmland / NoteBlock ===

    public static void onFarmlandTrample(Object level, Object pos, Object state, float fallDistance, Object entity) {
        // shim — fires BlockEvent.FarmlandTrampleEvent
    }

    public static void onNoteChange(Object level, Object pos, Object state, Object instrument) {
        // shim — fires NoteBlockEvent.Play
    }

    // === Crop Grow ===

    public static boolean canCropGrow(Object block, Object level, Object pos, Object state) {
        return true; // shim — return false to prevent growth
    }

    public static void fireCropGrowPost(Object block, Object level, Object pos, Object state) {
        // shim — fires CropGrowEvent.Post
    }

    // === Item Usage ===

    public static boolean canContinueUsing(Object entity, Object item, int remainingUseDuration) {
        return true; // shim — return false to stop using
    }

    public static void onItemStackedOn(Object carried, Object stack, Object container, Object player) {
        // shim — fires ItemStackedOnOtherEvent
    }

    // === Entity Griefing ===

    public static boolean canEntityGrief(Object entity) {
        return true; // shim — return false to prevent griefing
    }

    public static boolean canEntityDestroy(Object entity, Object state, Object level, Object pos) {
        return true; // shim
    }

    // === Travel ===

    public static void onTravelToDimension(Object entity, Object dimension) {
        // shim — fires EntityTravelToDimensionEvent
    }

    // === Anvil ===

    public static void onAnvilUpdate(Object container, Object left, Object right, Object name, int cost, Object player) {
        // shim — fires AnvilUpdateEvent
    }

    public static boolean fireAnvilCraftPre(Object container, Object left, Object right, Object name, Object output, int cost, Object player) {
        return false; // shim — return true to cancel
    }

    public static void fireAnvilCraftPost(Object container, Object left, Object right, Object name, Object output, int cost, Object player) {
        // shim — fires AnvilRepairEvent
    }

    // === Grindstone ===

    public static void onGrindstoneChange(Object container, Object top, Object bottom, Object player) {
        // shim — fires GrindstoneEvent
    }

    public static void onGrindstoneTake(Object container, Object top, Object bottom, Object player) {
        // shim
    }

    // === Game Mode ===

    public static void onChangeGameType(Object player, Object newGameType, Object oldGameType) {
        // shim — fires PlayerEvent.ChangeGameMode
    }

    // === Attribute Modification ===

    public static void modifyAttributes(Object itemStack, Object slotType, List<Object> modifiers) {
        // shim — fires ItemAttributeModifierEvent
    }

    public static void computeModifiedAttributes(Object itemStack, Object slotType, List<Object> modifiers) {
        // shim
    }

    // === Projectile ===

    public static Object getProjectile(Object weaponItem, Object projectileStack, Object shooter) {
        return projectileStack; // shim — return modified projectile
    }

    // === Entity Enter Section ===

    public static void onEntityEnterSection(Object entity, Object from, Object to) {
        // shim — fires EntityEvent.EnteringSection
    }

    // === Chat ===

    public static void onServerChatSubmittedEvent(Object player, Object message, Object chatDecorator) {
        // shim
    }

    // === Custom Click Action ===

    public static void onCustomClickAction(Object handler, Object player, Object action) {
        // shim
    }

    // === Data Packs ===

    public static Object getModDataPacks() {
        return null; // shim — returns mod data packs
    }

    public static Object getModDataPacksWithVanilla() {
        return null; // shim
    }

    // === Attribute Registration ===

    public static void getAttributesView(Object supplier, Object builder) {
        // shim
    }

    public static void modifyAttributes(Object supplier, Object builder) {
        // shim
    }

    // === Recipes ===

    public static void sendRecipes(Object player, Object recipeManager) {
        // shim
    }

    // === Loot ===

    public static void modifyLoot(Object lootTable, Object context, List<Object> generatedLoot) {
        // shim — fires LootTableLoadEvent
    }

    // === Vanilla Game Event ===

    public static void onVanillaGameEvent(Object level, Object event, Object pos, Object context) {
        // shim
    }

    // === Item Creator Mod ID ===

    public static String getDefaultCreatorModId(Object itemStack) {
        return null; // shim
    }

    // === Serializer ===

    public static Object getSerializer(Object entry) {
        return null; // shim
    }

    public static int getSerializerId(Object entry) {
        return -1; // shim
    }

    // === World Save Data ===

    public static void writeAdditionalLevelSaveData(Object overworldData, Object compound) {
        // shim
    }

    public static void readAdditionalLevelSaveData(Object overworldData, Object compound) {
        // shim
    }

    // === Enchanting ===

    public static void onPlayerEnchantItem(Object player, Object container, int slot) {
        // shim — fires PlayerEnchantEvent
    }

    // === Crafting ===

    public static void setCraftingPlayer(Object player) {
        // shim — ThreadLocal for crafting player
    }

    public static Object getCraftingPlayer() {
        return null; // shim
    }

    public static void firePlayerCraftingEvent(Object player, Object crafted, Object matrix) {
        // shim — fires PlayerCraftingEvent
    }

    public static void firePlayerSmeltedEvent(Object player, Object smelted) {
        // shim — fires PlayerSmeltedEvent
    }

    // === Target Change ===

    public static void onLivingChangeTarget(Object entity, Object newTarget) {
        // shim
    }

    // === LivingEntity AI target change ===
    public static Object onLivingChangeTarget(Object entity, Object source, Object newTarget) {
        return newTarget; // shim
    }
}
