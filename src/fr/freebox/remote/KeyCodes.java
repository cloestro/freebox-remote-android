package fr.freebox.remote;

/** Codes de touches Android TV utilisés par l'interface. */
final class KeyCodes {

    static final int POWER = 26;
    static final int OK = 23;
    static final int UP = 19;
    static final int DOWN = 20;
    static final int LEFT = 21;
    static final int RIGHT = 22;
    static final int BACK = 4;
    static final int HOME = 3;
    static final int MENU = 82;
    static final int INFO = 165;
    static final int GUIDE = 172;
    static final int SETTINGS = 176;
    static final int SEARCH = 84;
    static final int DEL = 67;
    static final int ASSIST = 219;
    static final int TV = 170;
    static final int VOLUME_UP = 24;
    static final int VOLUME_DOWN = 25;
    static final int MUTE = 91;
    static final int CHANNEL_UP = 166;
    static final int CHANNEL_DOWN = 167;
    static final int PLAY_PAUSE = 85;
    static final int STOP = 86;
    static final int REWIND = 89;
    static final int FORWARD = 90;
    static final int RECORD = 130;
    static final int RED = 183;
    static final int GREEN = 184;
    static final int YELLOW = 185;
    static final int BLUE = 186;

    private KeyCodes() {
    }

    static int numeric(int digit) {
        return 144 + digit;
    }
}
