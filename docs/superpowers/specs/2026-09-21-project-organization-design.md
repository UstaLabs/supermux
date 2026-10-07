# Persistent projects with multiple locations

## Purpose and agreed scope

Projects provide stable organization and metadata above workspaces. A project can contain multiple repositories or ordinary directories. Workspace membership continues to be calculated from paths, preserving automatic discovery without making project management a prerequisite for starting work.

The first version is broker-local. Cross-host grouping is a future requirement, but shared metadata synchronization is outside this version. Clients qualify project identity with the host identity.

This design replaces the calculated-only project decision documented in `WorkspaceGrouping.kt`. It leaves workspace execution paths and session ownership intact.

## Current behavior

- `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/WorkspaceGrouping.kt` groups active and archived workspaces by `repoRoot ?: workdir`. Project groups sort by their derived labels; workspaces sort by their existing ordering fields.
- `GET /projects` in `src/channels/web/index.ts` derives project paths from active and archived sessions and filters managed worktree directories.
- Workspaces persist `workdir` and `repo_root`; sessions reference workspaces. There is no persistent project entity.

## Data model

```text
projects
  id          UUID primary key
  name        nonempty display name
  image_id    nullable broker-owned image reference
  sort_order  integer
  created_at  timestamp

project_locations
  id          UUID primary key
  project_id  required foreign key to projects
  path        normalized absolute path, unique within the broker
```

Project names need not be unique. A project can have zero or more locations; each registered location belongs to exactly one project. Empty projects persist. Locations have their own stable IDs so they can be reassigned without changing their identity.

No project foreign key is added to sessions or workspaces. The broker returns resolved project identity in workspace projections, and clients use `(hostId, projectId)` for grouping and preferences.

The image reference must use durable, authenticated broker-owned storage. Project images must not expire under temporary chat-upload cleanup. Image upload/storage wiring is an implementation-plan task; this design does not assume an existing asset table.

## Resolution and boundaries

A project service owns metadata, location registration, and matching. Agent execution and Git/worktree creation continue to use workspace paths directly.

1. Select `repo_root` when present, otherwise `workdir`.
2. Normalize that absolute path using one consistent policy for registration and lookup: remove redundant separators and dot segments while retaining filesystem case sensitivity. Preserve the existing persisted path spelling; do not add filesystem-dependent symlink resolution during reads.
3. Match a registered location exactly.
4. Return the associated project, or an unresolved result without mutating storage.

There is no prefix matching or Git-remote matching. Nested repositories remain distinct. A workspace opened in a subdirectory without a recorded repository root is a distinct location; the project layer does not independently rediscover Git roots. Worktrees with recorded repository roots match their original repository.

List and snapshot reads are read-only and do not run Git or require directories to exist. Archived workspaces remain resolvable when their directories are unavailable.

Workspace creation registers its effective location if unknown, creating a default project named with the existing path-label convention. Registration is transactional and idempotent: concurrent creation for one location produces one project/location pair, with no orphan projects from a uniqueness race. Failure to register rolls back the new workspace transaction before an agent is launched.

Clients temporarily fall back to existing path grouping for unresolved records or older brokers. This fallback does not create project records.

## Operations and user behavior

- Create an empty named project, edit its name/image, and reorder projects.
- Add an existing directory or repository as a location. An already-owned location returns a conflict identifying its current project rather than silently changing ownership.
- Move a location to another project explicitly. Its active and archived workspaces follow the new mapping without updating their stored paths or restarting agents. The source project remains even when empty.
- Keep project ordering separate from workspace ordering. Existing workspace order is retained; project order uses `sort_order`, with name and ID as deterministic tie-breakers. Backfill assigns initial order to preserve the current alphabetical presentation.
- Show persistent projects even when they have no active workspaces. Preserve the existing special Personal Assistants presentation.
- Starting work from a project with one location selects it. Multiple locations require a location choice; the client can remember the last selection keyed by host and project and must discard it when that location no longer belongs. A project with no locations offers location selection/registration first.
- Joining an existing workspace uses that workspace's workdir and host, without consulting project defaults.

Moving a location between projects is an organizational operation. Physically moving a directory is a separate operation and is not implemented here. Editing a location path alone must not be advertised as migrating workspace paths: historical workspaces still reference the old path. To retain grouping across an external directory move, register the new path on the same project and retain the old location for historical records.

Project deletion and location removal are excluded from the initial version; neither is needed for reassignment or persistent metadata. They require explicit semantics for existing workspace membership before being added.

## API and client integration

Expose project metadata and locations through a dedicated project catalog API. Keep the existing path-only `GET /projects` response compatible for older clients during rollout; do not silently replace its schema with project entities.

Provide broker operations for creation, metadata edits, ordering, location registration, and location reassignment. Validate ownership and image references within the broker. Conflicting location registration returns a conflict response; missing project/location IDs return not-found responses. Updates are atomic.

Project changes publish an invalidation/update through the broker's existing client synchronization mechanism. Reassignment must refresh resolved workspace membership for active and archived views. Clients consume broker-resolved project identity rather than implementing independent matching algorithms.

The project picker presents projects and their locations. Sidebar grouping, archived grouping, and project preferences use stable identity. Exact endpoint names, event types, and upload integration will be specified in the implementation plan after inspecting the relevant infrastructure.

## Migration

Create the tables and backfill one project/location for each distinct normalized effective path across active and archived workspaces. Include legacy sessions without a workspace using the same `repo_root ?? workdir` rule. Deduplicate before inserting. Do not combine separate locations merely because they share a basename or remote URL.

Preserve all session, workspace, and view IDs, layouts, execution paths, and workspace ordering. Worktree records with `repo_root` join the repository project. A legacy record with only a managed worktree path remains unresolved instead of guessing a repository; retain fallback grouping until its origin can be established.

Migration uses persisted values and does not depend on filesystem availability. Existing database migration transaction/version handling makes it repeat-safe. Startup reconciliation registers any valid effective locations left by legacy creation paths; normal list reads never perform reconciliation.

## Future cross-host grouping

Each broker owns its local project records, image assets, and paths. Matching never crosses host boundaries. Identical names, paths, or repository remotes on different hosts are not evidence of shared identity.

A future shared grouping layer can explicitly associate `(hostId, projectId)` pairs with a shared identity. Metadata authority, ordering, images, offline behavior, and synchronization must be designed at that point. UUIDs and qualified client references allow that addition without changing local path resolution or agent execution. No unused global-project table or synchronization process is introduced now.

## Verification criteria

- Migration preserves existing workspace/session/view data and initial visual ordering, including archived and missing-directory records.
- Multiple repositories and ordinary directories group under one project; recorded worktrees resolve to their repository location.
- Exact matching handles nested paths without prefix collisions, and the normalization policy is consistent between registration and lookup.
- Concurrent registration produces one project/location; failed registration leaves no orphan rows or launched agents.
- Reassigning a location refreshes active and archived grouping without changing workdirs, restarting agents, or losing source-project metadata.
- Empty projects and project images survive session/workspace lifecycle and temporary-upload cleanup.
- Metadata edits and ordering propagate to connected clients; project and workspace ordering stay independent.
- Multiple-location launch selects the intended directory; joining a workspace retains the existing host/workdir constraints.
- Host-qualified client keys prevent collisions, and old clients retain the path-only API behavior.
- Snapshot/list reads have no database writes and do not depend on filesystem or Git availability.

## Design review

The user approved persistent projects, multiple locations, dynamic path matching, and broker-local ownership with future cross-host grouping. The operational rules above make those decisions concrete for implementation planning. No runtime implementation is included in this document.
