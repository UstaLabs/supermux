#!/usr/bin/env bash
# resolve-source.sh — map a session name / branch / path to a worktree.
#
# Usage (source or exec):
#   scripts/lib/resolve-source.sh <query>
#   SOURCE_QUERY=... source scripts/lib/resolve-source.sh   # sets RESOLVED_* vars
#
# Prints shell-friendly KEY=value lines:
#   workdir=...
#   name=...
#   branch=...
#   repo_root=...
#   session_id=...
#   base_branch=...
#
# Query resolution order:
#   1. Existing directory path (absolute or relative)
#   2. Exact session name (case-insensitive) in ~/.mux/state/db.sqlite3
#   3. Fuzzy session name / FTS substring
#   4. Branch name (mux/..., origin/...) → git worktree list or sessions.session_branch
#   5. Partial workdir basename under ~/.mux/worktrees
set -euo pipefail

# Agent sandboxes often remap $HOME away from the real user home. Prefer an
# existing live mux state, then passwd home, then $HOME.
_real_home() {
  local h
  for h in "${MUX_USER_HOME:-}" "$(getent passwd "$(id -un)" 2>/dev/null | cut -d: -f6)" "$HOME"; do
    [[ -n "$h" && -d "$h/.mux/state" ]] && { echo "$h"; return; }
  done
  # last resort: well-known path on this machine
  for h in /home/ahmet "$HOME"; do
    [[ -d "$h" ]] && { echo "$h"; return; }
  done
  echo "$HOME"
}
USER_HOME="$(_real_home)"
DB="${MUX_STATE_DIR:-$USER_HOME/.mux/state}/db.sqlite3"
WORKTREES_ROOT="${MUX_WORKTREES_ROOT:-$USER_HOME/.mux/worktrees}"
MAIN_REPO="${MUX_MAIN_REPO:-$USER_HOME/projects/supermux}"

query="${1:-${SOURCE_QUERY:-}}"
if [[ -z "$query" ]]; then
  echo "usage: resolve-source.sh <session-name|branch|path|substring>" >&2
  exit 2
fi

# strip accidental quotes
query="${query//\"/}"
query="${query//\'/}"

emit() {
  # $1=workdir $2=name $3=branch $4=repo_root $5=session_id $6=base_branch
  printf 'workdir=%q\n' "$1"
  printf 'name=%q\n' "${2:-}"
  printf 'branch=%q\n' "${3:-}"
  printf 'repo_root=%q\n' "${4:-}"
  printf 'session_id=%q\n' "${5:-}"
  printf 'base_branch=%q\n' "${6:-}"
  # also human line on stderr
  echo "resolved: ${2:-?} @ $1 (${3:-unknown branch})" >&2
}

# 1) path
if [[ -d "$query" ]]; then
  abs="$(cd "$query" && pwd)"
  branch="$(git -C "$abs" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  root="$(git -C "$abs" rev-parse --show-toplevel 2>/dev/null || echo "$abs")"
  emit "$abs" "$(basename "$abs")" "$branch" "$root" "" ""
  exit 0
fi
# relative to main repo
if [[ -d "$MAIN_REPO/$query" ]]; then
  abs="$(cd "$MAIN_REPO/$query" && pwd)"
  branch="$(git -C "$abs" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  emit "$abs" "$(basename "$abs")" "$branch" "$MAIN_REPO" "" ""
  exit 0
fi

sql_escape() { printf "%s" "$1" | sed "s/'/''/g"; }
q_esc="$(sql_escape "$query")"

if [[ -f "$DB" ]] && command -v sqlite3 >/dev/null; then
  # 2) exact name
  row="$(sqlite3 -separator $'\t' "$DB" \
    "SELECT id, name, workdir, IFNULL(session_branch,''), IFNULL(repo_root,''), IFNULL(base_branch,'')
     FROM sessions
     WHERE lower(name)=lower('$q_esc')
     ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'suspended' THEN 1 ELSE 2 END, created_at DESC
     LIMIT 1;" 2>/dev/null || true)"
  if [[ -n "$row" ]]; then
    IFS=$'\t' read -r sid sname sdir sbranch srepo sbase <<<"$row"
    if [[ -d "$sdir" ]]; then
      emit "$sdir" "$sname" "$sbranch" "$srepo" "$sid" "$sbase"
      exit 0
    fi
    echo "session '$sname' points at missing workdir: $sdir" >&2
  fi

  # 3) fuzzy name (LIKE)
  row="$(sqlite3 -separator $'\t' "$DB" \
    "SELECT id, name, workdir, IFNULL(session_branch,''), IFNULL(repo_root,''), IFNULL(base_branch,'')
     FROM sessions
     WHERE name LIKE '%$q_esc%' COLLATE NOCASE
        OR workdir LIKE '%$q_esc%'
        OR IFNULL(session_branch,'') LIKE '%$q_esc%'
     ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'suspended' THEN 1 ELSE 2 END,
              CASE WHEN name LIKE '%$q_esc%' COLLATE NOCASE THEN 0 ELSE 1 END,
              created_at DESC
     LIMIT 5;" 2>/dev/null || true)"
  if [[ -n "$row" ]]; then
    # if multiple, prefer first with existing dir; print candidates on stderr if >1
    count="$(printf '%s\n' "$row" | grep -c . || true)"
    if [[ "$count" -gt 1 ]]; then
      echo "multiple matches for '$query':" >&2
      printf '%s\n' "$row" | while IFS=$'\t' read -r sid sname sdir sbranch srepo sbase; do
        echo "  - $sname  branch=$sbranch  $sdir" >&2
      done
    fi
    while IFS=$'\t' read -r sid sname sdir sbranch srepo sbase; do
      if [[ -d "$sdir" ]]; then
        emit "$sdir" "$sname" "$sbranch" "$srepo" "$sid" "$sbase"
        exit 0
      fi
    done <<<"$row"
  fi

  # 4a) exact session_branch
  row="$(sqlite3 -separator $'\t' "$DB" \
    "SELECT id, name, workdir, IFNULL(session_branch,''), IFNULL(repo_root,''), IFNULL(base_branch,'')
     FROM sessions
     WHERE session_branch='$q_esc' OR session_branch LIKE '%/$q_esc'
     ORDER BY CASE status WHEN 'active' THEN 0 ELSE 1 END, created_at DESC
     LIMIT 1;" 2>/dev/null || true)"
  if [[ -n "$row" ]]; then
    IFS=$'\t' read -r sid sname sdir sbranch srepo sbase <<<"$row"
    if [[ -d "$sdir" ]]; then
      emit "$sdir" "$sname" "$sbranch" "$srepo" "$sid" "$sbase"
      exit 0
    fi
  fi
fi

# 4b) git worktree list on main repo
if [[ -d "$MAIN_REPO/.git" ]] || [[ -f "$MAIN_REPO/.git" ]]; then
  # worktree list --porcelain
  while IFS= read -r line; do
    case "$line" in
      worktree\ *) wt="${line#worktree }" ;;
      branch\ *) br="${line#branch refs/heads/}"
        if [[ "$br" == "$query" || "$br" == */"$query" || "$line" == *"$query"* ]]; then
          if [[ -n "${wt:-}" && -d "$wt" ]]; then
            emit "$wt" "$(basename "$wt")" "$br" "$MAIN_REPO" "" ""
            exit 0
          fi
        fi
        ;;
    esac
  done < <(git -C "$MAIN_REPO" worktree list --porcelain 2>/dev/null || true)
fi

# 5) basename under ~/.mux/worktrees
if [[ -d "$WORKTREES_ROOT" ]]; then
  # direct child or nested uuid dir match
  hit="$(find "$WORKTREES_ROOT" -maxdepth 3 -type d \( -name "$query" -o -path "*/*$query*" \) 2>/dev/null \
    | while read -r d; do
        [[ -f "$d/.git" || -d "$d/.git" || -f "$d/package.json" ]] && echo "$d" && break
      done)"
  if [[ -n "$hit" && -d "$hit" ]]; then
    branch="$(git -C "$hit" rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
    emit "$hit" "$(basename "$hit")" "$branch" "$MAIN_REPO" "" ""
    exit 0
  fi
fi

# 6) main repo itself as last resort for "dev" / "main" / "supermux"
if [[ "$query" =~ ^(dev|main|supermux|live|local)$ ]]; then
  emit "$MAIN_REPO" "supermux" "$(git -C "$MAIN_REPO" rev-parse --abbrev-ref HEAD 2>/dev/null || echo dev)" "$MAIN_REPO" "" ""
  exit 0
fi

echo "could not resolve source: $query" >&2
echo "try: exact session name, branch (mux/…), or absolute worktree path" >&2
exit 1
