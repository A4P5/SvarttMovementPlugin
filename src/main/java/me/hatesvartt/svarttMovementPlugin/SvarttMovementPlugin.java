package me.hatesvartt.svarttMovementPlugin;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SvarttMovementPlugin extends JavaPlugin implements Listener {

    private static class PlayerState {
        Location lastSafeLocation = null;
        Location lastLocation = null;
        long lastTimeNano = 0L;
        int suspicion = 0;
        long lastActionTime = 0L;
    }

    private static class BoatState {
        Location lastSafeLocation = null;
        Location lastLocation = null;
        int suspicion = 0;
        long lastActionTime = 0L;
    }

    private final Map<UUID, PlayerState> stateMap = new ConcurrentHashMap<>();
    private final Map<UUID, BoatState> boatStateMap = new ConcurrentHashMap<>();

    // olayer movement threshold
    private double maxGroundSpeed = 6.0;
    private double maxVerticalSpeed = 1.5;
    private int suspicionThreshold = 5;
    private long actionCooldownMillis = 500;
    private boolean notifyPlayer = false;

    // boat movement thresholds
    private double maxBoatHorizontalSpeed = 8.0;
    private double maxBoatVerticalSpeed = 1.0;
    private int boatSuspicionThreshold = 5;
    private long boatActionCooldown = 500;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        maxGroundSpeed = getConfig().getDouble("max-ground-speed", maxGroundSpeed);
        maxVerticalSpeed = getConfig().getDouble("max-vertical-speed", maxVerticalSpeed);
        suspicionThreshold = getConfig().getInt("suspicion-threshold", suspicionThreshold);
        actionCooldownMillis = getConfig().getLong("action-cooldown-ms", actionCooldownMillis);
        notifyPlayer = getConfig().getBoolean("notify-player", notifyPlayer);

        maxBoatHorizontalSpeed = getConfig().getDouble("max-boat-horizontal-speed", maxBoatHorizontalSpeed);
        maxBoatVerticalSpeed = getConfig().getDouble("max-boat-vertical-speed", maxBoatVerticalSpeed);
        boatSuspicionThreshold = getConfig().getInt("boat-suspicion-threshold", boatSuspicionThreshold);
        boatActionCooldown = getConfig().getLong("boat-action-cooldown", boatActionCooldown);

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        stateMap.clear();
        boatStateMap.clear();
    }

    private PlayerState getState(Player player) {
        return stateMap.computeIfAbsent(player.getUniqueId(), k -> new PlayerState());
    }

    private BoatState getBoatState(Boat boat) {
        return boatStateMap.computeIfAbsent(boat.getUniqueId(), k -> new BoatState());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from == null || to == null) return;
        if (!from.getWorld().equals(to.getWorld())) return;

        // --- the boatFly detection ---
        if (player.isInsideVehicle() && player.getVehicle() instanceof Boat boat) {
            BoatState bs = getBoatState(boat);

            double dx = to.getX() - (bs.lastLocation != null ? bs.lastLocation.getX() : from.getX());
            double dz = to.getZ() - (bs.lastLocation != null ? bs.lastLocation.getZ() : from.getZ());
            double dy = to.getY() - (bs.lastLocation != null ? bs.lastLocation.getY() : from.getY());

            double horizontalSpeed = Math.hypot(dx, dz) / 0.05; // approximate 1 tick = 0.05s
            double verticalSpeed = Math.abs(dy) / 0.05;

            boolean horizontalHack = horizontalSpeed > maxBoatHorizontalSpeed;
            boolean verticalHack = verticalSpeed > maxBoatVerticalSpeed;

            if (horizontalHack || verticalHack) {
                bs.suspicion++;
                long nowMs = System.currentTimeMillis();
                if (bs.suspicion >= boatSuspicionThreshold && nowMs - bs.lastActionTime > boatActionCooldown) {
                    bs.lastActionTime = nowMs;
                    if (bs.lastSafeLocation != null) {
                        boat.teleportAsync(bs.lastSafeLocation).thenRun(() ->
                                getServer().getScheduler().runTask(this, () -> boat.setVelocity(new Vector(0, 0, 0))));
                    }
                    bs.suspicion = 0;
                    if (notifyPlayer) {
                        player.sendMessage(ChatColor.RED + "Suspicious boat movement detected!");
                    }
                }
            } else {
                bs.lastSafeLocation = to.clone();
                bs.suspicion = Math.max(0, bs.suspicion - 1);
            }
            bs.lastLocation = to.clone();
            return; // skip normal movement checks while in boat
        }

        // --- Normal player movement ---
        PlayerState st = getState(player);

        if (player.isOp() || player.getGameMode() == GameMode.CREATIVE)
            return;

        if (player.isGliding()) { // elytra is fully bypassed
            st.lastSafeLocation = to.clone();
            st.lastLocation = to.clone();
            st.suspicion = 0;
            return;
        }

        long nowNano = System.nanoTime();
        if (st.lastTimeNano == 0L) {
            st.lastTimeNano = nowNano;
            st.lastLocation = from.clone();
            st.lastSafeLocation = from.clone();
            return;
        }

        double deltaSeconds = (nowNano - st.lastTimeNano) / 1_000_000_000.0;
        st.lastTimeNano = nowNano;
        if (deltaSeconds <= 0) return;

        double dx = to.getX() - st.lastLocation.getX();
        double dz = to.getZ() - st.lastLocation.getZ();
        double dy = to.getY() - st.lastLocation.getY();

        double horizontalDistance = Math.hypot(dx, dz);
        double verticalDistance = Math.abs(dy);

        if (horizontalDistance < 0.01 && verticalDistance < 0.01) {
            st.lastLocation = to.clone();
            return;
        }

        double horizontalSpeed = horizontalDistance / deltaSeconds;
        double verticalSpeed = verticalDistance / deltaSeconds;

        boolean onGround = player.isOnGround();
        boolean movingUp = dy > 0;
        boolean normalJump = !onGround && dy > 0 && dy <= 1.0;
        boolean verticalHack = movingUp && !normalJump && verticalSpeed > maxVerticalSpeed;
        boolean horizontalHack = horizontalSpeed > maxGroundSpeed;

        if (verticalHack || horizontalHack) {
            st.suspicion++;
            long nowMs = System.currentTimeMillis();
            if (st.suspicion >= suspicionThreshold && nowMs - st.lastActionTime > actionCooldownMillis) {
                st.lastActionTime = nowMs;
                if (st.lastSafeLocation != null) {
                    player.teleportAsync(st.lastSafeLocation).thenRun(() ->
                            getServer().getScheduler().runTask(this, () ->
                                    player.setVelocity(new Vector(0, 0, 0))));
                }
                st.suspicion = 0;

                if (notifyPlayer) {
                    player.sendMessage(ChatColor.RED + "Suspicious movement detected. You have been reset.");
                }
            }
        } else {
            st.lastSafeLocation = to.clone();
            st.suspicion = Math.max(0, st.suspicion - 1);
        }

        st.lastLocation = to.clone();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        stateMap.remove(event.getPlayer().getUniqueId());
        if (event.getPlayer().getVehicle() instanceof Boat boat) {
            boatStateMap.remove(boat.getUniqueId());
        }
    }
}
