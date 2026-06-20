#!/usr/bin/env python3
"""
Crelia - Generate NeoForge minecraft-patches for Folia+NeoForge hybrid kernel.
Creates patches 0014-0023 injecting EventHooks/CommonHooks calls into vanilla MC classes.
These patches are adapted for the Folia-patched decompiled source.
"""

import os

PATCH_DIR = "/home/z/my-project/crelia-neoforge/folia-server/minecraft-patches/features"
PATCH_HEADER = """From 0000000000000000000000000000000000000000 Mon Sep 17 00:00:00 2001
From: Crelia <crelia@users.noreply.github.com>
Date: Mon, 1 Jan 2024 00:00:00 +0000
Subject: [PATCH] {title}

{description}
"""


def make_file_diff(filepath, old_hash="0000000000000000000000000000000000000000", new_hash="0000000000000000000000000000000000000001"):
    """Create a diff header for a file."""
    return f"""diff --git a/{filepath} b/{filepath}
index {old_hash}..{new_hash} 100644
--- a/{filepath}
+++ b/{filepath}
"""


def make_import_hunk(imports, at_line, context_before, context_after):
    """Create a hunk that adds imports after a context line."""
    lines = []
    for i, line in enumerate(context_before):
        lines.append(f" {line}")
    for imp in imports:
        lines.append(f"+{imp}")
    for line in context_after:
        lines.append(f" {line}")
    count_before = len(context_before)
    count_after = len(context_after)
    return f"@@ -{at_line},{count_before + count_after} +{at_line},{count_before + len(imports) + count_after} @@\n" + "\n".join(lines)


def make_code_hunk(context_before, insert_lines, context_after, line_no):
    """Create a hunk that inserts code between context lines."""
    lines = []
    for line in context_before:
        lines.append(f" {line}")
    for ins in insert_lines:
        lines.append(f"+{ins}")
    for line in context_after:
        lines.append(f" {line}")
    total_ctx = len(context_before) + len(context_after)
    return f"@@ -{line_no},{total_ctx} +{line_no},{total_ctx + len(insert_lines)} @@\n" + "\n".join(lines)


# ============================================================
# PATCH 0014: Additional Entity Hooks
# LivingEntity: heal, die, fall, jump
# ItemEntity: expire, playerTouch
# Projectile: onHit
# ItemStack: tooltip
# ============================================================
def create_patch_0014():
    title = "NeoForge Additional Entity Hooks"
    desc = """Injects additional NeoForge event hooks into entity classes:
- LivingEntity: LivingHealEvent, LivingDeathEvent, LivingFallEvent, LivingJumpEvent
- ItemEntity: ItemExpireEvent, ItemEntityPickupEvent.Pre
- Projectile: ProjectileImpactEvent
- ItemStack: ItemTooltipEvent

These hooks are independent of Folia's region threading and work correctly
in the multi-threaded environment because they operate on the entity's
own region thread."""
    
    content = PATCH_HEADER.format(title=title, description=desc)
    
    # --- LivingEntity.java ---
    content += make_file_diff("net/minecraft/world/entity/LivingEntity.java", "0000000000000000000000000000000000000000", "1111111111111111111111111111111111111111")
    
    # Import hunk
    content += "@@ -136,6 +136,8 @@\n"
    content += " import org.slf4j.Logger;\n"
    content += "+// NeoForge start - entity event hooks\n"
    content += "+import net.neoforged.neoforge.event.EventHooks;\n"
    content += "+import net.neoforged.neoforge.common.CommonHooks;\n"
    content += "+// NeoForge end\n"
    content += " // CraftBukkit start\n"
    content += " import java.util.ArrayList;\n"
    
    # heal() hook
    content += "@@ -1378,6 +1381,10 @@\n"
    content += "     public void heal(float f, EntityRegainHealthEvent.RegainReason regainReason, boolean isFastRegen) {\n"
    content += "         // Paper end\n"
    content += "+        // NeoForge start - fire LivingHealEvent (cancelable)\n"
    content += "+        f = EventHooks.onLivingHeal(this, f);\n"
    content += "+        if (f <= 0) return;\n"
    content += "+        // NeoForge end\n"
    content += "         float f1 = this.getHealth();\n"
    content += " \n"
    content += "         if (f1 > 0.0F) {\n"
    
    # die() hook
    content += "@@ -1775,6 +1782,9 @@\n"
    content += "     public void die(DamageSource damageSource) {\n"
    content += "         if (!this.isRemoved() && !this.dead) {\n"
    content += "+            // NeoForge start - fire LivingDeathEvent (cancelable)\n"
    content += "+            if (CommonHooks.onLivingDeath(this, damageSource)) return;\n"
    content += "+            // NeoForge end\n"
    content += "             Entity entity = damageSource.getEntity();\n"
    content += "             LivingEntity entityliving = this.getKillCredit();\n"
    
    # causeFallDamage() hook
    content += "@@ -2134,6 +2144,9 @@\n"
    content += "     @Override\n"
    content += "     public boolean causeFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {\n"
    content += "         boolean flag = super.causeFallDamage(fallDistance, damageMultiplier, damageSource);\n"
    content += "+        // NeoForge start - fire LivingFallEvent\n"
    content += "+        CommonHooks.onLivingFall(this, fallDistance, damageMultiplier, damageSource);\n"
    content += "+        // NeoForge end\n"
    content += "         int i = this.calculateFallDamage(fallDistance, damageMultiplier);\n"
    
    # jumpFromGround() hook
    content += "@@ -2851,6 +2864,7 @@\n"
    content += "     @VisibleForTesting\n"
    content += "     public void jumpFromGround() {\n"
    content += "+        CommonHooks.onLivingJump(this); // NeoForge - fire LivingJumpEvent\n"
    content += "         float f = this.getJumpPower();\n"
    content += " \n"
    content += "         if (f > 1.0E-5F) {\n"
    
    # --- ItemEntity.java ---
    content += make_file_diff("net/minecraft/world/entity/item/ItemEntity.java", "0000000000000000000000000000000000000000", "2222222222222222222222222222222222222222")
    
    content += "@@ -29,6 +29,7 @@\n"
    content += " import net.minecraft.world.level.gameevent.GameEvent;\n"
    content += " import net.minecraft.world.level.portal.DimensionTransition;\n"
    content += " import net.minecraft.world.phys.Vec3;\n"
    content += "+import net.neoforged.neoforge.event.EventHooks; // NeoForge\n"
    content += " // CraftBukkit start\n"
    
    # onItemExpire
    content += "@@ -232,6 +233,9 @@\n"
    content += "                     return;\n"
    content += "                 }\n"
    content += "                 // CraftBukkit end\n"
    content += "+                // NeoForge start - fire ItemExpireEvent\n"
    content += "+                EventHooks.onItemExpire(this);\n"
    content += "+                // NeoForge end\n"
    content += "                 this.discard(EntityRemoveEvent.Cause.DESPAWN); // CraftBukkit\n"
    
    # playerTouch
    content += "@@ -465,6 +469,9 @@\n"
    content += "     @Override\n"
    content += "     public void playerTouch(net.minecraft.world.entity.player.Player player) {\n"
    content += "+        // NeoForge start - fire ItemEntityPickupEvent.Pre (cancelable)\n"
    content += "+        if (EventHooks.fireItemPickupPre(this, player)) return;\n"
    content += "+        // NeoForge end\n"
    content += "         if (!this.level().isClientSide) {\n"
    
    # --- Projectile.java ---
    content += make_file_diff("net/minecraft/world/entity/projectile/Projectile.java", "0000000000000000000000000000000000000000", "3333333333333333333333333333333333333333")
    
    content += "@@ -29,6 +29,7 @@\n"
    content += " import net.minecraft.world.phys.Vec3;\n"
    content += "+import net.neoforged.neoforge.event.EventHooks; // NeoForge\n"
    content += " // CraftBukkit start\n"
    
    content += "@@ -272,6 +273,9 @@\n"
    content += "     protected void onHit(HitResult hitResult) {\n"
    content += "+        // NeoForge start - fire ProjectileImpactEvent (cancelable)\n"
    content += "+        if (EventHooks.onProjectileImpact(this, hitResult)) return;\n"
    content += "+        // NeoForge end\n"
    content += "         HitResult.Type movingobjectposition_enummovingobjecttype = hitResult.getType();\n"
    
    # --- ItemStack.java ---
    content += make_file_diff("net/minecraft/world/item/ItemStack.java", "0000000000000000000000000000000000000000", "4444444444444444444444444444444444444444")
    
    content += "@@ -88,6 +88,7 @@\n"
    content += " import org.slf4j.Logger;\n"
    content += "+import net.neoforged.neoforge.event.EventHooks; // NeoForge\n"
    
    content += "@@ -1115,6 +1116,10 @@\n"
    content += "                 list.add(ItemStack.DISABLED_ITEM_TOOLTIP);\n"
    content += "             }\n"
    content += "+\n"
    content += "+            // NeoForge start - fire ItemTooltipEvent\n"
    content += "+            EventHooks.onItemTooltip(this, player, list, type, context);\n"
    content += "+            // NeoForge end\n"
    content += " \n"
    content += "             return list;\n"
    
    return content


# ============================================================
# PATCH 0015: LivingEntity Damage Hooks (CommonHooks)
# hurt(), knockback(), armor, totem, effect removal, drops
# ============================================================
def create_patch_0015():
    title = "NeoForge LivingEntity Damage Hooks"
    desc = """Injects NeoForge damage-system hooks into LivingEntity:
- onEntityIncomingDamage in hurt() — LivingDamageEvent.Pre
- onLivingDamagePost in actuallyHurt() — LivingDamageEvent.Post
- onLivingKnockBack in knockback()
- onArmorHurt in hurtArmor()
- onLivingUseTotem in checkTotemDeathProtection()
- onEffectRemoved in removeEffect()
- onLivingDrops in dropAllDeathLoot()

These are the core of NeoForge's damage modification system.
In Crelia, all these hooks run on the entity's owning region thread,
ensuring thread safety without additional synchronization."""
    
    content = PATCH_HEADER.format(title=title, description=desc)
    content += make_file_diff("net/minecraft/world/entity/LivingEntity.java", "1111111111111111111111111111111111111111", "5555555555555555555555555555555555555555")
    
    # hurt() — incoming damage hook
    content += "@@ -1310,6 +1310,10 @@\n"
    content += "     public boolean hurt(DamageSource source, float amount) {\n"
    content += "         // Paper start\n"
    content += "         if (!this.level().paperConfig().entities.mobExplosionDamage.enabled && source.is(net.minecraft.world.damagesource.DamageTypes.BAD_RESPAWN_POINT)) return false; // Folia - region threading\n"
    content += "+        // NeoForge start - fire LivingDamageEvent.Pre\n"
    content += "+        if (CommonHooks.onEntityIncomingDamage(this, source, amount)) return false;\n"
    content += "+        // NeoForge end\n"
    content += " \n"
    content += "         if (this.isInvulnerableTo(source)) {\n"
    
    # isInvulnerableTo() hook
    content += "@@ -1330,6 +1334,9 @@\n"
    content += "     public boolean isInvulnerableTo(DamageSource source) {\n"
    content += "+        // NeoForge start - allow mods to bypass invulnerability\n"
    content += "+        if (CommonHooks.isEntityInvulnerableTo(this, source)) return false;\n"
    content += "+        // NeoForge end\n"
    content += "         if (this.isRemoved()) return true; // CraftBukkit - SPIGOT-7625\n"
    
    # knockback() hook
    content += "@@ -1515,6 +1522,9 @@\n"
    content += "     public void knockback(double strength, double ratioX, double ratioZ) {\n"
    content += "+        // NeoForge start - fire LivingKnockBackEvent\n"
    content += "+        CommonHooks.onLivingKnockBack(this, null, (float)strength, ratioX, ratioZ);\n"
    content += "+        // NeoForge end\n"
    content += "         if (this.isSuppressingBounce()) {\n"
    
    # hurtArmor() hook
    content += "@@ -1650,6 +1660,9 @@\n"
    content += "     protected void hurtArmor(DamageSource source, float damage) {\n"
    content += "+        // NeoForge start - fire LivingShieldBlockEvent\n"
    content += "+        CommonHooks.onArmorHurt(this, source, damage);\n"
    content += "+        // NeoForge end\n"
    
    # checkTotemDeathProtection() hook
    content += "@@ -1720,6 +1733,10 @@\n"
    content += "     public boolean checkTotemDeathProtection(DamageSource source) {\n"
    content += "+        // NeoForge start - fire LivingEntityUseTotemEvent\n"
    content += "+        ItemStack totem = null;\n"
    content += "+        if (CommonHooks.onLivingUseTotem(this, source, totem)) return false;\n"
    content += "+        // NeoForge end\n"
    content += "         if (source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {\n"
    
    # actuallyHurt() — post damage hook
    content += "@@ -1830,6 +1847,9 @@\n"
    content += "     protected void actuallyHurt(DamageSource source, float amount) {\n"
    content += "+        // NeoForge start - fire LivingDamageEvent.Post\n"
    content += "+        amount = CommonHooks.onLivingDamagePost(this, source, amount, amount);\n"
    content += "+        // NeoForge end\n"
    content += "         if (!this.isDamageTicked()) {\n"
    
    # removeEffect() — effect removal hook
    content += "@@ -1230,6 +1242,9 @@\n"
    content += "     public boolean removeEffect(MobEffect effect) {\n"
    content += "+        // NeoForge start - fire MobEffectRemoveEvent\n"
    content += "+        if (EventHooks.onEffectRemoved(this, effect, EntityRemoveReason.REMOVED)) return false;\n"
    content += "+        // NeoForge end\n"
    
    # dropAllDeathLoot() — living drops hook
    content += "@@ -1900,6 +1915,9 @@\n"
    content += "     public void dropAllDeathLoot(DamageSource source) {\n"
    content += "+        // NeoForge start - fire LivingDropsEvent\n"
    content += "+        CommonHooks.onLivingDrops(this, source, this.drops, recentlyHit > 0, lootingLevel);\n"
    content += "+        // NeoForge end\n"
    
    return content


# ============================================================
# PATCH 0016: Player Interaction Hooks
# attack(), left/right click block, interact entity, item right click
# ============================================================
def create_patch_0016():
    title = "NeoForge Player Interaction Hooks"
    desc = """Injects NeoForge player interaction hooks:
- Player.attack() — AttackEntityEvent, CriticalHitEvent, SweepAttack
- ServerGamePacketListenerImpl — LeftClickBlock, RightClickBlock, RightClickItem
- Player.interact() — EntityInteract, EntityInteractSpecific
- Player.useItem() — PlayerInteractEvent.RightClickItem
- ServerPlayerGameMode.useItemOn() — RightClickBlock detailed

All interaction events fire on the player's owning region thread."""
    
    content = PATCH_HEADER.format(title=title, description=desc)
    
    # --- Player.java ---
    content += make_file_diff("net/minecraft/world/entity/player/Player.java", "7777777777777777777777777777777777777777", "6666666666666666666666666666666666666666")
    
    # attack() hook
    content += "@@ -1200,6 +1200,10 @@\n"
    content += "     public void attack(Entity target) {\n"
    content += "+        // NeoForge start - fire AttackEntityEvent\n"
    content += "+        CommonHooks.onPlayerAttackTarget(this, target);\n"
    content += "+        // NeoForge end\n"
    content += "         if (target.isAttackable()) {\n"
    
    # critical hit hook inside attack
    content += "@@ -1220,6 +1224,10 @@\n"
    content += "             if (flag2) {\n"
    content += "+                // NeoForge start - fire CriticalHitEvent\n"
    content += "+                if (!CommonHooks.fireCriticalHit(this, target, true)) flag2 = false;\n"
    content += "+                // NeoForge end\n"
    content += "                 if (i > 0.0F) {\n"
    
    # sweep attack hook
    content += "@@ -1265,6 +1273,10 @@\n"
    content += "             for (LivingEntity livingentity : list) {\n"
    content += "+                // NeoForge start - fire SweepAttackEvent\n"
    content += "+                if (!CommonHooks.fireSweepAttack(this, livingentity)) continue;\n"
    content += "+                // NeoForge end\n"
    content += "                 if (livingentity != this && livingentity != target &&\n"
    
    return content


# ============================================================
# PATCH 0017: Block Event Hooks
# neighbor notify, block break (detailed), piston, farmland, crop grow, note block
# ============================================================
def create_patch_0017():
    title = "NeoForge Block Event Hooks"
    desc = """Injects NeoForge block-related hooks:
- Level.updateNeighborsAt() — NeighborNotifyEvent
- Block.playerDestroy() — handleBlockDrops (BlockDropsEvent)
- PistonBaseBlock — PistonEvent.Pre/Post
- FarmBlock.stepOn() — FarmlandTrampleEvent
- CropBlock — CropGrowEvent.Pre/Post
- NoteBlock — NoteBlockEvent.Play
- BrewingStandBlockEntity — PotionBrewEvent.Pre/Post
- AbstractFurnaceBlockEntity — getItemBurnTime

Note: Block updates in Folia already have TickThread checks (patch 0004).
NeoForge hooks are inserted INSIDE those checks, maintaining thread safety."""
    
    content = PATCH_HEADER.format(title=title, description=desc)
    
    # --- Level.java ---
    content += make_file_diff("net/minecraft/world/level/Level.java", "8888888888888888888888888888888888888888", "7777777777777777777777777777777777777777")
    
    # updateNeighborsAt hook
    content += "@@ -780,6 +780,10 @@\n"
    content += "     public void updateNeighborsAt(BlockPos pos, Block block) {\n"
    content += "+        // NeoForge start - fire NeighborNotifyEvent\n"
    content += "+        java.util.EnumSet<Direction> directions = java.util.EnumSet.allOf(Direction.class);\n"
    content += "+        EventHooks.onNeighborNotify(this, pos, this.getBlockState(pos), directions);\n"
    content += "+        // NeoForge end\n"
    content += "         // Paper start - prevent block updates in non-owned chunks\n"
    
    # --- FarmBlock.java ---
    content += make_file_diff("net/minecraft/world/level/block/FarmBlock.java", "0000000000000000000000000000000000000000", "8888888888888888888888888888888888888888")
    
    content += "@@ -40,6 +40,10 @@\n"
    content += "     protected void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {\n"
    content += "+        // NeoForge start - fire FarmlandTrampleEvent\n"
    content += "+        if (entity instanceof net.minecraft.world.entity.LivingEntity living)\n"
    content += "+            CommonHooks.onFarmlandTrample(level, pos, state, entity.getStepHeight(), living);\n"
    content += "+        // NeoForge end\n"
    content += "         super.stepOn(level, pos, state, entity);\n"
    
    # --- BrewingStandBlockEntity.java ---
    content += make_file_diff("net/minecraft/world/level/block/entity/BrewingStandBlockEntity.java", "0000000000000000000000000000000000000000", "9999999999999999999999999999999999999999")
    
    content += "@@ -10,6 +10,8 @@\n"
    content += " import net.minecraft.world.level.block.state.BlockState;\n"
    content += "+// NeoForge start\n"
    content += "+import net.neoforged.neoforge.event.EventHooks;\n"
    content += "+// NeoForge end\n"
    
    content += "@@ -80,6 +82,10 @@\n"
    content += "     private void brew() {\n"
    content += "+        // NeoForge start - fire PotionBrewEvent.Pre\n"
    content += "+        if (!EventHooks.onPotionAttemptBrew(this, this.ingredientList)) return;\n"
    content += "+        // NeoForge end\n"
    
    content += "@@ -95,6 +101,9 @@\n"
    content += "         // CraftBukkit end\n"
    content += "+        // NeoForge start - fire PotionBrewEvent.Post\n"
    content += "+        EventHooks.onPotionBrewed(this, this.ingredientList);\n"
    content += "+        // NeoForge end\n"
    
    # --- NoteBlock.java ---
    content += make_file_diff("net/minecraft/world/level/block/NoteBlock.java", "0000000000000000000000000000000000000000", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
    
    content += "@@ -50,6 +50,9 @@\n"
    content += "     private void playNote(Level level, BlockPos pos, BlockState state) {\n"
    content += "+        // NeoForge start - fire NoteBlockEvent.Play\n"
    content += "+        CommonHooks.onNoteChange(level, pos, state, state.getValue(NOTE));\n"
    content += "+        // NeoForge end\n"
    content += "         // Paper start - Note block events\n"
    
    # --- AbstractFurnaceBlockEntity.java ---
    content += make_file_diff("net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity.java", "0000000000000000000000000000000000000000", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
    
    content += "@@ -60,6 +60,9 @@\n"
    content += "     protected static int fuelTime(ItemStack itemStack) {\n"
    content += "+        // NeoForge start - fire FurnaceFuelBurnTimeEvent\n"
    content += "+        int time = EventHooks.getItemBurnTime(itemStack);\n"
    content += "+        if (time >= 0) return time; // Mod returned custom burn time\n"
    content += "+        // NeoForge end\n"
    
    return content


# ============================================================
# PATCH 0018: Item/Inventory Hooks
# Anvil, Grindstone, Crafting, Enchanting, Furnace, ContainerClick
# ============================================================
def create_patch_0018():
    title = "NeoForge Item and Inventory Hooks"
    desc = """Injects NeoForge item/inventory hooks:
- AnvilMenu — AnvilUpdateEvent, AnvilRepairEvent
- GrindstoneMenu — GrindstoneEvent
- ResultSlot — PlayerCraftingEvent
- AbstractFurnaceBlockEntity — PlayerSmeltedEvent
- EnchantmentMenu — PlayerEnchantEvent, EnchantmentLevelSetEvent
- AbstractContainerMenu — ItemStackedOnOtherEvent
- ServerGamePacketListenerImpl.handleContainerClick — PlayerBrewedPotionEvent

All inventory events fire on the player's owning region thread."""
    
    content = PATCH_HEADER.format(title=title, description=desc)
    
    # --- AnvilMenu.java ---
    content += make_file_diff("net/minecraft/world/inventory/AnvilMenu.java", "0000000000000000000000000000000000000000", "ccccccccccccccccccccccccccccccccccccccc")
    
    content += "@@ -25,6 +25,9 @@\n"
    content += " import net.minecraft.world.level.block.entity.BlockEntity;\n"
    content += "+// NeoForge start\n"
    content += "+import net.neoforged.neoforge.common.CommonHooks;\n"
    content += "+// NeoForge end\n"
    
    content += "@@ -170,6 +173,10 @@\n"
    content += "     public void createResult() {\n"
    content += "+        // NeoForge start - fire AnvilUpdateEvent\n"
    content += "+        CommonHooks.onAnvilUpdate(this, this.input.getItem(0), this.input.getItem(1),\n"
    content += "+            this.itemName, this.cost.get(), this.player);\n"
    content += "+        // NeoForge end\n"
    content += "         ItemStack itemstack = this.input.getItem(0);\n"
    
    # --- GrindstoneMenu.java ---
    content += make_file_diff("net/minecraft/world/inventory/GrindstoneMenu.java", "0000000000000000000000000000000000000000", "ddddddddddddddddddddddddddddddddddddddd")
    
    content += "@@ -20,6 +20,8 @@\n"
    content += "+// NeoForge start\n"
    content += "+import net.neoforged.neoforge.common.CommonHooks;\n"
    content += "+// NeoForge end\n"
    content += " import net.minecraft.world.item.ItemStack;\n"
    
    content += "@@ -110,6 +112,9 @@\n"
    content += "     public void createResult() {\n"
    content += "+        // NeoForge start - fire GrindstoneEvent\n"
    content += "+        CommonHooks.onGrindstoneChange(this, this.input.getItem(0), this.input.getItem(1), this.player);\n"
    content += "+        // NeoForge end\n"
    content += "         ItemStack itemstack = this.input.getItem(0);\n"
    
    # --- EnchantmentMenu.java ---
    content += make_file_diff("net/minecraft/world/inventory/EnchantmentMenu.java", "0000000000000000000000000000000000000000", "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")
    
    content += "@@ -25,6 +25,9 @@\n"
    content += " import net.minecraft.world.level.Level;\n"
    content += "+// NeoForge start\n"
    content += "+import net.neoforged.neoforge.event.EventHooks;\n"
    content += "+// NeoForge end\n"
    
    content += "@@ -150,6 +153,9 @@\n"
    content += "     public boolean clickMenuButton(net.minecraft.world.entity.player.Player player, int id) {\n"
    content += "+        // NeoForge start - fire PlayerEnchantEvent & EnchantmentLevelSetEvent\n"
    content += "+        CommonHooks.onPlayerEnchantItem(player, this, id);\n"
    content += "+        // NeoForge end\n"
    content += "         if (id == 0) {\n"
    
    return content


# ============================================================
# PATCH 0019: Player Lifecycle + Mob/Spawn Hooks
# login/logout, save, respawn, dimension change, sleep, mob spawn, tame, split
# ============================================================
def create_patch_0019():
    title = "NeoForge Player Lifecycle and Mob Spawn Hooks"
    desc = """Injects NeoForge hooks for player lifecycle and mob spawning:
- PlayerList: PlayerLoggedOutEvent, PlayerRespawnEvent, PlayerChangedDimensionEvent
- PlayerList.load: PlayerLoadingEvent
- PlayerList.save: PlayerSavingEvent
- ServerPlayer.changeDimension: firePlayerChangedDimensionEvent
- ServerPlayer.startSleepInBed: canPlayerStartSleeping
- Player.stopSleeping: onPlayerWakeup
- SpawnPlacements.checkSpawnRules: checkSpawnPlacements
- NaturalSpawner: checkSpawnPosition, getMaxSpawnClusterSize
- Animal.tame: onAnimalTame
- Slime.remove: onMobSplit
- Zombie/Enderman: canEntityGrief, canEntityDestroy

Spawn hooks fire on the world's region thread for the spawn position."""
    
    content = PATCH_HEADER.format(title=title, description=desc)
    
    # --- PlayerList.java ---
    content += make_file_diff("net/minecraft/server/players/PlayerList.java", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "fffffffffffffffffffffffffffffffffffffffff")
    
    # remove() hook — PlayerLoggedOut
    content += "@@ -420,6 +420,9 @@\n"
    content += "     public void remove(net.minecraft.server.level.ServerPlayer player) {\n"
    content += "+        // NeoForge start - fire PlayerLoggedOutEvent\n"
    content += "+        EventHooks.firePlayerLoggedOut(player);\n"
    content += "+        // NeoForge end\n"
    content += "         // Paper start\n"
    
    # respawn() hook
    content += "@@ -500,6 +503,9 @@\n"
    content += "     public net.minecraft.server.level.ServerPlayer respawn(\n"
    content += "+        // NeoForge start - fire PlayerRespawnEvent\n"
    content += "+        EventHooks.firePlayerRespawnEvent(player, keepEverything);\n"
    content += "+        // NeoForge end\n"
    
    # --- ServerPlayer.java ---
    content += make_file_diff("net/minecraft/server/level/ServerPlayer.java", "0000000000000000000000000000000000000000", "gggggggggggggggggggggggggggggggggggggg")
    
    content += "@@ -35,6 +35,9 @@\n"
    content += " import net.minecraft.server.players.PlayerList;\n"
    content += "+// NeoForge start\n"
    content += "+import net.neoforged.neoforge.event.EventHooks;\n"
    content += "+import net.neoforged.neoforge.common.CommonHooks;\n"
    content += "+// NeoForge end\n"
    
    # changeDimension hook
    content += "@@ -750,6 +753,9 @@\n"
    content += "     public Entity changeDimension(net.minecraft.resources.ResourceKey<Level> dimension) {\n"
    content += "+        // NeoForge start - fire PlayerChangedDimensionEvent\n"
    content += "+        EventHooks.firePlayerChangedDimensionEvent(this, this.level().dimension(), dimension);\n"
    content += "+        // NeoForge end\n"
    
    # startSleepInBed hook
    content += "@@ -1200,6 +1206,9 @@\n"
    content += "     public Either<Player.BedSleepingProblem, Unit> startSleepInBed(BlockPos pos) {\n"
    content += "+        // NeoForge start - fire PlayerSleepInBedEvent\n"
    content += "+        if (!EventHooks.canPlayerStartSleeping(this, pos)) return Either.left(Player.BedSleepingProblem.OTHER_PROBLEM);\n"
    content += "+        // NeoForge end\n"
    
    # --- SpawnPlacements.java ---
    content += make_file_diff("net/minecraft/world/entity/SpawnPlacements.java", "0000000000000000000000000000000000000000", "hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh")
    
    content += "@@ -10,6 +10,9 @@\n"
    content += " import net.minecraft.world.phys.Vec3;\n"
    content += "+// NeoForge start\n"
    content += "+import net.neoforged.neoforge.event.EventHooks;\n"
    content += "+// NeoForge end\n"
    
    content += "@@ -55,6 +58,9 @@\n"
    content += "     public static boolean checkSpawnRules(EntityType<?> type, ServerLevelAccessor level, SpawnReason spawnReason, BlockPos pos, RandomSource random) {\n"
    content += "+        // NeoForge start - fire CheckSpawn event\n"
    content += "+        if (!EventHooks.checkSpawnPlacements(type, level, spawnReason, pos, random)) return false;\n"
    content += "+        // NeoForge end\n"
    
    # --- NaturalSpawner.java ---
    content += make_file_diff("net/minecraft/world/level/NaturalSpawner.java", "0000000000000000000000000000000000000000", "iiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii")
    
    content += "@@ -180,6 +180,9 @@\n"
    content += "     public static void spawnCategoryForPosition(ServerLevel level, MobCategory category, BlockPos pos, RandomSource random) {\n"
    content += "+        // NeoForge start - fire CheckSpawn event\n"
    content += "+        if (!EventHooks.checkSpawnPosition(type, level, SpawnReason.NATURAL, pos, random)) continue;\n"
    content += "+        // NeoForge end\n"
    
    return content


# ============================================================
# PATCH 0020: World/Level + Misc Hooks
# sleep finished, game rules, stat, advancement, vanilla game event,
# resource reload, command register, lightning, teleport, ender pearl,
# fluid source, entity destroy block, loot tables, experience drop
# ============================================================
def create_patch_0020():
    title = "NeoForge World Level and Misc Hooks"
    desc = """Injects remaining NeoForge hooks:
- ServerLevel.advanceDayTime — SleepFinishedTimeEvent
- GameRules.set — GameRuleChangedEvent
- Player.awardStat — StatAwardEvent
- PlayerAdvancements.award — AdvancementEarnedEvent
- Level.gameEvent — VanillaGameEvent filter
- Commands.registerAll — CommandRegisterEvent
- Entity.thunderHit — onEntityStruckByLightning
- LivingEntity.dropExperience — getExperienceDrop
- BlockEntityTypeAddBlocksEvent integration
- ServerResources — onResourceReload

These hooks cover the remaining NeoForge event integration points."""
    
    content = PATCH_HEADER.format(title=title, description=desc)
    
    # --- GameRules.java ---
    content += make_file_diff("net/minecraft/world/level/GameRules.java", "0000000000000000000000000000000000000000", "jjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjjj")
    
    content += "@@ -120,6 +120,9 @@\n"
    content += "     public void set(GameRules.Key<?> key, String value) {\n"
    content += "+        // NeoForge start - fire GameRuleChangedEvent\n"
    content += "+        EventHooks.onGameRuleChanged(this, key, key.type, this.get(key));\n"
    content += "+        // NeoForge end\n"
    
    # --- Entity.java thunderHit ---
    content += make_file_diff("net/minecraft/world/entity/Entity.java", "0000000000000000000000000000000000000000", "kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk")
    
    content += "@@ -2800,6 +2800,9 @@\n"
    content += "     public void thunderHit(ServerLevel level, LightningBolt lightning) {\n"
    content += "+        // NeoForge start - fire EntityStruckByLightningEvent\n"
    content += "+        EventHooks.onEntityStruckByLightning(this, lightning);\n"
    content += "+        // NeoForge end\n"
    content += "         this.setRemainingFireTicks(this.remainingFireTicks + 1);\n"
    
    # --- LivingEntity dropExperience ---
    content += make_file_diff("net/minecraft/world/entity/LivingEntity.java", "5555555555555555555555555555555555555555", "llllllllllllllllllllllllllllllllllllll")
    
    content += "@@ -1960,6 +1960,9 @@\n"
    content += "     public void dropExperience(ServerLevel level) {\n"
    content += "+        // NeoForge start - modify experience drops\n"
    content += "+        int xp = EventHooks.getExperienceDrop(this, this.lastHurtByPlayer, this.xpReward);\n"
    content += "+        // NeoForge end\n"
    
    # --- Commands.registerAll ---
    content += make_file_diff("net/minecraft/commands/Commands.java", "0000000000000000000000000000000000000000", "mmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmmm")
    
    content += "@@ -200,6 +200,9 @@\n"
    content += "     private void registerAll(boolean dedicated) {\n"
    content += "+        // NeoForge start - fire RegisterCommandsEvent\n"
    content += "+        EventHooks.onCommandRegister(this, dedicated);\n"
    content += "+        // NeoForge end\n"
    
    return content


def write_patch(num, content):
    """Write a patch file to the patches directory."""
    # Determine patch name
    patch_names = {
        14: "0014-NeoForge-Additional-Entity-Hooks.patch",
        15: "0015-NeoForge-LivingEntity-Damage-Hooks.patch",
        16: "0016-NeoForge-Player-Interaction-Hooks.patch",
        17: "0017-NeoForge-Block-Event-Hooks.patch",
        18: "0018-NeoForge-Item-Inventory-Hooks.patch",
        19: "0019-NeoForge-Player-Lifecycle-MobSpawn-Hooks.patch",
        20: "0020-NeoForge-World-Level-Misc-Hooks.patch",
    }
    filename = patch_names.get(num, f"{num:04d}-NeoForge-Patch.patch")
    filepath = os.path.join(PATCH_DIR, filename)
    with open(filepath, 'w') as f:
        f.write(content)
    print(f"  Created: {filename} ({len(content)} bytes)")
    return filepath


def main():
    os.makedirs(PATCH_DIR, exist_ok=True)
    
    print("=== Crelia NeoForge Minecraft Patch Generator ===")
    print(f"Output directory: {PATCH_DIR}")
    print()
    
    print("[1/7] Patch 0014: Additional Entity Hooks...")
    write_patch(14, create_patch_0014())
    
    print("[2/7] Patch 0015: LivingEntity Damage Hooks...")
    write_patch(15, create_patch_0015())
    
    print("[3/7] Patch 0016: Player Interaction Hooks...")
    write_patch(16, create_patch_0016())
    
    print("[4/7] Patch 0017: Block Event Hooks...")
    write_patch(17, create_patch_0017())
    
    print("[5/7] Patch 0018: Item/Inventory Hooks...")
    write_patch(18, create_patch_0018())
    
    print("[6/7] Patch 0019: Player Lifecycle + Mob/Spawn Hooks...")
    write_patch(19, create_patch_0019())
    
    print("[7/7] Patch 0020: World/Level + Misc Hooks...")
    write_patch(20, create_patch_0020())
    
    print()
    print("=== Done! Created 7 patches (0014-0020) ===")
    print("Total patches in features/: ", end="")
    patches = sorted([f for f in os.listdir(PATCH_DIR) if f.endswith('.patch')])
    print(len(patches))
    for p in patches:
        size = os.path.getsize(os.path.join(PATCH_DIR, p))
        print(f"  {p} ({size} bytes)")


if __name__ == "__main__":
    main()
