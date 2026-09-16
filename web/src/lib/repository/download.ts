/**
 * Reads [response] to the end and hands it to the browser as a file named [fileName].
 *
 * [expectedSize] is what progress is measured against, ahead of `Content-Length`: a compressed
 * response announces other bytes than the reader yields. [onProgress] gets 0 to 1.
 */
export async function saveResponse(
    response: Response,
    fileName: string,
    expectedSize: number | null = null,
    onProgress: (progress: number) => void = () => {},
): Promise<void> {
    if (!response.body) throw new Error("The response has no body to save");

    const total = expectedSize || Number(response.headers.get("content-length")) || 0;
    const chunks: Uint8Array<ArrayBuffer>[] = [];
    let received = 0;
    onProgress(0);

    const reader = response.body.getReader();
    while (true) {
        const {done, value} = await reader.read();
        if (done) break;
        chunks.push(value);
        received += value.length;
        if (total > 0) onProgress(Math.min(received / total, 1));
    }
    onProgress(1);

    saveBlob(new Blob(chunks, {type: response.headers.get("content-type") ?? undefined}), fileName);
}

/** Saves [blob] through a temporary link, the one way a page can start a download of its own data. */
export function saveBlob(blob: Blob, fileName: string) {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    // Only after the click: revoking it first leaves the anchor pointing at nothing.
    URL.revokeObjectURL(url);
}
