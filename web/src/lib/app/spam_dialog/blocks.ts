import * as Blockly from 'blockly/core';
import {
	CONDITION_CHECK,
	FIELD_LABELS,
	MATCH_LABELS,
	OPERATOR_LABELS,
	SPAM_BLOCKS,
	SPAM_FIELDS,
	SPAM_MATCHES
} from './rule';

/**
 * What the blocks of a spam filter are: five block types, the colours they wear and the flyout
 * they are dragged out of. Nothing in here touches a document, so the blocks can be exercised on
 * their own -- Blockly runs a workspace without a screen.
 *
 * Every block is a statement, and every operator is a wrapper with a well. "und" and "oder" hold a
 * column of as many conditions as you like -- they are the ones saying how the column is joined --
 * while "nicht" and the root hold exactly one, which is what the editor's connection checker is
 * for.
 */

/** The mail parts, in the order the dropdown offers them. */
const FIELD_OPTIONS = SPAM_FIELDS.map((field) => [FIELD_LABELS[field], field]);

/** The comparisons, likewise. */
const MATCH_OPTIONS = SPAM_MATCHES.map((match) => [MATCH_LABELS[match], match]);

/**
 * Saturated mid-tones with white lettering: the same three colours hold up on the light and the
 * dark dialog surface, which a block colour taken from the theme's own variables could not do --
 * Blockly parses these to derive the block's edges and only understands hex and rgb.
 */
const BLOCK_STYLES = {
	// Neutral for the frame and the operators, one colour for the comparisons: what a rule is
	// about are the comparisons, and "und" is scaffolding around them rather than another thing
	// to read.
	spam_root_blocks: { colourPrimary: '#18181b', colourTertiary: '#09090b' },
	spam_logic_blocks: { colourPrimary: '#52525b', colourTertiary: '#3f3f46' },
	spam_match_blocks: { colourPrimary: '#0f766e', colourTertiary: '#115e59' }
};

/**
 * The surfaces around the blocks do come from the theme's variables: Blockly writes these straight
 * into an inline style or into a stylesheet, where `var(--…)` resolves like anywhere else in the
 * app.
 *
 * Left unannotated on purpose: `inject` takes the theme structurally, and Blockly does not export
 * the interface behind it.
 */
export const SPAM_THEME = {
	name: 'overmail-spam',
	base: 'classic',
	blockStyles: BLOCK_STYLES,
	componentStyles: {
		workspaceBackgroundColour: 'var(--muted)',
		toolboxBackgroundColour: 'var(--card)',
		toolboxForegroundColour: 'var(--muted-foreground)',
		flyoutBackgroundColour: 'var(--card)',
		flyoutForegroundColour: 'var(--muted-foreground)',
		flyoutOpacity: 1,
		scrollbarColour: 'var(--muted-foreground)',
		scrollbarOpacity: 0.35,
		// An SVG filter and a rendering constant rather than a stylesheet declaration, so these two
		// stay concrete: a neutral grey that shows up against all three block colours.
		selectedGlowColour: '#a1a1aa',
		// Zelos blurs the whole block to mark the selection. Tightened to something closer to a
		// ring than a halo.
		selectedGlowSize: 0.4,
		insertionMarkerColour: '#a1a1aa'
	},
	// Points, not pixels -- that is what the renderer's stylesheet writes them as, so 10 here is
	// the ~13px the rest of the app calls small text.
	fontStyle: { family: '"DM Sans Variable", sans-serif', weight: '600', size: 10 }
};

/**
 * No categories: four blocks fit in one flyout, and a flyout that is always open is one less click.
 *
 * The gaps are spelled out because the flyout would otherwise put its one default gap after every
 * item alike, which reads as an even column of blocks with the group labels floating between them.
 * A label follows its group closely (the flyout's own gap, see `SpamFlyout`) and the last block of
 * a group carries the wider one.
 */
export const SPAM_TOOLBOX: Blockly.utils.toolbox.ToolboxInfo = {
	kind: 'flyoutToolbox',
	contents: [
		{ kind: 'label', text: 'Bedingung', id: undefined },
		{ kind: 'block', type: SPAM_BLOCKS.match, gap: 20 },
		{ kind: 'label', text: 'Verknüpfen', id: undefined },
		{ kind: 'block', type: SPAM_BLOCKS.and, gap: 8 },
		{ kind: 'block', type: SPAM_BLOCKS.or, gap: 8 },
		{ kind: 'block', type: SPAM_BLOCKS.not }
	]
};

/**
 * The blocks whose wells take exactly one condition. Both have a single well, so the type of the
 * block is enough to recognise it.
 */
const SINGLE_WELL_BLOCKS: string[] = [SPAM_BLOCKS.root, SPAM_BLOCKS.not];

/**
 * Keeps the root's well and the one in "nicht" down to one condition, while "und" and "oder" take
 * a column of as many as you like. A well cannot say this by itself: what puts a second block in
 * one is the first block's own connection, so the rule belongs where connections are checked.
 *
 * `doTypeChecks` is the one hook every path goes through -- dragging, the keyboard, and the
 * reconnect Blockly attempts when a block is dropped onto an occupied well.
 */
export class SpamConnectionChecker extends Blockly.ConnectionChecker {
	override doTypeChecks(a: Blockly.Connection, b: Blockly.Connection): boolean {
		return super.doTypeChecks(a, b) && !wouldOverfillAWell(a, b);
	}
}

/** Whether this is the connection under a block, rather than the one inside a well. */
function isUnderABlock(connection: Blockly.Connection): boolean {
	return connection === connection.getSourceBlock().nextConnection;
}

function wouldOverfillAWell(a: Blockly.Connection, b: Blockly.Connection): boolean {
	// Hanging a block under another one: the well that takes the block is the one at the top of
	// its column.
	const under = [a, b].find(isUnderABlock);
	if (under) return sitsInASingleWell(under.getSourceBlock());

	// The other way round: a whole column dropped into a well. Neither connection is under a
	// block, so a next-type one here is a well's.
	const well = [a, b].find((connection) => connection.type === Blockly.NEXT_STATEMENT);
	const top = [a, b].find((connection) => connection.type === Blockly.PREVIOUS_STATEMENT);

	return (
		!!well &&
		!!top &&
		SINGLE_WELL_BLOCKS.includes(well.getSourceBlock().type) &&
		!!top.getSourceBlock().getNextBlock()
	);
}

/** Whether the column this block is part of ends up in a well that takes one condition. */
function sitsInASingleWell(block: Blockly.Block): boolean {
	for (let current = block; ; ) {
		const above = current.previousConnection?.targetConnection;

		// Nothing above: the column is loose on the canvas, where it may be as long as it likes.
		if (!above) return false;
		if (!isUnderABlock(above)) return SINGLE_WELL_BLOCKS.includes(above.getSourceBlock().type);

		current = above.getSourceBlock();
	}
}

/** Whether the block definitions are in Blockly's registry, which is global and outlives dialogs. */
let defined = false;

export function defineSpamBlocks() {
	if (defined) return;
	defined = true;

	Blockly.defineBlocksWithJsonArray([
		{
			type: SPAM_BLOCKS.root,
			message0: 'E-Mail ist Spam, wenn %1',
			args0: [{ type: 'input_statement', name: 'CONDITION', check: CONDITION_CHECK }],
			style: 'spam_root_blocks',
			tooltip:
				'Trifft die Bedingung zu, wird die Mail als Spam einsortiert. Hier passt eine ' +
				'Bedingung hinein -- für mehrere nimm "und" oder "oder".',
			helpUrl: ''
		},
		{
			type: SPAM_BLOCKS.and,
			message0: `${OPERATOR_LABELS[SPAM_BLOCKS.and]} %1`,
			args0: [{ type: 'input_statement', name: 'CONDITIONS', check: CONDITION_CHECK }],
			previousStatement: CONDITION_CHECK,
			nextStatement: CONDITION_CHECK,
			style: 'spam_logic_blocks',
			tooltip: 'Trifft zu, wenn jede Bedingung darin zutrifft. Es dürfen beliebig viele sein.',
			helpUrl: ''
		},
		{
			type: SPAM_BLOCKS.or,
			message0: `${OPERATOR_LABELS[SPAM_BLOCKS.or]} %1`,
			args0: [{ type: 'input_statement', name: 'CONDITIONS', check: CONDITION_CHECK }],
			previousStatement: CONDITION_CHECK,
			nextStatement: CONDITION_CHECK,
			style: 'spam_logic_blocks',
			tooltip:
				'Trifft zu, wenn mindestens eine Bedingung darin zutrifft. Es dürfen beliebig ' +
				'viele sein.',
			helpUrl: ''
		},
		{
			type: SPAM_BLOCKS.not,
			message0: `${OPERATOR_LABELS[SPAM_BLOCKS.not]} %1`,
			args0: [{ type: 'input_statement', name: 'OPERAND', check: CONDITION_CHECK }],
			previousStatement: CONDITION_CHECK,
			nextStatement: CONDITION_CHECK,
			style: 'spam_logic_blocks',
			tooltip: 'Kehrt eine Bedingung um. Nimmt genau eine.',
			helpUrl: ''
		},
		{
			type: SPAM_BLOCKS.match,
			message0: '%1 %2 %3',
			args0: [
				{ type: 'field_dropdown', name: 'FIELD', options: FIELD_OPTIONS },
				{ type: 'field_dropdown', name: 'MATCH', options: MATCH_OPTIONS },
				{ type: 'field_input', name: 'VALUE', text: '', spellcheck: false }
			],
			previousStatement: CONDITION_CHECK,
			nextStatement: CONDITION_CHECK,
			style: 'spam_match_blocks',
			tooltip: 'Vergleicht einen Teil der Mail mit einem Text.',
			helpUrl: ''
		}
	]);
}
