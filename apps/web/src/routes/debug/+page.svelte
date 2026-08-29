<script lang="ts">
	import { goto } from '$app/navigation';
	import { Button } from '$lib/components/ui/button';
	import { Card, CardContent, CardHeader, CardTitle } from '$lib/components/ui/card';
	import { Badge } from '$lib/components/ui/badge';
	import { Input } from '$lib/components/ui/input';
	import { settings } from '$lib/stores/settings';
	import {
		debugRows,
		debugEnabled,
		debugPaused,
		diagnosticsUploading,
		diagnosticsStats,
		setDebugCapture,
		clearDebugRows,
		pushMarker,
		DEBUG_MAX_ROWS,
		DIAGNOSTICS_ENDPOINT_CONFIGURED,
		type DebugRow
	} from '$lib/stores/debug';
	import type { DumlEvent } from 'capacitor-dji-aoa';

	// Route guard: the debug tools live behind the developer-mode setting. If someone
	// reaches /debug directly without it on, bounce home.
	$effect(() => {
		if (!$settings.developerMode) goto('/');
	});

	let filterCmdSet = $state<string>('all');
	let markerLabel = $state<string>('');

	async function toggle() {
		await setDebugCapture(!$debugEnabled);
	}

	function addMarker() {
		const label = markerLabel.trim() || 'mark';
		pushMarker(label);
		markerLabel = '';
	}

	const filtered = $derived.by(() => {
		if (filterCmdSet === 'all') return $debugRows;
		const target = parseInt(filterCmdSet, 16);
		return $debugRows.filter(
			(r) => (r.kind === 'duml' || r.kind === 'dumlTx') && (r as DumlEvent).cmdSet === target
		);
	});

	function hex2(n: number) { return n.toString(16).padStart(2, '0'); }
	function hex4(n: number) { return n.toString(16).padStart(4, '0'); }

	function badgeVariant(r: DebugRow): 'default' | 'secondary' | 'outline' | 'destructive' {
		if (r.kind === 'portTx' || r.kind === 'dumlTx') return 'destructive';
		if (r.kind === 'duml') return 'default';
		if (r.kind === 'marker') return 'secondary';
		return 'outline';
	}

	function rowLabel(r: DebugRow): string {
		if (r.kind === 'portTx') return 'port TX';
		if (r.kind === 'dumlTx') return 'duml TX';
		if (r.kind === 'marker') return 'mark';
		return r.kind;
	}
</script>

<main class="screen-fit flex flex-col gap-3 overflow-hidden p-3">
	<header class="flex shrink-0 items-center justify-between">
		<a href="/" class="h-11 px-3 inline-flex items-center text-sm text-muted-foreground">← Back</a>
		<h1 class="text-lg font-heading font-bold">Debug</h1>
		<span class="w-16"></span>
	</header>

	<Card class="shrink-0">
		<CardContent class="p-3 flex flex-wrap gap-2 items-center">
			<Button class="h-11" variant={$debugEnabled ? 'destructive' : 'default'} onclick={toggle}>
				{$debugEnabled ? 'Stop capture' : 'Start capture'}
			</Button>
			<Button class="h-11" variant="outline" onclick={() => debugPaused.update((v) => !v)}>
				{$debugPaused ? '▶ Resume' : '⏸ Pause'}
			</Button>
			<Button class="h-11" variant="outline" onclick={clearDebugRows}>Clear</Button>
			<select class="text-sm border rounded px-2 h-11 bg-background" bind:value={filterCmdSet}>
				<option value="all">all cmdSets</option>
				<option value="02">0x02 camera</option>
				<option value="03">0x03 OSD/FC</option>
				<option value="00">0x00 general</option>
				<option value="04">0x04 gimbal</option>
				<option value="06">0x06 RC</option>
				<option value="09">0x09</option>
				<option value="0a">0x0a wifi</option>
			</select>
			<Badge variant="secondary" class="text-sm py-1 px-2">{$debugRows.length} / {DEBUG_MAX_ROWS}</Badge>
			<a
				href="/debug/camera-matrix"
				class="inline-flex h-11 items-center rounded-lg border border-border px-3 text-sm"
			>
				Camera matrix →
			</a>
		</CardContent>
	</Card>

	<Card class="shrink-0">
		<CardHeader class="pb-2">
			<CardTitle class="text-sm">Cloud diagnostics</CardTitle>
		</CardHeader>
		<CardContent class="p-3 flex flex-col gap-2">
			<div class="flex flex-wrap gap-2 items-center text-xs text-muted-foreground">
				<Badge variant={$diagnosticsUploading ? 'default' : 'outline'}>
					upload {$diagnosticsUploading ? 'on' : 'off'}
				</Badge>
				<Badge variant="outline">sent {$diagnosticsStats.sent}</Badge>
				{#if $diagnosticsStats.failed > 0}
					<Badge variant="destructive">dropped {$diagnosticsStats.failed}</Badge>
				{/if}
				{#if $diagnosticsStats.lastError}
					<span class="text-destructive break-all">{$diagnosticsStats.lastError}</span>
				{/if}
			</div>
			{#if !DIAGNOSTICS_ENDPOINT_CONFIGURED}
				<p class="text-xs text-muted-foreground">
					No cloud endpoint is configured in this build — uploads are a no-op (nothing leaves the
					device). Toggle sharing in Settings; captures are queued but discarded until an endpoint
					is compiled in.
				</p>
			{:else}
				<p class="text-xs text-muted-foreground">
					Enable “Share anonymous diagnostics” in Settings to upload captures over HTTPS.
				</p>
			{/if}
			<div class="flex gap-2 items-center">
				<Input
					class="flex-1 h-11"
					placeholder="marker label (e.g. 'applied ISO 200')"
					bind:value={markerLabel}
				/>
				<Button class="h-11" variant="outline" onclick={addMarker}>Mark</Button>
			</div>
		</CardContent>
	</Card>

	<Card class="flex min-h-0 flex-1 flex-col overflow-hidden">
		<CardHeader class="shrink-0 py-2">
			<CardTitle class="text-sm">Frames</CardTitle>
		</CardHeader>
		<CardContent class="min-h-0 flex-1 overflow-auto p-0">
			<table class="w-full text-xs font-mono">
				<thead class="bg-muted sticky top-0">
					<tr>
						<th class="px-2 py-1 text-left">t</th>
						<th class="px-2 py-1 text-left">kind</th>
						<th class="px-2 py-1 text-left">meta</th>
						<th class="px-2 py-1 text-left">hex</th>
					</tr>
				</thead>
				<tbody>
					{#each filtered as r, i (i + '-' + r.atMs)}
						<tr class="border-t border-border align-top">
							<td class="px-2 py-1 text-muted-foreground whitespace-nowrap">{new Date(r.atMs).toISOString().slice(14, 23)}</td>
							<td class="px-2 py-1"><Badge variant={badgeVariant(r)}>{rowLabel(r)}</Badge></td>
							{#if r.kind === 'port' || r.kind === 'portTx'}
								<td class="px-2 py-1">0x{hex4(r.port)}</td>
								<td class="px-2 py-1 break-all">{r.hex.slice(0, 96)}{r.hex.length > 96 ? '…' : ''}</td>
							{:else if r.kind === 'duml' || r.kind === 'dumlTx'}
								<td class="px-2 py-1">
									{hex2(r.src)}→{hex2(r.dst)}
									cs={hex2(r.cmdSet)} ci={hex2(r.cmdId)}
									t={hex2(r.cmdType)} #{r.seq}
								</td>
								<td class="px-2 py-1 break-all">{r.payloadHex.slice(0, 96)}{r.payloadHex.length > 96 ? '…' : ''}</td>
							{:else}
								<td class="px-2 py-1" colspan="2">{r.label}</td>
							{/if}
						</tr>
					{/each}
					{#if filtered.length === 0}
						<tr><td colspan="4" class="px-2 py-4 text-center text-muted-foreground">
							{$debugEnabled ? 'waiting for frames…' : 'capture stopped — click Start'}
						</td></tr>
					{/if}
				</tbody>
			</table>
		</CardContent>
	</Card>
</main>
