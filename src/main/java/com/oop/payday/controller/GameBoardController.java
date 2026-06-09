package com.oop.payday.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.oop.payday.app.GameApp;
import com.oop.payday.net.FanOutGameListener;
import com.oop.payday.net.NetMessage;
import com.oop.payday.net.NetworkBroadcaster;
import com.oop.payday.net.PublicBoardState;
import com.oop.payday.net.WireCodec;
import com.oop.payday.net.GameServer;
import com.oop.payday.net.GameClient;
import com.oop.payday.net.ClientMirror;
import com.oop.payday.player.NetworkPlayer;
import com.oop.payday.decision.BundleView;
import com.oop.payday.decision.CashInAction;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
import com.oop.payday.decision.SplitDecision;
import com.oop.payday.decision.TeamDistribution;
import com.oop.payday.game.Game;
import com.oop.payday.game.GameConfig;
import com.oop.payday.game.GameListener;
import com.oop.payday.game.MatchSetup;
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
import com.oop.payday.player.Player;
import com.oop.payday.view.CardView;
import com.oop.payday.view.RulebookBuilder;
import com.oop.payday.view.ScoreTableBuilder;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.geometry.Bounds;
import javafx.scene.image.WritableImage;
import javafx.scene.SnapshotParameters;
import javafx.scene.paint.Color;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.concurrent.CountDownLatch;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * 게임 보드 컨트롤러. 모델 이벤트({@link GameListener})를 화면에 반영하고,
 * 엔진의 입력 요청 알림({@code onRequestXxx})에 맞춰 단계별 입력 패널을 띄운다.
 *
 * <p>게임 로직은 별도 스레드에서 돌고, 모든 UI 변경은 {@link Platform#runLater} 로 처리한다.
 */
public final class GameBoardController implements GameListener, Initializable {

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
    @FXML private StackPane screenOverlay;
    @FXML private Label turnLabel;
    @FXML private Label messageLabel;

    private HBox fieldASortBox;
    private HBox fieldBSortBox;

    private Team teamA;
    private Team teamB;
    private Player localPlayer;
    private InputGateway inputGateway;
    private final Set<Card> cashSelection = new LinkedHashSet<>(); // 환금 패널 카드 선택(재렌더 사이 보존)
    private final Set<HelperCard> cashSelectedHelpers = new LinkedHashSet<>(); // 콤보 도우미 토글 상태
    // 환금 패널 증분 업데이트 참조. 카드 선택 UI는 중앙 패널이 아니라 내 필드에 붙인다.
    private boolean cashPhaseActive;
    private Label cashPreviewLabel;
    private Label cashHoldLimitLabel;
    private HBox cashHelperTogglesBox;
    private HBox cashHelperButtonsBox;
    private List<Card> cashRemaining;
    private CashInContext cashCashContext;
    private Team currentSplitTeam;
    private BoardAnimator animator; // 오버레이 큐·센터 전환·발동 연출 (startGame 에서 초기화)
    private int activeFieldACoinFloats;
    private int activeFieldBCoinFloats;
    private boolean distributionFieldUpdatePending;
    private boolean introPhase = true;
    private boolean gameOver = false;
    private Node cachedScoreTablePanel;

    /** 게임 모드. 다시하기/나가기 동작과 일시정지 메뉴 버튼 노출을 결정한다. */
    private enum Mode { OFFLINE, HOST, CLIENT }
    private Mode mode;
    private GameConfig config;            // 오프라인/호스트 재시작용
    private MatchSetup matchSetup;        // 오프라인 자리 배치(재시작용)
    private GameServer server;            // 호스트 세션 (소켓·리더 스레드 유지)
    private GameClient client;            // 클라이언트 세션
    private NetworkPlayer networkPlayer;  // 현재 호스트측 원격 대리자 (재시작 시 교체)
    private Game currentGame;             // 진행 중 게임 (중단용)
    private Thread gameThread;            // 게임 루프 스레드 (중단용)
    private boolean pauseMenuOpen;
    private boolean keyHandlerInstalled;
    private VBox activeBundle0;
    private VBox activeBundle1;
    private int phaseRevision;
    private boolean opponentCashDone = false;
    private Node opponentCashDoneBadge;
    private final Set<Card> newlyReceivedCards = new HashSet<>();
    private final Set<Card> newlyReceivedOpponentCards = new HashSet<>();

    private static final Comparator<Card> CARD_ORDER_BY_COLOR =
            Comparator.<Card>comparingInt(GameBoardController::cardRank)
                    .thenComparingInt(GameBoardController::cardColorRank)
                    .thenComparingInt(GameBoardController::cardNumberRank);

    private static final Comparator<Card> CARD_ORDER_BY_NUMBER =
            Comparator.<Card>comparingInt(GameBoardController::cardRank)
                    .thenComparingInt(GameBoardController::cardNumberRank)
                    .thenComparingInt(GameBoardController::cardColorRank);

    private Comparator<Card> sortModeA = CARD_ORDER_BY_COLOR;
    private Comparator<Card> sortModeB = CARD_ORDER_BY_COLOR;

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

    @Override
    public void initialize(java.net.URL location, java.util.ResourceBundle resources) {
        configureFieldStage(fieldAStage, fieldAFlow);
        configureFieldStage(fieldBStage, fieldBFlow);
        fieldASortBox = addSortButtons(fieldAStage, true);
        fieldBSortBox = addSortButtons(fieldBStage, false);
    }

    /**
     * 필드 스테이지는 카드 수와 무관하게 항상 보드 가로폭을 채우도록 강제한다.
     * 그렇지 않으면 VBox 안에서 자식 선호폭만큼만 줄어들어 외곽선이 카드 뒤에 묻힐 수 있다.
     */
    private void configureFieldStage(StackPane stage, FlowPane flow) {
        stage.setMaxWidth(Double.MAX_VALUE);
        flow.setMaxWidth(Double.MAX_VALUE);
        flow.prefWrapLengthProperty().bind(stage.widthProperty().subtract(64));
    }

    private HBox addSortButtons(StackPane stage, boolean isFieldA) {
        ToggleGroup group = new ToggleGroup();
        ToggleButton byColor  = new ToggleButton("색 기준");
        ToggleButton byNumber = new ToggleButton("숫자 기준");
        byColor.setToggleGroup(group);
        byNumber.setToggleGroup(group);
        byColor.getStyleClass().add("sort-toggle-button");
        byNumber.getStyleClass().add("sort-toggle-button");
        byColor.setSelected(true);

        group.selectedToggleProperty().addListener((obs, old, newToggle) -> {
            if (newToggle == null) {
                old.setSelected(true);
                return;
            }
            Comparator<Card> chosen = newToggle == byColor ? CARD_ORDER_BY_COLOR : CARD_ORDER_BY_NUMBER;
            if (isFieldA) sortModeA = chosen;
            else          sortModeB = chosen;
            updateBoardStatus();
        });

        HBox sortBox = new HBox(4, byColor, byNumber);
        sortBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(sortBox, Pos.TOP_LEFT);
        StackPane.setMargin(sortBox, new Insets(6, 0, 0, 10));
        stage.getChildren().add(sortBox);
        return sortBox;
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

    /** 메인 메뉴 대기실에서 호출. 슬롯 구성대로 팀/플레이어를 만들고 게임 스레드를 시작한다 (오프라인). */
    public void startGame(MatchSetup setup) {
        resetBoard();
        this.mode = Mode.OFFLINE;
        this.matchSetup = setup;
        this.config = setup.gameConfig();
        this.server = null;
        this.client = null;
        this.networkPlayer = null;

        // 사람(방장)이 속한 대기실 팀을 항상 teamA(=내 필드)로 둔다.
        boolean humanInA = setup.teamA().stream()
                .anyMatch(s -> s.kind() == MatchSetup.SlotKind.HUMAN_LOCAL);
        List<MatchSetup.Slot> mySlots = humanInA ? setup.teamA() : setup.teamB();
        List<MatchSetup.Slot> oppSlots = humanInA ? setup.teamB() : setup.teamA();

        HumanPlayer[] humanRef = new HumanPlayer[1];
        List<Player> myPlayers = buildPlayers(mySlots, humanRef);
        List<Player> oppPlayers = buildPlayers(oppSlots, humanRef);

        HumanPlayer human = humanRef[0];
        localPlayer = human;
        inputGateway = human != null ? new LocalInputGateway(human) : null;

        teamA = new Team(teamName(myPlayers, "우리 팀"), myPlayers);
        teamB = new Team(teamName(oppPlayers, "상대 팀"), oppPlayers);
        animator = new BoardAnimator(contentArea, globalOverlay, centerArea, this::isLocalActor, CARD_ORDER_BY_COLOR);
        updateBoardStatus();

        Game game = new Game(config, teamA, teamB, this);
        this.currentGame = game;
        startGameThread(game);
        installEscHandler();
    }

    /** 슬롯 목록을 플레이어로 변환한다. 사람 슬롯을 만나면 {@code humanRef[0]} 에 기록(로컬 플레이어 추적). */
    private List<Player> buildPlayers(List<MatchSetup.Slot> slots, HumanPlayer[] humanRef) {
        List<Player> players = new ArrayList<>();
        for (MatchSetup.Slot slot : slots) {
            switch (slot.kind()) {
                case HUMAN_LOCAL -> {
                    HumanPlayer h = new HumanPlayer(slot.name());
                    humanRef[0] = h;
                    players.add(h);
                }
                case BOT -> players.add(BotPlayer.play(slot.botKind().create()));
                case REMOTE -> players.add(new NetworkPlayer(slot.name())); // 2단계 네트워크
                case EMPTY -> { /* 빈 자리는 게임에서 제외 */ }
            }
        }
        return players;
    }

    private static String teamName(List<Player> players, String fallback) {
        return players.isEmpty() ? fallback : players.get(0).name();
    }

    /** 호스트 모드: 권위 Game 을 로컬에서 실행하고 NetworkPlayer 로 원격 클라이언트와 대전. */
    public void startHostGame(GameConfig config, GameServer server, NetworkPlayer networkPlayer) {
        this.mode = Mode.HOST;
        this.config = config;
        this.server = server;
        this.client = null;
        buildAndStartHostGame(networkPlayer, false);
        installEscHandler();
    }

    /** 호스트 게임을 구성하고 시작한다. {@code restart} 면 같은 연결로 재시작(핸드셰이크 대신 Restart 전송). */
    private void buildAndStartHostGame(NetworkPlayer np, boolean restart) {
        resetBoard();
        HumanPlayer p1 = new HumanPlayer("플레이어 1");
        localPlayer = p1;
        inputGateway = new LocalInputGateway(p1);

        teamA = new Team(p1.name(), List.of(p1));
        teamB = new Team(np.name(), List.of(np));
        animator = new BoardAnimator(contentArea, globalOverlay, centerArea, this::isLocalActor, CARD_ORDER_BY_COLOR);
        updateBoardStatus();

        List<Player> allPlayers = List.of(p1, np);
        NetworkBroadcaster broadcaster = new NetworkBroadcaster(
                np, teamA, teamB, null, allPlayers, server.outputStream());
        FanOutGameListener fanOut = new FanOutGameListener(this, broadcaster);
        Game game = new Game(config, teamA, teamB, fanOut);
        broadcaster.setGame(game);

        PublicBoardState initState = WireCodec.buildState(teamA, teamB, game, 1, allPlayers);
        try {
            if (restart) {
                server.sendRestart(config, 1, initState);  // 클라이언트에 재시작 통지
                server.rebind(np, allPlayers);             // 기존 net-reader 를 새 대리자로 재지정
            } else {
                server.sendHandshake(config, 1, initState);
                // 리더 스레드는 최초 한 번만 시작한다. onDisconnect 는 현재 세션 필드를 참조한다.
                server.startReaderLoop(np, allPlayers, this::onHostDisconnect);
            }
        } catch (java.io.IOException e) {
            setMessage((restart ? "재시작" : "핸드셰이크") + " 전송 실패: " + e.getMessage());
            return;
        }

        this.networkPlayer = np;
        this.currentGame = game;
        startGameThread(game);
    }

    /** 호스트 net-reader 가 연결 종료를 감지하면 호출. 현재 진행 중인 게임/대리자를 깨워 정상 종료시킨다. */
    private void onHostDisconnect() {
        NetworkPlayer np = this.networkPlayer;
        Game g = this.currentGame;
        if (np != null) np.abort();   // decideSplit/Choice/Helpers 대기 해제
        if (g != null) g.abort();     // 환금 인박스 대기 해제
        try {
            if (server != null) server.close();
        } catch (java.io.IOException ignored) {
            // 이미 끊긴 연결 — 무시
        }
        Platform.runLater(() -> showDisconnected("상대 연결이 끊어졌습니다."));
    }

    /** 클라이언트 모드: Game 없이 미러 상태만 추종하며 원격에서 온 이벤트를 렌더링한다. */
    public void startClientGame(ClientMirror mirror, GameClient client) {
        this.mode = Mode.CLIENT;
        this.server = null;
        this.client = client;
        bindClientMirror(mirror, client);

        client.startReaderLoop(mirror, this,
                () -> showDisconnected("호스트 연결이 끊어졌습니다."),
                this::onClientRestart);
        installEscHandler();
    }

    /**
     * 호스트가 보낸 {@link NetMessage.Restart} 를 reader 스레드(JavaFX)가 받아 호출.
     * 새 미러를 만들어 보드를 리셋하고, 반환된 미러가 이후 Envelope 적용 대상이 된다.
     */
    private ClientMirror onClientRestart(NetMessage.Restart restart) {
        ClientMirror mirror = new ClientMirror();
        mirror.init(restart.clientTeamId(), restart.initialState());
        resetBoard();
        bindClientMirror(mirror, client);
        return mirror;
    }

    /** 클라이언트 미러를 컨트롤러 상태에 연결한다(최초/재시작 공용). reader 루프는 건드리지 않는다. */
    private void bindClientMirror(ClientMirror mirror, GameClient client) {
        localPlayer = mirror.myPlayer();
        inputGateway = new NetworkInputGateway(client);
        teamA = mirror.myTeam();
        teamB = mirror.opponentTeam();
        animator = new BoardAnimator(contentArea, globalOverlay, centerArea, this::isLocalActor, CARD_ORDER_BY_COLOR);
        updateBoardStatus();
    }

    private void resetBoard() {
        gameOver = false;
        introPhase = true;
        pauseMenuOpen = false;
        phaseRevision = 0;
        distributionFieldUpdatePending = false;
        resetCashPanel();
        screenOverlay.getChildren().clear();
        screenOverlay.getStyleClass().clear();
        screenOverlay.setMouseTransparent(true);
        screenOverlay.setPickOnBounds(false);
    }

    /**
     * 게임 루프 스레드를 시작한다. 재시작/나가기로 버려진(현재가 아닌) 스레드의 인터럽트 예외는
     * 콘솔을 더럽히지 않도록 무시하고, 현재 게임 스레드의 예외만 출력한다.
     */
    private void startGameThread(Game game) {
        Thread loop = new Thread(game::play, "game-loop");
        loop.setDaemon(true);
        loop.setUncaughtExceptionHandler((t, e) -> {
            if (t == gameThread) {  // 교체된 옛 스레드면 gameThread 가 이미 새 스레드 → 무시
                e.printStackTrace();
            }
        });
        this.gameThread = loop;
        loop.start();
    }

    /** 진행 중인 게임 스레드를 중단시킨다(재시작·나가기 공용). 소켓·net-reader 는 호출자가 관리한다. */
    private void tearDownCurrentGame() {
        if (currentGame != null) currentGame.abort();      // 환금 인박스 대기 해제
        if (networkPlayer != null) networkPlayer.abort();  // 호스트 결정 채널 해제
        if (gameThread != null) gameThread.interrupt();    // 오프라인 HumanPlayer 대기 해제
    }

    // ===== ESC 일시정지 메뉴 =====

    /** 게임 보드 씬에 ESC 키 필터를 한 번만 설치한다. 같은 씬을 재사용하는 재시작에도 중복 설치되지 않는다. */
    private void installEscHandler() {
        if (keyHandlerInstalled) return;
        Platform.runLater(() -> {
            if (keyHandlerInstalled || contentArea.getScene() == null) return;
            keyHandlerInstalled = true;
            contentArea.getScene().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.ESCAPE) {
                    e.consume();
                    togglePauseMenu();
                }
            });
        });
    }

    private void togglePauseMenu() {
        if (gameOver) return;  // 종료 화면에선 ESC 무시
        if (pauseMenuOpen) {
            pauseMenuOpen = false;
            hideScreenOverlay();
        } else {
            pauseMenuOpen = true;
            showPauseOverlay();
        }
    }

    /** 일시정지 메뉴를 띄운다. 이 오버레이만 더 어둡게(pause-overlay) 표시한다. */
    private void showPauseOverlay() {
        showScreenOverlay(buildPauseMenu());
        screenOverlay.getStyleClass().add("pause-overlay");
    }

    private Node buildPauseMenu() {
        VBox root = panelRoot("메뉴");
        root.setSpacing(14);

        Button resume = pauseMenuButton("계속하기", e -> togglePauseMenu());
        Button rulebook = pauseMenuButton("규칙서", e -> showScreenOverlay(
                RulebookBuilder.build(this::showPauseOverlay)));

        VBox buttons = new VBox(10, resume, rulebook);
        buttons.setAlignment(Pos.CENTER);
        buttons.setMaxWidth(260);

        // 다시하기: 오프라인·호스트만 (네트워크 클라이언트는 재시작을 시작할 수 없음)
        if (mode != Mode.CLIENT) {
            buttons.getChildren().add(pauseMenuButton("다시하기", e -> restartGame()));
        }
        buttons.getChildren().add(pauseMenuButton("메인 메뉴로 나가기", e -> exitToMainMenu()));

        root.getChildren().add(buttons);
        return root;
    }

    private Button pauseMenuButton(String text, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        Button button = new Button(text);
        button.getStyleClass().add("menu-mode-button");  // 메인 메뉴 버튼과 통일
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(action);
        return button;
    }

    // ===== 다시하기 / 나가기 =====

    /** 같은 설정으로 새 판을 시작한다. 모드별 분기. */
    private void restartGame() {
        switch (mode) {
            case OFFLINE -> {
                tearDownCurrentGame();
                startGame(matchSetup);
            }
            case HOST -> {
                tearDownCurrentGame();
                buildAndStartHostGame(new NetworkPlayer("플레이어 2"), true);
            }
            case CLIENT -> {
                // 클라이언트는 재시작을 시작하지 않는다(버튼이 노출되지 않음).
            }
        }
    }

    /** 진행 중인 게임을 정리하고 메인 메뉴로 돌아간다. 네트워크는 연결을 닫아 상대에게 알린다. */
    private void exitToMainMenu() {
        tearDownCurrentGame();
        try {
            if (mode == Mode.HOST && server != null) {
                server.close();
            } else if (mode == Mode.CLIENT && client != null) {
                client.close();
            }
        } catch (java.io.IOException ignored) {
            // 이미 끊긴 연결 — 무시
        }
        try {
            GameApp.get().showScene("main_menu.fxml");
        } catch (java.io.IOException ex) {
            alert("메인 화면으로 이동할 수 없습니다: " + ex.getMessage());
        }
    }

    private void showDisconnected(String msg) {
        if (!gameOver) {
            setMessage(msg);
            Label label = new Label(msg);
            label.getStyleClass().add("preview");
            showScreenOverlay(label);
        }
    }

    // ===== GameListener (게임 스레드 → UI) =====

    @Override
    public void onGameSetup(List<Player> players) {
        Platform.runLater(() -> enqueueOverlay(() -> animator.playOfficerSetup(players)));
    }

    @Override
    public void onStealActivated(Player player, Card drawnCard) {
        Platform.runLater(() -> enqueueOverlay(() -> animator.playSteal(player, drawnCard)));
    }

    @Override
    public void onPhaseChanged(Phase phase, int round, Team splitTeam) {
        Platform.runLater(() -> {
            introPhase = false;
            int phaseToken = ++phaseRevision;
            currentSplitTeam = splitTeam;
            clearOpponentCashDoneBadge();
            newlyReceivedCards.clear();
            newlyReceivedOpponentCards.clear();
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
            Team myTeam = localPlayer != null ? teamFor(localPlayer) : null;
            List<Card> myCards;
            List<Card> opponentCards;
            if (myTeam == chooseTeam) {
                myCards = chooseCards;
                opponentCards = splitCards;
            } else if (myTeam == splitTeam) {
                myCards = splitCards;
                opponentCards = chooseCards;
            } else {
                myCards = List.of();
                opponentCards = List.of();
            }
            ensureDistributionAnimationPanel(chosenIndex, chooseCards, splitCards);
            animator.clearOpponentWaiting();
            playDistributionTransition(chosenIndex, chooseTeam, splitTeam, () -> {
                distributionFieldUpdatePending = false;
                newlyReceivedCards.clear();
                newlyReceivedCards.addAll(myCards);
                newlyReceivedOpponentCards.clear();
                newlyReceivedOpponentCards.addAll(opponentCards);
                updateBoardStatus();
                PauseTransition clearNew = new PauseTransition(Duration.seconds(6));
                clearNew.setOnFinished(e -> {
                    newlyReceivedCards.clear();
                    newlyReceivedOpponentCards.clear();
                    for (Node node : fieldAFlow.getChildren()) {
                        if (node instanceof CardView cv) cv.setNewlyReceived(false);
                    }
                    for (Node node : fieldBFlow.getChildren()) {
                        if (node instanceof CardView cv) cv.setNewlyReceived(false);
                    }
                });
                clearNew.play();
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
    public void onHelperUsed(Player player, HelperCard helper, String message,
            List<Card> drawn, List<Card> discarded) {
        Platform.runLater(() -> {
            setMessage(player.name() + " 도우미: " + message);
            updateBoardStatus(); // 사이드바 도우미 카드가 앞면(사용 완료)으로 공개됨
            if (usesEffectOverlay(helper.kind())) {
                enqueueOverlay(() -> animator.playHelperEffect(player, helper, message, drawn, discarded));
            }
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
            updateBoardStatus();
            playCoinChangeAnimation(team, delta);
        });
    }

    @Override
    public void onMessage(String message) {
        if (isStatusBarMessage(message)) {
            Platform.runLater(() -> setMessage(message));
        }
    }

    @Override
    public void onGameOver(Team winner) {
        Platform.runLater(() -> {
            gameOver = true;
            pauseMenuOpen = false;
            showScreenOverlay(buildGameOverPanel(winner));
            turnLabel.setText("게임 종료");
            updateBoardStatus();
        });
    }

    // ===== 입력 요청 (GameListener — 엔진 → UI) =====

    @Override
    public void onRequestSplit(Player player, List<Card> hand) {
        if (!isLocalActor(player)) return;
        Platform.runLater(() -> runAfterOverlay(() -> {
            updateBoardStatus();
            setCenterAnimated(buildSplitPanel(hand));
        }));
    }

    @Override
    public void onRequestChoice(Player player, ChoiceView view) {
        if (!isLocalActor(player)) return;
        Platform.runLater(() -> runAfterOverlay(() -> {
            updateBoardStatus();
            setCenterAnimated(buildChoicePanel(view));
        }));
    }

    @Override
    public void onRequestHelpers(Player player, List<HelperCard> options, int chooseCount) {
        if (!isLocalActor(player)) return;
        Platform.runLater(() -> runAfterOverlay(() -> {
            updateBoardStatus();
            setCenterAnimated(buildHelperSelectionPanel(options, chooseCount));
        }));
    }

    @Override
    public void onRequestTeamDistribution(Player leader, Team team, List<Card> acquired) {
        if (!isLocalActor(leader)) return; // 봇 리더는 전략이 자동 분배하므로 패널을 띄우지 않는다.
        Platform.runLater(() -> runAfterOverlay(() -> {
            updateBoardStatus();
            setCenterAnimated(buildTeamDistributionPanel(team, acquired));
        }));
    }

    @Override
    public void onCashTurn(Player player, CashInContext snapshot) {
        if (!isLocalActor(player)) {
            return;
        }
        Platform.runLater(() -> runAfterOverlay(() -> {
            Team team = teamFor(player);
            renderCashInPanel(localPlayer, team, snapshot, new ArrayList<>(snapshot.holdings()));
        }));
    }

    @Override
    public void onCashDone(Player player) {
        if (isLocalActor(player)) {
            Platform.runLater(() -> {
                cashSelection.clear();
                resetCashPanel();
                runAfterOverlay(() -> setWaiting("환금 완료 — 상대를 기다리는 중"));
            });
        } else if (teamFor(player) == teamB) {
            // 상대 팀 멤버가 환금을 마쳤을 때만 상대 완료 배지. 우리 팀 봇 멤버는 배지 없이 보드만 갱신.
            Platform.runLater(() -> {
                opponentCashDone = true;
                showOpponentCashDoneBadge();
            });
        }
    }

    private void showOpponentCashDoneBadge() {
        if (opponentCashDoneBadge != null) return;
        Label badge = new Label("환금 완료 ✓");
        badge.getStyleClass().add("cash-done-badge");
        StackPane.setAlignment(badge, Pos.CENTER);
        opponentCashDoneBadge = badge;
        fieldBStage.getChildren().add(badge);
    }

    private void clearOpponentCashDoneBadge() {
        if (opponentCashDoneBadge != null) {
            fieldBStage.getChildren().remove(opponentCashDoneBadge);
            opponentCashDoneBadge = null;
        }
        opponentCashDone = false;
    }

    // ===== 패널 빌더 =====

    private Node buildSplitPanel(List<Card> hand) {
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
                alert("분할이 올바르지 않습니다. 다시 확인하세요.");
                return;
            }
            clearCenter();
            inputGateway.provideSplit(decision);
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
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            WritableImage dragImage = wrapper.snapshot(params, null);
            dragboard.setDragView(dragImage, dragImage.getWidth() / 2, dragImage.getHeight() / 2);
            e.consume();
        });
        return wrapper;
    }

    private void installDropTarget(FlowPane target, int bundleIndex, Map<Card, Integer> bundleOf, Runnable refresh) {
        target.setOnDragOver(e -> {
            if (e.getGestureSource() instanceof Node node
                    && node.getUserData() instanceof Card card
                    && e.getDragboard().hasString()
                    && canMoveSplitCard(card, bundleIndex, bundleOf)) {
                e.acceptTransferModes(TransferMode.MOVE);
            }
            e.consume();
        });
        target.setOnDragDropped(e -> {
            Object source = e.getGestureSource();
            if (source instanceof Node node
                    && node.getUserData() instanceof Card card
                    && canMoveSplitCard(card, bundleIndex, bundleOf)) {
                bundleOf.put(card, bundleIndex);
                refresh.run();
                e.setDropCompleted(true);
            }
            e.consume();
        });
    }

    private boolean canMoveSplitCard(Card card, int targetBundle, Map<Card, Integer> bundleOf) {
        int currentBundle = bundleOf.getOrDefault(card, 0);
        if (currentBundle == targetBundle) {
            return true;
        }
        long sourceCount = bundleOf.values().stream().filter(index -> index == currentBundle).count();
        long targetCount = bundleOf.values().stream().filter(index -> index == targetBundle).count();
        return sourceCount > 1 && targetCount < 4;
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

    private Node buildChoicePanel(ChoiceView view) {
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
                    inputGateway.provideChoice(index);
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

    /** 사람 리더가 가져온 카드를 팀원과 나눠 갖는 패널(규칙 §6-2-4). 카드를 눌러 보관할 사람을 순환한다. */
    private VBox buildTeamDistributionPanel(Team team, List<Card> acquired) {
        List<Player> members = team.members();
        Map<Card, Integer> assign = new HashMap<>();
        int[] counts = new int[members.size()];
        for (int i = 0; i < members.size(); i++) {
            counts[i] = members.get(i).holdingCount();
        }
        for (Card card : acquired) {                    // 균형 분배로 초기 배정
            int target = 0;
            for (int i = 1; i < members.size(); i++) {
                if (counts[i] < counts[target]) target = i;
            }
            assign.put(card, target);
            counts[target]++;
        }

        VBox root = panelRoot("가져온 카드를 팀원과 나눠 가지세요. 카드를 눌러 보관할 사람을 바꿉니다.");

        FlowPane cardRow = new FlowPane(10, 10);
        cardRow.setAlignment(Pos.CENTER);
        for (Card card : acquired) {
            VBox cell = new VBox(4);
            cell.setAlignment(Pos.CENTER);
            CardView cardView = new CardView(card, true);
            Label owner = new Label(members.get(assign.get(card)).name());
            owner.getStyleClass().add("distribution-owner");
            cardView.setOnMouseClicked(e -> {
                int next = (assign.get(card) + 1) % members.size();
                assign.put(card, next);
                owner.setText(members.get(next).name());
            });
            cell.getChildren().addAll(cardView, owner);
            cardRow.getChildren().add(cell);
        }

        Button confirm = new Button("분배 확정");
        confirm.getStyleClass().add("menu-button");
        confirm.setOnAction(e -> {
            List<List<Card>> byMember = new ArrayList<>();
            for (int i = 0; i < members.size(); i++) {
                byMember.add(new ArrayList<>());
            }
            for (Card card : acquired) {
                byMember.get(assign.get(card)).add(card);
            }
            inputGateway.provideDistribution(new TeamDistribution(byMember));
        });
        HBox buttonRow = new HBox(confirm);
        buttonRow.setAlignment(Pos.CENTER);

        root.getChildren().addAll(cardRow, buttonRow);
        return root;
    }

    private Node buildHelperSelectionPanel(List<HelperCard> options, int chooseCount) {
        VBox root = panelRoot("도우미 후보 3장 중 " + chooseCount + "장을 선택하세요.");

        List<ToggleButton> toggles = new ArrayList<>();
        FlowPane row = new FlowPane(12, 12);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("helper-grid");
        row.setPrefWrapLength(860);
        for (HelperCard helper : options) {
            ToggleButton toggle = new ToggleButton();
            toggle.setUserData(helper);
            toggle.getStyleClass().add("helper-button");
            toggle.setPrefWidth(250);
            toggle.setPrefHeight(136);

            Label nameLbl = new Label(helper.displayName());
            nameLbl.getStyleClass().add("helper-button-name");
            nameLbl.setWrapText(true);
            nameLbl.setMaxWidth(220);

            Label descLbl = new Label(helper.effectText());
            descLbl.getStyleClass().add("helper-button-desc");
            descLbl.setWrapText(true);
            descLbl.setMaxWidth(220);

            VBox content = new VBox(5, nameLbl, descLbl);
            content.setAlignment(Pos.CENTER);
            toggle.setGraphic(content);

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
            setWaiting("상대가 도우미를 선택 중입니다");
            inputGateway.provideHelpers(selected);
        });

        root.getChildren().addAll(row, done);
        return root;
    }

    /** 환금 행동 한 건을 큐에 제출한다. onCashTurn 콜백이 패널을 증분 갱신한다. */
    private void submitCashAction(CashInAction action) {
        cashSelection.clear();
        inputGateway.submitCash(action);
    }

    /** 환금 패널 증분 업데이트 참조를 초기화한다. 페이즈 전환·완료 시 호출. */
    private void resetCashPanel() {
        cashPhaseActive = false;
        cashPreviewLabel = null;
        cashHoldLimitLabel = null;
        cashHelperTogglesBox = null;
        cashHelperButtonsBox = null;
        cashRemaining = null;
        cashCashContext = null;
        cashSelectedHelpers.clear();
    }

    /**
     * 환금 패널을 표시하거나 갱신한다.
     * 패널이 이미 활성 중이면 레이블·버튼만 갱신(전환 없음),
     * 처음 표시할 때만 전체 구조를 빌드하고 fade-in 전환을 수행한다.
     */
    private void renderCashInPanel(Player player, Team team, CashInContext context, List<Card> remaining) {
        boolean alreadyActive = cashPhaseActive;
        cashPhaseActive = true;
        cashRemaining = remaining;
        cashCashContext = context;
        cashSelection.retainAll(remaining);
        cashSelectedHelpers.retainAll(context.helpers());
        updateBoardStatus();

        if (alreadyActive) {
            updateCashPanelContent(player, context, remaining);
            return;
        }
        buildCashPanel(player, context, remaining);
    }

    /** 환금 컨트롤 최초 빌드 (구조 전체 생성 + fade-in 전환). */
    private void buildCashPanel(Player player, CashInContext context, List<Card> remaining) {
        VBox root = panelRoot("내 필드에서 카드를 선택해 환금하거나, 카드를 처분/도움 요청하세요. 끝나면 '턴 종료'.");
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
                alert("선택한 카드는 유효한 세트가 아닙니다. 저주받은 그림은 같은 숫자 보물이 포함된 세트와 함께만 무료 처분할 수 있습니다.");
                return;
            }
            List<HelperCard> cashHelpers = cashSelectedHelpers.stream()
                    .filter(helper -> canAttachToCash(helper, evaluation.get().set()))
                    .toList();
            CashInAction action = cashHelpers.isEmpty()
                    ? new CashInAction.Cash(chosen)
                    : new CashInAction.CashWithHelpers(chosen, cashHelpers);
            submitCashAction(action);
        });

        Button discardBtn = new Button("처분");
        discardBtn.getStyleClass().add("menu-button");
        discardBtn.setOnAction(e -> {
            if (cashSelection.isEmpty()) {
                alert("처분할 카드를 선택하세요.");
                return;
            }
            List<Card> toDiscard = new ArrayList<>(cashSelection);
            cashSelection.clear();
            for (Card c : toDiscard) {
                inputGateway.submitCash(new CashInAction.Discard(c));
            }
        });

        Button doneBtn = new Button("턴 종료");
        doneBtn.getStyleClass().add("menu-button");
        doneBtn.setOnAction(e -> {
            if (!player.isHoldLimitSuspended() && cashRemaining.size() > player.holdLimit()) {
                alert("보유 한도를 초과해서 턴을 종료할 수 없습니다. 현재 "
                        + cashRemaining.size() + "장 / 한도 " + player.holdLimit()
                        + "장입니다. 환금하거나 처분해 한도 이하로 맞춰주세요.");
                return;
            }
            cashSelection.clear();
            inputGateway.passCash();
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
        setCenterAnimated(root);
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
                        alert("샛길의 더그로 버릴 보물을 먼저 선택하세요(저주받은 그림 제외).");
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
                submitCashAction(action);
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
            alert("복사할 수 있는 사용 완료 도우미가 없습니다.");
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

    /**
     * 중앙 발동 오버레이를 띄울 도우미인지. 조건부 보너스(쿠쿠·레오·럭키)는 코인 플로팅 +
     * 사이드바 카드 공개로 충분하므로 생략하고, 판을 바꾸는 행동 도우미와 즉승(알파)만 오버레이를 쓴다.
     */
    private boolean usesEffectOverlay(HelperKind kind) {
        return kind != HelperKind.CUCKOO && kind != HelperKind.LEO && kind != HelperKind.LUCKY;
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

    private Node buildGameOverPanel(Team winner) {
        VBox root = panelRoot("게임 종료");
        root.setSpacing(16);

        // 보드 레이아웃 반영: teamB(상대) = 위, teamA(나) = 아래
        VBox topCard    = buildScoreCard(teamB, teamB == winner, false);
        VBox bottomCard = buildScoreCard(teamA, teamA == winner, true);

        VBox scoreColumn = new VBox(12, topCard, bottomCard);
        scoreColumn.setAlignment(Pos.CENTER);
        scoreColumn.setMaxWidth(440);

        Button exit = new Button("나가기");
        exit.getStyleClass().add("menu-button");
        exit.setOnAction(e -> exitToMainMenu());

        HBox actions = new HBox(12);
        actions.setAlignment(Pos.CENTER);
        // 다시하기: 오프라인·호스트만. 네트워크 클라이언트는 호스트의 재시작을 기다린다.
        if (mode != Mode.CLIENT) {
            Button restart = new Button("다시하기");
            restart.getStyleClass().add("menu-button");
            restart.setOnAction(e -> restartGame());
            actions.getChildren().add(restart);
        }
        actions.getChildren().add(exit);

        root.getChildren().addAll(scoreColumn, actions);
        if (mode == Mode.CLIENT) {
            Label note = new Label("호스트가 다시 시작할 수 있습니다.");
            note.getStyleClass().add("guide");
            root.getChildren().add(note);
        }
        return root;
    }

    private void showScreenOverlay(Node node) {
        screenOverlay.getStyleClass().setAll("screen-overlay");
        screenOverlay.setMouseTransparent(false);
        screenOverlay.setPickOnBounds(true);
        screenOverlay.setOnMouseClicked(null);
        StackPane.setAlignment(node, Pos.CENTER);
        node.setOpacity(0);
        screenOverlay.getChildren().setAll(node);
        fade(node, 0, 1, 220).play();
    }

    @FXML
    private void onScoreTable() {
        if (cachedScoreTablePanel == null) {
            cachedScoreTablePanel = buildScoreTablePanel();
        }
        screenOverlay.getStyleClass().setAll("screen-overlay");
        screenOverlay.setMouseTransparent(false);
        screenOverlay.setPickOnBounds(true);
        screenOverlay.setOnMouseClicked(null);
        StackPane.setAlignment(cachedScoreTablePanel, Pos.CENTER);
        cachedScoreTablePanel.setOpacity(1);
        screenOverlay.getChildren().setAll(cachedScoreTablePanel);
    }

    private Node buildScoreTablePanel() {
        return ScoreTableBuilder.build(this::hideScreenOverlay);
    }

    private void hideScreenOverlay() {
        screenOverlay.getChildren().clear();
        screenOverlay.getStyleClass().clear();
        screenOverlay.setMouseTransparent(true);
        screenOverlay.setPickOnBounds(false);
        screenOverlay.setOnMouseClicked(null);
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
        setCenterAnimated(buildWaitingChoicePanel(bundles), true);
    }

    // ===== BoardAnimator 위임 (기존 호출부를 유지) =====

    private void clearCenterIfOpponentWaiting() {
        animator.clearCenterIfOpponentWaiting();
    }

    private void clearCenter() {
        animator.clearCenter();
    }

    private void setCenter(Node node) {
        animator.setCenter(node);
    }

    private void setCenterAnimated(Node node) {
        animator.setCenterAnimated(node);
    }

    private void setCenterAnimated(Node node, boolean opponentWaiting) {
        animator.setCenterAnimated(node, opponentWaiting);
    }

    private FadeTransition fade(Node node, double from, double to, int millis) {
        return animator.fade(node, from, to, millis);
    }

    private void enqueueOverlay(Runnable animation) {
        animator.enqueueOverlay(animation);
    }

    private void playNextOverlay() {
        animator.playNextOverlay();
    }

    private void runAfterOverlay(Runnable action) {
        animator.runAfterOverlay(action);
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

    private void setMessage(String message) {
        messageLabel.setText(message);
    }

    private boolean isStatusBarMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("무효")
                || message.contains("사용할 수 없는")
                || message.contains("이미 사용")
                || message.contains("필요")
                || message.contains("있어야")
                || message.contains("초과")
                || message.contains("금지")
                || message.contains("게임 시작")
                || message.contains("라운드")
                || message.contains("게임 종료");
    }

    private void updateBoardStatus() {
        updateFields();
    }

    private void updateFields() {
        if (distributionFieldUpdatePending) {
            return;
        }
        boolean showFieldUI = !introPhase && !gameOver;
        fieldACountLabel.setVisible(showFieldUI);
        fieldBCountLabel.setVisible(showFieldUI);
        if (fieldASortBox != null) fieldASortBox.setVisible(showFieldUI);
        if (fieldBSortBox != null) fieldBSortBox.setVisible(showFieldUI);
        renderField(fieldATitleLabel, fieldAOfficerLabel, fieldAOfficerEffectLabel, fieldACoinsLabel,
                fieldACountLabel, fieldAStage, fieldAFlow, fieldAHelperFlow, teamA, "내 필드", sortModeA);
        renderField(fieldBTitleLabel, fieldBOfficerLabel, fieldBOfficerEffectLabel, fieldBCoinsLabel,
                fieldBCountLabel, fieldBStage, fieldBFlow, fieldBHelperFlow, teamB, "상대 필드", sortModeB);
    }

    private void renderField(Label titleLabel, Label officerLabel, Label officerEffectLabel,
            Label coinsLabel, Label countLabel, StackPane stage, FlowPane field, VBox helpers, Team team,
            String fallback, Comparator<Card> sortOrder) {
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
        List<Player> members = team.members();
        Player leader = team.leader();
        titleLabel.setText(fallback + " · " + memberNames(members));
        if (leader.officer() != null) {
            officerLabel.setText(leader.officer().korean());
            officerEffectLabel.setText(leader.officer().effectText());
        } else {
            officerLabel.setText("간부 없음");
            officerEffectLabel.setText("");
        }
        coinsLabel.setText(team.coins() + " 코인");
        boolean hideDetails = introPhase || gameOver; // 인트로·게임 종료 화면에선 보유 수/빈 필드 안내를 숨긴다
        countLabel.setText(hideDetails ? "" : holdingSummary(members));
        renderSidebarHelpers(helpers, members);
        Map<Card, Bounds> previousBounds = hideDetails ? Map.of() : cardBoundsByScene(field);
        field.getChildren().clear();
        if (hideDetails) {
            return;
        }
        // 다인 팀이면 멤버별로 카드 묶음을 나눠 그린다(멤버 사이에 이름 칩을 끼움).
        boolean multi = members.size() > 1;
        Map<Card, CardView> currentViews = new HashMap<>();
        for (Player member : members) {
            boolean cashSelectable = cashSelectableField(team, member);
            List<Card> cards = cashSelectable
                    ? new ArrayList<>(cashRemaining)
                    : new ArrayList<>(member.holdings());
            cards.sort(sortOrder);
            if (multi) {
                field.getChildren().add(memberChip(member, cards.size()));
            }
            for (Card card : cards) {
                CardView cardView = new CardView(card, true);
                if (cashSelection.contains(card)) {
                    cardView.setSelected(true);
                }
                if (newlyReceivedCards.contains(card) || newlyReceivedOpponentCards.contains(card)) {
                    cardView.setNewlyReceived(true);
                }
                if (cashSelectable) {
                    cardView.setOnMouseClicked(e -> toggleCashFieldSelection(cardView, card));
                }
                field.getChildren().add(cardView);
                currentViews.put(card, cardView);
            }
        }
        playFieldReflowTransition(stage, field, previousBounds, currentViews);
    }

    private static String memberNames(List<Player> members) {
        return members.stream().map(Player::name)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String holdingSummary(List<Player> members) {
        if (members.size() == 1) {
            Player p = members.get(0);
            return "보유 " + p.holdingCount() + " / " + p.holdLimit();
        }
        return members.stream()
                .map(p -> p.name() + " " + p.holdingCount() + "/" + p.holdLimit())
                .collect(java.util.stream.Collectors.joining("   "));
    }

    /** 다인 팀 카드 영역에서 멤버 구분용 칩. 환금 중인 로컬 멤버는 강조한다. */
    private Node memberChip(Player member, int cardCount) {
        Label chip = new Label(member.name() + " · " + cardCount + "장");
        chip.getStyleClass().add("field-member-chip");
        if (isLocalActor(member)) {
            chip.getStyleClass().add("field-member-chip-local");
        }
        return chip;
    }

    private Map<Card, Bounds> cardBoundsByScene(FlowPane field) {
        if (field.getScene() == null) {
            return Map.of();
        }
        Map<Card, Bounds> bounds = new HashMap<>();
        for (Node child : field.getChildren()) {
            if (child instanceof CardView cardView) {
                bounds.put(cardView.card(), child.localToScene(child.getBoundsInLocal()));
            }
        }
        return bounds;
    }

    private void playFieldReflowTransition(StackPane stage, FlowPane field, Map<Card, Bounds> previousBounds,
            Map<Card, CardView> currentViews) {
        if (previousBounds.isEmpty() || field.getScene() == null || stage.getScene() == null) {
            return;
        }
        field.applyCss();
        field.layout();

        ParallelTransition transition = new ParallelTransition();
        for (Map.Entry<Card, CardView> entry : currentViews.entrySet()) {
            Bounds before = previousBounds.get(entry.getKey());
            if (before == null) {
                continue;
            }
            CardView view = entry.getValue();
            Bounds after = view.localToScene(view.getBoundsInLocal());
            double dx = before.getMinX() - after.getMinX();
            double dy = before.getMinY() - after.getMinY();
            if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) {
                continue;
            }
            double cssX = view.getTranslateX();
            double cssY = view.getTranslateY();
            view.setTranslateX(cssX + dx);
            view.setTranslateY(cssY + dy);
            TranslateTransition move = new TranslateTransition(Duration.millis(300), view);
            move.setToX(cssX);
            move.setToY(cssY);
            move.setInterpolator(Interpolator.EASE_BOTH);
            transition.getChildren().add(move);
        }

        for (Map.Entry<Card, Bounds> entry : previousBounds.entrySet()) {
            if (currentViews.containsKey(entry.getKey())) {
                continue;
            }
            CardView ghost = new CardView(entry.getKey(), true);
            Bounds before = entry.getValue();
            Point2D stagePoint = stage.sceneToLocal(before.getMinX(), before.getMinY());
            ghost.setManaged(false);
            ghost.setMouseTransparent(true);
            ghost.setLayoutX(stagePoint.getX());
            ghost.setLayoutY(stagePoint.getY());
            stage.getChildren().add(ghost);

            FadeTransition fadeOut = fade(ghost, 1, 0, 180);
            ScaleTransition shrink = new ScaleTransition(Duration.millis(180), ghost);
            shrink.setToX(0.94);
            shrink.setToY(0.94);
            shrink.setInterpolator(Interpolator.EASE_IN);
            ParallelTransition vanish = new ParallelTransition(fadeOut, shrink);
            vanish.setOnFinished(e -> stage.getChildren().remove(ghost));
            transition.getChildren().add(vanish);
        }

        if (!transition.getChildren().isEmpty()) {
            transition.play();
        }
    }

    private boolean cashSelectableField(Team team, Player player) {
        return cashPhaseActive
                && cashRemaining != null
                && cashCashContext != null
                && team == teamA
                && player == localPlayer;
    }

    private void toggleCashFieldSelection(CardView cardView, Card card) {
        cardView.toggleSelected();
        if (cardView.isSelected()) {
            cashSelection.add(card);
        } else {
            cashSelection.remove(card);
        }
        updateCashInPreview(cashSelection, cashCashContext.helpers());
        refreshCashHelperToggles(cashCashContext.helpers());
    }

    private void renderSidebarHelpers(VBox target, List<Player> members) {
        target.getChildren().clear();
        for (Player member : members) {
            boolean local = isLocalActor(member);
            for (HelperCard helper : member.helpers()) {
                boolean faceUp = local || helper.isUsed();
                target.getChildren().add(buildSidebarHelperCard(helper, faceUp));
            }
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
            StackPane.setMargin(used, new javafx.geometry.Insets(0, -12, -17, -12));
            card.getChildren().add(used);
        }

        return card;
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

    private void playCoinChangeAnimation(Team team, int delta) {
        Label coinsLabel = team == teamA ? fieldACoinsLabel : team == teamB ? fieldBCoinsLabel : null;
        if (coinsLabel == null || delta == 0 || globalOverlay == null) {
            return;
        }
        int lane = nextCoinFloatLane(team);
        Label floating = new Label((delta > 0 ? "+" : "") + delta);
        floating.getStyleClass().add("coin-float");
        floating.getStyleClass().add(delta > 0 ? "coin-float-gain" : "coin-float-loss");
        floating.setMouseTransparent(true);
        floating.setOpacity(0);

        Bounds bounds = coinsLabel.localToScene(coinsLabel.getBoundsInLocal());
        Point2D anchor = globalOverlay.sceneToLocal(bounds.getMaxX() + 8, bounds.getMinY() - 6);
        floating.setLayoutX(anchor.getX());
        floating.setLayoutY(anchor.getY() + lane * 34);
        globalOverlay.getChildren().add(floating);

        FadeTransition fadeIn = fade(floating, 0, 1, 120);
        TranslateTransition rise = new TranslateTransition(Duration.millis(900), floating);
        rise.setFromY(0);
        rise.setToY(-44);
        FadeTransition fadeOut = fade(floating, 1, 0, 1100);
        ParallelTransition floatOut = new ParallelTransition(rise, fadeOut);
        fadeIn.setOnFinished(e -> floatOut.play());
        floatOut.setOnFinished(e -> {
            globalOverlay.getChildren().remove(floating);
            releaseCoinFloatLane(team);
        });
        fadeIn.play();
    }

    private int nextCoinFloatLane(Team team) {
        if (team == teamA) {
            return activeFieldACoinFloats++;
        }
        if (team == teamB) {
            return activeFieldBCoinFloats++;
        }
        return 0;
    }

    private void releaseCoinFloatLane(Team team) {
        if (team == teamA && activeFieldACoinFloats > 0) {
            activeFieldACoinFloats--;
        } else if (team == teamB && activeFieldBCoinFloats > 0) {
            activeFieldBCoinFloats--;
        }
    }

    private void alert(String message) {
        Alert a = new Alert(AlertType.WARNING, message);
        a.setHeaderText(null);
        a.setTitle("알림");
        a.showAndWait();
    }
}
