package dev.minegoon.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlainTest {

    @Test
    void readsTheViewFromBehind() {
        assertEquals(order("chase", 0, ""), Plain.read("!chase", true));
        assertEquals(order("chase", 0, ""), Plain.read("watch in third person", false));
        assertEquals(order("chase", 0, ""), Plain.read("!watch 3rd person", true));
        assertEquals(order("chase", 0, ""), Plain.read("watch me from behind", false));
        assertEquals(order("watch", 0, ""), Plain.read("!watch", true));
        assertEquals(order("free", 0, ""), Plain.read("!free", true));
        assertEquals(Optional.empty(), Plain.read("!watch the cave and tell me what you see", false));
    }

    @Test
    void readsABangLine() {
        assertEquals(order("get", 5, "diamond"), Plain.read("!get 5 diamond", true));
        assertEquals(order("get", 5, "diamond"), Plain.read("!get diamond 5", true));
        assertEquals(order("get", 1, "oak log"), Plain.read("!get oak log", true));
        assertEquals(order("get", 5, "diamonds"), Plain.read("!get me 5 diamonds", false));
        assertEquals(order("help", 0, ""), Plain.read("!help", false));
    }

    @Test
    void readsAPlainGet() {
        assertEquals(order("get", 5, "diamonds"), Plain.read("get me 5 diamonds", false));
        assertEquals(order("get", 3, "iron ingots"), Plain.read("get me a few iron ingots", false));
        assertEquals(order("get", 8, "oak logs"), Plain.read("hey can you grab some oak logs", false));
        assertEquals(order("get", 64, "torches"), Plain.read("please fetch a stack of torches", false));
        assertEquals(order("get", 2, "cows"), Plain.read("bring me a couple of cows", false));
        assertEquals(order("get", 1, "bucket"), Plain.read("fetch a bucket", false));
    }

    @Test
    void onlyTheBrakesAreControls() {
        assertEquals(true, Plain.isControl("stop"));
        assertEquals(true, Plain.isControl("!come here"));
        assertEquals(true, Plain.isControl("pause"));
        assertEquals(false, Plain.isControl("get me 5 diamonds"));
        assertEquals(false, Plain.isControl("protect 20 blocks"));
        assertEquals(false, Plain.isControl("stop mining and get me some wool"));
        assertEquals(false, Plain.isControl("Builder, come here"));
    }

    @Test
    void readsTheOtherCommands() {
        assertEquals(order("come", 0, ""), Plain.read("come here", false));
        assertEquals(order("come", 0, ""), Plain.read("can you come to me", false));
        assertEquals(order("give", 0, ""), Plain.read("give it to me", false));
        assertEquals(order("status", 0, ""), Plain.read("status?", false));
        assertEquals(order("inventory", 0, ""), Plain.read("inv", false));
        assertEquals(order("protect", 20, ""), Plain.read("protect 20 blocks", false));
        assertEquals(order("protect", 0, ""), Plain.read("protect this area", false));
        assertEquals(order("allow", 10, ""), Plain.read("allow 10", false));
        assertEquals(order("watch", 0, ""), Plain.read("watch", false));
        assertEquals(order("back", 0, ""), Plain.read("back", false));
        assertEquals(order("help", 0, ""), Plain.read("help", false));
    }

    @Test
    void leavesOrdinaryChatAndSentencesAlone() {
        assertEquals(Optional.empty(), Plain.read("come look at this", false));
        assertEquals(Optional.empty(), Plain.read("bring it back", false));
        assertEquals(Optional.empty(), Plain.read("I'm back", false));
        assertEquals(Optional.empty(), Plain.read("protect the castle", false));
        assertEquals(Optional.empty(), Plain.read("hello there", false));
        assertEquals(Optional.empty(), Plain.read("help me find diamonds", false));
        assertEquals(Optional.empty(), Plain.read("!what are you carrying", false));
        assertEquals(Optional.empty(), Plain.read("!I need a bucket and 16 torches", false));
        assertEquals(Optional.empty(), Plain.read("!get 4 logs and 4 cobblestone, then come back", false));
        assertEquals(Optional.empty(), Plain.read("Builder, get me 5 diamonds", false));
        assertEquals(Optional.empty(), Plain.read("!come look at this", false));
    }

    @Test
    void strictKeepsACommandThatASentenceWouldLeave() {
        assertEquals(order("come", 0, ""), Plain.read("!come look at this", true));
        assertEquals(order("get", 4, "logs and 4 cobblestone then come back"),
            Plain.read("!get 4 logs and 4 cobblestone, then come back", true));
    }

    private static Optional<Plain.Order> order(String verb, int number, String item) {
        return Optional.of(new Plain.Order(verb, number, item));
    }
}
