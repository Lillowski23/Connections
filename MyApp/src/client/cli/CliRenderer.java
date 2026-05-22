package client.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import shared.protocol.Response;
import shared.protocol.StatusCode;

import static client.cli.AnsiCodes.*;

/******************** CLI Renderer ********************************

Questa classe è la vera e propria view del client.
Prende dati json e li formatta con colori e formato comodo da leggere durante il gioco.

Difficolta':
    - metodi dedicati per ogni tipo di risposta
    - output coerente per successo, errore e notifiche

************************************************************************************/
public final class CliRenderer {

    private CliRenderer() {}

    public static Object outputLock() {
        return CliRenderer.class;
    }

    public static synchronized void prompt() {
        System.out.print(BOLD + "> " + RESET);
        System.out.flush();
    }

    
    public static synchronized void printWelcome() {
        System.out.println();
        System.out.println(BOLD + CYAN + "╔══════════════════════════════════════╗" + RESET);
        System.out.println(BOLD + CYAN + "║       C O N N E C T I O N S          ║" + RESET);
        System.out.println(BOLD + CYAN + "╚══════════════════════════════════════╝" + RESET);
        System.out.println();
        System.out.println(DIM + "Type 'help' for available commands" + RESET);
        System.out.println();
    }

    public static synchronized void printHelp() {
        System.out.println();
        System.out.println(BOLD + "Available commands:" + RESET);
        System.out.println("  " + GREEN + "register" + RESET + " <username> <password>   — Create account");
        System.out.println("  " + GREEN + "login" + RESET + "    <username> <password>   — Login to server");
        System.out.println("  " + GREEN + "logout" + RESET + "                           — Disconnect");
        System.out.println("  " + GREEN + "update" + RESET + "   user <name>             — Change username");
        System.out.println("  " + GREEN + "update" + RESET + "   pass <password>         — Change password");
        System.out.println("  " + GREEN + "update" + RESET + "   both <name> <password>  — Change both");
        System.out.println("  " + GREEN + "play" + RESET + "   <w1> \"<w2>\" <w3> <w4>   — Submit 4 words");
        System.out.println("  " + GREEN + "info" + RESET + "     [current|<gameId>]      — Game info");
        System.out.println("  " + GREEN + "stats" + RESET + "    [current|<gameId>]      — Game statistics");
        System.out.println("  " + GREEN + "me" + RESET + "                               — Your stats");
        System.out.println("  " + GREEN + "top" + RESET + "      [N|0]                   — Leaderboard (top N, 0 = all)");
        System.out.println("  " + GREEN + "rank" + RESET + "     [player]                — Player ranking");
        System.out.println("  " + GREEN + "help" + RESET + "                             — Show this help");
        System.out.println("  " + GREEN + "quit" + RESET + "                             — Exit");
        System.out.println();
    }

    // --- Auth ---
    public static synchronized void printRegisterResult(JsonObject response) {
        StatusCode status = Response.status(response);
        if (status == StatusCode.LOGGED_IN) {
            String username = response.has(Response.KEY_USERNAME)
                ? response.get(Response.KEY_USERNAME).getAsString() : "?";
            System.out.println(color("✅ Registered and logged in as " + username + "!", GREEN));

            if (response.has(Response.KEY_GAME_ID)) {
                int gameId = response.get(Response.KEY_GAME_ID).getAsInt();
                long delta = response.get(Response.KEY_TIME_REMAINING).getAsLong();
                System.out.println(color("  Game #" + gameId + " in progress — "
                    + formatTime(delta) + " remaining", YELLOW));
            }
        } else if (status == StatusCode.REGISTERED) {
            System.out.println(color("✅ Registered successfully! You can now login.", GREEN));
        } else {
            printError(response);
        }
    }

    public static synchronized void printLoginResult(JsonObject response) {
        StatusCode status = Response.status(response);
        if (status == StatusCode.LOGGED_IN) {
            String username = response.has(Response.KEY_USERNAME)
                ? response.get(Response.KEY_USERNAME).getAsString() : "you";
            System.out.println(color("✅ Welcome back, " + username + "!", GREEN));

            // Se c'è una partita in corso
            if (response.has(Response.KEY_GAME_ID)) {
                int gameId = response.get(Response.KEY_GAME_ID).getAsInt();
                long delta = response.get(Response.KEY_TIME_REMAINING).getAsLong();
                System.out.println(color("  Game #" + gameId + " in progress — "
                    + formatTime(delta) + " remaining", YELLOW));

                // Print words when present
                if (response.has(Response.KEY_WORDS)) {
                    printWordGrid(response.getAsJsonArray(Response.KEY_WORDS));
                } else if (response.has(Response.KEY_REMAINING_WORDS)) {
                    System.out.println(DIM + "  Resuming your game..." + RESET);
                    printWordGrid(response.getAsJsonArray(Response.KEY_REMAINING_WORDS));
                }
            }
        } else {
            printError(response);
        }
    }

    public static synchronized void printLogoutResult(JsonObject response) {
        StatusCode status = Response.status(response);
        if (status == StatusCode.LOGGED_OUT) {
            System.out.println(color("✅ Logged out.", GREEN));
        } else {
            printError(response);
        }
    }

    public static synchronized void printUpdateResult(JsonObject response) {
        StatusCode status = Response.status(response);
        if (status == StatusCode.CREDENTIALS_UPDATED) {
            System.out.println(color("✅ Credentials updated.", GREEN));
        } else {
            printError(response);
        }
    }

    // --- Game ---

    public static synchronized void printProposalResult(JsonObject response) {
        StatusCode status = Response.status(response);
        if (status == null) { printError(response); return; }

        switch (status) {
            case PROPOSAL_CORRECT:
                String theme = response.has(Response.KEY_THEME)
                    ? response.get(Response.KEY_THEME).getAsString() : "?";
                int gIdx = response.has(Response.KEY_GROUP_INDEX)
                    ? response.get(Response.KEY_GROUP_INDEX).getAsInt() : -1;
                System.out.println(groupColor(gIdx, "✓ CORRECT! Theme: " + theme));
                printScoreLine(response);
                break;

            case GAME_WON:
                System.out.println(color("🏆 YOU WON! All groups found! 🏆", BOLD + GREEN));
                printScoreLine(response);
                break;

            case PROPOSAL_WRONG:
                System.out.println(color("❌ Wrong guess.", RED));
                printScoreLine(response);
                break;

            case GAME_LOST:
                System.out.println(color("❌ GAME OVER ", BOLD + RED));
                printScoreLine(response);
                break;

            default:
                printError(response);
        }
    }

    public static synchronized void printGameInfo(JsonObject response) {
        StatusCode status = Response.status(response);
        if (status != StatusCode.OK) { printError(response); return; }

        boolean isFinalView = response.has(Response.KEY_GROUPS);
        boolean isLiveView = response.has(Response.KEY_REMAINING_WORDS);

        System.out.println();
        String title = "Game #" + response.get(Response.KEY_GAME_ID).getAsInt();
        if (isFinalView) {
            title += "  [FINAL RESULTS]";
        } else if (isLiveView) {
            title += "  [RUNNING]";
        }
        System.out.println(BOLD + title + RESET);
        System.out.println("  Time: " + formatTime(response.get(Response.KEY_TIME_REMAINING).getAsLong()));

        if (response.has(Response.KEY_CORRECT_GROUPS)) {
            System.out.println("  Groups found: " + response.get(Response.KEY_CORRECT_GROUPS).getAsInt() + "/4");
        }
        if (response.has(Response.KEY_ERRORS)) {
            int errors = response.get(Response.KEY_ERRORS).getAsInt();
            System.out.println("  Errors: " + color(errors + "/4", errors >= 3 ? RED : YELLOW));
        }
        if (response.has(Response.KEY_SCORE)) {
            System.out.println("  Score: " + response.get(Response.KEY_SCORE).getAsInt());
        }

        // Complete groups (final view for a finished/expired game)
        if (response.has(Response.KEY_GROUPS)) {
            System.out.println();
            JsonArray groups = response.getAsJsonArray(Response.KEY_GROUPS);
            for (int i = 0; i < groups.size(); i++) {
                JsonObject g = groups.get(i).getAsJsonObject();
                String theme = g.get(Response.KEY_THEME).getAsString();
                boolean found = g.get(Response.KEY_FOUND).getAsBoolean();
                String mark = found ? color("✅", GREEN) : color("❌", RED);
                System.out.print("  " + mark + " " + groupColor(i, theme) + ": ");
                JsonArray words = g.getAsJsonArray(Response.KEY_WORDS);
                for (int j = 0; j < words.size(); j++) {
                    if (j > 0) System.out.print(", ");
                    System.out.print(words.get(j).getAsString());
                }
                System.out.println();
            }
        }

        // Parole ancora giocabili (vista live)
        if (response.has(Response.KEY_REMAINING_WORDS)) {
            System.out.println();
            printWordGrid(response.getAsJsonArray(Response.KEY_REMAINING_WORDS));
        }
        System.out.println();
    }

    public static synchronized void printGameStats(JsonObject response) {
        StatusCode status = Response.status(response);
        if (status != StatusCode.OK) { printError(response); return; }

        System.out.println();
        System.out.println(BOLD + "Game #" + response.get(Response.KEY_GAME_ID).getAsInt()
                           + " Statistics" + RESET);
        System.out.println("  Time remaining: "
                           + formatTime(response.get(Response.KEY_TIME_REMAINING).getAsLong()));
        System.out.println("  Total players:  " + response.get(Response.KEY_TOTAL_PLAYERS).getAsInt());
        System.out.println("  Still playing:  " + response.get(Response.KEY_PLAYERS_ACTIVE).getAsInt());
        System.out.println("  Finished:       " + response.get(Response.KEY_PLAYERS_FINISHED).getAsInt()
                           + " (won/lost/timeout)");
        System.out.println("  Winners:        "
                           + color(String.valueOf(response.get(Response.KEY_PLAYERS_WON).getAsInt()), GREEN)
                           + " (subset of finished)");
        if (response.has(Response.KEY_AVERAGE_SCORE)) {
            double avg = response.get(Response.KEY_AVERAGE_SCORE).getAsDouble();
            System.out.println("  Average score:  " + String.format("%.2f", avg));
        }
        System.out.println();
    }

    // --- Leaderboard ---

    public static synchronized void printLeaderboard(JsonObject response) {
        StatusCode status = Response.status(response);
        if (status != StatusCode.OK) { printError(response); return; }

        // Singolo giocatore 
        if (response.has(Response.KEY_RANK) && !response.has(Response.KEY_LEADERBOARD)) {
            String user = response.get(Response.KEY_USERNAME).getAsString();
            int rank = response.get(Response.KEY_RANK).getAsInt();
            int score = response.get(Response.KEY_SCORE).getAsInt();
            System.out.println("  " + BOLD + "#" + rank + RESET + "  "
                               + user + "  " + DIM + score + " pts" + RESET);
            return;
        }

        // Classifica 
        if (!response.has(Response.KEY_LEADERBOARD)) return;
        JsonArray lb = response.getAsJsonArray(Response.KEY_LEADERBOARD);

        if (lb.size() == 0) {
            System.out.println();
            System.out.println(BOLD + " ═══ LEADERBOARD ═══" + RESET);
            System.out.println(DIM + "  No players in leaderboard yet." + RESET);
            System.out.println();
            return;
        }

        System.out.println();
        System.out.println(BOLD + " ═══ LEADERBOARD ═══" + RESET);
        System.out.println(DIM + "  #    Player           Score" + RESET);
        System.out.println(DIM + "  ─────────────────────────────" + RESET);

        for (int i = 0; i < lb.size(); i++) {
            JsonObject entry = lb.get(i).getAsJsonObject();
            int rank = entry.get(Response.KEY_RANK).getAsInt();
            String name = entry.get(Response.KEY_USERNAME).getAsString();
            int score = entry.get(Response.KEY_SCORE).getAsInt();

            String medal = rank <= 3 ? new String[]{"🥇", "🥈", "🥉"}[rank - 1] : "  ";
            String rankStr = String.format("%3d", rank);
            String nameStr = String.format("%-16s", name);
            System.out.println("  " + medal + rankStr + "  " + nameStr + "  " + score);
        }
        System.out.println();
    }

    // ---Player stats ---

    public static synchronized void printPlayerStats(JsonObject response) {
        StatusCode status = Response.status(response);
        if (status != StatusCode.OK) { printError(response); return; }

        String username = response.get(Response.KEY_USERNAME).getAsString();
        System.out.println();
        System.out.println(BOLD + username + " that's your stats!"  + RESET);
        System.out.println("  Puzzles played:   " + response.get(Response.KEY_PUZZLES_COMPLETED).getAsInt());
        System.out.println("  Win rate:         "
                           + String.format("%.1f%%", response.get(Response.KEY_WIN_RATE).getAsDouble()));
        System.out.println("  Loss rate:        "
                           + String.format("%.1f%%", response.get(Response.KEY_LOSS_RATE).getAsDouble()));
        System.out.println("  Current streak:   "
                           + color(String.valueOf(response.get(Response.KEY_CURRENT_STREAK).getAsInt()), YELLOW));
        System.out.println("  Max streak:       " + response.get(Response.KEY_MAX_STREAK).getAsInt());
        System.out.println("  Perfect puzzles:  "
                           + color(String.valueOf(response.get(Response.KEY_PERFECT_PUZZLES).getAsInt()), GREEN));
        System.out.println("  Total score:      " + response.get(Response.KEY_SCORE).getAsInt());

        // Istogramma errori
        if (response.has(Response.KEY_MISTAKE_HISTOGRAM)) {
            JsonArray hist = response.getAsJsonArray(Response.KEY_MISTAKE_HISTOGRAM);
            System.out.println();
            System.out.println(BOLD + "Mistake distribution:" + RESET);
            String[] labels = {"0 errors", "1 error ", "2 errors", "3 errors",
                               "4 (won) ", "4 (lost)", "timeout "};
            int maxVal = 0;
            for (int i = 0; i < hist.size(); i++) {
                maxVal = Math.max(maxVal, hist.get(i).getAsInt());
            }
            for (int i = 0; i < Math.min(hist.size(), labels.length); i++) {
                int val = hist.get(i).getAsInt();
                int barLen = maxVal > 0 ? (val * 20) / maxVal : 0;
                String bar = "█".repeat(barLen);
                System.out.printf("    %s  %s%s %d%s%n",
                    labels[i], i < 4 ? GREEN : (i < 6 ? RED : YELLOW), bar, val, RESET);
            }
        }
        System.out.println();
    }

    // --- Notifications ---

    public static synchronized void printNotification(JsonObject notification) {
        printNotification(notification, true);
    }

    public static synchronized void printNotification(JsonObject notification, boolean showPrompt) {
        String type = Response.notice(notification);
        if (type == null) return;

        System.out.println();
        System.out.println(DIM + "──────────────── notification ────────────────" + RESET);
        switch (type) {
            case Response.NOTICE_NEW_GAME:
                int gameId = notification.has(Response.KEY_GAME_ID)
                    ? notification.get(Response.KEY_GAME_ID).getAsInt() : -1;
                System.out.println(color("🔔 New game #" + gameId + " started!", BOLD + CYAN));
                System.out.println(DIM + "   Loading game info automatically..." + RESET);
                break;
            case Response.NOTICE_GAME_ENDED:
                System.out.println(color("⏰ Time's up! Game has ended.", BOLD + YELLOW));
                System.out.println(DIM + "   Type 'info' to see the results" + RESET);
                break;
            case Response.NOTICE_ALL_GAMES_COMPLETED:
                System.out.println(color("🏁 All puzzles have been played!", BOLD + GREEN));
                break;
            default:
                String msg = notification.has(Response.KEY_MESSAGE)
                    ? notification.get(Response.KEY_MESSAGE).getAsString() : type;
                System.out.println(color("🔔 " + msg, CYAN));
        }
        if (showPrompt) {
            prompt();
        }
    }

    // --- Utility ---

    public static synchronized void printError(JsonObject response) {
        String message = response.has(Response.KEY_MESSAGE)
            ? response.get(Response.KEY_MESSAGE).getAsString() : "Unknown error";
        int code = response.has(Response.KEY_STATUS)
            ? response.get(Response.KEY_STATUS).getAsInt() : 0;
        System.out.println(color("✗ Error " + code + ": " + message, RED));
    }

    private static void printScoreLine(JsonObject response) {
        StringBuilder sb = new StringBuilder("  ");
        if (response.has(Response.KEY_CORRECT_GROUPS)) {
            sb.append("Groups: ").append(response.get(Response.KEY_CORRECT_GROUPS).getAsInt()).append("/4");
        }
        if (response.has(Response.KEY_ERRORS)) {
            sb.append("  Errors: ").append(response.get(Response.KEY_ERRORS).getAsInt()).append("/4");
        }
        if (response.has(Response.KEY_SCORE)) {
            sb.append("  Score: ").append(response.get(Response.KEY_SCORE).getAsInt());
        }
        System.out.println(DIM + sb + RESET);
    }




private static void printWordGrid(JsonArray words) {
    if (words == null || words.size() == 0) {
        System.out.println(DIM + "(no words)" + RESET);
        return;
    }

    int n = words.size();
    int rows = (n == 1) ? 1 : 2; 
    int cols = (int) Math.ceil(n / (double) rows);

    int minW = 10;
    int pad = 2; // spazio interno totale

    int maxLen = 0;
    for (int i = 0; i < n; i++) {
        String s = words.get(i).isJsonNull() ? "" : words.get(i).getAsString();
        if (s.length() > maxLen) maxLen = s.length();
    }

    int w = Math.max(minW, maxLen + pad);
    String h = "─".repeat(w);

    StringBuilder top = new StringBuilder("┌");
    StringBuilder mid = new StringBuilder("├");
    StringBuilder bot = new StringBuilder("└");

    for (int c = 0; c < cols; c++) {
        top.append(h).append(c == cols - 1 ? "┐" : "┬");
        mid.append(h).append(c == cols - 1 ? "┤" : "┼");
        bot.append(h).append(c == cols - 1 ? "┘" : "┴");
    }

    System.out.println(top);

    for (int r = 0; r < rows; r++) {
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < cols; c++) {
            int idx = r * cols + c;
            String s = (idx < n && !words.get(idx).isJsonNull()) ? words.get(idx).getAsString() : "";
            line.append("│ ");
            line.append(String.format("%-" + (w - 1) + "s", s));
        }
        line.append("│");
        System.out.println(line);

        if (r < rows - 1) System.out.println(mid);
    }

    System.out.println(bot);
}




    private static String formatTime(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        return String.format("%d:%02d", m, s);
    }
}
