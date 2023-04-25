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

import hudson.Functions;
import hudson.Launcher;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.io.input.NullInputStream;
import org.apache.commons.lang.StringUtils;
import org.jenkinci.plugins.mock_slave.MockCloud;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

import hudson.cli.CLI;
import hudson.cli.CLICommandInvoker;
import hudson.model.Computer;
import hudson.model.Item;
import hudson.model.Node.Mode;
import hudson.model.Queue;
import hudson.model.User;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.AccessDeniedException2;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import hudson.security.HudsonPrivateSecurityRealm;
import hudson.slaves.Cloud;
import hudson.slaves.DumbSlave;
import java.io.File;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayInputStream;
import java.util.logging.Level;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import jenkins.model.Jenkins;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jenkinsci.test.acceptance.docker.fixtures.JavaContainer;
import static org.hamcrest.Matchers.*;
import org.jenkinsci.test.acceptance.docker.DockerRule;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.LoggerRule;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;

public class DistForkCommandTest {

    @Rule
    public JenkinsRule jr = new JenkinsRule();

    @Rule
    public LoggerRule logging = new LoggerRule();

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // could use DockerClassRule only if moved to another test suite
    @Rule
    public DockerRule<JavaContainer> docker = new DockerRule<>(JavaContainer.class);

    /** JENKINS_24752: otherwise {@link #testUserWithBuildAccessOnCloud} waits a long time */
    @BeforeClass
    public static void runFaster() {
        System.setProperty("hudson.model.LoadStatistics.clock", "1000");
    }

    private static String whoIAM;
    @BeforeClass
    public static void whoAmI() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        assumeThat(new Launcher.LocalLauncher(StreamTaskListener.fromStderr()).launch().cmds("whoami").stdout(baos).join(), is(0));
        whoIAM = baos.toString().trim();
    }

    @Test
    public void testRunOnMaster() throws Exception {
        String result = commandAndOutput(null, "-l", "built-in", "whoami");
        assertThat(result, allOf( containsString("Executing on master"), containsString(whoIAM)));
    }

    @Test
    public void testRunOnSlave() throws Exception {
        DumbSlave slave = jr.createOnlineSlave(jr.jenkins.getLabelAtom("slavelabel"));
        String result = commandAndOutput(null, "-l", "slavelabel", "whoami");
        assertThat(result, allOf( containsString("Executing on " + slave.getNodeName()), containsString(whoIAM)));
    }

    @Test
    public void testNoLabel() throws Exception {
        String result = commandAndOutput(null, "whoami");
        assertThat(result, allOf( containsString("Executing on "), containsString(whoIAM)));
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
        
        String result = commandAndOutput(null, "-l", "built-in", "whoami");
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
        
        String result = commandAndOutput("bob", "-l", "built-in", "whoami");
        assertThat(result, allOf( containsString("Executing on master"), containsString(whoIAM)));
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
        
        String result = commandAndOutput("alice", "-l", "built-in", "whoami");
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
        MockCloud cloud = new MockCloud("");
        cloud.setLabels("cloud");
        cloud.setOneShot(true);
        jr.jenkins.clouds.add(cloud);

        String result = commandAndOutput("bob", "-l", "cloud", "whoami");
        assertThat(result, allOf( containsString("Executing on mock-"), containsString(whoIAM)));
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
        
        MockCloud cloud = new MockCloud("");
        cloud.setLabels("cloud");
        cloud.setOneShot(true);
        jr.jenkins.clouds.add(cloud);

        String result = commandAndOutput("bob", "-l", "cloud", "whoami");
        assertThat(result, allOf( containsString("Executing on mock-"), containsString(whoIAM)));
    }

    @SuppressWarnings("deprecation")
    private String commandAndOutput(String user, String... args) throws Exception {
        CLICommandInvoker cliInvoker = new CLICommandInvoker(jr, "dist-fork");
        if (user != null) {
            cliInvoker.asUser(user);
        }
        CLICommandInvoker.Result result = cliInvoker.invokeWithArgs(args);
        return StringUtils.defaultString(result.stdout()) + StringUtils.defaultString(result.stderr());
    }

    private void registerSlave() throws Exception {
        StandardUsernameCredentials credentials = new UsernamePasswordCredentialsImpl(CredentialsScope.SYSTEM, "credentialId", "description", "test", "test");
        SystemCredentialsProvider.getInstance().getDomainCredentialsMap().put(Domain.global(), List.of(credentials));
        JavaContainer c = docker.get();
        DumbSlave s = new DumbSlave("docker", "/home/test/slave", new SSHLauncher(c.ipBound(22), c.port(22), credentials.getId()));
        jr.jenkins.addNode(s);
        jr.waitOnline(s);
    }

    @Issue("JENKINS-49205")
    @Test
    public void plainCLIStdinFileTransfersMaster() throws Exception {
        assumeFalse("TODO would need to write an equivalent batch script", Functions.isWindows());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry ze = new ZipEntry("a");
            zos.putNextEntry(ze);
            zos.write("hello ".getBytes());
            zos.closeEntry();
            ze = new ZipEntry("b");
            zos.putNextEntry(ze);
            zos.write("world".getBytes());
            zos.closeEntry();
        }
        CLICommandInvoker.Result r = new CLICommandInvoker(jr, new DistForkCommand()).
            withStdin(new ByteArrayInputStream(baos.toByteArray())).
            // TODO sleep necessary because TimestampFilter otherwise excludes files created immediately at start of process
            invokeWithArgs("-z", "=zip", "-Z", "=zip", "sh", "-c", "sleep 1; cat a b > c; rm a b");
        assertThat(r, CLICommandInvoker.Matcher.succeeded());
        try (ByteArrayInputStream bais = new ByteArrayInputStream(r.stdoutBinary()); ZipInputStream zis = new ZipInputStream(bais)) {
            ZipEntry ze = zis.getNextEntry();
            assertNotNull(ze);
            assertEquals("c", ze.getName());
            assertEquals("hello world", IOUtils.toString(zis));
            assertNull(zis.getNextEntry());
        }
    }

    @Issue("JENKINS-49205")
    @Test
    public void plainCLIStdinFileTransfersSlave() throws Exception {
        registerSlave();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry ze = new ZipEntry("a");
            zos.putNextEntry(ze);
            zos.write("hello ".getBytes());
            zos.closeEntry();
            ze = new ZipEntry("b");
            zos.putNextEntry(ze);
            zos.write("world".getBytes());
            zos.closeEntry();
        }
        CLICommandInvoker.Result r = new CLICommandInvoker(jr, new DistForkCommand()).
            withStdin(new ByteArrayInputStream(baos.toByteArray())).
            invokeWithArgs("-l", "docker", "-z", "=zip", "-Z", "=zip", "sh", "-c", "sleep 1; cat a b > c; rm a b");
        assertThat(r, CLICommandInvoker.Matcher.succeeded());
        try (ByteArrayInputStream bais = new ByteArrayInputStream(r.stdoutBinary()); ZipInputStream zis = new ZipInputStream(bais)) {
            ZipEntry ze = zis.getNextEntry();
            assertNotNull(ze);
            assertEquals("c", ze.getName());
            assertEquals("hello world", IOUtils.toString(zis));
            assertNull(zis.getNextEntry());
        }
    }

}
