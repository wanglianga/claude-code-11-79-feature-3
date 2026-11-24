package com.ctrial.disclosure;

import com.ctrial.model.DisclosureCell;
import com.ctrial.model.DisclosureRelease;

import java.util.List;

/**
 * A release plus its cells, whether the release is persisted or a transient
 * candidate still being evaluated. The analyse-before-publish path needs to
 * reason over a table that has not been saved yet, so every analyser works on
 * snapshots rather than reaching into the repository by id.
 */
public record ReleaseSnapshot(DisclosureRelease release, List<DisclosureCell> cells) {

    public static ReleaseSnapshot of(DisclosureRelease r, List<DisclosureCell> cells) {
        return new ReleaseSnapshot(r, cells);
    }
}
