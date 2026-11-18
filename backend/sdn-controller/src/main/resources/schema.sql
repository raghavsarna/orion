-- Orion schema. Topology and flow rules are NOT stored: they are rebuilt from the switches and the intents.

-- Every intent the user gave. status: PENDING, ACTIVE, NOT_POSSIBLE, EXPIRED
CREATE TABLE IF NOT EXISTS intent (
    id          UUID PRIMARY KEY,
    type        TEXT        NOT NULL,
    body        JSONB       NOT NULL,
    status      TEXT        NOT NULL,
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Named groups of IPs/subnets used by intents ("group:guests")
CREATE TABLE IF NOT EXISTS ip_group (
    name     TEXT PRIMARY KEY,
    members  TEXT[] NOT NULL
);

-- Who the NIB sends change notifications to ("local:<name>" or an http URL)
CREATE TABLE IF NOT EXISTS nib_subscriber (
    name     TEXT PRIMARY KEY,
    address  TEXT NOT NULL
);

-- Switches added through the API, so the controller can reach them again after a restart
CREATE TABLE IF NOT EXISTS manual_switch (
    switch_id  TEXT PRIMARY KEY,
    address    TEXT NOT NULL
);
