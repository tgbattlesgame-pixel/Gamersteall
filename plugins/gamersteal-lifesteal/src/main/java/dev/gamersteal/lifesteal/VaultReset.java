package dev.gamersteal.lifesteal;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public final class VaultReset implements ResetIntegration {
    private final JavaPlugin plugin;

    public VaultReset(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isAvailable() {
        return economy() != null;
    }

    @Override
    public boolean reset(UUID uuid) {
        Economy provider = economy();
        if (provider == null) {
            return false;
        }

        OfflinePlayer player = Bukkit.getOfflinePlayer(uuid);
        try {
            double balance = provider.getBalance(player);
            if (!Double.isFinite(balance)) {
                plugin.getLogger().severe("Vault returned a non-finite balance for " + uuid + ".");
                return false;
            }
            if (balance == 0.0d) {
                return true;
            }
            EconomyResponse response = balance > 0.0d
                    ? provider.withdrawPlayer(player, balance)
                    : provider.depositPlayer(player, -balance);
            if (!response.transactionSuccess()) {
                plugin.getLogger().severe("Vault could not reset the balance for " + uuid
                        + ": " + response.errorMessage);
                return false;
            }
            plugin.getLogger().info("Reset Vault balance for " + uuid + " to zero.");
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Vault balance reset failed for " + uuid + ": " + exception.getMessage());
            return false;
        }
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> provider =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        return provider == null ? null : provider.getProvider();
    }
}
