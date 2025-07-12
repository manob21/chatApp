import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.io.*;
import java.net.*;
import java.net.URL;
import java.util.ResourceBundle;

public class ChatClientController implements Initializable {

    @FXML private TextArea chatArea;
    @FXML private TextField messageField;
    @FXML private TextField usernameField;
    @FXML private Button connectButton;
    @FXML private Button sendButton;
    @FXML private Label serverLabel;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean connected = false;
    private String username;
    private Stage primaryStage;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Set initial UI state
        updateUIState();

        // Set up event handlers
        messageField.setOnAction(e -> sendMessage());

        // Configure chat area
        chatArea.setEditable(false);
        chatArea.setWrapText(true);

        // Set server info
        serverLabel.setText("Server: localhost:12345");

        // Set prompt text
        usernameField.setPromptText("Enter username");
        messageField.setPromptText("Type your message here...");
    }

    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;

        // Handle window close
        primaryStage.setOnCloseRequest(e -> {
            disconnect();
            Platform.exit();
        });
    }

    @FXML
    private void handleConnect() {
        if (!connected) {
            connect();
        } else {
            disconnect();
        }
    }

    @FXML
    private void handleSend() {
        sendMessage();
    }

    private void connect() {
        username = usernameField.getText().trim();
        if (username.isEmpty()) {
            showAlert("Please enter a username");
            return;
        }

        try {
            socket = new Socket("localhost", 12345);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Start listening for messages
            Thread messageListener = new Thread(this::listenForMessages);
            messageListener.setDaemon(true);
            messageListener.start();

            // Send username to server
            String prompt = in.readLine(); // Read "Enter your username:" prompt
            out.println(username);

            connected = true;
            updateUIState();

            Platform.runLater(() -> {
                chatArea.appendText("Connected to server as " + username + "\n");
                chatArea.appendText("Type /quit to disconnect\n\n");
                messageField.requestFocus();
            });

        } catch (IOException e) {
            showAlert("Failed to connect to server: " + e.getMessage());
        }
    }

    private void disconnect() {
        if (connected) {
            try {
                if (out != null) {
                    out.println("/quit");
                }
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                System.err.println("Error disconnecting: " + e.getMessage());
            }

            connected = false;
            updateUIState();

            Platform.runLater(() -> {
                chatArea.appendText("Disconnected from server\n");
            });
        }
    }

    private void sendMessage() {
        if (!connected) return;

        String message = messageField.getText().trim();
        if (message.isEmpty()) return;

        if (message.equalsIgnoreCase("/quit")) {
            disconnect();
            return;
        }

        out.println(message);
        Platform.runLater(() -> {
            chatArea.appendText("You: " + message + "\n");
            messageField.clear();
        });
    }

    private void listenForMessages() {
        try {
            String message;
            while ((message = in.readLine()) != null) {
                final String msg = message;
                Platform.runLater(() -> {
                    chatArea.appendText(msg + "\n");
                    chatArea.setScrollTop(Double.MAX_VALUE);
                });
            }
        } catch (IOException e) {
            if (connected) {
                Platform.runLater(() -> {
                    chatArea.appendText("Connection lost: " + e.getMessage() + "\n");
                });
                connected = false;
                Platform.runLater(this::updateUIState);
            }
        }
    }

    private void updateUIState() {
        connectButton.setText(connected ? "Disconnect" : "Connect");
        usernameField.setDisable(connected);
        messageField.setDisable(!connected);
        sendButton.setDisable(!connected);
    }

    private void showAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}