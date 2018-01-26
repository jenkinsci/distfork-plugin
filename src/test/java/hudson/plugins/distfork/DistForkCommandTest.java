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

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import org.apache.commons.io.input.NullInputStream;
import org.jenkinci.plugins.mock_slave.MockCloud;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

import hudson.cli.CLI;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Node.Mode;
import hudson.model.Queue;
import hudson.model.User;
import hudson.security.AccessDeniedException2;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.slaves.Cloud;
import hudson.slaves.DumbSlave;
import java.util.logging.Level;
import jenkins.model.Jenkins;

import static org.hamcrest.core.AllOf.allOf;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.Assert.assertThat;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.jvnet.hudson.test.LoggerRule;

public class DistForkCommandTest {

    @Rule
    public JenkinsRule jr = new JenkinsRule();

    @Rule
    public LoggerRule logging = new LoggerRule();

    /** JENKINS_24752: otherwise {@link #testUserWithBuildAccessOnCloud} waits a long time */
    @BeforeClass
    public static void runFaster() {
        System.setProperty("hudson.model.LoadStatistics.clock", "1000");
    }

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

    @Test
    public void testNoLabel() throws Exception {
        // whoami seems to be in both windows, linux and osx.
        String result = commandAndOutput("dist-fork", "whoami");
        assertThat(result, allOf( containsString("Executing on "), containsString(System.getProperty("user.name") )));
    }

    @Test
    @Issue("SECURITY-386")
    public void testAnonymousAccess() throws Exception {
        // an anoymous user with just Jenkins.READ should not be able to run this command.
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        realm.createAccount("alice","alice");
        realm.createAccount("bob","bob");
        
        GlobalMatrixAuthorizationStrategy authz = new GlobalMatrixAuthorizationStrategy();
        authz.add(Computer.BUILD, "bob");
        authz.add(Jenkins.READ, "bob");
        authz.add(Jenkins.READ, Jenkins.ANONYMOUS.getName());
        jr.jenkins.setSecurityRealm(realm);
        jr.jenkins.setAuthorizationStrategy(authz);
        
        String result = commandAndOutput("dist-fork", "-l", "master", "whoami");
        assertThat(result, containsString(new AccessDeniedException2(Jenkins.ANONYMOUS, Computer.BUILD).getMessage()));
    }

    @Test
    @Issue("SECURITY-386")
    public void testUserWithBuildAccess() throws Exception {
        // a user with Computer.BUILD should be able to run this command.
        
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        realm.createAccount("alice","alice");
        realm.createAccount("bob","bob");
        
        GlobalMatrixAuthorizationStrategy authz = new GlobalMatrixAuthorizationStrategy();
        authz.add(Computer.BUILD, "bob");
        authz.add(Jenkins.READ, "bob");
        authz.add(Jenkins.READ, Jenkins.ANONYMOUS.getName());
        jr.jenkins.setSecurityRealm(realm);
        jr.jenkins.setAuthorizationStrategy(authz);
        
        String result = commandAndOutput("dist-fork", "--username=bob", "--password=bob", "-l", "master", "whoami");
        assertThat(result, allOf( containsString("Executing on master"), containsString(System.getProperty("user.name") )));
    }

    @Test
    @Issue("SECURITY-386")
    public void testUserWithOutBuildAccess() throws Exception {
        // a user without Computer.BUILD should be able to run this command.
        
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        realm.createAccount("alice","alice");
        realm.createAccount("bob","bob");
        
        GlobalMatrixAuthorizationStrategy authz = new GlobalMatrixAuthorizationStrategy();
        authz.add(Computer.BUILD, "bob");
        authz.add(Jenkins.READ, "bob");
        authz.add(Jenkins.READ, "alice");
        authz.add(Item.BUILD, "alice");
        authz.add(Jenkins.READ, Jenkins.ANONYMOUS.getName());
        jr.jenkins.setSecurityRealm(realm);
        jr.jenkins.setAuthorizationStrategy(authz);
        
        String result = commandAndOutput("dist-fork", "--username=alice", "--password=alice", "-l", "master", "whoami");
        assertThat(result, containsString(new AccessDeniedException2(User.getById("alice", false).impersonate(), Computer.BUILD).getMessage()));
    }

    @Ignore("TODO against baseline when written, this was meaningless (bob was an admin); as of JENKINS-37616, it fails in the expected way: the task hangs in the queue because you need Computer.BUILD")
    @Test
    @Issue("SECURITY-386")
    public void testUserWithProvisionAccess() throws Exception {
        logging.record(Queue.class, Level.FINEST);
        // a user without Computer.BUILD should be able to run this command.
        
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        realm.createAccount("alice","alice");
        realm.createAccount("bob","bob");
        
        GlobalMatrixAuthorizationStrategy authz = new GlobalMatrixAuthorizationStrategy();
        //authz.add(Computer.BUILD, "bob");
        authz.add(Jenkins.READ, "bob");
        authz.add(Cloud.PROVISION, "bob");
        authz.add(Jenkins.READ, "alice");
        authz.add(Item.BUILD, "alice");
        authz.add(Jenkins.READ, Jenkins.ANONYMOUS.getName());
        jr.jenkins.setSecurityRealm(realm);
        jr.jenkins.setAuthorizationStrategy(authz);
        
        jr.jenkins.clouds.add(new MockCloud("Mock Cloud", Mode.NORMAL, 1, "cloud", true));
        
        String result = commandAndOutput("dist-fork", "--username=bob", "--password=bob", "-l", "cloud", "whoami");
        assertThat(result, allOf( containsString("Executing on mock-"), containsString(System.getProperty("user.name") )));
    }
    
    @Test
    @Issue("SECURITY-386")
    public void testUserWithBuildAccessOnCloud() throws Exception {
        logging.record(Queue.class, Level.FINEST);
        // a user without Cloud.PROVISION should be able to run this command.
        
        HudsonPrivateSecurityRealm realm = new HudsonPrivateSecurityRealm(false, false, null);
        realm.createAccount("alice","alice");
        realm.createAccount("bob","bob");
        
        GlobalMatrixAuthorizationStrategy authz = new GlobalMatrixAuthorizationStrategy();
        authz.add(Computer.BUILD, "bob");
        authz.add(Jenkins.READ, "bob");
        //authz.add(Cloud.PROVISION, "bob");
        authz.add(Jenkins.READ, "alice");
        authz.add(Item.BUILD, "alice");
        authz.add(Jenkins.READ, Jenkins.ANONYMOUS.getName());
        jr.jenkins.setSecurityRealm(realm);
        jr.jenkins.setAuthorizationStrategy(authz);
        
        jr.jenkins.clouds.add(new MockCloud("Mock Cloud", Mode.NORMAL, 1, "cloud", true));
        
        String result = commandAndOutput("dist-fork", "--username=bob", "--password=bob", "-l", "cloud", "whoami");
        assertThat(result, allOf( containsString("Executing on mock-"), containsString(System.getProperty("user.name") )));
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
