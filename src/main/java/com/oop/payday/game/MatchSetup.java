package com.oop.payday.game;

import java.util.ArrayList;
import java.util.List;

import com.oop.payday.bot.BotKind;

/**
 * 대기실에서 구성하는 한 판의 자리 배치. 항상 2팀이며 각 팀은 1~2개의 슬롯을 갖는다
 * (규칙서 §2: 2인=1v1, 3인=1v2, 4인=2v2). 슬롯은 사람·봇·원격 중 하나다.
 *
 * <p>순수 데이터 컨테이너로, 실제 {@link com.oop.payday.player.Player}/{@link Team} 생성은
 * 게임 보드 컨트롤러가 슬롯을 순회하며 수행한다(로컬 플레이어 추적이 필요하므로).
 */
public final class MatchSetup {

    /** 한 팀의 최대 인원(규칙서 §2: 2 vs 2). */
    public static final int MAX_TEAM_SIZE = 2;

    public enum SlotKind {
        /** 이 PC에서 조작하는 사람(방장). */
        HUMAN_LOCAL,
        /** 봇이 채우는 자리. */
        BOT,
        /** 원격 클라이언트가 채울 자리(2단계 네트워크). */
        REMOTE
    }

    /** 점유한 원격 클라이언트가 없는 슬롯의 clientId 표식. */
    public static final int NO_CLIENT = -1;

    /**
     * 한 자리의 구성.
     * {@code botKind} 는 {@code kind == BOT} 일 때만, {@code clientId} 는 {@code kind == REMOTE} 일 때만
     * 의미 있다(그 외엔 {@link #NO_CLIENT}).
     */
    public record Slot(SlotKind kind, BotKind botKind, String name, int clientId) {

        public static Slot human(String name) {
            return new Slot(SlotKind.HUMAN_LOCAL, null, name, NO_CLIENT);
        }

        public static Slot bot(BotKind botKind, String name) {
            return new Slot(SlotKind.BOT, botKind, name, NO_CLIENT);
        }

        public static Slot remote(int clientId, String name) {
            return new Slot(SlotKind.REMOTE, null, name, clientId);
        }

    }

    private final List<Slot> teamA = new ArrayList<>();
    private final List<Slot> teamB = new ArrayList<>();
    private boolean practice;

    public List<Slot> teamA() {
        return teamA;
    }

    public List<Slot> teamB() {
        return teamB;
    }

    public boolean practice() {
        return practice;
    }

    public void setPractice(boolean practice) {
        this.practice = practice;
    }

    /** 연습 모드 체크박스에 따라 정식/연습 규칙 설정을 만든다. */
    public GameConfig gameConfig() {
        return practice ? GameConfig.practice(true) : GameConfig.standard(true);
    }

    /** 기본 구성: 1 vs 1 (나 + 스마트 봇). 대기실 진입 시 출발점. */
    public static MatchSetup defaultSetup(String hostName) {
        MatchSetup setup = new MatchSetup();
        setup.teamA.add(Slot.human(hostName));
        setup.teamB.add(Slot.bot(BotKind.S3, "봇 1"));
        return setup;
    }
}
