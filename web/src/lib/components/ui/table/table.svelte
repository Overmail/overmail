<script lang="ts">
	import { cn, type WithElementRef } from "$lib/utils.js";
	import type { HTMLTableAttributes } from "svelte/elements";

	let {
		ref = $bindable(null),
		class: className,
		containerClass,
		children,
		...restProps
	}: WithElementRef<HTMLTableAttributes> & {
		/**
		 * For the box around the table rather than the table itself. That box is the one that
		 * scrolls sideways, so anything that has to sit on the scroll container -- a
		 * `scroll-fade-x`, a height -- goes here; `class` goes to the `<table>`.
		 */
		containerClass?: string;
	} = $props();
</script>

<div data-slot="table-container" class={cn("relative w-full overflow-x-auto", containerClass)}>
	<table bind:this={ref} data-slot="table" class={cn("w-full caption-bottom text-sm", className)} {...restProps}>
		{@render children?.()}
	</table>
</div>
