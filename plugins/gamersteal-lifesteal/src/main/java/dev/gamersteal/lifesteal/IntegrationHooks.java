package dev.gamersteal.lifesteal;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Level;

final class IntegrationHooks {
    private final JavaPlugin plugin;
    private final ResetIntegration luckPerms;
    private final ResetIntegration vault;
    private final ResetIntegration essentials;

    IntegrationHooks(JavaPlugin plugin) {
        this.plugin = plugin;
        luckPerms = load("LuckPerms", "dev.gamersteal.lifesteal.LuckPermsReset");
        vault = load("Vault", "dev.gamersteal.lifesteal.VaultReset");
        essentials = load("Essentials", "dev.gamersteal.lifesteal.EssentialsReset");
    }

    void reportAvailability() {
        report("LuckPerms rank", luckPerms);
        report("Vault economy", vault);
        report("EssentialsX homes", essentials);
    }

    void resetRank(UUID uuid) {
        reset("LuckPerms rank", luckPerms, uuid);
    }

    void resetMoney(UUID uuid) {
        reset("Vault balance", vault, uuid);
    }

    boolean resetHomes(UUID uuid) {
        return reset("EssentialsX homes", essentials, uuid);
    }

    private ResetIntegration load(String pluginName, String className) {
        if (!Bukkit.getPluginManager().isPluginEnabled(pluginName)) {
            return null;
        }
        try {
            Class<?> adapterClass = Class.forName(className, true, plugin.getClass().getClassLoader());
            return (ResetIntegration) adapterClass
                    .getDeclaredConstructor(JavaPlugin.class)
                    .newInstance(plugin);
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().log(Level.SEVERE,
                    pluginName + " is enabled, but its optional API adapter could not be loaded.", exception);
            return null;
        }
    }

    private void report(String label, ResetIntegration integration) {
        if (integration == null || !integration.isAvailable()) {
            plugin.getLogger().warning(label + " integration is unavailable; those resets will be skipped.");
        }
    }

    private boolean reset(String label, ResetIntegration integration, UUID uuid) {
        if (integration == null || !integration.isAvailable()) {
            plugin.getLogger().warning("Skipped " + label + " reset for " + uuid + ": integration is unavailable.");
            return false;
        }
        try {
            return integration.reset(uuid);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, label + " reset failed for " + uuid + ".", exception);
            return false;
        }
    }
}
