package client.cli;

import client.net.ConnectionManager;
import client.net.UdpListener;


import com.google.gson.JsonObject;
import shared.protocol.Request;
import shared.protocol.Response;
import shared.protocol.StatusCode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/******************** Command Loop ********************************

Qui vive il ciclo dei comandi lato client.
Leggo una riga da terminale, la trasformo in request,
la mando al server e renderizzo la risposta.

Scelte:
    - rete delegata a ConnectionManager, output delegato a CliRenderer
    - parsing flessibile per il comando "play" che accetta parole con spazi racchiuse tra virgolette
    - notifiche UDP gestite qui, cosi' su new_game posso anche richiedere automaticamente le info della partita
    - comandi update resi piu' espliciti lato CLI, senza cambiare il protocollo json verso il server

************************************************************************************/

public final class CommandLoop {

    private final ConnectionManager connection;
    private final UdpListener notificationReceiver;
    private final Scanner scanner;
    private final Object outputLock = CliRenderer.outputLock();
    private volatile boolean loggedIn = false;
    private volatile String currentUsername;

    public CommandLoop(ConnectionManager connection, UdpListener notificationReceiver) {
        this.connection = connection;
        this.notificationReceiver = notificationReceiver;
        this.scanner = new Scanner(System.in);
    }


    public void run() {
        
        CliRenderer.printWelcome();

        while (true) {
            synchronized (outputLock) {
                CliRenderer.prompt();
            }
            if (!scanner.hasNextLine()) break;

            String line = sanitizeInputLine(scanner.nextLine());
            if (line.isEmpty()) continue;

            String[] args = line.split("\\s+");
            String command = normalizeCommand(args[0]);

            try {
                synchronized (outputLock) {
                    switch (command) {
                        case "help":
                            CliRenderer.printHelp();
                            break;

                        case "register":
                            register(args);
                            break;

                        case "login":
                            login(args);
                            break;

                        case "logout":
                            logout();
                            break;

                        case "update":
                            update(args);
                            break;

                        case "play":
                        case "p":
                            play(line);
                            break;

                        case "info":
                        case "i":
                            info(args);
                            break;

                        case "stats":
                        case "s":
                            gameStats(args);
                            break;

                        case "me":
                            playerStats();
                            break;

                        case "top":
                            top(args);
                            break;

                        case "rank":
                            rank(args);
                            break;

                        case "quit":
                        case "exit":
                        case "q":
                            if (loggedIn) logout();
                            System.out.println("Bye!");
                            return;

                        default:
                            System.out.println(AnsiCodes.color(
                                "Unknown command. Type 'help' for available commands.", AnsiCodes.YELLOW));
                    }
                }
            } catch (IOException e) {
                synchronized (outputLock) {
                    System.out.println(AnsiCodes.color("Connection error: " + e.getMessage(), AnsiCodes.RED));
                    if (!connection.connected()) {
                        System.out.println("Disconnected from server. Exiting.");
                        return;
                    }
                }
            }
        }
    }

    private String sanitizeInputLine(String raw) {
        if (raw == null) return "";

        String tmp = raw
            .replace("\uFEFF", "")
            .replaceAll("\\p{Cntrl}", "")
            .trim();

        // If async output or copy/paste leaves the prompt in the input line
        // (example: "> rank"), remove it before parsing the command.
        while (tmp.startsWith(">")) {
            tmp = tmp.substring(1).trim();
        }

        return tmp;
    }

    private String normalizeCommand(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase();
    }

    private void printCurrentInfo(boolean includePrompt) {
        try {
            JsonObject req = Request.gameInfo(Request.CURRENT_GAME, 0);
            JsonObject tmp = connection.ask(req);
            synchronized (outputLock) {
                CliRenderer.printGameInfo(tmp);
                if (includePrompt) {
                    CliRenderer.prompt();
                }
            }
        } catch (IOException e) {
            synchronized (outputLock) {
                System.out.println(AnsiCodes.color(
                    "Type 'info' to check if the game is active. " + e.getMessage(),
                    AnsiCodes.YELLOW));
                if (includePrompt) {
                    CliRenderer.prompt();
                }
            }
        }
    }

    public void notification(JsonObject notification) {
        String type = Response.notice(notification);
        if (type == null) return;

        synchronized (outputLock) {
            CliRenderer.printNotification(notification, false);
        }

        if (loggedIn && Response.NOTICE_NEW_GAME.equals(type)) {
            printCurrentInfo(loggedIn);
        } else {
            synchronized (outputLock) {
                CliRenderer.prompt();
            }
        }
    }

    // --- Command handlers ---

    private void register(String[] args) throws IOException {
        if (loggedIn) {
            System.out.println("Already logged in. Logout first if you want to register another user.");
            return;
        }

        if (args.length < 3) {
            System.out.println("Usage: register <username> <password>");
            return;
        }

        JsonObject req = Request.register(args[1], args[2], 0);
        req.addProperty(Request.KEY_UDP_PORT, notificationReceiver.port());
        JsonObject tmp = connection.ask(req);
        CliRenderer.printRegisterResult(tmp);

        StatusCode status = Response.status(tmp);
        if (status == StatusCode.LOGGED_IN) {
            loggedIn = true;
            currentUsername = args[1];
        }
    }

    private void login(String[] args) throws IOException {
        if (loggedIn) {
            System.out.println("Already logged in. Logout first if you want to login as another user.");
            return;
        }

        if (args.length < 3) {
            System.out.println("Usage: login <username> <password>");
            return;
        }

        // Add UDP port for async notifications
        JsonObject req = Request.login(args[1], args[2], 0);
        req.addProperty(Request.KEY_UDP_PORT, notificationReceiver.port());

        JsonObject tmp = connection.ask(req);
        CliRenderer.printLoginResult(tmp);

        StatusCode status = Response.status(tmp);
        if (status == StatusCode.LOGGED_IN) {
            loggedIn = true;
            currentUsername = args[1];
        }
    }

    private void logout() throws IOException {
        if (!loggedIn) {
            System.out.println("Not logged in.");
            return;
        }
        JsonObject req = Request.logout(0);
        JsonObject tmp = connection.ask(req);
        CliRenderer.printLogoutResult(tmp);

        if (Response.status(tmp) == StatusCode.LOGGED_OUT) {
            loggedIn = false;
            currentUsername = null;
        }
    }

    private void update(String[] args) throws IOException {
        if (!requireLogin()) return;

        String newUser = null, newPass = null;

        if (args.length < 2) {
            printUpdateUsage();
            return;
        }

        String mode = args[1].toLowerCase();
        switch (mode) {
            case "user":
            case "username":
                if (args.length != 3) { printUpdateUsage(); return; }
                newUser = args[2];
                break;

            case "pass":
            case "password":
                if (args.length != 3) { printUpdateUsage(); return; }
                newPass = args[2];
                break;

            case "both":
            case "credentials":
                if (args.length != 4) { printUpdateUsage(); return; }
                newUser = args[2];
                newPass = args[3];
                break;

            default:
                // Keep compatibility with the previous CLI:
                // update <newPassword> or update <newUsername> <newPassword>.
                if (args.length == 2) {
                    newPass = args[1];
                } else if (args.length == 3) {
                    newUser = args[1];
                    newPass = args[2];
                } else {
                    printUpdateUsage();
                    return;
                }
                break;
        }

        System.out.print("Current password: ");
        System.out.flush();
        String oldPass = scanner.nextLine().trim();

        JsonObject req = Request.update(currentUsername, oldPass, newUser, newPass, 0);
        JsonObject tmp = connection.ask(req);
        CliRenderer.printUpdateResult(tmp);

        if (Response.status(tmp) == StatusCode.CREDENTIALS_UPDATED && newUser != null) {
            currentUsername = newUser;
            System.out.println(AnsiCodes.color("Logged in as " + currentUsername + ".", AnsiCodes.GREEN));
        }
    }

    private void printUpdateUsage() {
        System.out.println("Usage:");
        System.out.println("  update user <newUsername>");
        System.out.println("  update pass <newPassword>");
        System.out.println("  update both <newUsername> <newPassword>");
    }

    private void play(String line) throws IOException {
        if (!requireLogin()) return;

        String[] words = parsePlayWords(line);
        if (words == null) {
            System.out.println("Usage: play <w1> <w2> <w3> <w4>");
            System.out.println("   or: play \"ice cube\" \"new york\" w3 w4");
            return;
        }

        JsonObject req = Request.proposal(words, 0);
        JsonObject tmp = connection.ask(req);
        CliRenderer.printProposalResult(tmp);
    }

    private String[] parsePlayWords(String line) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace < 0) {
            return null;
        }

        String args = line.substring(firstSpace + 1).trim();
        if (args.isEmpty()) {
            return null;
        }

        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }

            if (!inQuotes && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }

            current.append(c);
        }

        if (inQuotes) {
            // Unbalanced quotes.
            return null;
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        if (tokens.size() != 4) {
            return null;
        }

        return tokens.toArray(new String[0]);
    }

    private void info(String[] args) throws IOException {
        if (!requireLogin()) return;
        int gameId = parseGameArg(args, "info");
        if (gameId == Integer.MIN_VALUE) return;
        JsonObject req = Request.gameInfo(gameId, 0);
        JsonObject tmp = connection.ask(req);
        CliRenderer.printGameInfo(tmp);
    }

    private void gameStats(String[] args) throws IOException {
        if (!requireLogin()) return;
        int gameId = parseGameArg(args, "stats");
        if (gameId == Integer.MIN_VALUE) return;
        JsonObject req = Request.gameStats(gameId, 0);
        JsonObject tmp = connection.ask(req);
        CliRenderer.printGameStats(tmp);
    }

    private int parseGameArg(String[] args, String cmd) {
        if (args.length < 2) {
            return Request.CURRENT_GAME;
        }

        String v = args[1].trim().toLowerCase();
        if ("current".equals(v)) {
            return Request.CURRENT_GAME;
        }

        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            System.out.println("Usage: " + cmd + " [current|<gameId>]");
            return Integer.MIN_VALUE;
        }
    }

    private void playerStats() throws IOException {
        if (!requireLogin()) return;
        JsonObject req = Request.playerStats(0);
        JsonObject tmp = connection.ask(req);
        CliRenderer.printPlayerStats(tmp);
    }

    private void top(String[] args) throws IOException {
        if (!requireLogin()) return;
        JsonObject req;
        if (args.length >= 2) {
            try {
                int n = Integer.parseInt(args[1]);
                req = Request.leaderboard(n, 0);
            } catch (NumberFormatException e) {
                System.out.println("Usage: top [N]");
                return;
            }
        } else {
            req = Request.leaderboard(0); // Full leaderboard
        }
        JsonObject tmp = connection.ask(req);
        CliRenderer.printLeaderboard(tmp);
    }

    private void rank(String[] args) throws IOException {
        if (!requireLogin()) return;
        String player = args.length >= 2 ? args[1] : currentUsername;
        JsonObject req = Request.rank(player, 0);
        JsonObject tmp = connection.ask(req);
        CliRenderer.printLeaderboard(tmp);
    }

    private boolean requireLogin() {
        if (!loggedIn) {
            System.out.println(AnsiCodes.color("Please login first.", AnsiCodes.YELLOW));
            return false;
        }
        return true;
    }
}
