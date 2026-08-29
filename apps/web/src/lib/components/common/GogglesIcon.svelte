<script lang="ts">
	// FPV goggles mark — drawn for this app (lucide has no goggles glyph).
	let { class: klass = 'size-5', strokeWidth = 1.5 }: { class?: string; strokeWidth?: number } =
		$props();
</script>

<svg
	class={klass}
	viewBox="0 0 24 24"
	fill="none"
	stroke="currentColor"
	stroke-width={strokeWidth}
	stroke-linecap="round"
	stroke-linejoin="round"
	aria-hidden="true"
>
	<path
		d="M3 9.5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-3.6a2 2 0 0 1-1.5-.7l-1-1.1a1.2 1.2 0 0 0-1.8 0l-1 1.1a2 2 0 0 1-1.5.7H5a2 2 0 0 1-2-2z"
	/>
	<path d="M7 12h2" />
	<path d="M15 12h2" />
</svg>
