<script lang="ts">
	import { onMount, onDestroy } from 'svelte';
	import { Capacitor } from '@capacitor/core';
	import { DjiAoa } from 'capacitor-dji-aoa';

	let { class: klass = '', children }: { class?: string; children?: import('svelte').Snippet } = $props();

	let container: HTMLDivElement;
	let resizeObs: ResizeObserver | null = null;
	let scrollListener: (() => void) | null = null;
	let lastRect = { x: -1, y: -1, w: -1, h: -1 };
	let shown = false;

	async function sync() {
		if (!container || !Capacitor.isNativePlatform()) return;
		const r = container.getBoundingClientRect();
		const dpr = window.devicePixelRatio || 1;
		const x = Math.round(r.left * dpr);
		const y = Math.round(r.top * dpr);
		const w = Math.round(r.width * dpr);
		const h = Math.round(r.height * dpr);
		if (w <= 0 || h <= 0) return;
		if (x === lastRect.x && y === lastRect.y && w === lastRect.w && h === lastRect.h && shown) return;
		lastRect = { x, y, w, h };
		await DjiAoa.showPreview({ x, y, width: w, height: h });
		shown = true;
	}

	onMount(() => {
		if (!Capacitor.isNativePlatform()) return;
		resizeObs = new ResizeObserver(() => void sync());
		resizeObs.observe(container);
		scrollListener = () => void sync();
		window.addEventListener('scroll', scrollListener, { passive: true, capture: true });
		window.addEventListener('resize', scrollListener);
		// Initial position (after layout settles)
		requestAnimationFrame(() => void sync());
	});

	onDestroy(() => {
		resizeObs?.disconnect();
		resizeObs = null;
		if (scrollListener) {
			window.removeEventListener('scroll', scrollListener, { capture: true });
			window.removeEventListener('resize', scrollListener);
			scrollListener = null;
		}
		if (Capacitor.isNativePlatform() && shown) {
			void DjiAoa.hidePreview();
			shown = false;
		}
	});
</script>

<div bind:this={container} class={klass}>
	<!-- The native SurfaceView punches a video window through the WebView on top of this
	     element. Children placed inside render at their natural z-order via the WebView and
	     stay visible above the video (because the WebView itself stays on top — the
	     SurfaceView covers only the bounding rect we report). -->
	{@render children?.()}
</div>
