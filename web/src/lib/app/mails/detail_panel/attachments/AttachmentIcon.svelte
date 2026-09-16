<script lang="ts" module>
    import type {Component} from "svelte";
    import {
        AddressBookIcon, AndroidLogoIcon, AppleLogoIcon, BookIcon, CalendarDotsIcon, CertificateIcon, CubeIcon,
        DatabaseIcon, EnvelopeIcon, FileArchiveIcon, FileAudioIcon, FileCIcon, FileCodeIcon, FileCppIcon,
        FileCSharpIcon, FileCssIcon, FileCsvIcon, FileDocIcon, FileHtmlIcon, FileIcon, FileImageIcon, FileIniIcon,
        FileJpgIcon, FileJsIcon, FileJsxIcon, FileMdIcon, FilePdfIcon, FilePngIcon, FilePptIcon, FilePyIcon,
        FileRsIcon, FileSqlIcon, FileSvgIcon, FileTsIcon, FileTsxIcon, FileTxtIcon, FileVideoIcon, FileVueIcon,
        FileXlsIcon, FileZipIcon, GifIcon, KeyIcon, PenNibIcon, TerminalWindowIcon, TextAaIcon, WindowsLogoIcon,
    } from "phosphor-svelte";

    // Full class strings, so Tailwind picks them up.
    const colors = {
        red: "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300",
        orange: "bg-orange-50 text-orange-700 dark:bg-orange-950 dark:text-orange-300",
        amber: "bg-amber-50 text-amber-700 dark:bg-amber-950 dark:text-amber-300",
        yellow: "bg-yellow-50 text-yellow-700 dark:bg-yellow-950 dark:text-yellow-300",
        lime: "bg-lime-50 text-lime-700 dark:bg-lime-950 dark:text-lime-300",
        green: "bg-green-50 text-green-700 dark:bg-green-950 dark:text-green-300",
        emerald: "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300",
        teal: "bg-teal-50 text-teal-700 dark:bg-teal-950 dark:text-teal-300",
        cyan: "bg-cyan-50 text-cyan-700 dark:bg-cyan-950 dark:text-cyan-300",
        sky: "bg-sky-50 text-sky-700 dark:bg-sky-950 dark:text-sky-300",
        blue: "bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-300",
        indigo: "bg-indigo-50 text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300",
        violet: "bg-violet-50 text-violet-700 dark:bg-violet-950 dark:text-violet-300",
        fuchsia: "bg-fuchsia-50 text-fuchsia-700 dark:bg-fuchsia-950 dark:text-fuchsia-300",
        pink: "bg-pink-50 text-pink-700 dark:bg-pink-950 dark:text-pink-300",
        rose: "bg-rose-50 text-rose-700 dark:bg-rose-950 dark:text-rose-300",
        slate: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300",
        stone: "bg-stone-100 text-stone-700 dark:bg-stone-800 dark:text-stone-300",
        gray: "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300",
    };

    type FileKind = { icon: Component; color: keyof typeof colors };

    const kind = (icon: Component, color: keyof typeof colors): FileKind => ({icon, color});

    const image = kind(FileImageIcon, "violet");
    const video = kind(FileVideoIcon, "rose");
    const audio = kind(FileAudioIcon, "fuchsia");
    const archive = kind(FileArchiveIcon, "amber");
    const code = kind(FileCodeIcon, "sky");
    const text = kind(FileTxtIcon, "gray");
    const fallback = kind(FileIcon, "gray");

    const byExtension: Record<string, FileKind> = {
        pdf: kind(FilePdfIcon, "red"),

        doc: kind(FileDocIcon, "blue"), docx: kind(FileDocIcon, "blue"), odt: kind(FileDocIcon, "blue"),
        rtf: kind(FileDocIcon, "blue"), pages: kind(FileDocIcon, "blue"),
        xls: kind(FileXlsIcon, "green"), xlsx: kind(FileXlsIcon, "green"), ods: kind(FileXlsIcon, "green"),
        numbers: kind(FileXlsIcon, "green"),
        csv: kind(FileCsvIcon, "emerald"), tsv: kind(FileCsvIcon, "emerald"),
        ppt: kind(FilePptIcon, "orange"), pptx: kind(FilePptIcon, "orange"), odp: kind(FilePptIcon, "orange"),
        key: kind(FilePptIcon, "orange"),
        epub: kind(BookIcon, "lime"), mobi: kind(BookIcon, "lime"),
        txt: text, log: text,
        md: kind(FileMdIcon, "slate"), markdown: kind(FileMdIcon, "slate"),

        png: kind(FilePngIcon, "violet"), jpg: kind(FileJpgIcon, "violet"), jpeg: kind(FileJpgIcon, "violet"),
        gif: kind(GifIcon, "violet"), svg: kind(FileSvgIcon, "pink"),
        webp: image, heic: image, heif: image, avif: image, bmp: image, tif: image, tiff: image, ico: image,
        psd: kind(PenNibIcon, "pink"), ai: kind(PenNibIcon, "pink"), sketch: kind(PenNibIcon, "pink"),
        fig: kind(PenNibIcon, "pink"), eps: kind(PenNibIcon, "pink"),

        mp4: video, mov: video, avi: video, mkv: video, webm: video, wmv: video, m4v: video,
        mp3: audio, wav: audio, flac: audio, ogg: audio, m4a: audio, aac: audio, opus: audio, wma: audio,

        zip: kind(FileZipIcon, "amber"), rar: archive, "7z": archive, tar: archive, gz: archive, tgz: archive,
        bz2: archive, xz: archive, zst: archive,

        js: kind(FileJsIcon, "yellow"), mjs: kind(FileJsIcon, "yellow"), jsx: kind(FileJsxIcon, "sky"),
        ts: kind(FileTsIcon, "blue"), tsx: kind(FileTsxIcon, "blue"), vue: kind(FileVueIcon, "emerald"),
        py: kind(FilePyIcon, "sky"), rs: kind(FileRsIcon, "orange"), c: kind(FileCIcon, "blue"),
        h: kind(FileCIcon, "blue"), cpp: kind(FileCppIcon, "blue"), cc: kind(FileCppIcon, "blue"),
        hpp: kind(FileCppIcon, "blue"), cs: kind(FileCSharpIcon, "violet"),
        html: kind(FileHtmlIcon, "orange"), htm: kind(FileHtmlIcon, "orange"), css: kind(FileCssIcon, "sky"),
        sql: kind(FileSqlIcon, "cyan"),
        ini: kind(FileIniIcon, "slate"), cfg: kind(FileIniIcon, "slate"), conf: kind(FileIniIcon, "slate"),
        toml: kind(FileIniIcon, "slate"), env: kind(FileIniIcon, "slate"),
        json: code, xml: code, yaml: code, yml: code, kt: code, kts: code, java: code, go: code, php: code,
        rb: code, swift: code, dart: code, svelte: code,
        sh: kind(TerminalWindowIcon, "slate"), bash: kind(TerminalWindowIcon, "slate"),
        zsh: kind(TerminalWindowIcon, "slate"), ps1: kind(TerminalWindowIcon, "slate"),
        bat: kind(TerminalWindowIcon, "slate"),

        ics: kind(CalendarDotsIcon, "teal"), ical: kind(CalendarDotsIcon, "teal"),
        vcf: kind(AddressBookIcon, "cyan"), vcard: kind(AddressBookIcon, "cyan"),
        eml: kind(EnvelopeIcon, "indigo"), msg: kind(EnvelopeIcon, "indigo"),

        pem: kind(CertificateIcon, "yellow"), crt: kind(CertificateIcon, "yellow"),
        cer: kind(CertificateIcon, "yellow"), p7s: kind(CertificateIcon, "yellow"),
        p12: kind(KeyIcon, "yellow"), pfx: kind(KeyIcon, "yellow"), asc: kind(KeyIcon, "yellow"),
        gpg: kind(KeyIcon, "yellow"), pgp: kind(KeyIcon, "yellow"), sig: kind(KeyIcon, "yellow"),

        ttf: kind(TextAaIcon, "stone"), otf: kind(TextAaIcon, "stone"), woff: kind(TextAaIcon, "stone"),
        woff2: kind(TextAaIcon, "stone"),
        stl: kind(CubeIcon, "teal"), obj: kind(CubeIcon, "teal"), glb: kind(CubeIcon, "teal"),
        gltf: kind(CubeIcon, "teal"), step: kind(CubeIcon, "teal"),
        db: kind(DatabaseIcon, "cyan"), sqlite: kind(DatabaseIcon, "cyan"),

        exe: kind(WindowsLogoIcon, "slate"), msi: kind(WindowsLogoIcon, "slate"),
        dmg: kind(AppleLogoIcon, "slate"), pkg: kind(AppleLogoIcon, "slate"),
        apk: kind(AndroidLogoIcon, "green"),
    };

    /** For attachments without a known extension. */
    const byContentType: Record<string, FileKind> = {
        "application/pdf": byExtension.pdf,
        "text/calendar": byExtension.ics,
        "text/vcard": byExtension.vcf,
        "text/x-vcard": byExtension.vcf,
        "message/rfc822": byExtension.eml,
        "text/html": byExtension.html,
        "text/csv": byExtension.csv,
        "application/zip": byExtension.zip,
        "application/json": code,
        "application/pkcs7-signature": byExtension.p7s,
        "application/pgp-signature": byExtension.asc,
        "application/pgp-keys": byExtension.asc,
    };

    const byTopLevelType: Record<string, FileKind> = {image, video, audio, text, font: byExtension.ttf};

    function fileKind(name: string, contentType: string): FileKind {
        const extension = name.includes(".") ? name.split(".").pop()!.toLowerCase() : "";
        const type = contentType.toLowerCase();

        return byExtension[extension]
            ?? byContentType[type]
            ?? byTopLevelType[type.split("/")[0]]
            ?? fallback;
    }
</script>

<script lang="ts">
    let {
        name,
        contentType,
        class: className = "",
    }: {
        name: string;
        contentType: string;
        class?: string;
    } = $props();

    let fileKindOf = $derived(fileKind(name, contentType));
    let Icon = $derived(fileKindOf.icon);
</script>

<div class="flex size-8 shrink-0 items-center justify-center rounded-sm {colors[fileKindOf.color]} {className}">
    <Icon class="size-4" />
</div>
