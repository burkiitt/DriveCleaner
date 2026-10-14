package com.drivecleaner.app;

import com.drivecleaner.scan.ScanService;
import com.drivecleaner.ui.MainController;
import com.drivecleaner.ui.ScanViewModel;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class DiskPilotApp extends Application {
    private ScanViewModel viewModel;

    @Override
    public void start(Stage stage) {
        viewModel = new ScanViewModel(new ScanService());
        MainController controller = new MainController(viewModel);
        stage.setTitle("DiskPilot");
        stage.setMinWidth(900);
        stage.setMinHeight(650);
        stage.setScene(new Scene(controller.view(), 1_100, 750));
        stage.show();
    }

    @Override
    public void stop() {
        if (viewModel != null) {
            viewModel.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
