package com.orion.shared.messages;

/** GET switch/link/{linkId}: is the link on this switch up? Used by the controller to check a dead neighbour. */
public record LinkStatus(String linkId, boolean up) {
}
