<script lang="ts">
	import { mergeProps } from "bits-ui";
	import { cn, type WithElementRef, type WithoutChildrenOrChild } from "$lib/utils.js";
	import type { Component, ComponentProps, Snippet } from "svelte";
	import type { HTMLAnchorAttributes, HTMLAttributes } from "svelte/elements";
	import * as Tooltip from "$lib/components/ui/tooltip/index.js";
	import SidebarMenuItem from "./sidebar-menu-item.svelte";
	import SidebarMenuButton, {
		type SidebarMenuButtonSize,
		type SidebarMenuButtonVariant,
	} from "./sidebar-menu-button.svelte";
	import SidebarMenuTrailing from "./sidebar-menu-trailing.svelte";

	// Props are typed against HTMLElement rather than the anchor: the same rest props have to
	// spread onto either element, so only the link attributes that are actually useful are added.
	let {
		ref = $bindable(null),
		class: className,
		itemClass,
		icon,
		label,
		href,
		isActive = false,
		variant = "default",
		size = "default",
		tooltipContent,
		tooltipContentProps,
		trailing,
		trailingHover,
		children,
		...restProps
	}: WithElementRef<HTMLAttributes<HTMLElement>, HTMLElement> &
		Pick<HTMLAnchorAttributes, "target" | "rel" | "download"> & {
		/** Leading icon, e.g. one of the phosphor components. */
		icon?: Component;
		label?: string | Snippet;
		/** Renders an `<a>` instead of a `<button>`. */
		href?: string;
		isActive?: boolean;
		variant?: SidebarMenuButtonVariant;
		size?: SidebarMenuButtonSize;
		tooltipContent?: Snippet | string;
		tooltipContentProps?: WithoutChildrenOrChild<ComponentProps<typeof Tooltip.Content>>;
		/** Trailing content: an unread count, a status icon, ... */
		trailing?: Snippet;
		/** Fades in over `trailing` while the row is hovered or focused. */
		trailingHover?: Snippet;
		/** Replaces icon and label, for rows that need more than those two. */
		children?: Snippet;
		/** Class for the `<li>`; `class` goes to the button. */
		itemClass?: string;
	} = $props();

	const Icon = $derived(icon);
	const hasTrailing = $derived(Boolean(trailing || trailingHover));
</script>

{#snippet content()}
	{#if children}
		{@render children()}
	{:else}
		{#if Icon}
			<Icon />
		{/if}
		<span>
			{#if typeof label === "string"}
				{label}
			{:else if label}
				{@render label()}
			{/if}
		</span>
	{/if}
{/snippet}

<SidebarMenuItem class={itemClass}>
	<SidebarMenuButton
		{isActive}
		{variant}
		{size}
		{tooltipContent}
		{tooltipContentProps}
		class={cn(
			// Room for the trailing slot, which overlays this padding.
			hasTrailing && "pr-8",
			className
		)}
	>
		<!-- The rest goes on the element rather than through MenuButton, whose props are typed
		     for a button; mergeProps keeps the tooltip trigger's handlers intact. -->
		{#snippet child({ props })}
			{#if href}
				<a bind:this={ref} {href} {...mergeProps(props, restProps)}>
					{@render content()}
				</a>
			{:else}
				<button bind:this={ref} {...mergeProps(props, restProps)} type="button">
					{@render content()}
				</button>
			{/if}
		{/snippet}
	</SidebarMenuButton>

	{#if hasTrailing}
		<SidebarMenuTrailing hover={trailingHover} children={trailing} />
	{/if}
</SidebarMenuItem>
