package com.oop.payday.controller;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.helper.HelperKind;
import com.oop.payday.model.helper.HelperRules;
import com.oop.payday.model.set.CashInEvaluator;
import com.oop.payday.model.set.SetType;
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.player.Player;
import com.oop.payday.view.CardView;
import com.oop.payday.view.Panels;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 환금 페이즈의 중앙 입력 패널과 그 상태를 담당하는 뷰 보조 컴포넌트.
 *
 * <p>{@code GameBoardController} 에서 분리해 컨트롤러 비대화를 줄였다({@link BoardAnimator} 와 같은
 * 패턴). 선택 카드·도우미 토글·미리보기 라벨 등 환금 진행 상태는 이 클래스가 소유하고, 컨트롤러는
 * 얇은 위임으로 호출한다. 컨트롤러에 의존하는 부분은 생성자로 받은 콜백뿐이라(현재 입력 창구·중앙 전환·
 * 보드 갱신) 결합이 작다. 카드 선택 UI 는 중앙 패널이 아니라 내 필드에 붙으므로, 필드 렌더링은
 * {@link #isFieldSelectable}/{@link #selectableCards}/{@link #isSelected}/{@link #handleCardClick} 로 연동한다.
 */
final class CashInPanel {

    private final Supplier<InputGateway> gateway;       // 세션마다 교체되므로 호출 시점에 읽는다
    private final Consumer<Node> setCenterAnimated;     // 호출 시점의 BoardAnimator 로 위임
    private final Runnable updateBoardStatus;           // 선택 변경 시 내 필드 재렌더

    private final Set<Card> cashSelection = new LinkedHashSet<>();          // 환금 패널 카드 선택(재렌더 사이 보존)
    private final Set<HelperCard> cashSelectedHelpers = new LinkedHashSet<>(); // 콤보 도우미 토글 상태
    private boolean cashPhaseActive;
    private Label cashPreviewLabel;
    private Label cashHoldLimitLabel;
    private HBox cashHelperTogglesBox;
    private HBox cashHelperButtonsBox;
    private List<Card> cashRemaining;
    private CashInContext cashCashContext;

    CashInPanel(Supplier<InputGateway> gateway, Consumer<Node> setCenterAnimated, Runnable updateBoardStatus) {
        this.gateway = gateway;
        this.setCenterAnimated = setCenterAnimated;
        this.updateBoardStatus = updateBoardStatus;
    }

    // ===== 필드 렌더링 연동 (내 필드의 카드 선택 UI) =====

    /** 이 필드(가 내 필드이고 내 카드)에 환금 선택 UI 를 붙여야 하는지. */
    boolean isFieldSelectable(boolean isFieldA, boolean isLocal) {
        return cashPhaseActive && cashRemaining != null && cashCashContext != null && isFieldA && isLocal;
    }

    /** 선택 가능한 필드에 그릴 카드 목록(보관 카드 대신 환금 컨텍스트의 잔여 카드). */
    List<Card> selectableCards() {
        return cashRemaining;
    }

    boolean isSelected(Card card) {
        return cashSelection.contains(card);
    }

    void handleCardClick(CardView cardView, Card card) {
        cardView.toggleSelected();
        if (cardView.isSelected()) {
            cashSelection.add(card);
        } else {
            cashSelection.remove(card);
        }
        updateCashInPreview(cashSelection, cashCashContext.helpers());
        refreshCashHelperToggles(cashCashContext.helpers());
    }

    // ===== 패널 표시/갱신 =====

    /** 환금 행동 한 건을 큐에 제출한다. onCashTurn 콜백이 패널을 증분 갱신한다. */
    private void submit(CashInAction action) {
        cashSelection.clear();
        gateway.get().submitCash(action);
    }

    /** 환금 패널 증분 업데이트 참조를 초기화한다. 페이즈 전환·완료 시 호출. */
    void reset() {
        cashPhaseActive = false;
        cashPreviewLabel = null;
        cashHoldLimitLabel = null;
        cashHelperTogglesBox = null;
        cashHelperButtonsBox = null;
        cashRemaining = null;
        cashCashContext = null;
        cashSelection.clear();
        cashSelectedHelpers.clear();
    }

    /**
     * 환금 패널을 표시하거나 갱신한다.
     * 패널이 이미 활성 중이면 레이블·버튼만 갱신(전환 없음),
     * 처음 표시할 때만 전체 구조를 빌드하고 fade-in 전환을 수행한다.
     */
    void render(Player player, CashInContext context, List<Card> remaining) {
        boolean alreadyActive = cashPhaseActive;
        cashPhaseActive = true;
        cashRemaining = remaining;
        cashCashContext = context;
        cashSelection.retainAll(remaining);
        cashSelectedHelpers.retainAll(context.helpers());
        updateBoardStatus.run();

        if (alreadyActive) {
            updateCashPanelContent(player, context, remaining);
            return;
        }
        buildCashPanel(player, context, remaining);
    }

    /** 환금 컨트롤 최초 빌드 (구조 전체 생성 + fade-in 전환). */
    private void buildCashPanel(Player player, CashInContext context, List<Card> remaining) {
        VBox root = Panels.panelRoot("내 필드에서 카드를 선택해 환금하거나, 카드를 처분/도움 요청하세요. 끝나면 '턴 종료'.");
        root.getStyleClass().add("cash-panel");
        root.setSpacing(10);
        root.setPadding(new Insets(16));
        root.setMaxHeight(Double.MAX_VALUE);
        root.setMinHeight(0);

        cashHoldLimitLabel = new Label(holdLimitText(player, remaining));
        applyHoldLimitStyle(cashHoldLimitLabel, player, remaining);
        HBox infoRow = new HBox(cashHoldLimitLabel);
        infoRow.setAlignment(Pos.CENTER_LEFT);

        cashHelperTogglesBox = new HBox(6);
        cashHelperTogglesBox.setAlignment(Pos.CENTER_LEFT);

        updateCashInPreview(cashSelection, context.helpers());
        refreshCashHelperToggles(context.helpers());

        cashPreviewLabel = new Label("선택된 카드: 없음");
        cashPreviewLabel.getStyleClass().add("preview");
        updateCashInPreview(cashSelection, context.helpers());

        Button cashBtn = new Button("환금");
        cashBtn.getStyleClass().add("menu-button");
        cashBtn.setOnAction(e -> {
            List<Card> chosen = new ArrayList<>(cashSelection);
            Optional<CashInEvaluator.Result> evaluation = CashInEvaluator.evaluate(chosen);
            if (evaluation.isEmpty()) {
                Panels.alert("선택한 카드는 유효한 세트가 아닙니다. 저주받은 그림은 같은 숫자 보물이 포함된 세트와 함께만 무료 처분할 수 있습니다.");
                return;
            }
            List<HelperCard> cashHelpers = cashSelectedHelpers.stream()
                    .filter(helper -> canAttachToCash(helper, evaluation.get().set()))
                    .toList();
            CashInAction action = cashHelpers.isEmpty()
                    ? new CashInAction.Cash(chosen)
                    : new CashInAction.CashWithHelpers(chosen, cashHelpers);
            submit(action);
        });

        Button discardBtn = new Button("처분");
        discardBtn.getStyleClass().add("menu-button");
        discardBtn.setOnAction(e -> {
            if (cashSelection.isEmpty()) {
                Panels.alert("처분할 카드를 선택하세요.");
                return;
            }
            List<Card> toDiscard = new ArrayList<>(cashSelection);
            cashSelection.clear();
            for (Card c : toDiscard) {
                gateway.get().submitCash(new CashInAction.Discard(c));
            }
        });

        Button doneBtn = new Button("턴 종료");
        doneBtn.getStyleClass().add("menu-button");
        doneBtn.setOnAction(e -> {
            if (!player.isHoldLimitSuspended() && cashRemaining.size() > player.holdLimit()) {
                Panels.alert("보유 한도를 초과해서 턴을 종료할 수 없습니다. 현재 "
                        + cashRemaining.size() + "장 / 한도 " + player.holdLimit()
                        + "장입니다. 환금하거나 처분해 한도 이하로 맞춰주세요.");
                return;
            }
            cashSelection.clear();
            gateway.get().passCash();
        });

        cashHelperButtonsBox = new HBox(8);
        cashHelperButtonsBox.setAlignment(Pos.CENTER_LEFT);
        refreshCashHelperButtons(context, remaining);

        Region btnSpacer = new Region();
        HBox.setHgrow(btnSpacer, Priority.ALWAYS);
        HBox bottomBar = new HBox(8, cashHelperTogglesBox, cashHelperButtonsBox, btnSpacer, cashBtn, discardBtn, doneBtn);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.getStyleClass().add("cash-bottom-bar");

        VBox controls = new VBox(8, infoRow, cashPreviewLabel, bottomBar);
        controls.setAlignment(Pos.BOTTOM_CENTER);
        controls.setFillWidth(true);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        root.getChildren().addAll(spacer, controls);
        setCenterAnimated.accept(root);
    }

    /** 패널이 이미 화면에 있을 때 변화하는 부분만 갱신 (전환 없음). */
    private void updateCashPanelContent(Player player, CashInContext context, List<Card> remaining) {
        cashHoldLimitLabel.setText(holdLimitText(player, remaining));
        applyHoldLimitStyle(cashHoldLimitLabel, player, remaining);
        updateCashInPreview(cashSelection, context.helpers());
        refreshCashHelperToggles(context.helpers());
        refreshCashHelperButtons(context, remaining);
    }

    private void applyHoldLimitStyle(Label label, Player player, List<Card> remaining) {
        label.getStyleClass().removeAll("limit-warning", "preview");
        label.getStyleClass().add(
                remaining.size() > player.holdLimit() && !player.isHoldLimitSuspended()
                        ? "limit-warning" : "preview");
    }

    /** 콤보형 도우미 토글 버튼(cashHelperTogglesBox)을 재구성한다. */
    private void refreshCashHelperToggles(List<HelperCard> helpers) {
        cashHelperTogglesBox.getChildren().clear();
        Optional<TreasureSet> selectedSet = CashInEvaluator.evaluate(new ArrayList<>(cashSelection))
                .map(CashInEvaluator.Result::set);
        for (HelperCard helper : helpers) {
            if (!HelperRules.isCashReaction(helper.kind()) || helper.isUsed()) {
                continue;
            }
            ToggleButton toggle = new ToggleButton(helper.displayName());
            toggle.getStyleClass().add("helper-action-button");
            boolean usable = selectedSet.isPresent() && canAttachToCash(helper, selectedSet.get());
            toggle.setDisable(!usable);
            if (!usable) {
                cashSelectedHelpers.remove(helper);
            }
            toggle.setSelected(usable && cashSelectedHelpers.contains(helper));
            toggle.setOnAction(e -> {
                if (toggle.isSelected()) {
                    cashSelectedHelpers.add(helper);
                } else {
                    cashSelectedHelpers.remove(helper);
                }
            });
            cashHelperTogglesBox.getChildren().add(toggle);
        }
    }

    /** 독립 사용 도우미 버튼(cashHelperButtonsBox)을 재구성한다. */
    private void refreshCashHelperButtons(CashInContext context, List<Card> remaining) {
        cashHelperButtonsBox.getChildren().clear();
        for (HelperCard helper : context.helpers()) {
            if (HelperRules.isCashReaction(helper.kind())) {
                continue;
            }
            Button helperBtn = new Button(helper.displayName());
            helperBtn.getStyleClass().add("helper-action-button");
            helperBtn.setDisable(standaloneDisabledReason(helper, context, remaining) != null);
            helperBtn.setOnAction(e -> {
                CashInAction action;
                if (helper.kind() == HelperKind.DOUG) {
                    // 샛길의 더그: 선택한 보물(저주 제외)만 버리고 그만큼 드로우(규칙서 §3-3).
                    List<Card> toDiscard = cashSelection.stream()
                            .filter(c -> !(c instanceof CursedCard))
                            .toList();
                    if (toDiscard.isEmpty()) {
                        Panels.alert("샛길의 더그로 버릴 보물을 먼저 선택하세요(저주받은 그림 제외).");
                        return;
                    }
                    action = new CashInAction.UseHelper(helper, null, toDiscard);
                } else if (helper.kind() == HelperKind.CROC_BROTHERS) {
                    Optional<HelperCard> target = chooseCrocTarget(cashCashContext, cashRemaining);
                    if (target.isEmpty()) {
                        return;
                    }
                    action = new CashInAction.UseHelper(helper, target.get());
                } else {
                    action = new CashInAction.UseHelper(helper);
                }
                submit(action);
            });
            cashHelperButtonsBox.getChildren().add(helperBtn);
        }
    }

    private boolean canAttachToCash(HelperCard helper, TreasureSet set) {
        return !helper.isUsed()
                && HelperRules.isCashReaction(helper.kind())
                && matchesCashReaction(helper.kind(), set);
    }

    private boolean matchesCashReaction(HelperKind kind, TreasureSet set) {
        return switch (kind) {
            case CUCKOO -> set.type() == SetType.RUN_SAME_COLOR && set.size() >= 3;
            case LEO -> set.type() == SetType.SAME_NUMBER && set.size() >= 3;
            case LUCKY -> set.type() == SetType.RUN_SAME_COLOR && set.size() == 5;
            case ALPHA -> set.size() == 4
                    && set.cards().stream().noneMatch(Card::isWild)
                    && set.cards().stream().allMatch(c -> c instanceof TreasureCard t && t.number() == 1);
            default -> false;
        };
    }

    private String standaloneDisabledReason(HelperCard helper, CashInContext context, List<Card> remaining) {
        if (helper.isUsed()) {
            return "이미 사용한 도우미입니다.";
        }
        return switch (helper.kind()) {
            case DOUG -> remaining.stream().anyMatch(c -> !(c instanceof CursedCard))
                    ? null : "저주가 아닌 보관 카드가 필요합니다.";
            case TUSKER -> null;
            case VIPER -> remaining.stream().anyMatch(CursedCard.class::isInstance)
                    ? null : "저주받은 그림을 보유해야 합니다.";
            case JUNK_DEALER -> context.discardPile().stream().anyMatch(Card::isWild)
                    ? null : "버림 더미에 굉장한 보물이 있어야 합니다.";
            case CROC_BROTHERS -> crocCopyTargets(context, remaining).isEmpty()
                    ? "복사 가능한 사용 완료 도우미가 없습니다." : null;
            default -> "환금할 세트에 붙여 사용하는 도우미입니다.";
        };
    }

    private Optional<HelperCard> chooseCrocTarget(CashInContext context, List<Card> remaining) {
        List<HelperCard> targets = crocCopyTargets(context, remaining);
        if (targets.isEmpty()) {
            Panels.alert("복사할 수 있는 사용 완료 도우미가 없습니다.");
            return Optional.empty();
        }
        ChoiceDialog<HelperCard> dialog = new ChoiceDialog<>(targets.get(0), targets);
        dialog.setTitle("크록 형제");
        dialog.setHeaderText("복사할 도우미를 선택하세요.");
        dialog.setContentText("복사 대상");
        return dialog.showAndWait();
    }

    private List<HelperCard> crocCopyTargets(CashInContext context, List<Card> remaining) {
        return context.usedHelpers().stream()
                .filter(helper -> helper.kind() != HelperKind.CROC_BROTHERS)
                .filter(helper -> canCopyInPreview(helper.kind(), null, remaining, context.discardPile()))
                .toList();
    }

    private boolean canCopyInPreview(HelperKind kind, TreasureSet lastSet, List<Card> remaining,
            List<Card> discardPile) {
        return switch (kind) {
            case CUCKOO, LEO, LUCKY, ALPHA -> lastSet != null && matchesCashReaction(kind, lastSet);
            case DOUG -> remaining.stream().anyMatch(c -> !(c instanceof CursedCard));
            case TUSKER -> true;
            case VIPER -> remaining.stream().anyMatch(CursedCard.class::isInstance);
            case JUNK_DEALER -> discardPile.stream().anyMatch(Card::isWild);
            case CROC_BROTHERS -> false;
        };
    }

    private String holdLimitText(Player player, List<Card> remaining) {
        String suffix;
        if (player.isHoldLimitSuspended()) {
            suffix = " · 보유 한도 무시";
        } else if (remaining.size() > player.holdLimit()) {
            suffix = " · 턴 종료 불가";
        } else {
            suffix = "";
        }
        return "보유 한도: " + remaining.size() + " / " + player.holdLimit() + suffix;
    }

    private void updateCashInPreview(Set<Card> selected, List<HelperCard> helpers) {
        if (cashPreviewLabel == null) {
            return;
        }
        if (selected.isEmpty()) {
            cashPreviewLabel.setText("선택된 카드: 없음");
            return;
        }
        Optional<CashInEvaluator.Result> evaluation = CashInEvaluator.evaluate(new ArrayList<>(selected));
        if (evaluation.isPresent()) {
            TreasureSet set = evaluation.get().set();
            String cursed = evaluation.get().hasFreeCursedCards()
                    ? " · 저주 " + evaluation.get().freeCursedCards().size() + "장 무료 처분"
                    : "";
            List<String> usableHelpers = helpers.stream()
                    .filter(h -> !h.isUsed() && HelperRules.isCashReaction(h.kind()) && canAttachToCash(h, set))
                    .map(HelperCard::displayName)
                    .toList();
            String helperSuffix = usableHelpers.isEmpty() ? ""
                    : "  +" + String.join(", ", usableHelpers) + " 사용 가능";
            cashPreviewLabel.setText("환금 가능: " + set.type().korean() + " " + set.size()
                    + "장 → " + set.coin() + "코인" + cursed + helperSuffix);
        } else {
            cashPreviewLabel.setText("선택 " + selected.size() + "장 — 유효한 세트 아님");
        }
    }
}
