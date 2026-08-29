<script lang="ts">
	let {
		label,
		hint = null,
		for: htmlFor = undefined,
		control
	}: {
		label: string;
		hint?: string | null;
		for?: string;
		control: import('svelte').Snippet;
	} = $props();
</script>

<div class="flex min-h-13 items-center justify-between gap-3 py-2">
	<div class="min-w-0 space-y-0.5">
		<!-- svelte-ignore a11y_label_has_associated_control -->
		<label for={htmlFor} class="block text-[14.5px] font-medium leading-tight text-pretty">
			{label}
		</label>
		{#if hint}
			<p class="text-xs leading-snug text-muted-foreground text-pretty">{hint}</p>
		{/if}
	</div>
	<div class="shrink-0">{@render control()}</div>
</div>
