package com.orion.switchnetwork.network;

import java.util.List;

/** The whole topology.yaml file: controller details, switches and links. */
public record TopologyFile(ControllerConfig controller, List<SwitchDef> switches, List<LinkDef> links) {

    public TopologyFile {
        switches = switches == null ? List.of() : List.copyOf(switches);
        links = links == null ? List.of() : List.copyOf(links);
    }

    /** Same topology with a different controller URL (used when CONTROLLER_URL is set). */
    public TopologyFile withControllerUrl(String url) {
        String token = controller == null ? null : controller.authToken();
        return new TopologyFile(new ControllerConfig(url, token), switches, links);
    }
}
