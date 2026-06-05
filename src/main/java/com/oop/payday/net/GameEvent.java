package com.oop.payday.net;

import java.io.Serializable;
import java.util.List;

import com.oop.payday.game.Phase;

/**
 * 호스트→클라이언트로 전달하는 게임 이벤트. {@link NetMessage.Envelope} 안에 담긴다.
 * 각 variant 는 {@link com.oop.payday.game.GameListener} 콜백과 1:1 대응한다.
 */
public sealed interface GameEvent extends Serializable
        permits GameEvent.PhaseChanged, GameEvent.GameSetup, GameEvent.PlayerSetup,
                GameEvent.HandDealt, GameEvent.ChoiceReady, GameEvent.Distributed,
                GameEvent.CashIn, GameEvent.CashTurn, GameEvent.CashDone,
                GameEvent.Discard, GameEvent.HelperUsed, GameEvent.ForcedDiscard,
                GameEvent.CoinsChanged, GameEvent.RoundEnd, GameEvent.GameOver,
                GameEvent.Message, GameEvent.StealActivated,
                GameEvent.RequestSplit, GameEvent.RequestChoice, GameEvent.RequestHelpers {

    record PhaseChanged(Phase phase, int round, int splitTeamId) implements GameEvent {}

    record GameSetup(List<Integer> playerIds) implements GameEvent {}

    record PlayerSetup(int playerId) implements GameEvent {}

    /** 꾀부리기 손패 5장 — 분할자(클라이언트)에게만 전달. */
    record HandDealt(int splitterId, List<CardDto> hand) implements GameEvent {}

    /** 두 묶음이 제시됨 — 뒷면 카드는 hasFaceDown 만 표시. */
    record ChoiceReady(List<CardDto> visible0, boolean faceDown0,
                       List<CardDto> visible1, boolean faceDown1) implements GameEvent {}

    record Distributed(int chosenIndex, int chooseTeamId, List<CardDto> chooseCards,
                       int splitTeamId, List<CardDto> splitCards) implements GameEvent {}

    record CashIn(int playerId, TreasureSetDto set) implements GameEvent {}

    /** 환금 패널 갱신 요청 — 해당 사람 플레이어에게만 전달. requestId 는 응답 상관관계용. */
    record CashTurn(int playerId, long requestId, CashInContextDto context) implements GameEvent {}

    record CashDone(int playerId) implements GameEvent {}

    record Discard(int playerId, CardDto card) implements GameEvent {}

    record HelperUsed(int playerId, HelperDto helper, String message,
                      List<CardDto> drawn, List<CardDto> discarded) implements GameEvent {}

    record ForcedDiscard(int playerId, List<CardDto> cards) implements GameEvent {}

    record CoinsChanged(int teamId, int delta) implements GameEvent {}

    record RoundEnd(int round) implements GameEvent {}

    record GameOver(int winnerTeamId) implements GameEvent {}

    record Message(String text) implements GameEvent {}

    /** 슬쩍하기 발동 — drawnCardId == -1 이면 드로우 실패. */
    record StealActivated(int playerId, CardDto drawnCard) implements GameEvent {}

    /** 꾀부리기 요청 — 분할자(클라이언트)에게만 전달. requestId 는 응답 상관관계용. */
    record RequestSplit(long requestId, List<CardDto> hand) implements GameEvent {}

    /** 분배 선택 요청 — 선택자(클라이언트)에게만 전달. requestId 는 응답 상관관계용. */
    record RequestChoice(long requestId,
                         List<CardDto> visible0, boolean faceDown0,
                         List<CardDto> visible1, boolean faceDown1) implements GameEvent {}

    /** 도우미 선택 요청 — 해당 플레이어(클라이언트)에게만 전달. requestId 는 응답 상관관계용. */
    record RequestHelpers(long requestId, int playerId, List<HelperDto> options, int chooseCount) implements GameEvent {}
}
