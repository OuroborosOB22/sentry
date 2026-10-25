package org.upgradeguard.sentry;

import java.io.PrintStream;

/** The same big letters and colours as the web page, shown only in a real terminal. */
final class Banner {
    private static final String WORDMARK = """
███████╗███████╗███╗   ██╗████████╗██████╗ ██╗   ██╗
██╔════╝██╔════╝████╗  ██║╚══██╔══╝██╔══██╗╚██╗ ██╔╝
███████╗█████╗  ██╔██╗ ██║   ██║   ██████╔╝ ╚████╔╝
╚════██║██╔══╝  ██║╚██╗██║   ██║   ██╔══██╗  ╚██╔╝
███████║███████╗██║ ╚████║   ██║   ██║  ██║   ██║
╚══════╝╚══════╝╚═╝  ╚═══╝   ╚═╝   ╚═╝  ╚═╝   ╚═╝
""";
    private static final int[] MINT = {125, 226, 176};
    private static final int[] LIGHT = {234, 255, 243};
    private static final String TAGLINE = "try the upgrade first";
    private static final String RESET = "\u001b[0m";

    private Banner() {
    }

    static boolean interactive() {
        return System.console() != null;
    }

    static boolean colour() {
        return interactive() && System.getenv("NO_COLOR") == null && !"dumb".equals(System.getenv("TERM"));
    }

    static boolean trueColour() {
        String support = System.getenv("COLORTERM");
        return support != null && (support.equalsIgnoreCase("truecolor") || support.equalsIgnoreCase("24bit"));
    }

    static void print(PrintStream out) {
        if (!interactive()) {
            return;
        }
        out.println();
        for (String line : WORDMARK.stripTrailing().split("\n")) {
            out.println(sweep(line, colour()));
        }
        out.println(dim(TAGLINE, colour()));
        out.println();
    }

    static String sweep(String line, boolean on) {
        if (!on) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        int width = Math.max(line.length() - 1, 1);
        for (int x = 0; x < line.length(); x++) {
            char c = line.charAt(x);
            if (c == ' ') {
                out.append(c);
                continue;
            }
            double glow = 1 - Math.abs(2.0 * x / width - 1);
            out.append(foreground(mix(glow * 0.85), glow < 0.5 ? 121 : 195)).append(c);
        }
        return out.append(RESET).toString();
    }

    private static int[] mix(double amount) {
        int[] rgb = new int[3];
        for (int i = 0; i < 3; i++) {
            rgb[i] = (int) Math.round(MINT[i] + (LIGHT[i] - MINT[i]) * amount);
        }
        return rgb;
    }

    private static String foreground(int[] rgb, int nearest) {
        return trueColour() ? "\u001b[38;2;" + rgb[0] + ";" + rgb[1] + ";" + rgb[2] + "m" : "\u001b[38;5;" + nearest + "m";
    }

    static String dim(String text, boolean on) {
        return on ? "\u001b[2m" + text + RESET : text;
    }

    static String accent(String text) {
        return colour() ? foreground(MINT, 121) + text + RESET : text;
    }

    static String decision(String decision) {
        if (!colour()) {
            return decision;
        }
        int[] rgb = decision.startsWith("ACCEPT") ? new int[] {125, 226, 176} : decision.startsWith("REJECT") ? new int[] {255, 138, 128} : new int[] {255, 210, 138};
        int nearest = decision.startsWith("ACCEPT") ? 41 : decision.startsWith("REJECT") ? 203 : 221;
        String background = trueColour() ? "48;2;" + rgb[0] + ";" + rgb[1] + ";" + rgb[2] : "48;5;" + nearest;
        return "\u001b[1;30;" + background + "m " + decision + " " + RESET;
    }

    static String paint(String text, String code, boolean on) {
        if (!on) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            out.append("\u001b[1;").append(code).append('m').append(line).append(RESET).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }
}
