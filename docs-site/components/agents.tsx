import { readFileSync } from 'node:fs';
import { join } from 'node:path';

const AGENTS = [
  { id: 'claude', name: 'Claude Code' },
  { id: 'codex', name: 'Codex' },
  { id: 'cursor', name: 'Cursor' },
  { id: 'opencode', name: 'OpenCode' },
  { id: 'grok', name: 'Grok' },
  { id: 'antigravity', name: 'Antigravity' },
] as const;

type AgentId = (typeof AGENTS)[number]['id'];

// Inlined so the marks drawn in currentColor follow the theme.
function svg(id: AgentId): string {
  return readFileSync(join(process.cwd(), 'public/images/agents', `${id}.svg`), 'utf8');
}

export function AgentLogo({ id, size = 20 }: { id: AgentId; size?: number }) {
  const agent = AGENTS.find((a) => a.id === id);
  return (
    <span
      className="sm-agent-logo"
      role="img"
      aria-label={agent?.name ?? id}
      style={{ width: size, height: size }}
      dangerouslySetInnerHTML={{ __html: svg(id) }}
    />
  );
}

export function AgentGrid() {
  return (
    <div className="sm-agent-grid not-prose">
      {AGENTS.map((a) => (
        <div key={a.id} className="sm-agent-cell">
          <AgentLogo id={a.id} size={22} />
          <span className="sm-agent-name">{a.name}</span>
        </div>
      ))}
    </div>
  );
}
