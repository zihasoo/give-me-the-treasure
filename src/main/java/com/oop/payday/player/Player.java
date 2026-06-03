package com.oop.payday.player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.officer.OfficerTile;

/**
 * 플레이어의 공통 상태와 의사결정 계약(추상). 상속·다형성의 축이자 플레이어 컨트롤러 seam.
 *
 * <p>{@code Game} 은 구체 타입(사람/봇)을 모른 채 {@code decideXxx} 를 호출한다.
 * {@link HumanPlayer} 는 UI 입력을, {@code BotPlayer} 는 전략을 통해 응답한다.
 * 추후 네트워크 대전은 {@code NetworkPlayer} 가 이 메서드들에서 원격 클라이언트에
 * 요청을 보내고 응답이 올 때까지 가상 스레드를 블록하는 방식으로 붙일 수 있다.
 * 이때 서버/엔진은 비공개 정보(분할 손패, 도우미 후보)를 소유자에게만 보내야 한다.
 */
public abstract class Player {

    private final String name;
    private final List<Card> holdings = new ArrayList<>();
    private final List<HelperCard> helpers = new ArrayList<>();
    private int holdLimit = 5; // 1인 팀 기본값. Game 이 팀 구성에 맞게 설정한다.
    private OfficerTile officer;
    private boolean leader;
    private boolean holdLimitSuspended;

    protected Player(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    // --- 보관 영역 ---

    public List<Card> holdings() {
        return Collections.unmodifiableList(holdings);
    }

    public void receive(Card card) {
        holdings.add(card);
    }

    public void receiveAll(Collection<Card> cards) {
        holdings.addAll(cards);
    }

    /** 보관 영역에서 카드를 제거한다(참조 동일성 기준). */
    public boolean remove(Card card) {
        return holdings.remove(card);
    }

    public void removeAll(Collection<Card> cards) {
        holdings.removeAll(cards);
    }

    public int holdingCount() {
        return holdings.size();
    }

    public int holdLimit() {
        return holdLimit;
    }

    public void setHoldLimit(int holdLimit) {
        this.holdLimit = holdLimit;
    }

    // --- 간부/도우미 ---

    public OfficerTile officer() {
        return officer;
    }

    public void setOfficer(OfficerTile officer) {
        this.officer = officer;
    }

    public boolean isLeader() {
        return leader;
    }

    public void setLeader(boolean leader) {
        this.leader = leader;
    }

    public List<HelperCard> helpers() {
        return Collections.unmodifiableList(helpers);
    }

    public void receiveHelpers(Collection<HelperCard> cards) {
        helpers.addAll(cards);
    }

    public boolean ownsHelper(HelperCard helper) {
        return helpers.contains(helper);
    }

    public boolean isHoldLimitSuspended() {
        return holdLimitSuspended;
    }

    public void suspendHoldLimit() {
        this.holdLimitSuspended = true;
    }

    public void clearHoldLimitSuspension() {
        this.holdLimitSuspended = false;
    }

    // --- 의사결정 (다형성) ---

    /** 꾀부리기: 손에 든 5장을 두 묶음으로 나누고 1장을 뒷면으로 둔다. */
    public abstract SplitDecision decideSplit(List<Card> hand);

    /** 분배: 두 묶음 중 가져갈 묶음의 인덱스(0 또는 1)를 고른다. */
    public abstract int decideChoice(ChoiceView view);

    /** 게임 준비: 받은 도우미 후보 중 사용할 카드를 고른다. */
    public abstract List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount);

    /** 환금: 자기 보관 영역으로 수행할 행동 목록(환금/처분/도움 요청)을 정한다. */
    public abstract List<CashInAction> decideCashIn(CashInContext context);

    public abstract boolean isBot();

    /** 환금 행동을 한 단계씩 공개할 때 행동 사이 대기 시간(ms). 0이면 즉시. */
    public int revealPaceMillis() { return 0; }

    @Override
    public String toString() {
        return name;
    }
}
