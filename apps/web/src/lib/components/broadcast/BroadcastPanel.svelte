<script lang="ts">
	import CopyIcon from '@lucide/svelte/icons/copy';
	import { toast } from 'svelte-sonner';

	import SectionLabel from '$lib/components/common/SectionLabel.svelte';
	import { connection } from '$lib/stores/connection';
	import { broadcast, startBroadcast, stopBroadcast } from '$lib/stores/broadcast';
	import QrCode from '$lib/components/broadcast/QrCode.svelte';
	import * as m from '$lib/paraglide/messages';

	let busy = $state(false);

	async function toggle() {
		busy = true;
		try {
			if ($broadcast.running) {
				await stopBroadcast();
				toast.success(m.bcast_stopped());
			} else {
				if ($connection.state !== 'connected') {
					toast.warning(m.bcast_connectBeforeBroadcast());
					return;
				}
				await startBroadcast();
			}
		} catch (e) {
			toast.error(m.bcast_error({ error: e instanceof Error ? e.message : String(e) }));
		} finally {
			busy = false;
		}
	}

	async function copyUrl() {
		if (!$broadcast.url) return;
		try {
			await navigator.clipboard.writeText($broadcast.url);
			toast.success(m.bcast_urlCopied());
		} catch {
			toast.warning($broadcast.url);
		}
	}
</script>

<div class="flex flex-col gap-2.5">
	<div class="flex items-center justify-between gap-2.5">
		<SectionLabel>{m.bcast_title()}</SectionLabel>
		{#if $broadcast.running}
			<span class="text-[11.5px] text-muted-foreground">
				{m.bcast_watching({ count: $broadcast.viewers })}
			</span>
		{/if}
	</div>

	{#if $broadcast.running && $broadcast.url}
		<button
			type="button"
			class="flex h-11.5 items-center gap-2.5 rounded-xl border bg-card px-3 text-left"
			onclick={copyUrl}
		>
			<span class="size-2 shrink-0 rounded-full bg-primary"></span>
			<span class="flex-1 truncate font-mono text-xs">{$broadcast.url}</span>
			<CopyIcon class="size-4 shrink-0 text-muted-foreground" />
		</button>
		<p class="text-[11px] leading-snug text-dim text-pretty">{m.bcast_hint()}</p>
		<div class="flex justify-center py-1">
			<QrCode value={$broadcast.url} />
		</div>
	{/if}

	<button
		type="button"
		disabled={busy}
		class="h-11 rounded-xl border text-[13.5px] font-semibold transition-colors disabled:opacity-60 {$broadcast.running
			? 'border-destructive/40 bg-destructive/16 text-destructive'
			: 'border-primary bg-primary text-primary-foreground'}"
		onclick={toggle}
	>
		{$broadcast.running ? m.bcast_stop() : m.bcast_start()}
	</button>
</div>
