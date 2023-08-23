/*
 * Copyright (c) 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.maven.downloader;

import static org.graalvm.maven.downloader.Main.LOGGER;
import static org.graalvm.maven.downloader.OptionProperties.DEFAULT_MAVEN_REPO;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleFinder;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;

public class MVNDownloader {

    private final Set<String> downloadedJars = new HashSet<>();
    private final Set<String> existingModules;
    private final Path outputDir;
    private final Path downloadDir;

    private final class DeleteDownloadDir implements Runnable {
        @Override
        public void run() {
            try {
                Files.deleteIfExists(downloadDir);
            } catch (IOException e) {
                // Nothing we can do now
            }
        }
    }

    public MVNDownloader(String outputDir) throws IOException {
        this.outputDir = Paths.get(outputDir);
        this.existingModules = getModuleNamesInDirectory(this.outputDir);
        this.downloadDir = Files.createTempDirectory(MVNDownloader.class.getSimpleName());
        Runtime.getRuntime().addShutdownHook(new Thread(new DeleteDownloadDir()));
    }

    private static Set<String> getModuleNamesInDirectory(Path dir) {
        return ModuleFinder.of(dir).findAll().stream().map(mr -> mr.descriptor().name()).collect(Collectors.toUnmodifiableSet());
    }

    public void downloadDependencies(String repoUrl, String groupId, String artifactId, String version)
                    throws IOException, URISyntaxException, ParserConfigurationException, SAXException, ClassCastException, NoSuchAlgorithmException {
        String artifactName = toMavenPath(groupId, artifactId, version, "pom");
        boolean mvnCentralFallback = !repoUrl.startsWith(DEFAULT_MAVEN_REPO);
        byte[] bytes = downloadMavenFile(repoUrl, artifactName, mvnCentralFallback);

        var factory = DocumentBuilderFactory.newInstance();
        var builder = factory.newDocumentBuilder();
        var document = builder.parse(new ByteArrayInputStream(bytes));

        // We care only about a very small subset of the POM, and accept even malformed POMs if the
        // required tags are there. Since this is supposed to access an actual repository, real
        // Maven tools wouldn't work if the POMs are really malformed, so we do minimal error
        // checking.

        var projectNode = (Element) document.getElementsByTagName("project").item(0);
        if (projectNode == null) {
            LOGGER.severe(String.format("Malformed pom %s does not have <project> tag", artifactName));
            System.exit(1);
        }
        LOGGER.fine(String.format("loaded model for %s", artifactName));

        var packagingNode = projectNode.getElementsByTagName("packaging").item(0);
        var packaging = packagingNode == null ? "jar" : packagingNode.getTextContent();

        if (!packaging.equals("pom")) {
            artifactName = toMavenPath(groupId, artifactId, version, packaging);
            if (downloadedJars.contains(artifactName)) {
                LOGGER.finer(String.format("skipped already downloaded artifact %s", artifactName));
                return;
            }
            bytes = downloadMavenFile(repoUrl, artifactName, mvnCentralFallback);
            var tmpfile = store(downloadDir, groupId, artifactId, version, bytes, packaging);
            var definedModules = getModuleNamesInDirectory(downloadDir);
            if (definedModules.size() > 1) {
                LOGGER.severe(String.format("Internal error: more than one module in temporary directory %s", downloadDir));
                System.exit(1);
            } else if (definedModules.size() == 1 && existingModules.containsAll(definedModules)) {
                LOGGER.finer(String.format("skipped artifact %s, which defines module %s, because it matches an existing module in the output dir %s",
                                artifactName, definedModules.toArray()[0], outputDir));
                Files.delete(tmpfile);
                // if the module is already there, we assume its dependencies must be too.
                return;
            } else {
                Files.move(tmpfile, outputDir.resolve(tmpfile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
            downloadedJars.add(artifactName);
        }

        var dependenciesNodes = document.getDocumentElement().getElementsByTagName("dependencies");
        for (int i = 0; i < dependenciesNodes.getLength(); i++) {
            var dependenciesNode = (Element) dependenciesNodes.item(i);
            var dependencyNodes = dependenciesNode.getElementsByTagName("dependency");
            for (int j = 0; j < dependencyNodes.getLength(); j++) {
                var dependencyNode = (Element) dependencyNodes.item(j);
                var gidNode = dependencyNode.getElementsByTagName("groupId").item(0);
                if (gidNode == null) {
                    LOGGER.severe(String.format("Malformed pom %s, dependency does not have <groupId> tag", artifactName));
                    System.exit(1);
                }
                var gid = gidNode.getTextContent();
                var aidNode = dependencyNode.getElementsByTagName("artifactId").item(0);
                if (aidNode == null) {
                    LOGGER.severe(String.format("Malformed pom %s, dependency does not have <artifactId> tag", artifactName));
                    System.exit(1);
                }
                var aid = aidNode.getTextContent();
                var versionNode = dependencyNode.getElementsByTagName("version").item(0);
                if (versionNode == null) {
                    LOGGER.severe(String.format("missing version for dependency %s:%s in %s", gid, aid, artifactName));
                    System.exit(1);
                }
                var ver = versionNode.getTextContent();
                var scopeNode = dependencyNode.getElementsByTagName("scope").item(0);
                if (scopeNode != null) {
                    var scope = scopeNode.getTextContent();
                    if ("test".equals(scope) || "provided".equals(scope)) {
                        continue;
                    }
                }
                downloadDependencies(repoUrl, gid, aid, ver);
            }
        }
    }

    private static byte[] downloadMavenFile(String repoUrl, String artefactName, boolean fallback) throws IOException, URISyntaxException {
        String url = repoUrl + artefactName;
        try {
            if (url.startsWith("file:")) {
                File file = new File(new URI(url));
                if (!file.exists()) {
                    throw new IOException(String.format("does not exist %s", url));
                }
            }
            URL u = new URI(url).toURL();
            byte[] bytes = downloadFromServer(u);
            LOGGER.info(String.format("downloaded file %s", url));
            return bytes;
        } catch (IOException ioe) {
            if (fallback) {
                LOGGER.log(Level.WARNING, String.format("could not download maven file from %s, because of: %s. Falling back on %s", url, ioe, DEFAULT_MAVEN_REPO));
                return downloadMavenFile(DEFAULT_MAVEN_REPO, artefactName, false);
            }
            LOGGER.log(Level.SEVERE, String.format("exception while downloading maven file from %s", url), ioe);
            throw ioe;
        } catch (URISyntaxException ex) {
            LOGGER.log(Level.SEVERE, String.format("wrong url", url), ex);
            throw ex;
        }
    }

    private static byte[] downloadFromServer(URL url) throws IOException {
        URLConnection conn = url.openConnection(getProxy());
        int code = HttpURLConnection.HTTP_OK;
        if (conn instanceof HttpURLConnection) {
            code = ((HttpURLConnection) conn).getResponseCode();
        }
        if (code != HttpURLConnection.HTTP_OK) {
            throw new IOException("Skipping download from " + url + " due to response code " + code);
        }
        try {
            try (InputStream is = conn.getInputStream(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int read;
                while ((read = is.read(buf)) != -1) {
                    baos.write(buf, 0, read);
                }
                return baos.toByteArray();
            }
        } catch (IOException ex) {
            throw new IOException("Cannot download: " + url + " due to: " + ex, ex);
        }
    }

    private static Proxy getProxy() {
        String httpProxy = getProxyVar("https_proxy");
        if (httpProxy == null || "".equals(httpProxy.trim())) {
            httpProxy = getProxyVar("http_proxy");
        }
        if (httpProxy == null || "".equals(httpProxy.trim())) {
            LOGGER.info(String.format("using no proxy"));
            return Proxy.NO_PROXY;
        }
        httpProxy = httpProxy.trim();
        int idx = httpProxy.lastIndexOf(":");
        if (idx < 0 || idx > httpProxy.length() - 1) {
            LOGGER.warning(String.format("http_proxy env variable has to be in format host:url, but was '%s'", httpProxy));
            return Proxy.NO_PROXY;
        }
        String host = httpProxy.substring(0, idx);
        int port;
        try {
            port = Integer.parseInt(httpProxy.substring(idx + 1));
        } catch (NumberFormatException e) {
            LOGGER.severe(String.format("can't parse port number in '%s'", httpProxy));
            throw e;
        }
        LOGGER.info(String.format("using proxy '%s:%s'", host, port));

        InetSocketAddress address = InetSocketAddress.createUnresolved(host, port);
        return new Proxy(Proxy.Type.HTTP, address);
    }

    private static String getProxyVar(String key) {
        String var = System.getenv(key);
        if (var == null) {
            var = System.getenv(key.toUpperCase(Locale.ROOT));
        }
        return var;
    }

    private static String toMavenPath(String groupId, String artifactId, String version, String extension) {
        return String.format("%s/%s/%s/%s", groupId.replace(".", "/"), artifactId, version, toArtifactFilename(artifactId, version, extension));
    }

    private static String toArtifactFilename(String artifactId, String version, String extension) {
        return String.format("%s-%s.%s", artifactId, version, extension);
    }

    private static Path store(Path dir, String groupId, String artifactId, String version, byte[] bytes, String extension) throws IOException {
        String fileName = String.format("%s-%s", groupId, toArtifactFilename(artifactId, version, extension));
        Path path = dir.resolve(fileName);
        Files.write(path, bytes);
        return path;
    }
}
