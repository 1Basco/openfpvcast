<script lang="ts">
	import ChevronRightIcon from '@lucide/svelte/icons/chevron-right';
	import { toast } from 'svelte-sonner';

	import SectionLabel from '$lib/components/common/SectionLabel.svelte';
	import Segmented from '$lib/components/common/Segmented.svelte';
	import { connection } from '$lib/stores/connection';
	import { cameraState, cameraActions } from '$lib/stores/camera';
	import { exposureModes, type ExposureMode } from '$lib/aoa';
	import * as m from '$lib/paraglide/messages';

	// Home-screen camera block: shows what the camera is set to right now; picking a value
	// happens on the /camera screen. Exposure mode is the one control that stays here because
	// it is a single tap and it gates everything else.

	const cam = $derived($cameraState);
	const connected = $derived($connection.state === 'connected');

	async function setMode(mode: ExposureMode) {
		if ($connection.state !== 'connected') {
			toast.warning(m.cam_connect());
			return;
		}
		try {
			const r = await cameraActions.exposureMode($connection.sessionId, mode);
			if (r.timedOut) toast.warning(`${m.cam_mode()} ${mode}: timeout`);
		} catch (e) {
			toast.error(`${m.cam_mode()} ${mode}: ${e instanceof Error ? e.message : String(e)}`);
		}
	}

	const tiles = $derived([
		{ label: 'ISO', value: cam.iso === 'auto-high' ? m.cam_isoAutoMax() : String(cam.iso), big: true },
		{ label: m.cam_shutterName(), value: `1/${cam.shutter}`, big: true },
		{
			label: m.cam_whiteBalance(),
			value: cam.wbMode === 'auto' ? 'auto' : `${cam.wbKelvin}K`,
			big: true
		},
		{ label: m.cam_video(), value: `${cam.resolution} · ${cam.fps}`, big: false }
	]);
</script>

<section class="flex flex-col gap-2.5">
	<SectionLabel>{m.page_cameraTitle()}</SectionLabel>

	<Segmented
		options={exposureModes}
		value={cam.exposureMode}
		onselect={setMode}
		disabled={!connected}
	/>

	<div class="grid grid-cols-2 gap-2.5">
		{#each tiles as t (t.label)}
			<a
				href="/camera/"
				class="flex flex-col gap-1.5 rounded-xl border bg-card px-3 py-3"
				aria-label={`${t.label}: ${t.value}`}
			>
				<span class="text-[10.5px] font-bold uppercase tracking-[0.1em] text-label">{t.label}</span>
				<span class="flex items-center justify-between gap-1.5">
					<span class="truncate font-mono font-medium {t.big ? 'text-[19px]' : 'text-[15px]'}">
						{t.value}
					</span>
					<ChevronRightIcon class="size-4 shrink-0 text-dim" />
				</span>
			</a>
		{/each}
	</div>
</section>
