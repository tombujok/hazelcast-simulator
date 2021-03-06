package com.hazelcast.simulator.utils.jars;

import com.hazelcast.simulator.common.SimulatorProperties;
import com.hazelcast.simulator.utils.Bash;
import com.hazelcast.simulator.utils.CommandLineExitException;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static com.hazelcast.simulator.TestEnvironmentUtils.deleteLogs;
import static com.hazelcast.simulator.utils.FileUtils.deleteQuiet;
import static com.hazelcast.simulator.utils.FileUtils.ensureExistingDirectory;
import static com.hazelcast.simulator.utils.FileUtils.ensureExistingFile;
import static com.hazelcast.simulator.utils.FileUtils.getSimulatorHome;
import static com.hazelcast.simulator.utils.jars.HazelcastJARs.BRING_MY_OWN;
import static com.hazelcast.simulator.utils.jars.HazelcastJARs.OUT_OF_THE_BOX;
import static com.hazelcast.simulator.utils.jars.HazelcastJARs.directoryForVersionSpec;
import static com.hazelcast.simulator.utils.jars.HazelcastJARs.newInstance;
import static java.util.Collections.singleton;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.contains;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class HazelcastJARsTest {

    private Bash bash = mock(Bash.class);
    private GitSupport gitSupport = mock(GitSupport.class);

    @After
    public void tearDown() {
        deleteLogs();
    }

    @Test
    public void testNewInstance() {
        SimulatorProperties properties = mock(SimulatorProperties.class);
        HazelcastJARs hazelcastJARs = newInstance(bash, properties, Collections.<String>emptySet());

        assertEquals(0, hazelcastJARs.getVersionSpecs().size());
    }

    @Test
    public void testNewInstance_withVersionSpecs() {
        SimulatorProperties properties = mock(SimulatorProperties.class);
        Set<String> versionSpecs = new HashSet<String>();
        versionSpecs.add("maven=3.5.1");
        versionSpecs.add("maven=3.5.2");
        versionSpecs.add("maven=3.5.3");

        HazelcastJARs hazelcastJARs = newInstance(bash, properties, versionSpecs);

        assertEquals(3, hazelcastJARs.getVersionSpecs().size());
    }

    @Test
    public void testDirectoryForVersionSpec() {
        assertNull(directoryForVersionSpec(BRING_MY_OWN));
        assertEquals("outofthebox", directoryForVersionSpec(OUT_OF_THE_BOX));
        assertEquals("git-tag-3.6", directoryForVersionSpec("git=tag=3.6"));
        assertEquals("maven-3.6", directoryForVersionSpec("maven=3.6"));
    }

    @Test
    public void testPrepare_outOfTheBox() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs(OUT_OF_THE_BOX);
        hazelcastJARs.prepare(false);
    }

    @Test
    public void testPrepare_bringMyOwn() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs(BRING_MY_OWN);
        hazelcastJARs.prepare(false);
    }

    @Test
    public void testPrepare_git() {
        when(gitSupport.checkout("tag=8.7")).thenReturn(new File[]{});

        HazelcastJARs hazelcastJARs = getHazelcastJARs("git=tag=8.7");
        hazelcastJARs.prepare(false);
    }

    @Test(expected = CommandLineExitException.class)
    public void testPrepare_git_enterpriseEnabled() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs("git=tag=3.5.3");
        hazelcastJARs.prepare(true);
    }

    @Test
    public void testPrepare_maven_existingRelease() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs("maven=8.7");
        File artifactFile = hazelcastJARs.getArtifactFile("hazelcast", "8.7");
        File artifactDir = new File(artifactFile.getParent());
        try {
            ensureExistingDirectory(artifactDir);
            ensureExistingFile(artifactFile);

            hazelcastJARs.prepare(false);
        } finally {
            deleteQuiet(artifactFile);
            deleteQuiet(artifactDir);
        }

        verify(bash, times(1)).execute(anyString());
        verify(bash, atLeastOnce()).download(anyString(), anyString());
        verifyNoMoreInteractions(bash);
    }

    @Test
    public void testPrepare_maven_invalidRelease_enterpriseEnabled() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs("maven=8.7");
        hazelcastJARs.prepare(true);

        verify(bash, atLeastOnce()).download(anyString(), anyString());
        verifyNoMoreInteractions(bash);
    }

    @Test(expected = CommandLineExitException.class)
    public void testPrepare_maven_invalidSNAPSHOT() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs("maven=8.7-SNAPSHOT");
        hazelcastJARs.prepare(false);
    }

    @Test(expected = CommandLineExitException.class)
    public void testPrepare_invalidVersionSpec() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs("invalidSpec");
        hazelcastJARs.prepare(false);
    }

    @Test
    public void testUpload() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs("maven=3.6");
        String sourceDir = hazelcastJARs.getAbsolutePath("maven=3.6");
        String targetDir = directoryForVersionSpec("maven=3.6");

        hazelcastJARs.upload("127.0.0.1", "simulatorHome", singleton("maven=3.6"));

        verify(bash, times(1)).ssh(eq("127.0.0.1"), contains(targetDir));
        verify(bash, times(1)).uploadToRemoteSimulatorDir(eq("127.0.0.1"), contains(sourceDir), anyString());
        verifyNoMoreInteractions(bash);
    }

    @Test
    public void testUpload_outOfTheBox() {
        String targetDir = directoryForVersionSpec(OUT_OF_THE_BOX);
        HazelcastJARs hazelcastJARs = getHazelcastJARs(OUT_OF_THE_BOX);
        hazelcastJARs.upload("127.0.0.1", "simulatorHome", singleton(OUT_OF_THE_BOX));

        verify(bash, times(1)).ssh(eq("127.0.0.1"), contains(targetDir));
        verify(bash, times(1)).uploadToRemoteSimulatorDir(eq("127.0.0.1"), contains("simulatorHome"), anyString());
        verifyNoMoreInteractions(bash);
    }

    @Test
    public void testUpload_bringMyOwn() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs(BRING_MY_OWN);
        hazelcastJARs.upload("127.0.0.1", getSimulatorHome().getAbsolutePath(), singleton(BRING_MY_OWN));

        verifyNoMoreInteractions(bash);
    }

    @Test
    public void testGetSnapshotUrl() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs();

        String url = hazelcastJARs.getSnapshotUrl("hazelcast", "3.6-SNAPSHOT");
        assertTrue(url.contains("hazelcast"));
        assertTrue(url.contains("3.6-SNAPSHOT"));
    }

    @Test(expected = CommandLineExitException.class)
    public void testGetSnapshotUrl_invalidVersion() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs();

        hazelcastJARs.getSnapshotUrl("hazelcast", "8.7-SNAPSHOT");
    }

    @Test
    public void testGetReleaseUrl() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs();

        String url = hazelcastJARs.getReleaseUrl("hazelcast", "3.5.3");
        assertTrue(url.contains("hazelcast"));
        assertTrue(url.contains("3.5.3"));
    }

    @Test
    public void testGetTagValue() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs();

        String buildNumber = hazelcastJARs.getTagValue(getMavenMetadata(), "buildNumber");
        assertEquals("565", buildNumber);
    }

    @Test(expected = CommandLineExitException.class)
    public void testGetTagValue_invalidTag() {
        HazelcastJARs hazelcastJARs = getHazelcastJARs();

        hazelcastJARs.getTagValue(getMavenMetadata(), "notFound");
    }

    private HazelcastJARs getHazelcastJARs() {
        return getHazelcastJARs(OUT_OF_THE_BOX);
    }

    private HazelcastJARs getHazelcastJARs(String version) {
        HazelcastJARs hazelcastJARs = new HazelcastJARs(bash, gitSupport);
        hazelcastJARs.addVersionSpec(version);
        return hazelcastJARs;
    }

    private String getMavenMetadata() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<metadata>  <groupId>com.hazelcast</groupId>" +
                "  <artifactId>hazelcast</artifactId>" +
                "  <version>3.6-SNAPSHOT</version>" +
                "  <versioning>" +
                "    <snapshot>" +
                "      <timestamp>20151018.215739</timestamp>" +
                "      <buildNumber>565</buildNumber>" +
                "    </snapshot>" +
                "    <lastUpdated>20151018215739</lastUpdated>" +
                "  </versioning>" +
                "</metadata>";
    }
}
