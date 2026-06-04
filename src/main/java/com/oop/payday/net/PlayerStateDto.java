package com.oop.payday.net;

import java.io.Serializable;
import java.util.List;

import com.oop.payday.model.officer.OfficerTile;

/** 플레이어 한 명의 공개 상태 스냅샷. */
public record PlayerStateDto(
        int playerId,
        String name,
        OfficerTile officer,
        boolean leader,
        int holdLimit,
        int holdingCount,
        List<CardDto> holdings,
        List<HelperDto> helpers) implements Serializable {}
