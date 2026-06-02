package com.oop.payday.decision;

import java.util.List;

/**
 * 분배 단계에서 선택 팀에게 제시되는 두 묶음의 모습. 인덱스 0 또는 1을 고른다.
 */
public record ChoiceView(List<BundleView> bundles) {

    public ChoiceView {
        bundles = List.copyOf(bundles);
    }

    public BundleView bundle(int index) {
        return bundles.get(index);
    }
}
