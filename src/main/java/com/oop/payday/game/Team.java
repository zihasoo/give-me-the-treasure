package com.oop.payday.game;

import java.util.List;

import com.oop.payday.player.Player;

/**
 * 두 팀 대결의 한 팀. 코인은 팀이 공유한다(규칙서 §6-3).
 *
 * <p>1v1 에서는 멤버가 1명. 다인 팀의 리더/멤버 구분(보유 한도·리더 효과)은
 * 첫 멤버를 리더로 두는 것으로 시작하고 추후 확장한다.
 */
public final class Team {

    private final String name;
    private final List<Player> members;
    private int coins;

    public Team(String name, List<Player> members) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("팀에는 최소 1명이 필요합니다.");
        }
        this.name = name;
        this.members = List.copyOf(members);
    }

    public String name() {
        return name;
    }

    public List<Player> members() {
        return members;
    }

    /** 라운드 주도권/도우미 등에서 대표가 필요할 때 쓰는 리더(현재는 첫 멤버). */
    public Player leader() {
        return members.get(0);
    }

    public int coins() {
        return coins;
    }

    public void addCoins(int amount) {
        coins += amount;
    }

    /** 코인을 차감한다(저주받은 그림 처분 등). 음수가 되지 않도록 0 에서 멈춘다. */
    public void spendCoins(int amount) {
        coins = Math.max(0, coins - amount);
    }

    @Override
    public String toString() {
        return name + "(" + coins + "코인)";
    }
}
