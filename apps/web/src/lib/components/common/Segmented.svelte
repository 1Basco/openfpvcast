<script lang="ts" generics="T extends string | number">
	// Two-or-more-up pill selector (auto / manual, high / low …). The track is a card, the
	// selected cell is the accent — same shape at every size in the design.
	let {
		options,
		value,
		onselect,
		format = (v: T) => String(v),
		sub,
		size = 'md',
		disabled = false
	}: {
		options: readonly T[];
		value: T;
		onselect: (v: T) => void;
		format?: (v: T) => string;
		sub?: (v: T) => string | null;
		size?: 'sm' | 'md';
		disabled?: boolean;
	} = $props();

	const cell = $derived(size === 'sm' ? 'h-9 text-[13px]' : 'h-10 text-sm');
</script>

<div
	class="grid gap-1 rounded-xl border bg-card p-1"
	style="grid-template-columns: repeat({options.length}, minmax(0, 1fr));"
>
	{#each options as opt (opt)}
		{@const active = opt === value}
		<button
			type="button"
			{disabled}
			class="flex flex-col items-center justify-center gap-px rounded-lg transition-colors disabled:opacity-50 {cell} {active
				? 'bg-primary font-semibold text-primary-foreground'
				: 'font-medium text-muted-foreground'}"
			aria-pressed={active}
			onclick={() => onselect(opt)}
		>
			<span>{format(opt)}</span>
			{#if sub?.(opt)}
				<span class="text-[10px] {active ? 'text-primary-foreground/75' : 'text-dim'}">
					{sub(opt)}
				</span>
			{/if}
		</button>
	{/each}
</div>
