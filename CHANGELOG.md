# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - Unreleased

### Added

- Tables `cluster_node`, `cluster_lease`, `cluster_setting` (standard QQQ tables, `cluster.read` /
  `cluster.write`), node status possible values, and the Cluster app with the Nodes widget
  (nodes grouped by role, status, heartbeat age).
- Liquibase changelogs in the jar: `db/liquibase/qbit-cluster/cluster.changelog.sql` (schema) and
  `cluster-defaults.changelog.sql` (insert-only seeded settings).
- Runtime: node registration and heartbeat, drain requests flowing to `QNodeLifecycle`, DRAINING /
  STOPPED tracking, lost-node sweep with the `cluster.node.lost` alert, purge of old rows, all on
  the database clock; `ClusterRuntimeService` for `QApplicationLauncher`.
- `ClusterLeases`: compare-and-set leases (`tryAcquire`, `renew`, `release`, `isLeader`).
- Record actions Drain node and Cancel drain (standard ETL Review / Result, `cluster.drain`).
- Health check `cluster.node.heartbeat`; help bundle `help/qbit-cluster.yaml`.
- Built against QQQ `4.1.0-SNAPSHOT` (feature/cluster-ha-p1); the release switches
  `qqq.snapshot.version` to the RC that ships the cluster APIs.
