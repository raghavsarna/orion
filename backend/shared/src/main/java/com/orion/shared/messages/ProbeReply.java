package com.orion.shared.messages;

/** Controller's answer to a switch probe. known=false means "register again". */
public record ProbeReply(boolean known) {
}
