package me.hatesvartt.svarttMovementPlugin;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class SvarttMovementPlugin extends JavaPlugin implements Listener {
    private final Map<UUID, Location> lastSafeLocation = new HashMap<>();
    private final Map<UUID, Long> lastTeleportTime = new HashMap<>();
    public static final String RESET = "\u001B[0m";
    public static final String PURPLE = "\u001B[35m";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getLogger().info(PURPLE + "SvarttMovementPlugin" + RESET + " initialized!");
        getLogger().info(" ~ Developed by " + PURPLE + "Hatesvartt" + RESET + " ~");

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        lastSafeLocation.clear();
        lastTeleportTime.clear();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null) return;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontalDistance = Math.hypot(dx, dz); // horizontal blocks per tick

        // config vars
        double maxGroundSpeed = getConfig().getDouble("max-speed", 0.55);
        double maxElytraSpeed = getConfig().getDouble("max-elytra-speed", 3.0);
        long cooldownMillis = getConfig().getLong("teleport-cooldown", 200L);
        boolean notifyPlayer = getConfig().getBoolean("notify-player", false);
        boolean ignoreOp = getConfig().getBoolean("ignore-op", false);
        String  elytraNotifierMessage = getConfig().getString("elytra-notifier-message", "§cYou are moving too fast with Elytra. Slow down!");
        String  groundNotifierMessage = getConfig().getString("ground-notifier-message", "§cYou are moving too fast. Slow down!");


        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        long lastTp = lastTeleportTime.getOrDefault(uuid, 0L);

        boolean onGround = player.isOnGround(); // better than !flying + !gliding
        boolean gliding = player.isGliding();
        boolean jumping = !onGround && !gliding && from.getY() < to.getY(); // some simple jump detection

        boolean groundTooFast = onGround && horizontalDistance > maxGroundSpeed;
        boolean elytraTooFast = gliding && horizontalDistance > maxElytraSpeed;

        // update safe location only if movement is legal or player is jumping

        // skips opped players if config is true
        if (ignoreOp && player.isOp()) return;

        if ((onGround && !groundTooFast) || jumping || (gliding && !elytraTooFast)) {
            lastSafeLocation.put(uuid, to.clone());
        }

        // teleport back if too fast (ignore short jumps to remain true to vanilla)
        if ((groundTooFast || elytraTooFast) && now - lastTp > cooldownMillis) {
            lastTeleportTime.put(uuid, now);

            Location safeLocation = lastSafeLocation.get(uuid);
            if (safeLocation != null) {
                player.teleportAsync(safeLocation).thenRun(() ->
                        getServer().getScheduler().runTask(this, () -> player.setVelocity(new Vector(0, 0, 0)))
                );
            }

            if (groundTooFast && !jumping) {
                if (notifyPlayer) player.sendMessage(groundNotifierMessage);
            } else if (elytraTooFast) {
                if (notifyPlayer) player.sendMessage(elytraNotifierMessage);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastSafeLocation.remove(uuid);
        lastTeleportTime.remove(uuid);
    }
}