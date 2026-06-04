package com.oop.payday.net;

import java.io.Serializable;
import java.util.List;

import com.oop.payday.model.set.SetType;

/** 환금 세트의 직렬화 가능한 DTO. */
public record TreasureSetDto(List<CardDto> cards, SetType type, int coin) implements Serializable {}
