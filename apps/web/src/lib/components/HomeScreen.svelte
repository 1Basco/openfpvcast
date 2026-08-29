<script lang="ts">
	import ClockIcon from '@lucide/svelte/icons/clock';
	import VideoIcon from '@lucide/svelte/icons/video';
	import SettingsIcon from '@lucide/svelte/icons/settings';
	import TerminalIcon from '@lucide/svelte/icons/terminal';
	import { toast } from 'svelte-sonner';

	import GogglesIcon from '$lib/components/common/GogglesIcon.svelte';
	import SectionLabel from '$lib/components/common/SectionLabel.svelte';
	import NavRow from '$lib/components/common/NavRow.svelte';
	import CameraSummary from '$lib/components/camera/CameraSummary.svelte';
	import RecordingSettings from '$lib/components/RecordingSettings.svelte';

	import { connection, goggleDescription, cameraModel, connect } from '$lib/stores/connection';
	import { settings } from '$lib/stores/settings';
	import { getActiveLocale, localeLabels } from '$lib/i18n.svelte';
	import * as m from '$lib/paraglide/messages';

	// Home: the screen you see whenever video is not flowing. Connected, it is the settings hub;
	// disconnected, it is a single call to action.

	let connecting = $state(false);

	const connState = $derived($connection.state);
	const isConnected = $derived(connState === 'connected');

	const statusLabel = $derived(
		connState === 'connected'
			? m.conn_connected()
			: connState === 'attached'
				? m.conn_gogglesDetected()
				: connState === 'requesting_permission'
					? m.conn_requestingPermission()
					: m.conn_noAccessory()
	);

	async function onConnect() {
		connecting = true;
		try {
			await connect();
		} catch (e) {
			toast.error(m.conn_connectFailed({ error: e instanceof Error ? e.message : String(e) }));
		} finally {
			connecting = false;
		}
	}
</script>

<!-- The screen is exactly one viewport tall and never scrolls as a whole: the header stays at the
     top and the app-navigation block at the bottom, so only the middle — the part whose height
     actually depends on connection state and camera values — gets a scrollbar when it needs one. -->
<main class="screen-fit flex flex-col overflow-hidden">
	<header class="flex h-14 shrink-0 items-center justify-between gap-3 px-4">
		<span class="font-heading text-[17px] font-semibold tracking-tight">Open FPV Cast</span>
		<span
			class="flex h-7 items-center gap-2 rounded-full px-3 {isConnected
				? 'bg-primary/16'
				: 'bg-secondary'}"
		>
			<span class="size-[7px] rounded-full {isConnected ? 'bg-primary' : 'bg-label'}"></span>
			<span
				class="text-xs {isConnected
					? 'font-semibold text-primary'
					: 'font-medium text-muted-foreground'}"
			>
				{statusLabel}
			</span>
		</span>
	</header>
	<div class="hairline shrink-0"></div>

	<div class="flex min-h-0 flex-1 flex-col overflow-y-auto">
		{#if isConnected}
			<div class="flex items-center gap-3 px-4 py-3.5">
				<span class="flex size-9.5 shrink-0 items-center justify-center rounded-[10px] bg-secondary">
					<GogglesIcon class="size-5 text-muted-foreground" />
				</span>
				<span class="flex min-w-0 flex-col gap-0.5">
					<span class="truncate text-[15px] font-medium">
						{$goggleDescription ?? m.conn_connected()}
					</span>
					{#if $cameraModel}
						<span class="font-mono text-[11px] tracking-wide text-muted-foreground">
							{$cameraModel.toUpperCase()}
						</span>
					{/if}
				</span>
			</div>

			<div class="px-4 pb-3.5">
				<div class="flex items-center gap-2.5 rounded-[10px] bg-card px-3 py-2.5">
					<ClockIcon class="size-4 shrink-0 text-label" />
					<span class="text-[12.5px] leading-snug text-muted-foreground text-pretty">
						{m.conn_waitingVideoShort()}
					</span>
				</div>
			</div>

			<div class="px-4">
				<CameraSummary />
			</div>

			<section class="mt-6 flex flex-col gap-1 px-4 pb-4">
				<SectionLabel>{m.rec_section()}</SectionLabel>
				<RecordingSettings />
			</section>
		{:else}
			<div class="flex flex-1 flex-col items-center justify-center gap-5 px-6 py-10">
				<span class="flex size-19 items-center justify-center rounded-[22px] border bg-card">
					<GogglesIcon class="size-9 text-label" strokeWidth={1.4} />
				</span>
				<span class="flex flex-col items-center gap-2">
					<span class="font-heading text-[19px] font-semibold tracking-tight">
						{m.conn_heroTitle()}
					</span>
					<span class="text-center text-[13.5px] leading-relaxed text-muted-foreground text-pretty">
						{m.conn_heroBody()}
					</span>
				</span>
				<button
					type="button"
					class="h-13 w-full rounded-xl bg-primary text-[15px] font-semibold text-primary-foreground disabled:opacity-60"
					disabled={connecting || connState === 'requesting_permission'}
					onclick={onConnect}
				>
					{connState === 'attached' ? m.conn_connect() : m.conn_tryConnect()}
				</button>
			</div>
		{/if}
	</div>

	<!-- app navigation: pinned to the bottom edge so it stays reachable without scrolling -->
	<div class="hairline shrink-0"></div>
	<section class="flex shrink-0 flex-col gap-1 px-4 pb-3 pt-3">
		<SectionLabel>{m.settings_appTitle()}</SectionLabel>
		<div class="flex flex-col">
			<NavRow label={m.nav_recordings()} href="/recordings/">
				{#snippet icon()}
					<VideoIcon class="size-4.5 shrink-0 text-muted-foreground" />
				{/snippet}
			</NavRow>
			<div class="hairline"></div>
			<NavRow
				label={m.nav_appSettings()}
				href="/settings/"
				value={localeLabels[getActiveLocale()]}
			>
				{#snippet icon()}
					<SettingsIcon class="size-4.5 shrink-0 text-muted-foreground" />
				{/snippet}
			</NavRow>
			{#if $settings.developerMode}
				<div class="hairline"></div>
				<NavRow label={m.nav_debug()} href="/debug/">
					{#snippet icon()}
						<TerminalIcon class="size-4.5 shrink-0 text-muted-foreground" />
					{/snippet}
				</NavRow>
			{/if}
		</div>
	</section>
</main>
