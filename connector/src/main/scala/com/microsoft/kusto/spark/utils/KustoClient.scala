package com.microsoft.kusto.spark.utils

import java.util
import java.util.StringJoiner
import java.util.concurrent.TimeUnit

import com.microsoft.azure.kusto.data.{Client, ClientFactory, ConnectionStringBuilder, KustoResultSetTable}
import com.microsoft.azure.kusto.ingest.result.{IngestionStatus, OperationStatus}
import com.microsoft.azure.kusto.ingest.{IngestClient, IngestClientFactory, IngestionProperties}
import com.microsoft.kusto.spark.common.KustoCoordinates
import com.microsoft.kusto.spark.datasink.KustoWriter.delayPeriodBetweenCalls
import com.microsoft.kusto.spark.datasink.SinkTableCreationMode.SinkTableCreationMode
import com.microsoft.kusto.spark.datasink.{PartitionResult, SinkTableCreationMode}
import com.microsoft.kusto.spark.datasource.KustoStorageParameters
import com.microsoft.kusto.spark.utils.CslCommandsGenerator._
import com.microsoft.kusto.spark.utils.KustoDataSourceUtils.extractSchemaFromResultTable
import com.microsoft.kusto.spark.utils.{KustoDataSourceUtils => KDSU}
import org.apache.commons.lang3.StringUtils
import org.apache.spark.sql.types.StructType
import org.apache.spark.util.CollectionAccumulator
import shaded.parquet.org.codehaus.jackson.map.ObjectMapper

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}

class KustoClient(val clusterAlias: String, val engineKcsb: ConnectionStringBuilder, val ingestKcsb: ConnectionStringBuilder) {
  val engineClient: Client = ClientFactory.createClient(engineKcsb)

  // Reading process does not require ingest client to start working
  lazy val dmClient: Client = ClientFactory.createClient(ingestKcsb)
  lazy val ingestClient: IngestClient = IngestClientFactory.createClient(ingestKcsb)

  private val exportProviderEntryCreator = (c: ContainerAndSas) => KDSU.parseSas(c.containerUrl + c.sas)
  private val ingestProviderEntryCreator = (c: ContainerAndSas) => c
  private lazy val  ingestContainersContainerProvider = new ContainerProvider(dmClient, clusterAlias, generateCreateTmpStorageCommand(), ingestProviderEntryCreator)
  private lazy val  exportContainersContainerProvider = new ContainerProvider(dmClient, clusterAlias, generateGetExportContainersCommand(), exportProviderEntryCreator)

  private val myName = this.getClass.getSimpleName

  def createTmpTableWithSameSchema(tableCoordinates: KustoCoordinates,
                                   tmpTableName: String,
                                   tableCreation: SinkTableCreationMode = SinkTableCreationMode.FailIfNotExist,
                                   schema: StructType): Unit = {

    val schemaShowCommandResult = engineClient.execute(tableCoordinates.database, generateTableGetSchemaAsRowsCommand(tableCoordinates.table.get)).getPrimaryResults
    var tmpTableSchema: String = ""
    val database = tableCoordinates.database
    val table = tableCoordinates.table.get

    if (schemaShowCommandResult.count() == 0) {
      // Table Does not exist
      if (tableCreation == SinkTableCreationMode.FailIfNotExist) {
        throw new RuntimeException("Table '" + table + "' doesn't exist in database '" + database + "', in cluster '" + tableCoordinates.cluster + "'")
      } else {
        // Parse dataframe schema and create a destination table with that schema
        val tableSchemaBuilder = new StringJoiner(",")
        schema.fields.foreach { field =>
          val fieldType = DataTypeMapping.getSparkTypeToKustoTypeMap(field.dataType)
          tableSchemaBuilder.add(s"['${field.name}']:$fieldType")
        }
        tmpTableSchema = tableSchemaBuilder.toString
        engineClient.execute(database, generateTableCreateCommand(table, tmpTableSchema))
      }
    } else {
      // Table exists. Parse kusto table schema and check if it matches the dataframes schema
      tmpTableSchema = extractSchemaFromResultTable(schemaShowCommandResult.getData.asInstanceOf[util.ArrayList[util.ArrayList[String]]])
    }

    // Create a temporary table with the kusto or dataframe parsed schema with 1 day retention
    engineClient.execute(database, generateTempTableCreateCommand(tmpTableName, tmpTableSchema))
    engineClient.execute(database, generateTableAlterRetentionPolicy(tmpTableName, "001:00:00:00", recoverable = false))
  }

  def getTempBlobForIngestion: ContainerAndSas = {
    ingestContainersContainerProvider.getContainer
  }

  def getTempBlobsForExport: Seq[KustoStorageParameters] = {
    exportContainersContainerProvider.getAllContainers
  }

  private[kusto] def finalizeIngestionWhenWorkersSucceeded(coordinates: KustoCoordinates,
                                                           batchIdIfExists: String,
                                                           kustoAdminClient: Client,
                                                           tmpTableName: String,
                                                           partitionsResults: CollectionAccumulator[PartitionResult],
                                                           timeout: FiniteDuration,
                                                           isAsync: Boolean = false): Unit = {
    import coordinates._

    val mergeTask = Future {
      KDSU.logInfo(myName, s"Polling on ingestion results, will move data to destination table when finished")

      try {
        partitionsResults.value.asScala.foreach {
          partitionResult => {
            var finalRes: IngestionStatus = null
            KDSU.doWhile[IngestionStatus](
              () => {
                finalRes = partitionResult.ingestionResult.getIngestionStatusCollection.get(0); finalRes
              },
              0,
              delayPeriodBetweenCalls,
              (timeout.toMillis / delayPeriodBetweenCalls + 5).toInt,
              res => res.status == OperationStatus.Pending,
              res => finalRes = res).await(timeout.toMillis, TimeUnit.MILLISECONDS)

            finalRes.status match {
              case OperationStatus.Pending =>
                throw new RuntimeException(s"Ingestion to Kusto failed on timeout failure. Cluster: '${coordinates.cluster}', " +
                  s"database: '${coordinates.database}', table: '$tmpTableName', batch: '$batchIdIfExists', partition: '${partitionResult.partitionId}'")
              case OperationStatus.Succeeded =>
                KDSU.logInfo(myName, s"Ingestion to Kusto succeeded. " +
                  s"Cluster: '${coordinates.cluster}', " +
                  s"database: '${coordinates.database}', " +
                  s"table: '$tmpTableName', batch: '$batchIdIfExists', partition: '${partitionResult.partitionId}'', from: '${finalRes.ingestionSourcePath}', Operation ${finalRes.operationId}")
              case otherStatus =>
                throw new RuntimeException(s"Ingestion to Kusto failed with status '$otherStatus'." +
                  s" Cluster: '${coordinates.cluster}', database: '${coordinates.database}', " +
                  s"table: '$tmpTableName', batch: '$batchIdIfExists', partition: '${partitionResult.partitionId}''. Ingestion info: '${readIngestionResult(finalRes)}'")
            }
          }
        }

        if (partitionsResults.value.size > 0) {
          // Move data to real table
          // Protect tmp table from merge/rebuild and move data to the table requested by customer. This operation is atomic.
          kustoAdminClient.execute(database, generateTableAlterMergePolicyCommand(tmpTableName, allowMerge = false, allowRebuild = false))
          kustoAdminClient.execute(database, generateTableMoveExtentsCommand(tmpTableName, table.get))
          KDSU.logInfo(myName, s"write to Kusto table '${table.get}' finished successfully $batchIdIfExists")
        } else {
          KDSU.logWarn(myName, s"write to Kusto table '${table.get}' finished with no data written")
        }
      } catch {
        case ex: Exception =>
          KDSU.reportExceptionAndThrow(
            myName,
            ex,
            "Trying to poll on pending ingestions", coordinates.cluster, coordinates.database, coordinates.table.getOrElse("Unspecified table name")
          )
      } finally {
        cleanupIngestionByproducts(database, kustoAdminClient, tmpTableName)
      }
    }

    if (!isAsync) {
      Await.result(mergeTask, timeout)
    }
  }

  private[kusto] def cleanupIngestionByproducts(database: String, kustoAdminClient: Client, tmpTableName: String): Unit = {
    try {
      kustoAdminClient.execute(database, generateTableDropCommand(tmpTableName))
    }
    catch {
      case exception: Exception =>
        KDSU.reportExceptionAndThrow(myName, exception, s"deleting temporary table $tmpTableName", database = database, shouldNotThrow = true)
    }
  }

  private[kusto] def setMappingOnStagingTableIfNeeded(stagingTableIngestionProperties: IngestionProperties, originalTable: String): Unit = {
    val mapping = stagingTableIngestionProperties.getIngestionMapping
    val mappingReferenceName = mapping.getIngestionMappingReference
    if (StringUtils.isNotBlank(mappingReferenceName)) {
      val mappingKind = mapping.getIngestionMappingKind.toString
      val cmd = generateShowTableMappingsCommand(originalTable, mappingKind)
      val mappings = engineClient.execute(stagingTableIngestionProperties.getDatabaseName, cmd).getPrimaryResults

      var found = false
      while (mappings.next && !found){
        if(mappings.getString(0).equals(mappingReferenceName)){
          val policyJson = mappings.getString(2).replace("\"","'")
          val c = generateCreateTableMappingCommand(stagingTableIngestionProperties.getTableName, mappingKind, mappingReferenceName, policyJson)
          engineClient.execute(stagingTableIngestionProperties.getDatabaseName, c)
          found = true
        }
      }
    }
  }

  private def readIngestionResult(statusRecord: IngestionStatus): String = {
    new ObjectMapper()
      .writerWithDefaultPrettyPrinter
      .writeValueAsString(statusRecord)
  }
}

case class ContainerAndSas(containerUrl: String, sas: String)
