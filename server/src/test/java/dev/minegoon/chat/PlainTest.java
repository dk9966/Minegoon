package dev.minegoon.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlainTest {

    @Test
    void readsABangLine() {
        assertEquals(order("get", 5, "diamond"), Plain.read("!get 5 diamond", "Builder", true));
        assertEquals(order("get", 5, "diamond"), Plain.read("!get diamond 5", "Builder", true));
        assertEquals(order("get", 1, "oak log"), Plain.read("!get oak log", "Builder", true));
    }

    @Test
    void readsAPlainGet() {
        assertEquals(order("get", 5, "diamonds"), Plain.read("get me 5 diamonds", "Builder", false));
        assertEquals(order("get", 3, "iron ingots"), Plain.read("Builder, get me a few iron ingots", "Builder", false));
        assertEquals(order("get", 8, "oak logs"), Plain.read("hey can you grab some oak logs", "Builder", false));
        assertEquals(order("get", 64, "torches"), Plain.read("please fetch a stack of torches", "Builder", false));
        assertEquals(order("get", 2, "cows"), Plain.read("bring me a couple of cows", "Builder", false));
        assertEquals(order("get", 1, "bucket"), Plain.read("fetch a bucket", "Builder", false));
    }

    @Test
    void readsTheOtherCommands() {
        assertEquals(order("come", 0, ""), Plain.read("come here", "Builder", false));
        assertEquals(order("come", 0, ""), Plain.read("can you come to me", "Builder", false));
        assertEquals(order("give", 0, ""), Plain.read("give it to me", "Builder", false));
        assertEquals(order("status", 0, ""), Plain.read("status?", "Builder", false));
        assertEquals(order("inventory", 0, ""), Plain.read("inv", "Builder", false));
        assertEquals(order("protect", 20, ""), Plain.read("protect 20 blocks", "Builder", false));
        assertEquals(order("protect", 0, ""), Plain.read("protect this area", "Builder", false));
        assertEquals(order("allow", 10, ""), Plain.read("allow 10", "Builder", false));
        assertEquals(order("watch", 0, ""), Plain.read("watch", "Builder", false));
        assertEquals(order("back", 0, ""), Plain.read("back", "Builder", false));
        assertEquals(order("help", 0, ""), Plain.read("help", "Builder", false));
    }

    @Test
    void leavesOrdinaryChatAlone() {
        assertEquals(Optional.empty(), Plain.read("come look at this", "Builder", false));
        assertEquals(Optional.empty(), Plain.read("bring it back", "Builder", false));
        assertEquals(Optional.empty(), Plain.read("I'm back", "Builder", false));
        assertEquals(Optional.empty(), Plain.read("protect the castle", "Builder", false));
        assertEquals(Optional.empty(), Plain.read("hello there", "Builder", false));
        assertEquals(Optional.empty(), Plain.read("help me find diamonds", "Builder", false));
    }

    @Test
    void bangKeepsACommandThatPlainChatWouldLeave() {
        assertEquals(order("come", 0, ""), Plain.read("!come look at this", "Builder", true));
    }

    private static Optional<Plain.Order> order(String verb, int number, String item) {
        return Optional.of(new Plain.Order(verb, number, item));
    }
}
