package hudson.plugins.distfork;

import hudson.model.Queue.Task;
import hudson.model.Queue.Executable;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.ResourceList;
import hudson.model.Hudson;
import hudson.model.AbstractProject;
import hudson.security.ACL;

import java.io.IOException;

/**
 * {@link Task} that represents a distfork work.
 *
 * <p>
 * TODO: once we authenticate the CLI client, allow that user to abort.
 *
 * @author Kohsuke Kawaguchi
 */
public class DistForkTask implements Task {
    private final Label label;
    private final String displayName;
    private final long estimatedDuration;
    private final Runnable runnable;

    public DistForkTask(Label label, String displayName, long estimatedDuration, Runnable runnable) {
        this.label = label;
        this.displayName = displayName;
        this.estimatedDuration = estimatedDuration;
        this.runnable = runnable;
    }

    public Label getAssignedLabel() {
        return label;
    }

    public Node getLastBuiltOn() {
        return null;
    }

    public boolean isBuildBlocked() {
        return false;
    }

    public String getWhyBlocked() {
        return null;
    }

    public String getName() {
        return getDisplayName();
    }

    public String getFullDisplayName() {
        return getDisplayName();
    }

    public long getEstimatedDuration() {
        return estimatedDuration;
    }

    public Executable createExecutable() throws IOException {
        return new Executable() {
            public Task getParent() {
                return DistForkTask.this;
            }

            public void run() {
                runnable.run();
            }

            public String toString() {
                return displayName;
            }
        };
    }

    public void checkAbortPermission() {
        getACL().checkPermission(AbstractProject.ABORT);
    }

    public boolean hasAbortPermission() {
        return getACL().hasPermission(AbstractProject.ABORT);
    }

    private ACL getACL() {
        return Hudson.getInstance().getACL();
    }

    public String getUrl() {
        // TODO
        throw new UnsupportedOperationException();
    }

    public String getDisplayName() {
        return displayName;
    }

    public ResourceList getResourceList() {
        return new ResourceList();
    }
}
