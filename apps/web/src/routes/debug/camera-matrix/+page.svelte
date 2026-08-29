<script lang="ts">
	import { goto } from '$app/navigation';
	import { Button } from '$lib/components/ui/button';
	import { Card, CardContent, CardHeader, CardTitle } from '$lib/components/ui/card';
	import { Badge } from '$lib/components/ui/badge';
	import { Input } from '$lib/components/ui/input';
	import { settings } from '$lib/stores/settings';
	import { connection } from '$lib/stores/connection';
	import { syncCameraState } from '$lib/stores/camera';
	import {
		runCameraMatrix,
		reportToMarkdown,
		summarize,
		stepCount,
		matrixGroups,
		type MatrixGroup,
		type MatrixStep,
		type MatrixReport,
		type MatrixVerdict,
		scanColorProfiles,
		sweepFpsByte,
		sweepFormatByte,
		sweepColorProfile,
		setProfileByteManually,
		colorProfileCandidates,
		manualOnly,
		type ProfileCandidate,
		type ProfileScanRow
	} from '$lib/bench/camera-matrix';

	// Bench tool: sweeps every ISO / shutter / WB / resolution / fps candidate through a SET → GET
	// round-trip and reports what this air unit actually accepted. Dev-only, same gate as /debug.
	//
	// This page is intentionally not translated. It is a bench instrument for whoever is doing
	// protocol work, not a user-facing surface — and its output goes into English docs.

	$effect(() => {
		if (!$settings.developerMode) goto('/');
	});

	const conn = $derived($connection);
	const sessionId = $derived(conn.state === 'connected' ? conn.sessionId : null);

	let selected = $state<MatrixGroup[]>([...matrixGroups]);
	let notes = $state('');
	let stepDelayMs = $state(250);
	let videoSettleMs = $state(1200);

	let running = $state(false);
	let abortRequested = $state(false);
	let done = $state(0);
	let total = $state(0);
	let steps = $state<MatrixStep[]>([]);
	let report = $state<MatrixReport | null>(null);
	let copied = $state<string | null>(null);

	const plannedTotal = $derived(stepCount(selected));
	const summary = $derived(summarize(steps));

	// A full sweep is minutes of round-trips; give the operator the damage up front.
	const estimateS = $derived.by(() => {
		const videoSteps = selected.includes('videoFormat') ? 30 : 0;
		const otherSteps = plannedTotal - videoSteps;
		// Each step is one SET + settle + one GET; the calls themselves cost ~150 ms each.
		return Math.round((otherSteps * (stepDelayMs + 300) + videoSteps * (videoSettleMs + 300)) / 1000);
	});

	function toggle(g: MatrixGroup) {
		selected = selected.includes(g) ? selected.filter((x) => x !== g) : [...selected, g];
	}

	async function run() {
		if (!sessionId || running) return;
		running = true;
		abortRequested = false;
		steps = [];
		report = null;
		copied = null;
		done = 0;
		total = plannedTotal;

		try {
			const r = await runCameraMatrix({
				sessionId,
				groups: selected,
				cameraModel: conn.state === 'connected' ? conn.cameraModel : 'unknown',
				goggleModel: conn.state === 'connected' ? conn.goggleModel : 'unknown',
				droneModel: conn.state === 'connected' ? (conn.droneModel ?? null) : null,
				appVersion: typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : '0.0.0',
				notes,
				stepDelayMs,
				videoSettleMs,
				onStep: (s) => (steps = [...steps, s]),
				onProgress: (d, t) => {
					done = d;
					total = t;
				},
				shouldAbort: () => abortRequested
			});
			report = r;
			// Echo the whole run to logcat so `adb logcat` captures it even if the phone
			// never gets to a clipboard — the marker makes it greppable.
			console.log('[camera-matrix] REPORT_JSON ' + JSON.stringify(r));
		} catch (e) {
			console.error('[camera-matrix] run failed', e);
		} finally {
			running = false;
			// The sweep left the camera on its restored values; re-seed the UI from the hardware.
			if (sessionId) await syncCameraState(sessionId).catch(() => {});
		}
	}

	/**
	 * What gets copied. `steps` is the superset — the sweep feeds it through `onStep`, and so do the
	 * byte probes and the colour-profile writes, none of which produce a `MatrixReport` of their
	 * own. Building from `steps` rather than from `report.steps` is what keeps a census-only or
	 * probe-only session copyable at all; otherwise its findings existed solely on screen and in
	 * logcat, which is a poor place to leave the one result a run was made for.
	 */
	function currentReport(): MatrixReport {
		const base: MatrixReport = report ?? {
			startedAtMs: steps[0]?.atMs ?? Date.now(),
			finishedAtMs: Date.now(),
			cameraModel: conn.state === 'connected' ? conn.cameraModel : 'unknown',
			goggleModel: conn.state === 'connected' ? conn.goggleModel : 'unknown',
			droneModel: conn.state === 'connected' ? (conn.droneModel ?? null) : null,
			appVersion: typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : '0.0.0',
			notes,
			stateBefore: null,
			stateAfter: null,
			aborted: false,
			steps: []
		};
		return { ...base, finishedAtMs: Date.now(), steps };
	}

	async function copy(kind: 'markdown' | 'json') {
		if (steps.length === 0 && !report) return;
		const r = currentReport();
		const text = kind === 'markdown' ? reportToMarkdown(r) : JSON.stringify(r, null, 2);
		try {
			await navigator.clipboard.writeText(text);
			copied = kind;
		} catch {
			// Clipboard can be refused in the WebView; logcat is the fallback path.
			console.log('[camera-matrix] ' + kind.toUpperCase() + '\n' + text);
			copied = kind + ' (via logcat)';
		}
	}

	// Colour profile (D-Log & friends): separate from the sweep on purpose. These commands have
	// names from DJI's dictionary but no verified encoding, so the flow is scan (read-only) →
	// sweep only what answered → and, for the one candidate with no Get pair, a manual shot the
	// operator confirms with their own eyes.

	// fps byte probe: which byte actually selects 120 fps? `FPS_TO_BYTE` says 7, but 30/50/60/100
	// are all fps/10, which would make it 12. Race mode is the clean place to ask: it permits
	// 1080p100 and 120 and nothing else, so a byte that moves the format there is unambiguous.

	let fpsFrom = $state(0);
	let fpsTo = $state(16);
	let fpsBusy = $state(false);

	// Format byte census. The Lite's table was guessed and both axes were mislabelled, so on a unit
	// with modes we have no byte for the sweep is blind. Enumerate rather than guess.
	let fmtFrom = $state(0);
	let fmtTo = $state(127);
	let fmtFpsByte = $state(3);
	let fmtBusy = $state(false);

	async function runFormatByteProbe() {
		if (!sessionId || fmtBusy) return;
		fmtBusy = true;
		abortRequested = false;
		try {
			const found = await sweepFormatByte(
				sessionId,
				fmtFrom,
				fmtTo,
				fmtFpsByte,
				(st) => (steps = [...steps, st]),
				() => abortRequested
			);
			console.log('[camera-matrix] FORMAT_BYTE_SCAN ' + JSON.stringify(found));
		} catch (e) {
			console.error('[camera-matrix] format byte probe failed', e);
		} finally {
			fmtBusy = false;
			if (sessionId) await syncCameraState(sessionId).catch(() => {});
		}
	}

	async function runFpsByteProbe() {
		if (!sessionId || fpsBusy) return;
		fpsBusy = true;
		abortRequested = false;
		try {
			const found = await sweepFpsByte(
				sessionId,
				fpsFrom,
				fpsTo,
				(st) => (steps = [...steps, st]),
				() => abortRequested
			);
			console.log('[camera-matrix] FPS_BYTE_SCAN ' + JSON.stringify(found));
		} catch (e) {
			console.error('[camera-matrix] fps byte probe failed', e);
		} finally {
			fpsBusy = false;
			if (sessionId) await syncCameraState(sessionId).catch(() => {});
		}
	}

	let scanRows = $state<ProfileScanRow[] | null>(null);
	let scanning = $state(false);
	let profileBusy = $state<string | null>(null);
	let manualArmed = $state(false);
	let manualLog = $state<string[]>([]);

	const candidateByName = (n: string) => colorProfileCandidates.find((c) => c.name === n)!;

	async function scanProfiles() {
		if (!sessionId || scanning) return;
		scanning = true;
		try {
			scanRows = await scanColorProfiles(sessionId);
			console.log('[camera-matrix] PROFILE_SCAN ' + JSON.stringify(scanRows));
		} catch (e) {
			console.error('[camera-matrix] profile scan failed', e);
		} finally {
			scanning = false;
		}
	}

	async function sweepProfile(c: ProfileCandidate) {
		if (!sessionId || profileBusy) return;
		profileBusy = c.name;
		try {
			await sweepColorProfile(sessionId, c, (st) => (steps = [...steps, st]));
		} catch (e) {
			console.error('[camera-matrix] profile sweep failed', e);
		} finally {
			profileBusy = null;
			if (sessionId) await syncCameraState(sessionId).catch(() => {});
		}
	}

	async function manualShot(c: ProfileCandidate, value: number) {
		if (!sessionId || profileBusy) return;
		profileBusy = `${c.name}=${value}`;
		try {
			const r = await setProfileByteManually(sessionId, c, value);
			manualLog = [
				...manualLog,
				`${c.name} = ${value} → ${r.timedOut ? 'timeout' : r.ackHex || '(empty ack)'}`
			];
			console.log(`[camera-matrix] PROFILE_MANUAL ${c.name}=${value} ack=${r.ackHex} timedOut=${r.timedOut}`);
		} finally {
			profileBusy = null;
		}
	}

	const hex2 = (n: number) => n.toString(16).padStart(2, '0');

	function scanVariant(r: ProfileScanRow): 'default' | 'secondary' | 'outline' | 'destructive' {
		if (r.result === 'answered') return 'default';
		if (r.result === 'no-get') return 'secondary';
		if (r.result === 'silent') return 'outline';
		return 'destructive';
	}

	function verdictVariant(v: MatrixVerdict): 'default' | 'secondary' | 'outline' | 'destructive' {
		if (v === 'ok') return 'default';
		if (v === 'substituted') return 'secondary';
		if (v === 'unreadable') return 'outline';
		return 'destructive';
	}
</script>

<!-- Header pinned; everything below scrolls as one region. The Sweep card is tall enough that
     pinning it too could starve the step list on a short screen. -->
<main class="screen-fit flex flex-col overflow-hidden">
	<header class="flex shrink-0 items-center justify-between p-3 pb-0">
		<a href="/debug" class="inline-flex h-11 items-center px-3 text-sm text-muted-foreground">
			← Debug
		</a>
		<h1 class="font-heading text-lg font-bold">Camera matrix</h1>
		<span class="w-16"></span>
	</header>

	<!-- Every card below is `shrink-0`: this is a flex column, so without it the cards would be
	     compressed to fit the viewport instead of scrolling — and since Card is `overflow-hidden`,
	     the squeezed-out content (the per-candidate `try` buttons, most visibly) just disappears. -->
	<div class="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto p-3">
	{#if !sessionId}
		<Card class="shrink-0">
			<CardContent class="p-4 text-sm text-muted-foreground">
				Not connected. Plug the phone into the goggles and connect before running a sweep.
			</CardContent>
		</Card>
	{:else}
		<Card class="shrink-0">
			<CardHeader class="pb-2">
				<CardTitle class="text-sm">Target</CardTitle>
			</CardHeader>
			<CardContent class="flex flex-wrap items-center gap-2 p-3 pt-0 text-xs">
				<Badge variant="default">{conn.state === 'connected' ? conn.cameraModel : ''}</Badge>
				<Badge variant="outline">{conn.state === 'connected' ? conn.goggleModel : ''}</Badge>
				{#if conn.state === 'connected' && conn.droneModel}
					<Badge variant="outline">{conn.droneModel}</Badge>
				{/if}
			</CardContent>
		</Card>

		<Card class="shrink-0">
			<CardHeader class="pb-2">
				<CardTitle class="text-sm">Sweep</CardTitle>
			</CardHeader>
			<CardContent class="flex flex-col gap-3 p-3 pt-0">
				<div class="flex flex-wrap gap-2">
					{#each matrixGroups as g (g)}
						<button
							type="button"
							class="h-9 rounded-lg border px-3 text-xs font-medium transition-colors
								{selected.includes(g)
								? 'border-primary bg-primary/15 text-primary'
								: 'border-border text-muted-foreground'}"
							disabled={running}
							onclick={() => toggle(g)}
						>
							{g}
						</button>
					{/each}
				</div>

				<!-- The goggles' race mode pins the video format and refuses every other one, so a run
				     made in it measures the mode, not the air unit — and nothing on the wire says
				     which mode you were in. Record it here or the report is ambiguous later. -->
				<label class="flex flex-col gap-1 text-xs text-muted-foreground">
					conditions — goggle mode, above all
					<Input
						class="h-9"
						placeholder="e.g. goggles in NORMAL mode, props off"
						disabled={running}
						bind:value={notes}
					/>
				</label>

				<div class="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
					<label class="flex items-center gap-2">
						step delay
						<Input
							class="h-9 w-20"
							type="number"
							disabled={running}
							bind:value={stepDelayMs}
						/>
						ms
					</label>
					<label class="flex items-center gap-2">
						video settle
						<Input
							class="h-9 w-24"
							type="number"
							disabled={running}
							bind:value={videoSettleMs}
						/>
						ms
					</label>
				</div>

				<p class="text-xs text-muted-foreground">
					{plannedTotal} steps · roughly {estimateS}s. Exposure is forced to <strong>manual</strong>
					before the ISO/shutter sweeps (they are ignored in auto), and every setting is restored
					when the run ends — including on abort. Video-format steps restart the encoder, so the
					preview will blink and may go black on combos this unit doesn't support.
				</p>

				<div class="flex flex-wrap gap-2">
					<Button
						class="h-11"
						disabled={running || selected.length === 0 || notes.trim() === ''}
						onclick={run}
					>
						{running ? `Running ${done}/${total}…` : 'Run sweep'}
					</Button>
					{#if running}
						<Button
							class="h-11"
							variant="destructive"
							disabled={abortRequested}
							onclick={() => (abortRequested = true)}
						>
							{abortRequested ? 'Stopping…' : 'Abort'}
						</Button>
					{/if}
					<!-- Available as soon as there is anything to copy — a census or a probe never
					     produces a report of its own, and its results are just as much the point. -->
					{#if steps.length > 0}
						<Button class="h-11" variant="outline" onclick={() => copy('markdown')}>
							Copy Markdown
						</Button>
						<Button class="h-11" variant="outline" onclick={() => copy('json')}>Copy JSON</Button>
					{/if}
				</div>

				{#if notes.trim() === ''}
					<p class="text-xs text-destructive">
						Fill in the conditions first — a run whose goggle mode nobody wrote down cannot be
						interpreted afterwards.
					</p>
				{/if}

				{#if copied}
					<p class="text-xs text-primary">Copied {copied}.</p>
				{/if}

				{#if running || report}
					<div class="h-1.5 w-full overflow-hidden rounded-full bg-muted">
						<div
							class="h-full bg-primary transition-[width]"
							style="width: {total > 0 ? Math.round((done / total) * 100) : 0}%"
						></div>
					</div>
				{/if}
			</CardContent>
		</Card>

		<Card class="shrink-0">
			<CardHeader class="pb-2">
				<CardTitle class="text-sm">Video format — byte census</CardTitle>
			</CardHeader>
			<CardContent class="flex flex-col gap-3 p-3 pt-0">
				<p class="text-xs text-muted-foreground">
					Walks the <strong>format byte</strong> at one fixed fps and records every value this unit
					accepts. Run this <strong>before</strong> the sweep on any air unit whose modes we haven't
					mapped: the sweep can only try bytes we already believe in, so a mode we have no byte for
					is invisible to it. On the Lite that blind spot cost us a wrong table twice over.
				</p>
				<div class="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
					<label class="flex items-center gap-2">
						from
						<Input class="h-9 w-16" type="number" disabled={fmtBusy} bind:value={fmtFrom} />
					</label>
					<label class="flex items-center gap-2">
						to
						<Input class="h-9 w-16" type="number" disabled={fmtBusy} bind:value={fmtTo} />
					</label>
					<label class="flex items-center gap-2">
						fps byte
						<Input class="h-9 w-16" type="number" disabled={fmtBusy} bind:value={fmtFpsByte} />
					</label>
				</div>
				<p class="text-xs text-muted-foreground">
					fps byte 3 = 30 fps, which every mode should reach — use it for a full census. Use 7 (120
					fps) to isolate which modes reach the top of the ladder.
				</p>
				<div class="flex flex-wrap gap-2">
					<Button
						class="h-11"
						variant="outline"
						disabled={fmtBusy || running}
						onclick={runFormatByteProbe}
					>
						{fmtBusy ? 'Scanning…' : `Census bytes ${fmtFrom}–${fmtTo}`}
					</Button>
					{#if fmtBusy}
						<Button
							class="h-11"
							variant="destructive"
							disabled={abortRequested}
							onclick={() => (abortRequested = true)}
						>
							Abort
						</Button>
					{/if}
				</div>
				<p class="text-xs text-muted-foreground">
					Roughly {Math.round(((fmtTo - fmtFrom + 1) * 1.2) / 60)}&nbsp;min for this range. Results
					land in Steps under group <code class="font-mono">formatByte</code>; every
					<code class="font-mono">ok</code> is a real mode of this unit.
				</p>
			</CardContent>
		</Card>

		<Card class="shrink-0">
			<CardHeader class="pb-2">
				<CardTitle class="text-sm">Video format — fps byte probe</CardTitle>
			</CardHeader>
			<CardContent class="flex flex-col gap-3 p-3 pt-0">
				<p class="text-xs text-muted-foreground">
					Holds resolution and codec at whatever the camera reports right now and walks only the
					<strong>fps byte</strong>, restoring the original pair afterwards. <code class="font-mono"
						>FPS_TO_BYTE</code
					>
					claims 120&nbsp;fps is byte&nbsp;7, but 30/50/60/100 are all fps÷10 — which would make it
					12. A byte that reads back unchanged is the one this unit really uses.
				</p>
				<div class="flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
					<label class="flex items-center gap-2">
						from
						<Input class="h-9 w-16" type="number" disabled={fpsBusy} bind:value={fpsFrom} />
					</label>
					<label class="flex items-center gap-2">
						to
						<Input class="h-9 w-16" type="number" disabled={fpsBusy} bind:value={fpsTo} />
					</label>
				</div>
				<div class="flex flex-wrap gap-2">
					<Button class="h-11" variant="outline" disabled={fpsBusy || running} onclick={runFpsByteProbe}>
						{fpsBusy ? 'Probing…' : `Probe fps bytes ${fpsFrom}–${fpsTo}`}
					</Button>
					{#if fpsBusy}
						<Button
							class="h-11"
							variant="destructive"
							disabled={abortRequested}
							onclick={() => (abortRequested = true)}
						>
							Abort
						</Button>
					{/if}
				</div>
				<p class="text-xs text-muted-foreground">
					Results land in the Steps table below, under group <code class="font-mono">fpsByte</code>.
					Each step restarts the encoder, so the preview blinks — roughly {(fpsTo - fpsFrom + 1) *
						1.5}s for this range.
				</p>
			</CardContent>
		</Card>

		<Card class="shrink-0">
			<CardHeader class="pb-2">
				<CardTitle class="text-sm">Colour profile — discovery</CardTitle>
			</CardHeader>
			<CardContent class="flex flex-col gap-3 p-3 pt-0">
				<p class="text-xs text-muted-foreground">
					Can this unit be switched from the normal picture profile to D-Log / HLG? Nobody knows
					yet — none of these commands has a verified encoding. They are cmdIds seen in this
					territory; nothing is known about their values.
				</p>

				<Button class="h-11" variant="outline" disabled={scanning || !!profileBusy} onclick={scanProfiles}>
					{scanning ? 'Scanning…' : 'Scan candidates (read-only)'}
				</Button>

				{#if scanRows}
					<!-- A list, not a table: on a phone the four columns squeezed the action button into a
					     sliver and it read as decoration. Each candidate gets its own block with the button
					     at full size, because pressing it is the whole point of the card. -->
					<div class="flex flex-col gap-2">
						{#each scanRows as r (r.name)}
							{@const c = candidateByName(r.name)}
							<div class="flex flex-col gap-1.5 rounded-lg border p-2.5">
								<div class="flex flex-wrap items-center gap-2">
									<span class="text-xs font-medium">{r.name}</span>
									<span class="font-mono text-[11px] text-dim">
										set 0x{hex2(r.setId)}{r.getId === null ? '' : ` · get 0x${hex2(r.getId)}`}
									</span>
									<Badge variant={scanVariant(r)}>{r.result}</Badge>
									{#if r.ackHex}
										<span class="break-all font-mono text-[11px] text-muted-foreground">
											{r.ackHex}
										</span>
									{/if}
								</div>
								<span class="text-[11px] leading-snug text-dim">{c.note}</span>
								{#if r.result === 'answered'}
									<Button
										class="h-11 w-full"
										variant="outline"
										disabled={!!profileBusy}
										onclick={() => sweepProfile(c)}
									>
										{profileBusy === r.name
											? 'Trying…'
											: `Try ${c.values.length} values (${c.values.join(', ')})`}
									</Button>
								{/if}
							</div>
						{/each}
					</div>

					<p class="text-xs text-muted-foreground">
						<strong>try</strong> writes each candidate value, reads it back, and restores whatever
						the command held before. Results land in the Steps table below and in the report. Only
						commands whose Get answered can be written this way — everything else stays untouched.
					</p>

					{#each colorProfileCandidates.filter(manualOnly) as c (c.name)}
						<div class="flex flex-col gap-2 rounded-lg border border-destructive/40 p-3">
							<span class="text-xs font-medium">{c.name} · 0x{hex2(c.setId)} — no Get pair</span>
							<span class="text-[11px] text-muted-foreground">
								This one cannot be read back or restored by code. Send a value, then
								<strong>watch the video</strong>: a log profile looks flat and washed out. Undo it by
								sending the other value yourself. Do this on the bench, never before a flight.
							</span>
							<label class="flex items-center gap-2 text-[11px] text-muted-foreground">
								<input type="checkbox" bind:checked={manualArmed} />
								I understand this is not reversible by the app
							</label>
							<div class="flex flex-wrap gap-2">
								{#each c.values as v (v)}
									<Button
										class="h-9 px-3 text-xs"
										variant="destructive"
										disabled={!manualArmed || !!profileBusy}
										onclick={() => manualShot(c, v)}
									>
										send {v}
									</Button>
								{/each}
							</div>
							{#each manualLog as line, i (i)}
								<span class="font-mono text-[11px] text-muted-foreground">{line}</span>
							{/each}
						</div>
					{/each}
				{/if}
			</CardContent>
		</Card>

		{#if steps.length > 0}
			<Card class="shrink-0">
				<CardHeader class="pb-2">
					<CardTitle class="text-sm">Summary</CardTitle>
				</CardHeader>
				<CardContent class="overflow-x-auto p-0">
					<table class="w-full text-xs">
						<thead class="bg-muted">
							<tr>
								<th class="px-2 py-1 text-left">group</th>
								<th class="px-2 py-1 text-right">ok</th>
								<th class="px-2 py-1 text-right">subst.</th>
								<th class="px-2 py-1 text-right">rejected</th>
								<th class="px-2 py-1 text-right">unread.</th>
								<th class="px-2 py-1 text-right">error</th>
							</tr>
						</thead>
						<tbody>
							{#each Object.entries(summary) as [group, c] (group)}
								<tr class="border-t border-border font-mono">
									<td class="px-2 py-1">{group}</td>
									<td class="px-2 py-1 text-right">{c.ok}</td>
									<td class="px-2 py-1 text-right">{c.substituted}</td>
									<td class="px-2 py-1 text-right">{c.rejected}</td>
									<td class="px-2 py-1 text-right">{c.unreadable}</td>
									<td class="px-2 py-1 text-right">{c.error}</td>
								</tr>
							{/each}
						</tbody>
					</table>
				</CardContent>
			</Card>

			<Card class="shrink-0 overflow-hidden">
				<CardHeader class="py-2">
					<CardTitle class="text-sm">Steps</CardTitle>
				</CardHeader>
				<CardContent class="overflow-x-auto p-0">
					<table class="w-full min-w-[30rem] font-mono text-xs">
						<thead class="sticky top-0 bg-muted">
							<tr>
								<th class="px-2 py-1 text-left">value</th>
								<th class="px-2 py-1 text-left">verdict</th>
								<th class="px-2 py-1 text-left">readback</th>
								<th class="px-2 py-1 text-left">SET / GET ack</th>
							</tr>
						</thead>
						<tbody>
							{#each steps as s, i (i + '-' + s.label)}
								<tr class="border-t border-border align-top">
									<td class="whitespace-nowrap px-2 py-1">{s.label}</td>
									<td class="px-2 py-1">
										<Badge variant={verdictVariant(s.verdict)}>{s.verdict}</Badge>
									</td>
									<td class="px-2 py-1">{s.readback ?? '—'}</td>
									<td class="break-all px-2 py-1 text-muted-foreground">
										{s.setTimedOut ? 'timeout' : s.setAckHex || '(empty)'}
										/
										{s.getTimedOut ? 'timeout' : s.getAckHex || '(empty)'}
									</td>
								</tr>
							{/each}
						</tbody>
					</table>
				</CardContent>
			</Card>
		{/if}
	{/if}
	</div>
</main>
