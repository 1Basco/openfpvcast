<script lang="ts">
	import AppBar from '$lib/components/common/AppBar.svelte';
	import AppSettings from '$lib/components/AppSettings.svelte';
	import * as m from '$lib/paraglide/messages';
</script>

<main class="screen-fit flex flex-col overflow-hidden">
	<AppBar title={m.nav_appSettings()} back="/" backLabel={m.nav_backShort()} />

	<div class="min-h-0 flex-1 overflow-y-auto px-4 py-4 pb-10">
		<AppSettings />
	</div>
</main>
