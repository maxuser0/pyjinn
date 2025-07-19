// SPDX-FileCopyrightText: © 2025 Greg Christiana <maxuser@pyjinn.org>
// SPDX-License-Identifier: MIT

package org.pyjinn.interpreter;

import java.io.InputStream;
import java.util.Properties;

public record VersionInfo(
    String pyjinnVersion, String buildTimestamp, String javaVendor, String javaVersion) {

  static VersionInfo load() throws Exception {
    Properties prop = new Properties();
    try (InputStream input =
        VersionInfo.class.getClassLoader().getResourceAsStream("build.properties")) {
      if (input == null) {
        return new VersionInfo("?", "?", "?", "?");
      }
      prop.load(input);
      return new VersionInfo(
          prop.getProperty("pyjinn.version"),
          prop.getProperty("build.timestamp"),
          prop.getProperty("java.vendor"),
          prop.getProperty("java.version"));
    }
  }

  public String toString() {
    return "Pyjinn %s (%s) [%s Java %s]"
        .formatted(pyjinnVersion, buildTimestamp, javaVendor, javaVersion);
  }
}
