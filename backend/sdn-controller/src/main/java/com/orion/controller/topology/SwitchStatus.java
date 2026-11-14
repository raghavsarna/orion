package com.orion.controller.topology;

/** ALIVE: answers probes. DEAD: missed too many probes but a neighbour still sees its link up. */
public enum SwitchStatus {
    ALIVE,
    DEAD
}
