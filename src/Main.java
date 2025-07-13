import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) {
        try {
            // Load FXML file
            FXMLLoader loader = new FXMLLoader(getClass().getResource("ChatClient.fxml"));
            Parent root = loader.load();

            // Get controller and set stage
            ChatClientController controller = loader.getController();
            controller.setPrimaryStage(primaryStage);

            // Create and show scene
            Scene scene = new Scene(root);
            primaryStage.setTitle("Chat Client");
            primaryStage.setScene(scene);
            primaryStage.show();

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error loading FXML: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}