<script lang="ts">
	import RotateCwSquareIcon from '@lucide/svelte/icons/rotate-cw-square';
	import VideoPreview from '$lib/components/VideoPreview.svelte';
	import TelemetryOverlay from '$lib/components/TelemetryOverlay.svelte';
	import SideTab from '$lib/components/SideTab.svelte';
	import { goggleDescription, cameraModel } from '$lib/stores/connection';
	import { isRecording, recordingElapsedMs, formatElapsed } from '$lib/stores/recording';
	import { settings, patchSettings } from '$lib/stores/settings';
	import * as m from '$lib/paraglide/messages';

	const forceLandscape = $derived($settings.ui.forceLandscape);

	function toggleLandscape() {
		patchSettings({ ui: { ...$settings.ui, forceLandscape: !forceLandscape } });
	}
</script>

<!-- Live layout: preview fills the screen; the side tab rail stays reachable at all times. -->
<main class="screen-fit flex flex-col overflow-hidden landscape:flex-row">
	<section class="relative min-h-0 min-w-0 flex-1 bg-black">
		<VideoPreview class="absolute inset-0" />
		<TelemetryOverlay />

		<div
			class="pointer-events-none absolute left-1/2 top-3.5 flex -translate-x-1/2 items-center gap-2"
		>
			{#if $goggleDescription}
				<span
					class="flex h-6.5 max-w-40 items-center truncate rounded-lg bg-background/60 px-2.5 text-[11px] font-medium text-foreground/80"
				>
					{$goggleDescription}
				</span>
			{/if}
			{#if $cameraModel}
				<span
					class="flex h-6.5 items-center rounded-lg bg-background/60 px-2.5 font-mono text-[11px] tracking-wide text-foreground/70"
				>
					{$cameraModel.toUpperCase()}
				</span>
			{/if}
		</div>

		<div class="absolute right-3.5 top-3.5 flex items-center gap-2">
			{#if $isRecording}
				<span
					class="pointer-events-none flex h-10 items-center gap-2 rounded-lg border border-destructive/35 bg-destructive/18 px-2.5"
				>
					<span class="size-2 animate-pulse rounded-full bg-destructive"></span>
					<span class="font-mono text-[12.5px] font-medium text-destructive">
						{formatElapsed($recordingElapsedMs)}
					</span>
				</span>
			{/if}
			<button
				type="button"
				class="flex size-10 items-center justify-center rounded-lg border transition-colors {forceLandscape
					? 'border-primary/40 bg-primary/20 text-primary'
					: 'border-white/10 bg-background/60 text-muted-foreground'}"
				onclick={toggleLandscape}
				title={forceLandscape ? m.stream_landscapeOn() : m.stream_landscapeOff()}
				aria-label={forceLandscape ? m.stream_landscapeOn() : m.stream_landscapeOff()}
				aria-pressed={forceLandscape}
			>
				<RotateCwSquareIcon class="size-5" />
			</button>
		</div>
	</section>

	<SideTab />
</main>
