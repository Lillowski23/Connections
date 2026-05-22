package server.game;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/******************** Game State ********************************

Descrive lo stato di una partita:
Contiene le 16 parole e i 4 gruppi con i loro temi.
Viene creato dal GameDataProvider leggendo il JSON ed immutabile.

NON contiene lo stato dei giocatori (PlayerGameState), ci sono 
solo i dati necessari al corretto svolgimento di una partita.

************************************************************************************/


public final class GameState {

    private final int gameId;
    private final Group[] groups;
    private final List<String> shuffledWords;
    private final long startTime;
    private final long durationMillis;
    private final Set<String> allWords;

    public record Group(String theme, Set<String> words) {
        //@record automatically generates constructor, getters, equals, hashCode, toString
        public boolean matches(Set<String> p) {
            return words.equals(p);
        }
    }

    
    public GameState(int gameId, Group[] groups, long durationMillis) {
        if (groups.length != 4) {
            throw new IllegalArgumentException("Error: exactly 4 groups are required");
        }
        this.gameId = gameId;
        this.groups = groups;
        this.durationMillis = durationMillis;
        this.startTime = System.currentTimeMillis();
        this.allWords = new HashSet<>();
        
        java.util.List<String> wordList = new java.util.ArrayList<>();
        for (Group g : groups) {
            allWords.addAll(g.words());
            wordList.addAll(g.words());
        }
        Collections.shuffle(wordList);
        this.shuffledWords = Collections.unmodifiableList(wordList);
    }

    
    public int check(Set<String> words) {
        for (int i = 0; i < groups.length; i++) {
            if (groups[i].matches(words)) {
                return i;
            }
        }
        return -1; // nessun gruppo corrisponde
    }

    public int gameId() { return gameId; }
    public Group[] groups() { return groups; }
    public List<String> shuffle() { return shuffledWords; }
    public long start() { return startTime; }
    public long duration() { return durationMillis; }
    
    public boolean areAllWordsValid(Set<String> words) {
        return allWords.containsAll(words);
    }


    public long leftMs() {
        long delta = System.currentTimeMillis() - startTime;
        long res = durationMillis - delta;
        return Math.max(0, res);
    }

    
    public int left() {
        return (int) (leftMs() / 1000);
    }

    
    public boolean expired() {
        return leftMs() <= 0;
    }

   
    public boolean running() {
        return !expired();
    }

    
    public Group group(int index) {
        return groups[index];
    }

    @Override
    public String toString() {
        return String.format("GameState{id=%d, remaining=%ds, words=%d}",
            gameId, left(), allWords.size());
    }
}
