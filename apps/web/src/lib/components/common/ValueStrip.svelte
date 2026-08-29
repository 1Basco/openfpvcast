<script lang="ts" generics="T extends string | number">
	import { tick } from 'svelte';

	// Horizontally scrollable value picker — replaces the 8-column grids for the long
	// ISO / shutter lists. The selected chip is scrolled into view when it changes.
	let {
		options,
		value,
		onselect,
		format = (v: T) => String(v),
		minWidth = 62,
		height = 48,
		bleed = true,
		disabled = false
	}: {
		options: readonly T[];
		value: T | null;
		onselect: (v: T) => void;
		format?: (v: T) => string;
		minWidth?: number;
		height?: number;
		/** Let the strip run to the screen edge (the page has 16px gutters); off inside narrow panels. */
		bleed?: boolean;
		disabled?: boolean;
	} = $props();

	let track = $state<HTMLDivElement | null>(null);

	$effect(() => {
		const v = value;
		void tick().then(() => {
			const el = track?.querySelector<HTMLElement>(`[data-v="${CSS.escape(String(v))}"]`);
			el?.scrollIntoView({ block: 'nearest', inline: 'center', behavior: 'smooth' });
		});
	});
</script>

<div class="strip {bleed ? '-mx-4 px-4' : ''}" bind:this={track}>
	<div class="flex w-max gap-2">
		{#each options as opt (opt)}
			{@const active = opt === value}
			<button
				type="button"
				{disabled}
				data-v={String(opt)}
				class="flex shrink-0 items-center justify-center rounded-lg border px-3 font-mono transition-colors disabled:opacity-50 {active
					? 'border-primary bg-primary font-medium text-primary-foreground'
					: 'bg-card text-muted-foreground'}"
				style="min-width: {minWidth}px; height: {height}px;"
				aria-pressed={active}
				onclick={() => onselect(opt)}
			>
				{format(opt)}
			</button>
		{/each}
	</div>
</div>
