package net.neoforged.neoforge.event.entity.player;

public class PlayerSpawnPhantomsEvent extends PlayerEvent {
    private int phantomsToSpawn;
    private Result result = Result.DEFAULT;

    public PlayerSpawnPhantomsEvent(Object player, int phantomsToSpawn) {
        super(player);
        this.phantomsToSpawn = phantomsToSpawn;
    }

    public int getPhantomsToSpawn() { return phantomsToSpawn; }
    public void setPhantomsToSpawn(int phantomsToSpawn) { this.phantomsToSpawn = phantomsToSpawn; }
    public void setResult(Result result) { this.result = result; }
    public Result getResult() { return result; }

    public boolean shouldSpawnPhantoms(Object level, Object pos) {
        if (this.getResult() == Result.ALLOW) return true;
        return this.getResult() == Result.DEFAULT;
    }

    public static enum Result {
        ALLOW,
        DEFAULT,
        DENY;
    }
}
