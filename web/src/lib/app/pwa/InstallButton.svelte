<script lang="ts">
	import { DownloadSimpleIcon } from 'phosphor-svelte';
	import { SidebarMenuButton } from '$lib/components/ui/sidebar';
	import { install } from './install.svelte';
</script>

{#if install.available}
	<SidebarMenuButton onclick={() => install.prompt()}>
		<DownloadSimpleIcon />
		<span>Install app</span>
	</SidebarMenuButton>
{/if}
