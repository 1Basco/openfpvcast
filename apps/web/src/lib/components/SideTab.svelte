<script lang="ts">
	import VideoIcon from '@lucide/svelte/icons/video';
	import SettingsIcon from '@lucide/svelte/icons/settings';
	import TerminalIcon from '@lucide/svelte/icons/terminal';

	import { Switch } from '$lib/components/ui/switch';
	import SettingRow from '$lib/components/common/SettingRow.svelte';
	import SectionLabel from '$lib/components/common/SectionLabel.svelte';
	import NavRow from '$lib/components/common/NavRow.svelte';
	import { settings, patchSettings, type SideTabSection } from '$lib/stores/settings';
	import { isRecording } from '$lib/stores/recording';
	import CameraControls from '$lib/components/camera/CameraControls.svelte';
	import RecordingControls from '$lib/components/RecordingControls.svelte';
	import BroadcastPanel from '$lib/components/broadcast/BroadcastPanel.svelte';
	import * as m from '$lib/paraglide/messages';

	// Layout-based, non-modal panel: the rail stays visible at the screen edge and the panel
	// expands by animating width (landscape) / height (portrait). Because it participates in
	// the flex layout, the video section shrinks beside it and VideoPreview's ResizeObserver
	// repositions the native SurfaceView — no z-order fight with the WebView.

	const open = $derived($settings.ui.sideTabOpen);
	const section = $derived($settings.ui.sideTabSection);

	function toggle(next: SideTabSection) {
		if (open && section === next) {
			patchSettings({ ui: { ...$settings.ui, sideTabOpen: false } });
		} else {
			patchSettings({ ui: { ...$settings.ui, sideTabOpen: true, sideTabSection: next } });
		}
	}

	const tabBase =
		'relative flex flex-1 items-center justify-center rounded-xl border text-[13.5px] transition-colors landscape:h-26 landscape:w-11 landscape:flex-none landscape:text-[13px] landscape:[writing-mode:vertical-rl]';
</script>

<aside
	class="flex shrink-0 flex-col-reverse border-t bg-background max-h-[60dvh] landscape:max-h-none landscape:max-w-[75vw] landscape:flex-row landscape:border-l landscape:border-t-0"
>
	<div
		class="overflow-x-hidden overflow-y-auto transition-all duration-200 {open
			? 'h-[45dvh] landscape:h-auto landscape:w-80'
			: 'h-0 landscape:h-auto landscape:w-0'}"
	>
		<div class="w-full p-3.5 landscape:w-80">
			{#if section === 'camera'}
				<CameraControls compact />
			{:else}
				<div class="flex flex-col gap-3.5">
					<RecordingControls />
					<div class="hairline"></div>
					<BroadcastPanel />
					<div class="hairline"></div>
					<SettingRow label={m.settings_forceLandscape()} for="tab-landscape">
						{#snippet control()}
							<Switch
								id="tab-landscape"
								checked={$settings.ui.forceLandscape}
								onCheckedChange={(v) =>
									patchSettings({ ui: { ...$settings.ui, forceLandscape: v } })}
							/>
						{/snippet}
					</SettingRow>
					<div class="hairline"></div>

					<!-- The streaming layout replaces Home wholesale, so these rows are the only way to
					     reach the app's sub-pages while video is flowing. Leaving `/` is safe: it only
					     calls hidePreview() (the SurfaceView goes GONE), while the USB session and the
					     decoder keep running — the picture comes back when the route returns to `/`. -->
					<div class="flex flex-col">
						<SectionLabel class="px-1 pb-1">{m.settings_appTitle()}</SectionLabel>
						<NavRow label={m.nav_recordings()} href="/recordings/">
							{#snippet icon()}
								<VideoIcon class="size-4.5 shrink-0 text-muted-foreground" />
							{/snippet}
						</NavRow>
						<div class="hairline"></div>
						<NavRow label={m.nav_appSettings()} href="/settings/">
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
				</div>
			{/if}
		</div>
	</div>

	<div class="flex shrink-0 flex-row items-stretch gap-2 p-1.5 landscape:flex-col landscape:justify-center">
		<button
			type="button"
			class="{tabBase} h-11 {open && section === 'camera'
				? 'border-primary bg-primary font-semibold text-primary-foreground'
				: 'bg-card font-medium text-muted-foreground'}"
			aria-pressed={open && section === 'camera'}
			onclick={() => toggle('camera')}
		>
			{m.tab_camera()}
		</button>
		<button
			type="button"
			class="{tabBase} h-11 {open && section === 'app'
				? 'border-primary bg-primary font-semibold text-primary-foreground'
				: 'bg-card font-medium text-muted-foreground'}"
			aria-pressed={open && section === 'app'}
			onclick={() => toggle('app')}
		>
			{m.tab_app()}
			{#if $isRecording}
				<span
					class="absolute right-2 top-2 size-[7px] animate-pulse rounded-full bg-destructive"
				></span>
			{/if}
		</button>
	</div>
</aside>
