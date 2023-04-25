package hudson.plugins.distfork;

import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.TarCompression;
import hudson.Functions;
import hudson.Launcher;
import hudson.Util;
import hudson.cli.CLICommand;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Executable;
import hudson.remoting.VirtualChannel;
import hudson.remoting.forward.Forwarder;
import hudson.remoting.forward.ForwarderFactory;
import hudson.remoting.forward.PortForwarder;
import hudson.security.AccessDeniedException2;
import hudson.slaves.Cloud;
import hudson.util.StreamTaskListener;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.Option;

import jenkins.model.Jenkins;
import jenkins.security.SlaveToMasterCallable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;

/**
 * @author Kohsuke Kawaguchi
 */
@Extension
public class DistForkCommand extends CLICommand {

    @Option(name="-l",usage="Label for controlling where to execute this command")
    public String label;

    @Option(name="-n",usage="Human readable name that describe this command. Used in Jenkins' UI.")
    public String name;

    @Option(name="-d",usage="Estimated duration of this task in milliseconds, or -1 if unknown")
    public long duration = -1;

    @Argument(handler=RestOfArgumentsHandler.class)
    public List<String> commands = new ArrayList<String>();

    @Option(name="-z",metaVar="FILE",
            usage="Zip/tgz file to be extracted into the target remote machine before execution of the command; " +
                  "requires -remoting unless you pass =zip or =tgz in which case stdin is used")
    public String zip;

    @Option(name="-Z",metaVar="FILE",
            usage="Bring back the newly added/updated files in the target remote machine after the end of the command " +
                  "by creating a zip/tgz bundle and place this in the local file system by this name; " +
                   "requires -remoting unless you pass =zip or =tgz in which case stdout is used")
    public String returnZip;

    @Option(name="-e",usage="Environment variables to set to the launched process",metaVar="NAME=VAL")
    public Map<String,String> envs = new HashMap<String,String>();

    public String getShortDescription() {
        return "forks a process on a remote machine and connects to its stdin/stdout";
    }

    protected int run() throws Exception {
        if(commands.isEmpty())
            throw new CmdLineException(null, "No commands are specified");

        Jenkins j = Jenkins.get();

        Label l = null;
        if (label!=null) {
            l = j.getLabel(label);
            if(l == null || l.isEmpty()) {
                // TODO does this leak information about labels on nodes that some users can not see?
                stderr.println("No such label: "+label);
                return -1;
            }
            
        }

        // only users who have build permission should be allowed to use the dist-fork command
        // whilst the Task itself will be restricted - it would just sit in the queue forever if a user does not have the correct permission
        // so try to be nice and see that there is at least somewhere where we can build the item...
        boolean hasPermission = false;

        if (l != null) {
            hasPermission = hasBuildPermission(l.getNodes()) || hasProvisionPermission(l.getClouds()) || 
                           (l.getClouds() != null && j.getAuthorizationStrategy().getRootACL().hasPermission(Computer.BUILD));
        }
        else {
            hasPermission = hasBuildPermission(j.getNodes()) || hasProvisionPermission(j.clouds) || 
                           (j.clouds != null && j.getAuthorizationStrategy().getRootACL().hasPermission(Computer.BUILD));
            // l.getNodes() includes the master if this has the specified label
            // but j.getNodes() does not include the master so check explicitly.
            if (!hasPermission && j.getNumExecutors() > 0) {
                Computer c = j.toComputer();
                hasPermission = c != null && c.hasPermission(Computer.BUILD);
            }
        }
        if (!hasPermission) {
            // no nodes or clouds and the user is missing Computer.Build or Cloud.PROVISION
            // there is no AccessDeniedException2 for multiple permissions - so go with Build...
            throw new AccessDeniedException2(Jenkins.getAuthentication(),Computer.BUILD);
        }

        // defaults to the command names
        if (name==null) {
            boolean dots=false;
            if(commands.size()>3) {
                name = Util.join(commands.subList(0,3)," ");
                dots=true;
            }

            name = Util.join(commands," ");
            if(name.length()>80) {
                name=name.substring(0,80);
                dots=true;
            }
            
            if(dots)    name+=" ...";
        }

        final int[] exitCode = new int[]{-1};

        DistForkTask t = new DistForkTask(l, name, duration, new Runnable() {
            @SuppressWarnings("deprecation") // checkChannel only used in -remoting modes
            @Override
            public void run() {
                StreamTaskListener listener;
                try {
                    listener = new StreamTaskListener(stderr, getClientCharset());
                } catch (IOException | InterruptedException x) {
                    Functions.printStackTrace(x, stderr);
                    return;
                }
                try {
                    Computer c = Computer.currentComputer();
                    Node n = c.getNode();
                    if (n == null) {
                        throw new IllegalStateException("missing node " + c.getName());
                    }
                    String nodeName = n.getNodeName().isEmpty() ? "master" : n.getNodeName();
                    listener.getLogger().println("Executing on " + nodeName);
                    FilePath root = n.getRootPath();
                    if (root == null) {
                        throw new IllegalStateException(nodeName + " seems to be offline");
                    }
                    root.mkdirs(); // needed at least for mock-slave
                    FilePath workDir = root.createTempDir("distfork",null);


                    {// copy over files
                        if(zip!=null) {
                            BufferedInputStream in = new BufferedInputStream(zip.matches("=(zip|tgz)") ? stdin : new FilePath(checkChannel(), zip).read());
                            if(zip.endsWith("zip"))
                                workDir.unzipFrom(in);
                            else
                                workDir.untarFrom(in, TarCompression.GZIP);
                        }

                    }

                    List<Closeable> cleanUpList = new ArrayList<Closeable>();

                    try {
                        long startTime = c.getChannel().call(new GetSystemTime());
                        Launcher launcher = n.createLauncher(listener);
                        exitCode[0] = launcher.launch().cmds(commands)
                                .stdin(stdin).stdout(stdout).stderr(stderr).pwd(workDir).envs(envs).join();

                        if (returnZip!=null) {
                            stderr.println("Copying back files");
                            try (OutputStream os = new BufferedOutputStream(returnZip.matches("=(zip|tgz)") ? stdout : new FilePath(checkChannel(), returnZip).write())) {
                                RootCutOffFilter scanner = new RootCutOffFilter(new TimestampFilter(startTime));
                                if(returnZip.endsWith("zip")) {
                                    workDir.zip(os,scanner);
                                } else {
                                    workDir.tar(TarCompression.GZIP.compress(os),scanner);
                                }
                                os.flush();
                            }
                        }
                    } finally {
                        workDir.deleteRecursive();
                        for (Closeable cl : cleanUpList)
                            cl.close();
                    }
                } catch (InterruptedException e) {
                    listener.error("Aborted");
                    exitCode[0] = -1;
                } catch (Exception e) {
                    Functions.printStackTrace(e, listener.error("Failed to execute a process"));
                    exitCode[0] = -1;
                }
            }

            /**
             * Sets up port-forwarding.
             */
            private void setUpPortForwarding(List<PortSpec> fowrarding, VirtualChannel recv, VirtualChannel send, List<Closeable> cleanUpList) throws IOException, InterruptedException {
                for (PortSpec spec : fowrarding) {
                    Forwarder f = ForwarderFactory.create(send, spec.forwardingHost, spec.forwardingPort);
                    cleanUpList.add(PortForwarder.create(recv,spec.receivingPort, f));
                }
            }
        });

        // run and wait for the completion
        Queue.WaitingItem item = j.getQueue().schedule(t, 0);
        if (item == null) {
            throw new IllegalStateException("Could not schedule task");
        }
        Future<Executable> f = item.getFuture();
        try {
            f.get();
        } catch (CancellationException e) {
            stderr.println("Task cancelled");
            return -1;
        } catch (InterruptedException e) {
            // if the command itself is aborted, cancel the execution
            f.cancel(true);
            throw e;
        }

        return exitCode[0];
    }

    /**
     * Check if the current user has permission to build on any of the specified Nodes.
     */
    private static final boolean hasBuildPermission(Collection<? extends Node> nodes) {
        for (Node node : nodes) {
            Computer c = node.toComputer();
            if (c != null && c.hasPermission(Computer.BUILD)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the current user has permission to provision on any of the specified clouds.
     * TODO as of 2.20 this is useless and the task will just hang anyway (see test)
     */
    private static final boolean hasProvisionPermission(Collection<? extends Cloud> clouds) {
        for (Cloud cloud : clouds) {
            if (cloud.hasPermission(Cloud.PROVISION)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Obtains the system clock.
     */
    private static final class GetSystemTime extends SlaveToMasterCallable<Long,RuntimeException> {
        public Long call() {
            return System.currentTimeMillis();
        }

        private static final long serialVersionUID = 1L;
    }
}
