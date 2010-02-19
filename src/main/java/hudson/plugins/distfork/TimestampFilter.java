package hudson.plugins.distfork;

import java.io.FileFilter;
import java.io.File;
import java.io.Serializable;

/**
 * @author Kohsuke Kawaguchi
 */
final class TimestampFilter implements FileFilter, Serializable {
    private final long startTime;

    public TimestampFilter(long startTime) {
        this.startTime = startTime;
    }

    public boolean accept(File f) {
        return f.lastModified()>=startTime;
    }

    private static final long serialVersionUID = 1L;
}
