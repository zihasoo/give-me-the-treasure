package com.oop.payday.model.officer;

import com.oop.payday.game.Team;
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.player.Player;

/**
 * 간부 리더 효과 판정에 필요한 읽기 전용 상황값.
 */
public record LeaderContext(
        Player leader,
        Team team,
        Team opponent,
        TreasureSet cashedSet,
        int teamCashCountThisRound,
        int opponentCursedCount,
        boolean enabled) {
}
