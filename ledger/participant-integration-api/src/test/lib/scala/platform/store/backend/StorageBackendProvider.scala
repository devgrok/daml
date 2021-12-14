// Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.store.backend

import java.sql.Connection

import com.daml.ledger.offset.Offset
import com.daml.platform.store.backend.ParameterStorageBackend.LedgerEnd
import com.daml.platform.store.backend.h2.H2StorageBackendFactory
import com.daml.platform.store.backend.oracle.OracleStorageBackendFactory
import com.daml.platform.store.backend.postgresql.PostgresStorageBackendFactory
import com.daml.platform.store.cache.MutableLedgerEndCache
import com.daml.platform.store.interning.{MockStringInterning, StringInterning}
import com.daml.testing.oracle.OracleAroundAll
import com.daml.testing.postgresql.PostgresAroundAll
import org.scalatest.Suite

/** Creates a database and a [[TestBackend]].
  * Used by [[StorageBackendSpec]] to run all StorageBackend tests on different databases.
  */
private[backend] trait StorageBackendProvider {
  protected def jdbcUrl: String
  protected def backend: TestBackend

  protected final def ingest(dbDtos: Vector[DbDto], connection: Connection): Unit = {
    def typeBoundIngest[T](ingestionStorageBackend: IngestionStorageBackend[T]): Unit =
      ingestionStorageBackend.insertBatch(
        connection,
        ingestionStorageBackend.batch(dbDtos, backend.stringInterningSupport),
      )
    typeBoundIngest(backend.ingestion)
  }

  protected final def updateLedgerEnd(
      ledgerEndOffset: Offset,
      ledgerEndSequentialId: Long,
  )(connection: Connection): Unit = {
    backend.parameter.updateLedgerEnd(LedgerEnd(ledgerEndOffset, ledgerEndSequentialId, 0))(
      connection
    ) // we do not care about the stringInterningId here
    updateLedgerEndCache(connection)
  }

  protected final def updateLedgerEnd(ledgerEnd: LedgerEnd)(connection: Connection): Unit = {
    backend.parameter.updateLedgerEnd(ledgerEnd)(connection)
    updateLedgerEndCache(connection)
  }

  protected final def updateLedgerEndCache(connection: Connection): Unit = {
    val ledgerEnd = backend.parameter.ledgerEndOrBeforeBegin(connection)
    backend.ledgerEndCache.set(ledgerEnd.lastOffset -> ledgerEnd.lastEventSeqId)
  }
}

private[backend] trait StorageBackendProviderPostgres
    extends StorageBackendProvider
    with PostgresAroundAll { this: Suite =>
  override protected def jdbcUrl: String = postgresDatabase.url
  override protected val backend: TestBackend = TestBackend(PostgresStorageBackendFactory)
}

private[backend] trait StorageBackendProviderH2 extends StorageBackendProvider { this: Suite =>
  override protected def jdbcUrl: String = "jdbc:h2:mem:storage_backend_provider;db_close_delay=-1"
  override protected val backend: TestBackend = TestBackend(H2StorageBackendFactory)
}

private[backend] trait StorageBackendProviderOracle
    extends StorageBackendProvider
    with OracleAroundAll { this: Suite =>
  override protected def jdbcUrl: String =
    s"jdbc:oracle:thin:$oracleUser/$oraclePwd@localhost:$oraclePort/ORCLPDB1"
  override protected val backend: TestBackend = TestBackend(OracleStorageBackendFactory)
}

case class TestBackend(
    ingestion: IngestionStorageBackend[_],
    parameter: ParameterStorageBackend,
    configuration: ConfigurationStorageBackend,
    party: PartyStorageBackend,
    packageBackend: PackageStorageBackend,
    deduplication: DeduplicationStorageBackend,
    completion: CompletionStorageBackend,
    contract: ContractStorageBackend,
    event: EventStorageBackend,
    dataSource: DataSourceStorageBackend,
    dbLock: DBLockStorageBackend,
    integrity: IntegrityStorageBackend,
    reset: ResetStorageBackend,
    stringInterning: StringInterningStorageBackend,
    ledgerEndCache: MutableLedgerEndCache,
    stringInterningSupport: StringInterning,
    userManagement: UserManagementStorageBackend,
)

object TestBackend {
  def apply(storageBackendFactory: StorageBackendFactory): TestBackend = {
    val ledgerEndCache = MutableLedgerEndCache()
    val stringInterning = new MockStringInterning
    TestBackend(
      ingestion = storageBackendFactory.createIngestionStorageBackend,
      parameter = storageBackendFactory.createParameterStorageBackend,
      configuration = storageBackendFactory.createConfigurationStorageBackend(ledgerEndCache),
      party = storageBackendFactory.createPartyStorageBackend(ledgerEndCache),
      packageBackend = storageBackendFactory.createPackageStorageBackend(ledgerEndCache),
      deduplication = storageBackendFactory.createDeduplicationStorageBackend,
      completion = storageBackendFactory.createCompletionStorageBackend(stringInterning),
      contract =
        storageBackendFactory.createContractStorageBackend(ledgerEndCache, stringInterning),
      event = storageBackendFactory.createEventStorageBackend(ledgerEndCache, stringInterning),
      dataSource = storageBackendFactory.createDataSourceStorageBackend,
      dbLock = storageBackendFactory.createDBLockStorageBackend,
      integrity = storageBackendFactory.createIntegrityStorageBackend,
      reset = storageBackendFactory.createResetStorageBackend,
      stringInterning = storageBackendFactory.createStringInterningStorageBackend,
      ledgerEndCache = ledgerEndCache,
      stringInterningSupport = stringInterning,
      userManagement = storageBackendFactory.createUserManagementStorageBackend,
    )
  }
}
