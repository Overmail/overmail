import {createColumnHelper, renderComponent, tableFeatures} from "@tanstack/svelte-table";
import type {EmailMeta} from "$lib/repository/EmailRepository.svelte";
import MailSenderCell from "./table/MailSenderCell.svelte";
import MailSentAtCell from "./table/MailSentAtCell.svelte";
import MailSubjectCell from "./table/MailSubjectCell.svelte";

/**
 * Core only. Sorting and filtering are deliberately absent: the list is paged in from the server
 * and only holds what has been scrolled to, so anything ordering or narrowing the loaded rows
 * would quietly do it to a fraction of the mailbox.
 */
export const features = tableFeatures({});

export type MailTableFeatures = typeof features;

/** One row of the table: the mail at a position of the mailbox. */
export type MailTableRow = {
    id: string;
    mail: EmailMeta;
};

const columnHelper = createColumnHelper<MailTableFeatures, MailTableRow>();

type MailCell = Parameters<typeof renderComponent>[0];

const cell = (component: MailCell) => {
    return ({row}: {row: {original: MailTableRow}}) => renderComponent(component, {mail: row.original.mail});
};

/** The headers are keys, not text: they have to follow the locale, see MailTable. */
export const columns = columnHelper.columns([
    columnHelper.display({
        id: "sender",
        header: "mails.table.sender",
        cell: cell(MailSenderCell as MailCell),
    }),
    columnHelper.display({
        id: "subject",
        header: "mails.table.subject",
        cell: cell(MailSubjectCell as MailCell),
    }),
    columnHelper.display({
        id: "sent",
        header: "mails.table.sentAt",
        cell: cell(MailSentAtCell as MailCell),
    }),
]);

/**
 * The widths are stated rather than left to the content: the list is windowed, so what is in the
 * DOM is whatever is near the viewport, and a column following its content would resize every
 * time somebody scrolled. The subject takes what the others leave, which is what gives the badges
 * room.
 */
export const COLUMN_WIDTHS: Record<string, string | undefined> = {
    sender: "18rem",
    subject: undefined,
    sent: "11rem",
};

/**
 * The placeholder each column shows for a mail the list does not hold yet, by column id.
 *
 * Roughly as wide as what will be there, so a half loaded list keeps its shape. These rows carry
 * no data at all -- they are the stretch the table holds open while it pages -- so they are
 * described here rather than rendered through a column's cell.
 */
export const GHOST_SHAPES: Record<string, {width: string; withAvatar?: boolean}> = {
    sender: {width: "w-44", withAvatar: true},
    subject: {width: "w-full"},
    sent: {width: "w-28"},
};
