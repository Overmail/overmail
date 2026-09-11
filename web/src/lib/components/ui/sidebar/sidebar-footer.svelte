<script lang="ts">
	import { cn, type WithElementRef } from "$lib/utils.js";
	import type { HTMLAttributes } from "svelte/elements";

	let {
		ref = $bindable(null),
		class: className,
		children,
		...restProps
	}: WithElementRef<HTMLAttributes<HTMLElement>> = $props();
</script>

<div
	bind:this={ref}
	data-slot="sidebar-footer"
	data-sidebar="footer"
	class={cn(
		"gap-2 p-2 flex flex-col",
		"group-data-[compact=true]/sidebar-root:gap-1.5 group-data-[compact=true]/sidebar-root:p-1.5",
		className
	)}
	{...restProps}
>
	{@render children?.()}
</div>
