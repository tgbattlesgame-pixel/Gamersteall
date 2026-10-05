package dev.gamersteal.lifesteal;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

final class LifeStealListener implements Listener {
    private final GamerStealLifeSteal plugin;

    LifeStealListener(GamerStealLifeSteal plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        PlayerData data = plugin.data(event.getUniqueId());
        if (data == null || data.eliminatedUntilMillis() <= System.currentTimeMillis()) {
            return;
        }
        event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                "You are eliminated. Your ban expires "
                        + java.time.Instant.ofEpochMilli(data.eliminatedUntilMillis()) + "."
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.loadPlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player creditedKiller = null;
        EntityDamageEvent cause = event.getEntity().getLastDamageCause();
        if (cause instanceof EntityDamageByEntityEvent entityCause) {
            creditedKiller = responsiblePlayer(entityCause.getDamager(), 0);
        }
        plugin.handleDeath(event, creditedKiller);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData data = plugin.data(event.getPlayer().getUniqueId());
        if (data == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.getPlayer().isOnline()) {
                PlayerData latest = plugin.data(event.getPlayer().getUniqueId());
                if (latest != null && latest.hearts() > 0) {
                    plugin.applyHeartLimit(event.getPlayer(), latest.hearts());
                }
            }
        });
    }
    private Player responsiblePlayer(Entity source, int depth) {
        if (depth > 4) {
            return null;
        }
        if (source instanceof Player player) {
            return player;
        }
        if (source instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (source instanceof TNTPrimed tnt && tnt.getSource() instanceof Entity owner) {
            return responsiblePlayer(owner, depth + 1);
        }
        return null;
    }
}
