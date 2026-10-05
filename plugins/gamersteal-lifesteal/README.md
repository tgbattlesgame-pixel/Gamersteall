# GamerStealLifeSteal

A UUID-based Paper lifesteal plugin with SQLite-backed heart data, pair cooldowns, and restart-safe temporary elimination bans.

## Compatibility and release status

- Java 25.
- Paper API `26.3.build.141-beta`, verified against PaperMC's published 26.3 API metadata on October 5, 2026.
- PaperMC's download service currently lists 26.3 beta builds and no stable 26.3 server build. This JAR targets that beta API; it has not been verified against a stable 26.3 server because one is not currently published.
- LuckPerms 5.5, Vault API 1.7.1, and EssentialsX 2.21.2 are optional integrations. Install the plugins and an economy provider to enable their reset features.
- Geyser/Floodgate players use their server UUIDs and normal Bukkit `Player` events. No Geyser or Floodgate API dependency is required.

## Build

With Java 25 and Maven installed:

```sh
mvn -f plugins/gamersteal-lifesteal/pom.xml clean package
```

The shaded plugin JAR is created at:

```text
plugins/gamersteal-lifesteal/target/GamerStealLifeSteal.jar
```

The JAR bundles SQLite JDBC only. Paper, LuckPerms, Vault, and EssentialsX APIs are provided by the server.

## Install

Copy `target/GamerStealLifeSteal.jar` into the Paper server's `plugins` directory, then restart. The plugin creates `plugins/GamerStealLifeSteal/config.yml` and `playerdata.db` on first start.

For all reset integrations to work:

1. Install LuckPerms and create the group named by `reset-rank` (default `PEASANT`; the API lookup is case-insensitive).
2. Install Vault and an economy plugin for balance resets.
3. Install EssentialsX for home resets.

The LuckPerms integration sets the user's primary group to the configured rank and ensures that group is assigned. It does not remove any other parent groups or permissions, preventing accidental loss of staff/admin grants.

## Gameplay

- Players start at 10 hearts; the default maximum is 30.
- A credited player kill subtracts and adds the configured number of hearts, bounded by the configured minimum and maximum.
- Player-to-player heart transfers share one 10-minute cooldown per unordered UUID pair, preventing reciprocal kill farming.
- Natural and mob deaths do not change hearts by default.
- Player-owned projectiles and primed TNT are attributed when Paper exposes the responsible player. The final damage event must identify that player, so unrelated fall, environmental, and mob deaths do not inherit credit from an earlier hit.
- At zero hearts, the player is reset, banned by UUID for the configured 48 hours, and recorded in SQLite. The expiry is re-applied after restart and automatically pardoned.
- At return, the player is reset again and receives 10 hearts.
- Offline admin resets are persisted as pending and finish when that UUID next joins.

## Commands

| Command | Purpose |
| --- | --- |
| `/gslifesteal` | Show command help |
| `/gslifesteal hearts <player>` | Show heart count and plugin kill/death counts |
| `/gslifesteal sethearts <player> <amount>` | Set hearts |
| `/gslifesteal giveheart <player>` | Add one heart |
| `/gslifesteal removeheart <player>` | Remove one heart |
| `/gslifesteal revive <player>` | Unban, reset data, and return the player with 10 hearts |
| `/gslifesteal unban <player>` | Remove the plugin's profile ban |
| `/gslifesteal reset <player>` | Reset gameplay data without applying an elimination ban |
| `/gslifesteal reload` | Reload `config.yml` |

`<player>` accepts a UUID or a name cached from a previous join. This avoids resolving an unknown name to a made-up offline UUID.

## Permissions

- `gslifesteal.admin` — all management commands; default: operators.
- `gslifesteal.reload` — reload command; default: operators.
- `gslifesteal.bypass` — excluded from heart-transfer and elimination mechanics; default: nobody.

## Data and safety

All player records and cooldown expirations are stored in SQLite, with database work performed on a dedicated single-thread executor. Player inventories, stats, ranks, balances, homes, and bans are only touched for the target UUID. World files are never accessed or changed.
