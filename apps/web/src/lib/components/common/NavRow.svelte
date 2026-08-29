<script lang="ts">
	import ChevronRightIcon from '@lucide/svelte/icons/chevron-right';

	let {
		label,
		href = null,
		value = null,
		onclick,
		icon
	}: {
		label: string;
		href?: string | null;
		value?: string | null;
		onclick?: () => void;
		icon?: import('svelte').Snippet;
	} = $props();

	const inner =
		'flex h-13 w-full items-center gap-3 text-left text-[14.5px] font-medium min-h-13';
</script>

{#snippet body()}
	{@render icon?.()}
	<span class="flex-1 truncate">{label}</span>
	{#if value}
		<span class="text-[12.5px] font-normal text-muted-foreground">{value}</span>
	{/if}
	<ChevronRightIcon class="size-4 shrink-0 text-dim" />
{/snippet}

{#if href}
	<a {href} class={inner}>{@render body()}</a>
{:else}
	<button type="button" class={inner} {onclick}>{@render body()}</button>
{/if}
