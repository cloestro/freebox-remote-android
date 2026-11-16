package fr.freebox.remote;

import java.util.Arrays;
import java.util.List;

/** Applications proposées dans l'onglet « Apps ». */
final class Apps {

    static final class App {
        final String name;
        final String link;

        App(String name, String link) {
            this.name = name;
            this.link = link;
        }
    }

    static final List<App> ALL = Arrays.asList(
            new App("YouTube", "https://www.youtube.com/tv"),
            new App("Netflix", "https://www.netflix.com/tv2"),
            new App("Prime Video", "https://app.primevideo.com/"),
            new App("Molotov", "https://www.molotov.tv/"),
            new App("france.tv", "https://www.france.tv/"),
            new App("Twitch", "https://www.twitch.tv/"));

    private Apps() {
    }
}
