package dev.gamersteal.lifesteal;

import com.earth2me.essentials.User;
import net.ess3.api.IEssentials;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.UUID;
import java.util.logging.Level;

public final class EssentialsReset implements ResetIntegration {
    private final JavaPlugin plugin;

    public EssentialsReset(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isAvailable() {
        return essentials() != null;
    }

    @Override
    public boolean reset(UUID uuid) {
        IEssentials api = essentials();
        if (api == null) {
            return false;
        }

        try {
            User user = api.getUser(uuid);
            if (user == null) {
                plugin.getLogger().warning("EssentialsX returned no user data for "
                        + uuid + "; homes remain pending.");
                return false;
            }
            for (String home : new ArrayList<>(user.getHomes())) {
                user.delHome(home);
            }
            user.save();
            plugin.getLogger().info("Removed EssentialsX homes for " + uuid + ".");
            return true;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "EssentialsX home reset failed for " + uuid, exception);
            return false;
        }
    }

    private IEssentials essentials() {
        Plugin optionalPlugin = Bukkit.getPluginManager().getPlugin("Essentials");
        if (optionalPlugin instanceof IEssentials api) {
            return api;
        }
        return null;
    }
}
