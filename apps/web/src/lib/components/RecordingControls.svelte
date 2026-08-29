<script lang="ts">
	import { Switch } from '$lib/components/ui/switch';
	import SettingRow from '$lib/components/common/SettingRow.svelte';
	import { toast } from 'svelte-sonner';

	import { connection } from '$lib/stores/connection';
	import { settings, patchSettings } from '$lib/stores/settings';
	import {
		isRecording,
		startRecording,
		stopRecording,
		recordingElapsedMs,
		formatElapsed
	} from '$lib/stores/recording';
	import * as m from '$lib/paraglide/messages';

	// The one control you must be able to hit in flight, plus the audio source switch.

	let busy = $state(false);

	async function onToggleRecord() {
		busy = true;
		try {
			if ($isRecording) {
				await stopRecording();
				toast.success(m.rec_stopped());
			} else {
				if ($connection.state !== 'connected') {
					toast.warning(m.rec_connectBeforeRecord());
					return;
				}
				await startRecording($connection.sessionId, { source: 'manual' });
			}
		} catch (e) {
			toast.error(m.rec_error({ error: e instanceof Error ? e.message : String(e) }));
		} finally {
			busy = false;
		}
	}
</script>

<div class="flex flex-col gap-1">
	<button
		type="button"
		disabled={busy}
		class="flex h-14 items-center justify-center gap-2.5 rounded-xl border transition-colors disabled:opacity-60 {$isRecording
			? 'border-destructive/40 bg-destructive/16'
			: 'border-white/10 bg-card'}"
		onclick={onToggleRecord}
	>
		{#if $isRecording}
			<span class="size-3 rounded-[3px] bg-destructive"></span>
			<span class="text-[15px] font-semibold tracking-wide text-destructive">{m.rec_stop()}</span>
			<span class="font-mono text-[15px] font-medium text-destructive/85">
				{formatElapsed($recordingElapsedMs)}
			</span>
		{:else}
			<span class="size-3 rounded-full bg-destructive"></span>
			<span class="text-[15px] font-semibold tracking-wide">{m.rec_rec()}</span>
		{/if}
	</button>

	<SettingRow label={m.rec_phoneAudio()} for="rec-audio">
		{#snippet control()}
			<Switch
				id="rec-audio"
				checked={$settings.recordPhoneAudio}
				onCheckedChange={(v) => patchSettings({ recordPhoneAudio: v })}
			/>
		{/snippet}
	</SettingRow>
</div>
