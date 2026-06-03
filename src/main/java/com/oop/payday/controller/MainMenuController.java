package com.oop.payday.controller;

import java.io.IOException;

import com.oop.payday.app.GameApp;
import com.oop.payday.game.GameConfig;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;

/**
 * 메인 메뉴 컨트롤러. 봇 종류(플레이/테스트)와 연습 룰 여부를 선택해 게임을 시작한다.
 */
public class MainMenuController {

    @FXML
    private CheckBox practiceRuleCheck;

    @FXML
    private Label statusLabel;

    @FXML
    private void onPlayerVsPlayBot() {
        startGame(false);
    }

    @FXML
    private void onPlayerVsTestBot() {
        startGame(true);
    }

    private void startGame(boolean testBot) {
        boolean practice = practiceRuleCheck.isSelected();
        GameConfig config = practice ? GameConfig.practice(true) : GameConfig.standard(true);
        try {
            GameApp.get().showGameBoard(config, testBot);
        } catch (IOException e) {
            statusLabel.setText("게임 화면을 여는 데 실패했습니다: " + e.getMessage());
        }
    }
}
