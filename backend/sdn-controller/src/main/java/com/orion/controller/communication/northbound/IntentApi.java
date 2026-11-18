package com.orion.controller.communication.northbound;

import com.orion.controller.ControllerException;
import com.orion.controller.OrionCore;
import com.orion.controller.intent.Intent;
import com.orion.controller.intent.IntentSpec;
import com.orion.shared.net.Ip;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Northbound: intents (form / JSON) and IP groups. */
@RestController
@RequestMapping("/api")
public class IntentApi {

    public record GroupRequest(String name, List<String> members) {
    }

    private final OrionCore core;

    public IntentApi(OrionCore core) {
        this.core = core;
    }

    @GetMapping("/intents")
    public List<Intent> intents() {
        return core.intentManager().list();
    }

    /** Store an intent; rules are generated right after (status goes PENDING -> ACTIVE or NOT_POSSIBLE). */
    @PostMapping("/intents")
    @ResponseStatus(HttpStatus.CREATED)
    public Intent add(@RequestBody IntentSpec spec) {
        return core.intentManager().add(spec);
    }

    @DeleteMapping("/intents/{id}")
    public Map<String, String> delete(@PathVariable UUID id) {
        core.intentManager().delete(id);
        return Map.of("deleted", id.toString());
    }

    @GetMapping("/groups")
    public Map<String, List<String>> groups() {
        return core.groups().findAll();
    }

    /** Create or replace a group. */
    @PutMapping("/groups/{name}")
    public Map<String, List<String>> saveGroup(@PathVariable String name, @RequestBody GroupRequest r) {
        return save(name, r.members());
    }

    @PostMapping("/groups")
    public Map<String, List<String>> addGroup(@RequestBody GroupRequest r) {
        return save(r.name(), r.members());
    }

    private Map<String, List<String>> save(String name, List<String> members) {
        if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
            throw ControllerException.badRequest("group name: letters, digits, - and _ only");
        }
        if (members == null || members.isEmpty()) {
            throw ControllerException.badRequest("a group needs at least one IP or subnet");
        }
        List<String> clean = members.stream().map(String::trim).filter(m -> !m.isEmpty()).toList();
        for (String m : clean) {
            if (!Ip.isIpOrCidr(m)) {
                throw ControllerException.badRequest("not an IP or subnet: " + m);
            }
        }
        core.groups().save(name, clean);
        core.intentManager().requestRecompute(false);
        return core.groups().findAll();
    }

    @DeleteMapping("/groups/{name}")
    public Map<String, List<String>> deleteGroup(@PathVariable String name) {
        if (!core.groups().delete(name)) {
            throw ControllerException.notFound("no group " + name);
        }
        core.intentManager().requestRecompute(false);
        return core.groups().findAll();
    }
}
