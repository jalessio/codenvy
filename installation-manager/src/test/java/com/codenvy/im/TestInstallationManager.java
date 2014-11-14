/*
 * CODENVY CONFIDENTIAL
 * __________________
 *
 *  [2012] - [2014] Codenvy, S.A.
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Codenvy S.A. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Codenvy S.A.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Codenvy S.A..
 */
package com.codenvy.im;


import com.codenvy.im.artifacts.Artifact;
import com.codenvy.im.artifacts.CDECArtifact;
import com.codenvy.im.artifacts.InstallManagerArtifact;
import com.codenvy.im.installer.InstallOptions;
import com.codenvy.im.installer.InstallStartedException;
import com.codenvy.im.installer.Installer;
import com.codenvy.im.restlet.InstallationManagerConfig;
import com.codenvy.im.user.UserCredentials;
import com.codenvy.im.utils.AccountUtils;
import com.codenvy.im.utils.HttpTransport;
import com.codenvy.im.utils.HttpTransportConfiguration;
import com.codenvy.im.utils.Version;

import org.apache.commons.io.FileUtils;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static com.codenvy.im.artifacts.ArtifactProperties.AUTHENTICATION_REQUIRED_PROPERTY;
import static com.codenvy.im.artifacts.ArtifactProperties.SUBSCRIPTION_PROPERTY;
import static org.mockito.Matchers.anyMap;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.endsWith;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/**
 * @author Anatoliy Bazko
 */
public class TestInstallationManager {

    private Artifact cdecArtifact;
    private Artifact installManagerArtifact;

    private InstallationManagerImpl manager;
    private HttpTransport           transport;

    private UserCredentials testCredentials;

    @BeforeMethod
    public void setUp() throws Exception {
        transport = mock(HttpTransport.class);

        installManagerArtifact = spy(new InstallManagerArtifact());
        cdecArtifact = spy(new CDECArtifact("update/endpoint", transport));

        manager = spy(new InstallationManagerImpl("api/endpoint",
                                                  "http://update.com/endpoint",
                                                  "target/download",
                                                  new HttpTransportConfiguration("", "0"),
                                                  transport,
                                                  new HashSet<>(Arrays.asList(installManagerArtifact, cdecArtifact))));

        testCredentials = new UserCredentials("auth token", "accountId");
    }

    @AfterMethod
    public void tearDown() throws Exception {
        FileUtils.deleteDirectory(Paths.get("target", "download").toFile());
    }

    @Test(expectedExceptions = IllegalStateException.class,
          expectedExceptionsMessageRegExp = "Can not install the artifact 'installation-manager' version '2.10.1', " +
                                            "because greater or equal version has already been installed.")
    public void testReInstallAlreadyInstalledArtifact() throws Exception {
        doReturn(new HashMap<Artifact, SortedMap<Version, Path>>() {{
            put(installManagerArtifact, new TreeMap<Version, Path>() {{
                put(Version.valueOf("2.10.1"), Paths.get("target/download/installation-manager/2.10.1/file1"));
            }});
        }}).when(manager).getDownloadedArtifacts();
        doReturn("2.10.1").when(installManagerArtifact).getInstalledVersion(testCredentials.getToken());

        when(transport.doOption(endsWith("api/"), anyString())).thenReturn("{\"ideVersion\":\"1.0.1\"}");

        manager.install(testCredentials.getToken(), installManagerArtifact, "2.10.1", null);

        verify(installManagerArtifact, never()).install(any(Path.class), null);
    }

    @Test(expectedExceptions = FileNotFoundException.class)
    public void testInstallArtifactErrorIfBinariesNotFound() throws Exception {
        doReturn(null).when(cdecArtifact).getInstalledVersion(testCredentials.getToken());

        manager.install(testCredentials.getToken(), cdecArtifact, "2.10.1", null);
    }

    @Test
    public void testInstallArtifact() throws Exception {
        doReturn(new HashMap<Artifact, SortedMap<Version, Path>>() {{
            put(cdecArtifact, new TreeMap<Version, Path>() {{
                put(Version.valueOf("1.0.1"), Paths.get("target/download/cdec/1.0.1/file1"));
            }});
        }}).when(manager).getDownloadedArtifacts();
        doNothing().when(cdecArtifact).install(any(Path.class), any(InstallOptions.class));
        doReturn(null).when(cdecArtifact).getInstalledVersion(testCredentials.getToken());

        manager.install(testCredentials.getToken(), cdecArtifact, "1.0.1", null);
        verify(cdecArtifact).install(any(Path.class), any(InstallOptions.class));
    }

    @Test(expectedExceptions = IllegalStateException.class,
          expectedExceptionsMessageRegExp = "Can not install the artifact 'installation-manager' version '2.10.0', " +
                                            "because greater or equal version has already been installed.")
    public void testUpdateIMErrorIfInstalledIMHasGreaterVersion() throws Exception {
        when(transport.doOption(endsWith("api/"), anyString())).thenReturn("{\"ideVersion\":\"1.0.0\"}");
        doReturn(new HashMap<Artifact, SortedMap<Version, Path>>() {{
            put(installManagerArtifact, new TreeMap<Version, Path>() {{
                put(Version.valueOf("2.10.0"), Paths.get("target/download/installation-manager/2.10.0/file1"));
            }});
        }}).when(manager).getDownloadedArtifacts();
        doReturn("2.10.1").when(installManagerArtifact).getInstalledVersion(testCredentials.getToken());

        manager.install(testCredentials.getToken(), installManagerArtifact, "2.10.0", null);
    }

    @Test(expectedExceptions = IllegalStateException.class,
          expectedExceptionsMessageRegExp = "Can not install the artifact 'cdec' version '1.0.0', " +
                                            "because greater or equal version has already been installed.")
    public void testUpdateCdecErrorIfInstalledCdecHasGreaterVersion() throws Exception {
        when(transport.doOption(endsWith("api/"), anyString())).thenReturn("{\"ideVersion\":\"1.0.1\"}");
        doReturn(new HashMap<Artifact, SortedMap<Version, Path>>() {{
            put(cdecArtifact, new TreeMap<Version, Path>() {{
                put(Version.valueOf("1.0.0"), Paths.get("target/download/cdec/1.0.0/file1"));
            }});
        }}).when(manager).getDownloadedArtifacts();

        manager.install(testCredentials.getToken(), cdecArtifact, "1.0.0", null);
    }

    @Test
    public void testInstallWithInstallOptions() throws Exception {
        final Path testPathToBinaries = Paths.get("target/download/cdec/3.0.0/file1");
        doReturn(new HashMap<Artifact, SortedMap<Version, Path>>() {{
            put(cdecArtifact, new TreeMap<Version, Path>() {{
                put(Version.valueOf("3.0.1"), testPathToBinaries);
            }});
        }}).when(manager).getDownloadedArtifacts();

        InstallOptions testOptions = new InstallOptions()
            .setType(Installer.Type.CDEC_SINGLE_NODE_WITH_PUPPET_MASTER);

        doThrow(new InstallStartedException(testOptions)).when(cdecArtifact).install(testPathToBinaries, testOptions);

        when(transport.doOption(endsWith("api/"), anyString())).thenReturn("{\"ideVersion\":\"3.0.0\"}");

        try {
            manager.install(testCredentials.getToken(), cdecArtifact, "3.0.1", testOptions);
        } catch(InstallStartedException e) {
            assertEquals(e.getInstallOptions(), testOptions);
            return;
        }

        fail();
    }

    @Test
    public void testCheckEnoughDiskSpace() throws Exception {
        manager.checkEnoughDiskSpace(100);
    }

    @Test(expectedExceptions = IOException.class,
          expectedExceptionsMessageRegExp = "Not enough disk space. Required [0-9]* bytes but available only [0-9]* bytes")
    public void testCheckEnoughDiskSpaceThrowIOException() throws Exception {
        manager.checkEnoughDiskSpace(Long.MAX_VALUE);
    }

    @Test
    public void testInstallArtifactNewlyArtifact() throws Exception {
        doReturn(new HashMap<Artifact, SortedMap<Version, Path>>() {{
            put(installManagerArtifact, new TreeMap<Version, Path>() {{
                put(Version.valueOf("2.10.2"), Paths.get("target/download/installation-manager/2.10.2/file1"));
            }});
        }}).when(manager).getDownloadedArtifacts();

        doReturn(Collections.emptyMap()).when(manager).getInstalledArtifacts(testCredentials.getToken());
        doReturn("2.10.1").when(installManagerArtifact).getInstalledVersion(testCredentials.getToken());
        doNothing().when(installManagerArtifact).install(any(Path.class), any(InstallOptions.class));

        manager.install(testCredentials.getToken(), installManagerArtifact, "2.10.2", null);
    }

    @Test
    public void testDownloadVersion() throws Exception {
        String version = "1.0.0";
        when(transport.doGet("api/endpoint/account", testCredentials.getToken()))
                .thenReturn("[{"
                            + "\"roles\":[\"" + AccountUtils.ACCOUNT_OWNER_ROLE + "\"],"
                            + "\"accountReference\":{\"id\":\"" + testCredentials.getAccountId() + "\"}"
                            + "}]");

        when(transport.doGet("api/endpoint/account/" + testCredentials.getAccountId() + "/subscriptions", testCredentials.getToken()))
                .thenReturn("[{serviceId:\"OnPremises\"}]");

        when(transport.doGet(endsWith("repository/properties/" + cdecArtifact.getName() + "/" + version)))
                .thenReturn(String.format("{\"%s\": \"true\", \"%s\":\"OnPremises\"}", AUTHENTICATION_REQUIRED_PROPERTY, SUBSCRIPTION_PROPERTY));

        manager.download(testCredentials, cdecArtifact, version);

    }

    @Test
    public void testGetUpdates() throws Exception {
        doReturn(new HashMap<Artifact, String>() {{
            put(cdecArtifact, "2.10.5");
            put(installManagerArtifact, "1.0.0");
        }}).when(manager).getInstalledArtifacts(testCredentials.getToken());
        doReturn(new HashMap<Artifact, String>() {{
            put(cdecArtifact, "2.10.5");
            put(installManagerArtifact, "1.0.1");
        }}).when(manager).getLatestVersionsToDownload();

        Map<Artifact, String> m = manager.getUpdates(testCredentials.getToken());
        assertEquals(m.size(), 1);
        assertEquals(m.get(installManagerArtifact), "1.0.1");
    }

    @Test
    public void testGetInstalledArtifacts() throws Exception {
        when(transport.doGet("update/endpoint/repository/installationinfo/" + CDECArtifact.NAME, testCredentials.getToken()))
                .thenReturn("{version:2.10.4}");
        when(transport.doGet("update/endpoint/repository/installationinfo/" + CDECArtifact.NAME, testCredentials.getToken()))
                .thenReturn("{\"version\":\"2.10.4\"}");
        when(transport.doOption(endsWith("api/"), anyString())).thenReturn("{\"ideVersion\":\"1.0.0\"}");

        Map<Artifact, String> m = manager.getInstalledArtifacts(testCredentials.getToken());
        assertNotNull(m.get(cdecArtifact));
        assertNotNull(m.get(installManagerArtifact));
    }

    @Test
    public void testGetLatestVersionsToDownload() throws Exception {
        doNothing().when(manager).validateArtifactProperties(anyMap());
        when(transport.doGet(endsWith("repository/properties/" + InstallManagerArtifact.NAME))).thenReturn("{\"version\":\"1.0.1\"}");
        when(transport.doGet(endsWith("repository/properties/" + CDECArtifact.NAME))).thenReturn("{\"version\":\"2.10.5\"}");
        Map<Artifact, String> m = manager.getLatestVersionsToDownload();

        assertEquals(m.size(), 2);
        assertEquals(m.get(installManagerArtifact), "1.0.1");
        assertEquals(m.get(cdecArtifact), "2.10.5");
    }

    @Test
    public void testGetDownloadedArtifacts() throws Exception {
        doReturn("{\"file\":\"file1\", \"md5\":\"d41d8cd98f00b204e9800998ecf8427e\"}").when(transport).doGet(endsWith("cdec/1.0.1"));
        doReturn("{\"file\":\"file2\", \"md5\":\"d41d8cd98f00b204e9800998ecf8427e\"}").when(transport).doGet(endsWith("cdec/1.0.2"));
        doReturn("{\"file\":\"file3\", \"md5\":\"d41d8cd98f00b204e9800998ecf8427e\"}").when(transport).doGet(
            endsWith("installation-manager/2.0.1"));
        doNothing().when(manager).validateArtifactProperties(anyMap());

        Path file1 = Paths.get("target", "download", cdecArtifact.getName(), "1.0.1", "file1");
        Path file2 = Paths.get("target", "download", cdecArtifact.getName(), "1.0.2", "file2");
        Path file3 = Paths.get("target", "download", installManagerArtifact.getName(), "2.0.1", "file3");
        Files.createDirectories(file1.getParent());
        Files.createDirectories(file2.getParent());
        Files.createDirectories(file3.getParent());
        Files.createFile(file1);
        Files.createFile(file2);
        Files.createFile(file3);

        Map<Artifact, SortedMap<Version, Path>> artifacts = manager.getDownloadedArtifacts();
        assertEquals(artifacts.size(), 2);

        // check order
        assertEquals(artifacts.toString(), "{cdec={" +
                                           "1.0.1=target/download/cdec/1.0.1/file1, " +
                                           "1.0.2=target/download/cdec/1.0.2/file2" +
                                           "}, " +
                                           "installation-manager={" +
                                           "2.0.1=target/download/installation-manager/2.0.1/file3" +
                                           "}}");
    }

    @Test
    public void testGetDownloadedArtifactsSeveralVersions() throws Exception {
        doReturn("{\"file\":\"file1\", \"md5\":\"d41d8cd98f00b204e9800998ecf8427e\"}").when(transport).doGet(endsWith("cdec/1.0.1"));
        doReturn("{\"file\":\"file2\", \"md5\":\"d41d8cd98f00b204e9800998ecf8427e\"}").when(transport).doGet(endsWith("cdec/1.0.2"));
        doNothing().when(manager).validateArtifactProperties(anyMap());

        Path file1 = Paths.get("target", "download", cdecArtifact.getName(), "1.0.1", "file1");
        Path file2 = Paths.get("target", "download", cdecArtifact.getName(), "1.0.2", "file2");
        Files.createDirectories(file1.getParent());
        Files.createDirectories(file2.getParent());
        Files.createFile(file1);
        Files.createFile(file2);

        Map<Artifact, SortedMap<Version, Path>> m = manager.getDownloadedArtifacts();
        assertEquals(m.size(), 1);
        assertTrue(m.containsKey(cdecArtifact));

        SortedMap<Version, Path> v = m.get(cdecArtifact);
        assertTrue(v.containsKey(Version.valueOf("1.0.1")));
        assertTrue(v.containsKey(Version.valueOf("1.0.2")));
        assertEquals(v.get(Version.valueOf("1.0.1")), file1);
        assertEquals(v.get(Version.valueOf("1.0.2")), file2);
    }

    @Test
    public void testGetDownloadedArtifactsReturnsEmptyMap() throws Exception {
        Map<Artifact, SortedMap<Version, Path>> m = manager.getDownloadedArtifacts();
        assertTrue(m.isEmpty());
    }

    @Test
    public void testGetArtifactPropertiesWithVersion() throws Exception {
        doReturn("{\"file\":\"file1\", \"md5\":\"a\"}").when(transport).doGet(endsWith("cdec/1.0.1"));
        doNothing().when(manager).validateArtifactProperties(anyMap());

        Map m = manager.getArtifactProperties(cdecArtifact, "1.0.1");
        assertTrue(m.containsKey("file"));
        assertTrue(m.containsKey("md5"));
        assertEquals(m.get("file"), "file1");
        assertEquals(m.get("md5"), "a");
    }

    @Test
    public void testGetArtifactProperties() throws Exception {
        doReturn("{\"file\":\"file1\", \"md5\":\"a\"}").when(transport).doGet(endsWith("cdec"));
        doNothing().when(manager).validateArtifactProperties(anyMap());

        Map m = manager.getArtifactProperties(cdecArtifact);
        assertTrue(m.containsKey("file"));
        assertTrue(m.containsKey("md5"));
        assertEquals(m.get("file"), "file1");
        assertEquals(m.get("md5"), "a");
    }

    @Test
    public void testSetAndGetConfig() throws Exception {
        doNothing().when(manager).storeProperty(anyString(), anyString());
        doNothing().when(manager).validatePath(any(Path.class));

        InstallationManagerConfig config = new InstallationManagerConfig();
        config.setDownloadDir("target/new-download");
        config.setProxyPort("1000");
        config.setProxyUrl("localhost");
        manager.setConfig(config);

        Map<String, String> m = manager.getConfig();
        assertEquals(m.size(), 4);
        assertTrue(m.containsValue("target/new-download"));
        assertTrue(m.containsValue("1000"));
        assertTrue(m.containsValue("localhost"));
        assertTrue(m.containsValue("http://update.com"));

        config.setDownloadDir("target/download");
        config.setProxyPort("");
        manager.setConfig(config);

        m = manager.getConfig();
        assertEquals(m.size(), 3);
        assertTrue(m.containsValue("target/download"));
        assertTrue(m.containsValue("localhost"));
        assertTrue(m.containsValue("http://update.com"));

        config.setProxyUrl("");
        manager.setConfig(config);

        m = manager.getConfig();
        assertEquals(m.size(), 2);
        assertTrue(m.containsValue("target/download"));
        assertTrue(m.containsValue("http://update.com"));
    }

    @Test(expectedExceptions = IOException.class)
    public void testSetConfigErrorIfDirectoryCantCreateDirectory() throws Exception {
        doNothing().when(manager).storeProperty(anyString(), anyString());

        InstallationManagerConfig config = new InstallationManagerConfig();
        config.setDownloadDir("/hello/world");

        manager.setConfig(config);
    }

    @Test(expectedExceptions = IOException.class)
    public void testSetConfigErrorIfDirectoryIsNotAbsolute() throws Exception {
        doNothing().when(manager).storeProperty(anyString(), anyString());

        InstallationManagerConfig config = new InstallationManagerConfig();
        config.setDownloadDir("hello");

        manager.setConfig(config);
    }
}
