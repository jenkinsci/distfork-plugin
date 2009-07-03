package hudson.plugins.distfork;

import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Setter;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.CmdLineException;

/**
 * @author Kohsuke Kawaguchi
 */
public class PortForwardingArgumentHandler extends OptionHandler {
    public PortForwardingArgumentHandler(CmdLineParser cmdLineParser, OptionDef optionDef, Setter setter) {
        super(cmdLineParser, optionDef, setter);
    }

    public int parseArguments(Parameters parameters) throws CmdLineException {
        String arg = parameters.getParameter(0);
        String[] tokens = arg.split(":");
        if(tokens.length!=3)
            throw new CmdLineException("Illegal port forwarding specification: "+arg);
        try {
            setter.addValue(new PortSpec(
                    Integer.parseInt(tokens[0]),tokens[1],Integer.parseInt(tokens[2])));
            return 1;
        } catch (NumberFormatException e) {
            throw new CmdLineException("Illegal port forwarding specification: "+arg);
        }
    }

    public String getDefaultMetaVariable() {
        return "PORT:HOST:PORT";
    }
}
