package com.orion.common.southbound;

/** POST controller/notification: a link on one of this switch's ports was added or deleted. */
public record LinkNotification(String switchId, PortInfo addedLink, PortInfo deletedLink) {
}
