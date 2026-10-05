package dev.gamersteal.lifesteal;

import net.kyori.adventure.text.Component;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

public final class GamerStealLifeSteal extends JavaPlugin {
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    private final Map<String, Long> pairCooldowns = new ConcurrentHashMap<>();
    private final Set<UUID> loadingPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> eliminationsInProgress = ConcurrentHashMap.newKeySet();
    private PluginStorage storage;
    private IntegrationHooks integrations;
    private volatile boolean stateLoaded;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        storage = new PluginStorage(getDataFolder().toPath()
                .resolve(getConfig().getString("database-file", "playerdata.db")));
        integrations = new IntegrationHooks(this);

        PluginCommand command = getCommand("gslifesteal");
        if (command == null) {
            getLogger().severe("The gslifesteal command is missing from plugin.yml; disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        LifeStealCommand executor = new LifeStealCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
        getServer().getPluginManager().registerEvents(new LifeStealListener(this), this);

        integrations.reportAvailability();
        storage.initialize()
                .thenCompose(ignored -> storage.loadSnapshot())
                .whenComplete((snapshot, error) -> runOnMain(() -> {
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "Could not load persistent lifesteal data.", unwrap(error));
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                    }
                    players.putAll(snapshot.players());
                    pairCooldowns.putAll(snapshot.cooldowns());
                    stateLoaded = true;
                    restoreExpirations();
                    for (Player online : getServer().getOnlinePlayers()) {
                        loadPlayer(online);
                    }
                    getLogger().info("Loaded " + players.size() + " player records and "
                            + pairCooldowns.size() + " active pair cooldowns.");
                }));
    }

    @Override
    public void onDisable() {
        if (storage != null) {
            try {
                storage.close().get(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                getLogger().log(Level.SEVERE, "Interrupted while flushing the player database.", exception);
            } catch (ExecutionException | TimeoutException exception) {
                getLogger().log(Level.SEVERE, "Could not flush the player database cleanly.", unwrap(exception));
            }
        }
    }

    void loadPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (!stateLoaded) {
            return;
        }
        PlayerData cached = players.get(uuid);
        if (cached != null) {
            PlayerData named = cached.withName(player.getName());
            players.put(uuid, named);
            if (!named.lastKnownName().equals(cached.lastKnownName())) {
                store(named);
            }
            handleLoadedPlayer(player, named);
            return;
        }
        if (!loadingPlayers.add(uuid)) {
            return;
        }
        storage.loadOrCreatePlayer(uuid, player.getName(), startingHearts())
                .whenComplete((data, error) -> runOnMain(() -> {
                    loadingPlayers.remove(uuid);
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "Could not load data for " + uuid, unwrap(error));
                        player.kick(Component.text("Your lifesteal data could not be loaded. Please reconnect later."));
                        return;
                    }
                    PlayerData named = data.withName(player.getName());
                    players.put(uuid, named);
                    if (player.isOnline()) {
                        handleLoadedPlayer(player, named);
                    }
                }));
    }

    void handleLoadedPlayer(Player player, PlayerData data) {
        long now = System.currentTimeMillis();
        if (data.eliminatedUntilMillis() > now) {
            ensureBan(data);
            player.kick(Component.text("You are eliminated. Your 48-hour ban expires "
                    + Instant.ofEpochMilli(data.eliminatedUntilMillis()) + "."));
            return;
        }
        if (data.eliminatedUntilMillis() > 0 || data.pendingReset()) {
            PlayerData returned = new PlayerData(
                    data.uuid(), player.getName(), startingHearts(), 0, 0, 0, false);
            players.put(data.uuid(), returned);
            resetOnlineGameplay(player, true);
            store(returned);
            getLogger().info("Applied the returning-player reset for " + data.uuid() + ".");
        } else {
            PlayerData named = data.withName(player.getName());
            players.put(data.uuid(), named);
            store(named);
            applyHeartLimit(player, named.hearts());
        }
    }

    PlayerData data(UUID uuid) {
        return players.get(uuid);
    }

    List<String> knownNames() {
        List<String> names = new ArrayList<>();
        for (PlayerData data : players.values()) {
            if (data.lastKnownName() != null && !data.lastKnownName().isBlank()) {
                names.add(data.lastKnownName());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    PlayerData resolve(String input) {
        try {
            return players.get(UUID.fromString(input));
        } catch (IllegalArgumentException ignored) {
            return players.values().stream()
                    .filter(data -> data.lastKnownName() != null
                            && data.lastKnownName().equalsIgnoreCase(input))
                    .findFirst()
                    .orElse(null);
        }
    }

    boolean handleDeath(PlayerDeathEvent event, Player creditedKiller) {
        Player victim = event.getEntity();
        PlayerData victimData = players.get(victim.getUniqueId());
        if (victimData == null) {
            getLogger().warning("Skipped lifesteal for " + victim.getUniqueId()
                    + " because their database record was not loaded.");
            return false;
        }

        if (creditedKiller != null
                && creditedKiller.getUniqueId().equals(victim.getUniqueId())) {
            creditedKiller = null;
        }

        PlayerData killerData = creditedKiller == null ? null : players.get(creditedKiller.getUniqueId());
        boolean canTransfer = creditedKiller != null && killerData != null
                && !victim.hasPermission("gslifesteal.bypass")
                && !creditedKiller.hasPermission("gslifesteal.bypass")
                && !victim.getUniqueId().equals(creditedKiller.getUniqueId());

        PlayerData nextVictim = victimData.withStats(victimData.kills(), victimData.deaths() + 1);
        PlayerData nextKiller = killerData;
        boolean pairOnCooldown = false;
        long now = System.currentTimeMillis();

        if (canTransfer) {
            String key = pairKey(victim.getUniqueId(), creditedKiller.getUniqueId());
            long until = pairCooldowns.getOrDefault(key, 0L);
            pairOnCooldown = until > now;
            if (!pairOnCooldown) {
                long nextCooldown = now + pairCooldownSeconds() * 1000L;
                if (pairCooldownSeconds() > 0) {
                    pairCooldowns.put(key, nextCooldown);
                    storage.saveCooldown(key, nextCooldown)
                            .exceptionally(error -> logStorageFailure("save a pair cooldown", error));
                }
                int heartsPerKill = Math.max(1, getConfig().getInt("hearts-per-kill", 1));
                int victimHearts = Math.max(minimumHearts(), nextVictim.hearts() - heartsPerKill);
                int killerHearts = Math.min(maximumHearts(), killerData.hearts() + heartsPerKill);
                nextVictim = nextVictim.withHearts(victimHearts);
                nextKiller = killerData.withHearts(killerHearts)
                        .withStats(killerData.kills() + 1, killerData.deaths());
            }
        } else if (killerData != null) {
            nextKiller = killerData.withStats(killerData.kills() + 1, killerData.deaths());
        }

        players.put(victim.getUniqueId(), nextVictim);
        store(nextVictim);
        if (nextKiller != null) {
            players.put(nextKiller.uuid(), nextKiller);
            store(nextKiller);
            Player onlineKiller = Bukkit.getPlayer(nextKiller.uuid());
            if (onlineKiller != null) {
                applyHeartLimit(onlineKiller, nextKiller.hearts());
                if (canTransfer && !pairOnCooldown) {
                    onlineKiller.sendMessage(Component.text("You gained "
                            + Math.min(Math.max(1, getConfig().getInt("hearts-per-kill", 1)),
                            maximumHearts() - killerData.hearts())
                            + " heart(s). You now have " + nextKiller.hearts() + "."));
                }
            }
        }

        boolean naturalLoss = creditedKiller == null
                && getConfig().getBoolean("natural-death-heart-loss", false)
                && !victim.hasPermission("gslifesteal.bypass");
        if (naturalLoss) {
            int loss = Math.max(1, getConfig().getInt("hearts-per-kill", 1));
            nextVictim = nextVictim.withHearts(Math.max(minimumHearts(), nextVictim.hearts() - loss));
            players.put(victim.getUniqueId(), nextVictim);
            store(nextVictim);
        }

        boolean reachedZero = (canTransfer && !pairOnCooldown) || naturalLoss
                ? nextVictim.hearts() <= minimumHearts()
                : false;
        if (reachedZero) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.setDeathMessage(null);
            eliminate(nextVictim, victim, creditedKiller == null ? "natural causes" : creditedKiller.getName());
            return true;
        }

        if (creditedKiller != null && canTransfer && pairOnCooldown) {
            victim.sendMessage(Component.text("No hearts changed: this player pair is still on cooldown."));
            Player onlineKiller = Bukkit.getPlayer(creditedKiller.getUniqueId());
            if (onlineKiller != null) {
                onlineKiller.sendMessage(Component.text("No hearts changed: this player pair is still on cooldown."));
            }
        } else if (creditedKiller != null && canTransfer) {
            victim.sendMessage(Component.text("You lost " + Math.min(
                    Math.max(1, getConfig().getInt("hearts-per-kill", 1)),
                    victimData.hearts() - minimumHearts()) + " heart(s). You now have "
                    + nextVictim.hearts() + "."));
        }
        return false;
    }

    void applyHeartLimit(Player player, int hearts) {
        AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            getLogger().warning("Paper did not provide MAX_HEALTH for " + player.getUniqueId() + ".");
            return;
        }
        double healthLimit = Math.max(2.0d, hearts * 2.0d);
        maxHealth.setBaseValue(healthLimit);
        if (!player.isDead() && player.getHealth() > healthLimit) {
            player.setHealth(healthLimit);
        }
    }

    void setHearts(PlayerData data, int hearts) {
        int bounded = Math.clamp(hearts, minimumHearts(), maximumHearts());
        PlayerData updated = data.withHearts(bounded);
        players.put(updated.uuid(), updated);
        store(updated);
        Player online = Bukkit.getPlayer(updated.uuid());
        if (online != null) {
            if (bounded <= minimumHearts()) {
                eliminate(updated, online, "an administrator");
            } else {
                applyHeartLimit(online, bounded);
            }
        } else if (bounded <= minimumHearts()) {
            eliminate(updated, null, "an administrator");
        }
    }

    void revive(PlayerData data, boolean fullReset) {
        pardon(data.uuid());
        eliminationsInProgress.remove(data.uuid());
        PlayerData revived = new PlayerData(
                data.uuid(),
                data.lastKnownName(),
                startingHearts(),
                0,
                0,
                0,
                true
        );
        players.put(data.uuid(), revived);
        store(revived);

        Player online = Bukkit.getPlayer(data.uuid());
        if (online != null) {
            if (fullReset) {
                resetOnlineGameplay(online, true);
            }
            PlayerData finished = revived.withResetPending(false);
            players.put(data.uuid(), finished);
            store(finished);
            applyHeartLimit(online, finished.hearts());
            online.sendMessage(Component.text(fullReset
                    ? "You have been revived with 10 hearts and reset gameplay data."
                    : "Your elimination ban was removed. Your hearts are now " + finished.hearts() + "."));
        } else if (fullReset) {
            integrations.resetRank(data.uuid());
            resetMoney(data.uuid());
        } else {
            getLogger().info("Queued the " + (fullReset ? "revive" : "unban")
                    + " reset for offline UUID " + data.uuid() + ".");
        }
        getLogger().info((fullReset ? "Revived " : "Unbanned ") + data.uuid() + ".");
    }

    void unban(PlayerData data) {
        pardon(data.uuid());
        eliminationsInProgress.remove(data.uuid());
        int hearts = data.hearts() <= minimumHearts() ? startingHearts() : data.hearts();
        PlayerData unbanned = new PlayerData(
                data.uuid(),
                data.lastKnownName(),
                hearts,
                data.kills(),
                data.deaths(),
                0,
                data.pendingReset()
        );
        players.put(data.uuid(), unbanned);
        store(unbanned);
        Player online = Bukkit.getPlayer(data.uuid());
        if (online != null) {
            if (unbanned.pendingReset()) {
                handleLoadedPlayer(online, unbanned);
            } else {
                applyHeartLimit(online, unbanned.hearts());
            }
        }
        getLogger().info("Removed the GamerStealLifeSteal ban for " + data.uuid() + ".");
    }

    void resetPlayer(PlayerData data) {
        PlayerData reset = new PlayerData(
                data.uuid(), data.lastKnownName(), startingHearts(), 0, 0, 0, true);
        players.put(data.uuid(), reset);
        store(reset);
        pardon(data.uuid());
        Player online = Bukkit.getPlayer(data.uuid());
        if (online != null) {
            resetOnlineGameplay(online, true);
            PlayerData done = reset.withResetPending(false);
            players.put(data.uuid(), done);
            store(done);
            applyHeartLimit(online, done.hearts());
        } else {
            integrations.resetRank(data.uuid());
            resetMoney(data.uuid());
        }
        getLogger().info("Reset gameplay data for UUID " + data.uuid() + ".");
    }

    void reloadPluginConfig() {
        reloadConfig();
        getLogger().info("Configuration reloaded.");
    }

    int startingHearts() {
        return Math.clamp(getConfig().getInt("starting-hearts", 10), minimumHearts(), maximumHearts());
    }

    int minimumHearts() {
        return Math.max(0, getConfig().getInt("minimum-hearts", 0));
    }

    int maximumHearts() {
        return Math.max(startingHeartsUnclamped(), getConfig().getInt("maximum-hearts", 30));
    }

    int startingHeartsUnclamped() {
        return Math.max(1, getConfig().getInt("starting-hearts", 10));
    }

    private void restoreExpirations() {
        long now = System.currentTimeMillis();
        for (PlayerData data : List.copyOf(players.values())) {
            if (data.eliminatedUntilMillis() <= 0) {
                continue;
            }
            if (data.eliminatedUntilMillis() <= now) {
                finishElimination(data);
            } else {
                ensureBan(data);
                long ticks = Math.max(1, (data.eliminatedUntilMillis() - now) / 50);
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    PlayerData current = players.get(data.uuid());
                    if (current != null && current.eliminatedUntilMillis() == data.eliminatedUntilMillis()) {
                        finishElimination(current);
                    }
                }, ticks);
            }
        }
    }

    private void finishElimination(PlayerData data) {
        pardon(data.uuid());
        eliminationsInProgress.remove(data.uuid());
        PlayerData returned = new PlayerData(
                data.uuid(), data.lastKnownName(), startingHearts(), 0, 0, 0, true);
        players.put(data.uuid(), returned);
        store(returned);
        Player online = Bukkit.getPlayer(data.uuid());
        if (online != null) {
            handleLoadedPlayer(online, returned);
        }
        getLogger().info("Expired the elimination ban for " + data.uuid() + ".");
    }

    private void eliminate(PlayerData data, Player online, String cause) {
        if (!eliminationsInProgress.add(data.uuid())) {
            return;
        }
        long durationHours = Math.max(1, getConfig().getLong("elimination-ban-hours", 48));
        long expiresAt = Instant.now().plus(Duration.ofHours(durationHours)).toEpochMilli();
        PlayerData eliminated = new PlayerData(data.uuid(), data.lastKnownName(), 0, 0, 0, expiresAt, true);
        players.put(data.uuid(), eliminated);

        String reason = "Eliminated by GamerStealLifeSteal. Return after the "
                + durationHours + "-hour elimination ban.";
        try {
            ProfileBanList bans = Bukkit.getBanList(BanList.Type.PROFILE);
            PlayerProfile profile = Bukkit.createProfile(data.uuid());
            bans.addBan(profile, reason, new Date(expiresAt), getName());
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not create the persistent UUID ban for " + data.uuid(), exception);
        }

        if (online != null) {
            resetOnlineGameplay(online, true);
            online.sendMessage(Component.text(
                    "You were eliminated. Your ban lasts " + durationHours
                            + " hours. Your gameplay progress and rank were reset. "
                            + "You will return with 10 hearts."));
            online.kick(Component.text("Eliminated. You are banned for " + durationHours
                    + " hours; progress and rank were reset. "
                    + "You will return with 10 hearts."));
        } else {
            integrations.resetRank(data.uuid());
            resetMoney(data.uuid());
        }

        store(eliminated);
        String victimName = data.lastKnownName() == null ? data.uuid().toString() : data.lastKnownName();
        String killerText = cause.equals("natural causes") ? "" : " by " + cause;
        Bukkit.broadcast(Component.text(victimName + " was eliminated" + killerText + "."));
        getLogger().info("Eliminated " + data.uuid() + " until "
                + Instant.ofEpochMilli(expiresAt) + "; gameplay reset and "
                + durationHours + "-hour ban recorded.");

        long ticks = Math.max(1, Duration.ofHours(durationHours).toSeconds() * 20L);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            PlayerData current = players.get(data.uuid());
            if (current != null && current.eliminatedUntilMillis() == expiresAt) {
                finishElimination(current);
            }
        }, ticks);
    }

    private void ensureBan(PlayerData data) {
        try {
            ProfileBanList bans = Bukkit.getBanList(BanList.Type.PROFILE);
            PlayerProfile profile = Bukkit.createProfile(data.uuid());
            bans.addBan(
                    profile,
                    "Eliminated by GamerStealLifeSteal. Return after the "
                            + getConfig().getLong("elimination-ban-hours", 48)
                            + "-hour elimination ban.",
                    new Date(data.eliminatedUntilMillis()),
                    getName());
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not restore the persistent UUID ban for " + data.uuid(), exception);
        }
    }

    private void pardon(UUID uuid) {
        try {
            ProfileBanList bans = Bukkit.getBanList(BanList.Type.PROFILE);
            bans.pardon(Bukkit.createProfile(uuid));
        } catch (RuntimeException exception) {
            getLogger().log(Level.WARNING, "Could not remove the profile ban for " + uuid, exception);
        }
    }

    private void resetOnlineGameplay(Player player, boolean clearHomes) {
        UUID uuid = player.getUniqueId();
        if (getConfig().getBoolean("reset-inventory", true)) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        }
        if (getConfig().getBoolean("reset-ender-chest", true)) {
            player.getEnderChest().clear();
        }
        if (getConfig().getBoolean("reset-xp", true)) {
            player.setTotalExperience(0);
            player.setLevel(0);
            player.setExp(0.0f);
        }
        if (getConfig().getBoolean("reset-stats", true)) {
            player.setStatistic(Statistic.PLAYER_KILLS, 0);
            player.setStatistic(Statistic.DEATHS, 0);
        }
        if (clearHomes && getConfig().getBoolean("reset-homes", true)) {
            integrations.resetHomes(uuid);
        }
        resetMoney(uuid);
        integrations.resetRank(uuid);
    }

    private void resetMoney(UUID uuid) {
        if (getConfig().getBoolean("reset-money", true)) {
            integrations.resetMoney(uuid);
        }
    }

    private String pairKey(UUID firstUuid, UUID secondUuid) {
        List<UUID> pair = new ArrayList<>(List.of(firstUuid, secondUuid));
        pair.sort(Comparator.comparing(UUID::toString));
        return pair.getFirst() + ":" + pair.getLast();
    }

    private void store(PlayerData data) {
        storage.savePlayer(data).exceptionally(error -> logStorageFailure("save player " + data.uuid(), error));
    }

    private Void logStorageFailure(String operation, Throwable error) {
        getLogger().log(Level.SEVERE, "Could not " + operation + ".", unwrap(error));
        return null;
    }

    private void runOnMain(Runnable runnable) {
        if (!isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(this, runnable);
    }

    private int pairCooldownSeconds() {
        return Math.max(0, getConfig().getInt("pair-kill-cooldown-seconds", 600));
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
