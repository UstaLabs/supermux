-- Persistent projects. Membership is NOT stored on workspaces or sessions: the
-- broker resolves repo_root ?? workdir to a location at read time.
-- Rows are backfilled by ProjectService.reconcile() at startup, not here —
-- path normalization and default labels are TypeScript rules.
-- Spec: docs/superpowers/specs/2026-09-21-project-organization-design.md
CREATE TABLE projects (
  id          TEXT PRIMARY KEY,
  name        TEXT NOT NULL CHECK(length(trim(name)) > 0),
  image_id    TEXT,
  sort_order  INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT NOT NULL
);

CREATE TABLE project_locations (
  id          TEXT PRIMARY KEY,
  project_id  TEXT NOT NULL REFERENCES projects(id),
  path        TEXT NOT NULL UNIQUE
);
CREATE INDEX project_locations_project ON project_locations(project_id);
