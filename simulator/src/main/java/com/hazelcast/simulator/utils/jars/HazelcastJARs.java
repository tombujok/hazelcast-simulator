/*
 * Copyright (c) 2008-2015, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.simulator.utils.jars;

import com.hazelcast.simulator.common.SimulatorProperties;
import com.hazelcast.simulator.utils.Bash;
import com.hazelcast.simulator.utils.CommandLineExitException;
import com.hazelcast.simulator.utils.FileUtilsException;
import org.apache.log4j.Logger;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hazelcast.simulator.utils.CommonUtils.getSimulatorVersion;
import static com.hazelcast.simulator.utils.FileUtils.USER_HOME;
import static com.hazelcast.simulator.utils.FileUtils.copyFilesToDirectory;
import static com.hazelcast.simulator.utils.FileUtils.ensureExistingDirectory;
import static com.hazelcast.simulator.utils.FileUtils.getText;
import static com.hazelcast.simulator.utils.FileUtils.newFile;
import static java.lang.String.format;

/**
 * Provides and uploads the correct Hazelcast JARs for a configured Hazelcast version.
 */
public class HazelcastJARs {

    public static final String GIT_VERSION_PREFIX = "git=";
    public static final String MAVEN_VERSION_PREFIX = "maven=";

    public static final String OUT_OF_THE_BOX = "outofthebox";
    public static final String BRING_MY_OWN = "bringmyown";

    private static final Logger LOGGER = Logger.getLogger(HazelcastJARs.class);

    private final Map<String, File> versionSpecDirs = new HashMap<String, File>();

    private final Bash bash;
    private final GitSupport gitSupport;

    HazelcastJARs(Bash bash, GitSupport gitSupport) {
        this.bash = bash;
        this.gitSupport = gitSupport;
    }

    public static HazelcastJARs newInstance(Bash bash, SimulatorProperties properties, Set<String> versionSpecs) {
        HazelcastJARs hazelcastJARs = new HazelcastJARs(bash, GitSupport.newInstance(bash, properties));
        for (String versionSpec : versionSpecs) {
            hazelcastJARs.addVersionSpec(versionSpec);
        }
        return hazelcastJARs;
    }

    public static String directoryForVersionSpec(String versionSpec) {
        if (BRING_MY_OWN.equals(versionSpec)) {
            return null;
        }
        if (OUT_OF_THE_BOX.equals(versionSpec)) {
            return "outofthebox";
        }
        return versionSpec.replace('=', '-');
    }

    public void prepare(boolean prepareEnterpriseJARs) {
        for (Map.Entry<String, File> versionSpecEntry : versionSpecDirs.entrySet()) {
            prepare(versionSpecEntry.getKey(), versionSpecEntry.getValue(), prepareEnterpriseJARs);
        }
    }

    public void upload(String ip, String simulatorHome, Set<String> versionSpecs) {
        for (String versionSpec : versionSpecs) {
            // create target directory
            String versionDir = directoryForVersionSpec(versionSpec);
            if (versionDir != null) {
                bash.ssh(ip, format("mkdir -p hazelcast-simulator-%s/hz-lib/%s", getSimulatorVersion(), versionDir));
            }

            if (OUT_OF_THE_BOX.equals(versionSpec)) {
                // upload Hazelcast JARs
                bash.uploadToRemoteSimulatorDir(ip, simulatorHome + "/lib/hazelcast*", "hz-lib/outofthebox");
            } else if (!BRING_MY_OWN.equals(versionSpec)) {
                // upload the actual Hazelcast JARs that are going to be used by the worker
                File versionSpecDir = versionSpecDirs.get(versionSpec);
                bash.uploadToRemoteSimulatorDir(ip, versionSpecDir + "/*.jar", "hz-lib/" + versionDir);
            }
        }
    }

    void addVersionSpec(String versionSpec) {
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        versionSpecDirs.put(versionSpec, new File(tmpDir, "hazelcastjars-" + UUID.randomUUID().toString()).getAbsoluteFile());
    }

    // just for testing
    Set<String> getVersionSpecs() {
        return versionSpecDirs.keySet();
    }

    // just for testing
    String getAbsolutePath(String versionSpec) {
        return versionSpecDirs.get(versionSpec).getAbsolutePath();
    }

    private void prepare(String versionSpec, File targetDir, boolean prepareEnterpriseJARs) {
        LOGGER.info("Hazelcast version-spec: " + versionSpec);
        if (OUT_OF_THE_BOX.equals(versionSpec) || BRING_MY_OWN.equals(versionSpec)) {
            // we don't need to do anything
            return;
        }

        ensureExistingDirectory(targetDir);

        if (versionSpec.startsWith(GIT_VERSION_PREFIX)) {
            if (prepareEnterpriseJARs) {
                throw new CommandLineExitException(
                        "Hazelcast Enterprise is currently not supported when HAZELCAST_VERSION_SPEC is set to Git.");
            }
            String revision = versionSpec.substring(GIT_VERSION_PREFIX.length());
            gitRetrieve(targetDir, revision);
        } else if (versionSpec.startsWith(MAVEN_VERSION_PREFIX)) {
            String version = versionSpec.substring(MAVEN_VERSION_PREFIX.length());
            if (prepareEnterpriseJARs) {
                mavenRetrieve(targetDir, "hazelcast-enterprise", version);
                mavenRetrieve(targetDir, "hazelcast-enterprise-client", version);
            } else {
                mavenRetrieve(targetDir, "hazelcast", version);
                mavenRetrieve(targetDir, "hazelcast-client", version);
                mavenRetrieve(targetDir, "hazelcast-wm", version);
            }
        } else {
            throw new CommandLineExitException("Unrecognized version spec: " + versionSpecDirs);
        }
    }

    private void gitRetrieve(File targetDir, String revision) {
        File[] files = gitSupport.checkout(revision);
        copyFilesToDirectory(files, targetDir);
    }

    private void mavenRetrieve(File targetDir, String artifact, String version) {
        File artifactFile = getArtifactFile(artifact, version);
        if (artifactFile.exists()) {
            LOGGER.info("Using artifact: " + artifactFile + " from local maven repository");
            bash.execute(format("cp %s %s", artifactFile.getAbsolutePath(), targetDir));
            return;
        }

        LOGGER.info("Artifact: " + artifactFile + " is not found in local maven repository, trying online one");
        String url;
        if (version.endsWith("-SNAPSHOT")) {
            url = getSnapshotUrl(artifact, version);
        } else {
            url = getReleaseUrl(artifact, version);
        }
        bash.download(url, targetDir.getAbsolutePath());
    }

    File getArtifactFile(String artifact, String version) {
        File repositoryDir = newFile(USER_HOME, ".m2", "repository");
        return newFile(repositoryDir, "com", "hazelcast", artifact, version, format("%s-%s.jar", artifact, version));
    }

    String getSnapshotUrl(String artifact, String version) {
        String baseUrl = "https://oss.sonatype.org/content/repositories/snapshots";
        String mavenMetadata = getMavenMetadata(artifact, version, baseUrl);
        LOGGER.debug(mavenMetadata);

        String shortVersion = version.replace("-SNAPSHOT", "");
        String timestamp = getTagValue(mavenMetadata, "timestamp");
        String buildNumber = getTagValue(mavenMetadata, "buildNumber");
        return format("%s/com/hazelcast/%s/%s/%s-%s-%s-%s.jar", baseUrl, artifact, version, artifact, shortVersion, timestamp,
                buildNumber);
    }

    String getReleaseUrl(String artifact, String version) {
        String baseUrl = "http://repo1.maven.org/maven2";
        return format("%s/com/hazelcast/%s/%s/%s-%s.jar", baseUrl, artifact, version, artifact, version);
    }

    String getMavenMetadata(String artifact, String version, String baseUrl) {
        String mavenMetadataUrl = format("%s/com/hazelcast/%s/%s/maven-metadata.xml", baseUrl, artifact, version);
        LOGGER.debug("Loading: " + mavenMetadataUrl);
        try {
            return getText(mavenMetadataUrl);
        } catch (FileUtilsException e) {
            throw new CommandLineExitException("Could not load " + mavenMetadataUrl, e);
        }
    }

    String getTagValue(String mavenMetadata, String tag) {
        Pattern pattern = Pattern.compile('<' + tag + ">(.+?)</" + tag + '>');
        Matcher matcher = pattern.matcher(mavenMetadata);
        if (!matcher.find()) {
            throw new CommandLineExitException("Could not find " + tag + " in " + mavenMetadata);
        }

        return matcher.group(1);
    }
}
