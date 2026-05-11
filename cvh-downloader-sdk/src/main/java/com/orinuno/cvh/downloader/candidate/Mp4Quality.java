package com.orinuno.cvh.downloader.candidate;

/** Resolution tag attached to a {@link DownloadCandidate} when the format is MP4. */
public enum Mp4Quality {
    P1080(1080),
    P720(720),
    P480(480),
    P360(360),
    P240(240),
    P144(144);

    private final int verticalLines;

    Mp4Quality(int verticalLines) {
        this.verticalLines = verticalLines;
    }

    public int verticalLines() {
        return verticalLines;
    }
}
