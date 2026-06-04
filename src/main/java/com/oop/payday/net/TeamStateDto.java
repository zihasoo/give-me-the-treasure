package com.oop.payday.net;

import java.io.Serializable;
import java.util.List;

/** 팀 한 개의 공개 상태 스냅샷. */
public record TeamStateDto(int teamId, String name, int coins, List<PlayerStateDto> members)
        implements Serializable {}
