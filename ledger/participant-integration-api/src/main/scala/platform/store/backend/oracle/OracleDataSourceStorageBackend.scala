// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.backend.oracle

import java.sql.Connection

import anorm.SqlParser.get
import anorm.SqlStringInterpolation
import com.daml.logging.LoggingContext
import com.daml.platform.store.backend.DataSourceStorageBackend
import com.daml.platform.store.backend.common.InitHookDataSourceProxy
import javax.sql.DataSource

object OracleDataSourceStorageBackend extends DataSourceStorageBackend {
  override def createDataSource(
      jdbcUrl: String,
      dataSourceConfig: DataSourceStorageBackend.DataSourceConfig,
      connectionInitHook: Option[Connection => Unit],
  )(implicit loggingContext: LoggingContext): DataSource = {
    val oracleDataSource = new oracle.jdbc.pool.OracleDataSource
    oracleDataSource.setURL(jdbcUrl)
    InitHookDataSourceProxy(oracleDataSource, connectionInitHook.toList)
  }

  override def checkDatabaseAvailable(connection: Connection): Unit =
    assert(SQL"SELECT 1 FROM DUAL".as(get[Int](1).single)(connection) == 1)
}
