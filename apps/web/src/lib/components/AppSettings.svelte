<script lang="ts">
	import { Switch } from '$lib/components/ui/switch';
	import { Button } from '$lib/components/ui/button';
	import * as Dialog from '$lib/components/ui/dialog';
	import SectionLabel from '$lib/components/common/SectionLabel.svelte';
	import SettingRow from '$lib/components/common/SettingRow.svelte';
	import RecordingSettings from '$lib/components/RecordingSettings.svelte';
	import { settings, patchSettings } from '$lib/stores/settings';
	import * as m from '$lib/paraglide/messages';
	import {
		availableLocales,
		localeLabels,
		getActiveLocale,
		setActiveLocale
	} from '$lib/i18n.svelte';
	import type { Locale } from '$lib/paraglide/runtime';

	// Body of the app-settings screen: sections separated by hairlines, no card chrome.

	let consentOpen = $state(false);

	function onDiagnosticsToggle(on: boolean) {
		if (on && !$settings.diagnostics.consented) {
			// First opt-in: require explicit consent before anything can be uploaded.
			consentOpen = true;
			return;
		}
		patchSettings({ diagnostics: { ...$settings.diagnostics, uploadEnabled: on } });
	}

	function acceptConsent() {
		patchSettings({ diagnostics: { consented: true, uploadEnabled: true } });
		consentOpen = false;
	}
</script>

<div class="flex flex-col gap-6">
	<section class="flex flex-col gap-1">
		<SettingRow label={m.settings_language()} for="app-locale">
			{#snippet control()}
				<select
					id="app-locale"
					class="h-9 rounded-lg border bg-card px-2 text-sm"
					value={getActiveLocale()}
					onchange={(e) => setActiveLocale(e.currentTarget.value as Locale)}
				>
					{#each availableLocales as loc (loc)}
						<option value={loc}>{localeLabels[loc]}</option>
					{/each}
				</select>
			{/snippet}
		</SettingRow>
	</section>

	<section class="flex flex-col gap-1">
		<SectionLabel>{m.rec_section()}</SectionLabel>
		<RecordingSettings />
	</section>

	<section class="flex flex-col gap-1">
		<SectionLabel>{m.bcast_title()}</SectionLabel>
		<SettingRow label={m.settings_autoBroadcast()} for="bcast-auto">
			{#snippet control()}
				<Switch
					id="bcast-auto"
					checked={$settings.broadcast.autoStart}
					onCheckedChange={(v) =>
						patchSettings({ broadcast: { ...$settings.broadcast, autoStart: v } })}
				/>
			{/snippet}
		</SettingRow>
		<div class="hairline"></div>
		<SettingRow
			label={m.settings_forceLandscape()}
			hint={m.settings_forceLandscapeHint()}
			for="force-landscape"
		>
			{#snippet control()}
				<Switch
					id="force-landscape"
					checked={$settings.ui.forceLandscape}
					onCheckedChange={(v) => patchSettings({ ui: { ...$settings.ui, forceLandscape: v } })}
				/>
			{/snippet}
		</SettingRow>
	</section>

	<section class="flex flex-col gap-1">
		<SectionLabel>{m.settings_appTitle()}</SectionLabel>
		<SettingRow
			label={m.settings_shareDiagnostics()}
			hint={m.settings_shareDiagnosticsHint()}
			for="diag-share"
		>
			{#snippet control()}
				<Switch
					id="diag-share"
					checked={$settings.diagnostics.uploadEnabled}
					onCheckedChange={onDiagnosticsToggle}
				/>
			{/snippet}
		</SettingRow>
		<div class="hairline"></div>
		<SettingRow
			label={m.settings_developerMode()}
			hint={m.settings_developerModeHint()}
			for="dev-mode"
		>
			{#snippet control()}
				<Switch
					id="dev-mode"
					checked={$settings.developerMode}
					onCheckedChange={(v) => patchSettings({ developerMode: v })}
				/>
			{/snippet}
		</SettingRow>
	</section>
</div>

<Dialog.Root bind:open={consentOpen}>
	<Dialog.Content class="max-w-md">
		<Dialog.Header>
			<Dialog.Title>{m.diag_consentTitle()}</Dialog.Title>
			<Dialog.Description class="whitespace-pre-line text-left">
				{m.diag_consentBody()}
			</Dialog.Description>
		</Dialog.Header>
		<Dialog.Footer class="gap-2">
			<Button variant="outline" onclick={() => (consentOpen = false)}>
				{m.diag_consentDecline()}
			</Button>
			<Button onclick={acceptConsent}>{m.diag_consentAccept()}</Button>
		</Dialog.Footer>
	</Dialog.Content>
</Dialog.Root>
