package com.oop.payday.view;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * 규칙서 패널 빌더. {@code docs/도적단의_월급날_규칙.md} 를 사람이 읽기 좋게 접이식 섹션
 * (Accordion + TitledPane)으로 재구성한다. 메인 메뉴와 게임 중 ESC 메뉴에서 공용으로 사용한다.
 *
 * <p>본문은 {@link TextFlow} 로 그려, 문장 안의 {@code **...**} 구간만 강조(볼드+금빛)한다.
 * {@link ScoreTableBuilder} 와 동일하게 정적 빌더로 동작하며, "닫기" 버튼이 {@code onClose} 를 호출한다.
 */
public final class RulebookBuilder {

    private RulebookBuilder() {}

    public static Node build(Runnable onClose) {
        VBox root = new VBox(14);
        root.getStyleClass().add("rulebook-panel");
        root.setAlignment(Pos.TOP_CENTER);
        root.setMaxWidth(760);
        root.setMaxHeight(640);

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("규칙서");
        title.getStyleClass().add("rulebook-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button close = new Button("닫기");
        close.getStyleClass().add("score-table-close");
        close.setOnAction(e -> onClose.run());
        header.getChildren().addAll(title, spacer, close);

        Accordion accordion = new Accordion();
        accordion.getPanes().addAll(
                overviewSection(),
                teamSection(),
                roundFlowSection(),
                phaseSection(),
                setsSection(),
                specialCardSection(),
                officerSection(),
                helperSection());
        accordion.setExpandedPane(accordion.getPanes().get(0));

        ScrollPane scroll = new ScrollPane(accordion);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("rulebook-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        root.getChildren().addAll(header, scroll);
        return root;
    }

    // ── 섹션 ─────────────────────────────────────────────────────────

    private static TitledPane overviewSection() {
        return section("1. 게임 개요 · 승리 조건", body(
                "2~4명이 항상 두 팀으로 나뉘어 대결하는 심리·블러핑 게임입니다.",
                "한 팀(**분할 팀**)이 보물 5장을 두 묶음으로 나누되 1장은 뒷면(비공개)으로 두고, 상대 팀(**선택 팀**)이 한 묶음을 고릅니다. 양 팀은 가져간 보물을 세트로 환금해 코인을 얻고, 라운드마다 역할을 교대합니다.",
                "**승리**: 종료 단계에 30코인 이상인 팀이 있으면, 그 순간 코인이 더 많은 팀이 승리합니다.",
                "연습 룰: 첫 플레이 시 20코인 승리 + 리더 효과 비활성화를 추천합니다.",
                "상대의 \"꾀\"를 간파하는 것이 핵심입니다."));
    }

    private static TitledPane teamSection() {
        return section("2. 팀 구성", body(
                "• 4인 → **2 vs 2**",
                "• 3인 → **1 vs 2**",
                "• 2인 → **1 vs 1**",
                "같은 팀끼리는 게임 내내 자유롭게 논의할 수 있습니다.",
                "팀 인원수에 따라 **보물 보유 한도**가 달라집니다(아래 4번 참고)."));
    }

    private static TitledPane roundFlowSection() {
        return section("3. 라운드 흐름", body(
                "게임은 여러 라운드로 진행됩니다. 각 라운드는 4단계로 이루어집니다.",
                "**꾀부리기 → 분배 → 환금 → 종료**",
                "라운드가 끝나면 분할 팀과 선택 팀의 역할을 교대합니다.",
                "카드 더미가 소진되면 버림 더미를 섞어 새 더미로 만든 뒤 마저 뽑습니다."));
    }

    private static TitledPane phaseSection() {
        return section("4. 단계별 상세", body(
                "**[꾀부리기]** 분할 팀 리더가 더미에서 5장을 뽑아, 2+3 또는 1+4 로 두 묶음으로 나눕니다. 정확히 1장만 뒷면(비공개)으로 둡니다.",
                "**[분배]** 선택 팀이 한 묶음을 고릅니다. 고른 묶음은 선택 팀이, 나머지는 분할 팀이 가져가고 뒷면 카드를 공개합니다. 가져간 카드에 슬쩍하기가 있으면 즉시 처리합니다.",
                "**[환금]** (모든 플레이어 동시 진행) 아래 행동을 원하는 순서·횟수로 수행합니다.",
                "  · **환금**: 보관 카드로 세트를 만들어 버림 더미에 내고 코인을 얻습니다(팀이 공유).",
                "  · **처분**: 카드 1장을 버립니다. (저주받은 그림은 2코인 지불)",
                "  · **도움 요청**: 비공개 도우미를 뒤집어 효과를 사용합니다.",
                "**[종료]** 30코인(연습 20) 이상인 팀이 있으면 코인이 더 많은 팀이 승리합니다. 그렇지 않으면 보유 한도를 초과한 카드를 강제 처분합니다.",
                "**보유 한도** — 1인 팀: 5장 / 2인 팀: 리더 3장, 멤버 4장."));
    }

    private static TitledPane setsSection() {
        return section("5. 세트 종류와 환금표", body(
                "환금할 수 있는 세트는 3종류입니다.",
                "• **같은 숫자**: 숫자가 같은 카드 2~4장 (같은 숫자는 색이 모두 다름).",
                "• **연속된 숫자**: 숫자가 연속된 카드 3~5장 (색 무관). 7→1 순환은 불가합니다(예: 7,1,2 안됨).",
                "• **연속 + 같은 색**: 숫자가 연속이면서 색도 같은 3~5장. 일반 연속보다 코인을 더 많이 얻습니다.",
                "세트 장수별 코인(환금표)은 메인 메뉴와 게임 화면의 '조합표'에서 확인할 수 있습니다."));
    }

    private static TitledPane specialCardSection() {
        return section("6. 특수 카드", body(
                "일반 보물은 4색(노랑·빨강·청록·파랑) × 숫자 1~7 입니다. 그 외 특수 카드가 있습니다.",
                "• **굉장한 보물**(와일드): 원하는 색·숫자의 보물로 간주해 세트에 사용할 수 있습니다.",
                "• **슬쩍하기**: 획득 즉시 이 카드와 버림 더미를 모두 카드 더미에 섞은 뒤, 그 팀이 1장을 뽑아 가져옵니다.",
                "• **저주받은 그림**(5장, 숫자 2~6): 세트에 넣을 수 없습니다. 처분하려면 2코인을 내야 하지만, 카드와 같은 숫자의 보물이 포함된 세트를 환금할 때는 코인 없이 1장을 무료 처분할 수 있습니다."));
    }

    private static TitledPane officerSection() {
        return section("7. 간부 리더 효과", body(
                "리더 토큰이 올라간 간부 타일에서만 효과가 발동합니다(연습 룰에서는 비활성화).",
                "• **플랭키**: 보물 보유 한도가 1장 더 많아집니다.",
                "• **척**: 한 라운드 동안 2번 이상 환금했다면 1코인을 얻습니다.",
                "• **와이즈**: 환금 단계가 끝났을 때 상대 팀이 저주받은 그림을 2장 이상 보유 중이면 1코인을 얻습니다.",
                "• **죠**: 6코인 이상에 해당하는 세트를 환금했다면 1코인을 얻습니다."));
    }

    private static TitledPane helperSection() {
        return section("8. 도우미 카드", body(
                "도우미는 환금 단계의 '도움 요청'으로 발동합니다(이미 사용한 도우미는 다시 쓸 수 없음).",
                "• **음속의 쿠쿠**: 같은 색 3장 이상 세트를 환금했을 때 3코인.",
                "• **닌자 레오**: 같은 숫자 3장 이상 세트를 환금했을 때 3코인.",
                "• **명견 럭키**: 같은 색 5장(연속+같은색) 세트를 환금했을 때 7코인.",
                "• **경찰견 알파**: 굉장한 보물 없이 숫자 1 보물 4장 세트를 환금하면 즉시 승리.",
                "• **샛길의 더그**: 저주가 아닌 보관 보물을 원하는 만큼 처분한 뒤 그만큼 더미에서 뽑습니다.",
                "• **완력의 투스커**: 더미에서 1장을 뽑고, 다음 라운드까지 보유 한도가 사라집니다.",
                "• **척후 바이퍼**: 저주받은 그림을 코인 없이 모두 처분하고, 처분한 장수만큼 코인을 얻습니다.",
                "• **검은 날개 고물상**: 버림 더미의 굉장한 보물을 가져옵니다. 단 이번 라운드엔 환금할 수 없습니다.",
                "• **크록 형제**: 앞면으로 놓인 도우미 1장의 효과를 골라 따라 사용합니다."));
    }

    // ── 공통 헬퍼 ─────────────────────────────────────────────────────

    private static TitledPane section(String title, Node content) {
        TitledPane pane = new TitledPane(title, content);
        pane.getStyleClass().add("rulebook-section");
        pane.setAnimated(false);
        return pane;
    }

    private static VBox body(String... paragraphs) {
        VBox box = new VBox(8);
        box.getStyleClass().add("rulebook-body");
        for (String paragraph : paragraphs) {
            box.getChildren().add(paragraphFlow(paragraph));
        }
        return box;
    }

    /** 한 문단을 TextFlow 로 만든다. {@code **...**} 구간만 강조(rulebook-strong)로 분리한다. */
    private static TextFlow paragraphFlow(String markup) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().add("rulebook-paragraph");
        String[] parts = markup.split("\\*\\*", -1);
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            Text text = new Text(parts[i]);
            text.getStyleClass().add(i % 2 == 1 ? "rulebook-strong" : "rulebook-text");
            flow.getChildren().add(text);
        }
        return flow;
    }
}
