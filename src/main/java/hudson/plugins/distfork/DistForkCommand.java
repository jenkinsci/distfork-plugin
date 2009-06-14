package hudson.plugins.distfork;

import hudson.Extension;
import hudson.FilePath;
import hudson.FilePath.TarCompression;
import hudson.Launcher;
import hudson.Util;
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

    @Option(name="-z",usage="Zip/tgz file to be extracted into the target remote machine before execution of the command")
    public String zip;

    @Option(name="-f",usage="Local files to be copied to remote locations",metaVar="REMOTE=LOCAL")
    public Map<String,String> files = new HashMap<String,String>();


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
            if(commands.size()>2)
                name = Util.join(commands.subList(0,2)," ")+" ...";
            else
                name = Util.join(commands," ");
        }

        final int[] exitCode = new int[]{-1};

        DistForkTask t = new DistForkTask(l, name, duration, new Runnable() {
            public void run() {
                // TODO: need a way to set environment variables
                StreamTaskListener listener = new StreamTaskListener(stdout);
                FilePath workDir = null;
                try {
                    Node n = Computer.currentComputer().getNode();

                    if(zip!=null || !files.isEmpty())
                        workDir = n.getRootPath().createTempDir("distfork",null);

                    if(zip!=null) {
                        BufferedInputStream in = new BufferedInputStream(new FilePath(channel, zip).read());
                        if(zip.endsWith(".zip"))
                            workDir.unzipFrom(in);
                        else
                            workDir.untarFrom(in, TarCompression.GZIP);
                    }

                    for (Entry<String, String> e : files.entrySet())
                        new FilePath(channel,e.getValue()).copyToWithPermission(workDir.child(e.getKey()));

                    try {
                        Launcher launcher = n.createLauncher(listener);
                        exitCode[0] = launcher.launch(commands.toArray(new String[commands.size()]),
                                new String[0], stdin, stdout, workDir).join();
                    } finally {
                        if(workDir!=null)
                            workDir.deleteRecursive();
                    }
                } catch (Exception e) {
                    e.printStackTrace(listener.error("Failed to execute a process"));
                    exitCode[0] = -1;
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
        }

        return exitCode[0];
    }
}
