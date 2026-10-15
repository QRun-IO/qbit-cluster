# QBit Cluster

Run a QQQ application as one unit on N nodes. Each node registers itself in `cluster_node` and
sends a heartbeat; a node that goes quiet is marked Lost and raises an alert; one node at a time
holds named leases for singleton work (`ClusterLeases`); an administrator can drain a node from
the UI; and an Admin home widget shows the nodes by role. Core QQQ stays orchestrator-agnostic:
this qbit builds on core's `QNodeRoles`, `QNodeLifecycle` and `AsyncJobRegistry`.

Design: [QRun-IO/qqq docs/specs/2026-10-15-cluster-ha-design.md](https://github.com/QRun-IO/qqq/blob/develop/docs/specs/2026-10-15-cluster-ha-design.md),
section E (summary in [docs/DESIGN.md](docs/DESIGN.md)).

## Requirements

- Java 21.
- QQQ 4.1 with the cluster APIs (`com.kingsrook.qqq.backend.core.cluster.QNodeRoles`,
  `QNodeLifecycle`, `AsyncJobRegistry.runningCount()`, `ConditionalUpdateAction`). Until a QQQ
  RC ships them, the build uses a locally installed `4.1.0-SNAPSHOT` (the `qqq.snapshot.version`
  property in `pom.xml`).
- A backend with the `CONDITIONAL_UPDATE` capability for the `cluster_` tables (the RDBMS module;
  the memory backend in tests). PostgreSQL in production.

## Install

```xml
<dependency>
   <groupId>com.kingsrook.qbits</groupId>
   <artifactId>qbit-cluster</artifactId>
   <version>0.1.0-RC.1</version>
</dependency>
```

```java
new ClusterQBitProducer()
   .withQBitConfig(new ClusterQBitConfig()
      .withBackendName("rdbms")           // required
      .withParentAppName("admin"))         // optional: Admin > Cluster
   .produce(qInstance);
```

Database (Liquibase, from the jar; the host runs it, the qbit never runs DDL):

```yaml
- include: { file: db/liquibase/qbit-cluster/cluster.changelog.sql }
- include: { file: db/liquibase/qbit-cluster/cluster-defaults.changelog.sql }
```

Both are idempotent, PostgreSQL-first (also H2), have no foreign keys and need no superuser.

Start the runtime with the application:

- `QApplicationLauncher` starts it automatically (the producer registers `ClusterRuntimeService`).
- A host that starts services itself (e.g. with `QApplicationJavalinServer`) calls
  `new ClusterRuntimeService().start(server.getQInstance())` after the server starts, and
  `stop()` in a shutdown hook.

## Tables

| Table | What | Who writes it |
|---|---|---|
| `cluster_node` | One row per node: node id, pod, Kubernetes node, zone, deployment, roles, version, started/heartbeat times, status (UP, DRAINING, LOST, STOPPED), worker threads, scheduler/ESB flags, running steps, heap, drain request | Each node (no insert or edit in the UI; Drain node / Cancel drain; rows may be deleted) |
| `cluster_lease` | Named leases: holder, expiry, version (compare-and-set) | `ClusterLeases`; an admin may edit to force a hand-over |
| `cluster_setting` | `heartbeat_seconds`=15, `lost_after_seconds`=60, `retain_stopped_days`=7, `alert_on_lost`=true (seeded; validated on save) | Administrators |

Node placement comes from optional environment variables (the Kubernetes Downward API):
`QQQ_POD_NAME`, `QQQ_K8S_NODE_NAME`, `QQQ_ZONE`, `QQQ_DEPLOYMENT`; the version from
`ClusterQBitConfig.withAppVersion`, else `QQQ_APP_VERSION`, else the QQQ version. Node id and
roles come from core (`QQQ_NODE_ID` / `HOSTNAME`, `QQQ_NODE_ROLES`).

## Runtime

- On start: upsert this node's row (status UP, drain request cleared).
- Every `heartbeat_seconds`: write the heartbeat and live fields; if `drain_requested` is set,
  call `QNodeLifecycle.startDraining("requested by <user>: <reason>")`.
- A `QNodeLifecycle.onDrain` listener marks the node DRAINING (from a request or SIGTERM) and
  gives up leases; stop (service stop or JVM shutdown hook) marks it STOPPED.
- The node holding the `cluster-sweeper` lease marks nodes with a heartbeat older than
  `lost_after_seconds` LOST, raises `cluster.node.lost` through core `QAlerts` (qbit-alerts turns
  it into an Alerts record), and deletes STOPPED/LOST rows older than `retain_stopped_days`. The
  sweep is idempotent, so a crash mid-sweep is simply redone by the next leader.
- Health check `cluster.node.heartbeat`: UP when this node's heartbeat is within two intervals,
  DEGRADED when late, DOWN past `lost_after_seconds`.

All times compare on the database clock (`ConditionalUpdateAction.databaseNow`), not pod clocks.

## Leases

```java
if(ClusterLeases.tryAcquire("my-singleton", Duration.ofSeconds(60)))
{
   // leader for up to 60 s; call again (or renew) each cycle to keep it
}
ClusterLeases.renew(name, ttl);  ClusterLeases.release(name);  ClusterLeases.isLeader(name);
ClusterLeases.as("some-node-id").tryAcquire(...);   // explicit node id
```

Every change is one atomic `UPDATE cluster_lease SET ... WHERE name = ? AND version = ? AND
(expires_at < now OR holder_node_id = me OR holder_node_id IS NULL)` through core's
`ConditionalUpdateAction`.

## Permissions

| Permission | Grants |
|---|---|
| `cluster.read` | The Cluster app, the Nodes widget, reading the three tables |
| `cluster.write` | Editing settings and leases, deleting old node rows |
| `cluster.drain` | The Drain node and Cancel drain record actions |

Base names are configurable: `withPermissionBaseName("cluster")`, `withDrainPermissionName("cluster.drain")`.

## Configuration

| Option | Default | |
|---|---|---|
| `backendName` | (required) | Backend of the `cluster_` tables |
| `appName` / `appLabel` | `cluster` / `Cluster` | The app (its home shows the Nodes widget) |
| `parentAppName` | none | Attach the app under an existing app (e.g. `admin`) |
| `permissionBaseName` | `cluster` | `cluster.read` / `cluster.write` |
| `drainPermissionName` | `cluster.drain` | |
| `appVersion` | none | Version each node reports |
| `widgetRefreshSeconds` | 15 | |
| `registerRuntimeService` | true | Add `ClusterRuntimeService` to the instance's runtime services |

Table names are fixed (`cluster_` prefix, per the QRun qbit standard).

## Adopting in another app

1. Add the dependency and produce the qbit (above), after the backend exists and before the
   host attaches its admin apps.
2. Include both changelogs in the host's master changelog.
3. Start `ClusterRuntimeService` (automatic with `QApplicationLauncher`).
4. Grant `cluster.read`, `cluster.write`, `cluster.drain` to administrators.
5. Optionally set the Downward API variables in the Deployment.

## Build

```bash
./mvnw verify      # checkstyle, tests (memory backend + H2/Liquibase), JaCoCo gates
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
