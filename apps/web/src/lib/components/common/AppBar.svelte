<script lang="ts">
	import ChevronLeftIcon from '@lucide/svelte/icons/chevron-left';

	let {
		title,
		back = null,
		backLabel = 'Back',
		trailing
	}: {
		title: string;
		back?: string | null;
		backLabel?: string;
		trailing?: import('svelte').Snippet;
	} = $props();
</script>

<header class="flex h-14 shrink-0 items-center gap-1.5 px-2">
	{#if back}
		<a
			href={back}
			aria-label={backLabel}
			class="flex size-11 shrink-0 items-center justify-center rounded-lg"
		>
			<ChevronLeftIcon class="size-5" />
		</a>
	{:else}
		<span class="w-2"></span>
	{/if}
	<h1 class="flex-1 truncate font-heading text-[17px] font-semibold tracking-tight">{title}</h1>
	{@render trailing?.()}
</header>
<div class="hairline shrink-0"></div>
