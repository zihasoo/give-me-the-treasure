package com.oop.payday.controller;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.function.Predicate;

import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.WildCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.player.Player;
import com.oop.payday.view.CardView;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * 보드 화면의 <b>오버레이 큐 · 가운데(center) 영역 전환 · 발동 연출</b>을 담당하는 뷰 보조 컴포넌트.
 *
 * <p>{@code GameBoardController} 에서 분리해 컨트롤러 비대화를 줄였다. 큐·센터 상태는 이 클래스가
 * 소유하며, 컨트롤러는 얇은 위임으로 호출한다. 컨트롤러에 의존하는 부분은 생성자로 받은
 * 노드와 콜백({@code isLocalActor}, 카드 정렬)뿐이라 결합이 작다.
 */
final class BoardAnimator {

    private final StackPane contentArea;
    private final Pane globalOverlay;
    private final StackPane centerArea;
    private final Predicate<Player> isLocalActor;
    private final Comparator<Card> cardOrder;

    private final Queue<Runnable> overlayQueue = new ArrayDeque<>();
    private final Queue<Runnable> afterOverlayQueue = new ArrayDeque<>();
    private boolean overlayPlaying;
    private int centerRevision;
    private FadeTransition activeCenterTransition;
    private boolean opponentWaitingVisible;

    BoardAnimator(StackPane contentArea, Pane globalOverlay, StackPane centerArea,
            Predicate<Player> isLocalActor, Comparator<Card> cardOrder) {
        this.contentArea = contentArea;
        this.globalOverlay = globalOverlay;
        this.centerArea = centerArea;
        this.isLocalActor = isLocalActor;
        this.cardOrder = cardOrder;
    }

    // ===== 오버레이 큐 =====

    void enqueueOverlay(Runnable animation) {
        overlayQueue.add(animation);
        if (!overlayPlaying) {
            playNextOverlay();
        }
    }

    void playNextOverlay() {
        Runnable next = overlayQueue.poll();
        if (next == null) {
            overlayPlaying = false;
            flushAfterOverlayQueue();
            return;
        }
        overlayPlaying = true;
        next.run();
    }

    void runAfterOverlay(Runnable action) {
        if (overlayPlaying || !overlayQueue.isEmpty()) {
            afterOverlayQueue.add(action);
            return;
        }
        action.run();
    }

    private void flushAfterOverlayQueue() {
        while (!afterOverlayQueue.isEmpty()) {
            afterOverlayQueue.poll().run();
        }
    }

    // ===== 가운데(center) 영역 =====

    boolean isOpponentWaiting() {
        return opponentWaitingVisible;
    }

    void clearOpponentWaiting() {
        opponentWaitingVisible = false;
    }

    void clearCenterIfOpponentWaiting() {
        if (opponentWaitingVisible) {
            clearCenter();
        }
    }

    void clearCenter() {
        setCenter(new StackPane());
    }

    void setCenter(Node node) {
        opponentWaitingVisible = false;
        centerRevision++;
        stopActiveCenterTransition();
        node.setOpacity(1);
        contentArea.getChildren().setAll(node);
    }

    void setCenterAnimated(Node node) {
        setCenterAnimated(node, false);
    }

    void setCenterAnimated(Node node, boolean opponentWaiting) {
        opponentWaitingVisible = opponentWaiting;
        int revision = ++centerRevision;
        stopActiveCenterTransition();
        if (contentArea.getChildren().isEmpty()) {
            node.setOpacity(0);
            contentArea.getChildren().setAll(node);
            playCenterFadeIn(node, revision);
            return;
        }
        Node old = contentArea.getChildren().get(0);
        FadeTransition out = fade(old, old.getOpacity(), 0, 220);
        activeCenterTransition = out;
        out.setOnFinished(e -> {
            if (revision != centerRevision) {
                return;
            }
            node.setOpacity(0);
            contentArea.getChildren().setAll(node);
            playCenterFadeIn(node, revision);
        });
        out.play();
    }

    private void playCenterFadeIn(Node node, int revision) {
        FadeTransition in = fade(node, 0, 1, 280);
        activeCenterTransition = in;
        in.setOnFinished(e -> {
            if (revision == centerRevision) {
                activeCenterTransition = null;
            }
        });
        in.play();
    }

    private void stopActiveCenterTransition() {
        if (activeCenterTransition != null) {
            activeCenterTransition.stop();
            activeCenterTransition = null;
        }
    }

    FadeTransition fade(Node node, double from, double to, int millis) {
        FadeTransition transition = new FadeTransition(Duration.millis(millis), node);
        transition.setFromValue(from);
        transition.setToValue(to);
        return transition;
    }

    private SequentialTransition buildCardFlip(StackPane container, Node front) {
        ScaleTransition shrink = new ScaleTransition(Duration.millis(180), container);
        shrink.setToX(0);
        PauseTransition swap = new PauseTransition(Duration.millis(10));
        swap.setOnFinished(e -> container.getChildren().setAll(front));
        ScaleTransition grow = new ScaleTransition(Duration.millis(200), container);
        grow.setFromX(0);
        grow.setToX(1);
        return new SequentialTransition(shrink, swap, grow);
    }

    // ===== 게임 시작 간부 애니메이션 =====

    void playOfficerSetup(List<Player> players) {
        final double CW = 124, CH = 176;

        VBox panel = new VBox(20);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(32, 44, 32, 44));
        panel.setMaxWidth(480);

        Label title = new Label("간부 배정");
        title.getStyleClass().add("waiting-label");

        Label sub = new Label("각 플레이어에게 간부가 배정되었습니다");
        sub.setStyle("-fx-text-fill: #8aa0a8; -fx-font-size: 13px;");

        VBox cardsCol = new VBox(12);
        cardsCol.setAlignment(Pos.CENTER_LEFT);
        cardsCol.setMaxWidth(Double.MAX_VALUE);

        List<StackPane> containers = new ArrayList<>();
        List<Node> fronts = new ArrayList<>();

        List<Player> ordered = players.stream()
                .sorted(Comparator.comparingInt(p -> isLocalActor.test(p) ? 1 : 0))
                .toList();
        for (Player p : ordered) {
            boolean local = isLocalActor.test(p);

            StackPane container = new StackPane(buildOfficerCardBack(CW, CH));
            container.setPrefSize(CW, CH);
            container.setMinSize(CW, CH);
            container.setMaxSize(CW, CH);

            Label nameLabel = new Label(p.name());
            nameLabel.setStyle("-fx-text-fill: " + (local ? "#f2d36b" : "#b9c8c2")
                + "; -fx-font-size: 14px; -fx-font-weight: bold;");
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            Label badge = new Label(local ? "나" : "상대");
            badge.setStyle("-fx-text-fill: " + (local ? "#101a1e" : "#8aa0a8")
                + "; -fx-background-color: " + (local ? "#f2d36b" : "rgba(180,200,210,0.25)")
                + "; -fx-background-radius: 4; -fx-font-size: 11px;"
                + " -fx-font-weight: bold; -fx-padding: 1 6 1 6;");

            HBox nameRow = new HBox(8, badge, nameLabel);
            nameRow.setAlignment(Pos.CENTER_LEFT);

            VBox nameCol = new VBox(4, nameRow);
            nameCol.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(nameCol, Priority.ALWAYS);

            HBox row = new HBox(18, nameCol, container);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(12, 16, 12, 16));
            row.setStyle("-fx-background-color: "
                + (local ? "rgba(242,211,107,0.07)" : "rgba(255,255,255,0.04)")
                + "; -fx-background-radius: 10; -fx-border-color: "
                + (local ? "rgba(242,211,107,0.25)" : "rgba(255,255,255,0.08)")
                + "; -fx-border-radius: 10; -fx-border-width: 1;");
            row.setMaxWidth(Double.MAX_VALUE);

            cardsCol.getChildren().add(row);
            containers.add(container);
            fronts.add(buildOfficerCardFront(p, CW, CH));
        }

        panel.getChildren().addAll(title, sub, cardsCol);
        StackPane.setAlignment(panel, Pos.CENTER);
        setCenter(panel);

        SequentialTransition seq = new SequentialTransition();
        seq.getChildren().add(new PauseTransition(Duration.millis(500)));
        for (int i = 0; i < containers.size(); i++) {
            seq.getChildren().add(buildCardFlip(containers.get(i), fronts.get(i)));
            long afterPause = (i < containers.size() - 1) ? 1000 : 300;
            seq.getChildren().add(new PauseTransition(Duration.millis(afterPause)));
        }
        seq.getChildren().add(new PauseTransition(Duration.millis(2000)));
        seq.setOnFinished(e -> { clearCenter(); playNextOverlay(); });
        seq.play();
    }

    private Node buildOfficerCardBack(double w, double h) {
        StackPane card = new StackPane();
        card.setPrefSize(w, h);
        card.setMaxSize(w, h);
        card.setStyle(
            "-fx-background-color: #0d1518, linear-gradient(to bottom, #1e3040, #0e1e2a);"
            + " -fx-background-insets: 0, 3; -fx-background-radius: 10, 7;"
            + " -fx-border-color: rgba(242,211,107,0.45); -fx-border-radius: 10; -fx-border-width: 1.5;"
            + " -fx-effect: dropshadow(gaussian,rgba(0,0,0,0.5),8,0.2,0,3);");
        Label q = new Label("?");
        q.setStyle("-fx-text-fill: rgba(242,211,107,0.5); -fx-font-size: 40px; -fx-font-weight: bold;");
        card.getChildren().add(q);
        return card;
    }

    private Node buildOfficerCardFront(Player player, double w, double h) {
        boolean hasOfficer = player.officer() != null;
        String officerName   = hasOfficer ? player.officer().korean()     : "없음";

        StackPane card = new StackPane();
        card.setPrefSize(w, h);
        card.setMaxSize(w, h);
        card.setStyle(
            "-fx-background-color: #0d1518, linear-gradient(to bottom, #1a2f40, #0e1e2c);"
            + " -fx-background-insets: 0, 3; -fx-background-radius: 10, 7;"
            + " -fx-border-color: rgba(242,211,107,0.85); -fx-border-radius: 10; -fx-border-width: 1.5;"
            + " -fx-effect: dropshadow(gaussian,rgba(242,211,107,0.25),14,0.2,0,0);");

        Label icon = new Label("★");
        icon.setStyle("-fx-text-fill: rgba(242,211,107,0.45); -fx-font-size: 26px;");

        Label nameLbl = new Label(officerName);
        nameLbl.setStyle("-fx-text-fill: #f2d36b; -fx-font-size: 19px; -fx-font-weight: bold;"
            + " -fx-alignment: center; -fx-text-alignment: center;");
        nameLbl.setWrapText(true);
        nameLbl.setMaxWidth(w - 18);

        VBox content = new VBox(8, icon, nameLbl);
        content.setAlignment(Pos.CENTER);
        content.setMaxWidth(w - 18);
        card.getChildren().add(content);
        return card;
    }

    // ===== 슬쩍하기 애니메이션 =====

    void playSteal(Player player, Card drawnCard) {
        VBox panel = new VBox(16);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(28, 44, 28, 44));
        panel.getStyleClass().add("waiting-panel");
        panel.setMaxWidth(460);

        Label title = new Label("↪  슬쩍하기!");
        title.setStyle("-fx-text-fill: #c97a52; -fx-font-size: 26px; -fx-font-weight: bold;"
            + " -fx-font-family: 'Book Antiqua','Malgun Gothic',serif;");

        Label whoLabel = new Label(player.name() + "  —  카드 더미 리셔플");
        whoLabel.setStyle("-fx-text-fill: #8aa0a8; -fx-font-size: 12px;");

        // 카드 더미 시각화: 3장 겹쳐서 뒷면
        StackPane pile = new StackPane();
        for (int i = 2; i >= 0; i--) {
            CardView cv = new CardView(new WildCard(-1), false, true);
            cv.setTranslateX((i - 1) * 7.0);
            cv.setTranslateY((i - 1) * -4.0);
            cv.setRotate((i - 1) * 6.0);
            pile.getChildren().add(cv);
        }
        pile.setPrefSize(CardView.COMPACT_WIDTH + 24, CardView.COMPACT_HEIGHT + 18);

        // 획득 카드 영역 (처음에는 뒷면 + 투명)
        StackPane drawnSlot = new StackPane();
        drawnSlot.setPrefSize(CardView.COMPACT_WIDTH, CardView.COMPACT_HEIGHT);
        drawnSlot.setMinSize(CardView.COMPACT_WIDTH, CardView.COMPACT_HEIGHT);
        drawnSlot.setOpacity(0);
        if (drawnCard != null) {
            drawnSlot.getChildren().add(new CardView(drawnCard, false, true));
        }

        Label drawnLabel = new Label("획득 카드");
        drawnLabel.setStyle("-fx-text-fill: #91dfc0; -fx-font-size: 11px; -fx-font-weight: bold;");
        drawnLabel.setOpacity(0);

        VBox drawnCol = new VBox(6, drawnLabel, drawnSlot);
        drawnCol.setAlignment(Pos.CENTER);

        HBox deckRow = new HBox(32, pile, drawnCol);
        deckRow.setAlignment(Pos.CENTER);

        panel.getChildren().addAll(title, whoLabel, deckRow);
        setCenter(panel);

        // 더미 흔들기 → 획득 카드 공개 순서로 애니메이션
        double[][] moves = {{-7,-5},{9,3},{-5,-3},{7,5},{-4,-2},{3,2},{0,0}};
        SequentialTransition jitter = new SequentialTransition(pile);
        for (double[] m : moves) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(75), pile);
            tt.setToX(m[0]);  tt.setToY(m[1]);
            jitter.getChildren().add(tt);
        }

        jitter.setOnFinished(e -> {
            // 획득 카드 레이블 페이드인
            FadeTransition showLbl = new FadeTransition(Duration.millis(160), drawnLabel);
            showLbl.setToValue(1);
            showLbl.play();

            // 뒷면 카드 표시 후 뒤집기
            drawnSlot.setOpacity(1);
            PauseTransition prePause = new PauseTransition(Duration.millis(280));
            prePause.setOnFinished(pe -> {
                ScaleTransition shrink = new ScaleTransition(Duration.millis(150), drawnSlot);
                shrink.setToX(0);
                shrink.setOnFinished(se -> {
                    drawnSlot.getChildren().clear();
                    if (drawnCard != null) {
                        drawnSlot.getChildren().add(new CardView(drawnCard, true, true));
                    } else {
                        Label empty = new Label("없음");
                        empty.setStyle("-fx-text-fill: #7d918d; -fx-font-size: 13px;");
                        drawnSlot.getChildren().add(empty);
                    }
                    ScaleTransition grow = new ScaleTransition(Duration.millis(190), drawnSlot);
                    grow.setFromX(0);
                    grow.setToX(1);
                    grow.setOnFinished(ge -> {
                        PauseTransition hold = new PauseTransition(Duration.millis(1500));
                        hold.setOnFinished(he -> { clearCenter(); playNextOverlay(); });
                        hold.play();
                    });
                    grow.play();
                });
                shrink.play();
            });
            prePause.play();
        });

        jitter.play();
    }

    // ===== 도우미 발동 애니메이션 =====

    /**
     * 도우미 카드를 뒷면→앞면으로 뒤집어 "발동"을 보여주고, 효과로 처분/획득한 카드를 공개한다.
     * 코인 변화는 컨트롤러의 코인 플로팅이 별도로 띄운다.
     * 모든 경로에서 마지막에 {@code playNextOverlay()} 로 큐를 비워야 한다(미호출 시 교착).
     */
    void playHelperEffect(Player player, HelperCard helper, String message,
            List<Card> drawn, List<Card> discarded) {
        if (globalOverlay == null) { // 오버레이 레이어가 없으면 큐만 진행(교착 방지).
            playNextOverlay();
            return;
        }
        final double CW = 136, CH = 190;

        VBox panel = new VBox(14);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(24, 38, 24, 38));
        panel.getStyleClass().add("waiting-panel");
        panel.setMaxWidth(620);
        panel.setMaxHeight(Region.USE_PREF_SIZE);

        Label title = new Label("✦  도우미 발동");
        title.setStyle("-fx-text-fill: #f2d36b; -fx-font-size: 21px; -fx-font-weight: bold;"
            + " -fx-font-family: 'Book Antiqua','Malgun Gothic',serif;");

        Label who = new Label(player.name() + (isLocalActor.test(player) ? "  (나)" : "  (상대)"));
        who.setStyle("-fx-text-fill: #8aa0a8; -fx-font-size: 12px;");

        StackPane container = new StackPane(buildOfficerCardBack(CW, CH));
        container.setPrefSize(CW, CH);
        container.setMinSize(CW, CH);
        container.setMaxSize(CW, CH);
        Node front = buildHelperFrontCard(helper, CW, CH);

        Label effect = new Label(message);
        effect.setStyle("-fx-text-fill: #91dfc0; -fx-font-size: 13px;");
        effect.setWrapText(true);
        effect.setMaxWidth(520);
        effect.setOpacity(0);

        HBox changes = new HBox(28);
        changes.setAlignment(Pos.CENTER);
        changes.setOpacity(0);
        if (!discarded.isEmpty()) {
            changes.getChildren().add(buildHelperChangeColumn("처분 " + discarded.size() + "장", discarded, "#c97a52"));
        }
        if (!drawn.isEmpty()) {
            changes.getChildren().add(buildHelperChangeColumn("획득 " + drawn.size() + "장", drawn, "#91dfc0"));
        }
        boolean hasChanges = !changes.getChildren().isEmpty();

        panel.getChildren().addAll(title, who, container, effect);
        if (hasChanges) {
            panel.getChildren().add(changes);
        }

        // 필드 카드보다 위(z 최상위)인 globalOverlay 에 띄워 잘리지 않게 한다.
        StackPane host = new StackPane(panel);
        host.setMouseTransparent(true);
        host.setStyle("-fx-background-color: rgba(6,11,15,0.66);");
        host.setOpacity(0);
        host.prefWidthProperty().bind(globalOverlay.widthProperty());
        host.prefHeightProperty().bind(globalOverlay.heightProperty());
        alignOverPlayField(host); // 좌측 사이드바를 제외하고 게임 필드 기준 가운데 정렬
        globalOverlay.getChildren().add(host);

        SequentialTransition seq = new SequentialTransition();
        seq.getChildren().add(fade(host, 0, 1, 160));
        seq.getChildren().add(new PauseTransition(Duration.millis(120)));
        seq.getChildren().add(buildCardFlip(container, front));
        PauseTransition reveal = new PauseTransition(Duration.millis(60));
        reveal.setOnFinished(e -> {
            fade(effect, 0, 1, 220).play();
            if (hasChanges) {
                fade(changes, 0, 1, 260).play();
            }
        });
        seq.getChildren().add(reveal);
        seq.getChildren().add(new PauseTransition(Duration.millis(hasChanges ? 5300 : 4800))); // 뒤집힌 뒤 유지(정보량 많음)
        seq.getChildren().add(fade(host, 1, 0, 240));
        seq.setOnFinished(e -> {
            globalOverlay.getChildren().remove(host);
            playNextOverlay();
        });
        seq.play();
    }

    /**
     * globalOverlay(전체 창)에 얹힌 오버레이를, 좌측 사이드바를 뺀 <b>게임 필드 영역</b> 기준으로
     * 가운데 정렬되도록 좌/우 패딩을 맞춘다. 필드 노드가 아직 배치 전이면 창 전체 기준으로 둔다.
     */
    private void alignOverPlayField(Region host) {
        if (globalOverlay == null || centerArea == null) {
            return;
        }
        Bounds field = centerArea.localToScene(centerArea.getBoundsInLocal());
        Bounds overlay = globalOverlay.localToScene(globalOverlay.getBoundsInLocal());
        if (field.getWidth() <= 0 || overlay.getWidth() <= 0) {
            return;
        }
        double left = Math.max(0, field.getMinX() - overlay.getMinX());
        double right = Math.max(0, (overlay.getMinX() + overlay.getWidth()) - (field.getMinX() + field.getWidth()));
        host.setPadding(new Insets(0, right, 0, left));
    }

    private Node buildHelperFrontCard(HelperCard helper, double w, double h) {
        StackPane card = new StackPane();
        card.setPrefSize(w, h);
        card.setMaxSize(w, h);
        card.setStyle(
            "-fx-background-color: #0d1518, linear-gradient(to bottom, #243a2e, #11241c);"
            + " -fx-background-insets: 0, 3; -fx-background-radius: 10, 7;"
            + " -fx-border-color: rgba(145,223,192,0.85); -fx-border-radius: 10; -fx-border-width: 1.5;"
            + " -fx-effect: dropshadow(gaussian,rgba(145,223,192,0.25),14,0.2,0,0);");

        Label icon = new Label("✦");
        icon.setStyle("-fx-text-fill: rgba(145,223,192,0.55); -fx-font-size: 26px;");

        Label nameLbl = new Label(helper.displayName());
        nameLbl.setStyle("-fx-text-fill: #d8f5e8; -fx-font-size: 16px; -fx-font-weight: bold;"
            + " -fx-alignment: center; -fx-text-alignment: center;");
        nameLbl.setWrapText(true);
        nameLbl.setMaxWidth(w - 18);

        Label effectLbl = new Label(helper.effectText());
        effectLbl.setStyle("-fx-text-fill: #91dfc0; -fx-font-size: 11px;"
            + " -fx-alignment: center; -fx-text-alignment: center;");
        effectLbl.setWrapText(true);
        effectLbl.setMaxWidth(w - 18);

        VBox content = new VBox(6, icon, nameLbl, effectLbl);
        content.setAlignment(Pos.CENTER);
        content.setMaxWidth(w - 18);
        card.getChildren().add(content);
        return card;
    }

    private VBox buildHelperChangeColumn(String caption, List<Card> cards, String color) {
        Label cap = new Label(caption);
        cap.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 11px; -fx-font-weight: bold;");
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER);
        List<Card> sorted = new ArrayList<>(cards);
        sorted.sort(cardOrder);
        for (Card c : sorted) {
            row.getChildren().add(new CardView(c, true, true));
        }
        VBox col = new VBox(6, cap, row);
        col.setAlignment(Pos.CENTER);
        return col;
    }
}
