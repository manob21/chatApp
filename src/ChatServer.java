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

    public static void removeClient(ClientHandler client) {
        clients.remove(client);
        System.out.println("Client disconnected. Total clients: " + clients.size());
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

            System.out.println(username + " joined the chat");
            ChatServer.broadcast(username + " joined the chat", this);

            String message;
            while ((message = in.readLine()) != null) {
                if (message.equalsIgnoreCase("/quit")) {
                    break;
                }
                System.out.println(username + ": " + message);
                ChatServer.broadcast(username + ": " + message, this);
            }

        } catch (IOException e) {
            System.err.println("Error handling client: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    public void sendMessage(String message) {
        if (out != null) {
            out.println(message);
        }
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