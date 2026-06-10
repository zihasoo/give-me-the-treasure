package com.oop.payday.controller;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

import com.oop.payday.app.GameApp;
import com.oop.payday.bot.BotKind;
import com.oop.payday.game.MatchSetup;

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
 * 대기실 컨트롤러. 2팀의 자리(슬롯) 구성을 편집해 {@link MatchSetup} 을 만들고 게임을 시작한다.
 *
 * <p>1단계(오프라인): 방장은 팀 A의 리더(첫 슬롯)로 고정되고, 나머지 자리는 봇으로 채운다.
 * 봇은 자리마다 전략(스마트/기본)을 따로 고를 수 있고, 각 팀 인원은 1~2명으로 조절한다.
 * 원격 사람 접속(빈자리 채우기)은 2단계에서 추가한다.
 */
public final class LobbyController implements Initializable {

    @FXML private Label roomAddressLabel;
    @FXML private CheckBox practiceCheck;
    @FXML private VBox teamABox;
    @FXML private VBox teamBBox;

    private final MatchSetup setup = MatchSetup.defaultSetup();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        roomAddressLabel.setText("방 주소: " + localIp() + "  (다른 사람 접속은 2단계에서 지원)");
        practiceCheck.setSelected(setup.practice());
        practiceCheck.selectedProperty().addListener((obs, was, now) -> setup.setPractice(now));
        renderTeams();
    }

    private void renderTeams() {
        renumberBots();
        renderTeam(teamABox, setup.teamA(), "우리 팀 (방장)", true);
        renderTeam(teamBBox, setup.teamB(), "상대 팀", false);
    }

    /** 두 팀의 봇을 순서대로 훑어 "봇 1, 봇 2…"로 다시 매긴다. 삭제해도 번호에 구멍이 안 생긴다. */
    private void renumberBots() {
        int n = 1;
        for (List<MatchSetup.Slot> team : List.of(setup.teamA(), setup.teamB())) {
            for (int i = 0; i < team.size(); i++) {
                MatchSetup.Slot slot = team.get(i);
                if (slot.kind() == MatchSetup.SlotKind.BOT) {
                    team.set(i, MatchSetup.Slot.bot(slot.botKind(), "봇 " + n++));
                }
            }
        }
    }

    private void renderTeam(VBox box, List<MatchSetup.Slot> slots, String title, boolean isTeamA) {
        box.getChildren().clear();
        Label header = new Label(title);
        header.getStyleClass().add("lobby-team-title");
        box.getChildren().add(header);

        for (int i = 0; i < slots.size(); i++) {
            box.getChildren().add(slotRow(slots, i));
        }

        if (slots.size() < MatchSetup.MAX_TEAM_SIZE) {
            Button addBot = new Button("+ 봇 추가");
            addBot.getStyleClass().add("lobby-add-button");
            addBot.setMaxWidth(Double.MAX_VALUE);
            addBot.setOnAction(e -> {
                slots.add(MatchSetup.Slot.bot(BotKind.SMART, "봇")); // 번호는 renumberBots 가 매김
                renderTeams();
            });
            box.getChildren().add(addBot);
        }
    }

    private Node slotRow(List<MatchSetup.Slot> slots, int index) {
        MatchSetup.Slot slot = slots.get(index);
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("lobby-slot");
        // 모든 행이 팀 박스 폭을 꽉 채우게 해, 라벨 행과 드롭다운 행의 폭이 같아지도록 한다
        // (봇을 추가/제거해도 가로 레이아웃이 흔들리지 않음).
        row.setMaxWidth(Double.MAX_VALUE);

        if (slot.kind() == MatchSetup.SlotKind.HUMAN_LOCAL) {
            Label me = new Label("👑 " + slot.name() + " (나)");
            me.getStyleClass().add("lobby-slot-host");
            HBox.setHgrow(me, Priority.ALWAYS);
            me.setMaxWidth(Double.MAX_VALUE);
            row.getChildren().add(me);
            return row;
        }

        Label tag = new Label(slot.name());
        tag.getStyleClass().add("lobby-slot-tag");
        tag.setMinWidth(70);

        ComboBox<BotKind> strategy = new ComboBox<>();
        strategy.getStyleClass().add("lobby-combo");
        strategy.getItems().setAll(BotKind.values());
        strategy.setValue(slot.botKind() != null ? slot.botKind() : BotKind.SMART);
        strategy.setConverter(botKindConverter());
        strategy.setButtonCell(botKindCell());
        strategy.setCellFactory(list -> botKindCell());
        strategy.setOnAction(e -> slots.set(index, MatchSetup.Slot.bot(strategy.getValue(), slot.name())));
        HBox.setHgrow(strategy, Priority.ALWAYS);
        strategy.setMaxWidth(Double.MAX_VALUE);
        strategy.setPrefHeight(38);

        Button remove = new Button("제거");
        remove.getStyleClass().add("lobby-remove-button");
        // 활성 인원이 최소 1명은 남아야 한다.
        remove.setDisable(setup.activeCount(slots) <= 1);
        remove.setOnAction(e -> {
            slots.remove(index);
            renderTeams();
        });

        row.getChildren().addAll(tag, strategy, remove);
        return row;
    }

    @FXML
    private void onStart() {
        try {
            GameApp.get().showGameBoard(setup);
        } catch (IOException ignored) {
        }
    }

    @FXML
    private void onBack() {
        try {
            GameApp.get().showScene("main_menu.fxml");
        } catch (IOException ignored) {
        }
    }

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

    private static String localIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
