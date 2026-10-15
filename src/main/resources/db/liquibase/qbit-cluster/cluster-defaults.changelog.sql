-- liquibase formatted sql
-- Copyright 2026 Kingsrook, LLC. Licensed under the Apache License, Version 2.0.
-- https://www.apache.org/licenses/LICENSE-2.0

-- qbit-cluster starter defaults: the four cluster settings, so the Settings table shows what
-- the runtime uses from the first deploy. Include it AFTER cluster.changelog.sql:
--
--   - include: { file: db/liquibase/qbit-cluster/cluster-defaults.changelog.sql }
--
-- Rules for this file:
--   * insert-only: a row is inserted only when its key is absent, so an administrator's edits
--     always survive and nothing is ever updated;
--   * every rollback is empty: a key-based delete could remove a row the host already had;
--   * every changeset has a precondition on its table, so a host that has not created the
--     table yet skips it (MARK_RAN) instead of failing its deploy;
--   * portable SQL (PostgreSQL and H2): INSERT ... SELECT ... WHERE NOT EXISTS.
-- The values equal ClusterSettings.DEFAULTS, which the runtime also uses when a row is missing,
-- so deploying this file never changes behavior. ClusterChangelogTest runs it twice.

-- changeset qbit-cluster:defaults-1
-- preconditions onFail:MARK_RAN onError:MARK_RAN
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM cluster_setting WHERE setting_key = 'heartbeat_seconds'
-- comment: How often each node records that it is alive.
INSERT INTO cluster_setting (setting_key, setting_value, description, create_date, modify_date)
SELECT 'heartbeat_seconds', '15', 'How often, in seconds, each node records that it is alive.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM cluster_setting WHERE setting_key = 'heartbeat_seconds');
-- rollback empty

-- changeset qbit-cluster:defaults-2
-- preconditions onFail:MARK_RAN onError:MARK_RAN
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM cluster_setting WHERE setting_key = 'lost_after_seconds'
-- comment: When a silent node is marked Lost.
INSERT INTO cluster_setting (setting_key, setting_value, description, create_date, modify_date)
SELECT 'lost_after_seconds', '60', 'A node with no heartbeat for this many seconds is marked Lost.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM cluster_setting WHERE setting_key = 'lost_after_seconds');
-- rollback empty

-- changeset qbit-cluster:defaults-3
-- preconditions onFail:MARK_RAN onError:MARK_RAN
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM cluster_setting WHERE setting_key = 'retain_stopped_days'
-- comment: How long rows of stopped and lost nodes are kept.
INSERT INTO cluster_setting (setting_key, setting_value, description, create_date, modify_date)
SELECT 'retain_stopped_days', '7', 'Days to keep the rows of stopped and lost nodes before they are deleted.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM cluster_setting WHERE setting_key = 'retain_stopped_days');
-- rollback empty

-- changeset qbit-cluster:defaults-4
-- preconditions onFail:MARK_RAN onError:MARK_RAN
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM cluster_setting WHERE setting_key = 'alert_on_lost'
-- comment: Whether a lost node raises the cluster.node.lost alert.
INSERT INTO cluster_setting (setting_key, setting_value, description, create_date, modify_date)
SELECT 'alert_on_lost', 'true', 'Whether a lost node raises an alert (true or false).', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM cluster_setting WHERE setting_key = 'alert_on_lost');
-- rollback empty
