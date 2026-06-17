package com.oop.payday.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.oop.payday.decision.BundleView;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.HelperDraftContext;
import com.oop.payday.decision.SplitContext;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.helper.HelperKind;
import com.oop.payday.model.officer.OfficerTile;
import com.oop.payday.model.set.SetType;
import com.oop.payday.model.set.SetEvaluator;
import com.oop.payday.model.set.TreasureSet;

/**
 * S7 기반에서 로드맵 4.1~4.6 의 S8급 구조 개선을 묶은 전략. 한 라운드 안의 정교함은 S7 에서 계승하되,
 * 다음 네 축을 더한다.
 *
 * <ol>
 *   <li><b>공개 정보 메모리/카드 카운팅</b>({@link PublicCardTracker}): 매 결정마다 내 보관·상대 보관·
 *       버림 더미로 카드 위치를 재구성해, 내가 짓는 세트의 성장 카드가 아직 <i>살아 있는지</i>(드로우/슬쩍
 *       가능)를 보고 보유 가치를 보정한다. 죽은(상대·소모) 카드로만 자라는 세트는 덜 본다.</li>
 *   <li><b>상대 선택 확률 모델</b>({@link OpponentModel}): 분할 평가에서 선택자를 boolean 이 아니라
 *       {@code P(takesA)} 확률로 보고 두 결과의 기대값으로 점수를 낸다. 기본 prior 는 S7 예측과 동치.</li>
 *   <li><b>실제 환금 결과 기반 종반 평가</b>({@link BotCardEvaluator#projectedCashCoin}): 종반 선택/분할의
 *       실현 마진을 세트 코인 근사가 아니라 도우미(ALPHA 즉승·LUCKY +7)·리더(JOE +1)까지 본 실현 코인으로
 *       계산한다.</li>
 *   <li><b>2v2 분배·도우미 드래프트 상황화</b>: 팀 분배는 세트 완성/저주 라우팅 우선, 도우미 드래프트는
 *       인원수·한도·리더에 따라 정적 순위를 보정한다.</li>
 * </ol>
 *
 * <p>봇은 여전히 무상태 순수 전략이다. {@code -Dbot.debugScores=true} 면 후보 점수 내역
 * ({@link ScoreBreakdown})을 {@code logs/bot-scores.log} 에 남겨, 패배 로그를 점수표로 진단하게 한다.
 */
public final class S8BotStrategy implements BotStrategy {

    // ─── 디버그 점수 로그 ───
    private static final boolean DEBUG_SCORES = Boolean.getBoolean("bot.debugScores");
    private static final Path SCORE_LOG = Path.of("logs", "bot-scores.log");

    // ─── S7 에서 계승한 가중치 ───
    private static final int ENDZONE_PCT = 30;
    private static final int LURE_WEIGHT = 40;
    private static final int MAXIMIN_WEIGHT = 100;
    private static final int DENY_WEIGHT = 100;
    private static final long WIN_SECURE = 1_000_000_000L;
    private static final long SECURE_MARGIN_UNIT = 1_000_000L;
    private static final int BASE_MAXIMIN_WEIGHT = 50;
    private static final int CURSE_HIDDEN_DUMP = 9_000;
    private static final int BASE_HIDDEN_EV = 60;
    private static final int COUNT_LURE = 60;
    private static final int HIDDEN_SUSPICION = 120;
    private static final int WILD_GRAB = 600;
    private static final int CURSE_LIABILITY = 120;

    // ─── S8 신규 가중치 ───
    /** 카드 카운팅 기반 '살아 있는 성장'(한 장 키울 코인 증분 / 예상 턴) 보너스 가중. */
    private static final int GROWTH_WEIGHT = 24;
    /** ALPHA 즉승 묶음을 종반 마진에서 압도적으로 택하게 하는 점수. */
    private static final long ALPHA_WIN_SCORE = WIN_SECURE * 4;

    private final OpponentModel opponentModel;

    public S8BotStrategy() {
        this(OpponentModel.DEFAULT);
    }

    /** 상대 성향 prior 를 주입하는 생성자(테스트·후속 학습용). */
    S8BotStrategy(OpponentModel opponentModel) {
        this.opponentModel = opponentModel;
    }

    @Override
    public String displayName() {
        return "S8";
    }

    // ─── 분할(꾀부리기) ───────────────────────────────────────────────────────────

    @Override
    public SplitDecision decideSplit(SplitContext context) {
        List<Card> hand = context.hand();
        List<Card> holdings = context.holdings();
        List<Card> oppHoldings = context.opponentHoldings();
        PublicCardTracker tracker = new PublicCardTracker(holdings, oppHoldings, context.discardPile());
        int myNeed = need(context.winningCoins(), context.myCoins());
        int tighten = tighten(context.myCoins(), context.opponentCoins(), context.winningCoins());

        SplitDecision best = null;
        long bestScore = Long.MIN_VALUE;
        int n = hand.size();
        for (int mask = 1; mask < (1 << n) - 1; mask++) {
            int count = Integer.bitCount(mask);
            if (count != 1 && count != 2) continue;
            List<Card> bundleA = new ArrayList<>();
            List<Card> bundleB = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) bundleA.add(hand.get(i));
                else bundleB.add(hand.get(i));
            }
            // 묶음 전체(뒷면 포함)에만 의존하는 값은 faceDown 루프 밖에서 한 번만 계산(비용 절감).
            PartitionEval eval = evalPartition(bundleA, bundleB, holdings, oppHoldings, tracker, myNeed, tighten);
            for (Card faceDown : hand) {
                long score = scoreSplit(bundleA, bundleB, faceDown, oppHoldings, eval, tighten);
                if (score > bestScore) {
                    bestScore = score;
                    best = new SplitDecision(bundleA, bundleB, faceDown);
                }
            }
        }
        if (DEBUG_SCORES && best != null) {
            log("SPLIT chosen A=" + summary(best.bundleA()) + " B=" + summary(best.bundleB())
                    + " faceDown=" + cardLabel(best.faceDownCard()) + " score=" + bestScore);
        }
        return best;
    }

    /** faceDown 과 무관한(묶음 전체 기준) 분할 평가값 묶음. */
    private record PartitionEval(int valueA, int valueB, int standaloneA, int standaloneB,
            int synA, int synB, boolean secureA, boolean secureB) {
    }

    private PartitionEval evalPartition(List<Card> bundleA, List<Card> bundleB, List<Card> holdings,
            List<Card> oppHoldings, PublicCardTracker tracker, int myNeed, int tighten) {
        return new PartitionEval(
                myKeepValue(holdings, bundleA, tighten, tracker),
                myKeepValue(holdings, bundleB, tighten, tracker),
                standaloneValue(bundleA, tighten),
                standaloneValue(bundleB, tighten),
                synergy(oppHoldings, bundleA),
                synergy(oppHoldings, bundleB),
                myNeed > 0 && secures(holdings, bundleA, myNeed),
                myNeed > 0 && secures(holdings, bundleB, myNeed));
    }

    private long scoreSplit(List<Card> bundleA, List<Card> bundleB, Card faceDown,
            List<Card> oppHoldings, PartitionEval eval, int tighten) {
        List<Card> visA = without(bundleA, faceDown);
        List<Card> visB = without(bundleB, faceDown);
        boolean faceInA = bundleA.contains(faceDown);
        boolean wild = faceDown.isWild();

        // 상대 선택 확률(로드맵 4.3): 선택자가 묶음 A 를 가져갈 확률. 봇은 뒷면 정체를 알므로 와일드 뒷면은
        // 간파당한다고 본다(faceInA ? A 의 뒷면와일드 : B 의 뒷면와일드).
        double pA = opponentModel.pTakesA(
                visA, faceInA, faceInA && wild,
                visB, !faceInA, !faceInA && wild,
                oppHoldings);

        // 기대 keep: 상대가 A 를 가져가면(p=pA) 나는 B 를, 아니면 A 를 가진다.
        double expectedKeep = pA * eval.valueB() + (1 - pA) * eval.valueA();
        int worstMine = Math.min(eval.valueA(), eval.valueB());
        int mmw = clamp(BASE_MAXIMIN_WEIGHT + (MAXIMIN_WEIGHT - BASE_MAXIMIN_WEIGHT) * tighten / 100, 0, 100);
        double keep = expectedKeep * (100 - mmw) / 100 + (double) worstMine * mmw / 100;

        double theirsA = eval.standaloneA() + (double) DENY_WEIGHT * eval.synA() / 100;
        double theirsB = eval.standaloneB() + (double) DENY_WEIGHT * eval.synB() / 100;
        double expectedTheirs = pA * theirsA + (1 - pA) * theirsB;

        int visTheirs = BotCardEvaluator.bestCashCoin(faceInA ? visA : visB);
        int visMine = BotCardEvaluator.bestCashCoin(faceInA ? visB : visA);
        int lure = visTheirs - visMine;

        long score = 0L;
        if (eval.secureA() && eval.secureB()) {
            score += WIN_SECURE;
        }
        score += (long) ((keep - expectedTheirs) * 10_000L) + (long) (keep * 10);
        score += (long) lure * LURE_WEIGHT * (100 - tighten) / 100;
        // 뒷면 저주 떠넘김: 뒷면이 저주이고 그 묶음을 선택자가 가져갈 확률에 비례.
        if (faceDown instanceof CursedCard) {
            double dumpProb = faceInA ? pA : (1 - pA);
            score += (long) (dumpProb * CURSE_HIDDEN_DUMP);
        }
        return score;
    }

    // ─── 선택(분배) ───────────────────────────────────────────────────────────────

    @Override
    public int decideChoice(ChoiceContext context) {
        ChoiceView view = context.view();
        List<Card> holdings = context.holdings();
        List<Card> oppHoldings = context.opponentHoldings();
        PublicCardTracker tracker = new PublicCardTracker(holdings, oppHoldings, context.discardPile());
        int oppCurseCount = curseCount(oppHoldings);
        int myNeed = need(context.winningCoins(), context.myCoins());
        int tighten = tighten(context.myCoins(), context.opponentCoins(), context.winningCoins());

        int best = 0;
        long bestScore = Long.MIN_VALUE;
        int bestSize = -1;
        int bundleCount = view.bundles().size();
        List<ScoreBreakdown> breakdowns = DEBUG_SCORES ? new ArrayList<>() : null;
        for (int i = 0; i < bundleCount; i++) {
            BundleView mine = view.bundle(i);
            BundleView other = view.bundle((i + 1) % bundleCount);
            ChoiceScore scored = scoreChoice(mine, other, holdings, oppHoldings, tracker,
                    context.helpers(), context.officer(), oppCurseCount, myNeed, tighten);
            if (breakdowns != null) breakdowns.add(scored.breakdown());
            if (scored.total() > bestScore || (scored.total() == bestScore && mine.size() > bestSize)) {
                best = i;
                bestScore = scored.total();
                bestSize = mine.size();
            }
        }
        if (breakdowns != null) logChoice(breakdowns, best);
        return best;
    }

    private record ChoiceScore(long total, ScoreBreakdown breakdown) {
    }

    private ChoiceScore scoreChoice(BundleView mine, BundleView other, List<Card> holdings,
            List<Card> oppHoldings, PublicCardTracker tracker, List<HelperCard> helpers, OfficerTile officer,
            int oppCurseCount, int myNeed, int tighten) {
        List<Card> mineVisible = mine.visibleCards();
        List<Card> otherVisible = other.visibleCards();

        // ALPHA 즉승: 이 묶음으로 숫자1 4장 + ALPHA 가 성립하면 코인 무관 즉시 승리 — 압도적으로 택한다.
        int myProjected = BotCardEvaluator.projectedCashCoin(combine(holdings, mineVisible), helpers, officer, oppCurseCount);
        if (myProjected >= BotCardEvaluator.ALPHA_WIN_COIN) {
            return new ChoiceScore(ALPHA_WIN_SCORE,
                    new ScoreBreakdown(summary(mineVisible), 0, 0, 0, 0, 0, 0, ALPHA_WIN_SCORE, ALPHA_WIN_SCORE, true));
        }

        // 임계 통과(이번 라운드 승리 코인 도달)면 실현 코인 마진(로드맵 4.4)으로 가른다. 내 쪽은 도우미/리더까지
        // 본 실현 코인, 상대 쪽은 받은 묶음 + 공개 보유의 실현 코인(상대 도우미는 비공개라 제외).
        if (secures(holdings, mineVisible, myNeed)) {
            int oppCash = BotCardEvaluator.projectedCashCoin(combine(oppHoldings, otherVisible), List.of(), null, 0);
            long margin = (long) (myProjected - oppCash) * SECURE_MARGIN_UNIT;
            long total = WIN_SECURE + margin;
            return new ChoiceScore(total,
                    new ScoreBreakdown(summary(mineVisible), 0, 0, 0, 0, 0, 0, margin, total, false));
        }

        int keep = myKeepValue(holdings, mineVisible, tighten, tracker);
        long coinTerm = keep; // myKeepValue 안에 coin·synergy·wild·curse·growth 가 모두 들었다(아래 분해는 로깅용).
        long hiddenTerm = 0;
        if (mine.hasFaceDown()) {
            hiddenTerm = (long) hiddenEv(mineVisible, otherVisible) * (200 - tighten) / 200;
        }
        long wildDeny = otherVisible.stream().anyMatch(Card::isWild) ? -WILD_GRAB : 0;

        int theirCoin = BotCardEvaluator.bestCashCoin(otherVisible) * 100;
        long denyCoin = -((long) theirCoin * tighten * DENY_WEIGHT / 10_000);
        long denySyn = -((long) synergy(oppHoldings, otherVisible) * DENY_WEIGHT / 100);

        long total = coinTerm + hiddenTerm + wildDeny + denyCoin + denySyn;
        ScoreBreakdown breakdown = new ScoreBreakdown(summary(mineVisible),
                coinTerm, 0, hiddenTerm, wildDeny, 0, denyCoin + denySyn, 0, total, false);
        return new ChoiceScore(total, breakdown);
    }

    // ─── 도우미 드래프트(상황화, 로드맵 4.6) ──────────────────────────────────────

    @Override
    public List<HelperCard> decideHelpers(List<HelperCard> options, int chooseCount, HelperDraftContext context) {
        return options.stream()
                .sorted(Comparator.comparingInt((HelperCard h) -> helperDraftScore(h, context)).reversed())
                .limit(chooseCount)
                .toList();
    }

    /** 정적 순위(S7 계승)에 드래프트 상황 보정을 더한다. */
    private int helperDraftScore(HelperCard helper, HelperDraftContext context) {
        int base = switch (helper.kind()) {
            case LUCKY -> 105;
            case ALPHA -> 100;
            case LEO, CUCKOO -> 88;
            case VIPER -> 86;
            case TUSKER -> 80;
            case CROC_BROTHERS -> 70;
            case JUNK_DEALER -> 60;
            case DOUG -> 56;
        };
        boolean solo = context.teamSize() <= 1;
        boolean tightLimit = context.holdLimit() <= 6;
        // 1v1 한도가 빡빡하면 한도를 푸는/저주를 터는 도우미 가치가 오른다.
        if (solo && tightLimit) {
            base += switch (helper.kind()) {
                case TUSKER -> 12;
                case DOUG -> 10;
                case VIPER -> 8;
                default -> 0;
            };
        }
        // LUCKY/CUCKOO 는 같은색 런을 적극 키우는 S8 보류 전략과 결합돼 가치가 오른다.
        if (helper.kind() == HelperKind.LUCKY) base += 6;
        // ALPHA 는 숫자1 추적/몰아주기(2v2 분배)와 결합 시 가치가 오른다.
        if (helper.kind() == HelperKind.ALPHA && context.teamSize() >= 2) base += 8;
        return base;
    }

    // ─── 환금 ─────────────────────────────────────────────────────────────────────

    @Override
    public List<CashInAction> planCashIn(CashInContext context, int opponentCoins) {
        if (DEBUG_SCORES) {
            CashInProjection projection = CashInPlanOptimizer.project(context, opponentCoins, CashInPlanOptimizer.Tuning.S8);
            log("CASH coinsGained=" + projection.coinsGained() + " instantWin=" + projection.instantWin()
                    + " actions=" + projection.actions().size());
            return projection.actions();
        }
        return CashInPlanOptimizer.plan(context, opponentCoins, CashInPlanOptimizer.Tuning.S8);
    }

    // ─── 2v2 팀 분배(로드맵 4.5) ──────────────────────────────────────────────────

    @Override
    public TeamDistribution decideTeamDistribution(List<Card> acquired, List<List<Card>> memberHoldings) {
        return TeamDistributionOptimizer.distribute(acquired, memberHoldings);
    }

    // ─── 평가 보조 ────────────────────────────────────────────────────────────────

    /**
     * 묶음을 내 쪽에 두는 가치. S7 의 coin·synergy·wild·저주 부채에, 카드 카운팅 기반 '살아 있는 성장'
     * 보너스(로드맵 4.2)를 더한다 — 보유+묶음의 최선 세트를 한 장 키울 카드가 아직 살아 있으면(드로우/슬쩍
     * 가능) 코인 증분/예상 턴에 비례해 가치를 올린다. 죽은 카드로만 자라는 세트는 보너스가 0 이다.
     */
    private static int myKeepValue(List<Card> holdings, List<Card> bundle, int tighten, PublicCardTracker tracker) {
        int coin = BotCardEvaluator.bestCashCoin(bundle) * 100;
        int synergy = synergy(holdings, bundle) * (100 - tighten) / 100;
        int wild = wildValue(bundle);
        int curseLiability = curseCount(bundle) * CURSE_LIABILITY;
        int growth = growthBonus(holdings, bundle, tracker) * (100 - tighten) / 100;
        return coin + synergy + wild - curseLiability + growth;
    }

    /** 보유+묶음의 최선 세트를 한 장 키우는 '살아 있는 성장' 보너스. 성장 카드가 죽었으면 0. */
    private static int growthBonus(List<Card> holdings, List<Card> bundle, PublicCardTracker tracker) {
        Optional<TreasureSet> best = SetEvaluator.findBestSet(combine(holdings, bundle));
        if (best.isEmpty()) return 0;
        TreasureSet set = best.get();
        int next = set.type().coin(set.size() + 1);
        if (next == SetType.INVALID) return 0;
        int headroom = next - set.coin();
        if (headroom <= 0) return 0;
        double turns = tracker.expectedTurnsToGrow(set);
        if (turns >= Double.MAX_VALUE / 4) return 0;
        return (int) (GROWTH_WEIGHT * headroom / turns);
    }

    private static int curseCount(List<Card> cards) {
        int count = 0;
        for (Card card : cards) {
            if (card instanceof CursedCard) count++;
        }
        return count;
    }

    private static int standaloneValue(List<Card> bundle, int tighten) {
        int coin = BotCardEvaluator.bestCashCoin(bundle) * 100;
        int potential = BotCardEvaluator.potentialScore(bundle) * (100 - tighten) / 100;
        int wild = wildValue(bundle);
        return coin + potential + wild;
    }

    private static int synergy(List<Card> holdings, List<Card> bundle) {
        return BotCardEvaluator.potentialScore(combine(holdings, bundle))
                - BotCardEvaluator.potentialScore(holdings);
    }

    private static int hiddenEv(List<Card> mineVisible, List<Card> otherVisible) {
        int myVis = BotCardEvaluator.bestCashCoin(mineVisible);
        int otherVis = BotCardEvaluator.bestCashCoin(otherVisible);
        int leanness = (otherVis - myVis) * 100 + (otherVisible.size() - mineVisible.size()) * COUNT_LURE;
        return BASE_HIDDEN_EV + Math.max(0, leanness) * HIDDEN_SUSPICION / 100;
    }

    private static int wildValue(List<Card> cards) {
        return cards.stream().anyMatch(Card::isWild) ? WILD_GRAB : 0;
    }

    private static boolean secures(List<Card> holdings, List<Card> bundle, int myNeed) {
        if (myNeed <= 0) return false;
        return BotCardEvaluator.bestCashCoin(combine(holdings, bundle)) >= myNeed;
    }

    private static List<Card> combine(List<Card> a, List<Card> b) {
        List<Card> combined = new ArrayList<>(a);
        combined.addAll(b);
        return combined;
    }

    private static int need(int winningCoins, int coins) {
        return Math.max(0, winningCoins - coins);
    }

    private int tighten(int myCoins, int opponentCoins, int winningCoins) {
        if (winningCoins <= 0) return 0;
        int closest = Math.min(need(winningCoins, myCoins), need(winningCoins, opponentCoins));
        int endzone = winningCoins * ENDZONE_PCT / 100;
        if (endzone <= 0 || closest >= endzone) return 0;
        return clamp((endzone - closest) * 100 / endzone, 0, 100);
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static List<Card> without(List<Card> cards, Card excluded) {
        List<Card> copy = new ArrayList<>(cards);
        copy.remove(excluded);
        return copy;
    }

    // ─── 디버그 로그 ──────────────────────────────────────────────────────────────

    private static void logChoice(List<ScoreBreakdown> breakdowns, int chosen) {
        StringBuilder sb = new StringBuilder("CHOICE\n");
        for (int i = 0; i < breakdowns.size(); i++) {
            ScoreBreakdown b = breakdowns.get(i);
            boolean isChosen = i == chosen;
            sb.append("  ").append(new ScoreBreakdown(b.label(), b.coin(), b.synergy(), b.hiddenEv(),
                    b.wild(), b.curse(), b.deny(), b.secureMargin(), b.total(), isChosen).toLogLine()).append('\n');
        }
        log(sb.toString().stripTrailing());
    }

    private static synchronized void log(String line) {
        try {
            Files.createDirectories(SCORE_LOG.getParent());
            Files.writeString(SCORE_LOG, line + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 디버그 로깅은 베스트에포트 — 실패해도 봇 동작에 영향 없음.
        }
    }

    private static String summary(List<Card> cards) {
        return cards.stream().map(S8BotStrategy::cardLabel).reduce((a, b) -> a + "," + b).orElse("∅");
    }

    private static String cardLabel(Card card) {
        if (card == null) return "-";
        if (card.isWild()) return "W";
        if (card instanceof TreasureCard t) return t.color().name().charAt(0) + String.valueOf(t.number());
        if (card instanceof CursedCard c) return "X" + c.number();
        return "?";
    }
}
