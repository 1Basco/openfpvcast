<script lang="ts">
	import { isStreaming } from '$lib/stores/stream';
	import HomeScreen from '$lib/components/HomeScreen.svelte';
	import StreamingMode from '$lib/components/StreamingMode.svelte';
</script>

<!-- The two app modes are driven by connection/stream state, NOT routing — navigating away
     would unmount VideoPreview and tear down the native video window. -->
{#if $isStreaming}
	<StreamingMode />
{:else}
	<HomeScreen />
{/if}
