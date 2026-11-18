package com.orion.controller;

import com.orion.controller.communication.southbound.SwitchClient;
import com.orion.controller.flowrules.FlowPusher;
import com.orion.controller.flowrules.PacketInHandler;
import com.orion.controller.flowrules.RuleGenerator;
import com.orion.controller.intent.IntentManager;
import com.orion.controller.storage.Database;
import com.orion.controller.storage.GroupRepository;
import com.orion.controller.storage.IntentRepository;
import com.orion.controller.storage.ManualSwitchRepository;
import com.orion.controller.storage.SubscriberRepository;
import com.orion.controller.topology.Nib;
import com.orion.controller.topology.NibSubscriber;
import com.orion.controller.topology.TopologyManager;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and wires every controller component (plain Java, no framework).
 * Each component is its own class with a clear boundary, so the monolith mirrors a microservice design.
 *
 * <p>Startup / self-healing order:
 * <ol>
 *   <li>Topology Manager starts at once: switches can register and the NIB fills up even if the DB is down.</li>
 *   <li>In the background: wait for the DB, load subscribers, subscribe the Intent Manager to the NIB,
 *       read the intents and push rules, then contact manually added switches.</li>
 * </ol>
 */
public final class OrionCore {

    private static final Logger log = LoggerFactory.getLogger(OrionCore.class);
    public static final String INTENT_MANAGER_SUBSCRIBER = "intent-manager";

    private final ControllerSettings settings;
    private final ActivityLog activity = new ActivityLog();
    private final Database database;
    private final IntentRepository intentRepo;
    private final GroupRepository groupRepo;
    private final SubscriberRepository subscriberRepo;
    private final ManualSwitchRepository manualRepo;
    private final Nib nib;
    private final SwitchClient switchClient;
    private final TopologyManager topologyManager;
    private final FlowPusher flowPusher;
    private final IntentManager intentManager;
    private final PacketInHandler packetInHandler;
    private Thread startup;

    public OrionCore(ControllerSettings settings) {
        this.settings = settings;
        this.database = new Database(settings.dbUrl(), settings.dbUser(), settings.dbPassword());
        this.intentRepo = new IntentRepository(database);
        this.groupRepo = new GroupRepository(database);
        this.subscriberRepo = new SubscriberRepository(database);
        this.manualRepo = new ManualSwitchRepository(database);
        this.nib = new Nib(activity);
        this.switchClient = new SwitchClient(settings.authToken());
        this.topologyManager = new TopologyManager(nib, switchClient, manualRepo, settings, activity);
        this.flowPusher = new FlowPusher(switchClient, activity);
        this.intentManager = new IntentManager(intentRepo, groupRepo, nib, new RuleGenerator(), flowPusher, settings,
                activity);
        this.packetInHandler = new PacketInHandler(nib, intentManager, switchClient, activity, settings.zone());
    }

    public void start() {
        topologyManager.start();
        startup = Thread.ofPlatform().name("orion-startup").daemon(true).start(() -> {
            database.waitUntilReady();
            subscriberRepo.saveIfMissing(INTENT_MANAGER_SUBSCRIBER, "local:" + INTENT_MANAGER_SUBSCRIBER);
            loadSubscribers();
            intentManager.start();
            topologyManager.contactManualSwitches();
            activity.add("CONTROLLER_READY", "intents loaded from database");
        });
    }

    /** Subscribes everyone listed in the nib_subscriber table. */
    public void loadSubscribers() {
        Map<String, String> rows = subscriberRepo.findAll();
        for (String name : nib.subscriberNames()) {
            if (!rows.containsKey(name)) {
                nib.unsubscribe(name);
            }
        }
        rows.forEach((name, address) -> {
            if (address.startsWith("local:")) {
                if (address.equals("local:" + INTENT_MANAGER_SUBSCRIBER)) {
                    nib.subscribe(NibSubscriber.local(name, intentManager::onNibEvent));
                } else {
                    log.warn("Unknown local subscriber {}", address);
                }
            } else if (address.startsWith("http")) {
                nib.subscribe(NibSubscriber.http(name, address));
            }
        });
        log.info("NIB subscribers: {}", nib.subscriberNames());
    }

    public void stop() {
        if (startup != null) {
            startup.interrupt();
        }
        topologyManager.stop();
        intentManager.stop();
        flowPusher.stop();
        packetInHandler.stop();
        database.close();
    }

    public ControllerSettings settings() {
        return settings;
    }

    public ActivityLog activity() {
        return activity;
    }

    public Database database() {
        return database;
    }

    public Nib nib() {
        return nib;
    }

    public TopologyManager topologyManager() {
        return topologyManager;
    }

    public IntentManager intentManager() {
        return intentManager;
    }

    public FlowPusher flowPusher() {
        return flowPusher;
    }

    public PacketInHandler packetInHandler() {
        return packetInHandler;
    }

    public GroupRepository groups() {
        return groupRepo;
    }

    public SubscriberRepository subscribers() {
        return subscriberRepo;
    }

    public ManualSwitchRepository manualSwitches() {
        return manualRepo;
    }

    public SwitchClient switchClient() {
        return switchClient;
    }
}
