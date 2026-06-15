package com.oop.payday.net;

import java.io.Serializable;
import java.util.List;

/** 클라이언트에게 전달하는 환금 의사결정 컨텍스트 DTO. */
public record CashInContextDto(
        List<CardDto> holdings,
        List<HelperDto> helpers,
        List<HelperDto> usedHelpers,
        List<CardDto> discardPile,
        int teamCoins,
        int holdLimit,
        int winningCoins,
        List<CardDto> opponentHoldings) implements Serializable {}
