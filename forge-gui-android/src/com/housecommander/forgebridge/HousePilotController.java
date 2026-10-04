/*
 * HOUSE Commander Lab assisted human pilot controller.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.housecommander.forgebridge;

import forge.LobbyPlayer;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilMana;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Human priority/action selection with Forge AI retained for low-level rules
 * plumbing such as mana sequencing and target selection.
 */
public final class HousePilotController extends PlayerControllerAi {
    private static final ZoneType[] ACTION_ZONES = new ZoneType[] {
            ZoneType.Hand,
            ZoneType.Battlefield,
            ZoneType.Graveyard,
            ZoneType.Exile,
            ZoneType.Command,
            ZoneType.Flashback
    };

    public HousePilotController(Game game, Player player, LobbyPlayer lobbyPlayer) {
        super(game, player, lobbyPlayer);
    }

    @Override
    public boolean isAI() {
        return false;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        List<SpellAbility> actions = legalActions();
        List<String> labels = new ArrayList<String>(actions.size() + 1);
        for (SpellAbility action : actions) {
            labels.add(actionLabel(action));
        }
        labels.add("Pass priority");

        int choice = PilotDecisionBridge.request(
                PilotDecision.Kind.ACTION,
                getPlayer().getName(),
                priorityPrompt(),
                labels
        );
        if (choice < 0 || choice >= actions.size()) {
            return null;
        }
        return Collections.singletonList(actions.get(choice));
    }

    @Override
    public boolean confirmAction(
            SpellAbility sa,
            PlayerActionConfirmMode mode,
            String message,
            List<String> options,
            Card cardToShow,
            Map<String, Object> params
    ) {
        List<String> labels = new ArrayList<String>();
        if (options != null && options.size() == 2) {
            labels.add(options.get(0));
            labels.add(options.get(1));
        } else {
            labels.add("Yes");
            labels.add("No");
        }

        int choice = PilotDecisionBridge.request(
                PilotDecision.Kind.CONFIRM,
                getPlayer().getName(),
                prompt(message, sa),
                labels
        );
        if (choice < 0) {
            return super.confirmAction(sa, mode, message, options, cardToShow, params);
        }
        return choice == 0;
    }

    @Override
    public boolean chooseBinary(
            SpellAbility sa,
            String question,
            BinaryChoiceType kind,
            Boolean defaultChoice
    ) {
        List<String> labels = binaryLabels(kind);
        int choice = PilotDecisionBridge.request(
                PilotDecision.Kind.BINARY,
                getPlayer().getName(),
                prompt(question, sa),
                labels
        );
        if (choice < 0) {
            return super.chooseBinary(sa, question, kind, defaultChoice);
        }
        return choice == 0;
    }

    private List<SpellAbility> legalActions() {
        List<SpellAbility> out = new ArrayList<SpellAbility>();
        for (ZoneType zone : ACTION_ZONES) {
            for (Card card : getPlayer().getCardsIn(zone)) {
                for (SpellAbility ability : card.getAllPossibleAbilities(getPlayer(), true)) {
                    if (ability == null || ability.isManaAbility()) {
                        continue;
                    }
                    try {
                        if (!ability.canPlay()) {
                            continue;
                        }
                        if (ability.getPayCosts() != null
                                && ability.getPayCosts().hasManaCost()
                                && !ComputerUtilMana.canPayManaCost(
                                        ability,
                                        getPlayer(),
                                        0,
                                        false
                                )) {
                            continue;
                        }
                        if (!ComputerUtilAbility.isFullyTargetable(ability)) {
                            continue;
                        }
                        out.add(ability);
                    } catch (Throwable ignored) {
                        // A predictive legality check must never break the game thread.
                    }
                }
            }
        }
        return out;
    }

    private String priorityPrompt() {
        String phase = String.valueOf(getGame().getPhaseHandler().getPhase());
        int turn = Math.max(0, getGame().getPhaseHandler().getTurn());
        return "Turn " + turn + " • " + phase + " — choose an action";
    }

    private static String actionLabel(SpellAbility ability) {
        String card = ability.getHostCard() == null
                ? "Ability"
                : ability.getHostCard().getName();
        String detail = safe(ability.getDescription());
        if (detail.isEmpty()) {
            detail = safe(ability.getStackDescription());
        }
        if (detail.isEmpty()) {
            detail = ability.isLandAbility() ? "Play land" : "Activate / cast";
        }
        detail = detail.replace('\n', ' ').replace('\r', ' ').trim();
        if (detail.length() > 140) {
            detail = detail.substring(0, 137) + "...";
        }
        return card + " — " + detail;
    }

    private static String prompt(String message, SpellAbility sa) {
        String text = safe(message).trim();
        if (!text.isEmpty()) {
            return text;
        }
        if (sa != null && sa.getHostCard() != null) {
            return sa.getHostCard().getName() + " — choose";
        }
        return "Choose";
    }

    private static List<String> binaryLabels(BinaryChoiceType kind) {
        if (kind == null) {
            return List.of("Option 1", "Option 2");
        }
        switch (kind) {
            case HeadsOrTails:
                return List.of("Heads", "Tails");
            case TapOrUntap:
                return List.of("Tap", "Untap");
            case PlayOrDraw:
                return List.of("Play", "Draw");
            case OddsOrEvens:
                return List.of("Odds", "Evens");
            case UntapOrLeaveTapped:
                return List.of("Untap", "Leave tapped");
            case LeftOrRight:
                return List.of("Left", "Right");
            case AddOrRemove:
                return List.of("Add", "Remove");
            case IncreaseOrDecrease:
                return List.of("Increase", "Decrease");
            default:
                return List.of("Option 1", "Option 2");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
