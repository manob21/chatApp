import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ChatServer {
    private static final int PORT = 12345;
    private static Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private static ExecutorService pool = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        System.out.println("Chat Server starting on port " + PORT);

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                ClientHandler clientHandler = new ClientHandler(clientSocket);
                clients.add(clientHandler);
                pool.execute(clientHandler);
                System.out.println("Client connected. Total clients: " + clients.size());
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }

    public static void broadcast(String message, ClientHandler sender) {
        for (ClientHandler client : clients) {
            if (client != sender) {
                client.sendMessage(message);
            }
        }
    }

    public static void sendPrivateMessage(String senderUsername, String recipientUsername, String message) {
        ClientHandler recipient = findClientByUsername(recipientUsername);
        if (recipient != null) {
            recipient.sendMessage("/private " + senderUsername + ": " + message);
            System.out.println("Private message from " + senderUsername + " to " + recipientUsername + ": " + message);
        }
    }

    public static void sendUserList(ClientHandler requester) {
        StringBuilder userList = new StringBuilder("/userlist ");
        for (ClientHandler client : clients) {
            if (client.getUsername() != null) {
                userList.append(client.getUsername()).append(",");
            }
        }

        // Remove trailing comma if exists
        if (userList.length() > 10) {
            userList.setLength(userList.length() - 1);
        }

        requester.sendMessage(userList.toString());
    }

    public static void broadcastUserList() {
        StringBuilder userList = new StringBuilder("/userlist ");
        for (ClientHandler client : clients) {
            if (client.getUsername() != null) {
                userList.append(client.getUsername()).append(",");
            }
        }

        // Remove trailing comma if exists
        if (userList.length() > 10) {
            userList.setLength(userList.length() - 1);
        }

        String userListMessage = userList.toString();
        for (ClientHandler client : clients) {
            client.sendMessage(userListMessage);
        }
    }

    public static ClientHandler findClientByUsername(String username) {
        for (ClientHandler client : clients) {
            if (username.equals(client.getUsername())) {
                return client;
            }
        }
        return null;
    }

    public static void removeClient(ClientHandler client) {
        clients.remove(client);
        System.out.println("Client disconnected. Total clients: " + clients.size());

        // Broadcast updated user list to all remaining clients
        broadcastUserList();
    }
}

class ClientHandler implements Runnable {
    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private String username;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try {
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Get username
            out.println("Enter your username:");
            username = in.readLine();

            if (username == null || username.trim().isEmpty()) {
                username = "Anonymous";
            }

            // Check if username is already taken
            if (ChatServer.findClientByUsername(username) != null) {
                username = username + "_" + System.currentTimeMillis() % 1000;
            }

            System.out.println(username + " joined the chat");
            ChatServer.broadcast(username + " joined the chat", this);

            // Send user list to new client and broadcast updated user list
            ChatServer.sendUserList(this);
            ChatServer.broadcastUserList();

            String message;
            while ((message = in.readLine()) != null) {
                if (message.equalsIgnoreCase("/quit")) {
                    break;
                } else if (message.equalsIgnoreCase("/users")) {
                    // Send user list to requesting client
                    ChatServer.sendUserList(this);
                } else if (message.startsWith("/msg ")) {
                    // Handle private message: /msg username message
                    handlePrivateMessage(message);
                } else if (message.startsWith("/private ")) {
                    // Handle private chat request: /private username
                    String targetUser = message.substring(9).trim();
                    if (ChatServer.findClientByUsername(targetUser) != null) {
                        sendMessage("Starting private chat with " + targetUser);
                    } else {
                        sendMessage("User " + targetUser + " not found");
                    }
                } else {
                    // Public message
                    System.out.println(username + ": " + message);
                    ChatServer.broadcast(username + ": " + message, this);
                }
            }

        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    private void handlePrivateMessage(String message) {
        try {
            // Parse: /msg username message content
            String[] parts = message.split(" ", 3);
            if (parts.length >= 3) {
                String recipientUsername = parts[1];
                String messageContent = parts[2];

                // Send private message
                ChatServer.sendPrivateMessage(username, recipientUsername, messageContent);
            } else {
                sendMessage("Invalid private message format. Use: /msg username message");
            }
        } catch (Exception e) {
            sendMessage("Error sending private message: " + e.getMessage());
        }
    }

    public void sendMessage(String message) {
        if (out != null) {
            out.println(message);
        }
    }

    public String getUsername() {
        return username;
    }

    private void cleanup() {
        try {
            if (username != null) {
                System.out.println(username + " left the chat");
                ChatServer.broadcast(username + " left the chat", this);
            }

            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();

        } catch (IOException e) {
            System.err.println("Error closing connection: " + e.getMessage());
        }

        ChatServer.removeClient(this);
    }
}