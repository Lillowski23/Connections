package client.cli;

/******************** ANSI Codes ********************************

Classe utility con tutte le costanti ANSI usate dalla CLI.
Serve a evitare stringhe escape sparse in giro per il codice.

Scelte:
    - colori e stili centralizzati
    - helper piccoli per mantenere il renderer leggibile
    - palette dedicata ai gruppi di gioco

Risultato: output terminale più chiaro senza sporcare la logica applicativa.

************************************************************************************/
public final class AnsiCodes {

    private AnsiCodes() {}

    // Reset
    public static final String RESET = "\u001B[0m";

    // Colori testo
    public static final String BLACK   = "\u001B[30m";
    public static final String RED     = "\u001B[31m";
    public static final String GREEN   = "\u001B[32m";
    public static final String YELLOW  = "\u001B[33m";
    public static final String BLUE    = "\u001B[34m";
    public static final String MAGENTA = "\u001B[35m";
    public static final String CYAN    = "\u001B[36m";
    public static final String WHITE   = "\u001B[37m";

    // Colori sfondo
    public static final String BG_BLACK   = "\u001B[40m";
    public static final String BG_RED     = "\u001B[41m";
    public static final String BG_GREEN   = "\u001B[42m";
    public static final String BG_YELLOW  = "\u001B[43m";
    public static final String BG_BLUE    = "\u001B[44m";
    public static final String BG_MAGENTA = "\u001B[45m";
    public static final String BG_CYAN    = "\u001B[46m";
    public static final String BG_WHITE   = "\u001B[47m";

    // Stili
    public static final String BOLD      = "\u001B[1m";
    public static final String DIM       = "\u001B[2m";
    public static final String ITALIC    = "\u001B[3m";
    public static final String UNDERLINE = "\u001B[4m";

    // Utility: colora una stringa
    public static String color(String text, String color) {
        return color + text + RESET;
    }

    // Colori per i 4 gruppi del gioco (come nel NYT Connections)
    public static final String[] GROUP_COLORS = {
        BG_YELLOW + BLACK,   // Gruppo 0: giallo
        BG_GREEN + BLACK,    // Gruppo 1: verde
        BG_BLUE + WHITE,     // Gruppo 2: blu
        BG_MAGENTA + WHITE   // Gruppo 3: viola
    };

    public static String groupColor(int index, String text) {
        if (index < 0 || index >= GROUP_COLORS.length) return text;
        return GROUP_COLORS[index] + " " + text + " " + RESET;
    }
}
