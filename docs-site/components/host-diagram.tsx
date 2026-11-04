import { Monitor, Smartphone, Globe } from 'lucide-react';
import { AgentLogo } from '@/components/agents';
import { Mark } from '@/components/logo';

const DEVICES = [
  { icon: Monitor, label: 'Desktop app' },
  { icon: Smartphone, label: 'Phone and tablet' },
  { icon: Globe, label: 'Browser' },
];

const AGENTS = ['claude', 'codex', 'cursor', 'opencode', 'grok', 'antigravity'] as const;

// How supermux fits together: your devices connect to one computer that runs the agents.
export function HostDiagram() {
  return (
    <figure className="sm-diagram not-prose" aria-label="Your devices connect to supermux on your computer, which runs your agents">
      <div className="sm-diagram-col">
        <div className="sm-diagram-label">Your devices</div>
        {DEVICES.map(({ icon: Icon, label }) => (
          <div key={label} className="sm-diagram-device">
            <Icon size={16} aria-hidden="true" />
            <span>{label}</span>
          </div>
        ))}
      </div>
      <div className="sm-diagram-link" aria-hidden="true">
        <span>paired</span>
      </div>
      <div className="sm-diagram-host">
        <div className="sm-diagram-label">Your computer or server</div>
        <div className="sm-diagram-core">
          <Mark className="sm-diagram-mark" />
          <span>supermux</span>
        </div>
        <div className="sm-diagram-agents">
          {AGENTS.map((id) => (
            <span key={id} className="sm-diagram-agent">
              <AgentLogo id={id} size={18} />
            </span>
          ))}
        </div>
        <div className="sm-diagram-note">your code, history and memory stay here</div>
      </div>
    </figure>
  );
}
