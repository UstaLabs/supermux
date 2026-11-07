// Lists the <Check> notes left in the content: open questions to settle before publishing.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const root = new URL('../content/pages', import.meta.url).pathname;
const files = [];
(function walk(dir) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path);
    else if (path.endsWith('.mdx')) files.push(path);
  }
})(root);

let count = 0;
for (const file of files.sort()) {
  for (const match of readFileSync(file, 'utf8').matchAll(/<Check>([\s\S]*?)<\/Check>/g)) {
    count++;
    console.log(`${relative(root, file)}\n  ${match[1].trim().replace(/\s+/g, ' ')}\n`);
  }
}
console.log(count ? `${count} open check(s).` : 'No open checks.');
