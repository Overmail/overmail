<script lang="ts">
	import { cn, type WithElementRef } from "$lib/utils.js";
	import type { Snippet } from "svelte";
	import type { HTMLAttributes } from "svelte/elements";

	let {
		ref = $bindable(null),
		class: className,
		hover,
		children,
		...restProps
	}: WithElementRef<HTMLAttributes<HTMLDivElement>> & {
		/**
		 * Cross-fades in over `children` while the row is hovered, keyboard-focused, or while
		 * something inside it is open (a dropdown). Keyboard focus, not plain `:focus-within`:
		 * a click leaves the focus on the row, and the swap would stay until something else
		 * takes it. Meant for an action on a row that otherwise
		 * shows a count. Without a pointer (touch) there is no hover, so `children` stays.
		 */
		hover?: Snippet;
	} = $props();
</script>

<!--
	Sits on top of the button's right padding rather than inside the button: that keeps the row's
	hover background running the full width, and lets `hover` hold a real button, which may not be
	nested in the anchor.
-->
<div
	bind:this={ref}
	data-slot="sidebar-menu-trailing"
	data-sidebar="menu-trailing"
	class={cn(
		"pointer-events-none absolute inset-y-0 right-2 flex items-center text-xs text-sidebar-foreground/60 tabular-nums [&_svg]:size-4 [&_svg]:shrink-0 peer-hover/menu-button:text-sidebar-accent-foreground peer-data-active/menu-button:text-sidebar-accent-foreground group-data-[collapsible=icon]:hidden",
		className
	)}
	{...restProps}
>
	<!-- One grid cell for both slots, so neither shifts the other and the swap is a pure fade. -->
	<div class="group/menu-trailing grid place-items-center *:[grid-area:1/1]">
		{#if children}
			<span
				class={cn(
					"transition-opacity duration-150",
					hover &&
						"group-hover/menu-item:opacity-0 group-has-[:focus-visible]/menu-item:opacity-0 group-has-[[data-state=open]]/menu-trailing:opacity-0"
				)}
			>
				{@render children()}
			</span>
		{/if}
		{#if hover}
			<span
				class="opacity-0 transition-opacity duration-150 group-hover/menu-item:pointer-events-auto group-hover/menu-item:opacity-100 group-has-[:focus-visible]/menu-item:pointer-events-auto group-has-[:focus-visible]/menu-item:opacity-100 group-has-[[data-state=open]]/menu-trailing:pointer-events-auto group-has-[[data-state=open]]/menu-trailing:opacity-100"
			>
				{@render hover()}
			</span>
		{/if}
	</div>
</div>
