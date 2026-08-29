<script lang="ts">
	import { Switch } from '$lib/components/ui/switch';
	import { Slider } from '$lib/components/ui/slider';
	import SettingRow from '$lib/components/common/SettingRow.svelte';
	import { settings, patchSettings } from '$lib/stores/settings';
	import * as m from '$lib/paraglide/messages';

	// The recording preferences that belong on the home screen: what happens on arm/disarm and
	// whether the phone mic is mixed in. Everything else lives in app settings.

	// The slider works in seconds for the human; the store keeps ms.
	const stopDelayS = $derived($settings.autoRecord.stopDelayMs / 1000);
</script>

<div class="flex flex-col">
	<SettingRow label={m.settings_autoRecord()} for="auto-rec">
		{#snippet control()}
			<Switch
				id="auto-rec"
				checked={$settings.autoRecord.enabled}
				onCheckedChange={(v) =>
					patchSettings({ autoRecord: { ...$settings.autoRecord, enabled: v } })}
			/>
		{/snippet}
	</SettingRow>

	{#if $settings.autoRecord.enabled}
		<div class="hairline"></div>
		<div class="flex flex-col gap-2.5 py-3">
			<span class="text-[12.5px] text-muted-foreground">
				{m.settings_stopAfter({ seconds: stopDelayS.toFixed(0) })}
			</span>
			<Slider
				type="single"
				min={0}
				max={30}
				step={1}
				value={stopDelayS}
				onValueChange={(v) =>
					patchSettings({
						autoRecord: { ...$settings.autoRecord, stopDelayMs: (v as number) * 1000 }
					})}
			/>
		</div>
	{/if}

	<div class="hairline"></div>

	<SettingRow label={m.rec_phoneAudio()} for="rec-audio-home">
		{#snippet control()}
			<Switch
				id="rec-audio-home"
				checked={$settings.recordPhoneAudio}
				onCheckedChange={(v) => patchSettings({ recordPhoneAudio: v })}
			/>
		{/snippet}
	</SettingRow>
</div>
