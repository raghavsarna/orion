package com.orion.common.southbound;

/** Controller's answer to a switch probe. known=false means "register again". */
public record ProbeReply(boolean known) {
}
