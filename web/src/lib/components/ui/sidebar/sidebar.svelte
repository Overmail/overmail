<script lang="ts">
	import * as Sheet from "$lib/components/ui/sheet/index.js";
	import { cn, type WithElementRef } from "$lib/utils.js";
	import { SIDEBAR_WIDTH_COMPACT, SIDEBAR_WIDTH_MOBILE } from "./constants.js";
	import { useSidebar } from "./context.svelte.js";
	import type { HTMLAttributes } from "svelte/elements";
	import { _ } from "svelte-i18n";

	let {
		ref = $bindable(null),
		side = "left",
		variant = "sidebar",
		collapsible = "offcanvas",
		compact = false,
		class: className,
		children,
		...restProps
	}: WithElementRef<HTMLAttributes<HTMLDivElement>> & {
		side?: "left" | "right";
		variant?: "sidebar" | "floating" | "inset";
		collapsible?: "offcanvas" | "icon" | "none";
		/**
		 * Tighter spacing, smaller rows and a narrower sidebar. Set here once: the sub-components
		 * pick it up from `data-compact` on this root via the `sidebar-root` group, so nothing
		 * inside has to be told about it.
		 */
		compact?: boolean;
	} = $props();

	const sidebar = useSidebar();

	// `undefined` rather than "false", so the attribute is absent when it does not apply.
	const compactAttr = $derived(compact ? "true" : undefined);
	const compactWidth = $derived(compact ? `--sidebar-width: ${SIDEBAR_WIDTH_COMPACT};` : undefined);
</script>

{#if collapsible === "none"}
	<div
		class={cn(
			"group/sidebar-root flex h-full w-(--sidebar-width) flex-col bg-sidebar text-sidebar-foreground",
			className
		)}
		data-compact={compactAttr}
		style={compactWidth}
		bind:this={ref}
		{...restProps}
	>
		{@render children?.()}
	</div>
{:else if sidebar.isMobile}
	<Sheet.Root bind:open={() => sidebar.openMobile, (v) => sidebar.setOpenMobile(v)} {...restProps}>
		<Sheet.Content
			bind:ref
			data-sidebar="sidebar"
			data-slot="sidebar"
			data-mobile="true"
			data-compact={compactAttr}
			class={cn(
				"group/sidebar-root w-(--sidebar-width) bg-sidebar p-0 text-sidebar-foreground [&>button]:hidden",
				className
			)}
			style="--sidebar-width: {SIDEBAR_WIDTH_MOBILE};"
			{side}
		>
			<Sheet.Header class="sr-only">
				<Sheet.Title>{$_("ui.sidebar.title")}</Sheet.Title>
				<Sheet.Description>{$_("ui.sidebar.description")}</Sheet.Description>
			</Sheet.Header>
			<div class="flex h-full w-full flex-col">
				{@render children?.()}
			</div>
		</Sheet.Content>
	</Sheet.Root>
{:else}
	<div
		bind:this={ref}
		class="group/sidebar-root group peer hidden text-sidebar-foreground md:block"
		data-state={sidebar.state}
		data-collapsible={sidebar.state === "collapsed" ? collapsible : ""}
		data-variant={variant}
		data-side={side}
		data-slot="sidebar"
		data-compact={compactAttr}
		style={compactWidth}
	>
		<!-- This is what handles the sidebar gap on desktop -->
		<div
			data-slot="sidebar-gap"
			class={cn(
				"transition-[width] duration-200 ease-linear relative w-(--sidebar-width) bg-transparent",
				"group-data-[collapsible=offcanvas]:w-0",
				"group-data-[side=right]:rotate-180",
				variant === "floating" || variant === "inset"
					? "group-data-[collapsible=icon]:w-[calc(var(--sidebar-width-icon)+(--spacing(4)))]"
					: "group-data-[collapsible=icon]:w-(--sidebar-width-icon)"
			)}
		></div>
		<div
			data-slot="sidebar-container"
			class={cn(
				"fixed inset-y-0 z-10 hidden h-svh w-(--sidebar-width) transition-[left,right,width] duration-200 ease-linear md:flex",
				side === "left"
					? "start-0 group-data-[collapsible=offcanvas]:start-[calc(var(--sidebar-width)*-1)]"
					: "end-0 group-data-[collapsible=offcanvas]:end-[calc(var(--sidebar-width)*-1)]",
				// Adjust the padding for floating and inset variants.
				variant === "floating" || variant === "inset"
					? "p-2 group-data-[collapsible=icon]:w-[calc(var(--sidebar-width-icon)+(--spacing(4))+2px)]"
					: "group-data-[collapsible=icon]:w-(--sidebar-width-icon) group-data-[side=left]:border-e group-data-[side=right]:border-s",
				className
			)}
			{...restProps}
		>
			<div
				data-sidebar="sidebar"
				data-slot="sidebar-inner"
				class="bg-sidebar group-data-[variant=floating]:rounded-2xl group-data-[variant=floating]:shadow-sm group-data-[variant=floating]:ring-1 group-data-[variant=floating]:ring-sidebar-border flex size-full flex-col"
			>
				{@render children?.()}
			</div>
		</div>
	</div>
{/if}
