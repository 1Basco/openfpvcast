<script lang="ts">
	// Small uppercase rule-label that heads every section in place of a card title.
	let { children, class: klass = '' }: { children: import('svelte').Snippet; class?: string } =
		$props();
</script>

<span class="text-[11px] font-bold uppercase tracking-[0.1em] text-label {klass}">
	{@render children()}
</span>
