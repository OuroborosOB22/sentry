package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WordsTest {
    @Test
    void shouts() {
        assertEquals("HI", Words.shout(" hi "));
    }
}
