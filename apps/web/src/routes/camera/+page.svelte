<script lang="ts">
	import AppBar from '$lib/components/common/AppBar.svelte';
	import CameraControls from '$lib/components/camera/CameraControls.svelte';
	import { cameraModel } from '$lib/stores/connection';
	import * as m from '$lib/paraglide/messages';
</script>

<main class="screen-fit flex flex-col overflow-hidden">
	<AppBar title={m.page_cameraTitle()} back="/" backLabel={m.nav_backShort()}>
		{#snippet trailing()}
			{#if $cameraModel}
				<span
					class="mr-2 flex h-6.5 items-center rounded-full bg-secondary px-2.5 font-mono text-[11px] tracking-wide text-muted-foreground"
				>
					{$cameraModel.toUpperCase()}
				</span>
			{/if}
		{/snippet}
	</AppBar>

	<div class="flex min-h-0 flex-1 flex-col gap-5 overflow-y-auto px-4 py-4 pb-10">
		<CameraControls />
	</div>
</main>
