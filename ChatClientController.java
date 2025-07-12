import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.*;
import java.net.*;
import java.net.URL;
import java.util.ResourceBundle;
import java.util.HashMap;
import java.util.Map;

public class ChatClientController implements Initializable {

    @FXML private TextArea chatArea;
    @FXML private TextField messageField;
    @FXML private TextField usernameField;
    @FXML private Button connectButton;
    @FXML private Button sendButton;
    @FXML private Label serverLabel;
    @FXML private ListView<String> userListView;
    @FXML private Label chatTitleLabel;
    @FXML private Button backToPublicButton;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private boolean connected = false;
    private String username;
    private Stage primaryStage;
    private ObservableList<String> userList;
    private Map<String, StringBuilder> privateChatHistories;
    private String currentChatUser = null; // null means public chat
    private StringBuilder publicChatHistory;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Debug: Check if FXML components are properly injected
        System.out.println("Initializing controller...");
        if (userListView == null) {
            System.err.println("ERROR: userListView is null!");
            return;
        }
        if (chatArea == null) {
            System.err.println("ERROR: chatArea is null!");
            return;
        }

        // Initialize collections
        userList = FXCollections.observableArrayList();
        privateChatHistories = new HashMap<>();
        publicChatHistory = new StringBuilder();

        // Set up user list
        userListView.setItems(userList);
        userListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) { // Double click
                String selectedUser = userListView.getSelectionModel().getSelectedItem();
                if (selectedUser != null && !selectedUser.equals(username)) {
                    openPrivateChat(selectedUser);
                }
            }
        });

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

        System.out.println("Controller initialized successfully!");
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

    @FXML
    private void handleBackToPublic() {
        switchToPublicChat();
    }

    private void openPrivateChat(String user) {
        currentChatUser = user;
        chatTitleLabel.setText("Private Chat with " + user);
        backToPublicButton.setVisible(true);

        // Create chat history for this user if it doesn't exist
        if (!privateChatHistories.containsKey(user)) {
            privateChatHistories.put(user, new StringBuilder());
        }

        // Display private chat history
        chatArea.setText(privateChatHistories.get(user).toString());
        chatArea.setScrollTop(Double.MAX_VALUE);

        // Request chat history from server
        out.println("/private " + user);
    }

    private void switchToPublicChat() {
        currentChatUser = null;
        chatTitleLabel.setText("Public Chat");
        backToPublicButton.setVisible(false);

        // Display public chat history
        chatArea.setText(publicChatHistory.toString());
        chatArea.setScrollTop(Double.MAX_VALUE);
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
                String welcomeMsg = "Connected to server as " + username + "\n";
                welcomeMsg += "Type /quit to disconnect\n";
                welcomeMsg += "Double-click on a user to start private chat\n\n";

                publicChatHistory.append(welcomeMsg);
                chatArea.appendText(welcomeMsg);
                messageField.requestFocus();
            });

            // Request user list from server
            out.println("/users");

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
                String disconnectMsg = "Disconnected from server\n";
                publicChatHistory.append(disconnectMsg);
                chatArea.appendText(disconnectMsg);

                // Clear user list
                userList.clear();

                // Reset to public chat
                switchToPublicChat();
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

        // Check if it's a private message
        if (currentChatUser != null) {
            out.println("/msg " + currentChatUser + " " + message);
            String chatMessage = "You to " + currentChatUser + ": " + message + "\n";

            Platform.runLater(() -> {
                privateChatHistories.get(currentChatUser).append(chatMessage);
                chatArea.appendText(chatMessage);
                messageField.clear();
            });
        } else {
            // Public message
            out.println(message);
            String chatMessage = "You: " + message + "\n";

            Platform.runLater(() -> {
                publicChatHistory.append(chatMessage);
                chatArea.appendText(chatMessage);
                messageField.clear();
            });
        }
    }

    private void listenForMessages() {
        try {
            String message;
            while ((message = in.readLine()) != null) {
                final String msg = message;
                Platform.runLater(() -> {
                    processIncomingMessage(msg);
                });
            }
        } catch (IOException e) {
            if (connected) {
                Platform.runLater(() -> {
                    String errorMsg = "Connection lost: " + e.getMessage() + "\n";
                    publicChatHistory.append(errorMsg);
                    chatArea.appendText(errorMsg);
                });
                connected = false;
                Platform.runLater(this::updateUIState);
            }
        }
    }

    private void processIncomingMessage(String message) {
        // Handle different types of messages from server
        if (message.startsWith("/userlist ")) {
            // Update user list
            String[] users = message.substring(10).split(",");
            userList.clear();
            for (String user : users) {
                if (!user.trim().isEmpty()) {
                    userList.add(user.trim());
                }
            }
        } else if (message.startsWith("/private ")) {
            // Private message received
            String[] parts = message.substring(9).split(": ", 2);
            if (parts.length == 2) {
                String sender = parts[0];
                String content = parts[1];

                // Create chat history if it doesn't exist
                if (!privateChatHistories.containsKey(sender)) {
                    privateChatHistories.put(sender, new StringBuilder());
                }

                String chatMessage = sender + ": " + content + "\n";
                privateChatHistories.get(sender).append(chatMessage);

                // If we're currently in this private chat, display the message
                if (sender.equals(currentChatUser)) {
                    chatArea.appendText(chatMessage);
                    chatArea.setScrollTop(Double.MAX_VALUE);
                }
            }
        } else {
            // Public message
            String chatMessage = message + "\n";
            publicChatHistory.append(chatMessage);

            // Only display if we're in public chat
            if (currentChatUser == null) {
                chatArea.appendText(chatMessage);
                chatArea.setScrollTop(Double.MAX_VALUE);
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