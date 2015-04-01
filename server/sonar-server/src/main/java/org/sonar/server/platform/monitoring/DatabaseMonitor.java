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

package org.sonar.server.platform.monitoring;

import org.apache.commons.dbcp.BasicDataSource;
import org.apache.commons.dbutils.DbUtils;
import org.sonar.core.persistence.DatabaseVersion;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.MyBatis;
import org.sonar.server.db.DbClient;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Information about database and connection pool
 */
public class DatabaseMonitor extends BaseMonitorMBean implements DatabaseMonitorMBean {

  public interface DatabaseAttributes {
    public static final String PRODUCT = "Database";
    public static final String VERSION = "Database Version";
    public static final String USERNAME = "Username";
    public static final String URL = "URL";
    public static final String DRIVER = "Driver";
    public static final String DRIVER_VERSION = "Driver Version";
    public static final String MIGRATION_STATUS = "Version Status";
  }

  public interface PoolAttributes {
    public static final String ACTIVE_CONNECTIONS = "Pool Active Connections";
    public static final String MAX_CONNECTIONS = "Pool Max Connections";
    public static final String INITIAL_SIZE = "Pool Initial Size";
    public static final String IDLE_CONNECTIONS = "Pool Idle Connections";
    public static final String MIN_IDLE_CONNECTIONS = "Pool Min Idle Connections";
    public static final String MAX_IDLE_CONNECTIONS = "Pool Max Idle Connections";
    public static final String MAX_WAIT_MS = "Pool Max Wait (ms)";
    public static final String REMOVE_ABANDONED = "Pool Remove Abandoned";
    public static final String REMOVE_ABANDONED_TIMEOUT_SECONDS = "Pool Remove Abandoned Timeout (seconds)";
  }

  private final DatabaseVersion dbVersion;
  private final DbClient dbClient;

  public DatabaseMonitor(DatabaseVersion dbVersion, DbClient dbClient) {
    this.dbVersion = dbVersion;
    this.dbClient = dbClient;
  }

  @Override
  public String name() {
    return "Database";
  }

  @Override
  public String getMigrationStatus() {
    return dbVersion.getStatus().name();
  }

  @Override
  public int getPoolActiveConnections() {
    return commonsDbcp().getNumActive();
  }

  @Override
  public int getPoolMaxActiveConnections() {
    return commonsDbcp().getMaxActive();
  }

  @Override
  public int getPoolIdleConnections() {
    return commonsDbcp().getNumIdle();
  }

  @Override
  public int getPoolMaxIdleConnections() {
    return commonsDbcp().getMaxIdle();
  }

  @Override
  public int getPoolMinIdleConnections() {
    return commonsDbcp().getMinIdle();
  }

  @Override
  public int getPoolInitialSize() {
    return commonsDbcp().getInitialSize();
  }

  @Override
  public long getPoolMaxWaitMillis() {
    return commonsDbcp().getMaxWait();
  }

  @Override
  public boolean getPoolRemoveAbandoned() {
    return commonsDbcp().getRemoveAbandoned();
  }

  @Override
  public int getPoolRemoveAbandonedTimeoutSeconds() {
    return commonsDbcp().getRemoveAbandonedTimeout();
  }

  @Override
  public LinkedHashMap<String, Object> attributes() {
    LinkedHashMap<String, Object> attributes = new LinkedHashMap<>();
    completeDbAttributes(attributes);
    completePoolAttributes(attributes);
    return attributes;
  }

  private void completePoolAttributes(Map<String, Object> attributes) {
    attributes.put(PoolAttributes.ACTIVE_CONNECTIONS, getPoolActiveConnections());
    attributes.put(PoolAttributes.MAX_CONNECTIONS, getPoolMaxActiveConnections());
    attributes.put(PoolAttributes.INITIAL_SIZE, getPoolInitialSize());
    attributes.put(PoolAttributes.IDLE_CONNECTIONS, getPoolIdleConnections());
    attributes.put(PoolAttributes.MIN_IDLE_CONNECTIONS, getPoolMinIdleConnections());
    attributes.put(PoolAttributes.MAX_IDLE_CONNECTIONS, getPoolMaxIdleConnections());
    attributes.put(PoolAttributes.MAX_WAIT_MS, getPoolMaxWaitMillis());
    attributes.put(PoolAttributes.REMOVE_ABANDONED, getPoolRemoveAbandoned());
    attributes.put(PoolAttributes.REMOVE_ABANDONED_TIMEOUT_SECONDS, getPoolRemoveAbandonedTimeoutSeconds());
  }

  private BasicDataSource commonsDbcp() {
    return (BasicDataSource) dbClient.database().getDataSource();
  }

  private void completeDbAttributes(Map<String, Object> attributes) {
    DbSession dbSession = dbClient.openSession(false);
    Connection connection = dbSession.getConnection();
    try {
      DatabaseMetaData metadata = connection.getMetaData();
      attributes.put(DatabaseAttributes.PRODUCT, metadata.getDatabaseProductName());
      attributes.put(DatabaseAttributes.VERSION, metadata.getDatabaseProductVersion());
      attributes.put(DatabaseAttributes.USERNAME, metadata.getUserName());
      attributes.put(DatabaseAttributes.URL, metadata.getURL());
      attributes.put(DatabaseAttributes.DRIVER, metadata.getDriverName());
      attributes.put(DatabaseAttributes.DRIVER_VERSION, metadata.getDriverVersion());
      attributes.put(DatabaseAttributes.MIGRATION_STATUS, getMigrationStatus());
    } catch (SQLException e) {
      throw new IllegalStateException("Fail to get DB metadata", e);

    } finally {
      DbUtils.closeQuietly(connection);
      MyBatis.closeQuietly(dbSession);
    }
  }
}
