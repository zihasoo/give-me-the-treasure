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

    /**
     * 간부 배정 연출을 팀 단위로 보여준다(상대 팀 위 / 우리 팀 아래).
     * 같은 팀 카드는 한 줄에 가로로 늘어놓아 "팀 대 팀" 구도가 드러나게 한다.
     * 뒤집기 순서는 상대 팀 먼저, 우리 팀(내 카드 포함) 나중이다.
     */
    void playOfficerSetup(List<Player> opponentMembers, List<Player> myMembers) {
        final double CW = 116, CH = 166;

        VBox panel = new VBox(14);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(28, 40, 28, 40));
        panel.setMaxWidth(660);

        Label title = new Label("간부 배정");
        title.getStyleClass().add("waiting-label");

        Label sub = new Label("각 플레이어에게 간부가 배정되었습니다");
        sub.setStyle("-fx-text-fill: #8aa0a8; -fx-font-size: 13px;");

        List<StackPane> containers = new ArrayList<>();
        List<Node> fronts = new ArrayList<>();

        // 상대 팀 먼저(위), 우리 팀 나중(아래) — containers/fronts 에 추가된 순서가 뒤집기 순서.
        Node opponentGroup = buildOfficerTeamGroup("상대 팀", opponentMembers, false, CW, CH, containers, fronts);
        Node myGroup = buildOfficerTeamGroup("우리 팀", myMembers, true, CW, CH, containers, fronts);

        Label vs = new Label("VS");
        vs.setStyle("-fx-text-fill: #f2d36b; -fx-font-size: 20px; -fx-font-weight: bold;"
            + " -fx-font-family: 'Book Antiqua','Malgun Gothic',serif;");

        panel.getChildren().addAll(title, sub);
        if (opponentGroup != null) panel.getChildren().add(opponentGroup);
        panel.getChildren().add(vs);
        if (myGroup != null) panel.getChildren().add(myGroup);
        StackPane.setAlignment(panel, Pos.CENTER);
        setCenter(panel);

        SequentialTransition seq = new SequentialTransition();
        seq.getChildren().add(new PauseTransition(Duration.millis(500)));
        for (int i = 0; i < containers.size(); i++) {
            seq.getChildren().add(buildCardFlip(containers.get(i), fronts.get(i)));
            long afterPause = (i < containers.size() - 1) ? 800 : 300;
            seq.getChildren().add(new PauseTransition(Duration.millis(afterPause)));
        }
        seq.getChildren().add(new PauseTransition(Duration.millis(2000)));
        seq.setOnFinished(e -> { clearCenter(); playNextOverlay(); });
        seq.play();
    }

    /** 한 팀의 간부 카드 줄(가로 배치 + 팀 배너)을 만든다. 멤버가 없으면 {@code null}. */
    private Node buildOfficerTeamGroup(String teamName, List<Player> members, boolean isMyTeam,
            double cw, double ch, List<StackPane> containers, List<Node> fronts) {
        if (members == null || members.isEmpty()) {
            return null;
        }
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER);
        for (Player p : members) {
            boolean local = isLocalActor.test(p);

            StackPane container = new StackPane(buildOfficerCardBack(cw, ch));
            container.setPrefSize(cw, ch);
            container.setMinSize(cw, ch);
            container.setMaxSize(cw, ch);

            Label nameLabel = new Label(p.name());
            nameLabel.setStyle("-fx-text-fill: " + (isMyTeam ? "#f2d36b" : "#b9c8c2")
                + "; -fx-font-size: 14px; -fx-font-weight: bold;");

            // 배지: 나 / 아군 / 상대 — 같은 팀(아군)은 우리 팀임이 드러나게 표기.
            String badgeText = local ? "나" : (isMyTeam ? "아군" : "상대");
            String badgeFill = local ? "#101a1e" : (isMyTeam ? "#91dfc0" : "#8aa0a8");
            String badgeBg = local ? "#f2d36b"
                : (isMyTeam ? "rgba(145,223,192,0.22)" : "rgba(180,200,210,0.22)");
            Label badge = new Label(badgeText);
            badge.setStyle("-fx-text-fill: " + badgeFill
                + "; -fx-background-color: " + badgeBg
                + "; -fx-background-radius: 4; -fx-font-size: 11px;"
                + " -fx-font-weight: bold; -fx-padding: 1 6 1 6;");

            HBox nameRow = new HBox(6, badge, nameLabel);
            nameRow.setAlignment(Pos.CENTER);

            VBox cell = new VBox(8, nameRow, container);
            cell.setAlignment(Pos.CENTER);
            cell.setPadding(new Insets(12, 14, 12, 14));
            cell.setStyle("-fx-background-color: "
                + (isMyTeam ? "rgba(242,211,107,0.07)" : "rgba(255,255,255,0.04)")
                + "; -fx-background-radius: 12; -fx-border-color: "
                + (isMyTeam ? "rgba(242,211,107,0.25)" : "rgba(255,255,255,0.08)")
                + "; -fx-border-radius: 12; -fx-border-width: 1;");

            row.getChildren().add(cell);
            containers.add(container);
            fronts.add(buildOfficerCardFront(p, cw, ch));
        }

        Label banner = new Label(teamName);
        banner.setStyle("-fx-text-fill: " + (isMyTeam ? "#f2d36b" : "#8aa0a8")
            + "; -fx-font-size: 13px; -fx-font-weight: bold;");

        VBox group = new VBox(8);
        group.setAlignment(Pos.CENTER);
        // 상대 팀은 배너를 카드 위에, 우리 팀은 카드 아래에 둔다(보드의 상/하 구도와 일치).
        if (isMyTeam) {
            group.getChildren().addAll(row, banner);
        } else {
            group.getChildren().addAll(banner, row);
        }
        return group;
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
        if (globalOverlay == null) { // 오버레이 레이어가 없으면 큐만 진행(교착 방지).
            playNextOverlay();
            return;
        }
        VBox panel = new VBox(16);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(28, 44, 28, 44));
        panel.getStyleClass().add("waiting-panel");
        panel.setMaxWidth(460);
        panel.setMaxHeight(Region.USE_PREF_SIZE); // 오버레이(StackPane host)가 패널을 세로로 늘리지 않도록 고정

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

        // 가운데 center 를 setCenter 로 갈아끼우면, center 에 있던 환금 패널이 떨어져 나가
        // 이후 증분 갱신으로는 되살아나지 못한다(환금 UI 사라짐 버그). 그래서 필드보다 위인
        // globalOverlay 에 겹쳐 올려, 환금 패널을 건드리지 않고 슬쩍하기 연출만 보여준다.
        StackPane host = new StackPane(panel);
        host.setStyle("-fx-background-color: rgba(6,11,15,0.58);");
        host.setOpacity(0);
        host.prefWidthProperty().bind(globalOverlay.widthProperty());
        host.prefHeightProperty().bind(globalOverlay.heightProperty());
        alignOverPlayField(host);
        boolean wasMouseTransparent = globalOverlay.isMouseTransparent();
        boolean wasPickOnBounds = globalOverlay.isPickOnBounds();
        globalOverlay.setMouseTransparent(false);
        globalOverlay.setPickOnBounds(true);
        globalOverlay.getChildren().add(host);
        fade(host, 0, 1, 160).play();

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
                        hold.setOnFinished(he -> {
                            FadeTransition out = fade(host, 1, 0, 220);
                            out.setOnFinished(oe -> {
                                globalOverlay.getChildren().remove(host);
                                globalOverlay.setMouseTransparent(wasMouseTransparent);
                                globalOverlay.setPickOnBounds(wasPickOnBounds);
                                playNextOverlay();
                            });
                            out.play();
                        });
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
        panel.setStyle("-fx-background-color: rgba(10, 19, 24, 0.72);");
        panel.setMaxWidth(620);
        panel.setMaxHeight(Region.USE_PREF_SIZE);

        Label title = new Label("✦  도우미 발동");
        title.setStyle("-fx-text-fill: #f2d36b; -fx-font-size: 22px; -fx-font-weight: black;"
            + " -fx-font-family: 'Malgun Gothic','Book Antiqua',serif;");

        Label who = new Label(player.name() + (isLocalActor.test(player) ? "  (나)" : "  (상대)"));
        who.setStyle("-fx-text-fill: #b6c7c1; -fx-font-size: 13px; -fx-font-weight: bold;");

        HBox header = new HBox(10, title, who);
        header.setAlignment(Pos.CENTER);

        StackPane container = new StackPane(buildOfficerCardBack(CW, CH));
        container.setPrefSize(CW, CH);
        container.setMinSize(CW, CH);
        container.setMaxSize(CW, CH);
        Node front = buildHelperFrontCard(helper, CW, CH);

        Label effect = new Label(helperEffectMessage(helper, message));
        effect.setStyle("-fx-text-fill: #91dfc0; -fx-font-size: 13px;"
            + " -fx-alignment: center; -fx-text-alignment: center;");
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

        panel.getChildren().addAll(header, container, effect);
        if (hasChanges) {
            panel.getChildren().add(changes);
        }

        // 필드 카드보다 위(z 최상위)인 globalOverlay 에 띄워 잘리지 않게 한다.
        StackPane host = new StackPane(panel);
        host.setMouseTransparent(false);
        host.setPickOnBounds(true);
        host.setStyle("-fx-background-color: rgba(6,11,15,0.58);");
        host.setOpacity(0);
        host.prefWidthProperty().bind(globalOverlay.widthProperty());
        host.prefHeightProperty().bind(globalOverlay.heightProperty());
        alignOverPlayField(host); // 좌측 사이드바를 제외하고 게임 필드 기준 가운데 정렬
        boolean wasMouseTransparent = globalOverlay.isMouseTransparent();
        boolean wasPickOnBounds = globalOverlay.isPickOnBounds();
        globalOverlay.setMouseTransparent(false);
        globalOverlay.setPickOnBounds(true);
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
        seq.getChildren().add(new PauseTransition(Duration.millis(5000))); // 뒤집힌 뒤 총 유지 시간이 약 5.5초가 되도록 조정
        seq.getChildren().add(fade(host, 1, 0, 240));
        seq.setOnFinished(e -> {
            globalOverlay.getChildren().remove(host);
            globalOverlay.setMouseTransparent(wasMouseTransparent);
            globalOverlay.setPickOnBounds(wasPickOnBounds);
            playNextOverlay();
        });
        seq.play();
    }

    private String helperEffectMessage(HelperCard helper, String message) {
        if (message == null) {
            return "";
        }
        for (String separator : List.of(" — ", " -- ")) {
            String prefix = helper.displayName() + separator;
            if (message.startsWith(prefix)) {
                return message.substring(prefix.length());
            }
        }
        return message;
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
            "-fx-background-color: #0d1518, linear-gradient(to bottom, #284435, #14281f);"
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
