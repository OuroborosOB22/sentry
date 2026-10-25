package org.upgradeguard.sentry;

import java.io.PrintStream;

/** The same big letters as the web page, shown only in a real terminal. */
final class Banner {
    private static final String WORDMARK = """
███████╗███████╗███╗   ██╗████████╗██████╗ ██╗   ██╗
██╔════╝██╔════╝████╗  ██║╚══██╔══╝██╔══██╗╚██╗ ██╔╝
███████╗█████╗  ██╔██╗ ██║   ██║   ██████╔╝ ╚████╔╝
╚════██║██╔══╝  ██║╚██╗██║   ██║   ██╔══██╗  ╚██╔╝
███████║███████╗██║ ╚████║   ██║   ██║  ██║   ██║
╚══════╝╚══════╝╚═╝  ╚═══╝   ╚═╝   ╚═╝  ╚═╝   ╚═╝
""";
    private static final String MINT = "38;5;121";
    private static final String GREEN = "38;5;41";
    private static final String RED = "38;5;203";
    private static final String YELLOW = "38;5;221";

    private Banner() {
    }

    static boolean interactive() {
        return System.console() != null;
    }

    static boolean colour() {
        return interactive() && System.getenv("NO_COLOR") == null && !"dumb".equals(System.getenv("TERM"));
    }

    static void print(PrintStream out) {
        if (!interactive()) {
            return;
        }
        out.println();
        out.println(paint(WORDMARK.stripTrailing(), MINT, colour()));
        out.println();
    }

    static String decision(String decision) {
        return paint(decision, decision.startsWith("ACCEPT") ? GREEN : decision.startsWith("REJECT") ? RED : YELLOW, colour());
    }

    static String paint(String text, String code, boolean on) {
        if (!on) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            out.append("\u001b[1;").append(code).append('m').append(line).append("\u001b[0m\n");
        }
        return out.substring(0, out.length() - 1);
    }
}
