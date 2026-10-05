package dev.gamersteal.lifesteal;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.node.Node;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class LuckPermsReset implements ResetIntegration {
    private final JavaPlugin plugin;

    public LuckPermsReset(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isAvailable() {
        return luckPerms() != null;
    }

    @Override
    public boolean reset(UUID uuid) {
        LuckPerms api = luckPerms();
        if (api == null) {
            return false;
        }

        String groupName = plugin.getConfig().getString("reset-rank", "PEASANT")
                .trim()
                .toLowerCase(Locale.ROOT);
        Group target = api.getGroupManager().getGroup(groupName);
        if (target == null) {
            plugin.getLogger().severe("Cannot reset LuckPerms rank for " + uuid
                    + ": the configured group '" + groupName + "' does not exist.");
            return false;
        }

        CompletableFuture<?> update = api.getUserManager().modifyUser(uuid, user -> {
            // Keep every existing parent group to preserve staff/admin permission grants.
            user.data().add(Node.builder("group." + groupName).build());
            user.setPrimaryGroup(groupName);
        });
        update.whenComplete((user, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.SEVERE,
                        "LuckPerms could not set the primary rank to '" + groupName + "' for " + uuid, error);
                return;
            }
            plugin.getLogger().info("Set LuckPerms primary rank to " + groupName + " for " + uuid
                    + "; retained all other group memberships.");
        });
        return true;
    }

    private LuckPerms luckPerms() {
        RegisteredServiceProvider<LuckPerms> provider =
                Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        return provider == null ? null : provider.getProvider();
    }
}
