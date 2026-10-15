# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project overview

QBit Cluster runs a QQQ application as one unit on N nodes: node registry and heartbeats
(`cluster_node`), lost-node detection with the `cluster.node.lost` alert, compare-and-set leases
for singletons (`cluster_lease`, `ClusterLeases`), seeded settings (`cluster_setting`), Drain node
/ Cancel drain record actions, the `cluster.node.heartbeat` health check and the Cluster app with
the Nodes widget. Read `docs/DESIGN.md` and `README.md` first.

## Build commands

Java 21; always use the wrapper (offline in this workspace):

```bash
./mvnw -o verify                       # checkstyle, tests, JaCoCo (>=70% instructions, >=90% classes)
./mvnw -o test -Dtest=ClusterLeasesTest
```

Tests use JUnit 5, AssertJ, MOCK auth and the memory backend; `ClusterChangelogTest` runs the
shipped changelogs through real Liquibase on H2 and the runtime through the RDBMS module. No
Docker. If core cluster classes are missing, the shared `~/.m2` 4.1.0-SNAPSHOT was overwritten:
reinstall QQQ from the `feature/cluster-ha-p1` worktree.

## Architecture by package

- `com.kingsrook.qbits.cluster`: `ClusterQBitProducer` (adds everything directly),
  `ClusterQBitConfig`, `ClusterLeases`.
- `model`: `ClusterTables` (table metadata, field and table names), `NodeStatus`,
  `ClusterSettings` (defaults + load), `ClusterSettingValidator`.
- `runtime`: `ClusterRuntime` (thread, tick, stop), `ClusterRuntimeService`,
  `ClusterNodeRegistry` (own row), `ClusterNodeInfo` (env, roles, JVM), `ClusterSweeper`,
  `ClusterAlerts`, `ClusterSystem` (system-session scope).
- `processes`: `ClusterNodeActions` (metadata), `ClusterNodeActionSteps` (Review / Result).
- `widgets`: `ClusterNodesWidgetRenderer`. `health`: `ClusterHeartbeatHealthCheck`.

## Invariants

- Every write that two nodes could race on is a `ConditionalUpdateAction` compare-and-set; times
  come from the backend clock (`databaseNow`), never the pod clock.
- The qbit never runs DDL. Schema and defaults ship as Liquibase SQL in the jar; defaults are
  insert-only with empty rollbacks. Column names = snake_case of field names; keep the changelog,
  `ClusterTables` and `ClusterSettings.DEFAULTS` in step (tests enforce it).
- Processes are record actions only (never in navigation), standard streamed ETL, plain labels.
- Renderers read in the viewer's session; only the runtime's own bookkeeping runs as system.
- Every table, field, process, widget and possible-value source has help in
  `help/qbit-cluster.yaml` (`ClusterQBitProducerTest.helpIsComplete`). Summaries are plain text,
  at most 120 characters, no underscores.

## Naming and code style

Tables `cluster_*`, permissions `cluster.read`, `cluster.write`, `cluster.drain`, processes
`clusterDrainNode` / `clusterCancelDrain`, widget `clusterNodes`, lease `cluster-sweeper`.
Kingsrook style: three-space indent, braces on new lines, `return (x);`, flower-box Javadoc on
every class and method, the Apache license header. Do not weaken checkstyle or coverage gates.

## Dependencies

Parent `com.kingsrook:qbit-build-parent:2.0.0`; the default `qqq-snapshot` profile imports
`qqq-bom-pom` `${qqq.snapshot.version}` (4.1.0-SNAPSHOT until a QQQ RC ships the cluster APIs).

## Release flow

CircleCI (`kingsrook/qqq-orb`): feature branches test; `develop` publishes SNAPSHOTs; `release/*`
publishes RCs (cut by `.github/workflows/release-rc.yml`); `vX.Y.Z` tags publish releases.
Publishing requires the owner's authorization. Update CHANGELOG before a release.
