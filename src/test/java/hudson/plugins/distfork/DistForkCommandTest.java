/*
 * The MIT License
 *
 * Copyright 2016 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.distfork;

import hudson.cli.CLI;
import hudson.slaves.DumbSlave;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import org.apache.commons.io.input.NullInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.hamcrest.core.StringContains.containsString;
import static org.hamcrest.core.AllOf.allOf;
import static org.junit.Assert.assertThat;

public class DistForkCommandTest {

    @Rule
    public JenkinsRule jr = new JenkinsRule();

    @Test
    public void testRunOnMaster() throws Exception {
        // whoami seems to be in both windows, linux and osx.
        String result = commandAndOutput("dist-fork", "-l", "master", "whoami");
        assertThat(result, allOf( containsString("Executing on master"), containsString(System.getProperty("user.name") )));
    }

    @Test
    public void testRunOnSlave() throws Exception {
        DumbSlave slave = jr.createOnlineSlave(jr.jenkins.getLabelAtom("slavelabel"));

        // whoami seems to be in both windows, linux and osx.
        String result = commandAndOutput("dist-fork", "-l", "slavelabel", "whoami");
        assertThat(result, allOf( containsString("Executing on " + slave.getNodeName()), containsString(System.getProperty("user.name") )));
    }

    private String commandAndOutput(String... args) throws Exception {
        CLI cli = new CLI(jr.getURL());
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            cli.execute(Arrays.asList(args), new NullInputStream(0), baos, baos);
            return baos.toString();
        } finally {
            cli.close();
        }
    }
}
