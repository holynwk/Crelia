# Анализ отсутствующих minecraft-patches для Crelia (NeoForge + Folia)

## 1. Обзор проекта

Crelia — это гибридный сервер Minecraft, объединяющий **Folia** (региональная многопоточность PaperMC) и **NeoForge** (мод-загрузчик). Проект основан на Paperweight patcher, который применяет патчи из Paper, Folia и NeoForge к ванильному коду Minecraft.

**Ключевая проблема:** В репозитории git **полностью отсутствуют** minecraft-patches для NeoForge. Директория `folia-server/src/minecraft` исключена из `.gitignore`:

```
/folia-server/src/minecraft
```

Это означает, что все изменения в ванильных MC-классах для поддержки NeoForge (вставки вызовов `EventHooks`, `CommonHooks`, `ServerLifecycleHooks`, `LanguageHook`) не сохранены в систему контроля версий.

---

## 2. Существующие патчи (Folia — полные)

### 2.1. minecraft-patches (folia-server/minecraft-patches/features/)

Эти патчи применяются **после** Paper-патчей к декомпилированному исходному коду Minecraft:

| # | Имя патча | Назначение |
|---|-----------|------------|
| 0001 | **Region-Threading-Base** | Основной патч региональной многопоточности. Модифицирует десятки классов: `MinecraftServer`, `ServerLevel`, `ServerChunkCache`, `Level`, `ServerPlayer`, `ChunkHolder`, `Entity`, `LivingEntity` и многие другие. Заменяет единый тик-цикл на региональный, добавляет `TickThread`, `RegionizedWorldData`, `TickRegionScheduler`, `RegionizedServer`. |
| 0002 | **Max-pending-logins** | Ограничивает количество одновременных логинов для защиты от флуда при старте сервера. Модифицирует `ServerLoginPacketListenerImpl` и `PlayerList`. |
| 0003 | **Add-chunk-system-throughput-counters-to-tps** | Добавляет счётчики пропускной способности системы чанков (загрузка/генерация в секунду) в вывод `/tps`. Модифицирует `ChunkFullTask` и `CommandServerHealth`. |
| 0004 | **Prevent-block-updates-in-non-loaded-or-non-owned-chunks** | Предотвращает обновления блоков в незагруженных или чужих регионах для стабильности многопоточности. Затрагивает `Level`, `DetectorRailBlock`, `PoweredRailBlock`, `RedStoneWireBlock`, `TripWireBlock`, `TripWireHookBlock`, `CollectingNeighborUpdater`. |
| 0005 | **Block-reading-in-world-tile-entities-on-worldgen-threads** | Блокирует чтение tile-entities из мира на потоках генерации мира. Модифицирует `ImposterProtoChunk`. |
| 0006 | **Sync-vehicle-position-to-player-position-on-player-data-load** | Синхронизирует позицию транспортного средства с позицией игрока при загрузке данных игрока. Модифицирует `ServerPlayer`. |
| 0007 | **Region-profiler** | Добавляет систему профилирования регионов (/profiler). Очень большой патч, добавляющий `RegionProfiler`, профилирование регионов, таймеры, отчёты. |
| 0008 | **Add-watchdog-thread** | Добавляет `FoliaWatchdogThread` для отслеживания зависших регионов. Выводит stacktrace региона, не отвечающего более 5 секунд. |

### 2.2. paper-patches (folia-server/paper-patches/features/)

Патчи, модифицирующие Paper-специфичный код (в `src/main/java/`):

| # | Имя патча | Назначение |
|---|-----------|------------|
| 0001 | **Region-Threading-Base** | Расширенная версия 0001 из minecraft-patches, затрагивающая Paper-классы: `TickThread`, `PaperHooks`, `PaperConfig`, `ChunkSystem` и др. |
| 0002 | **Update-Logo** | Замена логотипа Paper на логотип Folia (бинарный патч). |
| 0003 | **Build-changes** | Замена.brand на "Folia", обновление ссылок на PaperMC.io, замена идентификаторов bStats. Модифицирует `Metrics`, `PaperVersionFetcher`, `ServerBuildInfoImpl`. |
| 0004 | **Fix-tests-by-removing-them** | Отключает тесты, несовместимые с Folia. |
| 0005 | **Region-profiler** | Профилирование регионов для Paper-классов. |
| 0006 | **Add-watchdog-thread** | Делает `WatchdogThread.dumpThread()` публичным для использования из Folia watchdog. |
| 0007 | **Add-TPS-From-Region** | Добавляет API `getRegionTPS()` в `CraftServer` для получения TPS конкретного региона. |

### 2.3. paper-patches (folia-api/paper-patches/features/)

Патчи, модифицирующие Paper API:

| # | Имя патча | Назначение |
|---|-----------|------------|
| 0001 | **Force-disable-timings** | Принудительно отключает Timings (несовместимы с региональной многопоточностью). |
| 0002 | **Region-scheduler-API** | Добавляет API планировщиков: `RegionScheduler`, `AsyncScheduler`, `EntityScheduler`, `GlobalRegionScheduler`. Модифицирует `BukkitScheduler` (deprecated), `SimplePluginManager`. |
| 0003 | **Require-plugins-to-be-explicitly-marked-as-Folia-supported** | Требует `folia-supported: true` в plugin.yml, иначе плагин не загружается. |
| 0004 | **Add-TPS-From-Region** | Добавляет методы `getRegionTPS()` в `Bukkit` и `Server`. |

---

## 3. Существующие NeoForge coremods (работают на этапе трансформации байткода)

NeoForge использует **coremods** (SPI-провайдеры трансформации классов) для модификации байткода вместо патчей исходного кода. Это работает на этапе загрузки классов, а не на этапе patcher.

### 3.1. NeoForgeCoreMod.java

Регистрирует три процессора:

#### А) ReplaceFieldWithGetterAccess — замена прямого доступа к полям на геттеры

| Класс | Поле | Заменяется на геттер |
|-------|------|---------------------|
| `net.minecraft.world.level.biome.Biome` | `climateSettings` | `getModifiedClimateSettings()` |
| `net.minecraft.world.level.biome.Biome` | `specialEffects` | `getModifiedSpecialEffects()` |
| `net.minecraft.world.level.levelgen.structure.Structure` | `settings` | `getModifiedStructureSettings()` |
| `net.minecraft.world.level.block.FlowerPotBlock` | `potted` | `getPotted()` |

**Важно:** Эти геттеры (`getModifiedClimateSettings`, `getModifiedSpecialEffects`, `getModifiedStructureSettings`, `getPotted`) **должны быть определены в этих классах через патчи**. Без minecraft-patches, добавляющих эти методы, coremod потерпит неудачу при трансформации — он не найдёт целевой метод.

#### Б) MethodRedirector — перенаправление вызовов finalizeSpawn

Перенаправляет **виртуальные вызовы** `Mob.finalizeSpawn()` на статический вызов `EventHooks.finalizeMobSpawn()` в **27 классах** из файла `finalize_spawn_targets.json`:

```
net.minecraft.gametest.framework.GameTestHelper
net.minecraft.server.commands.RaidCommand
net.minecraft.server.commands.SummonCommand
net.minecraft.world.entity.EntityType
net.minecraft.world.entity.ai.village.VillageSiege
net.minecraft.world.entity.animal.equine.SkeletonTrapGoal
net.minecraft.world.entity.animal.equine.ZombieHorse
net.minecraft.world.entity.animal.frog.Tadpole
net.minecraft.world.entity.monster.Strider
net.minecraft.world.entity.monster.illager.Evoker$EvokerSummonSpellGoal
net.minecraft.world.entity.monster.spider.Spider
net.minecraft.world.entity.monster.zombie.Drowned
net.minecraft.world.entity.monster.zombie.Husk
net.minecraft.world.entity.monster.zombie.Zombie
net.minecraft.world.entity.monster.zombie.ZombieVillager
net.minecraft.world.entity.npc.CatSpawner
net.minecraft.world.entity.npc.villager.Villager
net.minecraft.world.entity.raid.Raid
net.minecraft.world.level.NaturalSpawner
net.minecraft.world.level.levelgen.PatrolSpawner
net.minecraft.world.level.levelgen.PhantomSpawner
net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces$OceanMonumentPiece
net.minecraft.world.level.levelgen.structure.structures.OceanRuinPieces$OceanRuinPiece
net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece
net.minecraft.world.level.levelgen.structure.structures.WoodlandMansionPieces$WoodlandMansionPiece
net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
```

### 3.2. ReplaceFieldComparisonWithInstanceOf

Определён в коде, но **не зарегистрирован** в NeoForgeCoreMod. Это запасной трансформатор для замены сравнений полей на `instanceof` проверки. В текущей конфигурации не используется.

---

## 4. Отсутствующие minecraft-patches для NeoForge

### 4.1. Проблема

Coremods выполняют **два вида** трансформаций:
1. **Автономные** (заменяют GETFIELD на INVOKEVIRTUAL, перенаправляют вызовы) — эти работают без патчей.
2. **Требующие предварительных модификаций** — coremod `ReplaceFieldWithGetterAccess` ищет **уже существующий** метод-геттер в классе. Без патчей, добавляющих методы `getModifiedClimateSettings()`, `getModifiedSpecialEffects()`, `getModifiedStructureSettings()`, `getPotted()`, coremod **упадёт с ошибкой**.

### 4.2. Необходимые патчи для поддержки coremods

#### Класс: `net.minecraft.world.level.biome.Biome`

**Нужно добавить два метода:**
```java
// Патч: замена прямого доступа к полю climateSettings на модифицируемый геттер
public ClimateSettings getModifiedClimateSettings() {
    return this.climateSettings;
}

// Патч: замена прямого доступа к полю specialEffects на модифицируемый геттер
public BiomeSpecialEffects getModifiedSpecialEffects() {
    return this.specialEffects;
}
```

После этого coremod `ReplaceFieldWithGetterAccess` заменит все `GETFIELD Biome.climateSettings` на `INVOKEVIRTUAL Biome.getModifiedClimateSettings()`, позволяя модам переопределять эти значения через `ModifiableBiomeInfo`.

#### Класс: `net.minecraft.world.level.levelgen.structure.Structure`

**Нужно добавить один метод:**
```java
// Патч: замена прямого доступа к полю settings на модифицируемый геттер
public StructureSettings getModifiedStructureSettings() {
    return this.settings;
}
```

Аналогично, позволяет модам модифицировать настройки структур.

#### Класс: `net.minecraft.world.level.block.FlowerPotBlock`

**Нужно добавить один метод:**
```java
// Патч: замена прямого доступа к полю potted на модифицируемый геттер
public Holder<Block> getPotted() {
    return this.potted;
}
```

Позволяет модам добавлять自定义 горшечные растения.

---

## 5. Отсутствующие minecraft-patches для внедрения EventHooks

Метод `EventHooks.finalizeMobSpawn()` обрабатывается coremod'ом автоматически (перенаправление вызовов). Однако большинство других хуков NeoForge в `EventHooks.java` и `CommonHooks.java` требуют **вставки вызовов в ванильный код** через патчи.

### 5.1. Критические хуки EventHooks, требующие патчей

| Метод EventHooks | Где вызывается в ванильном коде | Встраиваемые классы |
|-----------------|-------------------------------|---------------------|
| `fireServerTickPre/Post` | `MinecraftServer.tickServer()` | `MinecraftServer` |
| `fireLevelTickPre/Post` | `MinecraftServer.tickChildren()`, `ClientLevel.tick()` | `MinecraftServer`, `ClientLevel` |
| `firePlayerTickPre/Post` | `Player.tick()` | `Player` |
| `fireEntityTickPre/Post` | `LivingEntity.tick()` | `LivingEntity` |
| `onMultiBlockPlace` | `Block.setPlacedBy()` для мультиблоков | `Block` |
| `onBlockPlace` | `Block.setPlacedBy()` для одиночных блоков | `Block` |
| `onNeighborNotify` | `Level.updateNeighborsAt()` | `Level` |
| `doPlayerHarvestCheck` | `Player.hasCorrectToolForDrops()` | `Player` |
| `getBreakSpeed` | `Player.getDigSpeed()` | `Player` |
| `onPlayerDestroyItem` | `Player.attack()`, destruction logic | `Player` |
| `checkSpawnPlacements` | `SpawnPlacements.checkSpawnRules()` | `SpawnPlacements` |
| `checkSpawnPosition` | `NaturalSpawner.spawnCategoryForPosition()` | `NaturalSpawner` |
| `checkSpawnPositionSpawner` | `BaseSpawner.spawnInWorld()` | `BaseSpawner` |
| `getItemBurnTime` | `AbstractFurnaceBlockEntity.burn()` | `AbstractFurnaceBlockEntity` |
| `getExperienceDrop` | `LivingEntity.dropExperience()` | `LivingEntity` |
| `getMaxSpawnClusterSize` | `NaturalSpawner` | `NaturalSpawner` |
| `getPlayerDisplayName` | `Player.getDisplayName()` | `Player` |
| `getPlayerTabListDisplayName` | `ServerPlayer.getTabListDisplayName()` | `ServerPlayer` |
| `onItemTooltip` | `ItemStack.getTooltipLines()` | `ItemStack` |
| `onEntityStruckByLightning` | `Entity.thunderHit()` | `Entity` |
| `onItemUseStart/Tick/Stop/Finish` | `LivingEntity.useItem()` | `LivingEntity` |
| `onStartEntityTracking/onStopEntityTracking` | `ServerEntity.sendPairingData()` | `ServerEntity` |
| `firePlayerLoadingEvent` | `PlayerList.load()` | `PlayerList` |
| `firePlayerSavingEvent` | `PlayerList.save()` | `PlayerList` |
| `onToolUse` | `BlockState.useOnItemWithResult()` / `Block.useWithoutItem()` | `BlockState`, `Block` |
| `onPlaySoundAtEntity` | `Level.playSound()` | `Level` |
| `onPlaySoundAtPosition` | `Level.playSeededSound()` | `Level` |
| `onItemExpire` | `ItemEntity.tick()` | `ItemEntity` |
| `fireItemPickupPre/Post` | `ItemEntity.playerTouch()` | `ItemEntity` |
| `canMountEntity` | `Entity.startRiding()` / `Entity.stopRiding()` | `Entity` |
| `onAnimalTame` | `Animal.tame()` | `Animal` |
| `canPlayerStartSleeping` | `ServerPlayer.startSleepInBed()` | `ServerPlayer` |
| `onPlayerWakeup` | `Player.stopSleeping()` | `Player` |
| `onPlayerFall` | `Player.checkFallDamage()` | `Player` |
| `onExplosionStart` | `Level.explode()` | `Level` |
| `onExplosionDetonate` | `Level.explode()` | `Level` |
| `getExplosionKnockback` | `Level.explode()` | `Level` |
| `onLivingHeal` | `LivingEntity.heal()` | `LivingEntity` |
| `onPotionAttemptBrew/onPotionBrewed` | `BrewingStandBlockEntity.brew()` | `BrewingStandBlockEntity` |
| `onPlayerBrewedPotion` | `ServerGamePacketListenerImpl.handleContainerClick()` | `ServerGamePacketListenerImpl` |
| `onArrowNock/Loose` | `BowItem.use()` / projectile logic | `BowItem` и др. |
| `onProjectileImpact` | `Projectile.onHit()` | `Projectile` |
| `loadLootTable` | `LootDataType.deserialize()` | `LootDataType` |
| `canCreateFluidSource` | `LiquidBlockContainer.placeLiquid()` | `LiquidBlockContainer` |
| `onTrySpawnPortal` | `PortalForcer.createPortal()` | `PortalForcer` |
| `onEnchantmentLevelSet` | `EnchantmentMenu.clickMenuButton()` | `EnchantmentMenu` |
| `onEntityDestroyBlock` | `LivingEntity.destroyBlock()` | `LivingEntity` |
| `canEntityGrief` |多处 (зомби разрушают двери и т.д.) | `Zombie`, `Enderman` и др. |
| `fireBlockGrowFeature` | `BonemealableBlock.performBonemeal()` | `BonemealableBlock` |
| `alterGround` | `AlterGroundDecorator.place()` | `AlterGroundDecorator` |
| `fireChunkTicketLevelUpdated` | `ChunkHolder.updateTicketLevel()` | `ChunkHolder` |
| `fireChunkWatch/Sent/UnWatch` | `ServerChunkCache` | `ServerChunkCache` |
| `onPistonMovePre/Post` | `PistonBaseBlock.move()` | `PistonBaseBlock` |
| `onSleepFinished` | `ServerLevel.advanceDayTime()` | `ServerLevel` |
| `onResourceReload` | `ServerResources.loadResources()` | `ServerResources` |
| `onCommandRegister` | `Commands.registerAll()` | `Commands` |
| `getEntitySizeForge` | `Entity.getDimensions()` | `Entity` |
| `canLivingConvert/onLivingConvert` | `ZombieVillager.convert()`, `Pig.convert()`, etc. | `ZombieVillager`, `Pig`, `MushroomCow` |
| `onEntityTeleportCommand` | `TeleportCommand.teleport()` | `TeleportCommand` |
| `onEnderTeleport` | `EnderMan.teleport()` | `EnderMan` |
| `onEnderPearlLand` | `ThrownEnderpearl.onHit()` | `ThrownEnderpearl` |
| `onPermissionChanged` | `PlayerList` | `PlayerList` |
| `firePlayerChangedDimensionEvent` | `ServerPlayer.changeDimension()` | `ServerPlayer` |
| `firePlayerLoggedIn/LoggedOut` | `PlayerList` | `PlayerList` |
| `firePlayerRespawnPositionEvent` | `PlayerList.respawn()` | `PlayerList` |
| `firePlayerRespawnEvent` | `PlayerList.respawn()` | `PlayerList` |
| `firePlayerCraftingEvent` | `ResultSlot.onTake()` | `ResultSlot` |
| `firePlayerSmeltedEvent` | `AbstractFurnaceBlockEntity` | `AbstractFurnaceBlockEntity` |
| `getCustomSpawners` | `ServerLevel.tickCustomSpawners()` | `ServerLevel` |
| `onGameRuleChanged` | `GameRules.set()` | `GameRules` |
| `onStatAward` | `Player.awardStat()` | `StatsCounter`, `PlayerStat` |
| `onAdvancementEarnedEvent` | `PlayerAdvancements.award()` | `PlayerAdvancements` |
| `onAdvancementProgressedEvent` | `PlayerAdvancements` | `PlayerAdvancements` |
| `onEffectRemoved` | `MobEffectInstance.onRemoveFromEntity()` | `LivingEntity` |
| `getEnchantmentLevelSpecific` | `Item.getEnchantmentLevel()` | `Item` |
| `getAllEnchantmentLevels` | `ItemStack.getEnchantments()` | `ItemStack` |
| `onCreativeModeTabBuildContents` | `CreativeModeTab.buildContents()` | `CreativeModeTab` |
| `onMobSplit` | `Slime.remove()` | `Slime` |
| `fireBonemealEvent` | `BonemealableBlock` | Various blocks |
| `onLivingDamagePre/Post` | (вызывается из CommonHooks) | см. ниже |

### 5.2. Критические хуки CommonHooks, требующие патчей

| Метод CommonHooks | Где вызывается | Назначение |
|-------------------|---------------|------------|
| `canContinueUsing` | `LivingEntity.shouldTriggerItemUseAnimation()` | Позволяет модам прерывать использование предмета |
| `onItemStackedOn` | `AbstractContainerMenu.doClick()` | Событие stacking предметов |
| `onDifficultyChange` | `MinecraftServer.setDifficulty()` | Уведомление модов о смене сложности |
| `onLivingChangeTarget` | `LivingEntity.getTarget()` / AI цели | Позволяет модам изменять выбор цели |
| `isEntityInvulnerableTo` | `Entity.isInvulnerableTo()` | Позволяет модам делать сущности уязвимыми/неуязвимыми |
| `onEntityIncomingDamage` | `LivingEntity.hurt()` | Основной хук входящего урона (LivingDamageEvent.Pre) |
| `onLivingKnockBack` | `LivingEntity.knockback()` | Контроль отбрасывания |
| `onLivingUseTotem` | `LivingEntity.checkTotemDeathProtection()` | Позволяет модам добавлять другие «тотемы» |
| `onLivingDamagePre/Post` | `LivingEntity.actuallyHurt()` | До/после нанесения урона |
| `onArmorHurt` | `LivingEntity.hurtArmor()` | Хук урона броне |
| `onLivingDeath` | `LivingEntity.die()` | Позволяет модам отменять смерть |
| `onLivingDrops` | `LivingEntity.dropAllDeathLoot()` | Модификация лута при смерти |
| `onLivingFall` | `LivingEntity.causeFallDamage()` | Событие падения с высоты |
| `getEntityVisibilityMultiplier` | `LivingEntity.getVisibilityPercent()` | Видимость сущности |
| `isLivingOnLadder` | `LivingEntity.onClimbable()` | Позволяет модам добавлять «лестницы» |
| `onLivingJump` | `LivingEntity.jumpFromGround()` | Событие прыжка |
| `onPlayerTossEvent` | `Player.drop()` | Событие выбрасывания предмета |
| `onVanillaGameEvent` | `Level.gameEvent()` | Фильтрация vanilla game events |
| `onServerChatSubmittedEvent` | `ServerGamePacketListenerImpl.handleChat()` | Декорация/фильтрация чата |
| `getServerChatSubmittedDecorator` | Инициализация декоратора | Регистрация ChatDecorator |
| `handleBlockDrops` | `Block.playerDestroy()` | BlockDropsEvent — полная замена логики дропа блоков |
| `fireBlockBreak` | `ServerGamePacketListenerImpl.handlePlayerAction()` | Событие разрушения блока игроком |
| `onPlaceItemIntoWorld` | `BlockItem.place()` | Полная замена логики размещения предмета |
| `onPlayerEnchantItem` | `EnchantmentMenu.clickMenuButton()` | Событие зачарования |
| `onAnvilUpdate` | `AnvilMenu.createResult()` | Модификация логики наковальни |
| `fireAnvilCraftPre/Post` | `AnvilMenu` | Pre/Post события крафта на наковальне |
| `onGrindstoneChange` | `GrindstoneMenu.createResult()` | Модификация логики точильного камня |
| `onGrindstoneTake` | `GrindstoneMenu` | Событие взятия результата |
| `setCraftingPlayer/getCraftingPlayer` | `CraftingContainer` | ThreadLocal для текущего крафтера |
| `onPlayerAttackTarget` | `Player.attack()` | Событие атаки |
| `onTravelToDimension` | `Entity.changeDimension()` | Позволяет модам блокировать телепортацию |
| `onInteractEntityAt` | `ServerGamePacketListenerImpl.handleInteract()` | Событие взаимодействия с сущностью |
| `onInteractEntity` | `Player.interact()` | Общее событие взаимодействия |
| `onItemRightClick` | `Player.useItem()` | Событие ПКМ с предметом |
| `onLeftClickBlock` | `ServerGamePacketListenerImpl.handlePlayerAction()` | Событие ЛКМ по блоку |
| `onRightClickBlock` | `ServerGamePacketListenerImpl.handleUseItemOn()` | Событие ПКМ по блоку |
| `onEmptyClick/onEmptyLeftClick` | `ServerGamePacketListenerImpl.handleUseItem()` | Событие клика в воздух |
| `onChangeGameType` | `ServerPlayer.setGameMode()` | Событие смены игрового режима |
| `canCropGrow/fireCropGrowPost` | `CropBlock.grow()` / `StemBlock.grow()` | События роста культур |
| `fireCriticalHit` | `Player.attack()` | Событие критического удара |
| `fireSweepAttack` | `Player.attack()` | Событие sweep-атаки |
| `computeModifiedAttributes` | `ItemStack.getAttributeModifiers()` | Модификация атрибутов предмета |
| `getProjectile` | `ProjectileWeaponItem` | Выбор снаряда для оружия |
| `getDefaultCreatorModId` | `ItemStack.getCreatorModId()` | Определение мода-создателя предмета |
| `onFarmlandTrample` | `FarmBlock.stepOn()` | Событие вытаптывания фермы |
| `onNoteChange` | `NoteBlock.playNote()` | Событие изменения ноты |
| `getSerializer/getSerializerId` | `EntityDataSerializer` | Регистрация кастомных сериализаторов |
| `canEntityDestroy` | `LivingEntity.canDestroyBlock()` | Проверка может ли моб разрушить блок |
| `modifyLoot` | `LootTable.getRandomItems()` | Глобальные модификаторы лута |
| `getModDataPacks/getModDataPacksWithVanilla` | World creation / reload | Добавление дата-паков модов |
| `getAttributesView/modifyAttributes` | `DefaultAttributes.getSupplier()` | Модификация атрибутов сущностей |
| `onEntityEnterSection` | `Entity.moveTo()` / section changes | Хук смены секции сущности |
| `onDamageBlock` | `LivingEntity.hurt()` | LivingShieldBlockEvent |
| `onLivingSwapHandItems` | `LivingEntity.swapHandItems()` | Событие смены предметов в руках |
| `writeAdditionalLevelSaveData` | `OverworldData.save()` | Сохранение NeoForge-данных мира |
| `readAdditionalLevelSaveData` | `OverworldData.load()` | Загрузка NeoForge-данных мира |
| `shouldSuppressEnderManAnger` | `Enderman.isLookingAtMe()` | Позволяет модам подавлять гнев эндермена |
| `canMobEffectBeApplied` | `LivingEntity.addEffect()` | Позволяет модам блокировать эффекты |
| `sendRecipes` | `ServerPlayer` | Отправка рецептов с учётом модов |
| `onCustomClickAction` | `ServerGamePacketListenerImpl` | Кастомные click actions |

---

## 6. Отсутствующие патчи для ServerLifecycleHooks

| Вызов | Где вставляется | Класс |
|-------|---------------|-------|
| `ServerLifecycleHooks.handleServerAboutToStart()` | `MinecraftServer.runServer()` перед запуском | `MinecraftServer` |
| `ServerLifecycleHooks.handleServerStarting()` | `MinecraftServer.runServer()` после создания уровней | `MinecraftServer` |
| `ServerLifecycleHooks.handleServerStarted()` | `MinecraftServer.runServer()` после полного старта | `MinecraftServer` |
| `ServerLifecycleHooks.handleServerStopping()` | `MinecraftServer.halt()` | `MinecraftServer` |
| `ServerLifecycleHooks.handleServerStopped()` | `MinecraftServer.runServer()` в finally | `MinecraftServer` |
| `ServerLifecycleHooks.expectServerStopped()` | Перед входом в игровой цикл | `MinecraftServer` |

---

## 7. Отсутствующие патчи для LanguageHook

| Вызов | Где вставляется | Класс |
|-------|---------------|-------|
| `LanguageHook.loadBuiltinLanguages()` | При запуске клиента, до `Language.load()` | `Language` или `ClientBrandRetriever` |
| `LanguageHook.captureLanguageMap()` | В `Language.load()` после загрузки en_us | `Language` |
| `LanguageHook.loadModLanguages()` | Через `ServerLifecycleHooks.handleServerStarting()` | Вызывается из ServerLifecycleHooks |

---

## 8. Дополнительные интеграции NeoForge, требующие патчей

### 8.1. Injected Interfaces (внедряемые интерфейсы)

Файл `META-INF/injected-interfaces.json` определяет **~80 внедряемых интерфейсов**. Эти интерфейсы должны быть добавлены в `implements` соответствующих ванильных классов через патчи. Ключевые:

- `Entity` → `IEntityExtension`
- `LivingEntity` → `ILivingEntityExtension`
- `Player` → `IPlayerExtension`
- `ItemStack` → `IItemStackExtension`, `MutableDataComponentHolder`
- `Block` → `IBlockExtension`
- `BlockState` → `IBlockStateExtension`
- `Level` → `ILevelExtension`
- `Item` → `IItemExtension`
- `BlockEntity` → `IBlockEntityExtension`
- `ServerChunkCache` → `IServerChunkCacheExtension`
- `PlayerList` → `IPlayerListExtension`
- И множество других

### 8.2. Access Transformers

Файл `META-INF/accesstransformer.cfg` (47+ КБ) содержит сотни записей, меняющих видимость полей и методов в ванильных классах. В контексте Paperweight, AT-файлы применяются автоматически, но это требует, чтобы целевые поля/методы **существовали** в коде (что они и делают, так как это ванильные классы).

### 8.3. Mixins

Файл `neoforge.mixins.json` регистрирует только два миксина:
- `BlockEntityTypeAccessor` — доступ к приватному полю `BlockEntityType.validBlocks`
- `MappedRegistryAccessor` — доступ к приватному полю `MappedRegistry.registrationInfos`

Эти миксины применяются через систему NeoForge SPI и не требуют патчей.

### 8.4. Custom Entry Point: CreliaServer

Кастомный лаунчер `crelia.launcher.Main` запускает `crelia.CreliaServer` вместо стандартного `net.minecraft.server.Main`. Это требует наличия класса `CreliaServer` (вероятно, в `folia-server/src/minecraft`), который не может быть в репозитории из-за `.gitignore`.

---

## 9. Конфликт Folia и NeoForge: Проблемы совместимости

### 9.1. Серверные тик-события

NeoForge вставляет хуки `fireServerTickPre/Post` в `MinecraftServer.tickServer()` и `fireLevelTickPre/Post` в `MinecraftServer.tickChildren()`. Folia **полностью переписывает** эти методы — `tickChildren()` разбит на региональные тики. Это означает, что стандартные NeoForge-патчи для `MinecraftServer` **не применятся** к Folia без значительной адаптации.

### 9.2. Entity tick events

Аналогично, `fireEntityTickPre/Post` в `LivingEntity.tick()` и `firePlayerTickPre/Post` в `Player.tick()` должны быть адаптированы к региональной модели. Folia тикает сущности в разных потоках для разных регионов, и события NeoForge должны корректно работать в этой модели.

### 9.3. Block updates

Патч 0004 Folia оборачивает обновления блоков проверками `TickThread.isTickThreadFor()`. NeoForge-хуки (BlockEvent, NeighborNotifyEvent) должны быть вставлены **до или после** этих проверок, что требует тщательного порядка применения патчей.

### 9.4. Chunk system

Folia использует полностью переработанную систему чанков (moonrise patches). Хуки NeoForge для `ChunkHolder`, `ServerChunkCache`, ticket system должны быть адаптированы к новой архитектуре.

---

## 10. Конкретный план создания отсутствующих патчей

### Этап 1: Патчи для поддержки coremods (критично)

Создать minecraft-patches, добавляющие методы-геттеры в 3 класса:

1. **`net/minecraft/world/level/biome/Biome.java`** — добавить `getModifiedClimateSettings()` и `getModifiedSpecialEffects()`
2. **`net/minecraft/world/level/levelgen/structure/Structure.java`** — добавить `getModifiedStructureSettings()`
3. **`net/minecraft/world/level/block/FlowerPotBlock.java`** — добавить `getPotted()`

**Метод:** Взять из стандартного NeoForge 1.21.1 сервера соответствующие патчи. Они маленькие и тривиальные.

### Этап 2: Патчи для injected-interfaces (критично)

Создать патчи, добавляющие `implements IXxxExtension` в ~80 ванильных классов. Это необходимо для того, чтобы кастомные интерфейсы NeoForge были доступны на экземплярах ванильных классов.

**Метод:** Сравнить список из `injected-interfaces.json` с существующим Folia-кодом и добавить отсутствующие `implements`.

### Этап 3: Патчи для ServerLifecycleHooks (высокий приоритет)

Вставить вызовы жизненного цикла сервера в `MinecraftServer`. Это необходимо для загрузки модов, обработки конфигурации и запуска событий.

**Сложность:** Высокая, так как Folia существенно модифицирует `MinecraftServer.runServer()`. Нужно найти эквивалентные точки вставки в Folia-версии.

### Этап 4: Патчи для EventHooks (высокий приоритет)

Вставить вызовы EventHooks в ванильный код. Оценка по категориям сложности:

- **Простые** (независимые от многопоточности): `onPlayerDestroyItem`, `onAnimalTame`, `canPlayerStartSleeping`, `onPlayerWakeup`, `onGrindstoneChange`, `onNoteChange`, `fireBonemealEvent`, `onPlayerEnchantItem`, `onPotionBrewed`, и т.д.
- **Средние** (затрагивают сущности, но не тик-цикл): `onLivingHeal`, `onLivingDeath`, `onLivingDrops`, `onEntityDestroyBlock`, `onLivingFall`, `handleBlockDrops`, `fireBlockBreak`, `canEntityGrief`
- **Сложные** (конфликтуют с региональной многопоточностью): `fireServerTickPre/Post`, `fireLevelTickPre/Post`, `fireEntityTickPre/Post`, `firePlayerTickPre/Post`, `fireChunkWatch/Sent/UnWatch`

### Этап 5: Патчи для CommonHooks (высокий приоритет)

Аналогично EventHooks, но с большим количеством хуков, особенно в системе:
- Урона (`onEntityIncomingDamage`, `onLivingDamagePre/Post`, `onArmorHurt`)
- Взаимодействия (`onLeftClickBlock`, `onRightClickBlock`, `onInteractEntity`)
- Предметов (`onPlaceItemIntoWorld`, `onItemRightClick`, `onItemStackedOn`)

### Этап 6: Патчи для LanguageHook (средний приоритет)

Вставить `LanguageHook.loadBuiltinLanguages()` и `LanguageHook.captureLanguageMap()` в соответствующие точки.

### Этап 7: Тестирование и отладка

1. Сборка проекта с `./gradlew build`
2. Проверка, что coremods применяются без ошибок
3. Загрузка тестового мода
4. Проверка работы событий NeoForge в региональной модели

---

## 11. Рекомендуемый подход к реверс-инжинирингу

### 11.1. Использование diff с NeoForge сервером

Самый эффективный метод:
1. Скачать vanilla-decompiled исходный код Minecraft 1.21.1 (26.1.2)
2. Скачать стандартный NeoForge 1.21.1 серверные патчи
3. Скачать стандартные Folia патчи
4. Создать объединённые патчи, адаптированные к Folia-версии классов

### 11.2. Использование paperweight

Paperweight уже умеет работать с патчами. Нужно создать директорию `folia-server/neoforge-patches/` и зарегистрировать её в `build.gradle.kts` как дополнительный набор патчей, применяемых **после** Folia-патчей.

### 11.3. Постепенная интеграция

Начать с **Этапа 1** (патчи для coremods) — это минимальный набор для успешного запуска. Затем добавлять патчи по приоритету, тестируя на каждом этапе.

---

## 12. Резюме

| Категория | Количество требуемых патчей | Статус | Приоритет |
|-----------|----------------------------|--------|-----------|
| Методы-геттеры для coremods (3 класса, 4 метода) | 3 патча | **DONE** (0009-0011) | **КРИТИЧНО** |
| Injected interfaces (~80 классов) | N/A (coremod) | **DONE** (coremod) | **КРИТИЧНО** |
| ServerLifecycleHooks + CreliaServer entry point | 1 патч | **DONE** (0012) | **ВЫСОКИЙ** |
| EventHooks Integration (tick, entity join, block break, explosion, chunk watch) | 1 патч | **DONE** (0013) | **ВЫСОКИЙ** |
| Additional Entity Hooks (heal, die, fall, jump, item, projectile, tooltip) | 1 патч | **DONE** (0014) | **ВЫСОКИЙ** |
| LivingEntity Damage Hooks (hurt, knockback, armor, totem, effect, drops) | 1 патч | **DONE** (0015) | **ВЫСОКИЙ** |
| Player Interaction Hooks (attack, critical, sweep) | 1 патч | **DONE** (0016) | **ВЫСОКИЙ** |
| Block Event Hooks (neighbor, farmland, brewing, note, furnace) | 1 патч | **DONE** (0017) | **ВЫСОКИЙ** |
| Item/Inventory Hooks (anvil, grindstone, enchant, craft) | 1 патч | **DONE** (0018) | **ВЫСОКИЙ** |
| Player Lifecycle + Mob Spawn Hooks (login/logout, respawn, dimension, sleep, spawn) | 1 патч | **DONE** (0019) | **ВЫСОКИЙ** |
| World/Level + Misc Hooks (game rules, lightning, experience, commands) | 1 патч | **DONE** (0020) | **СРЕДНИЙ** |
| LanguageHook (2-3 вставки) | 1 патч | TODO | **СРЕДНИЙ** |
| Дополнительные мелкие хуки (remaining ~20) | 1-2 патча | TODO | **НИЗКИЙ** |

### Текущее состояние minecraft-patches (folia-server/minecraft-patches/features/):

```
0001-0008: Folia патчи (region threading, fixes, profiler, watchdog)
0009: NeoForge Biome ClimateSettings + SpecialEffects getters
0010: NeoForge Structure Settings getter
0011: NeoForge FlowerPotBlock Potted getter
0012: NeoForge CreliaServer Entry Point + ServerLifecycleHooks
0013: NeoForge EventHooks Integration (tick, entity join, block break, explosion, chunk watch)
0014: NeoForge Additional Entity Hooks (heal, die, fall, jump, item, projectile, tooltip)
0015: NeoForge LivingEntity Damage Hooks (hurt, knockback, armor, totem, effect, drops)
0016: NeoForge Player Interaction Hooks (attack, critical hit, sweep)
0017: NeoForge Block Event Hooks (neighbor notify, farmland, brewing, note block, furnace)
0018: NeoForge Item/Inventory Hooks (anvil, grindstone, enchanting)
0019: NeoForge Player Lifecycle + Mob Spawn Hooks (login/out, respawn, dimension, sleep, spawn)
0020: NeoForge World/Level + Misc Hooks (game rules, lightning, experience, commands)
```

**Итого: 20 патчей (8 Folia + 12 NeoForge)** — покрывает ~80% требуемых хуков.

**Главное препятствие:** Патчи NeoForge для тик-цикла, чанков и сущностей **напрямую конфликтуют** с Folia-архитектурой региональной многопоточности. Это требует не механического копирования NeoForge-патчей, а их **перепроектирования** для работы в многопоточной среде. События уровня (LevelEvent, EntityTickEvent, ServerTickEvent) должны быть привязаны к регионам, а не к глобальному тик-циклу.