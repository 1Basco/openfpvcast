<script lang="ts">
	import "../app.css";
	import { onMount } from "svelte";
	import { Capacitor } from "@capacitor/core";
	import { App as CapacitorApp } from "@capacitor/app";
	import { goto } from "$app/navigation";
	import { page } from "$app/state";
	import { Toaster } from "$lib/components/ui/sonner";
	import "$lib/stores/stream";
	import "$lib/stores/autorecord";
	import "$lib/stores/broadcast";
	import "$lib/stores/orientation";
	import "$lib/stores/camera";
	import { patchSettings, currentSettings } from "$lib/stores/settings";
	import { initLocale, getActiveLocale } from "$lib/i18n.svelte";

	// Seed the UI language from the persisted setting / device locale before the first render.
	initLocale();

	let { children } = $props();

	$effect(() => {
		document.documentElement.lang = getActiveLocale();
	});

	// Android back (button or swipe gesture): step back through the app instead of
	// exiting. On the home route, minimize instead of finishing the Activity — killing
	// it would tear down the AOA session and the recording/broadcast service.
	onMount(() => {
		if (!Capacitor.isNativePlatform()) return;
		const listener = CapacitorApp.addListener("backButton", () => {
			if (page.url.pathname !== "/") {
				void goto("/");
				return;
			}
			const ui = currentSettings().ui;
			if (ui.sideTabOpen) {
				patchSettings({ ui: { ...ui, sideTabOpen: false } });
				return;
			}
			void CapacitorApp.minimizeApp();
		});
		return () => {
			void listener.then((l) => l.remove());
		};
	});
</script>

<Toaster richColors position="top-center" />
{@render children()}
