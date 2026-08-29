<script lang="ts">
	import { armed } from '$lib/stores/telemetry';
	import { cameraState } from '$lib/stores/camera';
	import { settings } from '$lib/stores/settings';

	// HUD over the video (pointer-events pass through). Deliberately thin: battery voltage, link
	// strength and the rest of the flight data are already burned into the frame by the goggles'
	// own OSD, so re-drawing them here would only duplicate what the pilot can see. What this adds
	// is the state the goggles do NOT show — whether we consider the aircraft armed, and (while the
	// camera panel is open) the values that panel changes, so you can check them without looking
	// away from the frame. The REC pill lives in StreamingMode because it shares the top-right
	// cluster with the landscape lock.

	const cam = $derived($cameraState);
	const cameraPanelOpen = $derived($settings.ui.sideTabOpen && $settings.ui.sideTabSection === 'camera');

	type Stat = { label: string; value: string };

	const stats = $derived.by<Stat[]>(() => {
		if (!cameraPanelOpen) return [];
		return [
			{ label: 'ISO', value: String(cam.iso) },
			{ label: 'SHUT', value: `1/${cam.shutter}` },
			{ label: 'WB', value: cam.wbMode === 'auto' ? 'auto' : `${cam.wbKelvin}K` }
		];
	});
</script>

{#if $armed}
	<div
		class="pointer-events-none absolute left-3.5 top-3.5 flex h-6.5 items-center rounded-lg border border-destructive/35 bg-destructive/18 px-2.5"
	>
		<span class="text-[11px] font-bold tracking-[0.12em] text-destructive">ARMED</span>
	</div>
{/if}

{#if stats.length > 0}
	<div
		class="pointer-events-none absolute bottom-3.5 left-3.5 right-3.5 flex max-w-max items-stretch overflow-hidden rounded-[10px] bg-background/65 backdrop-blur-sm"
	>
		{#each stats as s, i (s.label)}
			{#if i > 0}
				<span class="my-2 w-px bg-white/10"></span>
			{/if}
			<span class="flex flex-col justify-center gap-px px-3.5 py-1.5">
				<span class="text-[9px] font-bold tracking-[0.11em] text-label">{s.label}</span>
				<span class="font-mono text-[13px] font-medium">{s.value}</span>
			</span>
		{/each}
	</div>
{/if}
