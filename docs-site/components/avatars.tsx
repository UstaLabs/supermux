import { basePath } from '@/lib/shared';

// Stills of the app's built-in characters, rendered with the app's own avatar renderer
// (src/core/agents/avatar/renderer.js) from src/core/agents/avatar/gallery/*.json.
const CHARACTERS = ['Hoot', 'Miso', 'Inky', 'Sprout', 'Pebble', 'Boo', 'Fox', 'Pip', 'Ponder', 'Lev'];

const STATES = [
  { id: 'idle', label: 'Idle' },
  { id: 'working', label: 'Working' },
  { id: 'waiting', label: 'Has a reply' },
  { id: 'asleep', label: 'Asleep' },
];

function src(name: string, state: string) {
  return `${basePath}/images/avatars/${name.toLowerCase()}-${state}.svg`;
}

export function AvatarGallery() {
  return (
    <div className="sm-avatar-grid not-prose">
      {CHARACTERS.map((name) => (
        <figure key={name} className="sm-avatar-cell">
          <img src={src(name, 'idle')} alt={name} width={64} height={64} />
          <figcaption>{name}</figcaption>
        </figure>
      ))}
    </div>
  );
}

export function AvatarStates({ name = 'Hoot' }: { name?: string }) {
  return (
    <div className="sm-avatar-states not-prose">
      {STATES.map((s) => (
        <figure key={s.id} className="sm-avatar-cell">
          <img src={src(name, s.id)} alt={`${name}, ${s.label.toLowerCase()}`} width={72} height={72} />
          <figcaption>{s.label}</figcaption>
        </figure>
      ))}
    </div>
  );
}
