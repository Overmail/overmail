import { createColumnHelper, renderComponent, tableFeatures } from '@tanstack/svelte-table';
import type { Mail } from '$lib/repository/MailRepository';
import MailParticipantsCell from './MailParticipantsCell.svelte';
import MailSentCell from './MailSentCell.svelte';
import MailSubjectCell from './MailSubjectCell.svelte';
import MailTagsCell from './MailTagsCell.svelte';

/**
 * Core only. Sorting and filtering are deliberately absent: the list is paged in from the server
 * and only holds what has been scrolled to, so anything ordering or narrowing the loaded rows
 * would quietly do it to a fraction of the mailbox.
 */
export const features = tableFeatures({});

export type MailTableFeatures = typeof features;

const columnHelper = createColumnHelper<MailTableFeatures, Mail>();

export const columns = columnHelper.columns([
	columnHelper.accessor('subject', {
		header: 'Betreff',
		cell: ({ row }) => renderComponent(MailSubjectCell, { subject: row.original.subject })
	}),
	columnHelper.accessor('sender', {
		header: 'Von',
		cell: ({ row }) => renderComponent(MailParticipantsCell, { participants: [row.original.sender] })
	}),
	columnHelper.accessor('recipients', {
		header: 'An',
		cell: ({ row }) => renderComponent(MailParticipantsCell, { participants: row.original.recipients })
	}),
	columnHelper.accessor('cc', {
		header: 'Cc',
		cell: ({ row }) => renderComponent(MailParticipantsCell, { participants: row.original.cc })
	}),
	columnHelper.accessor('bcc', {
		header: 'Bcc',
		cell: ({ row }) => renderComponent(MailParticipantsCell, { participants: row.original.bcc })
	}),
	columnHelper.accessor('sent_at', {
		header: 'Gesendet',
		cell: ({ row }) => renderComponent(MailSentCell, { sentAt: row.original.sent_at })
	}),
	columnHelper.accessor('tags', {
		header: 'Tags',
		cell: ({ row }) => renderComponent(MailTagsCell, { tags: row.original.tags })
	})
]);

/**
 * Column widths by id. The table is laid out `table-fixed` -- with rows coming and going as the
 * viewport moves, content-driven widths would resize the columns on every scroll and the sticky
 * header would stop lining up with them. Percentages so the columns fill whatever width the table
 * ends up with; the table carries a `min-width`, so they never collapse on a narrow screen.
 */
export const COLUMN_WIDTHS: Record<string, string> = {
	subject: 'w-[24%]',
	sender: 'w-[15%]',
	recipients: 'w-[15%]',
	cc: 'w-[11%]',
	bcc: 'w-[11%]',
	sent_at: 'w-[12%]',
	tags: 'w-[12%]'
};
