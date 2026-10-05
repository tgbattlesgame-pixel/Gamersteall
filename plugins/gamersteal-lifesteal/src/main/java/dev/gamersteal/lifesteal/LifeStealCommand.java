package dev.gamersteal.lifesteal;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class LifeStealCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of(
            "hearts", "sethearts", "giveheart", "removeheart",
            "revive", "unban", "reset", "reload"
    );

    private final GamerStealLifeSteal plugin;

    LifeStealCommand(GamerStealLifeSteal plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);

        if (action.equals("reload")) {
            if (!sender.hasPermission("gslifesteal.reload")
                    && !sender.hasPermission("gslifesteal.admin")) {
                deny(sender);
                return true;
            }
            plugin.reloadPluginConfig();
            sender.sendMessage(Component.text("GamerStealLifeSteal configuration reloaded."));
            return true;
        }

        if (!sender.hasPermission("gslifesteal.admin")) {
            deny(sender);
            return true;
        }

        if (action.equals("hearts")) {
            if (!requireArgs(sender, args, 2, "/gslifesteal hearts <player>")) {
                return true;
            }
            PlayerData data = find(sender, args[1]);
            if (data != null) {
                sender.sendMessage(Component.text(data.lastKnownName() + " has " + data.hearts()
                        + " heart(s); kills: " + data.kills() + ", deaths: " + data.deaths() + "."));
            }
            return true;
        }

        if (action.equals("sethearts")) {
            if (!requireArgs(sender, args, 3, "/gslifesteal sethearts <player> <amount>")) {
                return true;
            }
            PlayerData data = find(sender, args[1]);
            if (data == null) {
                return true;
            }
            Integer amount = parseInteger(sender, args[2]);
            if (amount == null) {
                return true;
            }
            if (amount < plugin.minimumHearts() || amount > plugin.maximumHearts()) {
                sender.sendMessage(Component.text("Hearts must be between " + plugin.minimumHearts()
                        + " and " + plugin.maximumHearts() + "."));
                return true;
            }
            plugin.setHearts(data, amount);
            sender.sendMessage(Component.text("Set " + data.lastKnownName() + " to " + amount + " heart(s)."));
            return true;
        }

        if (action.equals("giveheart") || action.equals("removeheart")
                || action.equals("revive") || action.equals("unban") || action.equals("reset")) {
            if (!requireArgs(sender, args, 2, "/gslifesteal " + action + " <player>")) {
                return true;
            }
            PlayerData data = find(sender, args[1]);
            if (data == null) {
                return true;
            }
            switch (action) {
                case "giveheart" -> {
                    if (data.eliminatedUntilMillis() > System.currentTimeMillis()) {
                        sender.sendMessage(Component.text("That player is currently eliminated; use revive first."));
                        return true;
                    }
                    if (data.hearts() >= plugin.maximumHearts()) {
                        sender.sendMessage(Component.text("That player is already at the heart limit."));
                        return true;
                    }
                    plugin.setHearts(data, data.hearts() + 1);
                    sender.sendMessage(Component.text("Gave one heart to " + data.lastKnownName() + "."));
                }
                case "removeheart" -> {
                    plugin.setHearts(data, data.hearts() - 1);
                    sender.sendMessage(Component.text("Removed one heart from " + data.lastKnownName() + "."));
                }
                case "revive" -> {
                    plugin.revive(data, true);
                    sender.sendMessage(Component.text("Revived " + data.lastKnownName()
                            + " with 10 hearts and reset gameplay data."));
                }
                case "unban" -> {
                    plugin.unban(data);
                    sender.sendMessage(Component.text("Removed the GamerStealLifeSteal ban for "
                            + data.lastKnownName() + "."));
                }
                case "reset" -> {
                    plugin.resetPlayer(data);
                    sender.sendMessage(Component.text("Reset gameplay data for " + data.lastKnownName() + "."));
                }
                default -> {
                    return false;
                }
            }
            return true;
        }

        sendHelp(sender);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("gslifesteal.admin")
                && !sender.hasPermission("gslifesteal.reload")) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(SUBCOMMANDS, args[0]);
        }
        if (args.length == 2 && SUBCOMMANDS.contains(args[0].toLowerCase(Locale.ROOT))
                && !args[0].equalsIgnoreCase("reload")) {
            List<String> names = new ArrayList<>(plugin.knownNames());
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (names.stream().noneMatch(name -> name.equalsIgnoreCase(player.getName()))) {
                    names.add(player.getName());
                }
            }
            return matching(names, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("sethearts")) {
            return matching(List.of("0", "10", "30"), args[2]);
        }
        return List.of();
    }

    private PlayerData find(CommandSender sender, String target) {
        PlayerData data = plugin.resolve(target);
        if (data == null) {
            sender.sendMessage(Component.text("Unknown player. Use a UUID or a player who has joined this server."));
        }
        return data;
    }

    private Integer parseInteger(CommandSender sender, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            sender.sendMessage(Component.text("Heart amount must be a whole number."));
            return null;
        }
    }

    private boolean requireArgs(CommandSender sender, String[] args, int count, String usage) {
        if (args.length < count) {
            sender.sendMessage(Component.text("Usage: " + usage));
            return false;
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("GamerStealLifeSteal commands:"));
        sender.sendMessage(Component.text("/gslifesteal hearts <player>"));
        sender.sendMessage(Component.text("/gslifesteal sethearts <player> <amount>"));
        sender.sendMessage(Component.text("/gslifesteal giveheart <player>"));
        sender.sendMessage(Component.text("/gslifesteal removeheart <player>"));
        sender.sendMessage(Component.text("/gslifesteal revive <player>"));
        sender.sendMessage(Component.text("/gslifesteal unban <player>"));
        sender.sendMessage(Component.text("/gslifesteal reset <player>"));
        sender.sendMessage(Component.text("/gslifesteal reload"));
    }

    private void deny(CommandSender sender) {
        sender.sendMessage(Component.text("You do not have permission to use this command."));
    }

    private List<String> matching(List<String> choices, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return choices.stream()
                .filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
}
