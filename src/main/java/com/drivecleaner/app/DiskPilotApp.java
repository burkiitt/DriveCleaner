package com.drivecleaner.app;

import com.drivecleaner.cleanup.CleanupService;
import com.drivecleaner.safety.ProtectionPolicy;
import com.drivecleaner.scan.ScanService;
import com.drivecleaner.ui.CleanupViewModel;
import com.drivecleaner.ui.MainController;
import com.drivecleaner.ui.ScanViewModel;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public final class DiskPilotApp extends Application {
    private ScanViewModel viewModel;
    private CleanupViewModel cleanupViewModel;

    @Override
    public void start(Stage stage) {
        ProtectionPolicy protectionPolicy = ProtectionPolicy.windowsDefaults();
        viewModel = new ScanViewModel(new ScanService(protectionPolicy));
        cleanupViewModel = new CleanupViewModel(new CleanupService(protectionPolicy));
        MainController controller = new MainController(viewModel, cleanupViewModel);
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
        if (cleanupViewModel != null) {
            cleanupViewModel.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
