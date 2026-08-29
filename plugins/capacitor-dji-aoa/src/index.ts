import { registerPlugin } from '@capacitor/core';

import type { DjiAoaPlugin } from './definitions';

const DjiAoa = registerPlugin<DjiAoaPlugin>('DjiAoa', {
	web: () => import('./web').then((m) => new m.DjiAoaWeb())
});

export * from './definitions';
export { DjiAoa };
