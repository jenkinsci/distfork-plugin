package hudson.plugins.distfork;

import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.TarCompression;
import hudson.Launcher;
import hudson.Util;
import hudson.remoting.VirtualChannel;
import hudson.remoting.forward.PortForwarder;
import hudson.remoting.forward.ForwarderFactory;
import hudson.remoting.forward.Forwarder;
import hudson.cli.CLICommand;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Queue.Executable;
import hudson.util.StreamTaskListener;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.Option;

import java.io.BufferedInputStream;
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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

    @Option(name="-n",usage="Human readable name that describe this command. Used in Hudson's UI.")
    public String name;

    @Option(name="-d",usage="Estimated duration of this task in milliseconds, or -1 if unknown")
    public long duration = -1;

    @Argument(handler=RestOfArgumentsHandler.class)
    public List<String> commands = new ArrayList<String>();

    @Option(name="-z",metaVar="FILE",
            usage="Zip/tgz file to be extracted into the target remote machine before execution of the command")
    public String zip;

    @Option(name="-Z",metaVar="FILE",
            usage="Bring back the newly added/updated files in the target remote machine after the end of the command " +
                  "by creating a zip/tgz bundle and place this in the local file system by this name.")
    public String returnZip;

    @Option(name="-e",usage="Environment variables to set to the launched process",metaVar="NAME=VAL")
    public Map<String,String> envs = new HashMap<String,String>();

    @Option(name="-f",usage="Local files to be copied to remote locations before the exeuction of a task",metaVar="REMOTE=LOCAL")
    public Map<String,String> files = new HashMap<String,String>();

    @Option(name="-F",usage="Remote files to be copied back to local locations after the execution of a task",metaVar="LOCAL=REMOTE")
    public Map<String,String> returnFiles = new HashMap<String,String>();

    @Option(name="-L",usage="Local to remote port forwarding",handler=PortForwardingArgumentHandler.class)
    public List<PortSpec> l2rFowrarding = new ArrayList<PortSpec>();

    @Option(name="-R",usage="Remote to local port forwarding",handler=PortForwardingArgumentHandler.class)
    public List<PortSpec> r2lFowrarding = new ArrayList<PortSpec>();

    public String getShortDescription() {
        return "forks a process on a remote machine and connects to its stdin/stdout";
    }

    protected int run() throws Exception {
        if(commands.isEmpty())
            throw new CmdLineException("No commands are specified");

        Hudson h = Hudson.getInstance();

        Label l = null;
        if (label!=null) {
            l = h.getLabel(label);
            if(l.isEmpty()) {
                stderr.println("No such label: "+label);
                return -1;
            }
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
            public void run() {
                StreamTaskListener listener = new StreamTaskListener(stdout);
                try {
                    Computer c = Computer.currentComputer();
                    Node n = c.getNode();
                    FilePath workDir = n.getRootPath().createTempDir("distfork",null);


                    {// copy over files
                        if(zip!=null) {
                            BufferedInputStream in = new BufferedInputStream(new FilePath(channel, zip).read());
                            if(zip.endsWith(".zip"))
                                workDir.unzipFrom(in);
                            else
                                workDir.untarFrom(in, TarCompression.GZIP);
                        }

                        for (Entry<String, String> e : files.entrySet())
                            new FilePath(channel,e.getValue()).copyToWithPermission(workDir.child(e.getKey()));
                    }

                    List<Closeable> cleanUpList = new ArrayList<Closeable>();
                    setUpPortForwarding(l2rFowrarding,channel,c.getChannel(),cleanUpList);
                    setUpPortForwarding(r2lFowrarding,c.getChannel(),channel,cleanUpList);

                    try {
                        Launcher launcher = n.createLauncher(listener);
                        exitCode[0] = launcher.launch().cmds(commands)
                                .stdin(stdin).stdout(stdout).stderr(stderr).pwd(workDir).envs(envs).join();

                        if (!returnFiles.isEmpty() || returnZip!=null) {
                            stderr.println("Copying back files");
                            for (Entry<String, String> e : returnFiles.entrySet())
                                workDir.child(e.getValue()).copyToWithPermission(new FilePath(channel,e.getKey()));

                            // TODO: support timestamp based test
                            if (returnZip!=null) {
                                FilePath zip = new FilePath(channel,returnZip);
                                if(returnZip.endsWith(".zip")) {
                                    OutputStream os = new BufferedOutputStream(zip.write());
                                    try {
                                        workDir.createZipArchive(os);
                                    } finally {
                                        os.close();
                                    }
                                } else {
                                    // TODO
                                    throw new UnsupportedOperationException();
                                }
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
                    e.printStackTrace(listener.error("Failed to execute a process"));
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
        Queue q = h.getQueue();
        Future<Executable> f = q.schedule(t, 0).getFuture();
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
}
