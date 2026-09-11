<script lang="ts">
	import { cn, type WithElementRef } from "$lib/utils.js";
	import type { HTMLAttributes } from "svelte/elements";

	let {
		ref = $bindable(null),
		class: className,
		children,
		...restProps
	}: WithElementRef<HTMLAttributes<HTMLUListElement>> = $props();
</script>

<ul
	bind:this={ref}
	data-slot="sidebar-menu-sub"
	data-sidebar="menu-sub"
	class={cn("mx-3.5 translate-x-px gap-1 border-l border-sidebar-border px-2.5 py-0.5 group-data-[collapsible=icon]:hidden flex min-w-0 flex-col",
		"group-data-[compact=true]/sidebar-root:mx-2.5 group-data-[compact=true]/sidebar-root:gap-0.5 group-data-[compact=true]/sidebar-root:px-2", className)}
	{...restProps}
>
	{@render children?.()}
</ul>
