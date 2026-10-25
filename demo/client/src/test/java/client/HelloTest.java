package client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HelloTest {
    @Test
    void saysHello() {
        assertTrue(new Hello().say("Ada").contains("Ada"));
    }

    @Test
    void addsAGreetingWord() {
        assertTrue(new Hello().say("Ada").startsWith("Hello"));
    }
}
