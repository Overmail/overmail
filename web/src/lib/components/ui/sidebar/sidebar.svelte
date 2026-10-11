<script lang="ts">
	import * as Sheet from "$lib/components/ui/sheet/index.js";
	import { cn, type WithElementRef } from "$lib/utils.js";
	import { SIDEBAR_WIDTH_COMPACT, SIDEBAR_WIDTH_ICON_COMPACT, SIDEBAR_WIDTH_MOBILE } from "./constants.js";
	import { useSidebar } from "./context.svelte.js";
	import type { HTMLAttributes } from "svelte/elements";
	import { _ } from "svelte-i18n";

	let {
		ref = $bindable(null),
		side = "left",
		variant = "sidebar",
		collapsible = "offcanvas",
		compact = false,
		peek = false,
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
		/**
		 * Unfolds a sidebar collapsed to its icons while the pointer rests on it: floating over
		 * the page rather than pushing it aside, and gone again when the pointer leaves.
		 */
		peek?: boolean;
	} = $props();

	const sidebar = useSidebar();

	// `undefined` rather than "false", so the attribute is absent when it does not apply.
	const compactAttr = $derived(compact ? "true" : undefined);
	const compactWidth = $derived(
		compact
			? `--sidebar-width: ${SIDEBAR_WIDTH_COMPACT}; --sidebar-width-icon: ${SIDEBAR_WIDTH_ICON_COMPACT};`
			: undefined
	);

	/** How long the pointer has to rest on the icons before they unfold; passing over them does nothing. */
	const PEEK_DELAY_MS = 200;

	/** The fixed box that is the sidebar on screen, for what is open inside it. */
	let container: HTMLDivElement | null = $state(null);

	let peekTimer: ReturnType<typeof setTimeout> | null = null;
	let leaveTimer: ReturnType<typeof setInterval> | null = null;

	function clearPeekTimers() {
		if (peekTimer !== null) clearTimeout(peekTimer);
		if (leaveTimer !== null) clearInterval(leaveTimer);
		peekTimer = null;
		leaveTimer = null;
	}

	function onPointerEnter(event: PointerEvent) {
		// A finger has no resting: on touch the sidebar is opened by its button.
		if (!peek || event.pointerType !== "mouse") return;

		clearPeekTimers();
		if (sidebar.open || sidebar.peeking) return;

		peekTimer = setTimeout(() => {
			peekTimer = null;
			if (!sidebar.open) sidebar.peeking = true;
		}, PEEK_DELAY_MS);
	}

	/**
	 * Whether something that belongs to the sidebar is still in use though the pointer is not on
	 * it: a menu opened from a row, which is rendered outside of it, or a name being typed.
	 */
	function isHeld(): boolean {
		return container?.querySelector('[aria-expanded="true"], [data-state="open"], input:focus') != null;
	}

	function onPointerLeave() {
		clearPeekTimers();
		if (!sidebar.peeking) return;

		if (!isHeld()) {
			sidebar.peeking = false;
			return;
		}

		// Folding now would pull the row out from under its own menu. Nothing announces that
		// menu closing, so it is looked for until it has -- or until the pointer is back.
		leaveTimer = setInterval(() => {
			if (isHeld()) return;

			clearPeekTimers();
			sidebar.peeking = false;
		}, PEEK_DELAY_MS);
	}

	$effect(() => clearPeekTimers);
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
		data-collapsible={sidebar.state === "collapsed" && !sidebar.peeking ? collapsible : ""}
		data-peek={sidebar.peeking ? "true" : undefined}
		data-variant={variant}
		data-side={side}
		data-slot="sidebar"
		data-compact={compactAttr}
		style={compactWidth}
	>
		<!-- This is what handles the sidebar gap on desktop. It and the container below move on
		     one curve -- fast out of the gate, long in settling -- so the page and the sidebar
		     are one movement; a linear one reads as two things sliding. -->
		<div
			data-slot="sidebar-gap"
			class={cn(
				"transition-[width] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] relative w-(--sidebar-width) bg-transparent",
				"group-data-[collapsible=offcanvas]:w-0",
				"group-data-[side=right]:rotate-180",
				variant === "floating" || variant === "inset"
					? "group-data-[collapsible=icon]:w-[calc(var(--sidebar-width-icon)+(--spacing(4)))]"
					: "group-data-[collapsible=icon]:w-(--sidebar-width-icon)",
				// A look at the sidebar is over the page, not beside it: the room stays the icons'.
				"group-data-[peek=true]:w-(--sidebar-width-icon)"
			)}
		></div>
		<div
			data-slot="sidebar-container"
			class={cn(
				"fixed inset-y-0 z-10 hidden h-svh w-(--sidebar-width) transition-[left,right,width,box-shadow] duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] md:flex",
				// Over everything the page sticks to its top, and lifted off it: a ring where a
				// border would be, because a border is the caller's to take away.
				"group-data-[peek=true]:z-40 group-data-[peek=true]:shadow-xl group-data-[peek=true]:ring-1 group-data-[peek=true]:ring-sidebar-border",
				side === "left"
					? "start-0 group-data-[collapsible=offcanvas]:start-[calc(var(--sidebar-width)*-1)]"
					: "end-0 group-data-[collapsible=offcanvas]:end-[calc(var(--sidebar-width)*-1)]",
				// Adjust the padding for floating and inset variants.
				variant === "floating" || variant === "inset"
					? "p-2 group-data-[collapsible=icon]:w-[calc(var(--sidebar-width-icon)+(--spacing(4))+2px)]"
					: "group-data-[collapsible=icon]:w-(--sidebar-width-icon) group-data-[side=left]:border-e group-data-[side=right]:border-s",
				className
			)}
			bind:this={container}
			onpointerenter={onPointerEnter}
			onpointerleave={onPointerLeave}
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
