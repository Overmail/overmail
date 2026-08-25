import * as Blockly from 'blockly/core';
import * as De from 'blockly/msg/de';
import { base } from '$app/paths';
import { SPAM_BLOCKS, readRule, workspaceStateFor, type RuleReadout, type SpamRule } from './rule';
import { SPAM_THEME, SPAM_TOOLBOX, SpamConnectionChecker, defineSpamBlocks } from './blocks';

/**
 * The Blockly half of the spam dialog. Everything in here touches the DOM the moment it is
 * imported, which is why the editor component pulls it in from `onMount` rather than at the top
 * of the module: on the server there is nothing for Blockly to inject into.
 */

/**
 * The dragger of the drag that is running, so that Escape can reach it: Blockly makes one per drag
 * and keeps it to itself.
 */
let dragging: SpamDragger | null = null;

/** A drag that can be called off, which Blockly's own pointer drags cannot. */
class SpamDragger extends Blockly.dragging.Dragger {
	private aborted = false;
	private fromFlyout = false;

	override onDragStart(e?: PointerEvent | KeyboardEvent) {
		// Read before `super`, which swaps a block dragged out of the palette for the copy that
		// lands on the canvas.
		this.fromFlyout = this.draggable.workspace.isFlyout;
		dragging = this;

		return super.onDragStart(e);
	}

	override onDragEnd(e?: PointerEvent | KeyboardEvent) {
		dragging = null;

		if (!this.aborted) {
			super.onDragEnd(e);
			return;
		}

		// A block on its way out of the palette was never anywhere, so calling that drag off
		// leaves nothing behind.
		if (this.fromFlyout && Blockly.isDeletable(this.draggable)) {
			this.draggable.endDrag(e, Blockly.DragDisposition.DELETE);
			this.draggable.dispose();
		} else {
			// The two steps Blockly takes for a drop it does not allow: the block goes back, and
			// then the drag is closed off -- the revert on its own would leave the event group
			// open, which is what makes a drag one step in the undo stack.
			this.draggable.revertDrag();
			this.draggable.endDrag(e, Blockly.DragDisposition.REVERT);
		}

		Blockly.Events.setGroup(false);
	}

	/** Marks the running drag to be undone rather than dropped once it ends. */
	static abort() {
		if (dragging) dragging.aborted = true;
	}
}

/**
 * The palette, with one padding value instead of three. The flyout derives its inset from its own
 * corner radius and its gap from that inset again -- and both are readonly by the time a subclass
 * can see them, hence the assign.
 */
class SpamFlyout extends Blockly.VerticalFlyout {
	constructor(options: Blockly.Options) {
		super(options);

		Object.assign(this, {
			// Left and top inside the palette. What sits to the right of the widest block is the
			// room Blockly keeps for the scrollbar and cannot be given back.
			MARGIN: 12,
			// Between a label and the group under it. Everything else says how far apart it wants
			// to be itself, see SPAM_TOOLBOX.
			GAP_Y: 8
		});
	}
}

/**
 * Blockly's own metrics are built for a classroom whiteboard. These bring the blocks down to the
 * size of the app around them: half the padding, smaller sockets, and the radii the app uses
 * elsewhere. Written out one by one rather than through zelos' grid unit, because the grid unit is
 * read in its constructor and overrides only land afterwards.
 */
const RENDERER_OVERRIDES = {
	// Nothing plugs in sideways, so the puzzle tab is dead weight -- and the palette's left inset
	// is measured from it.
	TAB_WIDTH: 0,
	CORNER_RADIUS: 6,
	MIN_BLOCK_HEIGHT: 28,
	SMALL_PADDING: 3,
	MEDIUM_PADDING: 5,
	MEDIUM_LARGE_PADDING: 7,
	LARGE_PADDING: 10,
	FIELD_BORDER_RECT_RADIUS: 5,
	FIELD_BORDER_RECT_X_PADDING: 6,
	FIELD_BORDER_RECT_Y_PADDING: 3,
	FIELD_DROPDOWN_SVG_ARROW_PADDING: 6,
	// The bump a block is dropped onto, and how far the wells are inset.
	NOTCH_WIDTH: 22,
	NOTCH_OFFSET_LEFT: 10,
	STATEMENT_INPUT_PADDING_LEFT: 10,
	// An empty well: big enough to aim at, no bigger.
	STATEMENT_INPUT_SPACER_MIN_WIDTH: 96,
	EMPTY_STATEMENT_INPUT_HEIGHT: 22,
	TOP_ROW_PRECEDES_STATEMENT_MIN_HEIGHT: 8,
	BOTTOM_ROW_AFTER_STATEMENT_MIN_HEIGHT: 12
};

export type SpamEditorOptions = {
	/**
	 * Where the blocks go. Needs a size of its own: Blockly measures this element and fills it,
	 * it never gives it one.
	 */
	host: HTMLElement;
	/** The rule the editor opens with, or null for a bare root block. */
	initial: SpamRule | null;
	/** Called once on startup and after every change the user makes to the blocks. */
	onchange: (readout: RuleReadout) => void;
};

export type SpamEditor = {
	/** Blockly only learns about a new container size from here. */
	resize: () => void;
	/**
	 * Calls off the drag that is running, putting the block back where it was picked up. False if
	 * nothing was being dragged, which is the caller's cue to let the key through.
	 */
	abortDrag: () => boolean;
	dispose: () => void;
};

export function createSpamEditor(options: SpamEditorOptions): SpamEditor {
	defineSpamBlocks();

	// Thinner than the 15px Blockly reserves, which is also what the palette keeps free to its
	// right -- so this is the one number that makes the palette's padding look even.
	Blockly.Scrollbar.scrollbarThickness = 8;

	// Context menus, the tooltip on the trashcan, "Baustein löschen" -- everything Blockly itself
	// says. Our own blocks bring their German along in their definitions.
	Blockly.setLocale(De as unknown as Record<string, string>);

	// Nothing sets a parent container here on purpose. Blockly's dropdowns, field editors and
	// tooltips then land in the injection div, which is the one place they can be: it is what
	// `setMainWorkspace` hands the focus manager as the root that popovers live in. Anywhere else
	// and focusing a dropdown reads as focus leaving that root, which hides the dropdown from
	// inside the call that is still opening it -- and the exception that follows takes the running
	// gesture down with it. The div sits inside the dialog either way, so the modal leaves its
	// pointer events and its focus alone, and Blockly positions the popovers relative to whatever
	// contains them.

	const workspace = Blockly.inject(options.host, {
		toolbox: SPAM_TOOLBOX,
		theme: SPAM_THEME,
		renderer: 'zelos',
		rendererOverrides: RENDERER_OVERRIDES,
		plugins: {
			flyoutsVerticalToolbox: SpamFlyout,
			blockDragger: SpamDragger,
			connectionChecker: SpamConnectionChecker
		},
		// Cursors, the zoom sprites and the dropdown arrow. A copy of node_modules/blockly/media
		// under static/, rather than Blockly's CDN, so the installed app keeps its blocks when
		// the network is gone -- re-copy it when Blockly is updated.
		media: `${base}/blockly-media/`,
		// Dots rather than a lattice: enough to see that the canvas can be panned.
		grid: { spacing: 20, length: 1, colour: 'rgba(113, 113, 122, 0.3)', snap: true },
		move: { drag: true, wheel: true, scrollbars: true },
		// No zoom buttons: two sprite-covered circles on the canvas for something the wheel and
		// the trackpad already do.
		zoom: { controls: false, wheel: false, pinch: true, startScale: 1, minScale: 0.5, maxScale: 1.5 },
		// Nor a bin. Dragging a block back into the palette is what gets rid of it.
		trashcan: false,
		sounds: false
	});

	Blockly.serialization.workspaces.load(workspaceStateFor(options.initial), workspace);

	// The rule hangs off this one block, so losing it would leave the editor with nothing to read.
	workspace.getBlocksByType(SPAM_BLOCKS.root, false)[0]?.setDeletable(false);

	options.onchange(readRule(workspace));

	workspace.addChangeListener((event) => {
		// Selecting, clicking and scrolling change what the workspace looks like, not what it says.
		if (event.isUiEvent) return;
		options.onchange(readRule(workspace));
	});

	return {
		resize: () => Blockly.svgResize(workspace),
		abortDrag: () => {
			// Covers panning the canvas as well, where there is no block to put back -- ending
			// that gesture is still the right answer to Escape.
			if (!workspace.isDragging()) return false;

			SpamDragger.abort();
			workspace.cancelCurrentGesture();

			return true;
		},
		dispose: () => workspace.dispose()
	};
}
