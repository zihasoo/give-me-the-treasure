package com.oop.payday.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Random;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CardColor;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.StealCard;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.card.WildCard;

/**
 * 카드 더미 + 버림 더미. 카드 더미가 소진되면 버림 더미를 섞어 새 더미로 만든다(규칙서 §5).
 *
 * <p>구성(총 35장): 일반 보물 28장(4색×1~7) + 굉장한 보물 1장
 * + 슬쩍하기 1장 + 저주받은 그림 5장(숫자 2~6).
 */
public final class Deck {

    private final Deque<Card> drawPile = new ArrayDeque<>();
    private final List<Card> discardPile = new ArrayList<>();
    private final Random random;
    private int nextId = 0;

    public Deck() {
        this(new Random());
    }

    /** 재현 가능한 셔플을 위해 시드를 받는 생성자. */
    public Deck(Random random) {
        this.random = random;
        buildStandardCards();
        buildSpecialCards();
        shuffleDrawPile();
    }

    private void buildStandardCards() {
        for (CardColor color : CardColor.values()) {
            for (int n = TreasureCard.MIN_NUMBER; n <= TreasureCard.MAX_NUMBER; n++) {
                drawPile.add(new TreasureCard(nextId++, color, n));
            }
        }
        drawPile.add(new WildCard(nextId++));
    }

    private void buildSpecialCards() {
        drawPile.add(new StealCard(nextId++));
        for (int n = CursedCard.MIN_NUMBER; n <= CursedCard.MAX_NUMBER; n++) {
            drawPile.add(new CursedCard(nextId++, n));
        }
    }

    private void shuffleDrawPile() {
        List<Card> tmp = new ArrayList<>(drawPile);
        Collections.shuffle(tmp, random);
        drawPile.clear();
        drawPile.addAll(tmp);
    }

    /**
     * 카드 1장을 뽑는다. 더미가 비었으면 버림 더미를 재셔플해 채운 뒤 뽑는다.
     *
     * @return 뽑은 카드, 카드 더미와 버림 더미가 모두 비었으면 {@code null}
     */
    public Card draw() {
        if (drawPile.isEmpty()) {
            reshuffleFromDiscard();
        }
        return drawPile.pollFirst();
    }

    /** 카드 {@code n}장을 뽑아 리스트로 반환한다(부족하면 가능한 만큼). */
    public List<Card> draw(int n) {
        List<Card> drawn = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Card c = draw();
            if (c == null) {
                break;
            }
            drawn.add(c);
        }
        return drawn;
    }

    public void discard(Card card) {
        discardPile.add(card);
    }

    public void discard(List<Card> cards) {
        discardPile.addAll(cards);
    }

    private void reshuffleFromDiscard() {
        if (discardPile.isEmpty()) {
            return;
        }
        Collections.shuffle(discardPile, random);
        drawPile.addAll(discardPile);
        discardPile.clear();
    }

    /**
     * 슬쩍하기 처리: 주어진 카드와 버림 더미를 모두 카드 더미에 합친 뒤 전체를 다시 섞는다
     * (규칙서 §3-1). 이후 호출부가 {@link #draw()} 로 1장을 뽑는다.
     */
    public void absorbDiscardWith(Card card) {
        drawPile.add(card);
        drawPile.addAll(discardPile);
        discardPile.clear();
        shuffleDrawPile();
    }

    public int drawPileSize() {
        return drawPile.size();
    }

    public int discardPileSize() {
        return discardPile.size();
    }

    /** 현재 버림 더미의 읽기 전용 뷰(슬쩍하기 등 특수 효과 처리용, M5). */
    public List<Card> discardView() {
        return Collections.unmodifiableList(discardPile);
    }

    /** 버림 더미에 있는 굉장한 보물을 꺼낸다(검은 날개 고물상 효과). */
    public Card takeWildFromDiscard() {
        for (int i = 0; i < discardPile.size(); i++) {
            Card card = discardPile.get(i);
            if (card.isWild()) {
                return discardPile.remove(i);
            }
        }
        return null;
    }
}
