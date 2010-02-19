package hudson.plugins.distfork;

import hudson.util.DirScanner;
import hudson.util.FileVisitor;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;

/**
 * Directory scanner that cuts off the head directory name.
 *
 * So where normally we'd see abc/def/ghi in the archive, this would put the same
 * file in def/ghi. 
 *
 * @author Kohsuke Kawaguchi
*/
class RootCutOffFilter extends DirScanner.Filter {
    public RootCutOffFilter(FileFilter filter) {
        super(filter);
    }

    @Override
    public void scan(File dir, final FileVisitor visitor) throws IOException {
        super.scan(dir,new FileVisitor() {
            @Override
            public void visit(File f, String relativePath) throws IOException {
                int idx = relativePath.indexOf('/');
                if (idx<0)  return;

                // ignore the first path component
                visitor.visit(f,relativePath.substring(idx+1));
            }
        });
    }

    private static final long serialVersionUID = 1L;
}
