package io.fixbreak.parser;

/** A line that looked like FIX but failed validation. */
public record ParseIssue(FixLineParser.Issue kind, String detail, int fileIdx, long line, int part, long time) {
    public ParseIssue(FixLineParser.Issue kind, String detail, int fileIdx, long line, long time) {
        this(kind, detail, fileIdx, line, 0, time);
    }

    public long ref() {
        return Refs.of(fileIdx, line, part);
    }
}
