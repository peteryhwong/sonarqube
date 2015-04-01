/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.platform.ws;

import org.sonar.api.platform.Server;
import org.sonar.api.platform.ServerUpgradeStatus;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.server.platform.monitoring.DatabaseMonitor;

import com.google.common.io.Resources;

/**
 * SystemStatusWsAction
 *
 * @author Sébastien Lesaint (sebastien.lesaint@sonarsource.com)
 */
public class SystemStatusWsAction implements SystemWsAction {

  private final Server server;
  private final ServerUpgradeStatus serverUpgradeStatus;
  private final DatabaseMonitor databaseMonitor;

  public SystemStatusWsAction(Server server, ServerUpgradeStatus serverUpgradeStatus, DatabaseMonitor databaseMonitor) {
    this.server = server;
    this.serverUpgradeStatus = serverUpgradeStatus;
    this.databaseMonitor = databaseMonitor;
  }

  @Override
  public void define(WebService.NewController controller) {
    controller.createAction("index")
      .setDescription("Get the server status:" +
        "<ul>" +
        "<li>UP</li>" +
        "<li>DOWN (generally for database connection failures)</li>" +
        "<li>SETUP (if the server must be upgraded)</li>" +
        "<li>MIGRATION_RUNNING (the upgrade process is currently running)</li>" +
        "</ul>")
      .setSince("5.2")
      .setResponseExample(Resources.getResource(this.getClass(), "example-status.json"))
      .setHandler(this);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    JsonWriter json = response.newJsonWriter();
    writeJson(json);
    json.close();
  }

  private void writeJson(JsonWriter json) {
    Status status = computeStatus();

    json.beginObject();
    json.prop("id", server.getId());
    json.prop("version", server.getVersion());
    json.prop("status", status.toString());
    json.endObject();
  }

  private Status computeStatus() {
    if (!isConnectedToDB())  {
      return Status.DOWN;
    }
    MigrationStatus migrationStatus = computeMigrationStatus();
    if (migrationStatus == MigrationStatus.RUNNING) {
      return Status.MIGRATION_RUNNING;
    }
    if (migrationStatus == MigrationStatus.NEEDED) {
      return Status.SETUP;
    }
    if (migrationStatus == MigrationStatus.NOT_SUPPORTED) {
      return Status.DOWN;
    }
    return Status.UP;
  }

  private boolean isConnectedToDB() {
    // TODO check DB connection is up
    return true;
  }

  private MigrationStatus computeMigrationStatus() {
    if (serverUpgradeStatus.isUpgraded()) {
      return MigrationStatus.UP_TO_DATE;
    }
    if (isProductionDB()) {
      return MigrationStatus.NEEDED;
    }
    return MigrationStatus.NOT_SUPPORTED;
  }

  private boolean isProductionDB() {
    Object dbProduct = databaseMonitor.attributes().get(DatabaseMonitor.DatabaseAttributes.PRODUCT);
    return dbProduct instanceof String && !"H2".equalsIgnoreCase((String) dbProduct);
  }

  private enum Status {
    UP, DOWN, SETUP, MIGRATION_RUNNING
  }

  private enum MigrationStatus {
    NEEDED, RUNNING, FAILED, SUCCESS, UP_TO_DATE, NOT_SUPPORTED
  }
}
