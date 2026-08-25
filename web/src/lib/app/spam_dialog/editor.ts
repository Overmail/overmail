import * as Blockly from 'blockly/core';
import * as De from 'blockly/msg/de';
import { base } from '$app/paths';
import { SPAM_BLOCKS, readRule, workspaceStateFor, type RuleReadout, type SpamRule } from './rule';
import { SPAM_THEME, SPAM_TOOLBOX, defineSpamBlocks } from './blocks';

/**
 * The Blockly half of the spam dialog. Everything in here touches the DOM the moment it is
 * imported, which is why the editor component pulls it in from `onMount` rather than at the top
 * of the module: on the server there is nothing for Blockly to inject into.
 */

export type SpamEditorOptions = {
	/**
	 * Where the blocks go. Needs a size of its own: Blockly measures this element and fills it,
	 * it never gives it one.
	 */
	host: HTMLElement;
	/**
	 * Where Blockly puts its dropdowns, text-field editors and tooltips. They are positioned in
	 * page coordinates, so this has to be an element whose own origin is the page origin -- and it
	 * has to sit inside the dialog, because the modal switches pointer events off for everything
	 * outside it and pulls stray focus back in.
	 */
	widgetHost: HTMLElement;
	/** The rule the editor opens with, or null for a bare root block. */
	initial: SpamRule | null;
	/** Called once on startup and after every change the user makes to the blocks. */
	onchange: (readout: RuleReadout) => void;
};

export type SpamEditor = {
	/** Blockly only learns about a new container size from here. */
	resize: () => void;
	dispose: () => void;
};

export function createSpamEditor(options: SpamEditorOptions): SpamEditor {
	defineSpamBlocks();

	// Context menus, the tooltip on the trashcan, "Baustein löschen" -- everything Blockly itself
	// says. Our own blocks bring their German along in their definitions.
	Blockly.setLocale(De as unknown as Record<string, string>);

	// Before `inject`: the widget div is created during injection, and it is created wherever the
	// parent container points at that moment.
	Blockly.common.setParentContainer(options.widgetHost);

	const workspace = Blockly.inject(options.host, {
		toolbox: SPAM_TOOLBOX,
		theme: SPAM_THEME,
		renderer: 'zelos',
		// Cursors, the zoom sprites and the dropdown arrow. A copy of node_modules/blockly/media
		// under static/, rather than Blockly's CDN, so the installed app keeps its blocks when
		// the network is gone -- re-copy it when Blockly is updated.
		media: `${base}/blockly-media/`,
		grid: { spacing: 24, length: 3, colour: 'rgba(113, 113, 122, 0.35)', snap: true },
		move: { drag: true, wheel: true, scrollbars: true },
		zoom: { controls: true, wheel: false, startScale: 1, minScale: 0.5, maxScale: 1.5 },
		// A second way to be rid of a block, next to dragging it back into the flyout -- and the
		// only one that says so before you try it.
		trashcan: true,
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
		dispose: () => {
			workspace.dispose();

			// The dialog's DOM goes away with it, and a parent container pointing into a detached
			// tree would swallow the dropdowns of whatever opens Blockly next.
			if (Blockly.common.getParentContainer() === options.widgetHost) {
				Blockly.common.setParentContainer(document.body);
			}
		}
	};
}
