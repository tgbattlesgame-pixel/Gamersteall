package dev.gamersteal.lifesteal;

import java.util.UUID;

public record PlayerData(
        UUID uuid,
        String lastKnownName,
        int hearts,
        int kills,
        int deaths,
        long eliminatedUntilMillis,
        boolean pendingReset
) {
    public PlayerData withName(String name) {
        return new PlayerData(uuid, name, hearts, kills, deaths, eliminatedUntilMillis, pendingReset);
    }

    public PlayerData withHearts(int value) {
        return new PlayerData(uuid, lastKnownName, value, kills, deaths, eliminatedUntilMillis, pendingReset);
    }

    public PlayerData withStats(int newKills, int newDeaths) {
        return new PlayerData(uuid, lastKnownName, hearts, newKills, newDeaths, eliminatedUntilMillis, pendingReset);
    }

    public PlayerData withElimination(long untilMillis, boolean resetPending) {
        return new PlayerData(uuid, lastKnownName, hearts, kills, deaths, untilMillis, resetPending);
    }

    public PlayerData withResetPending(boolean value) {
        return new PlayerData(uuid, lastKnownName, hearts, kills, deaths, eliminatedUntilMillis, value);
    }
}
