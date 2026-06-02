package com.oop.payday.controller;

import java.io.IOException;

import com.oop.payday.app.GameApp;
import com.oop.payday.game.GameConfig;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;

/**
 * 메인 메뉴 컨트롤러. 게임 모드(PvP / PvBot)와 연습 룰 여부를 선택해 게임을 시작한다.
 */
public class MainMenuController {

    @FXML
    private CheckBox practiceRuleCheck;

    @FXML
    private Label statusLabel;

    @FXML
    private void onPlayerVsPlayer() {
        startGame(false);
    }

    @FXML
    private void onPlayerVsBot() {
        startGame(true);
    }

    private void startGame(boolean vsBot) {
        boolean practice = practiceRuleCheck.isSelected();
        GameConfig config = practice ? GameConfig.practice(vsBot) : GameConfig.standard(vsBot);
        try {
            GameApp.get().showGameBoard(config, vsBot);
        } catch (IOException e) {
            statusLabel.setText("게임 화면을 여는 데 실패했습니다: " + e.getMessage());
        }
    }
}
