// src/main/java/com/pastlands/cosmeticslite/client/CosmeticsClientState.java
package com.pastlands.cosmeticslite.client;

public class CosmeticsClientState {
    private static boolean unlocked = false;

    /**
     * Returns whether cosmetics are unlocked for this client.
     */
    public static boolean isUnlocked() {
        return unlocked;
    }

    /**
     * Sets the unlocked flag from a server sync packet.
     */
    public static void setUnlocked(boolean value) {
        unlocked = value;
    }

    /**
     * Resets the state to locked (for logout or fallback).
     */
    public static void reset() {
        unlocked = false;
    }
}
