package com.oop.payday.game;

import java.util.List;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.player.Player;

/**
 * 모델 → 뷰 단방향 이벤트 통지(옵저버). 게임 로직이 진행되며 상태 변화를 알린다.
 *
 * <p>모든 메서드는 기본 구현이 비어 있어, 뷰는 필요한 이벤트만 골라 구현하면 된다.
 * 이벤트는 게임 스레드에서 호출되므로, 구현체(JavaFX)는 UI 갱신을
 * {@code Platform.runLater} 로 감싸야 한다.
 */
public interface GameListener {

    default void onPhaseChanged(Phase phase, int round, Team splitTeam) {
    }

    /** 간부/리더/도우미 등 게임 준비 정보가 갱신됨. */
    default void onPlayerSetup(Player player) {
    }

    /** 분할자가 카드 5장을 받음(분할자에게만 공개). */
    default void onHandDealt(Player splitter, List<Card> hand) {
    }

    /** 선택 팀에게 두 묶음이 제시됨(뒷면 카드는 가려짐). */
    default void onChoiceReady(BundlePair bundles) {
    }

    /** 분배 완료: 선택 결과와 각 팀이 가져간 카드(공개됨). */
    default void onDistributed(int chosenIndex, Team chooseTeam, List<Card> chooseCards,
            Team splitTeam, List<Card> splitCards) {
    }

    default void onCashIn(Player player, TreasureSet set) {
    }

    default void onDiscard(Player player, Card card) {
    }

    default void onHelperUsed(Player player, HelperCard helper, String message) {
    }

    /** 종료 단계의 보유 한도 초과로 강제 처분됨. */
    default void onForcedDiscard(Player player, List<Card> cards) {
    }

    default void onCoinsChanged(Team team, int delta) {
    }

    default void onRoundEnd(int round) {
    }

    default void onGameOver(Team winner) {
    }

    /** 일반 안내 메시지(로그/상태표시줄). */
    default void onMessage(String message) {
    }

    /**
     * 게임 스레드를 블록해 UI 애니메이션이 완료될 때까지 대기한다.
     * 구현체는 현재 진행 중인 오버레이(배너·분배 애니메이션)가 끝난 뒤 반환해야 한다.
     */
    default void awaitAnimations() {
    }

    /**
     * 분할 결과를 뷰로 전달하기 위한 두 묶음 묶음 표현.
     * 각 묶음의 공개 카드와 뒷면 유무를 담는다.
     */
    record BundlePair(List<Card> visible0, boolean faceDown0,
            List<Card> visible1, boolean faceDown1) {
    }
}
