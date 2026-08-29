<script lang="ts">
	import CheckIcon from '@lucide/svelte/icons/check';
	import MinusIcon from '@lucide/svelte/icons/minus';
	import PlusIcon from '@lucide/svelte/icons/plus';
	import ChevronRightIcon from '@lucide/svelte/icons/chevron-right';
	import LockIcon from '@lucide/svelte/icons/lock';
	import LoaderIcon from '@lucide/svelte/icons/loader-circle';
	import { toast } from 'svelte-sonner';

	import { Slider } from '$lib/components/ui/slider';
	import SectionLabel from '$lib/components/common/SectionLabel.svelte';
	import Segmented from '$lib/components/common/Segmented.svelte';
	import ValueStrip from '$lib/components/common/ValueStrip.svelte';

	import { connection, cameraModel } from '$lib/stores/connection';
	import { cameraState, cameraActions } from '$lib/stores/camera';
	import {
		exposureModes,
		type Iso,
		type IsoState,
		type ShutterDenominator,
		type ExposureMode,
		type VideoAspect,
		type VideoResolution,
		type VideoFps,
		type SendCameraCommandResult
	} from '$lib/aoa';
	import {
		capabilitiesFor,
		isFallback,
		aspectsOf,
		resolutionsFor,
		fpsFor,
		nearestMode
	} from '$lib/schemas/camera-capabilities';
	import * as m from '$lib/paraglide/messages';

	/**
	 * `compact` is the in-stream panel (a narrow column beside the video): only what gets
	 * touched in flight, with the video format tucked behind a disclosure because applying it
	 * restarts the feed. The full variant is the /camera screen.
	 *
	 * There are no "Apply" buttons in either variant — tapping a value sends it and the footer
	 * line confirms what went out.
	 */
	let { compact = false }: { compact?: boolean } = $props();

	const cam = $derived($cameraState);
	const connected = $derived($connection.state === 'connected');

	/**
	 * Everything offered below comes from the attached camera's measured capabilities, never from
	 * the full protocol lists. A Lite and a Pro do not accept the same video modes, and the old UI
	 * offered both the same set — including an H.265 codec control that no unit ever accepted.
	 *
	 * When the model is unknown this falls back to the intersection of the units we have measured,
	 * and says so on screen rather than passing it off as this camera's real envelope.
	 */
	const caps = $derived(capabilitiesFor($cameraModel));
	const usingFallback = $derived(isFallback($cameraModel));

	const isoOptions = $derived(caps.iso);
	const shutterOptions = $derived(caps.shutter);
	const aspectOptions = $derived(aspectsOf(caps));
	const resolutionOptions = $derived(resolutionsFor(caps, cam.aspect));
	const fpsOptions = $derived(fpsFor(caps, cam.aspect, cam.resolution));

	const wbKelvinMin = $derived(caps.wbKelvin.min);
	const wbKelvinMax = $derived(caps.wbKelvin.max);
	const wbKelvinStep = $derived(caps.wbKelvin.step);

	let busy = $state<string | null>(null);
	let sent = $state<string | null>(null);
	let videoOpen = $state(false);

	const videoSummary = $derived(`${cam.resolution} · ${cam.fps}`);
	const isoLabel = $derived(
		typeof cam.iso === 'number'
			? String(cam.iso)
			: cam.iso === 'auto-high'
				? m.cam_isoAutoMax()
				: 'auto'
	);
	const wbLabel = $derived(cam.wbMode === 'auto' ? 'auto' : `${cam.wbKelvin}K`);
	const selectedIso = $derived(typeof cam.iso === 'number' ? (cam.iso as Iso) : null);

	async function run(label: string, fn: (sessionId: string) => Promise<SendCameraCommandResult>) {
		if ($connection.state !== 'connected') {
			toast.warning(m.cam_connect());
			return;
		}
		const id = $connection.sessionId;
		busy = label;
		try {
			const r = await fn(id);
			if (r.timedOut) {
				toast.warning(`${label}: timeout`);
				return;
			}
			sent = label;
		} catch (e) {
			toast.error(`${label}: ${e instanceof Error ? e.message : String(e)}`);
		} finally {
			busy = null;
		}
	}

	const setMode = (mode: ExposureMode) =>
		run(`${m.cam_mode()} ${mode}`, (id) => cameraActions.exposureMode(id, mode));
	const setIso = (iso: IsoState) => run(`ISO ${iso}`, (id) => cameraActions.iso(id, iso));
	const setShutter = (s: ShutterDenominator) =>
		run(`${m.cam_shutterName()} 1/${s}`, (id) => cameraActions.shutter(id, s));
	const setWb = (mode: 'auto' | 'custom', kelvin = cam.wbKelvin) =>
		run(`WB ${mode === 'auto' ? 'auto' : `${kelvin}K`}`, (id) =>
			cameraActions.wb(id, mode, kelvin)
		);
	const lockWb = () => run(`WB ${m.cam_wbLock()}`, (id) => cameraActions.lockWb(id));
	const nudgeWb = (delta: number) =>
		setWb(
			'custom',
			Math.min(wbKelvinMax, Math.max(wbKelvinMin, cam.wbKelvin + delta * wbKelvinStep * 5))
		);
	const setVideo = (
		aspect: VideoAspect = cam.aspect,
		resolution: VideoResolution = cam.resolution,
		fps: VideoFps = cam.fps
	) => {
		// Picking 4:3 while at 120 fps, or 4K on a Lite, can name a combination this unit does not
		// have. Snap to the nearest legal one instead of sending something we know it will refuse.
		const mode = nearestMode(caps, aspect, resolution, fps);
		if (!mode) return;
		return run(`${m.cam_videoFormat()} ${mode.aspect} ${mode.resolution}/${mode.fps}`, (id) =>
			cameraActions.videoFormat(id, mode.aspect, mode.resolution, mode.fps)
		);
	};
</script>

{#snippet exposureSection()}
	<div class="flex flex-col gap-2">
		<SectionLabel>{m.cam_exposure()}</SectionLabel>
		<Segmented
			options={exposureModes}
			value={cam.exposureMode}
			onselect={setMode}
			size={compact ? 'sm' : 'md'}
			disabled={!connected}
		/>
	</div>
{/snippet}

{#snippet valueHeader(label: string, value: string)}
	<div class="flex items-baseline justify-between gap-2">
		<SectionLabel>{label}</SectionLabel>
		<span class="font-mono text-[13px]">{value}</span>
	</div>
{/snippet}

{#snippet modeChip(label: string, active: boolean, onclick: () => void)}
	<button
		type="button"
		disabled={!connected}
		class="h-10 flex-1 rounded-lg border text-[13px] font-medium disabled:opacity-50 {active
			? 'border-primary bg-primary text-primary-foreground'
			: 'bg-card text-muted-foreground'}"
		aria-pressed={active}
		{onclick}
	>
		{label}
	</button>
{/snippet}

{#snippet isoSection()}
	<div class="flex flex-col gap-2.5">
		{@render valueHeader('ISO', isoLabel)}
		<!-- The camera's own auto modes (ISO bytes 0x00 / 0x01), not a UI convenience: picking one
		     hands the choice back to the camera, so no chip in the strip below stays selected. -->
		<div class="flex gap-1.5">
			{@render modeChip('auto', cam.iso === 'auto', () => setIso('auto'))}
			{@render modeChip(m.cam_isoAutoMax(), cam.iso === 'auto-high', () => setIso('auto-high'))}
		</div>
		<ValueStrip
			options={isoOptions}
			value={selectedIso}
			onselect={setIso}
			minWidth={compact ? 54 : 62}
			height={compact ? 44 : 48}
			bleed={!compact}
			disabled={!connected}
		/>
	</div>
{/snippet}

{#snippet shutterSection()}
	<div class="flex flex-col gap-2.5">
		{@render valueHeader(m.cam_shutterName(), `1/${cam.shutter}`)}
		<ValueStrip
			options={shutterOptions}
			value={cam.shutter}
			onselect={setShutter}
			format={(v) => `1/${v}`}
			minWidth={compact ? 58 : 66}
			height={compact ? 44 : 48}
			bleed={!compact}
			disabled={!connected}
		/>
		{#if !compact}
			<span class="text-[11.5px] text-dim">
				{m.cam_shutterHint({
					count: shutterOptions.length,
					fastest: shutterOptions[0]
				})}
			</span>
		{/if}
	</div>
{/snippet}

{#snippet wbSection()}
	<div class="flex flex-col gap-2.5">
		{@render valueHeader(m.cam_whiteBalance(), wbLabel)}
		{#if compact}
			<div class="flex gap-1.5">
				<button
					type="button"
					disabled={!connected}
					class="h-10 flex-1 rounded-lg border text-[13px] font-medium disabled:opacity-50 {cam.wbMode ===
					'auto'
						? 'border-primary bg-primary text-primary-foreground'
						: 'bg-card text-muted-foreground'}"
					onclick={() => setWb('auto')}
				>
					auto
				</button>
				<button
					type="button"
					disabled={!connected}
					class="h-10 flex-1 rounded-lg border text-[13px] font-medium disabled:opacity-50 {cam.wbMode ===
					'custom'
						? 'border-primary bg-primary text-primary-foreground'
						: 'bg-card text-muted-foreground'}"
					onclick={() => setWb('custom')}
				>
					manual
				</button>
				{#if cam.wbMode === 'auto'}
					<button
						type="button"
						aria-label={m.cam_wbLock()}
						disabled={!connected}
						class="flex h-10 w-11 shrink-0 items-center justify-center rounded-lg border bg-card text-muted-foreground disabled:opacity-40"
						onclick={lockWb}
					>
						<LockIcon class="size-4" />
					</button>
				{:else}
					<button
						type="button"
						aria-label="-500K"
						disabled={!connected}
						class="flex h-10 w-11 shrink-0 items-center justify-center rounded-lg border bg-card text-muted-foreground disabled:opacity-40"
						onclick={() => nudgeWb(-1)}
					>
						<MinusIcon class="size-4" />
					</button>
					<button
						type="button"
						aria-label="+500K"
						disabled={!connected}
						class="flex h-10 w-11 shrink-0 items-center justify-center rounded-lg border bg-card text-muted-foreground disabled:opacity-40"
						onclick={() => nudgeWb(1)}
					>
						<PlusIcon class="size-4" />
					</button>
				{/if}
			</div>
		{:else}
			<Segmented
				options={['auto', 'custom'] as const}
				value={cam.wbMode}
				onselect={(v) => setWb(v)}
				format={(v) => (v === 'auto' ? 'auto' : 'manual')}
				disabled={!connected}
			/>
			{#if cam.wbMode === 'auto'}
				<!-- The bridge between the two modes: instead of jumping to whatever the slider last
				     held, take the temperature auto is using right now and freeze it. -->
				<button
					type="button"
					disabled={!connected}
					class="flex h-11 items-center justify-center gap-2 rounded-xl border bg-card text-[13.5px] font-medium text-foreground disabled:opacity-50"
					onclick={lockWb}
				>
					<LockIcon class="size-4 text-muted-foreground" />
					{m.cam_wbLock()}
				</button>
				<span class="text-[11.5px] text-dim">{m.cam_wbLockHint()}</span>
			{:else}
				<div class="space-y-1.5 pt-1">
					<Slider
						type="single"
						min={wbKelvinMin}
						max={wbKelvinMax}
						step={wbKelvinStep}
						value={cam.wbKelvin}
						disabled={!connected}
						onValueCommit={(v) => setWb('custom', v as number)}
					/>
					<div class="flex justify-between font-mono text-[10.5px] text-dim">
						<span>{wbKelvinMin}K</span>
						<span>{wbKelvinMax}K</span>
					</div>
				</div>
			{/if}
		{/if}
	</div>
{/snippet}

{#snippet videoSection()}
	<div class="flex flex-col gap-4">
		<SectionLabel>{m.cam_video()}</SectionLabel>

		<!-- The wire's format byte selects aspect × resolution, not resolution × codec — the codec is
		     not in this command at all. The old "Codec: h264/h265/pro" control was offering values the
		     Lite refuses outright; it is gone rather than renamed. -->
		<div class="flex flex-col gap-2">
			<span class="text-[12.5px] text-muted-foreground">{m.cam_resolution()}</span>
			<Segmented
				options={resolutionOptions}
				value={cam.resolution}
				onselect={(r) => setVideo(cam.aspect, r)}
				size={compact ? 'sm' : 'md'}
				disabled={!connected}
			/>
		</div>

		<div class="flex flex-col gap-2">
			<span class="text-[12.5px] text-muted-foreground">{m.cam_aspect()}</span>
			<Segmented
				options={aspectOptions}
				value={cam.aspect}
				onselect={(a) => setVideo(a)}
				size={compact ? 'sm' : 'md'}
				disabled={!connected}
			/>
		</div>

		<div class="flex flex-col gap-2">
			<span class="text-[12.5px] text-muted-foreground">FPS</span>
			<Segmented
				options={fpsOptions}
				value={cam.fps}
				onselect={(f) => setVideo(cam.aspect, cam.resolution, f)}
				size={compact ? 'sm' : 'md'}
				disabled={!connected}
			/>
		</div>
	</div>
{/snippet}

{#snippet footerLine()}
	{#if busy}
		<div class="flex items-center gap-2.5 rounded-lg bg-card px-3 py-2.5">
			<LoaderIcon class="size-4 shrink-0 animate-spin text-muted-foreground" />
			<span class="text-[12.5px] text-muted-foreground">{m.cam_sending({ label: busy })}</span>
		</div>
	{:else if sent}
		<div class="flex items-center gap-2.5 rounded-lg bg-card px-3 py-2.5">
			<CheckIcon class="size-4 shrink-0 text-primary" />
			<span class="text-[12.5px] text-muted-foreground">
				{m.cam_sentToGoggles()}
				<span class="font-mono text-foreground">{sent}</span>
			</span>
		</div>
	{:else if !connected}
		<div class="rounded-lg bg-card px-3 py-2.5 text-[12.5px] text-muted-foreground">
			{m.cam_notConnected()}
		</div>
	{/if}
{/snippet}

{#if compact}
	<div class="flex flex-col gap-3">
		{@render exposureSection()}
		{@render isoSection()}
		{@render shutterSection()}
		{@render wbSection()}

		<div class="hairline"></div>

		<button
			type="button"
			class="flex items-center gap-2.5 text-left"
			aria-expanded={videoOpen}
			onclick={() => (videoOpen = !videoOpen)}
		>
			<span class="flex flex-1 flex-col gap-0.5">
				<span class="text-[13px] font-medium">{m.cam_videoFormat()}</span>
				<span class="text-[10.5px] text-dim">{m.cam_videoRestartsStream()}</span>
			</span>
			<span class="rounded-lg bg-secondary px-2.5 py-1 font-mono text-[11.5px] text-muted-foreground">
				{videoSummary}
			</span>
			<ChevronRightIcon class="size-4 shrink-0 text-dim transition-transform {videoOpen ? 'rotate-90' : ''}" />
		</button>

		{#if videoOpen}
			{@render videoSection()}
		{/if}

		{@render footerLine()}
	</div>
{:else}
	<div class="flex flex-col gap-5">
		{#if usingFallback}
			<p class="rounded-lg bg-card px-3 py-2.5 text-[12.5px] text-muted-foreground">
				{m.cam_modelNotDetected()}
			</p>
		{/if}

		{@render exposureSection()}
		{@render isoSection()}
		{@render shutterSection()}
		{@render wbSection()}

		<div class="hairline"></div>

		{@render videoSection()}
		{@render footerLine()}
	</div>
{/if}
