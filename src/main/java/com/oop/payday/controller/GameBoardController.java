package com.oop.payday.controller;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import com.oop.payday.app.GameApp;
import com.oop.payday.app.Settings;
import com.oop.payday.bot.BotKind;
import com.oop.payday.bot.BotStrategy;
import com.oop.payday.bot.LlmBotStrategy;
import com.oop.payday.decision.BundleView;
import com.oop.payday.decision.CashInContext;
import com.oop.payday.decision.ChoiceView;
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
import com.oop.payday.model.set.TreasureSet;
import com.oop.payday.net.ClientMirror;
import com.oop.payday.net.FanOutGameListener;
import com.oop.payday.net.GameClient;
import com.oop.payday.net.GameServer;
import com.oop.payday.net.NetMessage;
import com.oop.payday.net.NetworkBroadcaster;
import com.oop.payday.net.PublicBoardState;
import com.oop.payday.net.WireCodec;
import com.oop.payday.player.BotPlayer;
import com.oop.payday.player.HumanPlayer;
import com.oop.payday.player.NetworkPlayer;
import com.oop.payday.player.Player;
import com.oop.payday.view.CardView;
import com.oop.payday.view.Panels;
import com.oop.payday.view.RulebookBuilder;
import com.oop.payday.view.ScorePanels;
import com.oop.payday.view.ScoreTableBuilder;
import com.oop.payday.view.SplitPanelBuilder;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Translate;
import javafx.util.Duration;

/**
 * 게임 보드 컨트롤러. 모델 이벤트({@link GameListener})를 화면에 반영하고,
 * 엔진의 입력 요청 알림({@code onRequestXxx})에 맞춰 단계별 입력 패널을 띄운다.
 *
 * <p>게임 로직은 별도 스레드에서 돌고, 모든 UI 변경은 {@link Platform#runLater} 로 처리한다.
 */
public final class GameBoardController implements GameListener, Initializable {

    @FXML private Label fieldACoinsLabel;
    @FXML private Label fieldBCoinsLabel;
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
    private Team currentSplitTeam;
    private BoardAnimator animator; // 오버레이 큐·센터 전환·발동 연출 (startGame 에서 초기화)
    // 환금 페이즈 패널·선택 상태. 카드 선택 UI는 중앙 패널이 아니라 내 필드에 붙는다(아래 renderField 연동).
    // animator 보다 뒤에 둬야 초기화식의 전방 참조를 피한다.
    private final CashInPanel cashPanel = new CashInPanel(
            () -> inputGateway,                        // 세션마다 교체되는 현재 입력 창구
            node -> animator.setCenterAnimated(node),  // 호출 시점의 BoardAnimator
            this::updateBoardStatus);                  // 선택 변경 시 내 필드 재렌더
    private int activeFieldACoinFloats;
    private int activeFieldBCoinFloats;
    private boolean distributionFieldUpdatePending;
    private boolean introPhase = true;
    private boolean gameOver = false;
    private Node cachedScoreTablePanel;
    private LlmBotStrategy llmBot;

    /** 게임 모드. 다시하기/나가기 동작과 일시정지 메뉴 버튼 노출을 결정한다. */
    private enum Mode { OFFLINE, HOST, CLIENT }
    private Mode mode;
    private GameConfig config;            // 오프라인/호스트 재시작용
    private MatchSetup matchSetup;        // 오프라인 자리 배치(재시작용)
    private GameServer server;            // 호스트 세션 (소켓·리더 스레드 유지)
    private GameClient client;            // 클라이언트 세션
    private NetworkBroadcaster broadcaster; // 호스트 모드에서 리더 진행 상태를 팀원에게 중계할 때 사용
    private List<NetworkPlayer> networkPlayers = new ArrayList<>(); // 호스트측 원격 대리자들 (재시작 시 교체)
    private Game currentGame;             // 진행 중 게임 (중단용)
    private Thread gameThread;            // 게임 루프 스레드 (중단용)
    private boolean disconnected;         // 네트워크 끊김 — 다시하기 불가(일시정지 메뉴에서 숨김)

    /** 게임 세대 발급기. 호스트가 판마다 증가시켜 이전 판의 늦은 메시지를 클라이언트가 걸러내게 한다. */
    private static final java.util.concurrent.atomic.AtomicInteger EPOCH_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();
    private boolean pauseMenuOpen;
    private boolean keyHandlerInstalled;
    private VBox activeBundle0;
    private VBox activeBundle1;
    private int phaseRevision;

    private final Set<Player> cashDonePlayers = new HashSet<>();
    private final Set<Card> newlyReceivedCards = new HashSet<>();
    private final Set<Card> newlyReceivedOpponentCards = new HashSet<>();

    // 팀원(리더가 아닌 아군) 읽기 전용 패널의 라이브 동기화 참조. 활성 패널이 아니면 null.
    private List<Player> teammateDistMembers;            // 분배 패널 멤버 순서(배정 인덱스 해석용)
    private List<Label> teammateDistOwnerLabels;         // 가져온 카드별 보관자 라벨(배정 인덱스순)
    private List<Label> teammateHelperBadges;            // 도우미 후보별 역할 배지(후보 순서)
    private List<StackPane> teammateHelperCards;         // 도우미 후보별 카드 노드(역할 강조용)
    private Player teammateHelperLeader;                 // 도우미 선택 중인 리더(배지 라벨 표기용)

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

    /** 필드 가로 padding(CSS center-field-stage padding: 12 32 → 좌우 32+32). */
    private static final double FIELD_HPAD = 64;
    /** 카드 기본 폭 + 정상 카드 간격. 겹치지 않을 때 카드 1장당 전진폭. */
    private static final double NORMAL_STEP = CardView.WIDTH + 8;
    /**
     * 카드를 겹칠 때 한 장이 최소한 전진해야 하는 폭. CardView 좌상단 숫자
     * (코너 margin 8 + minW 18 + 테두리 insets ~3 ≈ 30)가 항상 보이도록 하한.
     */
    private static final double MIN_STEP = 28;
    /** 멤버 칩 앞 간격, 칩과 첫 카드 사이 간격(겹침과 무관한 고정 간격). */
    private static final double CHIP_GAP = 14;
    private static final double CARD_AFTER_CHIP_GAP = 6;

    /**
     * 필드 스테이지는 카드 수와 무관하게 항상 보드 가로폭을 채우도록 강제한다.
     * 그렇지 않으면 VBox 안에서 자식 선호폭만큼만 줄어들어 외곽선이 카드 뒤에 묻힐 수 있다.
     * 또한 줄바꿈을 꺼서 카드가 절대 둘째 줄로 내려가지 않게 하고(간격은 margin으로 제어),
     * 카드가 많으면 applyFieldOverlap가 음수 margin으로 부채꼴처럼 겹쳐 한 줄을 유지한다.
     */
    private void configureFieldStage(StackPane stage, FlowPane flow) {
        stage.setMaxWidth(Double.MAX_VALUE);
        flow.setMaxWidth(Double.MAX_VALUE);
        flow.prefWrapLengthProperty().unbind();
        flow.setPrefWrapLength(Double.MAX_VALUE);
        flow.setHgap(0);
        flow.setVgap(0);

        // 극단적으로 카드가 많아 MIN_STEP에서도 넘치면 가장자리만 잘리고 레이아웃은 유지.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(stage.widthProperty());
        clip.heightProperty().bind(stage.heightProperty());
        stage.setClip(clip);

        // 폭이 바뀌면(리사이즈/최대화) 겹침 폭을 다시 계산한다(리빌드·애니메이션 없음).
        stage.widthProperty().addListener((obs, o, n) -> applyFieldOverlap(stage, flow));
    }

    /**
     * 필드 카드들을 한 줄에 담기 위해 카드 1장당 전진폭(step)을 계산해 FlowPane margin으로 적용한다.
     * 정상 폭에 들어가면 기존과 동일한 간격을, 넘치면 후속 카드에 음수 left margin을 줘
     * 부채꼴처럼 겹친다(자식 추가 순서 = z-순서 → 뒤 카드가 위로, 각 카드 좌상단 숫자는 노출).
     * step은 좌상단 숫자가 항상 보이도록 MIN_STEP 이상으로 유지한다.
     */
    private void applyFieldOverlap(StackPane stage, FlowPane field) {
        var children = field.getChildren();
        if (children.isEmpty()) {
            return;
        }
        // 1) 겹침이 없을 때(정상 간격)의 합계 폭과 "후속 카드" 수를 측정한다.
        double naturalWidth = 0;
        int followerCards = 0; // 그룹의 첫 카드 뒤에 오는 카드(겹칠 수 있는 대상) 수
        boolean prevWasCard = false;
        for (int i = 0; i < children.size(); i++) {
            Node node = children.get(i);
            boolean first = (i == 0);
            if (node instanceof CardView) {
                if (prevWasCard) {
                    naturalWidth += NORMAL_STEP;
                    followerCards++;
                } else {
                    naturalWidth += CardView.WIDTH + (first ? 0 : CARD_AFTER_CHIP_GAP);
                }
                prevWasCard = true;
            } else { // 멤버 칩(Label)
                node.applyCss();
                naturalWidth += node.prefWidth(-1) + (first ? 0 : CHIP_GAP);
                prevWasCard = false;
            }
        }

        // 2) 사용 가능한 폭에 맞춰 step을 구한다.
        double available = stage.getWidth() - FIELD_HPAD;
        double step = NORMAL_STEP;
        if (available > 0 && followerCards > 0 && naturalWidth > available) {
            step = NORMAL_STEP + (available - naturalWidth) / followerCards;
            step = Math.max(MIN_STEP, Math.min(NORMAL_STEP, step));
        }

        // 3) step으로 각 자식의 left margin을 적용한다(후속 카드만 겹침).
        prevWasCard = false;
        for (int i = 0; i < children.size(); i++) {
            Node node = children.get(i);
            boolean first = (i == 0);
            double left;
            if (node instanceof CardView) {
                left = prevWasCard ? (step - CardView.WIDTH) : (first ? 0 : CARD_AFTER_CHIP_GAP);
                prevWasCard = true;
            } else {
                left = first ? 0 : CHIP_GAP;
                prevWasCard = false;
            }
            FlowPane.setMargin(node, new Insets(0, 0, 0, left));
        }
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
            updateBoardStatus(true);
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
        this.broadcaster = null;
        this.networkPlayers = List.of();

        // 사람(방장)이 속한 대기실 팀을 항상 teamA(=내 필드)로 둔다.
        boolean humanInA = setup.teamA().stream()
                .anyMatch(s -> s.kind() == MatchSetup.SlotKind.HUMAN_LOCAL);
        List<MatchSetup.Slot> mySlots = humanInA ? setup.teamA() : setup.teamB();
        List<MatchSetup.Slot> oppSlots = humanInA ? setup.teamB() : setup.teamA();

        HumanPlayer[] humanRef = new HumanPlayer[1];
        List<Player> myPlayers = buildPlayers(mySlots, humanRef, null);
        List<Player> oppPlayers = buildPlayers(oppSlots, humanRef, null);

        HumanPlayer human = humanRef[0];
        localPlayer = human;
        inputGateway = human != null ? new LocalInputGateway(human) : null;

        teamA = new Team(teamName(myPlayers, "우리 팀"), myPlayers);
        teamB = new Team(teamName(oppPlayers, "상대 팀"), oppPlayers);
        animator = new BoardAnimator(contentArea, globalOverlay, centerArea, this::isLocalActor, CARD_ORDER_BY_COLOR);
        updateBoardStatus();

        Game game = new Game(config, teamA, teamB, wrapWithLlmBotListeners(this));
        this.currentGame = game;
        startGameThread(game);
        installEscHandler();
    }

    /** 원격 슬롯과 그 대리자의 묶음(핸드셰이크·바인딩용). */
    private record RemoteBinding(int clientId, NetworkPlayer player) {}

    /**
     * 슬롯 목록을 플레이어로 변환한다. 사람 슬롯은 {@code humanRef[0]} 에 기록(로컬 플레이어 추적),
     * 원격 슬롯은 {@code remotes} 가 null 이 아니면 {@link RemoteBinding} 으로 수집한다.
     */
    private List<Player> buildPlayers(List<MatchSetup.Slot> slots, HumanPlayer[] humanRef,
            List<RemoteBinding> remotes) {
        List<Player> players = new ArrayList<>();
        for (MatchSetup.Slot slot : slots) {
            switch (slot.kind()) {
                case HUMAN_LOCAL -> {
                    HumanPlayer h = new HumanPlayer(slot.name());
                    humanRef[0] = h;
                    players.add(h);
                }
                case BOT -> players.add(BotPlayer.play(createBotStrategy(slot.botKind()), slot.name()));
                case REMOTE -> {
                    NetworkPlayer np = new NetworkPlayer(slot.name());
                    if (remotes != null) remotes.add(new RemoteBinding(slot.clientId(), np));
                    players.add(np);
                }
            }
        }
        return players;
    }

    private BotStrategy createBotStrategy(BotKind kind) {
        BotKind resolved = kind != null ? kind : BotKind.HARD;
        return resolved.create(
                line -> Platform.runLater(() -> animator.showSpeech(line)),
                Settings.geminiApiKey());
    }

    /**
     * LLM 봇 전략이 있으면 {@link GameListener}로도 체이닝해 게임 흐름을 컨텍스트로 주입할 수 있게 한다.
     * LLM 봇이 없으면 {@code base}를 그대로 반환한다.
     */
    private GameListener wrapWithLlmBotListeners(GameListener base) {
        GameListener listener = base;
        for (Team team : List.of(teamA, teamB)) {
            Team opponent = team == teamA ? teamB : teamA;
            for (Player p : team.members()) {
                if (p instanceof BotPlayer bot && bot.strategy() instanceof LlmBotStrategy llm) {
                    llm.setTeams(team, opponent);
                    llmBot = llm;
                    listener = new FanOutGameListener(listener, llm);
                }
            }
        }
        return listener;
    }

    private static String teamName(List<Player> players, String fallback) {
        if (players.isEmpty()) return fallback;
        return players.stream().map(Player::name).collect(java.util.stream.Collectors.joining(" & "));
    }

    /** 호스트 모드: 대기실 구성(원격 슬롯 포함)으로 권위 Game 을 실행한다. */
    public void startHostGame(MatchSetup setup, GameServer server) {
        this.mode = Mode.HOST;
        this.matchSetup = setup;
        this.config = setup.gameConfig();
        this.server = server;
        this.client = null;
        buildAndStartHostGame(false);
        installEscHandler();
    }

    /**
     * 호스트 게임을 구성하고 시작한다. {@code restart} 면 같은 연결로 재시작(핸드셰이크 대신 Restart 전송).
     * 대기실 {@code matchSetup} 의 슬롯대로 팀/플레이어를 만들고, 원격 슬롯마다 {@link NetworkPlayer}
     * 를 만들어 그 클라이언트 세션에 묶는다. 클라이언트별 관점 초기 상태를 핸드셰이크로 보낸다.
     */
    private void buildAndStartHostGame(boolean restart) {
        resetBoard();
        MatchSetup setup = this.matchSetup;

        // 사람(방장)이 속한 대기실 팀을 board teamA(내 필드)로 둔다.
        boolean humanInA = setup.teamA().stream()
                .anyMatch(s -> s.kind() == MatchSetup.SlotKind.HUMAN_LOCAL);
        List<MatchSetup.Slot> mySlots = humanInA ? setup.teamA() : setup.teamB();
        List<MatchSetup.Slot> oppSlots = humanInA ? setup.teamB() : setup.teamA();

        HumanPlayer[] hostRef = new HumanPlayer[1];
        List<RemoteBinding> remotes = new ArrayList<>();
        List<Player> myPlayers = buildPlayers(mySlots, hostRef, remotes);
        List<Player> oppPlayers = buildPlayers(oppSlots, hostRef, remotes);

        HumanPlayer host = hostRef[0];
        localPlayer = host;
        inputGateway = new LocalInputGateway(host);

        teamA = new Team(teamName(myPlayers, "우리 팀"), myPlayers);
        teamB = new Team(teamName(oppPlayers, "상대 팀"), oppPlayers);
        animator = new BoardAnimator(contentArea, globalOverlay, centerArea, this::isLocalActor, CARD_ORDER_BY_COLOR);
        updateBoardStatus();

        List<Player> allPlayers = new ArrayList<>(myPlayers);
        allPlayers.addAll(oppPlayers);
        this.networkPlayers = remotes.stream().map(RemoteBinding::player).toList();

        int epoch = EPOCH_SEQ.incrementAndGet();
        broadcaster = new NetworkBroadcaster(server, teamA, teamB, null, allPlayers, epoch);
        FanOutGameListener fanOut = new FanOutGameListener(this, broadcaster);
        Game game = new Game(config, teamA, teamB, wrapWithLlmBotListeners(fanOut));
        broadcaster.setGame(game);
        this.currentGame = game;

        server.beginGame(allPlayers);
        // 게임 단계 리스너를 핸드셰이크·바인딩보다 먼저 설치한다 — 대기실→게임 전환 창에서
        // 끊긴 클라이언트의 통지가 옛 대기실 리스너로 새지 않고 여기로 온다.
        server.setClientListener(new GameServer.ClientListener() {
            @Override public void onClientConnected(int clientId) { /* 게임 중 신규 접속 없음 */ }
            @Override public void onLobbyMessage(int clientId, NetMessage msg) { handleClientPreview(clientId, msg); }
            @Override public void onClientDisconnected(int clientId) { onHostDisconnect(); }
        });
        // 각 원격 클라이언트에 자기 관점 초기 상태 + 팀/플레이어 id 를 핸드셰이크로 보내고 대리자를 바인딩한다.
        for (RemoteBinding rb : remotes) {
            int playerId = allPlayers.indexOf(rb.player());
            int teamId = myPlayers.contains(rb.player()) ? 0 : 1;
            PublicBoardState init = WireCodec.buildState(teamA, teamB, game, playerId, allPlayers);
            NetMessage msg = restart
                    ? new NetMessage.Restart(epoch, config.winningCoins(), config.leaderEffectsEnabled(),
                            teamId, playerId, init)
                    : new NetMessage.Handshake(epoch, config.winningCoins(), config.leaderEffectsEnabled(),
                            teamId, playerId, init);
            server.sendTo(rb.clientId(), msg);
            server.bindPlayer(rb.clientId(), rb.player());
        }
        // 리스너 설치 전에 이미 끊겨 통지를 놓친 원격(전환 창 끊김, 끊긴 채 다시하기 등)이 있으면
        // 게임을 시작하지 않고 즉시 끊김 처리한다 — 죽은 대리자를 영원히 기다리는 멈춤 방지.
        for (RemoteBinding rb : remotes) {
            if (server.session(rb.clientId()) == null) {
                onHostDisconnect();
                return;
            }
        }

        startGameThread(game);
    }

    /** 호스트 net-reader 가 연결 종료를 감지하면 호출. 진행 중인 게임/대리자들을 깨워 정상 종료시킨다. */
    private void onHostDisconnect() {
        Game g = this.currentGame;
        for (NetworkPlayer np : this.networkPlayers) {
            np.abort();   // decideSplit/Choice/Helpers/Distribution 대기 해제
        }
        if (g != null) g.abort();     // 환금 인박스 대기 해제
        try {
            if (server != null) server.close();
        } catch (java.io.IOException ignored) {
            // 이미 끊긴 연결 — 무시
        }
        Platform.runLater(() -> showDisconnected("상대 연결이 끊어졌습니다."));
    }

    /**
     * 클라이언트 모드: 핸드셰이크로 미러를 초기화하고 Game 없이 미러 상태만 추종한다.
     * reader 스레드는 대기실 단계에서 이미 돌고 있으므로 {@link GameClient#enterGame} 로 게임 단계로 전환만 한다.
     */
    public void startClientGame(GameClient client, NetMessage.Handshake hs) {
        this.mode = Mode.CLIENT;
        this.server = null;
        this.client = client;
        this.broadcaster = null;
        ClientMirror mirror = new ClientMirror();
        mirror.init(hs.clientTeamId(), hs.clientPlayerId(), hs.initialState());
        resetBoard();
        bindClientMirror(mirror, client);

        client.enterGame(mirror, hs.epoch(), this,
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
        mirror.init(restart.clientTeamId(), restart.clientPlayerId(), restart.initialState());
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
        llmBot = null;
        disconnected = false;
        introPhase = true;
        pauseMenuOpen = false;
        phaseRevision = 0;
        distributionFieldUpdatePending = false;
        clearTeammatePanelState();
        cashPanel.reset();
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
        loop.setUncaughtExceptionHandler((t, e) -> {});
        this.gameThread = loop;
        loop.start();
    }

    /** 진행 중인 게임 스레드를 중단시킨다(재시작·나가기 공용). 소켓·net-reader 는 호출자가 관리한다. */
    private void tearDownCurrentGame() {
        if (currentGame != null) currentGame.abort();      // 환금 인박스 대기 해제
        for (NetworkPlayer np : networkPlayers) np.abort(); // 호스트 결정 채널들 해제
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

        // 다시하기: 오프라인·호스트만 (네트워크 클라이언트는 재시작을 시작할 수 없음).
        // 끊긴 뒤에는 상대가 없으므로 숨긴다.
        if (mode != Mode.CLIENT && !disconnected) {
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
                if (disconnected) return;  // 끊긴 채 재시작 불가(버튼은 숨겨지지만 이중 안전망)
                tearDownCurrentGame();
                buildAndStartHostGame(true);
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
        disconnected = true;
        if (!gameOver) {
            setMessage(msg);
            Label label = new Label(msg);
            label.getStyleClass().add("waiting-label");
            label.setWrapText(true);

            Button exitBtn = new Button("메인 화면으로 나가기");
            exitBtn.getStyleClass().add("menu-button");
            exitBtn.setOnAction(e -> exitToMainMenu());

            VBox panel = new VBox(20, label, exitBtn);
            panel.setAlignment(Pos.CENTER);
            panel.setPadding(new Insets(32));
            panel.getStyleClass().add("waiting-panel");
            showScreenOverlay(panel);
        }
    }

    // ===== GameListener (게임 스레드 → UI) =====

    @Override
    public void onGameSetup(List<Player> players) {
        Platform.runLater(() -> {
            // 팀 대 팀 구도로 보여주기 위해 우리 팀(teamA)/상대 팀(teamB)으로 나눈다.
            List<Player> myMembers = new ArrayList<>();
            List<Player> oppMembers = new ArrayList<>();
            for (Player p : players) {
                if (teamA != null && teamA.members().contains(p)) {
                    myMembers.add(p);
                } else {
                    oppMembers.add(p);
                }
            }
            enqueueOverlay(() -> animator.playOfficerSetup(oppMembers, myMembers));
        });
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
            cashDonePlayers.clear();
            if (phase == Phase.CASH_IN && !newlyReceivedCards.isEmpty()) {
                PauseTransition clearNew = new PauseTransition(Duration.seconds(5));
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
            } else {
                newlyReceivedCards.clear();
                newlyReceivedOpponentCards.clear();
            }
            clearTeammatePanelState();
            clearCenterIfOpponentWaiting();
            turnLabel.setText(phase.korean());
            updateBoardStatus();
            if (phase == Phase.SCHEME && !isLocalActor(splitTeam.leader())) {
                String schemeMsg = splitTeam == teamA
                        ? "리더가 카드를 분할 중입니다"
                        : "상대가 카드를 분할 중입니다";
                runAfterOverlay(() -> setWaitingIfCurrent(phaseToken, schemeMsg));
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
                String choiceMsg = chooser == teamA
                        ? "리더가 묶음을 선택 중입니다"
                        : "상대가 카드를 선택 중입니다";
                runAfterOverlay(() -> setWaitingChoiceIfCurrent(phaseToken, bundles, choiceMsg));
            }
        });
    }

    @Override
    public void onDistributed(int chosenIndex, Team chooseTeam, List<Card> chooseCards,
            Team splitTeam, List<Card> splitCards) {
        Platform.runLater(() -> {
            distributionFieldUpdatePending = true;
            int phaseToken = phaseRevision;
            setMessage(chooseTeam.name() + "이(가) " + (chosenIndex == 0 ? "왼쪽" : "오른쪽") + " 묶음 선택 · "
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
            });
            // 내 팀이 분배에 관여하지 않는(1인 팀) 경우, 상대 다인 팀의 분배를 기다리는 동안 빈 화면이
            // 되지 않도록 대기 안내를 띄운다. 다인 팀이면 분배/읽기전용 패널이 대신 표시된다.
            Team idleOpp = myTeam != null ? otherTeam(myTeam) : null;
            if (myTeam != null && myTeam.members().size() == 1
                    && idleOpp != null && idleOpp.members().size() > 1) {
                runAfterOverlay(() -> setWaitingIfCurrent(phaseToken, "상대 팀이 카드를 분배하는 중입니다"));
            }
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
            var panel = ScorePanels.gameOver(teamA, teamB, winner, mode != Mode.CLIENT,
                    this::restartGame, this::exitToMainMenu);
            showScreenOverlay(panel.root());
            if (llmBot != null) {
                llmBot.requestEndLine(winner, line -> Platform.runLater(() -> panel.setBotQuote().accept(line)));
            }
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
            setCenterAnimated(SplitPanelBuilder.build(hand,
                    decision -> { clearCenter(); inputGateway.provideSplit(decision); }));
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
        if (!isLocalActor(player)) {
            if (isAlly(player) && options != null && !options.isEmpty()) {
                Platform.runLater(() -> runAfterOverlay(() ->
                        setCenterAnimated(buildTeammateHelperWaitingPanel(player, options))));
            }
            return;
        }
        Platform.runLater(() -> runAfterOverlay(() -> {
            updateBoardStatus();
            setCenterAnimated(buildHelperSelectionPanel(options, chooseCount));
        }));
    }

    @Override
    public void onRequestTeamDistribution(Player leader, Team team, List<Card> acquired) {
        if (isLocalActor(leader)) {
            Platform.runLater(() -> runAfterOverlay(() -> {
                updateBoardStatus();
                setCenterAnimated(buildTeamDistributionPanel(team, acquired));
            }));
        } else if (isAlly(leader)) {
            // 같은 팀 팀원: 리더의 분배를 읽기 전용으로 보며 라이브 동기화한다(봇 리더면 즉시 결정되어 잠깐 표시).
            Platform.runLater(() -> runAfterOverlay(() ->
                    setCenterAnimated(buildTeammateDistributionPanel(team, acquired, leader))));
        }
        // 봇 리더(상대 팀)·내가 관여하지 않는 분배는 패널을 띄우지 않는다.
    }

    @Override
    public void onTeamDistributionPreview(List<Integer> assignment) {
        Platform.runLater(() -> applyTeammateDistributionPreview(assignment));
    }

    @Override
    public void onHelperSelectionPreview(List<Integer> roles) {
        Platform.runLater(() -> applyTeammateHelperPreview(roles));
    }

    @Override
    public void onTeamDistributionDone(int leaderId) {
        Platform.runLater(this::switchTeammateToOpponentWaiting);
    }

    @Override
    public void onCashTurn(Player player, CashInContext snapshot) {
        if (!isLocalActor(player)) {
            return;
        }
        Platform.runLater(() -> runAfterOverlay(() ->
                cashPanel.render(localPlayer, snapshot, new ArrayList<>(snapshot.holdings()))));
    }

    @Override
    public void onCashDone(Player player) {
        if (isLocalActor(player)) {
            Platform.runLater(() -> {
                cashPanel.reset();
                cashDonePlayers.add(player);
                updateBoardStatus();
                runAfterOverlay(() -> setWaiting("환금 완료 — 상대를 기다리는 중"));
            });
        } else {
            Platform.runLater(() -> {
                cashDonePlayers.add(player);
                updateBoardStatus();
            });
        }
    }

    // ===== 패널 빌더 =====

    private Node buildChoicePanel(ChoiceView view) {
        VBox root = panelRoot("두 묶음 중 하나를 선택하세요. (뒷면 카드는 가려져 있습니다)");

        HBox bundlesRow = new HBox(40);
        bundlesRow.setAlignment(Pos.CENTER);
        activeBundle0 = null;
        activeBundle1 = null;
        for (int i = 0; i < view.bundles().size(); i++) {
            BundleView bundle = view.bundle(i);
            int index = i;

            Button pick = new Button((i == 0 ? "왼쪽" : "오른쪽") + " 묶음 선택 (" + bundle.size() + "장)");
            pick.getStyleClass().add("menu-button");

            VBox box = bundleBox((i == 0 ? "왼쪽" : "오른쪽") + " 묶음", bundle.visibleCards(), bundle.hasFaceDown(), pick);
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

    private Node buildWaitingChoicePanel(GameListener.BundlePair bundles, String title) {
        return buildWaitingChoicePanel(
                bundles.visible0(), bundles.faceDown0(),
                bundles.visible1(), bundles.faceDown1(), title);
    }

    private Node buildWaitingChoicePanel(List<Card> visible0, boolean faceDown0,
            List<Card> visible1, boolean faceDown1, String title) {
        VBox root = panelRoot(title);
        HBox bundlesRow = new HBox(40);
        bundlesRow.setAlignment(Pos.CENTER);
        activeBundle0 = bundleBox("왼쪽 묶음", visible0, faceDown0);
        activeBundle1 = bundleBox("오른쪽 묶음", visible1, faceDown1);
        bundlesRow.getChildren().addAll(activeBundle0, activeBundle1);
        root.getChildren().add(bundlesRow);
        return root;
    }

    private void ensureDistributionAnimationPanel(int chosenIndex, List<Card> chooseCards, List<Card> splitCards) {
        if (activeBundle0 != null && activeBundle1 != null) {
            return;
        }
        VBox chosenBox = bundleBox("선택한 " + (chosenIndex == 0 ? "왼쪽" : "오른쪽") + " 묶음", chooseCards, false);
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
        return Panels.bundleBox(title, visibleCards, hasFaceDown, controls);
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
        clearTeammatePanelState(); // 내가 리더 — 팀원 읽기 전용 패널은 없다.
        List<Player> members = team.members();
        Map<Card, Integer> assign = balancedAssignment(acquired, members);

        VBox root = panelRoot("가져온 카드를 팀원과 나눠 가지세요. 카드를 눌러 보관할 사람을 바꿉니다.");

        FlowPane cardRow = new FlowPane(14, 10);
        cardRow.setAlignment(Pos.CENTER);
        for (Card card : acquired) {
            VBox cell = new VBox(6);
            cell.setAlignment(Pos.CENTER);
            CardView cardView = new CardView(card, true);
            Label owner = new Label();
            applyOwnerLabel(owner, members.get(assign.get(card)));
            cardView.setOnMouseClicked(e -> {
                int next = (assign.get(card) + 1) % members.size();
                assign.put(card, next);
                applyOwnerLabel(owner, members.get(next));
                broadcastTeamDistributionPreview(assignmentList(acquired, assign)); // 팀원 화면 동기화
            });
            cell.getChildren().addAll(cardView, owner);
            cardRow.getChildren().add(cell);
        }

        Button confirm = new Button("분배 확정");
        confirm.getStyleClass().add("menu-button");
        boolean[] submitted = {false};
        confirm.setOnAction(e -> {
            // 결정은 FX 스레드에서 SynchronousQueue.put 으로 게임 스레드에 넘긴다. 중복 클릭하면
            // 두 번째 put 이 받는 쪽 없이 FX 스레드를 영원히 막아 화면이 멈춘다 → 한 번만 제출하도록 막는다.
            if (submitted[0]) {
                return;
            }
            submitted[0] = true;
            confirm.setDisable(true);
            cardRow.setDisable(true);
            List<List<Card>> byMember = new ArrayList<>();
            for (int i = 0; i < members.size(); i++) {
                byMember.add(new ArrayList<>());
            }
            for (Card card : acquired) {
                byMember.get(assign.get(card)).add(card);
            }
            // 제출 후 상대 팀 분배를 기다리는 동안 빈 화면 대신 대기 안내를 띄우고, 같은 팀 팀원도
            // 읽기 전용 패널에서 같은 대기 화면으로 전환시킨다.
            setWaiting("상대 팀이 카드를 분배하는 중입니다");
            broadcastTeamDistributionDone();
            inputGateway.provideDistribution(new TeamDistribution(byMember));
        });
        HBox buttonRow = new HBox(confirm);
        buttonRow.setAlignment(Pos.CENTER);

        root.getChildren().addAll(cardRow, buttonRow);
        broadcastTeamDistributionPreview(assignmentList(acquired, assign)); // 초기 균형 배정 동기화
        return root;
    }

    /** 같은 팀 팀원(리더가 아닌 아군)이 보는 읽기 전용 분배 패널. 리더의 배정을 라이브로 따라간다. */
    private Node buildTeammateDistributionPanel(Team team, List<Card> acquired, Player leader) {
        List<Player> members = team.members();
        Map<Card, Integer> assign = balancedAssignment(acquired, members); // 리더 초기값과 동일한 규칙

        VBox root = panelRoot(leader.name() + " 리더가 가져온 카드를 분배 중입니다...");

        List<Label> ownerLabels = new ArrayList<>();
        FlowPane cardRow = new FlowPane(14, 10);
        cardRow.setAlignment(Pos.CENTER);
        for (Card card : acquired) {
            VBox cell = new VBox(6);
            cell.setAlignment(Pos.CENTER);
            CardView cardView = new CardView(card, true);
            Label owner = new Label();
            applyOwnerLabel(owner, members.get(assign.get(card)));
            ownerLabels.add(owner);
            cell.getChildren().addAll(cardView, owner);
            cardRow.getChildren().add(cell);
        }
        // 읽기 전용: 클릭 핸들러를 달지 않는다(제목으로 리더가 분배 중임을 안내).

        // 라이브 동기화 참조 등록(도우미 패널 참조는 비운다).
        teammateHelperBadges = null;
        teammateHelperLeader = null;
        teammateDistMembers = members;
        teammateDistOwnerLabels = ownerLabels;

        root.getChildren().add(cardRow);
        return root;
    }

    /** 리더의 분배 프리뷰를 팀원 패널에 반영한다. 활성 분배 패널이 없으면 무시. */
    private void applyTeammateDistributionPreview(List<Integer> assignment) {
        if (teammateDistOwnerLabels == null || teammateDistMembers == null) {
            return;
        }
        int n = Math.min(assignment.size(), teammateDistOwnerLabels.size());
        for (int i = 0; i < n; i++) {
            int idx = assignment.get(i);
            if (idx >= 0 && idx < teammateDistMembers.size()) {
                applyOwnerLabel(teammateDistOwnerLabels.get(i), teammateDistMembers.get(idx));
            }
        }
    }

    /** 가져온 카드를 멤버 보유 수가 적은 쪽부터 균형 있게 초기 배정한다(리더·팀원 패널 공통). */
    private Map<Card, Integer> balancedAssignment(List<Card> acquired, List<Player> members) {
        Map<Card, Integer> assign = new HashMap<>();
        int[] counts = new int[members.size()];
        for (int i = 0; i < members.size(); i++) {
            counts[i] = members.get(i).holdingCount();
        }
        for (Card card : acquired) {
            int target = 0;
            for (int i = 1; i < members.size(); i++) {
                if (counts[i] < counts[target]) target = i;
            }
            assign.put(card, target);
            counts[target]++;
        }
        return assign;
    }

    /** 배정 맵을 가져온 카드 순서의 멤버 인덱스 리스트로 변환(프리뷰 전송용). */
    private List<Integer> assignmentList(List<Card> acquired, Map<Card, Integer> assign) {
        List<Integer> list = new ArrayList<>(acquired.size());
        for (Card card : acquired) {
            list.add(assign.getOrDefault(card, 0));
        }
        return list;
    }

    /** 분배 패널의 보관자 라벨을 갱신한다. 내(로컬) 카드면 강조 스타일을 입힌다. */
    private void applyOwnerLabel(Label label, Player owner) {
        label.setText(owner.name() + (isLocalActor(owner) ? " (나)" : ""));
        label.getStyleClass().setAll("distribution-owner");
        if (isLocalActor(owner)) {
            label.getStyleClass().add("distribution-owner-mine");
        }
    }

    private Node buildHelperSelectionPanel(List<HelperCard> options, int chooseCount) {
        clearTeammatePanelState(); // 내가 선택 주체 — 팀원 읽기 전용 패널은 없다.
        Team myTeam = teamFor(localPlayer);
        Player teammate = myTeam == null ? null : teammateOf(myTeam, localPlayer);
        // 2인 팀 리더는 고르는 동시에 나/팀원 배정까지 한 패널에서 끝낸다(규칙 §4-3).
        if (teammate != null && chooseCount == 2) {
            return buildHelperSelectAndAssignPanel(options, teammate);
        }
        return buildHelperSelectOnlyPanel(options, chooseCount);
    }

    /**
     * 팀원(리더가 아닌 쪽)이 볼 수 있는 도우미 후보 읽기 전용 패널.
     * 각 후보에 역할 배지를 달고, 리더의 선택({@link #onHelperSelectionPreview})을 라이브로 따라간다.
     */
    private Node buildTeammateHelperWaitingPanel(Player leader, List<HelperCard> options) {
        VBox root = panelRoot(leader.name() + " 리더가 도우미를 선택 중입니다...");
        FlowPane row = new FlowPane(12, 12);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("helper-grid");
        row.setPrefWrapLength(860);

        List<Label> badges = new ArrayList<>();
        List<StackPane> cards = new ArrayList<>();
        for (HelperCard helper : options) {
            Label badge = new Label("미선택");
            badge.getStyleClass().add("helper-pick-badge");

            Label nameLbl = new Label(helper.displayName());
            nameLbl.getStyleClass().add("helper-button-name");
            nameLbl.setWrapText(true);
            nameLbl.setMaxWidth(220);
            Label descLbl = new Label(helper.effectText());
            descLbl.getStyleClass().add("helper-button-desc");
            descLbl.setWrapText(true);
            descLbl.setMaxWidth(220);

            VBox content = new VBox(6, badge, nameLbl, descLbl);
            content.setAlignment(Pos.CENTER);
            StackPane card = new StackPane(content);
            card.getStyleClass().addAll("helper-button", "helper-pick-card");
            card.setPrefWidth(250);
            card.setPrefHeight(150);

            badges.add(badge);
            cards.add(card);
            row.getChildren().add(card);
        }

        // 라이브 동기화 참조 등록(분배 패널 참조는 비운다).
        teammateDistMembers = null;
        teammateDistOwnerLabels = null;
        teammateHelperBadges = badges;
        teammateHelperCards = cards;
        teammateHelperLeader = leader;

        root.getChildren().add(row);
        return root;
    }

    /** 리더의 도우미 선택 프리뷰를 팀원 패널에 반영한다. 활성 도우미 패널이 없으면 무시. */
    private void applyTeammateHelperPreview(List<Integer> roles) {
        if (teammateHelperBadges == null || teammateHelperCards == null) {
            return;
        }
        int n = Math.min(roles.size(), teammateHelperBadges.size());
        for (int i = 0; i < n; i++) {
            int role = roles.get(i);
            Label badge = teammateHelperBadges.get(i);
            StackPane card = teammateHelperCards.get(i);
            card.getStyleClass().removeAll("helper-pick-mine", "helper-pick-mate");
            badge.getStyleClass().removeAll("helper-pick-badge-mine", "helper-pick-badge-mate");
            switch (role) {
                case 1 -> {
                    // 리더 몫
                    card.getStyleClass().add("helper-pick-mate");
                    badge.getStyleClass().add("helper-pick-badge-mate");
                    badge.setText(teammateHelperLeader != null ? teammateHelperLeader.name() : "리더");
                }
                case 2 -> {
                    // 팀원(나) 몫
                    card.getStyleClass().add("helper-pick-mine");
                    badge.getStyleClass().add("helper-pick-badge-mine");
                    badge.setText("나");
                }
                default -> badge.setText("미선택");
            }
        }
    }

    /** 1인 팀(또는 1장 선택): 후보 중 {@code chooseCount} 장만 고른다. */
    private Node buildHelperSelectOnlyPanel(List<HelperCard> options, int chooseCount) {
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
        boolean[] submitted = {false};
        done.setOnAction(e -> {
            List<HelperCard> selected = toggles.stream()
                    .filter(ToggleButton::isSelected)
                    .map(t -> (HelperCard) t.getUserData())
                    .toList();
            if (selected.size() != chooseCount) {
                alert(chooseCount + "장을 선택하세요.");
                return;
            }
            // 중복 클릭(FX 스레드 put 교착) 방지.
            if (submitted[0]) {
                return;
            }
            submitted[0] = true;
            done.setDisable(true);
            setWaiting("상대가 도우미를 선택 중입니다");
            inputGateway.provideHelpers(selected);
        });

        root.getChildren().addAll(row, done);
        return root;
    }

    /**
     * 2인 팀 리더용: 후보 3장 중 2장을 고르면서 동시에 나/팀원에게 배정한다(규칙 §4-3).
     * 카드를 누르면 미선택 → 나 → 팀원 순으로 순환한다. 같은 역할은 한 장만 가질 수 있고,
     * "나" 1장 + "팀원" 1장이 되면 제출 가능. 제출 순서 [나, 팀원] → 엔진이 리더(=나)·멤버(=팀원)에 매핑.
     */
    private Node buildHelperSelectAndAssignPanel(List<HelperCard> options, Player teammate) {
        final int NONE = 0, MINE = 1, MATE = 2;
        VBox root = panelRoot("후보 3장 중 2장을 골라 팀과 나누세요. "
                + "카드를 누르면 배정되고, 다시 누르면 역할(나 ↔ " + teammate.name() + ")이 바뀝니다.");

        Map<HelperCard, Integer> state = new HashMap<>();
        Map<HelperCard, Node> cardOf = new HashMap<>();
        Map<HelperCard, Label> badgeOf = new HashMap<>();

        FlowPane row = new FlowPane(12, 12);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("helper-grid");
        row.setPrefWrapLength(860);

        Button done = new Button("선택 완료");
        done.getStyleClass().add("menu-button");
        done.setDisable(true);

        Runnable refresh = () -> {
            long mine = options.stream().filter(h -> state.get(h) == MINE).count();
            long mate = options.stream().filter(h -> state.get(h) == MATE).count();
            for (HelperCard h : options) {
                int s = state.get(h);
                Node card = cardOf.get(h);
                Label badge = badgeOf.get(h);
                card.getStyleClass().removeAll("helper-pick-mine", "helper-pick-mate");
                badge.getStyleClass().removeAll("helper-pick-badge-mine", "helper-pick-badge-mate");
                switch (s) {
                    case MINE -> {
                        card.getStyleClass().add("helper-pick-mine");
                        badge.getStyleClass().add("helper-pick-badge-mine");
                        badge.setText("나");
                    }
                    case MATE -> {
                        card.getStyleClass().add("helper-pick-mate");
                        badge.getStyleClass().add("helper-pick-badge-mate");
                        badge.setText(teammate.name());
                    }
                    default -> {
                        badge.setText("미선택");
                    }
                }
            }
            done.setDisable(!(mine == 1 && mate == 1));
        };

        for (HelperCard helper : options) {
            state.put(helper, NONE);

            Label badge = new Label("미선택");
            badge.getStyleClass().add("helper-pick-badge");
            badgeOf.put(helper, badge);

            Label nameLbl = new Label(helper.displayName());
            nameLbl.getStyleClass().add("helper-button-name");
            nameLbl.setWrapText(true);
            nameLbl.setMaxWidth(220);

            Label descLbl = new Label(helper.effectText());
            descLbl.getStyleClass().add("helper-button-desc");
            descLbl.setWrapText(true);
            descLbl.setMaxWidth(220);

            VBox content = new VBox(6, badge, nameLbl, descLbl);
            content.setAlignment(Pos.CENTER);

            StackPane card = new StackPane(content);
            card.getStyleClass().addAll("helper-button", "helper-pick-card");
            card.setPrefWidth(250);
            card.setPrefHeight(150);
            card.setOnMouseClicked(e -> {
                int cur = state.get(helper);
                int next;
                switch (cur) {
                    case NONE -> {
                        // 미선택 카드는 아직 비어 있는 역할(나 우선, 없으면 팀원)에 배정 →
                        // 두 카드를 한 번씩만 눌러도 나·팀원 배정이 끝난다.
                        boolean hasMine = options.stream().anyMatch(h -> state.get(h) == MINE);
                        boolean hasMate = options.stream().anyMatch(h -> state.get(h) == MATE);
                        next = !hasMine ? MINE : (!hasMate ? MATE : MINE);
                    }
                    case MINE -> next = MATE;    // 다시 누르면 역할 전환
                    default -> next = NONE;    // 한 바퀴 돌면 해제
                }
                // 같은 역할(나/팀원)은 한 장만: 다른 카드가 이미 그 역할이면 비운다.
                if (next == MINE || next == MATE) {
                    for (HelperCard other : options) {
                        if (other != helper && state.get(other) == next) {
                            state.put(other, NONE);
                        }
                    }
                }
                state.put(helper, next);
                refresh.run();
                broadcastHelperSelectionPreview(rolesList(options, state)); // 팀원 화면 동기화
            });
            cardOf.put(helper, card);
            row.getChildren().add(card);
        }
        refresh.run();
        broadcastHelperSelectionPreview(rolesList(options, state)); // 초기 상태(전부 미선택) 동기화

        boolean[] submitted = {false};
        done.setOnAction(e -> {
            HelperCard mine = options.stream().filter(h -> state.get(h) == MINE).findFirst().orElse(null);
            HelperCard mate = options.stream().filter(h -> state.get(h) == MATE).findFirst().orElse(null);
            if (mine == null || mate == null || submitted[0]) {     // done은 유효할 때만 켜지지만 방어
                return;
            }
            submitted[0] = true;
            done.setDisable(true);
            setWaiting("상대가 도우미를 선택 중입니다");
            inputGateway.provideHelpers(List.of(mine, mate));   // [나, 팀원]
        });

        root.getChildren().addAll(row, done);
        return root;
    }

    /** 같은 팀에서 나를 제외한 다른 멤버(2인 팀의 팀원). 없으면 null. */
    private Player teammateOf(Team team, Player me) {
        for (Player p : team.members()) {
            if (p != me) {
                return p;
            }
        }
        return null;
    }

    /** 도우미 선택 상태 맵을 후보 순서의 역할 리스트로 변환(프리뷰 전송용). 값: 0=미선택,1=리더,2=팀원. */
    private List<Integer> rolesList(List<HelperCard> options, Map<HelperCard, Integer> state) {
        List<Integer> list = new ArrayList<>(options.size());
        for (HelperCard h : options) {
            list.add(state.getOrDefault(h, 0));
        }
        return list;
    }

    /** 팀원 읽기 전용(분배·도우미) 패널의 라이브 동기화 참조를 모두 비운다. */
    private void clearTeammatePanelState() {
        teammateDistMembers = null;
        teammateDistOwnerLabels = null;
        teammateHelperBadges = null;
        teammateHelperCards = null;
        teammateHelperLeader = null;
    }

    // ── 리더 선택 진행 상태 송신(같은 팀 팀원 화면 동기화) ─────────────────

    /** 리더의 팀 분배 진행 상태를 팀원에게 보낸다. 호스트는 직접 중계, 클라이언트는 호스트로 전송. */
    private void broadcastTeamDistributionPreview(List<Integer> assignment) {
        switch (mode) {
            case HOST -> {
                if (broadcaster != null) broadcaster.broadcastTeamDistributionPreview(localPlayer, assignment);
            }
            case CLIENT -> sendClientPreview(new NetMessage.DistributionPreview(assignment));
            case OFFLINE -> { /* 화면을 공유하는 원격 팀원이 없다 */ }
        }
    }

    /** 리더의 도우미 선택 진행 상태를 팀원에게 보낸다. */
    private void broadcastHelperSelectionPreview(List<Integer> roles) {
        switch (mode) {
            case HOST -> {
                if (broadcaster != null) broadcaster.broadcastHelperSelectionPreview(localPlayer, roles);
            }
            case CLIENT -> sendClientPreview(new NetMessage.HelperPreview(roles));
            case OFFLINE -> { }
        }
    }

    /** 내(리더)가 분배를 확정했음을 같은 팀 팀원에게 알린다(팀원 화면을 상대 대기로 전환). */
    private void broadcastTeamDistributionDone() {
        switch (mode) {
            case HOST -> {
                if (broadcaster != null) broadcaster.broadcastTeamDistributionDone(localPlayer);
            }
            case CLIENT -> sendClientPreview(new NetMessage.DistributionDone());
            case OFFLINE -> { }
        }
    }

    /** 읽기 전용 분배 패널을 보던 팀원이 우리 팀 분배 완료 후 상대 대기 화면으로 전환한다. */
    private void switchTeammateToOpponentWaiting() {
        if (teammateDistOwnerLabels != null) {   // 분배 읽기 전용 패널이 떠 있을 때만 전환
            clearTeammatePanelState();
            setWaiting("상대 팀이 카드를 분배하는 중입니다");
        }
    }

    private void sendClientPreview(NetMessage msg) {
        if (client == null) return;
        try {
            client.send(msg);
        } catch (java.io.IOException ignored) {
            // 끊기면 reader 가 처리 — 진행 상태 동기화는 유실돼도 무방하다.
        }
    }

    /**
     * 호스트가 받은 클라이언트 리더의 진행 상태 메시지를 처리한다(서버 reader 스레드에서 호출).
     * 같은 팀의 다른 클라이언트로 중계하고, 호스트 자신이 그 팀의 팀원이면 화면도 갱신한다.
     */
    private void handleClientPreview(int clientId, NetMessage msg) {
        if (server == null || broadcaster == null) return;
        var session = server.session(clientId);
        if (session == null || session.player() == null) return;
        NetworkPlayer actor = session.player();
        switch (msg) {
            case NetMessage.DistributionPreview m -> {
                broadcaster.broadcastTeamDistributionPreview(actor, m.assignment());
                if (isAlly(actor)) {
                    Platform.runLater(() -> applyTeammateDistributionPreview(m.assignment()));
                }
            }
            case NetMessage.HelperPreview m -> {
                broadcaster.broadcastHelperSelectionPreview(actor, m.roles());
                if (isAlly(actor)) {
                    Platform.runLater(() -> applyTeammateHelperPreview(m.roles()));
                }
            }
            case NetMessage.DistributionDone _ -> {
                broadcaster.broadcastTeamDistributionDone(actor);
                if (isAlly(actor)) {
                    Platform.runLater(this::switchTeammateToOpponentWaiting);
                }
            }
            default -> { /* 게임 중 그 외 비결정 메시지는 무시 */ }
        }
    }

    /**
     * 중앙 발동 오버레이를 띄울 도우미인지. 조건부 보너스(쿠쿠·레오·럭키)는 코인 플로팅 +
     * 사이드바 카드 공개로 충분하므로 생략하고, 판을 바꾸는 행동 도우미와 즉승(알파)만 오버레이를 쓴다.
     */
    private boolean usesEffectOverlay(HelperKind kind) {
        return kind != HelperKind.CUCKOO && kind != HelperKind.LEO && kind != HelperKind.LUCKY;
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

    // ===== 공통 헬퍼 =====

    private VBox panelRoot(String guide) {
        return Panels.panelRoot(guide);
    }

    private Node waitingPanel(String text) {
        return Panels.waitingPanel(text);
    }

    private void setWaiting(String text) {
        setCenterAnimated(waitingPanel(text), true);
    }

    private void setWaitingIfCurrent(int phaseToken, String text) {
        if (phaseToken == phaseRevision) {
            setWaiting(text);
        }
    }

    private void setWaitingChoiceIfCurrent(int phaseToken, GameListener.BundlePair bundles, String title) {
        if (phaseToken != phaseRevision) {
            return;
        }
        setCenterAnimated(buildWaitingChoicePanel(bundles, title), true);
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
        updateBoardStatus(false);
    }

    private void updateBoardStatus(boolean forceFieldUpdate) {
        updateFields(forceFieldUpdate);
    }

    private void updateFields(boolean forceUpdate) {
        if (distributionFieldUpdatePending && !forceUpdate) {
            return;
        }
        boolean showFieldUI = !introPhase && !gameOver;
        if (fieldASortBox != null) fieldASortBox.setVisible(showFieldUI);
        if (fieldBSortBox != null) fieldBSortBox.setVisible(showFieldUI);
        renderField(fieldACoinsLabel, fieldAStage, fieldAFlow, fieldAHelperFlow, teamA, sortModeA);
        renderField(fieldBCoinsLabel, fieldBStage, fieldBFlow, fieldBHelperFlow, teamB, sortModeB);
    }

    private void renderField(Label coinsLabel, StackPane stage, FlowPane field,
            VBox helpers, Team team, Comparator<Card> sortOrder) {
        if (team == null) {
            coinsLabel.setText("0 코인");
            field.getChildren().clear();
            helpers.getChildren().clear();
            return;
        }
        List<Player> members = team.members();
        coinsLabel.setText(team.coins() + " 코인");
        boolean hideDetails = introPhase || gameOver;
        renderSidebarHelpers(helpers, members);
        Map<Card, Bounds> previousBounds = hideDetails ? Map.of() : cardBoundsByScene(field);
        field.getChildren().clear();
        if (hideDetails) {
            return;
        }
        Map<Card, CardView> currentViews = new HashMap<>();
        for (Player member : members) {
            boolean cashSelectable = cashPanel.isFieldSelectable(team == teamA, member == localPlayer);
            List<Card> cards = cashSelectable
                    ? new ArrayList<>(cashPanel.selectableCards())
                    : new ArrayList<>(member.holdings());
            cards.sort(sortOrder);
            field.getChildren().add(memberChip(member, cards.size()));
            for (Card card : cards) {
                CardView cardView = new CardView(card, true);
                if (cashPanel.isSelected(card)) {
                    cardView.setSelected(true);
                }
                if (newlyReceivedCards.contains(card) || newlyReceivedOpponentCards.contains(card)) {
                    cardView.setNewlyReceived(true);
                }
                if (cashSelectable) {
                    cardView.setOnMouseClicked(e -> cashPanel.handleCardClick(cardView, card));
                }
                field.getChildren().add(cardView);
                currentViews.put(card, cardView);
            }
        }
        applyFieldOverlap(stage, field);
        playFieldReflowTransition(stage, field, previousBounds, currentViews);
    }


    private Node memberChip(Player member, int cardCount) {
        boolean done = cashDonePlayers.contains(member);
        String text = member.name() + " · " + cardCount + "/" + member.holdLimit() + "장" + (done ? " ✓" : "");
        Label chip = new Label(text);
        chip.getStyleClass().add("field-member-chip");
        if (isLocalActor(member)) chip.getStyleClass().add("field-member-chip-local");
        if (done) chip.getStyleClass().add("field-member-chip-done");
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
            // CSS translateY (-fx-translate-y: -8 for card-selected) is a StyleableProperty.
            // Calling setTranslateY() programmatically locks it at USER priority, permanently
            // blocking CSS from updating it (e.g., when card-selected is toggled later).
            // Fix: animate a separate Translate transform so CSS keeps full control of the node's own translateX/Y.
            Translate offset = new Translate(dx, dy);
            view.getTransforms().add(offset);
            Timeline move = new Timeline(new KeyFrame(Duration.millis(300),
                    new KeyValue(offset.xProperty(), 0, Interpolator.EASE_BOTH),
                    new KeyValue(offset.yProperty(), 0, Interpolator.EASE_BOTH)));
            move.setOnFinished(e -> view.getTransforms().remove(offset));
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

    private void renderSidebarHelpers(VBox target, List<Player> members) {
        target.getChildren().clear();
        for (int i = 0; i < members.size(); i++) {
            Player member = members.get(i);
            boolean ally = isAlly(member);
            target.getChildren().add(buildSidebarMemberChip(member));
            // 우리 팀(아군)의 도우미는 항상 공개한다. 상대 팀은 사용 완료된 것만 공개.
            for (HelperCard helper : member.helpers()) {
                boolean faceUp = ally || helper.isUsed();
                target.getChildren().add(buildSidebarHelperCard(helper, faceUp));
            }
            if (i < members.size() - 1) {
                Region divider = new Region();
                divider.getStyleClass().add("sidebar-member-divider");
                target.getChildren().add(divider);
            }
        }
    }

    private Node buildSidebarMemberChip(Player member) {
        String officerPart = member.officer() != null ? " | " + member.officer().korean() : "";

        Label nameLine = new Label(member.name() + officerPart);
        nameLine.setWrapText(true);
        nameLine.getStyleClass().add("sidebar-member-chip-name");

        VBox chip = new VBox(2);
        chip.setMaxWidth(Double.MAX_VALUE);
        chip.getStyleClass().add("sidebar-member-chip");
        if (isAlly(member)) chip.getStyleClass().add("sidebar-member-chip-ally");
        chip.getChildren().add(nameLine);

        if (member.officer() != null) {
            Label effectLine = new Label(member.officer().effectText());
            effectLine.setWrapText(true);
            effectLine.getStyleClass().add("sidebar-member-chip-effect");
            chip.getChildren().add(effectLine);
        }
        return chip;
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

    /** 로컬 플레이어와 같은 팀(우리 팀 = teamA)인지. 아군 도우미·분배 표시 판정에 쓴다. */
    private boolean isAlly(Player player) {
        return player != null && teamA != null && teamA.members().contains(player);
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
        Panels.alert(message);
    }
}
