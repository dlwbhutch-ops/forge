/*
 * HOUSE Commander Lab pilot lobby player.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.player.PlayerController;

/** Creates exactly one HOUSE-assisted human seat inside a Forge match. */
public final class HousePilotLobbyPlayer extends LobbyPlayerAi {
    public HousePilotLobbyPlayer(String name) {
        super(name, null);
        setAiProfile("Default");
    }

    @Override
    public PlayerController createMindSlaveController(Player master, Player slave) {
        return new HousePilotController(slave.getGame(), slave, this);
    }

    @Override
    public Player createIngamePlayer(Game game, int id) {
        Player player = new Player(getName(), game, id);
        player.setFirstController(new HousePilotController(game, player, this));
        return player;
    }
}
