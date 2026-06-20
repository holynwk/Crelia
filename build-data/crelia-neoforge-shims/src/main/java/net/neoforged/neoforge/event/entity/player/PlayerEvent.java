package net.neoforged.neoforge.event.entity.player;

import java.io.File;
import java.util.Optional;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.event.entity.living.LivingEvent;

public abstract class PlayerEvent extends LivingEvent {
    private final Object player;

    public PlayerEvent(Object player) {
        super(player);
        this.player = player;
    }

    @Override
    public Object getEntity() {
        return player;
    }

    public static class HarvestCheck extends PlayerEvent {
        private final Object state;
        private final Object level;
        private final Object pos;
        private boolean success;

        public HarvestCheck(Object player, Object state, Object level, Object pos, boolean success) {
            super(player);
            this.state = state;
            this.level = level;
            this.pos = pos;
            this.success = success;
        }

        public Object getTargetBlock() { return state; }
        public Object getLevel() { return level; }
        public Object getPos() { return pos; }
        public boolean canHarvest() { return success; }
        public void setCanHarvest(boolean success) { this.success = success; }
    }

    public static class BreakSpeed extends PlayerEvent implements ICancellableEvent {
        private final Object state;
        private final float originalSpeed;
        private float newSpeed = 0.0f;
        private final Optional<Object> pos;

        public BreakSpeed(Object player, Object state, float original, Object pos) {
            super(player);
            this.state = state;
            this.originalSpeed = original;
            this.setNewSpeed(original);
            this.pos = Optional.ofNullable(pos);
        }

        public Object getState() { return state; }
        public float getOriginalSpeed() { return originalSpeed; }
        public float getNewSpeed() { return newSpeed; }
        public void setNewSpeed(float newSpeed) { this.newSpeed = newSpeed; }
        public Optional<Object> getPosition() { return pos; }
    }

    public static class NameFormat extends PlayerEvent {
        private final Object username;
        private Object displayname;

        public NameFormat(Object player, Object username) {
            super(player);
            this.username = username;
            this.displayname = username;
        }

        public Object getUsername() { return username; }
        public Object getDisplayname() { return displayname; }
        public void setDisplayname(Object displayname) { this.displayname = displayname; }
    }

    public static class TabListNameFormat extends PlayerEvent {
        private Object displayName;

        public TabListNameFormat(Object player) { super(player); }

        public Object getDisplayName() { return displayName; }
        public void setDisplayName(Object displayName) { this.displayName = displayName; }
    }

    public static class Clone extends PlayerEvent {
        private final Object original;
        private final boolean wasDeath;

        public Clone(Object _new, Object oldPlayer, boolean wasDeath) {
            super(_new);
            this.original = oldPlayer;
            this.wasDeath = wasDeath;
        }

        public Object getOriginal() { return original; }
        public boolean isWasDeath() { return wasDeath; }
    }

    public static class StartTracking extends PlayerEvent {
        private final Object target;
        public StartTracking(Object player, Object target) { super(player); this.target = target; }
        public Object getTarget() { return target; }
    }

    public static class StopTracking extends PlayerEvent {
        private final Object target;
        public StopTracking(Object player, Object target) { super(player); this.target = target; }
        public Object getTarget() { return target; }
    }

    public static class LoadFromFile extends PlayerEvent {
        private final File playerDirectory;
        private final String playerUUID;
        public LoadFromFile(Object player, File originDirectory, String playerUUID) {
            super(player);
            this.playerDirectory = originDirectory;
            this.playerUUID = playerUUID;
        }
        public File getPlayerFile(String suffix) { return new File(this.playerDirectory, this.playerUUID + "." + suffix); }
        public File getPlayerDirectory() { return playerDirectory; }
        public String getPlayerUUID() { return playerUUID; }
    }

    public static class SaveToFile extends PlayerEvent {
        private final File playerDirectory;
        private final String playerUUID;
        public SaveToFile(Object player, File originDirectory, String playerUUID) {
            super(player);
            this.playerDirectory = originDirectory;
            this.playerUUID = playerUUID;
        }
        public File getPlayerFile(String suffix) { return new File(this.playerDirectory, this.playerUUID + "." + suffix); }
        public File getPlayerDirectory() { return playerDirectory; }
        public String getPlayerUUID() { return playerUUID; }
    }

    public static class ItemCraftedEvent extends PlayerEvent {
        private final Object crafting;
        private final Object craftMatrix;
        public ItemCraftedEvent(Object player, Object crafting, Object craftMatrix) {
            super(player); this.crafting = crafting; this.craftMatrix = craftMatrix;
        }
        public Object getCrafting() { return crafting; }
        public Object getInventory() { return craftMatrix; }
    }

    public static class ItemSmeltedEvent extends PlayerEvent {
        private final Object smelting;
        private final int amountRemoved;
        public ItemSmeltedEvent(Object player, Object crafting, int amountRemoved) {
            super(player); this.smelting = crafting; this.amountRemoved = amountRemoved;
        }
        public Object getSmelting() { return smelting; }
        public int getAmountRemoved() { return amountRemoved; }
    }

    public static class PlayerLoggedInEvent extends PlayerEvent {
        public PlayerLoggedInEvent(Object player) { super(player); }
    }

    public static class PlayerLoggedOutEvent extends PlayerEvent {
        public PlayerLoggedOutEvent(Object player) { super(player); }
    }

    public static class PlayerRespawnEvent extends PlayerEvent {
        private final boolean endConquered;
        public PlayerRespawnEvent(Object player, boolean endConquered) {
            super(player); this.endConquered = endConquered;
        }
        public boolean isEndConquered() { return endConquered; }
    }

    public static class PlayerChangeGameModeEvent extends PlayerEvent implements ICancellableEvent {
        private final Object currentGameMode;
        private Object newGameMode;
        public PlayerChangeGameModeEvent(Object player, Object currentGameMode, Object newGameMode) {
            super(player); this.currentGameMode = currentGameMode; this.newGameMode = newGameMode;
        }
        public Object getCurrentGameMode() { return currentGameMode; }
        public Object getNewGameMode() { return newGameMode; }
        public void setNewGameMode(Object newGameMode) { this.newGameMode = newGameMode; }
    }
}
