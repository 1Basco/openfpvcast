<script lang="ts">
	import QRCode from 'qrcode';

	let { value, size = 148 }: { value: string; size?: number } = $props();

	// Offline QR — the qrcode package is bundled by Vite, nothing leaves the phone.
	let dataUrl = $state<string | null>(null);
	$effect(() => {
		QRCode.toDataURL(value, { width: size, margin: 1 })
			.then((u) => (dataUrl = u))
			.catch(() => (dataUrl = null));
	});
</script>

{#if dataUrl}
	<img src={dataUrl} alt="QR: {value}" width={size} height={size} class="rounded-md bg-white p-1" />
{/if}
