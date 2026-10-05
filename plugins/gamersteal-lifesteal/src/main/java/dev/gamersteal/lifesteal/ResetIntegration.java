package dev.gamersteal.lifesteal;

import java.util.UUID;

interface ResetIntegration {
    boolean isAvailable();

    boolean reset(UUID uuid);
}
