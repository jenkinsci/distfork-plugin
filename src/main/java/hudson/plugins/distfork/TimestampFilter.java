package hudson.plugins.distfork;

import java.io.FileFilter;
import java.io.File;

/**
 * @author Kohsuke Kawaguchi
 */
final class TimestampFilter implements FileFilter {
    private final long startTime;

    public TimestampFilter(long startTime) {
        this.startTime = startTime;
    }

    public boolean accept(File f) {
        return f.lastModified()>startTime;
    }
}
