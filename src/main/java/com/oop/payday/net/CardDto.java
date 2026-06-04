package com.oop.payday.net;

import java.io.Serializable;

import com.oop.payday.model.card.CardColor;

/**
 * 카드 한 장의 직렬화 가능한 데이터 전송 객체.
 * type: "TREASURE" | "WILD" | "STEAL" | "CURSED"
 * color/number: TREASURE·CURSED 에서만 유효, 나머지는 null/0.
 */
public record CardDto(int id, String type, CardColor color, int number) implements Serializable {

    public static final String TREASURE = "TREASURE";
    public static final String WILD     = "WILD";
    public static final String STEAL    = "STEAL";
    public static final String CURSED   = "CURSED";
}
