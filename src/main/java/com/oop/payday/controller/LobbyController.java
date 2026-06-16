package com.oop.payday.controller;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

import com.oop.payday.app.GameApp;
import com.oop.payday.bot.BotKind;
import com.oop.payday.game.MatchSetup;
import com.oop.payday.game.MatchSetup.Slot;
import com.oop.payday.game.MatchSetup.SlotKind;
import com.oop.payday.net.GameClient;
import com.oop.payday.net.GameServer;
import com.oop.payday.net.NetMessage;
import com.oop.payday.net.NetMessage.LobbySlotView;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * 대기실 컨트롤러. 두 모드를 지원한다.
 *
 * <ul>
 *   <li><b>호스트({@link #initHost})</b>: 권위 {@link MatchSetup} 을 편집하고 {@link GameServer} 로
 *       접속을 받는다. 접속한 클라이언트는 빈자리/봇 자리에 자동 배정되며, 방장은 봇·전략·팀 이동을 편집한다.
 *       대기실 변경은 모든 클라이언트에 방송된다. '게임 시작' 시 원격이 있으면 호스트 게임, 없으면 오프라인으로 시작한다.
 *   <li><b>클라이언트({@link #initClient})</b>: 호스트가 보내는 대기실 상태를 읽기 전용으로 표시하고,
 *       핸드셰이크가 오면 게임 보드로 전환한다.
 * </ul>
 */
public final class LobbyController implements Initializable {

    @FXML private Label roomAddressLabel;
    @FXML private CheckBox practiceCheck;
    @FXML private VBox teamABox;
    @FXML private VBox teamBBox;
    @FXML private Button startButton;

    private boolean hostMode;

    // 호스트 전용
    private MatchSetup setup;
    private GameServer server;

    // 클라이언트 전용
    private GameClient client;
    private int myClientId = -1;

    // 호스트 교체 선택 상태
    private List<Slot> swapFrom = null;
    private int swapFromIndex = -1;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 모드별 초기화는 initHost()/initClient() 에서 수행한다(FXML 로드 직후 호출됨).
    }

    // ====================================================================
    // 호스트 모드
    // ====================================================================

    /** 호스트 대기실 시작: 서버를 열고 접속을 받는다. */
    public void initHost(String hostName) {
        hostMode = true;
        setup = MatchSetup.defaultSetup(hostName);
        practiceCheck.setSelected(setup.practice());
        practiceCheck.selectedProperty().addListener((obs, was, now) -> {
            if (!hostMode) return;
            setup.setPractice(now);
            broadcastLobby();
        });

        try {
            server = new GameServer();
            server.setClientListener(new GameServer.ClientListener() {
                @Override public void onClientConnected(int clientId) {
                    Platform.runLater(() -> hostOnClientConnected(clientId));
                }
                @Override public void onLobbyMessage(int clientId, NetMessage msg) {
                    Platform.runLater(() -> hostOnLobbyMessage(clientId, msg));
                }
                @Override public void onClientDisconnected(int clientId) {
                    Platform.runLater(() -> hostOnClientDisconnected(clientId));
                }
            });
            server.startAccepting();
            roomAddressLabel.setText("방 주소: " + server.localAddress() + ":" + server.port()
                    + "   |   '접속하기'로 방에 입장할 수 있습니다");
        } catch (IOException e) {
            server = null;
            roomAddressLabel.setText("방 주소: 사용 불가(포트 점유) — 봇과 오프라인으로만 플레이");
        }
        renderHostTeams();
    }

    private void hostOnClientConnected(int clientId) {
        if (server == null) return;
        if (!assignSeat(clientId)) {
            server.sendTo(clientId, new NetMessage.LobbyClosed("방이 가득 찼습니다."));
            server.closeSession(clientId);  // 클라이언트의 자발적 종료에 의존하지 않고 즉시 정리
            return;
        }
        renderHostTeams();
        broadcastLobby();
    }

    private void hostOnLobbyMessage(int clientId, NetMessage msg) {
        if (msg instanceof NetMessage.LobbyHello hello && hello.name() != null && !hello.name().isBlank()) {
            renameRemote(clientId, hello.name());
            renderHostTeams();
            broadcastLobby();
        }
    }

    private void hostOnClientDisconnected(int clientId) {
        if (freeSeat(clientId)) {
            renderHostTeams();
            broadcastLobby();
        }
    }

    // ── 자리 배정 ────────────────────────────────────────────────────

    /** 접속한 클라이언트를 봇자리→새 좌석 순으로 배정한다. 자리가 없으면 false. */
    private boolean assignSeat(int clientId) {
        String name = "플레이어 " + (clientId + 1);
        if (fillFirst(SlotKind.BOT, clientId, name)) return true;
        return addRemoteSeat(clientId, name);
    }

    private boolean fillFirst(SlotKind kind, int clientId, String name) {
        for (List<Slot> team : teams()) {
            for (int i = 0; i < team.size(); i++) {
                if (team.get(i).kind() == kind) {
                    team.set(i, Slot.remote(clientId, name));
                    return true;
                }
            }
        }
        return false;
    }

    /** 인원이 적은 팀부터 새 원격 좌석을 추가한다(균형). */
    private boolean addRemoteSeat(int clientId, String name) {
        List<Slot> a = setup.teamA();
        List<Slot> b = setup.teamB();
        boolean aRoom = a.size() < MatchSetup.MAX_TEAM_SIZE;
        boolean bRoom = b.size() < MatchSetup.MAX_TEAM_SIZE;
        List<Slot> target;
        if (aRoom && bRoom) {
            target = a.size() <= b.size() ? a : b;
        } else if (aRoom) {
            target = a;
        } else if (bRoom) {
            target = b;
        } else {
            return false;
        }
        target.add(Slot.remote(clientId, name));
        return true;
    }

    /** 클라이언트가 점유한 원격 좌석을 제거한다. */
    private boolean freeSeat(int clientId) {
        for (List<Slot> team : teams()) {
            for (int i = 0; i < team.size(); i++) {
                Slot s = team.get(i);
                if (s.kind() == SlotKind.REMOTE && s.clientId() == clientId) {
                    team.remove(i);
                    return true;
                }
            }
        }
        return false;
    }

    private void renameRemote(int clientId, String name) {
        for (List<Slot> team : teams()) {
            for (int i = 0; i < team.size(); i++) {
                Slot s = team.get(i);
                if (s.kind() == SlotKind.REMOTE && s.clientId() == clientId) {
                    team.set(i, Slot.remote(clientId, name));
                    return;
                }
            }
        }
    }

    private List<List<Slot>> teams() {
        return List.of(setup.teamA(), setup.teamB());
    }

    // ── 대기실 방송 ──────────────────────────────────────────────────

    private void broadcastLobby() {
        if (server == null) return;
        List<LobbySlotView> views = slotViews();
        for (GameServer.ClientSession s : server.sessions()) {
            server.sendTo(s.clientId(), new NetMessage.LobbyState(views, setup.practice(), s.clientId()));
        }
    }

    private List<LobbySlotView> slotViews() {
        List<LobbySlotView> views = new ArrayList<>();
        addViews(views, 0, setup.teamA());
        addViews(views, 1, setup.teamB());
        return views;
    }

    private void addViews(List<LobbySlotView> views, int teamId, List<Slot> team) {
        for (int i = 0; i < team.size(); i++) {
            Slot s = team.get(i);
            String kind = switch (s.kind()) {
                case HUMAN_LOCAL -> "HUMAN";
                case BOT -> "BOT";
                case REMOTE -> "REMOTE";
            };
            String detail = (s.kind() == SlotKind.BOT && s.botKind() != null) ? s.botKind().displayName() : "";
            views.add(new LobbySlotView(teamId, i, kind, s.name(), detail, s.clientId()));
        }
    }

    // ── 호스트 렌더링(편집 가능) ──────────────────────────────────────

    private void renderHostTeams() {
        renumberBots();
        renderHostTeam(teamABox, setup.teamA(), "우리 팀 (방장)");
        renderHostTeam(teamBBox, setup.teamB(), "상대 팀");
        refreshStartEnabled();
    }

    /** 두 팀의 봇을 순서대로 훑어 "봇 1, 봇 2…"로 다시 매긴다. */
    private void renumberBots() {
        int n = 1;
        for (List<Slot> team : teams()) {
            for (int i = 0; i < team.size(); i++) {
                Slot slot = team.get(i);
                if (slot.kind() == SlotKind.BOT) {
                    team.set(i, Slot.bot(slot.botKind(), "봇 " + n++));
                }
            }
        }
    }

    private void renderHostTeam(VBox box, List<Slot> slots, String title) {
        box.getChildren().clear();
        Label header = new Label(title);
        header.getStyleClass().add("lobby-team-title");
        box.getChildren().add(header);

        for (int i = 0; i < slots.size(); i++) {
            box.getChildren().add(hostSlotRow(slots, i));
        }
        for (int i = slots.size(); i < MatchSetup.MAX_TEAM_SIZE; i++) {
            box.getChildren().add(placeholderRow(slots));
        }
    }

    private Node placeholderRow(List<Slot> slots) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("lobby-slot");
        row.setMaxWidth(Double.MAX_VALUE);

        Button addBot = new Button("+ 봇 추가");
        addBot.getStyleClass().add("lobby-add-button");
        addBot.setOnAction(e -> {
            slots.add(Slot.bot(BotKind.S6, "봇"));
            renderHostTeams();
            broadcastLobby();
        });
        row.getChildren().add(addBot);
        return row;
    }

    private Node hostSlotRow(List<Slot> slots, int index) {
        Slot slot = slots.get(index);
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("lobby-slot");
        row.setMaxWidth(Double.MAX_VALUE);

        switch (slot.kind()) {
            case HUMAN_LOCAL -> {
                Label me = new Label("👑 " + slot.name() + " (나)");
                me.getStyleClass().add("lobby-slot-host");
                HBox.setHgrow(me, Priority.ALWAYS);
                me.setMaxWidth(Double.MAX_VALUE);
                row.getChildren().add(me);
            }
            case BOT -> {
                Label tag = new Label(slot.name());
                tag.getStyleClass().add("lobby-slot-tag");
                tag.setMinWidth(60);

                ComboBox<BotKind> strategy = new ComboBox<>();
                strategy.getStyleClass().add("lobby-combo");
                strategy.getItems().setAll(BotKind.values());
                strategy.setValue(slot.botKind() != null ? slot.botKind() : BotKind.S6);
                strategy.setConverter(botKindConverter());
                strategy.setButtonCell(botKindCell());
                strategy.setCellFactory(list -> botKindCell());
                strategy.setOnAction(e -> {
                    slots.set(index, Slot.bot(strategy.getValue(), slot.name()));
                    broadcastLobby();
                });
                HBox.setHgrow(strategy, Priority.ALWAYS);
                strategy.setMaxWidth(Double.MAX_VALUE);
                strategy.setPrefHeight(38);

                row.getChildren().addAll(tag, strategy,
                        moveButton(slots, index), removeButton(slots, index));
            }
            case REMOTE -> {
                Label tag = new Label("🧑 " + slot.name() + " (원격)");
                tag.getStyleClass().add("lobby-slot-host");
                HBox.setHgrow(tag, Priority.ALWAYS);
                tag.setMaxWidth(Double.MAX_VALUE);
                row.getChildren().addAll(tag, moveButton(slots, index));
            }
        }
        return row;
    }

    /**
     * 슬롯을 반대 팀으로 옮기는 버튼.
     * 반대 팀에 빈자리가 있으면 바로 이동, 꽉 찼으면 교체 선택 모드로 진입한다.
     */
    private Button moveButton(List<Slot> from, int index) {
        List<Slot> to = (from == setup.teamA()) ? setup.teamB() : setup.teamA();
        boolean toFull = to.size() >= MatchSetup.MAX_TEAM_SIZE;

        // 이 슬롯이 교체 선택된 상태 — 다시 클릭하면 취소
        if (swapFrom == from && swapFromIndex == index) {
            Button cancel = new Button("⇄");
            cancel.getStyleClass().add("lobby-move-button-selected");
            cancel.setOnAction(e -> { swapFrom = null; swapFromIndex = -1; renderHostTeams(); });
            return cancel;
        }

        // 반대 팀에 교체 대기 슬롯이 있음 — 이 슬롯이 교체 목표
        if (swapFrom == to) {
            return swapTargetButton(from, index);
        }

        // 일반 이동 또는 교체 선택 진입
        Button move = new Button("⇄");
        move.getStyleClass().add("lobby-move-button");
        if (!toFull) {
            move.setOnAction(e -> {
                Slot moved = from.remove(index);
                to.add(moved);
                swapFrom = null; swapFromIndex = -1;
                renderHostTeams(); broadcastLobby();
            });
        } else {
            move.setOnAction(e -> { swapFrom = from; swapFromIndex = index; renderHostTeams(); });
        }
        return move;
    }

    private Button swapTargetButton(List<Slot> targetTeam, int targetIndex) {
        Button btn = new Button("↔");
        btn.getStyleClass().add("lobby-move-button");
        btn.setOnAction(e -> {
            Slot a = swapFrom.get(swapFromIndex);
            Slot b = targetTeam.get(targetIndex);
            swapFrom.set(swapFromIndex, b);
            targetTeam.set(targetIndex, a);
            swapFrom = null; swapFromIndex = -1;
            renderHostTeams(); broadcastLobby();
        });
        return btn;
    }

    private Button removeButton(List<Slot> slots, int index) {
        Button remove = new Button("✕");
        remove.getStyleClass().add("lobby-icon-button");
        remove.setDisable(slots.size() <= 1 && setup.teamA().size() + setup.teamB().size() <= 2);
        remove.setOnAction(e -> {
            swapFrom = null; swapFromIndex = -1;
            slots.remove(index);
            renderHostTeams();
            broadcastLobby();
        });
        return remove;
    }

    // ── 클라이언트 모드 ──────────────────────────────────────────────

    /** 클라이언트 대기실 시작: 호스트의 대기실 상태를 읽기 전용으로 따른다. */
    public void initClient(GameClient client) {
        hostMode = false;
        this.client = client;
        startButton.setVisible(false);
        startButton.setManaged(false);
        practiceCheck.setDisable(true);
        roomAddressLabel.setText("호스트에 접속됨 — 방장이 시작하기를 기다리는 중…");

        client.startLobby(new GameClient.LobbyHandler() {
            @Override public void onLobbyState(NetMessage.LobbyState state) { clientApplyState(state); }
            @Override public void onGameStart(NetMessage.Handshake hs) { clientStartGame(hs); }
            @Override public void onLobbyClosed(String reason) { clientLeave(reason); }
        }, () -> clientLeave("호스트 연결이 끊어졌습니다."));
    }

    private void clientApplyState(NetMessage.LobbyState state) {
        this.myClientId = state.yourClientId();
        practiceCheck.setSelected(state.practice());
        renderClientTeam(teamABox, state, 0, "팀 1");
        renderClientTeam(teamBBox, state, 1, "팀 2");
    }

    private void renderClientTeam(VBox box, NetMessage.LobbyState state, int teamId, String title) {
        box.getChildren().clear();
        Label header = new Label(title);
        header.getStyleClass().add("lobby-team-title");
        box.getChildren().add(header);

        for (LobbySlotView v : state.slots()) {
            if (v.teamId() != teamId) continue;
            box.getChildren().add(clientSlotRow(v));
        }
    }

    private Node clientSlotRow(LobbySlotView v) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("lobby-slot");
        row.setMaxWidth(Double.MAX_VALUE);

        boolean me = v.clientId() != MatchSetup.NO_CLIENT && v.clientId() == myClientId;
        String text = switch (v.kind()) {
            case "HUMAN" -> "👑 " + v.name() + " (방장)";
            case "BOT" -> "🤖 " + v.name() + " · " + v.detail();
            case "REMOTE" -> "🧑 " + v.name() + (me ? " (나)" : " (원격)");
            default -> "빈 자리 (대기 중…)";
        };
        Label label = new Label(text);
        label.getStyleClass().add(me ? "lobby-slot-host" : "lobby-slot-tag");
        HBox.setHgrow(label, Priority.ALWAYS);
        label.setMaxWidth(Double.MAX_VALUE);
        row.getChildren().add(label);
        return row;
    }

    private void clientStartGame(NetMessage.Handshake hs) {
        try {
            GameApp.get().showClientGame(client, hs);
        } catch (IOException ignored) {
        }
    }

    private void clientLeave(String reason) {
        roomAddressLabel.setText(reason);
        try {
            GameApp.get().showScene("main_menu.fxml");
        } catch (IOException ignored) {
        }
    }

    // ── 공통 액션 ────────────────────────────────────────────────────

    private boolean hasRemoteSeat() {
        for (List<Slot> team : teams()) {
            for (Slot s : team) {
                if (s.kind() == SlotKind.REMOTE) return true;
            }
        }
        return false;
    }

    private boolean canStart() {
        return !setup.teamA().isEmpty() && !setup.teamB().isEmpty();
    }

    private void refreshStartEnabled() {
        if (startButton != null) startButton.setDisable(!canStart());
    }

    @FXML
    private void onStart() {
        if (!hostMode || !canStart()) return;
        boolean hasRemote = hasRemoteSeat();
        if (server != null) server.stopAccepting();
        try {
            if (hasRemote && server != null) {
                GameApp.get().showHostGame(setup, server);
            } else {
                if (server != null) {
                    try { server.close(); } catch (IOException ignored) {}
                }
                GameApp.get().showGameBoard(setup);
            }
        } catch (IOException ignored) {
        }
    }

    @FXML
    private void onBack() {
        if (hostMode) {
            if (server != null) {
                server.broadcast(new NetMessage.LobbyClosed("방장이 대기실을 닫았습니다."));
                try { server.close(); } catch (IOException ignored) {}
            }
        } else if (client != null) {
            try { client.close(); } catch (IOException ignored) {}
        }
        try {
            GameApp.get().showScene("main_menu.fxml");
        } catch (IOException ignored) {
        }
    }

    // ── 콤보 표시 ────────────────────────────────────────────────────

    private static StringConverter<BotKind> botKindConverter() {
        return new StringConverter<>() {
            @Override public String toString(BotKind kind) {
                return kind == null ? "" : kind.displayName();
            }
            @Override public BotKind fromString(String s) {
                return null;
            }
        };
    }

    private static ListCell<BotKind> botKindCell() {
        return new ListCell<>() {
            @Override protected void updateItem(BotKind kind, boolean empty) {
                super.updateItem(kind, empty);
                setText(empty || kind == null ? null : kind.displayName());
            }
        };
    }
}
