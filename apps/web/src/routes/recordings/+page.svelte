<script lang="ts">
	import { onMount } from 'svelte';
	import { Capacitor } from '@capacitor/core';
	import { DjiAoa, type RecordingFile } from 'capacitor-dji-aoa';
	import PlayIcon from '@lucide/svelte/icons/play';
	import TrashIcon from '@lucide/svelte/icons/trash-2';
	import RefreshIcon from '@lucide/svelte/icons/rotate-cw';
	import { toast } from 'svelte-sonner';

	import AppBar from '$lib/components/common/AppBar.svelte';
	import * as m from '$lib/paraglide/messages';

	let files = $state<RecordingFile[]>([]);
	let loading = $state(true);
	let playingUri = $state<string | null>(null);

	const totalBytes = $derived(files.reduce((sum, f) => sum + f.sizeBytes, 0));

	async function refresh() {
		loading = true;
		try {
			if (Capacitor.isNativePlatform()) {
				const r = await DjiAoa.listRecordings();
				files = r.recordings;
			}
		} catch (e) {
			toast.error(m.recordings_listFailed({ error: e instanceof Error ? e.message : String(e) }));
		} finally {
			loading = false;
		}
	}

	async function remove(f: RecordingFile) {
		try {
			await DjiAoa.deleteRecording({ uri: f.uri });
			if (playingUri === f.uri) playingUri = null;
			files = files.filter((x) => x.uri !== f.uri);
			toast.success(m.recordings_deleted());
		} catch (e) {
			toast.error(m.recordings_deleteFailed({ error: e instanceof Error ? e.message : String(e) }));
		}
	}

	/** content:// and file:// URIs must go through Capacitor's local HTTP bridge to play in the WebView. */
	function playableSrc(uri: string): string {
		return Capacitor.convertFileSrc(uri);
	}

	function fmtSize(bytes: number): string {
		if (bytes >= 1_000_000_000) return (bytes / 1_000_000_000).toFixed(2) + ' GB';
		if (bytes >= 1_000_000) return (bytes / 1_000_000).toFixed(1) + ' MB';
		return Math.round(bytes / 1000) + ' kB';
	}

	function fmtDuration(ms: number): string {
		if (!ms) return '—';
		const s = Math.round(ms / 1000);
		return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
	}

	function fmtDate(ms: number): string {
		return new Date(ms).toLocaleString();
	}

	onMount(() => void refresh());
</script>

<main class="screen-fit flex flex-col overflow-hidden">
	<AppBar title={m.nav_recordings()} back="/" backLabel={m.nav_backShort()}>
		{#snippet trailing()}
			<button
				type="button"
				class="flex size-11 shrink-0 items-center justify-center rounded-lg text-muted-foreground"
				aria-label={m.recordings_refresh()}
				onclick={refresh}
			>
				<RefreshIcon class="size-4.5" />
			</button>
		{/snippet}
	</AppBar>

	<div class="min-h-0 flex-1 overflow-y-auto">
		{#if loading}
		<p class="p-4 text-center text-[12.5px] text-muted-foreground">{m.recordings_loading()}</p>
	{:else if files.length === 0}
		<p class="p-4 text-[12.5px] text-muted-foreground">
			{m.recordings_emptyPrefix()} <code class="font-mono">Movies/OpenFpvCast/</code>.
		</p>
	{:else}
		<div class="flex items-center justify-between px-4 py-3">
			<span class="text-xs text-muted-foreground">{m.recordings_files({ count: files.length })}</span>
			<span class="font-mono text-xs text-muted-foreground">{fmtSize(totalBytes)}</span>
		</div>

		<div class="flex flex-col px-4 pb-8">
			{#each files as f, i (f.uri)}
				{@const playing = playingUri === f.uri}
				{#if i > 0 && !playing}
					<div class="hairline"></div>
				{/if}
				<div class={playing ? 'my-2.5 overflow-hidden rounded-2xl border border-primary/40 bg-card' : ''}>
					<div class="flex h-[70px] items-center gap-3 {playing ? 'px-3' : ''}">
						<button
							type="button"
							class="flex size-8.5 shrink-0 items-center justify-center rounded-full {playing
								? 'bg-primary text-primary-foreground'
								: 'bg-secondary text-muted-foreground'}"
							aria-label={m.recordings_play()}
							onclick={() => (playingUri = playing ? null : f.uri)}
						>
							<PlayIcon class="size-3.5 fill-current" />
						</button>
						<button
							type="button"
							class="flex min-w-0 flex-1 flex-col gap-0.5 text-left"
							onclick={() => (playingUri = playing ? null : f.uri)}
						>
							<span class="truncate font-mono text-[12.5px]">{f.name}</span>
							<span class="truncate text-[11px] text-muted-foreground">
								{fmtDuration(f.durationMs)} · {fmtSize(f.sizeBytes)} · {fmtDate(f.createdAtMs)}
							</span>
						</button>
						<button
							type="button"
							class="flex size-9 shrink-0 items-center justify-center rounded-lg {playing
								? 'text-destructive'
								: 'text-dim'}"
							aria-label={m.recordings_delete()}
							onclick={() => remove(f)}
						>
							<TrashIcon class="size-4.5" />
						</button>
					</div>
					{#if playing}
						<!-- svelte-ignore a11y_media_has_caption -->
						<video
							src={playableSrc(f.uri)}
							controls
							autoplay
							playsinline
							class="aspect-video w-full bg-black"
						></video>
					{/if}
				</div>
			{/each}
		</div>

		<div class="px-4 pb-8">
			<span class="font-mono text-[11px] text-dim">Movies/OpenFpvCast/</span>
		</div>
	{/if}
	</div>
</main>
