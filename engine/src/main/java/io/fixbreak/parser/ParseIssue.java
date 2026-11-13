package io.fixbreak.parser;

/** A line that looked like FIX but failed validation. */
public record ParseIssue(FixLineParser.Issue kind, String detail, int fileIdx, long line, long time) {
    public long ref() {
        return Refs.of(fileIdx, line);
    }
}
