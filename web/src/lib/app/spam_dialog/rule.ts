import type * as Blockly from 'blockly/core';

/** The part of a mail a comparison reads. */
export const SPAM_FIELDS = ['subject', 'sender_name', 'sender_address', 'body'] as const;

export type SpamField = (typeof SPAM_FIELDS)[number];

/** How a comparison holds that part against its text. */
export const SPAM_MATCHES = ['equals', 'contains', 'regex'] as const;

export type SpamMatch = (typeof SPAM_MATCHES)[number];

/**
 * A filter as the blocks spell it out: boolean operators over comparisons on one mail, which is
 * spam when the tree comes out true. `op` is the tag on every node, so a rule can be walked --
 * and read by whoever ends up applying it -- without knowing which shape it is beforehand.
 *
 * `and` and `or` take as many operands as the block they came from had conditions in it, which is
 * one for one what the editor shows: a wrapper with a column of conditions inside. `not` and the
 * root take exactly one.
 */
export type SpamRule =
	| { op: 'and' | 'or'; operands: SpamRule[] }
	| { op: 'not'; operand: SpamRule }
	| { op: 'match'; field: SpamField; match: SpamMatch; value: string };

/** A named filter, as the dialog hands it over. */
export type SpamFilter = {
	name: string;
	rule: SpamRule;
};

/** The block types the editor knows, in one place: both the blocks and the reader name them. */
export const SPAM_BLOCKS = {
	root: 'spam_root',
	and: 'spam_and',
	or: 'spam_or',
	not: 'spam_not',
	match: 'spam_match'
} as const;

/** What every well in the editor takes, and what conditions connect to each other with. */
export const CONDITION_CHECK = 'SpamCondition';

/**
 * What the three operators are called. The blocks are labelled with them, and so is the one thing
 * that can be wrong with an operator: an empty well.
 */
export const OPERATOR_LABELS = {
	[SPAM_BLOCKS.and]: 'und',
	[SPAM_BLOCKS.or]: 'oder',
	[SPAM_BLOCKS.not]: 'nicht'
} as const;

/** What the mail parts are called on the blocks and in a rule spelled out as a sentence. */
export const FIELD_LABELS: Record<SpamField, string> = {
	subject: 'Betreff',
	sender_name: 'Absendername',
	sender_address: 'Absenderadresse',
	body: 'Inhalt'
};

/** Same for the comparisons. Written so that `<field> <match> "<value>"` reads as a sentence. */
export const MATCH_LABELS: Record<SpamMatch, string> = {
	equals: 'ist gleich',
	contains: 'enthält',
	regex: 'passt auf Regex'
};

/** What came out of the workspace, and what to tell the user about it. */
export type RuleReadout = {
	/** The filter as it stands, or null while the blocks do not add up to one. */
	rule: SpamRule | null;
	/** Why `rule` is null, in the words the dialog would put on screen. */
	problem: string | null;
	/** Worth saying, but no reason to hold the filter back. */
	warning: string | null;
};

/**
 * Why the tree does not add up yet. Thrown rather than returned so that the walk below stays a
 * plain recursion over the blocks instead of threading a result type through every branch; the
 * message is written for the user.
 */
class Unfinished extends Error {}

/** Reads the rule out of the block every workspace is built around. */
export function readRule(workspace: Blockly.Workspace): RuleReadout {
	const [root, ...rest] = workspace.getBlocksByType(SPAM_BLOCKS.root, true);

	// Loose blocks are no reason to stop -- they are how you park a condition while rearranging --
	// but they are also easy to forget about, so they get said out loud. A second root would be
	// one too, except that nothing can make one: the toolbox does not offer it.
	const strays = workspace.getTopBlocks(false).filter((block) => block !== root).length;
	const warning =
		strays === 0
			? null
			: strays === 1
				? 'Ein Baustein liegt frei herum und gehört nicht zur Regel.'
				: `${strays} Bausteine liegen frei herum und gehören nicht zur Regel.`;

	if (!root || rest.length > 0) {
		return { rule: null, problem: 'Der Regel fehlt ihr Anfang.', warning };
	}

	// Said apart from the gaps inside the tree: an empty root is where every filter starts, so it
	// is worth naming what goes in it rather than reporting a hole.
	if (!root.getInputTargetBlock('CONDITION')) {
		return { rule: null, problem: 'Der Regel fehlt noch eine Bedingung.', warning };
	}

	try {
		return { rule: only(root, 'CONDITION'), problem: null, warning };
	} catch (error) {
		if (error instanceof Unfinished) return { rule: null, problem: error.message, warning };
		throw error;
	}
}

function readCondition(block: Blockly.Block): SpamRule {
	// An insertion marker is the grey preview of a block being dragged, not a block the user has
	// put anywhere. A disabled one is placed, but switched off.
	if (block.isInsertionMarker()) {
		throw new Unfinished('Es ist noch eine Lücke offen.');
	}
	if (!block.isEnabled()) {
		throw new Unfinished('Ein abgeschalteter Baustein steckt noch in der Regel.');
	}

	switch (block.type) {
		case SPAM_BLOCKS.and:
		case SPAM_BLOCKS.or: {
			const op = block.type === SPAM_BLOCKS.and ? 'and' : 'or';
			const operands = flatten(column(block, 'CONDITIONS'), op);

			// An operator over one condition is that condition -- which is also how a group whose
			// other conditions are switched off reads.
			return operands.length === 1 ? operands[0] : { op, operands };
		}

		case SPAM_BLOCKS.not:
			return { op: 'not', operand: only(block, 'OPERAND') };

		case SPAM_BLOCKS.match:
			return readMatch(block);

		default:
			throw new Unfinished(`Der Baustein „${block.type}“ gehört nicht in einen Spamfilter.`);
	}
}

/**
 * The column of conditions in an operator's well, top to bottom. This is what "as many as you like"
 * means: the operator holds them all, and it is the one saying how they are joined.
 */
function column(block: Blockly.Block, name: string): SpamRule[] {
	const conditions: SpamRule[] = [];

	for (let child = block.getInputTargetBlock(name); child; child = child.getNextBlock()) {
		// Neither is part of the rule: one is not placed yet, the other is placed and meant to sit
		// this one out, which is what every Blockly editor takes a disabled block to mean.
		if (child.isInsertionMarker() || !child.isEnabled()) continue;

		conditions.push(readCondition(child));
	}

	if (conditions.length === 0) {
		throw new Unfinished(`„${labelFor(block)}“ ist noch leer.`);
	}

	return conditions;
}

/** The one condition in a well that takes exactly one -- the root's and the one in "nicht". */
function only(block: Blockly.Block, name: string): SpamRule {
	const child = block.getInputTargetBlock(name);
	if (!child) throw new Unfinished('Es ist noch eine Lücke offen.');

	// The editor's connection checker is what keeps these wells down to one block. If one ever
	// slips past it, saying so beats quietly dropping half the rule.
	if (child.getNextBlock()) {
		throw new Unfinished(`„${labelFor(block)}“ nimmt nur eine Bedingung.`);
	}

	return readCondition(child);
}

function labelFor(block: Blockly.Block): string {
	return OPERATOR_LABELS[block.type as keyof typeof OPERATOR_LABELS] ?? 'Die Regel';
}

/** `a und (b und c)` and `a und b und c` say the same thing, and the flat one reads better. */
function flatten(operands: SpamRule[], op: 'and' | 'or'): SpamRule[] {
	return operands.flatMap((operand) =>
		(operand.op === 'and' || operand.op === 'or') && operand.op === op
			? operand.operands
			: [operand]
	);
}

function readMatch(block: Blockly.Block): SpamRule {
	const field = block.getFieldValue('FIELD') as SpamField;
	const match = block.getFieldValue('MATCH') as SpamMatch;
	const value = block.getFieldValue('VALUE') as string;

	if (value.length === 0) {
		throw new Unfinished(`„${FIELD_LABELS[field]} ${MATCH_LABELS[match]}“ hat noch keinen Text.`);
	}

	// The rule is meant to run somewhere else later on, so a regex that no engine can compile has
	// to be caught here rather than at the first mail it is held against.
	if (match === 'regex') {
		try {
			new RegExp(value);
		} catch {
			throw new Unfinished(`„${value}“ ist kein gültiger Regex.`);
		}
	}

	return { op: 'match', field, match, value };
}

/**
 * The rule as one German sentence. The blocks say the same thing, but they say it in two
 * dimensions -- this is the line to read back before saving the filter.
 */
export function describeRule(rule: SpamRule): string {
	switch (rule.op) {
		case 'match':
			return `${FIELD_LABELS[rule.field]} ${MATCH_LABELS[rule.match]} „${rule.value}“`;
		case 'not':
			return `nicht ${nested(rule.operand)}`;
		case 'and':
		case 'or':
			return rule.operands.map(nested).join(rule.op === 'and' ? ' und ' : ' oder ');
	}
}

/** Brackets around anything that is itself made of parts, so `a und (b oder c)` stays readable. */
function nested(rule: SpamRule): string {
	return rule.op === 'match' ? describeRule(rule) : `(${describeRule(rule)})`;
}

/**
 * The workspace the editor starts from: the root block, with `rule` already sitting in it when
 * the dialog was handed one. Plain JSON in Blockly's own serialization format, so seeding the
 * editor and reopening a saved filter are the same code path.
 */
export function workspaceStateFor(rule: SpamRule | null): Record<string, unknown> {
	const condition = rule ? asBlocks(rule) : null;

	return {
		blocks: {
			languageVersion: 0,
			blocks: [
				{
					type: SPAM_BLOCKS.root,
					// Clear of the flyout, which the workspace's origin already sits to the right of.
					x: 40,
					y: 32,
					inputs: condition ? { CONDITION: { block: blockStateFor(condition) } } : {}
				}
			]
		}
	};
}

/**
 * The same rule, in the shape blocks can hold it: an operator over one thing is that thing, and a
 * group that turns out to hold nothing is dropped -- the editor then opens with an empty well
 * rather than with a block that says nothing.
 */
function asBlocks(rule: SpamRule): SpamRule | null {
	if (rule.op === 'match') return rule;

	if (rule.op === 'not') {
		const operand = asBlocks(rule.operand);
		return operand ? { op: 'not', operand } : null;
	}

	const operands = rule.operands
		.map(asBlocks)
		.filter((operand): operand is SpamRule => operand !== null);

	if (operands.length === 0) return null;
	if (operands.length === 1) return operands[0];

	return { op: rule.op, operands };
}

function blockStateFor(rule: SpamRule): Record<string, unknown> {
	switch (rule.op) {
		case 'match':
			return {
				type: SPAM_BLOCKS.match,
				fields: { FIELD: rule.field, MATCH: rule.match, VALUE: rule.value }
			};
		case 'not':
			return {
				type: SPAM_BLOCKS.not,
				inputs: { OPERAND: { block: blockStateFor(rule.operand) } }
			};
		case 'and':
		case 'or':
			return {
				type: rule.op === 'and' ? SPAM_BLOCKS.and : SPAM_BLOCKS.or,
				inputs: { CONDITIONS: { block: columnStateFor(rule.operands) } }
			};
	}
}

/** One condition per block, each hanging off the one above it. */
function columnStateFor(conditions: SpamRule[]): Record<string, unknown> {
	const [first, ...rest] = conditions;
	const state = blockStateFor(first);

	if (rest.length > 0) state.next = { block: columnStateFor(rest) };

	return state;
}
