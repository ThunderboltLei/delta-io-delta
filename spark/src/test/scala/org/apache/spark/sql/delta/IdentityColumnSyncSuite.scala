/*
 * Copyright (2021) The Delta Lake Project Authors.
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

package org.apache.spark.sql.delta

import org.apache.spark.sql.delta.GeneratedAsIdentityType.GeneratedByDefault
import org.apache.spark.sql.delta.sources.{DeltaSourceUtils, DeltaSQLConf}

import org.apache.spark.sql.{AnalysisException, Row}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.types._

case class IdentityColumnTestTableRow(id: Long, value: String)

/**
 * Identity Column test suite for the SYNC IDENTITY command.
 */
trait IdentityColumnSyncSuiteBase
  extends IdentityColumnTestUtils {

  import testImplicits._

  /**
   * Create and manage a table with a single identity column "id" generated by default and a single
   * String "value" column.
   */
  private def withSimpleGeneratedByDefaultTable(
      tblName: String, startsWith: Long, incrementBy: Long)(f: => Unit): Unit = {
    withTable(tblName) {
      createTable(
        tblName,
        Seq(
          IdentityColumnSpec(
            GeneratedByDefault,
            startsWith = Some(startsWith),
            incrementBy = Some(incrementBy)),
          TestColumnSpec(colName = "value", dataType = StringType)
        )
      )

      f
    }
  }

  test("alter table sync identity on delta table") {
    val starts = Seq(-1, 1)
    val steps = Seq(-3, 3)
    val alterKeywords = Seq("ALTER", "CHANGE")
    for (start <- starts; step <- steps; alterKeyword <- alterKeywords) {
      val tblName = getRandomTableName
      withSimpleGeneratedByDefaultTable(tblName, start, step) {
        // Test empty table.
        val oldSchema = DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot.schema
        sql(s"ALTER TABLE $tblName $alterKeyword COLUMN id SYNC IDENTITY")
        assert(DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot.schema === oldSchema)

        // Test a series of values that are not all following start and step configurations.
        for (i <- start to (start + step * 10)) {
          sql(s"INSERT INTO $tblName VALUES($i, 'v')")
          sql(s"ALTER TABLE $tblName $alterKeyword COLUMN id SYNC IDENTITY")
          val expected = start + (((i - start) + (step - 1)) / step) * step
          val schema = DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot.schema
          assert(schema("id").metadata.getLong(DeltaSourceUtils.IDENTITY_INFO_HIGHWATERMARK) ===
            expected)
        }
      }
    }
  }

  test("sync identity with values before start") {
    val tblName = getRandomTableName
    withSimpleGeneratedByDefaultTable(tblName, startsWith = 100L, incrementBy = 2L) {
      val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tblName))
      assert(getHighWaterMark(deltaLog.update(), "id").isEmpty,
        "an empty table does not have an identity high watermark")

      sql(s"INSERT INTO $tblName (id, value) VALUES (1, 'a'), (2, 'b'), (99, 'c')")
      assert(getHighWaterMark(deltaLog.update(), "id").isEmpty,
        "user inserted values do not update the high watermark")

      sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      assert(getHighWaterMark(deltaLog.update(), "id").isEmpty,
        "sync identity must not add a high watermark that is lower " +
        "than the start value when it has positive increment")

      sql(s"INSERT INTO $tblName (value) VALUES ('d'), ('e'), ('f')")

      val result = spark.read.table(tblName)
        .as[IdentityColumnTestTableRow]
        .collect()
        .sortBy(_.id)
      assert(result.length === 6)
      assert(result.take(3) === Seq(IdentityColumnTestTableRow(1, "a"),
                                    IdentityColumnTestTableRow(2, "b"),
                                    IdentityColumnTestTableRow(99, "c")))
      checkGeneratedIdentityValues(
        sortedRows = result.takeRight(3),
        start = 100,
        step = 2,
        expectedLowerBound = 100,
        expectedUpperBound =
          highWaterMark(DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot, "id"),
        expectedDistinctCount = 3)
    }
  }

  test("sync identity with start in table") {
    val tblName = getRandomTableName
    withSimpleGeneratedByDefaultTable(tblName, startsWith = 100L, incrementBy = 2L) {
      sql(s"INSERT INTO $tblName (id, value) VALUES (1, 'a'), (2, 'b'), (100, 'c')")
      sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      sql(s"INSERT INTO $tblName (value) VALUES ('d'), ('e'), ('f')")

      val result = spark.read.table(tblName)
        .as[IdentityColumnTestTableRow]
        .collect()
        .sortBy(_.id)
      assert(result.length === 6)
      assert(result.take(3) === Seq(IdentityColumnTestTableRow(1, "a"),
                                    IdentityColumnTestTableRow(2, "b"),
                                    IdentityColumnTestTableRow(100, "c")))
      checkGeneratedIdentityValues(
        sortedRows = result.takeRight(3),
        start = 100,
        step = 2,
        expectedLowerBound = 101,
        expectedUpperBound =
          highWaterMark(DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot, "id"),
        expectedDistinctCount = 3)
    }
  }

  test("sync identity with values before and after start") {
    val tblName = getRandomTableName
    withSimpleGeneratedByDefaultTable(tblName, startsWith = 100L, incrementBy = 2L) {
      sql(s"INSERT INTO $tblName (id, value) VALUES (1, 'a'), (2, 'b'), (101, 'c')")
      sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      sql(s"INSERT INTO $tblName (value) VALUES ('d'), ('e'), ('f')")

      val result = spark.read.table(tblName)
        .as[IdentityColumnTestTableRow]
        .collect()
        .sortBy(_.id)
      assert(result.length === 6)
      assert(result.take(3) === Seq(IdentityColumnTestTableRow(1, "a"),
                                    IdentityColumnTestTableRow(2, "b"),
                                    IdentityColumnTestTableRow(101, "c")))
      checkGeneratedIdentityValues(
        sortedRows = result.takeRight(3),
        start = 100,
        step = 2,
        expectedLowerBound = 102,
        expectedUpperBound =
          highWaterMark(DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot, "id"),
        expectedDistinctCount = 3)
    }
  }

  test("sync identity with values before start and negative step") {
    val tblName = getRandomTableName
    withSimpleGeneratedByDefaultTable(tblName, startsWith = -10L, incrementBy = -2L) {
      val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tblName))
      assert(getHighWaterMark(deltaLog.update(), "id").isEmpty,
        "an empty table does not have an identity high watermark")

      sql(s"INSERT INTO $tblName (id, value) VALUES (1, 'a'), (2, 'b'), (-9, 'c')")
      assert(getHighWaterMark(deltaLog.update(), "id").isEmpty,
        "user inserted values do not update the high watermark")

      sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      assert(getHighWaterMark(deltaLog.update(), "id").isEmpty,
        "sync identity must not add a high watermark that is higher " +
        "than the start value when it has negative increment")

      sql(s"INSERT INTO $tblName (value) VALUES ('d'), ('e'), ('f')")

      val result = spark.read.table(tblName)
        .as[IdentityColumnTestTableRow]
        .collect()
        .sortBy(_.id)
      assert(result.length === 6)
      assert(result.takeRight(3) === Seq(IdentityColumnTestTableRow(-9, "c"),
                                         IdentityColumnTestTableRow(1, "a"),
                                         IdentityColumnTestTableRow(2, "b")))
      checkGeneratedIdentityValues(
        sortedRows = result.take(3),
        start = -10,
        step = -2,
        expectedLowerBound =
          highWaterMark(DeltaLog.forTable(spark, TableIdentifier(tblName)).snapshot, "id"),
        expectedUpperBound = -10,
        expectedDistinctCount = 3)
    }
  }

  test("alter table sync identity - deleting high watermark rows followed by sync identity" +
    " brings down the highWatermark only with a flag") {
    for (generatedAsIdentityType <- GeneratedAsIdentityType.values) {
      val tblName = getRandomTableName
      withTable(tblName) {
        createTableWithIdColAndIntValueCol(tblName, generatedAsIdentityType, Some(1L), Some(10L))
        val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tblName))
        (0 to 4).foreach { v =>
          sql(s"INSERT INTO $tblName(value) VALUES ($v)")
        }

        checkAnswer(sql(s"SELECT max(id) FROM $tblName"), Row(41))
        sql(s"DELETE FROM $tblName WHERE value IN (0, 3, 4)")
        assert(highWaterMark(deltaLog.snapshot, "id") === 41L)
        // Unless this flag is enabled, the high watermark is not updated if it is lower
        // than the previous high watermark.
        withSQLConf(
            DeltaSQLConf.DELTA_IDENTITY_ALLOW_SYNC_IDENTITY_TO_LOWER_HIGH_WATER_MARK.key -> "false"
        ) {
          sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
          assert(highWaterMark(deltaLog.update(), "id") === 41L)
        }
        // With the flag enabled, the high watermark is updated even if it is lower,
        // than the previous high watermark, as long as it is higher than the defined start.
        withSQLConf(
            DeltaSQLConf.DELTA_IDENTITY_ALLOW_SYNC_IDENTITY_TO_LOWER_HIGH_WATER_MARK.key -> "true"
        ) {
          sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
          assert(highWaterMark(deltaLog.update(), "id") === 21L)
        }
        sql(s"INSERT INTO $tblName(value) VALUES (8)")
        checkAnswer(sql(s"SELECT max(id) FROM $tblName"), Row(31))
        checkAnswer(sql(s"SELECT COUNT(DISTINCT id) == COUNT(*) FROM $tblName"), Row(true))
      }
    }
  }

  test("alter table sync identity overflow error") {
    val tblName = getRandomTableName
    withSimpleGeneratedByDefaultTable(tblName, startsWith = 1L, incrementBy = 10L) {
      sql(s"INSERT INTO $tblName VALUES (${Long.MaxValue}, 'a')")
      assertThrows[ArithmeticException] {
        sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      }
    }
  }

  test("alter table sync identity on non delta table error") {
    val tblName = getRandomTableName
    withTable(tblName) {
      sql(
        s"""
           |CREATE TABLE $tblName (
           |  id BIGINT,
           |  value INT
           |) USING parquet;
           |""".stripMargin)
      val ex = intercept[AnalysisException] {
        sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      }
      assert(ex.getMessage.contains(
        "ALTER TABLE ALTER COLUMN SYNC IDENTITY is only supported by Delta."))
    }
  }

  test("alter table sync identity on non identity column error") {
    val tblName = getRandomTableName
    withTable(tblName) {
      createTable(
        tblName,
        Seq(
          TestColumnSpec(colName = "id", dataType = LongType),
          TestColumnSpec(colName = "value", dataType = IntegerType)
        )
      )
      val ex = intercept[AnalysisException] {
        sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      }
      assert(ex.getMessage.contains(
        "ALTER TABLE ALTER COLUMN SYNC IDENTITY cannot be called on non IDENTITY columns."))
    }
  }

  for (positiveStep <- DeltaTestUtils.BOOLEAN_DOMAIN)
  test(s"SYNC IDENTITY on table with bad water mark. positiveStep = $positiveStep") {
    val tblName = getRandomTableName
    withTable(tblName) {
      val incrementBy = if (positiveStep)  48 else -48
      createTableWithIdColAndIntValueCol(
        tblName,
        GeneratedByDefault,
        startsWith = Some(100),
        incrementBy = Some(incrementBy)
      )
      val deltaLog = DeltaLog.forTable(spark, TableIdentifier(tblName))

      // Insert data that don't respect the start.
      if (positiveStep) {
        sql(s"INSERT INTO $tblName(id, value) VALUES (4, 4)")
      } else {
        sql(s"INSERT INTO $tblName(id, value) VALUES (196, 196)")
      }
      forceBadWaterMark(deltaLog)
      val badWaterMark = highWaterMark(deltaLog.snapshot, "id")

      // Even though the candidate high water mark and the existing high water mark is invalid,
      // we don't want to prevent updates to the high water mark as this would lead to us
      // generating the same values over and over.
      sql(s"ALTER TABLE $tblName ALTER COLUMN id SYNC IDENTITY")
      val newHighWaterMark = highWaterMark(deltaLog.update(), colName = "id")
      assert(newHighWaterMark !== badWaterMark,
        "Sync identity should update the high water mark based on the data.")
      if (positiveStep) {
        assert(newHighWaterMark > badWaterMark)
      } else {
        assert(newHighWaterMark < badWaterMark)
      }
    }
  }

  for {
    allowExplicitInsert <- DeltaTestUtils.BOOLEAN_DOMAIN
    allowLoweringHighWatermarkForSyncIdentity <- DeltaTestUtils.BOOLEAN_DOMAIN
  } test(s"IdentityColumn.updateToValidHighWaterMark - allowExplicitInsert = $allowExplicitInsert,"
    + s" allowLoweringHighWatermarkForSyncIdentity = $allowLoweringHighWatermarkForSyncIdentity") {
    /**
     * Unit test for the updateToValidHighWaterMark function by creating a StructField with the
     * specified start, step, and existing high water mark. After calling the function, we verify
     * the StructField's metadata has the expect high water mark.
     */
    def testUpdateToValidHighWaterMark(
        start: Long,
        step: Long,
        allowExplicitInsert: Boolean,
        allowLoweringHighWatermarkForSyncIdentity: Boolean,
        existingHighWaterMark: Option[Long],
        candidateHighWaterMark: Long,
        expectedHighWaterMark: Option[Long]): Unit = {
      /** Creates a MetadataBuilder for Struct Metadata. */
      def getMetadataBuilder(highWaterMarkOpt: Option[Long]): MetadataBuilder = {
        var metadataBuilder = new MetadataBuilder()
          .putLong(DeltaSourceUtils.IDENTITY_INFO_START, start)
          .putLong(DeltaSourceUtils.IDENTITY_INFO_STEP, step)
          .putBoolean(DeltaSourceUtils.IDENTITY_INFO_ALLOW_EXPLICIT_INSERT, allowExplicitInsert)

        highWaterMarkOpt match {
          case Some(oldHighWaterMark) =>
            metadataBuilder = metadataBuilder.putLong(
              DeltaSourceUtils.IDENTITY_INFO_HIGHWATERMARK, oldHighWaterMark)
          case None => ()
        }
        metadataBuilder
      }

      val initialStructField = StructField(
        name = "id",
        LongType,
        nullable = false,
        metadata = getMetadataBuilder(existingHighWaterMark).build())
      val (updatedStructField, _) =
        IdentityColumn.updateToValidHighWaterMark(
          initialStructField, candidateHighWaterMark, allowLoweringHighWatermarkForSyncIdentity)
      val expectedMetadata = getMetadataBuilder(expectedHighWaterMark).build()
      assert(updatedStructField.metadata === expectedMetadata)
    }

    // existingHighWaterMark = None, positive step
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = 3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = None,
      candidateHighWaterMark = 2L,
      expectedHighWaterMark = Some(4L) // rounded up
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = 3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = None,
      candidateHighWaterMark = 0L,
      expectedHighWaterMark = None // below start
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = 3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = None,
      candidateHighWaterMark = 1L,
      expectedHighWaterMark = Some(1L) // equal to start
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = 3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = None,
      candidateHighWaterMark = 7L,
      expectedHighWaterMark = Some(7L) // respects start and step
    )

    // existingHighWaterMark = None, negative step
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = -3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = None,
      candidateHighWaterMark = -1L,
      expectedHighWaterMark = Some(-2L) // rounded up
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = -3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = None,
      candidateHighWaterMark = 2L,
      expectedHighWaterMark = None // above start
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = -3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = None,
      candidateHighWaterMark = 1L,
      expectedHighWaterMark = Some(1L) // equal to start
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = -3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = None,
      candidateHighWaterMark = -5L,
      expectedHighWaterMark = Some(-5L) // respects start and step
    )

    // existingHighWaterMark = Some, positive step
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = 3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(4L),
      candidateHighWaterMark = 5L,
      expectedHighWaterMark = Some(7L) // rounded up
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = 3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(4L),
      candidateHighWaterMark = 0L,
      expectedHighWaterMark = Some(4L) // below start, preserve existing high watermark
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = 3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(-5L),
      candidateHighWaterMark = -2L,
      expectedHighWaterMark = Some(-2L) // below start, bad existing water mark, update to candidate
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = 3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(-5L),
      candidateHighWaterMark = 0L,
      expectedHighWaterMark = Some(1L) // below start, bad existing water mark, update rounded up
    )

    testUpdateToValidHighWaterMark(
      start = 1L,
      step = 3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(-5L),
      candidateHighWaterMark = -9L,
      expectedHighWaterMark = if (allowLoweringHighWatermarkForSyncIdentity) {
        // below start, bad existing water mark, allow lowering, rounded down
        Some(-8L)
      } else {
        // below start, bad existing water mark, keep existing
        Some(-5L)
      }
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = 3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(4L),
      candidateHighWaterMark = 1L,
      expectedHighWaterMark = if (allowLoweringHighWatermarkForSyncIdentity) {
        Some(1L) // allow lowering
      } else {
        Some(4L) // below existing high watermark
      }
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = 3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(4L),
      candidateHighWaterMark = 7L,
      expectedHighWaterMark = Some(7L) // respects start and step
    )

    // existingHighWaterMark = Some, negative step
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = -3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(-2L),
      candidateHighWaterMark = -3L,
      expectedHighWaterMark = Some(-5L) // rounded up
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = -3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(-2L),
      candidateHighWaterMark = 2L,
      expectedHighWaterMark = Some(-2L) // above start, preserve existing high water mark
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = -3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(7L),
      candidateHighWaterMark = 4L,
      expectedHighWaterMark = Some(4L) // above start, bad existing water mark, update to candidate
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = -3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(7L),
      candidateHighWaterMark = 6L,
      expectedHighWaterMark = Some(4L) // above start, bad existing water mark, update rounded down
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = -3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(7L),
      candidateHighWaterMark = 11L,
      expectedHighWaterMark = if (allowLoweringHighWatermarkForSyncIdentity) {
        // above start, bad existing water mark, allow lowering, rounded down
        Some(10L)
      } else {
        // above start, bad existing water mark, keep existing
        Some(7L)
      }
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = -3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(-2L),
      candidateHighWaterMark = 1L,
      expectedHighWaterMark = if (allowLoweringHighWatermarkForSyncIdentity) {
        Some(1L) // allow lowering
      } else {
        Some(-2L) // higher than high watermark
      }
    )
    testUpdateToValidHighWaterMark(
      start = 1L,
      step = -3L,
      allowExplicitInsert = allowExplicitInsert,
      allowLoweringHighWatermarkForSyncIdentity = allowLoweringHighWatermarkForSyncIdentity,
      existingHighWaterMark = Some(-2L),
      candidateHighWaterMark = -5L,
      expectedHighWaterMark = Some(-5L) // respects start and step
    )
  }

  test("IdentityColumn.roundToNext") {
    val posStart = 7L
    val negStart = -7L
    val posLargeStart = Long.MaxValue - 10000
    val negLargeStart = Long.MinValue + 10000
    for (start <- Seq(posStart, negStart, posLargeStart, negLargeStart)) {
      assert(IdentityColumn.roundToNext(start = start, step = 3L, value = start) === start)
      assert(IdentityColumn.roundToNext(
        start = start, step = 3L, value = start + 5L) === start + 6L)
      assert(IdentityColumn.roundToNext(
        start = start, step = 3L, value = start + 6L) === start + 6L)
      assert(IdentityColumn.roundToNext(
        start = start, step = 3L, value = start - 5L) === start - 3L) // bad watermark
      assert(IdentityColumn.roundToNext(
        start = start, step = 3L, value = start - 7L) === start - 6L) // bad watermark
      assert(IdentityColumn.roundToNext(
        start = start, step = 3L, value = start - 6L) === start - 6L) // bad watermark
      assert(IdentityColumn.roundToNext(start = start, step = -3L, value = start) === start)
      assert(IdentityColumn.roundToNext(
        start = start, step = -3L, value = start - 5L) === start - 6L)
      assert(IdentityColumn.roundToNext(
        start = start, step = -3L, value = start - 6L) === start - 6L)
      assert(IdentityColumn.roundToNext(
        start = start, step = -3L, value = start + 5L) === start + 3L) // bad watermark
      assert(IdentityColumn.roundToNext(
        start = start, step = -3L, value = start + 7L) === start + 6L) // bad watermark
      assert(IdentityColumn.roundToNext(
        start = start, step = -3L, value = start + 6L) === start + 6L) // bad watermark
    }
  }
}

class IdentityColumnSyncScalaSuite
  extends IdentityColumnSyncSuiteBase
  with ScalaDDLTestUtils

class IdentityColumnSyncScalaIdColumnMappingSuite
  extends IdentityColumnSyncSuiteBase
  with ScalaDDLTestUtils
  with DeltaColumnMappingEnableIdMode

class IdentityColumnSyncScalaNameColumnMappingSuite
  extends IdentityColumnSyncSuiteBase
  with ScalaDDLTestUtils
  with DeltaColumnMappingEnableNameMode
