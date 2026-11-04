import { copyFileSync } from 'node:fs';
copyFileSync('src/schema.d.ts', 'dist/schema.d.ts');
