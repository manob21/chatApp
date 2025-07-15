import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    @FXML private Label fileNameField;
    @FXML private Button selectFileButton;
    @FXML private Button fileSendButton;
    @FXML private ProgressBar progressBar;
    @FXML private Label progressLabel;
    @FXML private LineChart lineChart;

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
    private File selectedFile;
    private ExecutorService executorService;
    private XYChart.Series<Number, Number> series;
    private  int round;

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
        executorService = Executors.newCachedThreadPool();

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

        series = new XYChart.Series<>();
        series.setName("cwndview");
        lineChart.getData().add(series);

        // Set prompt text
        usernameField.setPromptText("Enter username");
        messageField.setPromptText("Type your message here...");

        System.out.println("Controller initialized successfully!");
    }

    public void addDataPoint(Number x, Number y) {
        series.getData().add(new XYChart.Data<>(x, y));
        //round++;

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

    @FXML
    private void handleFileSend(){
        sendFile();
    }

    @FXML
    private void handleFileSelection(){
        selectFile();
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

                // Check if it's a file transfer message
                if (content.startsWith("File ")) {
                    System.out.println(content);
                    String[] fileParts = content.split(" ");
                    if (fileParts.length >= 6) {
                        String senderIP = fileParts[2];
                        System.out.println(senderIP);
                        int port = Integer.parseInt(fileParts[3]);
                        String fileName = fileParts[4];
                        long fileSize = Long.parseLong(fileParts[5]);

                        String fileMessage = sender + " wants to send you a file: " + fileName + " (" + fileSize + " bytes)\n";

                        // Create chat history if it doesn't exist
                        if (!privateChatHistories.containsKey(sender)) {
                            privateChatHistories.put(sender, new StringBuilder());
                        }

                        privateChatHistories.get(sender).append(fileMessage);

                        // If we're currently in this private chat, display the message
                        if (sender.equals(currentChatUser)) {
                            chatArea.appendText(fileMessage);
                            chatArea.setScrollTop(Double.MAX_VALUE);
                        }

                        // Show confirmation dialog
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                            alert.setTitle("File Transfer");
                            alert.setHeaderText("Incoming File");
                            alert.setContentText(sender + " wants to send you a file: " + fileName + " (" + fileSize + " bytes)\n\nDo you want to accept this file?");

                            alert.showAndWait().ifPresent(response -> {
                                if (response == ButtonType.OK) {
                                    receiveFile(senderIP, port, fileName, fileSize);
                                }
                            });
                        });
                    }
                } else {
                    // Regular private message
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

    private void selectFile(){
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select a File");
        fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("All Files", "*.*"));

        Stage stage = (Stage) selectFileButton.getScene().getWindow();
        selectedFile = fileChooser.showOpenDialog(stage);
        String selectedFileName = selectedFile.getName();
        fileNameField.setText(selectedFileName);

    }

    private void sendFile() {
        if (!connected) {
            showAlert("Please connect to server first");
            return;
        }

        if (currentChatUser == null) {
            showAlert("Please select a user for private chat to send file");
            return;
        }

        if (selectedFile != null) {
            executorService.submit(() -> {
                try {
                    int port = 12348;
                    ServerSocket serverSocket = new ServerSocket(port);
                    String localIP = "127.0.0.1";

                    Platform.runLater(() -> {
                        String fileMessage = "Sending file: " + selectedFile.getName() + " to " + currentChatUser + "\n";
                        privateChatHistories.get(currentChatUser).append(fileMessage);
                        if (currentChatUser.equals(currentChatUser)) {
                            chatArea.appendText(fileMessage);
                        }
                    });

                    String fileTransferMessage = "File " + currentChatUser + " " + localIP + " " + port + " " + selectedFile.getName() + " " + selectedFile.length();
                    out.println("/msg " + currentChatUser + " " + fileTransferMessage);

                    Socket clientSocket = serverSocket.accept();

                    // Initialize TCP Reno congestion control parameters
                    TCPRenoSender tcpSender = new TCPRenoSender(clientSocket, selectedFile);
                    tcpSender.sendFileWithCongestionControl();

                    clientSocket.close();
                    serverSocket.close();

                } catch (IOException e) {
                    Platform.runLater(() -> {
                        String errorMessage = "Error sending file: " + e.getMessage() + "\n";
                        privateChatHistories.get(currentChatUser).append(errorMessage);
                        if (currentChatUser.equals(currentChatUser)) {
                            chatArea.appendText(errorMessage);
                        }
                    });
                }
            });
        }
    }

    // TCP Reno Sender Class
    class TCPRenoSender {
        private Socket socket;
        private File file;
        private DataOutputStream dos;
        private DataInputStream dis;

        // TCP Reno parameters
        private int cwnd = 1; // Congestion window (in segments)
        private int ssthresh = 32; // Slow start threshold
        private int segmentSize = 1024; // Segment size in bytes
        private int duplicateAcks = 0;
        private int lastAckedSeq = 0;
        private int nextSeqNum = 0;
        private boolean inSlowStart = true;
        private boolean inFastRecovery = false;

        // Timing parameters
        private long rtt = 100; // Initial RTT estimate (ms)
        private long rttvar = 50; // RTT variance
        private long rto = 200; // Retransmission timeout
        private final double alpha = 0.125; // RTT smoothing factor
        private final double beta = 0.25; // RTT variance smoothing factor

        // Flow control
        private int receiverWindow = 65535; // Receiver's advertised window
        private int effectiveWindow;


        // Buffers and queues
        private Map<Integer, byte[]> sentSegments = new ConcurrentHashMap<>();
        private Map<Integer, Long> segmentTimestamps = new ConcurrentHashMap<>();
        private Queue<Integer> retransmissionQueue = new ConcurrentLinkedQueue<>();

        public TCPRenoSender(Socket socket, File file) throws IOException {
            this.socket = socket;
            this.file = file;
            this.dos = new DataOutputStream(socket.getOutputStream());
            this.dis = new DataInputStream(socket.getInputStream());

            // Set socket options for better performance
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000); // 5 second timeout for reading segments
            // Don't set socket timeout for ACK reading - let it block
        }

        public void sendFileWithCongestionControl() throws IOException {
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buffer = new byte[segmentSize];
                int bytesRead;
                long totalSent = 0;
                long fileSize = file.length();

                // Start ACK receiver thread
                Thread ackReceiver = new Thread(this::receiveAcks);
                ackReceiver.setDaemon(true);
                ackReceiver.start();

                // Give ACK receiver time to start
                Thread.sleep(100);

                long startTime = System.currentTimeMillis();
                long timeout = 60000; // 60 second timeout (increased from 30)
                round = 1;
                while (totalSent < fileSize) {
                    // Check for overall timeout
                    if (System.currentTimeMillis() - startTime > timeout) {
                        System.out.println("Transfer timeout - breaking");
                        break;
                    }

                    // Calculate effective window (minimum of congestion window and receiver window)
                    effectiveWindow = Math.min(cwnd * segmentSize, receiverWindow);
                    int thispacket=1;
                    // Send segments within the window
                    boolean sentSomething = false;
                    while ((canSendSegment() && totalSent < fileSize) && thispacket <= cwnd) {
                        bytesRead = fis.read(buffer);
                        if (bytesRead == -1) break;

                        // Prepare segment
                        byte[] segment = new byte[bytesRead];
                        System.arraycopy(buffer, 0, segment, 0, bytesRead);

                        sendSegment(nextSeqNum, segment);
                        thispacket ++;
                        totalSent += bytesRead;
                        sentSomething = true;
                        // Update progress
                        double progress = (double) totalSent / fileSize;
                        Platform.runLater(() -> {
                            progressBar.setProgress(progress);
                            progressLabel.setText("Sending Progress " + String.format("%.1f", progress * 100) + "%");
                        });
                    }

                    if (sentSomething) {
                        Thread.sleep(5); // Short delay when actively sending
                    } else {
                        Thread.sleep(20); // Longer delay when waiting
                    }

                    Platform.runLater(() -> {
                        series.getData().add(new XYChart.Data<>(round++, cwnd));
                    });

                    if (sentSomething) {
                        Thread.sleep(5); // Short delay when actively sending
                    } else {
                        Thread.sleep(20); // Longer delay when waiting
                    }

                    System.out.println(cwnd);
                    // Handle retransmissions
                    handleRetransmissions();
                    if(inSlowStart){
                        cwnd*=2;
                        if(cwnd >= ssthresh){
                            inSlowStart = false;
                            inFastRecovery = true;
                        }
                    }else if(inFastRecovery){
                        cwnd++;
                    }
                    if(cwnd >= 41){
                        ssthresh = cwnd/2;
                        ssthresh = Math.max(2, ssthresh);
                        cwnd = ssthresh;
                    }



                    // Adaptive delay based on activity
                    if (sentSomething) {
                        Thread.sleep(5); // Short delay when actively sending
                    } else {
                        Thread.sleep(20); // Longer delay when waiting
                    }
                }

                // Wait for final ACKs
                Thread.sleep(rto);

                // Stop ACK receiver
                ackReceiver.interrupt();

                Platform.runLater(() -> {
                    String successMessage = "File sent successfully: " + file.getName() + "\n";
                    privateChatHistories.get(currentChatUser).append(successMessage);
                    if (currentChatUser.equals(currentChatUser)) {
                        chatArea.appendText(successMessage);
                    }
                });

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private boolean canSendSegment() {
            int segmentsInFlight = nextSeqNum - (lastAckedSeq + 1);
            return segmentsInFlight < cwnd;
        }

        private void sendSegment(int seqNum, byte[] data) throws IOException {
            // Create segment with sequence number and data
            dos.writeInt(seqNum);
            dos.writeInt(data.length);
            dos.write(data);
            dos.flush();

            //System.out.println("Sending segment: " + seqNum + ", cwnd: " + cwnd + ", lastAcked: " + lastAckedSeq);

            // Store segment for potential retransmission
            sentSegments.put(seqNum, data);
            segmentTimestamps.put(seqNum, System.currentTimeMillis());

            // Only increment nextSeqNum if this is a new segment (not a retransmission)
            if (seqNum == nextSeqNum) {
                nextSeqNum++;
            }
        }

        private void receiveAcks() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        int ackNum = dis.readInt();
                        int advertisedWindow = dis.readInt();

                        //System.out.println("Received ACK: " + ackNum + ", lastAcked: " + lastAckedSeq);

                        receiverWindow = advertisedWindow;
                        //System.out.println(cwnd);

                        if (ackNum > lastAckedSeq) {
                            // New ACK received
                            handleNewAck(ackNum);
                        } else if (ackNum == lastAckedSeq && lastAckedSeq >= 0) {
                            // Duplicate ACK (only count duplicates after first ACK)
                            handleDuplicateAck(ackNum);
                        }
                    } catch (IOException e) {
                        if (!Thread.currentThread().isInterrupted()) {
                            //System.out.println("ACK read error: " + e.getMessage());
                            Thread.sleep(10); // Brief pause before retrying
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.out.println("ACK receiver thread ended: " + e.getMessage());
            }
        }

        private void handleNewAck(int ackNum) {
            // Only process if this is a new ACK (ackNum > lastAckedSeq)
            if (ackNum <= lastAckedSeq) {
                return;
            }

            // Calculate RTT and update RTO for the acknowledged segment
            Long timestamp = segmentTimestamps.get(ackNum);
            if (timestamp != null) {
                long sampleRtt = System.currentTimeMillis() - timestamp;
                updateRtt(sampleRtt);
            }

            // Clean up ALL acknowledged segments up to ackNum
            for (int seq = lastAckedSeq + 1; seq <= ackNum; seq++) {
                sentSegments.remove(seq);
                segmentTimestamps.remove(seq);
            }

            // Update last acknowledged sequence number
            lastAckedSeq = ackNum;
            duplicateAcks = 0;

            //System.out.println("Cleaned up segments up to: " + ackNum + ", remaining: " + segmentTimestamps.size());


        }

        private void handleDuplicateAck(int ackNum) {
            duplicateAcks++;

            if (duplicateAcks == 3) {
                // Fast retransmit
                fastRetransmit(ackNum + 1);

                // Enter fast recovery
                ssthresh = Math.max(cwnd / 2, 2);
                cwnd = ssthresh + 3;
                inFastRecovery = true;
                inSlowStart = false;
            }
        }

        private void fastRetransmit(int seqNum) {
            byte[] segment = sentSegments.get(seqNum);
            if (segment != null) {
                try {
                    System.out.println("Fast retransmitting segment: " + seqNum);
                    dos.writeInt(seqNum);
                    dos.writeInt(segment.length);
                    dos.write(segment);
                    dos.flush();
                    ssthresh = ssthresh/2;
                    cwnd = ssthresh + 2;
                    inSlowStart = false;
                    inFastRecovery = true;
                    // Update timestamp for retransmitted segment
                    segmentTimestamps.put(seqNum, System.currentTimeMillis());
                } catch (IOException e) {
                    System.out.println("Error in fast retransmit: " + e.getMessage());
                }
            }
        }
        // Handle retransmission error



        private void handleRetransmissions() {
            long currentTime = System.currentTimeMillis();
            if(cwnd > 41){
                fastRetransmit(lastAckedSeq);
            }
            if(cwnd > (3*ssthresh)){
                handleTimeout(lastAckedSeq);
            }
            // Only check unacknowledged segments
            for (Map.Entry<Integer, Long> entry : new ArrayList<>(segmentTimestamps.entrySet())) {
                int seqNum = entry.getKey();
                long timestamp = entry.getValue();

                // Only check segments that are not yet acknowledged
                if (seqNum > lastAckedSeq && currentTime - timestamp > rto) {
                    System.out.println("Timeout for segment: " + seqNum + " (lastAcked: " + lastAckedSeq + ")");
                    handleTimeout(seqNum);
                    break; // Handle one timeout at a time
                }
            }
        }

        private void handleTimeout(int seqNum) {
            System.out.println("Timeout for segment: " + seqNum + " (lastAcked: " + lastAckedSeq + ")");

            // Only handle timeout if segment is not yet acknowledged
            if (seqNum <= lastAckedSeq) {
                System.out.println("Segment " + seqNum + " already acknowledged, ignoring timeout");
                segmentTimestamps.remove(seqNum);
                sentSegments.remove(seqNum);
                return;
            }

            // TCP Reno timeout handling
            ssthresh = Math.max(cwnd / 2, 2);
            cwnd = 1;
            inSlowStart = true;
            inFastRecovery = false;
            duplicateAcks = 0;

            // Retransmit the timed-out segment
            byte[] segment = sentSegments.get(seqNum);
            if (segment != null) {
                try {
                    System.out.println("Timeout retransmitting segment: " + seqNum);
                    dos.writeInt(seqNum);
                    dos.writeInt(segment.length);
                    dos.write(segment);
                    dos.flush();

                    // Update timestamp for retransmitted segment
                    segmentTimestamps.put(seqNum, System.currentTimeMillis());
                } catch (IOException e) {
                    System.out.println("Error in timeout retransmit: " + e.getMessage());
                }
            }

            // Double the RTO (exponential backoff)
            rto = Math.min(rto * 2, 10000); // Cap at 10 seconds
        }

        private void updateRtt(long sampleRtt) {
            if (rtt == 0) {
                rtt = sampleRtt;
                rttvar = sampleRtt / 2;
            } else {
                rttvar = (long) ((1 - beta) * rttvar + beta * Math.abs(sampleRtt - rtt));
                rtt = (long) ((1 - alpha) * rtt + alpha * sampleRtt);
            }

            rto = rtt + 4 * rttvar;
            rto = Math.max(rto, 200); // Minimum RTO of 200ms
            rto = Math.min(rto, 60000); // Maximum RTO of 60 seconds
        }
    }
    
    // Enhanced receiving method with flow control
    private void receiveFile(String senderIP, int port, String fileName, long fileSize) {
        executorService.submit(() -> {
            Platform.runLater(() -> {
                FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("Save File As");
                fileChooser.setInitialFileName(fileName);
                File saveFile = fileChooser.showSaveDialog(primaryStage);

                if (saveFile != null) {
                    executorService.submit(() -> {
                        try {
                            Socket socket = new Socket(senderIP, port);
                            TCPRenoReceiver receiver = new TCPRenoReceiver(socket, saveFile, fileSize);
                            receiver.receiveFileWithFlowControl();
                        } catch (IOException e) {
                            Platform.runLater(() -> {
                                String errorMessage = "Error receiving file: " + e.getMessage() + "\n";
                                chatArea.appendText(errorMessage);
                            });
                        }
                    });
                } else {
                    try{
                        Socket socket = new Socket(senderIP, port);
                        socket.close();
                    }catch (IOException e){
                        e.printStackTrace();
                    }
                }
            });
        });
    }

    // TCP Reno Receiver Class
    class TCPRenoReceiver {
        private Socket socket;
        private File saveFile;
        private long fileSize;
        private DataInputStream dis;
        private DataOutputStream dos;

        // Flow control parameters
        private int receiverWindow = 65535;
        private int bufferSize = 32768; // 32KB buffer
        private int expectedSeqNum = 0;
        private Map<Integer, byte[]> receivedSegments = new TreeMap<>();

        public TCPRenoReceiver(Socket socket, File saveFile, long fileSize) throws IOException {
            this.socket = socket;
            this.saveFile = saveFile;
            this.fileSize = fileSize;
            this.dis = new DataInputStream(socket.getInputStream());
            this.dos = new DataOutputStream(socket.getOutputStream());

            socket.setTcpNoDelay(true);
        }

        public void receiveFileWithFlowControl() throws IOException {
            try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                long totalBytesReceived = 0;

                while (totalBytesReceived < fileSize) {
                    // Read segment
                    int seqNum = dis.readInt();
                    int dataLength = dis.readInt();
                    byte[] data = new byte[dataLength];
                    dis.readFully(data);

                    if (seqNum == expectedSeqNum) {
                        // In-order segment
                        fos.write(data);
                        totalBytesReceived += dataLength;
                        expectedSeqNum++;

                        // Check for buffered segments
                        while (receivedSegments.containsKey(expectedSeqNum)) {
                            byte[] bufferedData = receivedSegments.remove(expectedSeqNum);
                            fos.write(bufferedData);
                            totalBytesReceived += bufferedData.length;
                            expectedSeqNum++;
                        }

                        // Send ACK for the last in-order segment received
                        sendAck(expectedSeqNum - 1);
                        //System.out.println("Sent ACK: " + (expectedSeqNum - 1) + ", window: " + receiverWindow);
                    } else if (seqNum > expectedSeqNum) {
                        // Out-of-order segment
                        receivedSegments.put(seqNum, data);
                        //System.out.println("Out-of-order segment: " + seqNum + ", expected: " + expectedSeqNum);

                        // Send duplicate ACK for last in-order segment
                        sendAck(expectedSeqNum - 1);
                        //System.out.println("Sent duplicate ACK: " + (expectedSeqNum - 1) + ", window: " + receiverWindow);
                    } else {
                        // Duplicate segment (already received)
                        //System.out.println("Duplicate segment received: " + seqNum);
                        sendAck(expectedSeqNum - 1);
                        //System.out.println("Sent ACK: " + (expectedSeqNum - 1) + ", window: " + receiverWindow);
                    }

                    // Update progress
                    double progress = (double) totalBytesReceived / fileSize;
                    Platform.runLater(() -> {
                        progressBar.setProgress(progress);
                        progressLabel.setText("Receiving Progress " + String.format("%.1f", progress * 100) + "%");
                    });

                    // Update receiver window based on available buffer space
                    updateReceiverWindow();
                }

                Platform.runLater(() -> {
                    String successMessage = "File received successfully: " + saveFile.getName() + "\n";
                    String sender = getCurrentSenderFromMessage();
                    if (sender != null) {
                        if (!privateChatHistories.containsKey(sender)) {
                            privateChatHistories.put(sender, new StringBuilder());
                        }
                        privateChatHistories.get(sender).append(successMessage);
                        if (sender.equals(currentChatUser)) {
                            chatArea.appendText(successMessage);
                        }
                    }
                });

            } finally {
                socket.close();
            }
        }

        private void sendAck(int ackNum) throws IOException {
            dos.writeInt(ackNum);
            dos.writeInt(receiverWindow);
            dos.flush();
        }

        private void updateReceiverWindow() {
            // Simple flow control: reduce window if buffer is getting full
            int bufferedSegments = receivedSegments.size();
            if (bufferedSegments > 50) {
                receiverWindow = Math.max(receiverWindow / 2, 1024);
            } else if (bufferedSegments < 10) {
                receiverWindow = Math.min(receiverWindow * 2, 65535);
            }
        }
    }

    private String getCurrentSenderFromMessage() {
        // This is a helper method to extract sender from the current message context
        // In a real implementation, you'd pass the sender as a parameter
        return "Unknown"; // Placeholder
    }


    

}