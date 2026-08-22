import { createColumnHelper, renderComponent, tableFeatures } from '@tanstack/svelte-table';
import type { MailTableRow } from './rows';
import MailGroupCell from './table/MailGroupCell.svelte';
import MailSenderCell from './table/MailSenderCell.svelte';
import MailSentAtCell from './table/MailSentAtCell.svelte';
import MailStatusCell from './table/MailStatusCell.svelte';
import MailSubjectCell from './table/MailSubjectCell.svelte';

/**
 * Core only. Sorting and filtering are deliberately absent: the list is paged in from the server
 * and only holds what has been scrolled to, so anything ordering or narrowing the loaded rows
 * would quietly do it to a fraction of the mailbox.
 */
export const features = tableFeatures({});

export type MailTableFeatures = typeof features;

/** A group header owns the whole row, so it is rendered once and the other columns skip it. */
export const spansRow = (row: MailTableRow) => row.kind === 'group';

const columnHelper = createColumnHelper<MailTableFeatures, MailTableRow>();

/**
 * Renders [component] for a mail row. Only the spanning column is ever asked for a group row, but
 * every column is asked for every row, so the others answer with nothing rather than a mail cell.
 */
type MailCell = Parameters<typeof renderComponent>[0];

const cell = (component: MailCell, options: { rendersGroup?: boolean } = {}) => {
	return ({ row }: { row: { original: MailTableRow } }) => {
		const data = row.original;

		if (data.kind === 'group') {
			return options.rendersGroup
				? renderComponent(MailGroupCell, {
						thread: data.thread,
						loaded: data.loaded,
						total: data.total,
						participants: data.participants
					})
				: '';
		}

		return renderComponent(component, { mail: data.mail });
	};
};

export const columns = columnHelper.columns([
	columnHelper.display({
		id: 'sender',
		header: 'VON',
		cell: cell(MailSenderCell as MailCell, { rendersGroup: true })
	}),
	columnHelper.display({
		id: 'subject',
		header: 'BETREFF',
		cell: cell(MailSubjectCell as MailCell)
	}),
	columnHelper.display({
		id: 'sent_at',
		header: 'GESENDET',
		cell: cell(MailSentAtCell as MailCell)
	}),
	columnHelper.display({
		id: 'is_read',
		header: '',
		cell: cell(MailStatusCell as MailCell)
	})
]);

/**
 * The widths are stated rather than left to the content: the list is windowed, so what is in the
 * DOM is whatever is near the viewport, and a column following its content would resize every time
 * somebody scrolled. The subject takes what the others leave, which is what gives the badges room.
 */
export const COLUMN_WIDTHS: Record<string, string | undefined> = {
	sender: '13rem',
	subject: undefined,
	sent_at: '10rem',
	is_read: '6rem'
};

/**
 * The placeholder each column shows for a mail the list does not hold yet, by column id.
 *
 * Roughly as wide as what will be there, so a half loaded list keeps its shape. These rows carry
 * no data at all -- they are the tail the table holds open while it pages -- so they are described
 * here rather than rendered through a column's cell.
 */
export const GHOST_SHAPES: Record<string, { width: string; withAvatar?: boolean }> = {
	sender: { width: 'w-32', withAvatar: true },
	subject: { width: 'w-full' },
	sent_at: { width: 'w-36' },
	is_read: { width: 'w-16' }
};
