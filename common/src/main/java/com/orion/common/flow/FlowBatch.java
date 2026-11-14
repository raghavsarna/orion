package com.orion.common.flow;

import java.util.List;

/** Change one rule or a group of rules on a switch, applied all-or-nothing. */
public record FlowBatch(List<FlowRule> add, List<FlowRule> modify, List<String> delete) {

    public FlowBatch {
        add = add == null ? List.of() : List.copyOf(add);
        modify = modify == null ? List.of() : List.copyOf(modify);
        delete = delete == null ? List.of() : List.copyOf(delete);
    }

    public boolean isEmpty() {
        return add.isEmpty() && modify.isEmpty() && delete.isEmpty();
    }
}
