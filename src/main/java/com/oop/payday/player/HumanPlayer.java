package com.oop.payday.player;

import java.util.List;
import java.util.concurrent.SynchronousQueue;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.helper.HelperCard;

/**
 * 사람 플레이어. 의사결정은 UI 스레드가 채워 넣는다.
 *
 * <p>게임 루프는 별도 스레드에서 돌며 {@code decideXxx} 에서 블록한다.
 * UI(M3)는 사용자가 행동을 마치면 {@code provideXxx} 로 결정을 전달해 게임을 진행시킨다.
 * {@link SynchronousQueue} 로 한 번에 하나의 결정만 주고받는다.
 */
public final class HumanPlayer extends Player {

    private final SynchronousQueue<SplitDecision> splitChannel = new SynchronousQueue<>();
    private final SynchronousQueue<Integer> choiceChannel = new SynchronousQueue<>();
    private final SynchronousQueue<List<HelperCard>> helperChannel = new SynchronousQueue<>();

    private HumanUi ui;

    public HumanPlayer(String name) {
        super(name);
    }

    /** UI 창구를 연결한다(게임 시작 전 컨트롤러가 호출). */
    public void setUi(HumanUi ui) {
        this.ui = ui;
    }

    @Override
    public SplitDecision decideSplit(List<Card> hand) {
        if (ui != null) {
            ui.requestSplit(this, hand);
        }
        return take(splitChannel);
    }

    @Override
    public int decideChoice(ChoiceView view) {
        if (ui != null) {
            ui.requestChoice(this, view);
        }
        return take(choiceChannel);
    }

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount) {
        if (ui != null) {
            ui.requestHelperSelection(this, options, chooseCount);
        }
        return take(helperChannel);
    }

    @Override
    public List<CashInAction> decideCashIn(CashInContext context) {
        // 사람 환금은 풀(pull)이 아니라 이벤트 루프로 처리한다(Game.submitCash/passCash). 호출되지 않음.
        throw new UnsupportedOperationException("사람 환금은 이벤트 루프(submitCash)로 처리합니다.");
    }

    @Override
    public boolean isBot() {
        return false;
    }

    // --- UI 스레드가 호출 (M3) ---

    public void provideSplit(SplitDecision decision) {
        put(splitChannel, decision);
    }

    public void provideChoice(int index) {
        put(choiceChannel, index);
    }

    public void provideHelpers(List<HelperCard> helpers) {
        put(helperChannel, helpers);
    }

    private static <T> T take(SynchronousQueue<T> channel) {
        try {
            return channel.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("의사결정 대기 중 인터럽트됨", e);
        }
    }

    private static <T> void put(SynchronousQueue<T> channel, T value) {
        try {
            channel.put(value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("의사결정 전달 중 인터럽트됨", e);
        }
    }
}
