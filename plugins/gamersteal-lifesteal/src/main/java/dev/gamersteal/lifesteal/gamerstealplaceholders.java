package dev.gamersteal.lifesteal;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GamerStealPlaceholders extends PlaceholderExpansion {

    private final GamerStealLifeSteal plugin;

    public GamerStealPlaceholders(GamerStealLifeSteal plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "gsl";
    }

    @Override
    public @NotNull String getAuthor() {
        return "GamerSteal";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        PlayerData data = plugin.getPlayerData(player.getUniqueId());

        if (data == null) {
            return "";
        }

        return switch (params.toLowerCase()) {
            case "hearts" -> String.valueOf(data.hearts());
            case "kills" -> String.valueOf(data.kills());
            case "deaths" -> String.valueOf(data.deaths());
            default -> null;
        };
    }
}
