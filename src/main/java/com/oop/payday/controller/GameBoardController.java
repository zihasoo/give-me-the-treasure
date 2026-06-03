package com.oop.payday.controller;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import com.oop.payday.bot.HeuristicBotStrategy;
import com.oop.payday.app.GameApp;
import com.oop.payday.decision.BundleView;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.game.Game;
import com.oop.payday.game.GameConfig;
import com.oop.payday.game.GameListener;
import com.oop.payday.game.Phase;
import com.oop.payday.game.Team;
import com.oop.payday.model.card.Card;
import com.oop.payday.model.card.CursedCard;
import com.oop.payday.model.card.StealCard;
import com.oop.payday.model.card.TreasureCard;
import com.oop.payday.model.helper.HelperCard;
import com.oop.payday.model.helper.HelperKind;
import com.oop.payday.model.helper.HelperRules;
import com.oop.payday.model.set.CashInEvaluator;
import com.oop.payday.model.set.SetType;
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.player.BotPlayer;
import com.oop.payday.player.HumanPlayer;
import com.oop.payday.player.HumanUi;
import com.oop.payday.player.Player;
import com.oop.payday.view.CardView;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ScrollPane.ScrollBarPolicy;
import javafx.scene.control.ToggleButton;
import javafx.geometry.Bounds;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;

import java.util.concurrent.CountDownLatch;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * 게임 보드 컨트롤러. 모델 이벤트({@link GameListener})를 화면에 반영하고,
 * 사람 플레이어의 입력 요청({@link HumanUi})에 맞춰 단계별 입력 패널을 띄운다.
 *
 * <p>게임 로직은 별도 스레드에서 돌고, 모든 UI 변경은 {@link Platform#runLater} 로 처리한다.
 */
public final class GameBoardController implements GameListener, HumanUi {

    @FXML private Label fieldATitleLabel;
    @FXML private Label fieldAOfficerLabel;
    @FXML private Label fieldAOfficerEffectLabel;
    @FXML private Label fieldACoinsLabel;
    @FXML private Label fieldACountLabel;
    @FXML private Label fieldBTitleLabel;
    @FXML private Label fieldBOfficerLabel;
    @FXML private Label fieldBOfficerEffectLabel;
    @FXML private Label fieldBCoinsLabel;
    @FXML private Label fieldBCountLabel;
    @FXML private StackPane fieldAStage;
    @FXML private StackPane fieldBStage;
    @FXML private Pane globalOverlay;
    @FXML private FlowPane fieldAFlow;
    @FXML private FlowPane fieldBFlow;
    @FXML private VBox fieldAHelperFlow;
    @FXML private VBox fieldBHelperFlow;
    @FXML private StackPane centerArea;
    @FXML private StackPane contentArea;
    @FXML private StackPane overlayArea;
    @FXML private Label turnLabel;
    @FXML private Label messageLabel;

    private Team teamA;
    private Team teamB;
    private HumanPlayer localPlayer;
    private Team currentSplitTeam;
    private final Queue<Runnable> overlayQueue = new ArrayDeque<>();
    private final Queue<Runnable> afterOverlayQueue = new ArrayDeque<>();
    private final Map<Team, Integer> pendingCoinPreview = new HashMap<>();
    private boolean overlayPlaying;
    private boolean distributionFieldUpdatePending;
    private VBox activeBundle0;
    private VBox activeBundle1;
    private int centerRevision;
    private FadeTransition activeCenterTransition;
    private boolean opponentWaitingVisible;
    private int phaseRevision;

    /** 카드 표시 순서: 보물(색→숫자) → 굉장한 보물 → 저주받은 그림(숫자) → 기타. */
    private static final Comparator<Card> CARD_ORDER =
            Comparator.<Card>comparingInt(GameBoardController::cardRank)
                    .thenComparingInt(GameBoardController::cardColorRank)
                    .thenComparingInt(GameBoardController::cardNumberRank);

    private static int cardRank(Card card) {
        if (card instanceof TreasureCard) {
            return 0;
        }
        if (card.isWild()) {
            return 1;
        }
        if (card instanceof CursedCard) {
            return 2;
        }
        if (card instanceof StealCard) {
            return 3;
        }
        return 4;
    }

    private static int cardColorRank(Card card) {
        return card instanceof TreasureCard t ? t.color().ordinal() : 0;
    }

    private static int cardNumberRank(Card card) {
        if (card instanceof TreasureCard t) {
            return t.number();
        }
        if (card instanceof CursedCard c) {
            return c.number();
        }
        return 0;
    }

    /**
     * 현재 진행 중인 오버레이 애니메이션이 모두 끝날 때까지 게임 스레드를 블록한다.
     * Platform.runLater로 예약된 이후 람다(환금 페이즈 배너 포함)까지 기다린 뒤 반환한다.
     */
    @Override
    public void awaitAnimations() {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> runAfterOverlay(latch::countDown));
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 메인 메뉴에서 호출. 플레이어/팀/게임을 구성하고 게임 스레드를 시작한다. */
    public void startGame(GameConfig config, boolean testBot) {
        HumanPlayer p1 = new HumanPlayer("플레이어 1");
        p1.setUi(this);
        localPlayer = p1;

        HeuristicBotStrategy strategy = new HeuristicBotStrategy();
        Player p2 = testBot ? BotPlayer.test(strategy) : BotPlayer.play(strategy);

        teamA = new Team(p1.name(), List.of(p1));
        teamB = new Team(p2.name(), List.of(p2));
        updateBoardStatus();

        Game game = new Game(config, teamA, teamB, this);
        Thread loop = new Thread(game::play, "game-loop");
        loop.setDaemon(true);
        loop.start();
    }

    // ===== GameListener (게임 스레드 → UI) =====

    @Override
    public void onGameSetup(List<Player> players) {
        Platform.runLater(() -> enqueueOverlay(() -> playOfficerSetupAnimation(players)));
    }

    @Override
    public void onStealActivated(Player player, Card drawnCard) {
        Platform.runLater(() -> enqueueOverlay(() -> playStealAnimation(player, drawnCard)));
    }

    @Override
    public void onPhaseChanged(Phase phase, int round, Team splitTeam) {
        Platform.runLater(() -> {
            int phaseToken = ++phaseRevision;
            currentSplitTeam = splitTeam;
            clearCenterIfOpponentWaiting();
            turnLabel.setText(phase.korean());
            updateBoardStatus();
            if (phase == Phase.SCHEME && !isLocalActor(splitTeam.leader())) {
                runAfterOverlay(() -> setWaitingIfCurrent(phaseToken, "상대가 카드를 분할 중입니다"));
            } else if (phase == Phase.DISTRIBUTE) {
                activeBundle0 = null;
                activeBundle1 = null;
            }
        });
    }

    @Override
    public void onPlayerSetup(Player player) {
        Platform.runLater(this::updateBoardStatus);
    }

    @Override
    public void onChoiceReady(GameListener.BundlePair bundles) {
        Platform.runLater(() -> {
            distributionFieldUpdatePending = true;
            int phaseToken = phaseRevision;
            Team chooser = otherTeam(currentSplitTeam);
            if (chooser != null && !isLocalActor(chooser.leader())) {
                runAfterOverlay(() -> setWaitingChoiceIfCurrent(phaseToken, bundles));
            }
        });
    }

    @Override
    public void onDistributed(int chosenIndex, Team chooseTeam, List<Card> chooseCards,
            Team splitTeam, List<Card> splitCards) {
        Platform.runLater(() -> {
            distributionFieldUpdatePending = true;
            setMessage(chooseTeam.name() + "이(가) 묶음 " + (chosenIndex == 0 ? "①" : "②") + " 선택 · "
                    + chooseTeam.name() + " " + chooseCards + " / "
                    + splitTeam.name() + " " + splitCards);
            ensureDistributionAnimationPanel(chosenIndex, chooseCards, splitCards);
            opponentWaitingVisible = false;
            playDistributionTransition(chosenIndex, chooseTeam, splitTeam, () -> {
                distributionFieldUpdatePending = false;
                updateBoardStatus();
            });
        });
    }

    @Override
    public void onCashIn(Player player, TreasureSet set) {
        Platform.runLater(() -> {
            setMessage(player.name() + " 환금: " + set);
            updateBoardStatus();
        });
    }

    @Override
    public void onDiscard(Player player, Card card) {
        Platform.runLater(() -> {
            setMessage(player.name() + " 처분: " + card.displayName());
            updateBoardStatus();
        });
    }

    @Override
    public void onHelperUsed(Player player, HelperCard helper, String message) {
        Platform.runLater(() -> {
            setMessage(player.name() + " 도우미: " + message);
            updateBoardStatus();
        });
    }

    @Override
    public void onForcedDiscard(Player player, List<Card> cards) {
        Platform.runLater(() -> {
            setMessage(player.name() + " 보유 한도 초과로 강제 처분: " + cards);
            updateBoardStatus();
        });
    }

    @Override
    public void onCoinsChanged(Team team, int delta) {
        Platform.runLater(() -> {
            int unpreviewedDelta = consumePendingCoinDelta(team, delta);
            updateBoardStatus();
            if (unpreviewedDelta != 0) {
                playCoinChangeAnimation(team, unpreviewedDelta);
            }
        });
    }

    @Override
    public void onMessage(String message) {
        Platform.runLater(() -> setMessage(message));
    }

    @Override
    public void onGameOver(Team winner) {
        Platform.runLater(() -> {
            setCenterAnimated(buildGameOverPanel(winner));
            turnLabel.setText("게임 종료");
            updateBoardStatus();
        });
    }

    // ===== HumanUi (사람 입력 요청) =====

    @Override
    public void requestSplit(HumanPlayer player, List<Card> hand) {
        if (!isLocalActor(player)) {
            return;
        }
        Platform.runLater(() -> {
            runAfterOverlay(() -> {
                updateBoardStatus();
                setCenterAnimated(buildSplitPanel(player, hand));
            });
        });
    }

    @Override
    public void requestChoice(HumanPlayer player, ChoiceView view) {
        if (!isLocalActor(player)) {
            return;
        }
        Platform.runLater(() -> {
            runAfterOverlay(() -> {
                updateBoardStatus();
                setCenterAnimated(buildChoicePanel(player, view));
            });
        });
    }

    @Override
    public void requestHelperSelection(HumanPlayer player, List<HelperCard> options, int chooseCount) {
        if (!isLocalActor(player)) {
            return;
        }
        Platform.runLater(() -> {
            runAfterOverlay(() -> {
                updateBoardStatus();
                setCenterAnimated(buildHelperSelectionPanel(player, options, chooseCount));
            });
        });
    }

    @Override
    public void requestCashIn(HumanPlayer player, CashInContext context) {
        if (!isLocalActor(player)) {
            return;
        }
        Platform.runLater(() -> {
            runAfterOverlay(() -> {
                Team team = teamFor(player);
                setMessage(player.name() + " 환금 입력 대기 — 보관 카드 " + context.holdings().size() + "장");
                updateBoardStatus();
                renderCashInPanel(player, team, context, new ArrayList<>(context.holdings()), new ArrayList<>());
            });
        });
    }

    // ===== 패널 빌더 =====

    private Node buildSplitPanel(HumanPlayer player, List<Card> hand) {
        VBox root = panelRoot("카드를 드래그해 두 묶음으로 나누세요. 우클릭하면 비공개 카드가 됩니다.");

        Map<Card, Integer> bundleOf = new HashMap<>();
        Map<Card, Node> nodeOf = new HashMap<>();
        Card[] faceDown = new Card[1];

        FlowPane bundleA = splitDropZone("묶음 ①");
        FlowPane bundleB = splitDropZone("묶음 ②");

        Runnable refresh = () -> {
            bundleA.getChildren().clear();
            bundleB.getChildren().clear();
            for (Card card : hand) {
                Node node = nodeOf.computeIfAbsent(card, c -> buildDraggableSplitCard(c, bundleOf, faceDown, bundleA, bundleB));
                if (bundleOf.getOrDefault(card, 0) == 0) {
                    bundleA.getChildren().add(node);
                } else {
                    bundleB.getChildren().add(node);
                }
                markSplitCard(node, card == faceDown[0]);
            }
        };

        for (int i = 0; i < hand.size(); i++) {
            bundleOf.put(hand.get(i), i < 2 ? 0 : 1);
        }
        faceDown[0] = hand.get(0);
        installDropTarget(bundleA, 0, bundleOf, refresh);
        installDropTarget(bundleB, 1, bundleOf, refresh);
        refresh.run();

        HBox bundlesRow = new HBox(18,
                splitBundleBox("묶음 ①", bundleA),
                splitBundleBox("묶음 ②", bundleB));
        bundlesRow.setAlignment(Pos.CENTER);

        Button done = new Button("분할 완료");
        done.getStyleClass().add("menu-button");
        done.setOnAction(e -> {
            List<Card> cardsA = new ArrayList<>();
            List<Card> cardsB = new ArrayList<>();
            for (Card card : hand) {
                if (bundleOf.getOrDefault(card, 0) == 0) {
                    cardsA.add(card);
                } else {
                    cardsB.add(card);
                }
            }
            if (faceDown[0] == null) {
                alert("우클릭으로 비공개 카드 1장을 선택하세요.");
                return;
            }
            SplitDecision decision = new SplitDecision(cardsA, cardsB, faceDown[0]);
            if (!decision.isValid()) {
                alert("묶음은 2+3 또는 1+4 로 나눠야 합니다. (현재 "
                        + cardsA.size() + " + " + cardsB.size() + ")");
                return;
            }
            clearCenter();
            player.provideSplit(decision);
        });

        root.getChildren().addAll(bundlesRow, done);
        return root;
    }

    private FlowPane splitDropZone(String label) {
        FlowPane pane = new FlowPane(10, 10);
        pane.setAlignment(Pos.CENTER);
        pane.setPrefWrapLength(360);
        pane.setMaxWidth(380);
        pane.setMinHeight(135);
        pane.getStyleClass().add("split-drop-zone");
        pane.setUserData(label);
        return pane;
    }

    private VBox splitBundleBox(String title, FlowPane cards) {
        Label label = new Label(title);
        label.getStyleClass().add("split-title");
        VBox box = new VBox(10, label, cards);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("split-bundle-box");
        box.setPadding(new Insets(12));
        box.setMaxWidth(400);
        return box;
    }

    private Node buildDraggableSplitCard(Card card, Map<Card, Integer> bundleOf, Card[] faceDown,
            FlowPane bundleA, FlowPane bundleB) {
        StackPane wrapper = new StackPane(new CardView(card, true, true));
        wrapper.getStyleClass().add("split-card");
        wrapper.setUserData(card);

        Label hidden = new Label("비공개");
        hidden.getStyleClass().add("face-down-ribbon");
        StackPane.setAlignment(hidden, Pos.BOTTOM_CENTER);
        wrapper.getChildren().add(hidden);

        wrapper.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                faceDown[0] = card;
                refreshSplitFaceDown(bundleA, bundleB, faceDown[0]);
            }
        });
        wrapper.setOnDragDetected(e -> {
            Dragboard dragboard = wrapper.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(String.valueOf(card.id()));
            dragboard.setContent(content);
            WritableImage dragImage = wrapper.snapshot(null, null);
            dragboard.setDragView(dragImage, dragImage.getWidth() / 2, dragImage.getHeight() / 2);
            e.consume();
        });
        return wrapper;
    }

    private void installDropTarget(FlowPane target, int bundleIndex, Map<Card, Integer> bundleOf, Runnable refresh) {
        target.setOnDragOver(e -> {
            if (e.getGestureSource() != target && e.getDragboard().hasString()) {
                e.acceptTransferModes(TransferMode.MOVE);
            }
            e.consume();
        });
        target.setOnDragDropped(e -> {
            Object source = e.getGestureSource();
            if (source instanceof Node node && node.getUserData() instanceof Card card) {
                bundleOf.put(card, bundleIndex);
                refresh.run();
                e.setDropCompleted(true);
            }
            e.consume();
        });
    }

    private void refreshSplitFaceDown(FlowPane bundleA, FlowPane bundleB, Card faceDown) {
        for (Node node : concatNodes(bundleA.getChildren(), bundleB.getChildren())) {
            markSplitCard(node, node.getUserData() == faceDown);
        }
    }

    private void markSplitCard(Node node, boolean selected) {
        node.getStyleClass().remove("split-face-down");
        if (selected) {
            if (!node.getStyleClass().contains("split-face-down")) {
                node.getStyleClass().add("split-face-down");
            }
        }
        if (node instanceof StackPane stack) {
            for (Node child : stack.getChildren()) {
                if (child.getStyleClass().contains("face-down-ribbon")) {
                    child.setVisible(selected);
                }
            }
        }
    }

    private List<Node> concatNodes(List<Node> first, List<Node> second) {
        List<Node> nodes = new ArrayList<>(first.size() + second.size());
        nodes.addAll(first);
        nodes.addAll(second);
        return nodes;
    }

    private Node buildChoicePanel(HumanPlayer player, ChoiceView view) {
        VBox root = panelRoot("두 묶음 중 하나를 선택하세요. (뒷면 카드는 가려져 있습니다)");

        HBox bundlesRow = new HBox(40);
        bundlesRow.setAlignment(Pos.CENTER);
        activeBundle0 = null;
        activeBundle1 = null;
        for (int i = 0; i < view.bundles().size(); i++) {
            BundleView bundle = view.bundle(i);
            int index = i;

            Button pick = new Button("묶음 " + (i == 0 ? "①" : "②") + " 선택 (" + bundle.size() + "장)");
            pick.getStyleClass().add("menu-button");

            VBox box = bundleBox("묶음 " + (i == 0 ? "①" : "②"), bundle.visibleCards(), bundle.hasFaceDown(), pick);
            rememberActiveBundle(index, box);
            pick.setUserData(box);
            pick.setOnAction(e -> {
                box.getStyleClass().add("bundle-picked");
                disableBundleButtons();
                PauseTransition delay = new PauseTransition(Duration.millis(1100));
                delay.setOnFinished(done -> {
                    player.provideChoice(index);
                });
                delay.play();
            });
            bundlesRow.getChildren().add(box);
        }

        root.getChildren().add(bundlesRow);
        return root;
    }

    private Node buildWaitingChoicePanel(GameListener.BundlePair bundles) {
        return buildWaitingChoicePanel(
                bundles.visible0(), bundles.faceDown0(),
                bundles.visible1(), bundles.faceDown1());
    }

    private Node buildWaitingChoicePanel(List<Card> bundleA, List<Card> bundleB, Card faceDown) {
        return buildWaitingChoicePanel(
                visibleCards(bundleA, faceDown), bundleA.contains(faceDown),
                visibleCards(bundleB, faceDown), bundleB.contains(faceDown));
    }

    private Node buildWaitingChoicePanel(List<Card> visible0, boolean faceDown0,
            List<Card> visible1, boolean faceDown1) {
        VBox root = panelRoot("상대의 선택을 기다리는 중입니다.");
        HBox bundlesRow = new HBox(40);
        bundlesRow.setAlignment(Pos.CENTER);
        activeBundle0 = bundleBox("묶음 ①", visible0, faceDown0);
        activeBundle1 = bundleBox("묶음 ②", visible1, faceDown1);
        bundlesRow.getChildren().addAll(activeBundle0, activeBundle1);
        root.getChildren().add(bundlesRow);
        return root;
    }

    private void ensureDistributionAnimationPanel(int chosenIndex, List<Card> chooseCards, List<Card> splitCards) {
        if (activeBundle0 != null && activeBundle1 != null) {
            return;
        }
        VBox chosenBox = bundleBox("선택한 묶음 " + (chosenIndex == 0 ? "①" : "②"), chooseCards, false);
        VBox otherBox = bundleBox("남은 묶음", splitCards, false);
        if (chosenIndex == 0) {
            activeBundle0 = chosenBox;
            activeBundle1 = otherBox;
        } else {
            activeBundle0 = otherBox;
            activeBundle1 = chosenBox;
        }

        VBox root = panelRoot("상대의 선택을 기다리는 중입니다.");
        HBox row = new HBox(40, activeBundle0, activeBundle1);
        row.setAlignment(Pos.CENTER);
        root.getChildren().add(row);
        setCenter(root);
    }

    private List<Card> visibleCards(List<Card> cards, Card faceDown) {
        List<Card> visible = new ArrayList<>(cards);
        visible.remove(faceDown);
        return visible;
    }

    private VBox bundleBox(String title, List<Card> visibleCards, boolean hasFaceDown, Node... controls) {
        FlowPane cards = new FlowPane(8, 8);
        cards.setAlignment(Pos.CENTER);
        cards.setPrefWrapLength(360);
        cards.setMaxWidth(360);
        for (Card c : visibleCards) {
            cards.getChildren().add(new CardView(c, true, true));
        }
        if (hasFaceDown) {
            cards.getChildren().add(new CardView(new com.oop.payday.model.card.WildCard(-1), false, true));
        }

        VBox box = new VBox(10);
        box.setAlignment(Pos.CENTER);
        box.getStyleClass().add("bundle-box");
        box.setPadding(new Insets(12));
        box.setMaxWidth(400);
        box.getChildren().addAll(new Label(title), cards);
        box.getChildren().addAll(controls);
        return box;
    }

    private void rememberActiveBundle(int index, VBox box) {
        if (index == 0) {
            activeBundle0 = box;
        } else {
            activeBundle1 = box;
        }
    }

    private void disableBundleButtons() {
        for (VBox box : List.of(activeBundle0, activeBundle1)) {
            if (box == null) {
                continue;
            }
            for (Node node : box.getChildren()) {
                if (node instanceof Button button) {
                    button.setDisable(true);
                }
            }
        }
    }

    private Node buildHelperSelectionPanel(HumanPlayer player, List<HelperCard> options, int chooseCount) {
        VBox root = panelRoot("도우미 후보 3장 중 " + chooseCount + "장을 선택하세요.");

        List<ToggleButton> toggles = new ArrayList<>();
        FlowPane row = new FlowPane(12, 12);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("helper-grid");
        row.setPrefWrapLength(860);
        for (HelperCard helper : options) {
            ToggleButton toggle = new ToggleButton(helper.displayName() + "\n" + helper.effectText());
            toggle.setWrapText(true);
            toggle.setUserData(helper);
            toggle.getStyleClass().add("helper-button");
            toggle.setPrefWidth(250);
            toggle.setPrefHeight(136);
            toggles.add(toggle);
            row.getChildren().add(toggle);
        }

        Button done = new Button("선택 완료");
        done.getStyleClass().add("menu-button");
        done.setOnAction(e -> {
            List<HelperCard> selected = toggles.stream()
                    .filter(ToggleButton::isSelected)
                    .map(t -> (HelperCard) t.getUserData())
                    .toList();
            if (selected.size() != chooseCount) {
                alert(chooseCount + "장을 선택하세요.");
                return;
            }
            clearCenter();
            player.provideHelpers(selected);
        });

        root.getChildren().addAll(row, done);
        return root;
    }

    /** 환금 패널을 (남은 카드, 누적 행동) 상태로 다시 그린다(환금/처분마다 재호출). */
    private void renderCashInPanel(HumanPlayer player, Team team, CashInContext context, List<Card> remaining,
            List<CashInAction> actions) {
        VBox root = panelRoot("세트를 선택해 환금하거나, 카드를 처분/도움 요청하세요. 끝나면 '턴 종료'.");
        root.getStyleClass().add("cash-panel");
        root.setSpacing(10);
        root.setPadding(new Insets(16));
        root.setMaxHeight(Double.MAX_VALUE);
        syncPendingCoinPreview(team, actions);
        updateBoardStatus();

        Set<Card> selected = new LinkedHashSet<>();
        Set<HelperCard> selectedCashHelpers = new LinkedHashSet<>();
        Label preview = new Label("선택된 카드: 없음");
        preview.getStyleClass().add("preview");

        Label holdLimit = new Label(holdLimitText(player, remaining, actions));
        holdLimit.getStyleClass().add(remaining.size() > player.holdLimit() && !isTuskerQueued(actions)
                ? "limit-warning" : "preview");

        Label planned = new Label("예약된 행동: " + actions.size() + "개");
        planned.getStyleClass().add("preview");

        Region infoSpacer = new Region();
        HBox.setHgrow(infoSpacer, Priority.ALWAYS);
        HBox infoRow = new HBox(8, holdLimit, infoSpacer, planned);
        infoRow.setAlignment(Pos.CENTER_LEFT);

        HBox cashHelperToggles = new HBox(6);
        cashHelperToggles.setAlignment(Pos.CENTER_LEFT);

        FlowPane cardsPane = new FlowPane(10, 10);
        cardsPane.setAlignment(Pos.CENTER);
        List<Card> sortedRemaining = new ArrayList<>(remaining);
        sortedRemaining.sort(CARD_ORDER);
        for (Card card : sortedRemaining) {
            CardView cv = new CardView(card, true, true);
            cv.setOnMouseClicked(e -> {
                cv.toggleSelected();
                if (cv.isSelected()) {
                    selected.add(card);
                } else {
                    selected.remove(card);
                }
                updateCashInPreview(preview, selected);
                refreshCashHelperToggles(cashHelperToggles, selectedCashHelpers, context.helpers(), actions, selected);
            });
            cardsPane.getChildren().add(cv);
        }
        refreshCashHelperToggles(cashHelperToggles, selectedCashHelpers, context.helpers(), actions, selected);
        StackPane cardStage = new StackPane(cardsPane);
        cardStage.getStyleClass().add("cash-card-stage");
        cardStage.setAlignment(Pos.CENTER);
        ScrollPane cardScroller = new ScrollPane(cardStage);
        cardScroller.getStyleClass().add("cash-card-scroll");
        cardScroller.setFitToWidth(true);
        cardScroller.setHbarPolicy(ScrollBarPolicy.NEVER);
        cardScroller.setVbarPolicy(ScrollBarPolicy.AS_NEEDED);
        cardScroller.setPrefViewportHeight((int) (CardView.PANEL_HEIGHT + 44));
        cardScroller.setMaxHeight((int) (CardView.PANEL_HEIGHT + 44));
        cardStage.minHeightProperty().bind(cardScroller.heightProperty().subtract(4));

        Button cashBtn = new Button("환금");
        cashBtn.getStyleClass().add("menu-button");
        cashBtn.setOnAction(e -> {
            List<Card> chosen = new ArrayList<>(selected);
            Optional<CashInEvaluator.Result> evaluation = CashInEvaluator.evaluate(chosen);
            if (evaluation.isEmpty()) {
                alert("선택한 카드는 유효한 세트가 아닙니다. 저주받은 그림은 같은 숫자 보물이 포함된 세트와 함께만 무료 처분할 수 있습니다.");
                return;
            }
            List<HelperCard> cashHelpers = selectedCashHelpers.stream()
                    .filter(helper -> canAttachToCash(helper, evaluation.get().set(), actions))
                    .toList();
            actions.add(cashHelpers.isEmpty()
                    ? new CashInAction.Cash(chosen)
                    : new CashInAction.CashWithHelpers(chosen, cashHelpers));
            remaining.removeAll(chosen);
            renderCashInPanel(player, team, context, remaining, actions);
        });

        Button discardBtn = new Button("처분");
        discardBtn.getStyleClass().add("menu-button");
        discardBtn.setOnAction(e -> {
            if (selected.isEmpty()) {
                alert("처분할 카드를 선택하세요.");
                return;
            }
            for (Card c : selected) {
                actions.add(new CashInAction.Discard(c));
            }
            remaining.removeAll(selected);
            renderCashInPanel(player, team, context, remaining, actions);
        });

        Button doneBtn = new Button("턴 종료");
        doneBtn.getStyleClass().add("menu-button");
        doneBtn.setOnAction(e -> {
            int projectedCount = projectedRemainingCount(remaining, actions);
            if (!isTuskerQueued(actions) && projectedCount > player.holdLimit()) {
                alert("보유 한도를 초과해서 턴을 종료할 수 없습니다. 현재 "
                        + projectedCount + "장 / 한도 " + player.holdLimit()
                        + "장입니다. 환금하거나 처분해 한도 이하로 맞춰주세요.");
                return;
            }
            clearCenter();
            player.provideCashIn(actions);
        });

        HBox helperButtons = new HBox(8);
        helperButtons.setAlignment(Pos.CENTER_LEFT);
        for (HelperCard helper : context.helpers()) {
            if (HelperRules.isCashReaction(helper.kind())) {
                continue;
            }
            Button helperBtn = new Button(helper.displayName());
            helperBtn.getStyleClass().add("helper-action-button");
            String disabledReason = standaloneDisabledReason(helper, context, remaining, actions);
            helperBtn.setDisable(disabledReason != null);
            helperBtn.setOnAction(e2 -> {
                if (helper.kind() == HelperKind.CROC_BROTHERS) {
                    Optional<HelperCard> target = chooseCrocTarget(context, remaining, actions);
                    if (target.isEmpty()) {
                        return;
                    }
                    actions.add(new CashInAction.UseHelper(helper, target.get()));
                } else {
                    actions.add(new CashInAction.UseHelper(helper));
                }
                renderCashInPanel(player, team, context, remaining, actions);
            });
            helperButtons.getChildren().add(helperBtn);
        }

        Region btnSpacer = new Region();
        HBox.setHgrow(btnSpacer, Priority.ALWAYS);
        HBox bottomBar = new HBox(8, cashHelperToggles, helperButtons, btnSpacer, cashBtn, discardBtn, doneBtn);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.getStyleClass().add("cash-bottom-bar");

        Node plannedActions = buildPlannedActionsPanel(player, team, context, remaining, actions);
        root.getChildren().addAll(infoRow, cardScroller, preview, plannedActions, bottomBar);
        setCenter(root);
    }

    private Node buildPlannedActionsPanel(HumanPlayer player, Team team, CashInContext context, List<Card> remaining,
            List<CashInAction> actions) {
        FlowPane list = new FlowPane(6, 6);
        list.setAlignment(Pos.CENTER_LEFT);
        list.getStyleClass().add("planned-action-list");
        if (actions.isEmpty()) {
            Label empty = new Label("예약된 행동 없음");
            empty.getStyleClass().add("planned-action-empty");
            list.getChildren().add(empty);
            return list;
        }
        for (int i = 0; i < actions.size(); i++) {
            int index = i;
            CashInAction action = actions.get(i);
            Label text = new Label(actionSummary(action));
            text.getStyleClass().add("planned-action-text");
            Button cancel = new Button("취소");
            cancel.getStyleClass().add("planned-action-cancel");
            cancel.setOnAction(e -> {
                restoreActionCards(remaining, action);
                actions.remove(index);
                renderCashInPanel(player, team, context, remaining, actions);
            });
            HBox item = new HBox(6, text, cancel);
            item.setAlignment(Pos.CENTER_LEFT);
            item.getStyleClass().add("planned-action-item");
            list.getChildren().add(item);
        }
        return list;
    }

    private String actionSummary(CashInAction action) {
        return switch (action) {
            case CashInAction.Cash cash -> "환금 " + cash.cards().size() + "장";
            case CashInAction.CashWithHelpers cash -> "환금 " + cash.cards().size() + "장 + "
                    + cash.helpers().stream().map(HelperCard::displayName).reduce((a, b) -> a + ", " + b).orElse("");
            case CashInAction.Discard discard -> "처분 " + discard.card().displayName();
            case CashInAction.UseHelper use -> use.copyTarget() == null
                    ? "도움 " + use.helper().displayName()
                    : "도움 " + use.helper().displayName() + " → " + use.copyTarget().displayName();
        };
    }

    private void restoreActionCards(List<Card> remaining, CashInAction action) {
        switch (action) {
            case CashInAction.Cash cash -> remaining.addAll(cash.cards());
            case CashInAction.CashWithHelpers cash -> remaining.addAll(cash.cards());
            case CashInAction.Discard discard -> remaining.add(discard.card());
            case CashInAction.UseHelper ignored -> {
            }
        }
    }

    private void refreshCashHelperToggles(HBox target, Set<HelperCard> selectedCashHelpers,
            List<HelperCard> helpers, List<CashInAction> actions, Set<Card> selected) {
        target.getChildren().clear();
        Optional<TreasureSet> selectedSet = CashInEvaluator.evaluate(new ArrayList<>(selected))
                .map(CashInEvaluator.Result::set);
        for (HelperCard helper : helpers) {
            if (!HelperRules.isCashReaction(helper.kind()) || helper.isUsed() || isHelperQueued(actions, helper)) {
                continue;
            }
            HelperSummary summary = helperSummary(helper.kind());
            ToggleButton toggle = new ToggleButton(summary.description());
            toggle.getStyleClass().add("cash-helper-toggle");
            boolean usable = selectedSet.isPresent() && canAttachToCash(helper, selectedSet.get(), actions);
            toggle.setDisable(!usable);
            if (!usable) {
                selectedCashHelpers.remove(helper);
            }
            toggle.setSelected(usable && selectedCashHelpers.contains(helper));
            toggle.setOnAction(e -> {
                if (toggle.isSelected()) {
                    selectedCashHelpers.add(helper);
                } else {
                    selectedCashHelpers.remove(helper);
                }
            });
            target.getChildren().add(toggle);
        }
    }

    private boolean canAttachToCash(HelperCard helper, TreasureSet set, List<CashInAction> actions) {
        return !helper.isUsed()
                && !isHelperQueued(actions, helper)
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

    private String standaloneDisabledReason(HelperCard helper, CashInContext context, List<Card> remaining,
            List<CashInAction> actions) {
        if (helper.isUsed()) {
            return "이미 사용한 도우미입니다.";
        }
        if (isHelperQueued(actions, helper)) {
            return "이미 이번 턴 행동에 예약된 도우미입니다.";
        }
        return switch (helper.kind()) {
            case DOUG -> remaining.stream().anyMatch(c -> !(c instanceof CursedCard))
                    ? null : "저주가 아닌 보관 카드가 필요합니다.";
            case TUSKER -> null;
            case VIPER -> remaining.stream().anyMatch(CursedCard.class::isInstance)
                    ? null : "저주받은 그림을 보유해야 합니다.";
            case JUNK_DEALER -> context.discardPile().stream().anyMatch(Card::isWild)
                    ? null : "버림 더미에 굉장한 보물이 있어야 합니다.";
            case CROC_BROTHERS -> crocCopyTargets(context, remaining, actions).isEmpty()
                    ? "복사 가능한 사용 완료 도우미가 없습니다." : null;
            default -> "환금할 세트에 붙여 사용하는 도우미입니다.";
        };
    }

    private Optional<HelperCard> chooseCrocTarget(CashInContext context, List<Card> remaining,
            List<CashInAction> actions) {
        List<HelperCard> targets = crocCopyTargets(context, remaining, actions);
        if (targets.isEmpty()) {
            alert("복사할 수 있는 사용 완료 도우미가 없습니다.");
            return Optional.empty();
        }
        ChoiceDialog<HelperCard> dialog = new ChoiceDialog<>(targets.get(0), targets);
        dialog.setTitle("크록 형제");
        dialog.setHeaderText("복사할 도우미를 선택하세요.");
        dialog.setContentText("복사 대상");
        return dialog.showAndWait();
    }

    private List<HelperCard> crocCopyTargets(CashInContext context, List<Card> remaining,
            List<CashInAction> actions) {
        TreasureSet lastSet = lastQueuedCashSet(actions).orElse(null);
        return context.usedHelpers().stream()
                .filter(helper -> helper.kind() != HelperKind.CROC_BROTHERS)
                .filter(helper -> canCopyInPreview(helper.kind(), lastSet, remaining, context.discardPile()))
                .toList();
    }

    private Optional<TreasureSet> lastQueuedCashSet(List<CashInAction> actions) {
        Optional<TreasureSet> result = Optional.empty();
        for (CashInAction action : actions) {
            if (action instanceof CashInAction.Cash cash) {
                result = CashInEvaluator.evaluate(cash.cards()).map(CashInEvaluator.Result::set);
            } else if (action instanceof CashInAction.CashWithHelpers cash) {
                result = CashInEvaluator.evaluate(cash.cards()).map(CashInEvaluator.Result::set);
            }
        }
        return result;
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

    private String holdLimitText(Player player, List<Card> remaining, List<CashInAction> actions) {
        String suffix = isTuskerQueued(actions)
                ? " · 완력의 투스커 예약됨"
                : projectedRemainingCount(remaining, actions) > player.holdLimit() ? " · 턴 종료 불가" : "";
        return "보유 한도: " + projectedRemainingCount(remaining, actions) + " / " + player.holdLimit() + suffix;
    }

    private int projectedRemainingCount(List<Card> remaining, List<CashInAction> actions) {
        int count = remaining.size();
        if (isHelperKindQueued(actions, HelperKind.VIPER)) {
            count -= remaining.stream().filter(com.oop.payday.model.card.CursedCard.class::isInstance).count();
        }
        if (isHelperKindQueued(actions, HelperKind.JUNK_DEALER)) {
            count += 1;
        }
        return Math.max(0, count);
    }

    private boolean isTuskerQueued(List<CashInAction> actions) {
        return isHelperKindQueued(actions, HelperKind.TUSKER);
    }

    private boolean isHelperKindQueued(List<CashInAction> actions, HelperKind kind) {
        return actions.stream().anyMatch(action -> switch (action) {
            case CashInAction.UseHelper use -> use.helper().kind() == kind
                    || (use.copyTarget() != null && use.copyTarget().kind() == kind);
            case CashInAction.CashWithHelpers cash -> cash.helpers().stream()
                    .anyMatch(helper -> helper.kind() == kind);
            default -> false;
        });
    }

    private boolean isHelperQueued(List<CashInAction> actions, HelperCard helper) {
        return actions.stream().anyMatch(action -> switch (action) {
            case CashInAction.UseHelper use -> use.helper() == helper;
            case CashInAction.CashWithHelpers cash -> cash.helpers().contains(helper);
            default -> false;
        });
    }

    private void updateCashInPreview(Label preview, Set<Card> selected) {
        if (selected.isEmpty()) {
            preview.setText("선택된 카드: 없음");
            return;
        }
        Optional<CashInEvaluator.Result> evaluation = CashInEvaluator.evaluate(new ArrayList<>(selected));
        if (evaluation.isPresent()) {
            TreasureSet set = evaluation.get().set();
            String cursed = evaluation.get().hasFreeCursedCards()
                    ? " · 저주 " + evaluation.get().freeCursedCards().size() + "장 무료 처분"
                    : "";
            preview.setText("환금 가능: " + set.type().korean() + " " + set.size()
                    + "장 → " + set.coin() + "코인" + cursed);
        } else {
            preview.setText("선택 " + selected.size() + "장 — 유효한 세트 아님");
        }
    }

    private Node buildGameOverPanel(Team winner) {
        VBox root = panelRoot("게임 종료");
        root.setSpacing(16);

        // 보드 레이아웃 반영: teamB(상대) = 위, teamA(나) = 아래
        VBox topCard    = buildScoreCard(teamB, teamB == winner, false);
        VBox bottomCard = buildScoreCard(teamA, teamA == winner, true);

        VBox scoreColumn = new VBox(12, topCard, bottomCard);
        scoreColumn.setAlignment(Pos.CENTER);
        scoreColumn.setMaxWidth(440);

        Button mainMenu = new Button("메인 화면");
        mainMenu.getStyleClass().add("menu-button");
        mainMenu.setOnAction(e -> {
            try {
                GameApp.get().showScene("main_menu.fxml");
            } catch (java.io.IOException ex) {
                alert("메인 화면으로 이동할 수 없습니다: " + ex.getMessage());
            }
        });

        root.getChildren().addAll(scoreColumn, mainMenu);
        return root;
    }

    private VBox buildScoreCard(Team team, boolean win, boolean isBottom) {
        Label badge = new Label(win ? "승리" : "패배");
        badge.getStyleClass().add(win ? "score-win-badge" : "score-lose-badge");

        Label coins = new Label(String.valueOf(team.coins()));
        coins.getStyleClass().add(win ? "score-coins-win" : "score-coins-lose");

        Label name = new Label(team.name());
        name.getStyleClass().add("score-name");

        VBox card;
        if (isBottom) {
            card = new VBox(8, coins, badge, name);
        } else {
            card = new VBox(8, name, badge, coins);
        }
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().addAll("score-card", win ? "score-card-win" : "score-card-lose");
        card.setPadding(new Insets(18, 36, 18, 36));
        return card;
    }

    // ===== 공통 헬퍼 =====

    private VBox panelRoot(String guide) {
        VBox root = new VBox(18);
        root.setAlignment(Pos.TOP_CENTER);
        root.setPadding(new Insets(24));
        root.setMaxWidth(1120);
        root.setMaxHeight(Region.USE_PREF_SIZE);
        root.setMinHeight(Region.USE_PREF_SIZE);
        Label g = new Label(guide);
        g.getStyleClass().add("guide");
        g.setWrapText(true);
        root.getChildren().add(g);
        return root;
    }

    // ===== 게임 시작 간부 애니메이션 =====

    private void playOfficerSetupAnimation(List<Player> players) {
        VBox panel = new VBox(22);
        panel.setAlignment(Pos.CENTER);
        panel.setPadding(new Insets(36, 48, 36, 48));
        panel.setMaxWidth(560);

        Label title = new Label("간부 배정");
        title.getStyleClass().add("waiting-label");

        Label sub = new Label("각 플레이어에게 간부가 배정되었습니다");
        sub.setStyle("-fx-text-fill: #8aa0a8; -fx-font-size: 13px;");

        HBox cardsRow = new HBox(32);
        cardsRow.setAlignment(Pos.CENTER);

        List<StackPane> containers = new ArrayList<>();
        List<Node> fronts = new ArrayList<>();

        for (Player p : players) {
            StackPane container = new StackPane(buildOfficerCardBack());
            container.setPrefSize(CardView.WIDTH, CardView.HEIGHT);
            container.setMinSize(CardView.WIDTH, CardView.HEIGHT);
            container.setMaxSize(CardView.WIDTH, CardView.HEIGHT);

            String desc = p.name() + (isLocalActor(p) ? "  (나)" : "");
            Label nameLabel = new Label(desc);
            nameLabel.setStyle("-fx-text-fill: #b9c8c2; -fx-font-size: 12px; -fx-font-weight: bold;");

            VBox col = new VBox(10, container, nameLabel);
            col.setAlignment(Pos.CENTER);
            cardsRow.getChildren().add(col);
            containers.add(container);
            fronts.add(buildOfficerCardFront(p));
        }

        panel.getChildren().addAll(title, sub, cardsRow);
        setCenter(panel);

        SequentialTransition seq = new SequentialTransition();
        seq.getChildren().add(new PauseTransition(Duration.millis(500)));
        for (int i = 0; i < containers.size(); i++) {
            seq.getChildren().add(buildCardFlip(containers.get(i), fronts.get(i)));
            seq.getChildren().add(new PauseTransition(Duration.millis(380)));
        }
        seq.getChildren().add(new PauseTransition(Duration.millis(1300)));
        seq.setOnFinished(e -> { clearCenter(); playNextOverlay(); });
        seq.play();
    }

    private Node buildOfficerCardBack() {
        StackPane card = new StackPane();
        card.setPrefSize(CardView.WIDTH, CardView.HEIGHT);
        card.setMaxSize(CardView.WIDTH, CardView.HEIGHT);
        card.setStyle(
            "-fx-background-color: #0d1518, linear-gradient(to bottom, #1e3040, #0e1e2a);"
            + " -fx-background-insets: 0, 3; -fx-background-radius: 10, 7;"
            + " -fx-border-color: rgba(242,211,107,0.45); -fx-border-radius: 10; -fx-border-width: 1.5;"
            + " -fx-effect: dropshadow(gaussian,rgba(0,0,0,0.5),8,0.2,0,3);");
        Label q = new Label("?");
        q.setStyle("-fx-text-fill: rgba(242,211,107,0.5); -fx-font-size: 32px; -fx-font-weight: bold;");
        card.getChildren().add(q);
        return card;
    }

    private Node buildOfficerCardFront(Player player) {
        boolean hasOfficer = player.officer() != null;
        String officerName   = hasOfficer ? player.officer().korean()     : "없음";
        String officerEffect = hasOfficer ? player.officer().effectText() : "";

        StackPane card = new StackPane();
        card.setPrefSize(CardView.WIDTH, CardView.HEIGHT);
        card.setMaxSize(CardView.WIDTH, CardView.HEIGHT);
        card.setStyle(
            "-fx-background-color: #0d1518, linear-gradient(to bottom, #1a2f40, #0e1e2c);"
            + " -fx-background-insets: 0, 3; -fx-background-radius: 10, 7;"
            + " -fx-border-color: rgba(242,211,107,0.85); -fx-border-radius: 10; -fx-border-width: 1.5;"
            + " -fx-effect: dropshadow(gaussian,rgba(242,211,107,0.25),14,0.2,0,0);");

        Label icon = new Label("★");
        icon.setStyle("-fx-text-fill: rgba(242,211,107,0.45); -fx-font-size: 20px;");

        Label nameLbl = new Label(officerName);
        nameLbl.setStyle("-fx-text-fill: #f2d36b; -fx-font-size: 14px; -fx-font-weight: bold;"
            + " -fx-alignment: center; -fx-text-alignment: center;");
        nameLbl.setWrapText(true);
        nameLbl.setMaxWidth(CardView.WIDTH - 14);

        Label effectLbl = new Label(officerEffect);
        effectLbl.setStyle("-fx-text-fill: #91dfc0; -fx-font-size: 9px;"
            + " -fx-alignment: center; -fx-text-alignment: center;");
        effectLbl.setWrapText(true);
        effectLbl.setMaxWidth(CardView.WIDTH - 14);

        VBox content = new VBox(5, icon, nameLbl, effectLbl);
        content.setAlignment(Pos.CENTER);
        content.setMaxWidth(CardView.WIDTH - 14);
        card.getChildren().add(content);
        return card;
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

    // ===== 슬쩍하기 애니메이션 =====

    private void playStealAnimation(Player player, Card drawnCard) {
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
            CardView cv = new CardView(new com.oop.payday.model.card.WildCard(-1), false, true);
            cv.setTranslateX((i - 1) * 7.0);
            cv.setTranslateY((i - 1) * -4.0);
            cv.setRotate((i - 1) * 6.0);
            pile.getChildren().add(cv);
        }
        pile.setPrefSize(CardView.PANEL_WIDTH + 24, CardView.PANEL_HEIGHT + 18);

        // 획득 카드 영역 (처음에는 뒷면 + 투명)
        StackPane drawnSlot = new StackPane();
        drawnSlot.setPrefSize(CardView.PANEL_WIDTH, CardView.PANEL_HEIGHT);
        drawnSlot.setMinSize(CardView.PANEL_WIDTH, CardView.PANEL_HEIGHT);
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

    private Node waitingPanel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("waiting-label");
        label.setWrapText(true);

        StackPane panel = new StackPane(label);
        panel.getStyleClass().add("waiting-panel");
        panel.setPadding(new Insets(28));
        panel.setMaxWidth(680);
        panel.setMinHeight(180);
        return panel;
    }

    private void setWaiting(String text) {
        opponentWaitingVisible = true;
        setCenterAnimated(waitingPanel(text), true);
    }

    private void setWaitingIfCurrent(int phaseToken, String text) {
        if (phaseToken == phaseRevision) {
            setWaiting(text);
        }
    }

    private void setWaitingChoiceIfCurrent(int phaseToken, GameListener.BundlePair bundles) {
        if (phaseToken != phaseRevision) {
            return;
        }
        opponentWaitingVisible = true;
        setCenterAnimated(buildWaitingChoicePanel(bundles), true);
    }

    private void clearCenterIfOpponentWaiting() {
        if (opponentWaitingVisible) {
            clearCenter();
        }
    }

    private void clearCenter() {
        setCenter(new StackPane());
    }

    private void setCenter(Node node) {
        opponentWaitingVisible = false;
        centerRevision++;
        stopActiveCenterTransition();
        node.setOpacity(1);
        contentArea.getChildren().setAll(node);
    }

    private void setCenterAnimated(Node node) {
        setCenterAnimated(node, false);
    }

    private void setCenterAnimated(Node node, boolean opponentWaiting) {
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

    private FadeTransition fade(Node node, double from, double to, int millis) {
        FadeTransition transition = new FadeTransition(Duration.millis(millis), node);
        transition.setFromValue(from);
        transition.setToValue(to);
        return transition;
    }

    private void playDistributionTransition(int chosenIndex, Team chooseTeam, Team splitTeam, Runnable afterFinished) {
        enqueueOverlay(() -> {
            VBox chosenBox = chosenIndex == 0 ? activeBundle0 : activeBundle1;
            VBox otherBox = chosenIndex == 0 ? activeBundle1 : activeBundle0;
            if (chosenBox == null || otherBox == null) {
                afterFinished.run();
                playNextOverlay();
                return;
            }

            chosenBox.getStyleClass().add("bundle-picked");
            otherBox.getStyleClass().remove("bundle-picked");
            setMessage(chooseTeam.name() + " 선택 완료 — 보물을 분배합니다");
            PauseTransition hold = new PauseTransition(Duration.millis(700));
            ParallelTransition move = new ParallelTransition(
                    moveBundle(chosenBox, verticalDirectionFor(chooseTeam) * 260),
                    moveBundle(otherBox, verticalDirectionFor(splitTeam) * 260),
                    fade(chosenBox, 1, 0, 760),
                    fade(otherBox, 1, 0, 760));
            hold.setOnFinished(e -> move.play());
            move.setOnFinished(e -> {
                resetBundleAnimation(chosenBox);
                resetBundleAnimation(otherBox);
                afterFinished.run();
                clearCenter();
                playNextOverlay();
            });
            hold.play();
        });
    }

    private TranslateTransition moveBundle(Node node, double y) {
        TranslateTransition transition = new TranslateTransition(Duration.millis(760), node);
        transition.setFromY(0);
        transition.setToY(y);
        return transition;
    }

    private int verticalDirectionFor(Team team) {
        return team == teamA ? 1 : -1;
    }

    private void resetBundleAnimation(Node node) {
        node.setTranslateY(0);
        node.setOpacity(1);
    }

    private void enqueueOverlay(Runnable animation) {
        overlayQueue.add(animation);
        if (!overlayPlaying) {
            playNextOverlay();
        }
    }

    private void playNextOverlay() {
        Runnable next = overlayQueue.poll();
        if (next == null) {
            overlayPlaying = false;
            flushAfterOverlayQueue();
            return;
        }
        overlayPlaying = true;
        next.run();
    }

    private void runAfterOverlay(Runnable action) {
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

    private void setMessage(String message) {
        messageLabel.setText(message);
    }

    private void updateBoardStatus() {
        updateFields();
    }

    private void updateFields() {
        if (distributionFieldUpdatePending) {
            return;
        }
        renderField(fieldATitleLabel, fieldAOfficerLabel, fieldAOfficerEffectLabel, fieldACoinsLabel,
                fieldACountLabel, fieldAFlow, fieldAHelperFlow, teamA, "내 필드");
        renderField(fieldBTitleLabel, fieldBOfficerLabel, fieldBOfficerEffectLabel, fieldBCoinsLabel,
                fieldBCountLabel, fieldBFlow, fieldBHelperFlow, teamB, "상대 필드");
    }

    private void renderField(Label titleLabel, Label officerLabel, Label officerEffectLabel,
            Label coinsLabel, Label countLabel, FlowPane field, VBox helpers, Team team, String fallback) {
        if (team == null) {
            titleLabel.setText(fallback);
            officerLabel.setText("간부 없음");
            officerEffectLabel.setText("");
            coinsLabel.setText("0 코인");
            countLabel.setText("");
            field.getChildren().clear();
            helpers.getChildren().clear();
            return;
        }
        Player player = team.leader();
        titleLabel.setText(fallback + " · " + player.name());
        if (player.officer() != null) {
            officerLabel.setText(player.officer().korean());
            officerEffectLabel.setText(player.officer().effectText());
        } else {
            officerLabel.setText("간부 없음");
            officerEffectLabel.setText("");
        }
        coinsLabel.setText(displayedCoins(team) + " 코인");
        countLabel.setText("보유 " + player.holdingCount() + " / " + player.holdLimit());
        renderSidebarHelpers(helpers, player);
        field.getChildren().clear();
        if (player.holdings().isEmpty()) {
            Label empty = new Label("보물 없음");
            empty.getStyleClass().add("field-empty");
            field.getChildren().add(empty);
            return;
        }
        List<Card> holdings = new ArrayList<>(player.holdings());
        holdings.sort(CARD_ORDER);
        for (Card card : holdings) {
            field.getChildren().add(new CardView(card, true));
        }
    }

    private void renderSidebarHelpers(VBox target, Player player) {
        target.getChildren().clear();
        boolean local = isLocalActor(player);
        for (HelperCard helper : player.helpers()) {
            boolean faceUp = local || helper.isUsed();
            target.getChildren().add(buildSidebarHelperCard(helper, faceUp));
        }
    }

    private Node buildSidebarHelperCard(HelperCard helper, boolean faceUp) {
        StackPane card = new StackPane();
        card.getStyleClass().addAll("sidebar-helper-card", faceUp ? "sidebar-helper-front" : "sidebar-helper-back");
        if (helper.isUsed()) {
            card.getStyleClass().add("sidebar-helper-used");
        }

        VBox content = new VBox(2);
        content.setAlignment(Pos.CENTER);
        if (faceUp) {
            Label name = new Label(helper.displayName());
            name.getStyleClass().add("sidebar-helper-name");
            name.setWrapText(true);
            Label description = new Label(helper.effectText());
            description.getStyleClass().add("sidebar-helper-description");
            description.setWrapText(true);
            content.getChildren().addAll(name, description);
        } else {
            Label mark = new Label("?");
            mark.getStyleClass().add("sidebar-helper-back-mark");
            Label label = new Label("비공개");
            label.getStyleClass().add("sidebar-helper-kind");
            label.setWrapText(true);
            content.getChildren().addAll(mark, label);
        }
        card.getChildren().add(content);

        if (helper.isUsed()) {
            Label used = new Label("사용 완료");
            used.setMaxWidth(Double.MAX_VALUE);
            used.getStyleClass().add("sidebar-helper-used-ribbon");
            StackPane.setAlignment(used, Pos.BOTTOM_CENTER);
            card.getChildren().add(used);
        }

        return card;
    }

    private HelperSummary helperSummary(HelperKind kind) {
        return switch (kind) {
            case CUCKOO -> new HelperSummary("색 3+ 환금 +3");
            case LEO -> new HelperSummary("숫자 3+ 환금 +3");
            case LUCKY -> new HelperSummary("색 5장 환금 +7");
            case ALPHA -> new HelperSummary("1 네 장 환금 승리");
            case DOUG -> new HelperSummary("비저주 버리고 드로우");
            case TUSKER -> new HelperSummary("1장 드로우, 한도 무시");
            case VIPER -> new HelperSummary("저주 제거 후 +코인");
            case JUNK_DEALER -> new HelperSummary("와일드 회수, 환금 금지");
            case CROC_BROTHERS -> new HelperSummary("사용된 도우미 복사");
        };
    }

    private record HelperSummary(String description) {
    }

    private Team teamFor(Player player) {
        if (teamA != null && teamA.members().contains(player)) {
            return teamA;
        }
        if (teamB != null && teamB.members().contains(player)) {
            return teamB;
        }
        return null;
    }

    private Team otherTeam(Team team) {
        if (team == teamA) {
            return teamB;
        }
        if (team == teamB) {
            return teamA;
        }
        return null;
    }

    private boolean isLocalActor(Player player) {
        return player != null && player == localPlayer;
    }

    private int displayedCoins(Team team) {
        return Math.max(0, team.coins() + pendingCoinPreview.getOrDefault(team, 0));
    }

    private void syncPendingCoinPreview(Team team, List<CashInAction> actions) {
        if (team == null) {
            return;
        }
        int delta = projectedCoinDelta(team, actions);
        if (delta == 0) {
            pendingCoinPreview.remove(team);
        } else {
            pendingCoinPreview.put(team, delta);
        }
    }

    private int projectedCoinDelta(Team team, List<CashInAction> actions) {
        int coins = team.coins();
        int delta = 0;
        for (CashInAction action : actions) {
            int change = switch (action) {
                case CashInAction.Cash cash -> CashInEvaluator.evaluate(cash.cards())
                        .map(result -> result.set().coin())
                        .orElse(0);
                case CashInAction.CashWithHelpers cash -> CashInEvaluator.evaluate(cash.cards())
                        .map(result -> result.set().coin() + cash.helpers().stream()
                                .mapToInt(this::previewHelperCoinDelta)
                                .sum())
                        .orElse(0);
                case CashInAction.Discard discard -> discard.card() instanceof CursedCard
                        ? -Math.min(2, coins)
                        : 0;
                case CashInAction.UseHelper use -> previewHelperCoinDelta(use.helper())
                        + (use.copyTarget() == null ? 0 : previewHelperCoinDelta(use.copyTarget()));
            };
            coins = Math.max(0, coins + change);
            delta += change;
        }
        return delta;
    }

    private int previewHelperCoinDelta(HelperCard helper) {
        return switch (helper.kind()) {
            case CUCKOO, LEO -> 3;
            case LUCKY -> 7;
            default -> 0;
        };
    }

    private void previewCoinDelta(Team team, int requestedDelta) {
        if (team == null || requestedDelta == 0) {
            return;
        }
        int actualDelta = requestedDelta < 0
                ? Math.max(requestedDelta, -displayedCoins(team))
                : requestedDelta;
        if (actualDelta == 0) {
            return;
        }
        pendingCoinPreview.merge(team, actualDelta, Integer::sum);
        if (pendingCoinPreview.get(team) == 0) {
            pendingCoinPreview.remove(team);
        }
        updateBoardStatus();
        playCoinChangeAnimation(team, actualDelta);
    }

    private int previewDiscardDelta(Team team, Set<Card> selected) {
        if (team == null || selected.isEmpty()) {
            return 0;
        }
        int coins = displayedCoins(team);
        int delta = 0;
        for (Card card : selected) {
            if (card instanceof com.oop.payday.model.card.CursedCard) {
                int spend = Math.min(2, coins);
                coins -= spend;
                delta -= spend;
            }
        }
        return delta;
    }

    private int consumePendingCoinDelta(Team team, int actualDelta) {
        int pending = pendingCoinPreview.getOrDefault(team, 0);
        int consumed = 0;
        if (actualDelta > 0 && pending > 0) {
            consumed = Math.min(actualDelta, pending);
        } else if (actualDelta < 0 && pending < 0) {
            consumed = -Math.min(-actualDelta, -pending);
        }
        int remainder = pending - consumed;
        if (remainder == 0) {
            pendingCoinPreview.remove(team);
        } else if (pending != 0) {
            pendingCoinPreview.put(team, remainder);
        }
        return actualDelta - consumed;
    }

    private void playCoinChangeAnimation(Team team, int delta) {
        Label coinsLabel = team == teamA ? fieldACoinsLabel : team == teamB ? fieldBCoinsLabel : null;
        if (coinsLabel == null || delta == 0 || globalOverlay == null) {
            return;
        }
        Label floating = new Label((delta > 0 ? "+" : "") + delta);
        floating.getStyleClass().add("coin-float");
        floating.getStyleClass().add(delta > 0 ? "coin-float-gain" : "coin-float-loss");
        floating.setMouseTransparent(true);
        floating.setOpacity(0);

        Bounds bounds = coinsLabel.localToScene(coinsLabel.getBoundsInLocal());
        Point2D anchor = globalOverlay.sceneToLocal(bounds.getMaxX() + 8, bounds.getMinY() - 6);
        floating.setLayoutX(anchor.getX());
        floating.setLayoutY(anchor.getY());
        globalOverlay.getChildren().add(floating);

        FadeTransition fadeIn = fade(floating, 0, 1, 120);
        TranslateTransition rise = new TranslateTransition(Duration.millis(900), floating);
        rise.setFromY(0);
        rise.setToY(-44);
        FadeTransition fadeOut = fade(floating, 1, 0, 1100);
        ParallelTransition floatOut = new ParallelTransition(rise, fadeOut);
        fadeIn.setOnFinished(e -> floatOut.play());
        floatOut.setOnFinished(e -> globalOverlay.getChildren().remove(floating));
        fadeIn.play();
    }

    private void alert(String message) {
        Alert a = new Alert(AlertType.WARNING, message);
        a.setHeaderText(null);
        a.setTitle("알림");
        a.showAndWait();
    }
}
