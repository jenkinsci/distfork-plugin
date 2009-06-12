package hudson.plugins.distfork;

import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.OptionDef;

/**
 * Eagerly grabs all the arguments.
 *
 * <p>
 * Used with {@link Argument}, this implements a semantics where
 * non-option token causes the option parsing to terminate.
 * An example of this is ssh(1), where "ssh -p 222 abc" will treat "-p" as an option
 * to ssh but "ssh abc -p 222" is considered to have no option for ssh.
 *
 * TODO: to be moved to args4j once I confirmed that this works.
 *
 * @author Kohsuke Kawaguchi
 */
public class RestOfArgumentsHandler extends OptionHandler {
    public RestOfArgumentsHandler(CmdLineParser cmdLineParser, OptionDef optionDef, Setter setter) {
        super(cmdLineParser, optionDef, setter);
    }

    public int parseArguments(Parameters parameters) throws CmdLineException {
        // TODO: hack until we get the Parameters.size() method in the newer args4j
        int i;
        for (i=0; ; i++) {
            try {
                setter.addValue(parameters.getParameter(i));
            } catch (CmdLineException e) {
                return i;
            }
        }
    }

    public String getDefaultMetaVariable() {
        return "ARGS";
    }
}
