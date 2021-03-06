/*
 * Sonar Runner
 * Copyright (C) 2011 SonarSource
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.runner;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Bootstrapper used to download everything from the server and create the correct classloader required to execute a Sonar analysis in isolation.
 */
class Bootstrapper {

  static final String VERSION_PATH = "/api/server/version";
  static final String BATCH_PATH = "/batch/";
  static final int CONNECT_TIMEOUT_MILLISECONDS = 30000;
  static final int READ_TIMEOUT_MILLISECONDS = 60000;

  private File bootDir;
  private String serverUrl;
  private String productToken;
  private String serverVersion;

  /**
   * @param productToken part of User-Agent request-header field - see http://tools.ietf.org/html/rfc1945#section-10.15
   */
  Bootstrapper(String productToken, String serverUrl, File workDir) {
    this.productToken = productToken;
    bootDir = new File(workDir, "batch");
    bootDir.mkdirs();
    if (serverUrl.endsWith("/")) {
      this.serverUrl = serverUrl.substring(0, serverUrl.length() - 1);
    } else {
      this.serverUrl = serverUrl;
    }
  }

  /**
   * @return server url
   */
  String getServerUrl() {
    return serverUrl;
  }

  /**
   * @return server version
   */
  String getServerVersion() {
    if (serverVersion == null) {
      try {
        serverVersion = remoteContent(VERSION_PATH);
      } catch (IOException e) {
        throw new IllegalStateException("Fail to request server version", e);
      }
    }
    return serverVersion;
  }

  /**
   * Download batch files from server and creates {@link BootstrapClassLoader}.
   * To use this method version of Sonar should be at least 2.6.
   *
   * @param urls             additional URLs for loading classes and resources
   * @param parent           parent ClassLoader
   * @param unmaskedPackages only classes and resources from those packages would be available for loading from parent
   */
  BootstrapClassLoader createClassLoader(URL[] urls, ClassLoader parent, String... unmaskedPackages) {
    BootstrapClassLoader classLoader = new BootstrapClassLoader(parent, unmaskedPackages);
    List<File> files = downloadBatchFiles();
    for (URL url : urls) {
      classLoader.addURL(url);
    }
    for (File file : files) {
      try {
        classLoader.addURL(file.toURI().toURL());
      } catch (MalformedURLException e) {
        throw new IllegalStateException("Fail to create classloader", e);
      }
    }
    return classLoader;
  }

  private void remoteContentToFile(String path, File toFile) {
    InputStream input = null;
    FileOutputStream output = null;
    String fullUrl = serverUrl + path;
    try {
      HttpURLConnection connection = newHttpConnection(new URL(fullUrl));
      output = new FileOutputStream(toFile, false);
      input = connection.getInputStream();
      IOUtils.copyLarge(input, output);
    } catch (IOException e) {
      IOUtils.closeQuietly(output);
      IOUtils.deleteFileQuietly(toFile);
      throw new IllegalStateException("Fail to download the file: " + fullUrl, e);
    } finally {
      IOUtils.closeQuietly(input);
      IOUtils.closeQuietly(output);
    }
  }

  String remoteContent(String path) throws IOException {
    String fullUrl = serverUrl + path;
    HttpURLConnection conn = newHttpConnection(new URL(fullUrl));
    Reader reader = new InputStreamReader((InputStream) conn.getContent());
    try {
      int statusCode = conn.getResponseCode();
      if (statusCode != HttpURLConnection.HTTP_OK) {
        throw new IOException("Status returned by url : '" + fullUrl + "' is invalid : " + statusCode);
      }
      return IOUtils.toString(reader);
    } finally {
      IOUtils.closeQuietly(reader);
      conn.disconnect();
    }
  }

  /**
   * By convention, the product tokens are listed in order of their significance for identifying the application.
   */
  String getUserAgent() {
    return "sonar-bootstrapper/" + Version.getVersion() + " " + productToken;
  }

  HttpURLConnection newHttpConnection(URL url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
    connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
    connection.setInstanceFollowRedirects(true);
    connection.setRequestMethod("GET");
    connection.setRequestProperty("User-Agent", getUserAgent());
    return connection;
  }

  private List<File> downloadBatchFiles() {
    try {
      List<File> files = new ArrayList<File>();
      String libs = remoteContent(BATCH_PATH);
      for (String lib : libs.split(",")) {
        File file = new File(bootDir, lib);
        remoteContentToFile(BATCH_PATH + lib, file);
        files.add(file);
      }
      return files;
    } catch (Exception e) {
      throw new IllegalStateException("Fail to download libraries from server", e);
    }
  }
}
