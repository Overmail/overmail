<script lang="ts" module>
	/** One card, as it is handed to the tooltip snippet. */
	export type UsageGraphDay = {
		/** `yyyy-mm-dd`, the same key the state is filled with. */
		date: string;
		count: number;
	};

	/**
	 * Either nothing has been counted yet, or here is what was counted.
	 *
	 * A day nothing happened on is absent from [days] rather than held at zero — the graph draws a
	 * card for every day of the year either way, so an entry would only be a second way of saying
	 * the same thing.
	 */
	export type UsageGraphState =
		| { type: 'loading' }
		| { type: 'data'; days: ReadonlyMap<string, number> };
</script>

<script lang="ts">
	import type { Snippet } from 'svelte';

	// `state` is renamed on the way in: a local binding under that name would turn every `$state`
	// in this file into a store subscription.
	let {
		year,
		state: usage,
		color = 'var(--primary)',
		label = `Usage per day in ${year}`,
		tooltip
	}: {
		year: number;
		state: UsageGraphState;
		/**
		 * What a busy day is drawn in, as any CSS colour. It is mixed into the empty card rather
		 * than used as a fill, so a colour that knows nothing about the theme still reads in both.
		 */
		color?: string;
		/**
		 * What the grid is, for a reader who is not looking at it. The graph itself does not know
		 * what it counted — a tag's mails, a whole mailbox — so the caller says.
		 */
		label?: string;
		/**
		 * Rendered over the card the pointer is on, and it draws its own bubble — what belongs in
		 * it is the caller's business, since only the caller knows what was counted. Without it the
		 * graph ignores the pointer entirely.
		 */
		tooltip?: Snippet<[UsageGraphDay]>;
	} = $props();

	/** `size-3` and `gap-1` below, in pixels. The tooltip is placed off these, so they go together. */
	const CARD_PX = 12;
	const GAP_PX = 4;
	const PITCH_PX = CARD_PX + GAP_PX;

	const ROWS = 7;

	/**
	 * The rail down the left, row for row with the cards — Monday first, like the rows themselves.
	 *
	 * Every other day is named and the rest are blank: seven labels down 16px rows read as a wall of
	 * text, and four are enough to find a row by counting one step from the nearest.
	 */
	const WEEKDAYS = ['Mon', '', 'Wed', '', 'Fri', '', 'Sun'];

	/** How far apart two neighbouring cards are in the wave; the whole grid takes ~1.5s to cross. */
	const WAVE_STEP_MS = 25;

	/**
	 * What the quietest day that happened at all is worth.
	 *
	 * Without a floor a single mail against a busy day comes out as a percent or two of tint, which
	 * is a card that says "nothing here" while something did happen.
	 */
	const MIN_SHARE = 0.2;

	const isLeapYear = (of: number) => new Date(of, 1, 29).getDate() === 29;

	/** Built by hand rather than through `toISOString`, which would answer in UTC and shift a day. */
	const isoDate = (date: Date) =>
		`${date.getFullYear()}-${`${date.getMonth() + 1}`.padStart(2, '0')}-${`${date.getDate()}`.padStart(2, '0')}`;

	/**
	 * How many cards stand empty before 1 January, so every row is one weekday.
	 *
	 * Monday first, as the week is written here — `getDay()` counts from Sunday.
	 */
	const leadingCells = $derived((new Date(year, 0, 1).getDay() + 6) % 7);

	/**
	 * Every day of [year] with where it sits. Cards fill a column before starting the next, so a
	 * card's row is its weekday and its column is its week.
	 */
	const cells = $derived.by(() => {
		const total = isLeapYear(year) ? 366 : 365;

		return Array.from({ length: total }, (_, index) => {
			const position = index + leadingCells;

			return {
				date: isoDate(new Date(year, 0, 1 + index)),
				row: position % ROWS,
				column: Math.floor(position / ROWS)
			};
		});
	});

	const isLoading = $derived(usage.type === 'loading');
	const counts = $derived(usage.type === 'data' ? usage.days : null);

	const countOn = (date: string) => counts?.get(date) ?? 0;

	/** What the fullest card is measured against. Nothing counted yet means nothing to measure. */
	const busiest = $derived(counts?.size ? Math.max(...counts.values()) : 0);

	/**
	 * How much of [color] a day is worth, as a percentage: its own count against the busiest day of
	 * the year, so the graph is read within itself rather than against a scale nobody stated.
	 *
	 * Zero while loading as well as on a day nothing arrived — the shimmer wants one flat surface,
	 * and an empty day is empty either way.
	 */
	const shareOn = (date: string) => {
		const count = countOn(date);
		if (count === 0 || busiest === 0) return 0;
		return Math.round((MIN_SHARE + (1 - MIN_SHARE) * (count / busiest)) * 100);
	};

	// Which card the pointer is on, by index into [cells]. Only tracked when there is a tooltip to
	// show for it, so a graph without one costs nothing per card.
	let hoveredIndex = $state<number | null>(null);
	const hovered = $derived(hoveredIndex === null ? null : (cells[hoveredIndex] ?? null));
</script>

<div class="flex w-fit gap-1">
	<!--
		Its own grid beside the cards rather than a first column inside them, so the card grid keeps
		starting at zero: the tooltip is placed off that origin, and a rail of unknown width in front
		of it would shift every card by however wide the longest name happens to render.

		Same seven rows and the same gap, so the two line up without either knowing the other's size.
	-->
	<div class="grid grid-rows-7 gap-1 pr-0.5" aria-hidden="true">
		{#each WEEKDAYS as weekday, row (row)}
			<div class="text-muted-foreground flex h-3 items-center text-[10px] leading-none">
				{weekday}
			</div>
		{/each}
	</div>

	<!-- The tooltip is positioned against this box, so the grid is what it wraps and nothing more. -->
	<div class="relative w-fit">
		<!--
			One picture rather than 365 of them: a reader is told what the graph is, and the cards
			below it are the drawing. The colour is stated once here too, so the caller's string
			reaches exactly one place and every card only carries its own share of it.
		-->
		<div
			class="grid w-fit grid-flow-col grid-rows-7 gap-1"
			style="--usage-tint: {color}"
			role="img"
			aria-label={label}
			onmouseleave={() => (hoveredIndex = null)}
		>
			{#each Array.from({ length: leadingCells }) as _, index (index)}
				<div class="size-3"></div>
			{/each}

			{#each cells as cell, index (cell.date)}
				<div
					class="card size-3 overflow-hidden rounded-xs"
					class:shimmer={isLoading}
					class:enter={counts !== null}
					style="--wave-delay: {(cell.row + cell.column) *
						WAVE_STEP_MS}ms; --tint-share: {shareOn(cell.date)}%"
					aria-hidden="true"
					onmouseenter={() => (hoveredIndex = tooltip ? index : null)}
				></div>
			{/each}
		</div>

		{#if tooltip && hovered}
			<!-- Placed off the grid's own geometry rather than off a measured element: the pitch is
			     known, and reading a rect back would cost a layout per card the pointer crosses. -->
			<div
				class="pointer-events-none absolute z-10 -translate-x-1/2 -translate-y-full pb-1"
				style="left: {hovered.column * PITCH_PX + CARD_PX / 2}px; top: {hovered.row * PITCH_PX}px"
			>
				{@render tooltip({ date: hovered.date, count: countOn(hovered.date) })}
			</div>
		{/if}
	</div>
</div>

<style>
	/*
		Only the colour travels. It runs along the same diagonal the shimmer does, at half its step
		so the counts land in about half the time the wave takes to cross.
	*/
	.card {
		/*
			Mixed into the empty card rather than laid over it: the tint is whatever the caller
			handed over and knows nothing about the theme, so a flat fill would be unreadable in one
			of them. A share of 0% is the empty card exactly, which is what a quiet day and the whole
			loading state both are.
		*/
		background-color: color-mix(
			in oklab,
			var(--usage-tint, var(--primary)) var(--tint-share, 0%),
			var(--accent)
		);
		transition: background-color 500ms ease-out;
		transition-delay: calc(var(--wave-delay) / 2);
	}

	/*
		The grid itself arrives in one piece, undelayed: what is being waited for is the counts, and
		staggering the empty cards as well made the year look like it was being built rather than
		filled in. So every card is put down at once, and the colour then walks across them.

		Runs once: the class goes on when the first counts land and stays on, so a day whose count
		moves later only transitions its colour.
	*/
	.enter {
		animation: card-enter 320ms ease-out;
	}

	@keyframes card-enter {
		from {
			opacity: 0;
			transform: scale(0.4);
		}
		to {
			opacity: 1;
			transform: scale(1);
		}
	}

	/*
		The light passes over the cards themselves rather than as a sheet across the grid: a band
		drawn over the top would light the gaps too, and the shape being waited for is the cards.
		Each one carries its own delay, so the wave travels down and to the right.
	*/
	.shimmer {
		position: relative;
	}

	.shimmer::after {
		content: '';
		position: absolute;
		inset: 0;
		/* The theme's ink, so this darkens on a light card and lightens on a dark one. */
		background-color: var(--foreground);
		opacity: 0;
		animation: card-shimmer 2.4s ease-in-out infinite;
		animation-delay: var(--wave-delay);
	}

	/* Barely there on purpose: it says "still counting", it is not the thing being looked at. */
	@keyframes card-shimmer {
		0%,
		100% {
			opacity: 0;
		}
		50% {
			opacity: 0.09;
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.card {
			transition: none;
		}

		.enter,
		.shimmer::after {
			animation: none;
		}
	}
</style>
