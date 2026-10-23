package com.nexusrmm.sync;

/** Identity of the sending machine, included once per batch envelope. */
public record SyncDevice(String computerId, String hostname) {
}
