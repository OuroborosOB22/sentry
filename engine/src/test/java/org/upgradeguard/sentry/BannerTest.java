package org.upgradeguard.sentry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.Test;

class BannerTest {
    @Test
    void withoutATerminalNothingIsPrintedAndNoColourCodesAreAdded() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Banner.print(new PrintStream(bytes));
        assertEquals(0, bytes.size());
        assertEquals("ACCEPT", Banner.decision("ACCEPT"));
    }

    @Test
    void paintingWrapsEveryLineOnItsOwn() {
        String painted = Banner.paint("ab\ncd", "38;5;41", true);
        assertEquals(2, painted.split("\n").length);
        assertTrue(painted.startsWith("\u001b[1;38;5;41mab\u001b[0m"));
    }

    @Test
    void theSweepKeepsEveryLetterAndOnlyAddsColourCodes() {
        String swept = Banner.sweep("SE TRY", true);
        assertEquals("SE TRY", swept.replaceAll("\u001b\\[[0-9;]*m", ""));
        assertEquals("SE TRY", Banner.sweep("SE TRY", false));
    }
}
