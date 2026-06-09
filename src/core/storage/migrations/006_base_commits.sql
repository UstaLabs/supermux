-- src/core/storage/migrations/006_base_commits.sql
ALTER TABLE sessions ADD COLUMN base_commits TEXT;
