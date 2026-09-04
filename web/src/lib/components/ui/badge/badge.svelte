<script lang="ts" module>
	import { type VariantProps, tv } from "tailwind-variants";

	export const badgeVariants = tv({
		base: "h-5.5 gap-1 rounded-xs px-1.5 text-sm font-medium transition-all has-data-[icon=inline-end]:pr-1 has-data-[icon=inline-start]:pl-1 [&>svg]:size-3.5! group/badge inline-flex w-fit shrink-0 items-center justify-center overflow-hidden whitespace-nowrap transition-colors focus-visible:ring-[3px] focus-visible:ring-ring/50 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 [&>svg]:pointer-events-none",
		variants: {
			variant: {
				default: "bg-primary text-primary-foreground [a]:hover:bg-primary/80",
				secondary: "bg-secondary text-secondary-foreground [a]:hover:bg-secondary/80",
				destructive: "bg-destructive/10 text-destructive focus-visible:ring-destructive/20 dark:bg-destructive/20 dark:focus-visible:ring-destructive/40 [a]:hover:bg-destructive/20",
				outline: "border border-border text-foreground [a]:hover:bg-muted [a]:hover:text-muted-foreground",
				ghost: "hover:bg-muted hover:text-muted-foreground dark:hover:bg-muted/50",
				link: "text-primary underline-offset-4 hover:underline",
			},
		},
		defaultVariants: {
			variant: "default",
		},
	});

	export type BadgeVariant = VariantProps<typeof badgeVariants>["variant"];
</script>

<script lang="ts">
	import { cn, type WithElementRef } from "$lib/utils.js";
	import type { HTMLAnchorAttributes } from "svelte/elements";
	import { XIcon } from "phosphor-svelte";
	import { _ } from "svelte-i18n";

	let {
		ref = $bindable(null),
		href,
		class: className,
		variant = "default",
		color,
		style,
		onremove,
		children,
		...restProps
	}: WithElementRef<HTMLAnchorAttributes> & {
		variant?: BadgeVariant;
		/**
		 * An arbitrary colour (any CSS colour) for this badge. It only ever reaches the
		 * background; the text stays the standard foreground, so the contrast holds no matter
		 * which colour comes in. See the style block below.
		 */
		color?: string;
		/**
		 * Makes the badge removable: an X after the content, and this is what pressing it calls.
		 * Without it the badge carries no button at all and stays a label like any other.
		 */
		onremove?: () => void;
	} = $props();

	const tintStyle = $derived(
		[color && `--badge-color: ${color}`, style].filter(Boolean).join("; ") || undefined,
	);
</script>

<svelte:element
	this={href ? "a" : "span"}
	bind:this={ref}
	data-slot="badge"
	data-tinted={color ? "" : undefined}
	{href}
	class={cn(badgeVariants({ variant }), className)}
	style={tintStyle}
	{...restProps}
>
	{@render children?.()}

	{#if onremove}
		<!--
			A button, because it is one: the badge itself is a span or a link, and the X has to be
			reachable by keyboard either way. `data-icon=inline-end` is what makes the badge
			tighten its trailing padding for it, the same as for an icon a caller puts there, and
			the size is set here because the badge only sizes its own direct children.
		-->
		<button
			type="button"
			data-icon="inline-end"
			class="inline-flex shrink-0 items-center justify-center rounded-xs opacity-60 outline-none transition-opacity hover:opacity-100 focus-visible:ring-[3px] focus-visible:ring-ring/50 cursor-pointer"
			onclick={(event) => {
				// A removable badge can be a link as well, and the X is not the way to it.
				event.preventDefault();
				event.stopPropagation();
				onremove();
			}}
		>
			<XIcon class="size-3.5" />
			<span class="sr-only">{$_("ui.badge.remove")}</span>
		</button>
	{/if}
</svelte:element>

<!--
	A tinted badge keeps the caller's hue but not its lightness: `oklch(from …)` throws away the
	source L and C and rebuilds the fill at a lightness this theme is known to work against, so a
	colour picked from a label name — which knows nothing about the theme — cannot end up as an
	unreadable fill. Chroma is capped rather than taken as-is, both to stay near the display gamut
	(where out-of-range chroma gets mapped away anyway) and to keep the chip a surface instead of a
	shout. Unlayered component CSS outranks Tailwind's `@layer utilities`, so this wins over the
	variant's own `bg-*`/`text-*` without depending on class order. The fill is the whole edge:
	there is no border to carry the hue as well.
-->
<style>
	[data-slot="badge"][data-tinted] {
		background-color: oklch(from var(--badge-color) 0.93 min(c, 0.09) h);
		color: var(--foreground);
	}

	a[data-slot="badge"][data-tinted]:hover {
		background-color: oklch(from var(--badge-color) 0.88 min(c, 0.11) h);
	}

	:global(.dark) [data-slot="badge"][data-tinted] {
		background-color: oklch(from var(--badge-color) 0.28 min(c, 0.09) h);
	}

	:global(.dark) a[data-slot="badge"][data-tinted]:hover {
		background-color: oklch(from var(--badge-color) 0.34 min(c, 0.11) h);
	}
</style>
