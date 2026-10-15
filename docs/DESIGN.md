# QBit Cluster design

The approved design is section E of
[QRun-IO/qqq docs/specs/2026-10-15-cluster-ha-design.md](https://github.com/QRun-IO/qqq/blob/develop/docs/specs/2026-10-15-cluster-ha-design.md)
("QQQ on N nodes: cluster, HA, DR and scaling"). Sections A (node roles, `QNodeRoles`) and D
(graceful drain, `QNodeLifecycle`) are the core APIs this qbit builds on.

## What this qbit owns (P1)

- **Registry** (`cluster_node`): one row per node, written by the node itself with
  compare-and-set updates keyed by node id (no audits or record events; bookkeeping every 15 s).
- **Leases** (`cluster_lease`, `ClusterLeases`): leadership for non-Quartz singletons. One atomic
  conditional UPDATE per change, guarded by `version` and by "expired, free, or already mine".
  Times on the database clock.
- **Settings** (`cluster_setting`): seeded by an insert-only changelog; the runtime falls back to
  the same defaults when a row is missing or invalid, and the table rejects invalid values.
- **Drain**: a record action sets `drain_requested`; the node polls its own row each heartbeat and
  calls `QNodeLifecycle.startDraining`, whose listener marks the row DRAINING. Core flips `/ready`
  to 503 and stops claiming work. Cancel works only while the node is still UP (compare-and-set).
- **Lost nodes**: the `cluster-sweeper` lease holder marks stale UP/DRAINING rows LOST
  (compare-and-set on status and heartbeat) and raises `cluster.node.lost` via core `QAlerts`. A
  LOST node that beats again goes back to UP and its alert resolves.
- **Retention**: the same sweep deletes STOPPED/LOST rows older than `retain_stopped_days`. It is
  idempotent, so a sweep interrupted by a crash is simply redone by the next leader; no separate
  durable job is needed.
- **Health**: `cluster.node.heartbeat` (this node's own heartbeat is current).
- **UI**: the Cluster app (its home is the Nodes TABLE widget, full width) and the three tables,
  through the standard `/qqq/v1` metadata; no custom frontend.

## Not in P1

Scaling Deployments through a Kubernetes client, metrics for HPA/KEDA, and requests-in-flight on
the node row are P3 (spec section E). Cross-node cache invalidation is core P2 (`ClusterBus`).
