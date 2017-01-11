package hudson.plugins.distfork;

import hudson.model.Queue.Item;
import hudson.model.Queue.Task;
import hudson.model.Queue.Executable;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.ResourceList;
import hudson.model.Hudson;
import hudson.model.AbstractProject;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.SubTask;
import hudson.security.ACL;
import jenkins.model.Jenkins;

import org.acegisecurity.Authentication;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

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
    private final Authentication auth;

    public DistForkTask(Label label, String displayName, long estimatedDuration, Runnable runnable) {
        this.label = label;
        this.displayName = displayName;
        this.estimatedDuration = estimatedDuration;
        this.runnable = runnable;
        this.auth = Jenkins.getAuthentication();
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
            public SubTask getParent() {
                return DistForkTask.this;
            }

            public void run() {
                runnable.run();
            }

            public long getEstimatedDuration() {
                return estimatedDuration;
            }

            @Override
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
        return null;
    }

    public String getDisplayName() {
        return displayName;
    }

    public ResourceList getResourceList() {
        return new ResourceList();
    }

    public CauseOfBlockage getCauseOfBlockage() {
        // not blocked at any time
        return null;
    }

    public boolean isConcurrentBuild() {
        // concurrently buildable
        return true;
    }

    public Collection<? extends SubTask> getSubTasks() {
        return Collections.singleton(this);
    }

    public Task getOwnerTask() {
        return this;
    }

    public Object getSameNodeConstraint() {
        return null;
    }

    @Nonnull
    public Authentication getDefaultAuthentication() {
        return auth;
    }

    @Override
    public Authentication getDefaultAuthentication(Item item) {
        return auth;
    }
}
