/**
  * Licensed to the Apache Software Foundation (ASF) under one or more
  * contributor license agreements.  See the NOTICE file distributed with
  * this work for additional information regarding copyright ownership.
  * The ASF licenses this file to You under the Apache License, Version 2.0
  * (the "License"); you may not use this file except in compliance with
  * the License.  You may obtain a copy of the License at
  *
  * http://www.apache.org/licenses/LICENSE-2.0
  *
  * Unless required by applicable law or agreed to in writing, software
  * distributed under the License is distributed on an "AS IS" BASIS,
  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  * See the License for the specific language governing permissions and
  * limitations under the License.
  */
package kafka.server

import java.io.{File, IOException}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import com.yammer.metrics.core.Gauge
import kafka.api._
import kafka.cluster.{BrokerEndPoint, Partition, Replica}
import kafka.common._
import kafka.controller.KafkaController
import kafka.log.{LogAppendInfo, LogManager}
import kafka.message.{ByteBufferMessageSet, MessageSet}
import kafka.metrics.KafkaMetricsGroup
import kafka.utils._
import kafka.validator.MessageValidator
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.utils.{Time => JTime}

import scala.collection._

/*
 * Result metadata of a log append operation on the log
 */
case class LogAppendResult(info: LogAppendInfo, error: Option[Throwable] = None) {
  def errorCode = error match {
    case None => ErrorMapping.NoError
    case Some(e) => ErrorMapping.codeFor(e.getClass.asInstanceOf[Class[Throwable]])
  }
}

/*
 * Result metadata of a log read operation on the log
 * @param info @FetchDataInfo returned by the @Log read
 * @param hw high watermark of the local replica
 * @param readSize amount of data that was read from the log i.e. size of the fetch
 * @param isReadFromLogEnd true if the request read up to the log end offset snapshot
 *                         when the read was initiated, false otherwise
 * @param error Exception if error encountered while reading from the log
 */
case class LogReadResult(info: FetchDataInfo,
                         hw: Long,
                         readSize: Int,
                         isReadFromLogEnd: Boolean,
                         error: Option[Throwable] = None) {

  def errorCode = error match {
    case None => ErrorMapping.NoError
    case Some(e) => ErrorMapping.codeFor(e.getClass.asInstanceOf[Class[Throwable]])
  }

  override def toString = {
    "Fetch Data: [%s], HW: [%d], readSize: [%d], isReadFromLogEnd: [%b], error: [%s]"
      .format(info, hw, readSize, isReadFromLogEnd, error)
  }
}

object LogReadResult {
  val UnknownLogReadResult = LogReadResult(FetchDataInfo(LogOffsetMetadata.UnknownOffsetMetadata,
    MessageSet.Empty),
    -1L,
    -1,
    false)
}

case class BecomeLeaderOrFollowerResult(responseMap: collection.Map[(String, Int), Short], errorCode: Short) {

  override def toString = {
    "update results: [%s], global error: [%d]".format(responseMap, errorCode)
  }
}

object ReplicaManager {
  val HighWatermarkFilename = "replication-offset-checkpoint"
  val IsrChangePropagationBlackOut = 5000L
  val IsrChangePropagationInterval = 60000L
}

class ReplicaManager(val config: KafkaConfig,
                     metrics: Metrics,
                     time: Time,
                     jTime: JTime,
                     val zkUtils: ZkUtils,
                     scheduler: Scheduler,
                     val logManager: LogManager,
                     val isShuttingDown: AtomicBoolean,
                     threadNamePrefix: Option[String] = None) extends Logging with KafkaMetricsGroup {
  /* epoch of the controller that last changed the leader */
  @volatile var controllerEpoch: Int = KafkaController.InitialControllerEpoch - 1
  private val localBrokerId = config.brokerId
  private val allPartitions = new Pool[(String, Int), Partition]
  private val replicaStateChangeLock = new Object
  val replicaFetcherManager = new ReplicaFetcherManager(config, this, metrics, jTime, threadNamePrefix)
  private val highWatermarkCheckPointThreadStarted = new AtomicBoolean(false)
  val highWatermarkCheckpoints = config.logDirs.map(dir => (new File(dir).getAbsolutePath, new OffsetCheckpoint(new File(dir, ReplicaManager.HighWatermarkFilename)))).toMap
  private var hwThreadInitialized = false
  this.logIdent = "[Replica Manager on Broker " + localBrokerId + "]: "
  val stateChangeLogger = KafkaController.stateChangeLogger
  private val isrChangeSet: mutable.Set[TopicAndPartition] = new mutable.HashSet[TopicAndPartition]()
  private val lastIsrChangeMs = new AtomicLong(System.currentTimeMillis())
  private val lastIsrPropagationMs = new AtomicLong(System.currentTimeMillis())
  /* get a validator instance by reflection */
  private val messageValidator = Class.forName(config.validatorClass).newInstance().asInstanceOf[MessageValidator]

  val delayedProducePurgatory = new DelayedOperationPurgatory[DelayedProduce](
    purgatoryName = "Produce", config.brokerId, config.producerPurgatoryPurgeIntervalRequests)
  val delayedFetchPurgatory = new DelayedOperationPurgatory[DelayedFetch](
    purgatoryName = "Fetch", config.brokerId, config.fetchPurgatoryPurgeIntervalRequests)

  val leaderCount = newGauge(
    "LeaderCount",
    new Gauge[Int] {
      def value = {
        getLeaderPartitions().size
      }
    }
  )
  val partitionCount = newGauge(
    "PartitionCount",
    new Gauge[Int] {
      def value = allPartitions.size
    }
  )
  val underReplicatedPartitions = newGauge(
    "UnderReplicatedPartitions",
    new Gauge[Int] {
      def value = underReplicatedPartitionCount()
    }
  )
  val isrExpandRate = newMeter("IsrExpandsPerSec", "expands", TimeUnit.SECONDS)
  val isrShrinkRate = newMeter("IsrShrinksPerSec", "shrinks", TimeUnit.SECONDS)

  def underReplicatedPartitionCount(): Int = {
    getLeaderPartitions().count(_.isUnderReplicated)
  }

  def startHighWaterMarksCheckPointThread() = {
    if (highWatermarkCheckPointThreadStarted.compareAndSet(false, true))
      scheduler.schedule("highwatermark-checkpoint", checkpointHighWatermarks, period = config.replicaHighWatermarkCheckpointIntervalMs, unit = TimeUnit.MILLISECONDS)
  }

  def recordIsrChange(topicAndPartition: TopicAndPartition) {
    isrChangeSet synchronized {
      isrChangeSet += topicAndPartition
      lastIsrChangeMs.set(System.currentTimeMillis())
    }
  }

  /**
    * This function periodically runs to see if ISR needs to be propagated. It propagates ISR when:
    * 1. There is ISR change not propagated yet.
    * 2. There is no ISR Change in the last five seconds, or it has been more than 60 seconds since the last ISR propagation.
    * This allows an occasional ISR change to be propagated within a few seconds, and avoids overwhelming controller and
    * other brokers when large amount of ISR change occurs.
    */
  def maybePropagateIsrChanges() {
    val now = System.currentTimeMillis()
    isrChangeSet synchronized {
      if (isrChangeSet.nonEmpty &&
        (lastIsrChangeMs.get() + ReplicaManager.IsrChangePropagationBlackOut < now ||
          lastIsrPropagationMs.get() + ReplicaManager.IsrChangePropagationInterval < now)) {
        ReplicationUtils.propagateIsrChanges(zkUtils, isrChangeSet)
        isrChangeSet.clear()
        lastIsrPropagationMs.set(now)
      }
    }
  }

  /**
    * Try to complete some delayed produce requests with the request key;
    * this can be triggered when:
    *
    * 1. The partition HW has changed (for acks = -1)
    * 2. A follower replica's fetch operation is received (for acks > 1)
    */
  def tryCompleteDelayedProduce(key: DelayedOperationKey) {
    val completed = delayedProducePurgatory.checkAndComplete(key)
    debug("Request key %s unblocked %d producer requests.".format(key.keyLabel, completed))
  }

  /**
    * Try to complete some delayed fetch requests with the request key;
    * this can be triggered when:
    *
    * 1. The partition HW has changed (for regular fetch)
    * 2. A new message set is appended to the local log (for follower fetch)
    */
  def tryCompleteDelayedFetch(key: DelayedOperationKey) {
    val completed = delayedFetchPurgatory.checkAndComplete(key)
    debug("Request key %s unblocked %d fetch requests.".format(key.keyLabel, completed))
  }

  def startup() {
    // start ISR expiration thread
    scheduler.schedule("isr-expiration", maybeShrinkIsr, period = config.replicaLagTimeMaxMs, unit = TimeUnit.MILLISECONDS)
    scheduler.schedule("isr-change-propagation", maybePropagateIsrChanges, period = 2500L, unit = TimeUnit.MILLISECONDS)
  }

  def stopReplica(topic: String, partitionId: Int, deletePartition: Boolean): Short = {
    stateChangeLogger.trace("Broker %d handling stop replica (delete=%s) for partition [%s,%d]".format(localBrokerId,
      deletePartition.toString, topic, partitionId))
    val errorCode = ErrorMapping.NoError
    getPartition(topic, partitionId) match {
      case Some(partition) =>
        if (deletePartition) {
          val removedPartition = allPartitions.remove((topic, partitionId))
          if (removedPartition != null)
            removedPartition.delete() // this will delete the local log
        }
      case None =>
        // Delete log and corresponding folders in case replica manager doesn't hold them anymore.
        // This could happen when topic is being deleted while broker is down and recovers.
        if (deletePartition) {
          val topicAndPartition = TopicAndPartition(topic, partitionId)

          if (logManager.getLog(topicAndPartition).isDefined) {
            logManager.deleteLog(topicAndPartition)
          }
        }
        stateChangeLogger.trace("Broker %d ignoring stop replica (delete=%s) for partition [%s,%d] as replica doesn't exist on broker"
          .format(localBrokerId, deletePartition, topic, partitionId))
    }
    stateChangeLogger.trace("Broker %d finished handling stop replica (delete=%s) for partition [%s,%d]"
      .format(localBrokerId, deletePartition, topic, partitionId))
    errorCode
  }

  def stopReplicas(stopReplicaRequest: StopReplicaRequest): (mutable.Map[TopicAndPartition, Short], Short) = {
    replicaStateChangeLock synchronized {
      val responseMap = new collection.mutable.HashMap[TopicAndPartition, Short]
      if (stopReplicaRequest.controllerEpoch < controllerEpoch) {
        stateChangeLogger.warn("Broker %d received stop replica request from an old controller epoch %d."
          .format(localBrokerId, stopReplicaRequest.controllerEpoch) +
          " Latest known controller epoch is %d " + controllerEpoch)
        (responseMap, ErrorMapping.StaleControllerEpochCode)
      } else {
        controllerEpoch = stopReplicaRequest.controllerEpoch
        // First stop fetchers for all partitions, then stop the corresponding replicas
        replicaFetcherManager.removeFetcherForPartitions(stopReplicaRequest.partitions.map(r => TopicAndPartition(r.topic, r.partition)))
        for (topicAndPartition <- stopReplicaRequest.partitions) {
          val errorCode = stopReplica(topicAndPartition.topic, topicAndPartition.partition, stopReplicaRequest.deletePartitions)
          responseMap.put(topicAndPartition, errorCode)
        }
        (responseMap, ErrorMapping.NoError)
      }
    }
  }

  def getOrCreatePartition(topic: String, partitionId: Int): Partition = {
    var partition = allPartitions.get((topic, partitionId))
    if (partition == null) {
      allPartitions.putIfNotExists((topic, partitionId), new Partition(topic, partitionId, time, this))
      partition = allPartitions.get((topic, partitionId))
    }
    partition
  }

  def getPartition(topic: String, partitionId: Int): Option[Partition] = {
    val partition = allPartitions.get((topic, partitionId))
    if (partition == null)
      None
    else
      Some(partition)
  }

  def getReplicaOrException(topic: String, partition: Int): Replica = {
    val replicaOpt = getReplica(topic, partition)
    if (replicaOpt.isDefined)
      replicaOpt.get
    else
      throw new ReplicaNotAvailableException("Replica %d is not available for partition [%s,%d]".format(config.brokerId, topic, partition))
  }

  def getLeaderReplicaIfLocal(topic: String, partitionId: Int): Replica = {
    val partitionOpt = getPartition(topic, partitionId)
    partitionOpt match {
      case None =>
        throw new UnknownTopicOrPartitionException("Partition [%s,%d] doesn't exist on %d".format(topic, partitionId, config.brokerId))
      case Some(partition) =>
        partition.leaderReplicaIfLocal match {
          case Some(leaderReplica) => leaderReplica
          case None =>
            throw new NotLeaderForPartitionException("Leader not local for partition [%s,%d] on broker %d"
              .format(topic, partitionId, config.brokerId))
        }
    }
  }

  def getReplica(topic: String, partitionId: Int, replicaId: Int = config.brokerId): Option[Replica] = {
    val partitionOpt = getPartition(topic, partitionId)
    partitionOpt match {
      case None => None
      case Some(partition) => partition.getReplica(replicaId)
    }
  }

  /**
    * Append messages to leader replicas of the partition, and wait for them to be replicated to other replicas;
    * the callback function will be triggered either when timeout or the required acks are satisfied
    */
  def appendMessages(timeout: Long,
                     requiredAcks: Short,
                     internalTopicsAllowed: Boolean,
                     messagesPerPartition: Map[TopicAndPartition, MessageSet],
                     responseCallback: Map[TopicAndPartition, ProducerResponseStatus] => Unit) {

    if (isValidRequiredAcks(requiredAcks)) {
      val sTime = SystemTime.milliseconds

      val localProduceResults = appendToLocalLog(internalTopicsAllowed, messagesPerPartition, requiredAcks)
      debug("Produce to local log in %d ms".format(SystemTime.milliseconds - sTime))

      val produceStatus = localProduceResults.map { case (topicAndPartition, result) =>
        topicAndPartition ->
          ProducePartitionStatus(
            result.info.lastOffset + 1, // required offset
            ProducerResponseStatus(result.errorCode, result.info.firstOffset)) // response status
      }

      if (delayedRequestRequired(requiredAcks, messagesPerPartition, localProduceResults)) {
        // create delayed produce operation
        val produceMetadata = ProduceMetadata(requiredAcks, produceStatus)
        val delayedProduce = new DelayedProduce(timeout, produceMetadata, this, responseCallback)

        // create a list of (topic, partition) pairs to use as keys for this delayed produce operation
        val producerRequestKeys = messagesPerPartition.keys.map(new TopicPartitionOperationKey(_)).toSeq

        // try to complete the request immediately, otherwise put it into the purgatory
        // this is because while the delayed produce operation is being created, new
        // requests may arrive and hence make this operation completable.
        delayedProducePurgatory.tryCompleteElseWatch(delayedProduce, producerRequestKeys)

      } else {
        // we can respond immediately
        val produceResponseStatus = produceStatus.mapValues(status => status.responseStatus)
        responseCallback(produceResponseStatus)
      }
    } else {
      // If required.acks is outside accepted range, something is wrong with the client
      // Just return an error and don't handle the request at all
      val responseStatus = messagesPerPartition.map {
        case (topicAndPartition, messageSet) =>
          (topicAndPartition ->
            ProducerResponseStatus(Errors.INVALID_REQUIRED_ACKS.code,
              LogAppendInfo.UnknownLogAppendInfo.firstOffset))
      }
      responseCallback(responseStatus)
    }
  }

  // If all the following conditions are true, we need to put a delayed produce request and wait for replication to complete
  //
  // 1. required acks = -1
  // 2. there is data to append
  // 3. at least one partition append was successful (fewer errors than partitions)
  private def delayedRequestRequired(requiredAcks: Short, messagesPerPartition: Map[TopicAndPartition, MessageSet],
                                     localProduceResults: Map[TopicAndPartition, LogAppendResult]): Boolean = {
    requiredAcks == -1 &&
      messagesPerPartition.size > 0 &&
      localProduceResults.values.count(_.error.isDefined) < messagesPerPartition.size
  }

  private def isValidRequiredAcks(requiredAcks: Short): Boolean = {
    requiredAcks == -1 || requiredAcks == 1 || requiredAcks == 0
  }

  /**
    * Append the messages to the local replica logs
    */
  private def appendToLocalLog(internalTopicsAllowed: Boolean,
                               messagesPerPartition: Map[TopicAndPartition, MessageSet],
                               requiredAcks: Short): Map[TopicAndPartition, LogAppendResult] = {
    trace("Append [%s] to local log ".format(messagesPerPartition))
    messagesPerPartition.map { case (topicAndPartition, messages) =>
      BrokerTopicStats.getBrokerTopicStats(topicAndPartition.topic).totalProduceRequestRate.mark()
      BrokerTopicStats.getBrokerAllTopicsStats().totalProduceRequestRate.mark()

      // reject appending to internal topics if it is not allowed
      if (Topic.InternalTopics.contains(topicAndPartition.topic) && !internalTopicsAllowed) {
        (topicAndPartition, LogAppendResult(
          LogAppendInfo.UnknownLogAppendInfo,
          Some(new InvalidTopicException("Cannot append to internal topic %s".format(topicAndPartition.topic)))))
      } else {
        try {
          messageValidator.validate(topicAndPartition, messages)
          val partitionOpt = getPartition(topicAndPartition.topic, topicAndPartition.partition)
          val info = partitionOpt match {
            case Some(partition) =>
              partition.appendMessagesToLeader(messages.asInstanceOf[ByteBufferMessageSet], requiredAcks)
            case None => throw new UnknownTopicOrPartitionException("Partition %s doesn't exist on %d"
              .format(topicAndPartition, localBrokerId))
          }

          val numAppendedMessages =
            if (info.firstOffset == -1L || info.lastOffset == -1L)
              0
            else
              info.lastOffset - info.firstOffset + 1

          // update stats for successfully appended bytes and messages as bytesInRate and messageInRate
          BrokerTopicStats.getBrokerTopicStats(topicAndPartition.topic).bytesInRate.mark(messages.sizeInBytes)
          BrokerTopicStats.getBrokerAllTopicsStats.bytesInRate.mark(messages.sizeInBytes)
          BrokerTopicStats.getBrokerTopicStats(topicAndPartition.topic).messagesInRate.mark(numAppendedMessages)
          BrokerTopicStats.getBrokerAllTopicsStats.messagesInRate.mark(numAppendedMessages)

          trace("%d bytes written to log %s-%d beginning at offset %d and ending at offset %d"
            .format(messages.sizeInBytes, topicAndPartition.topic, topicAndPartition.partition, info.firstOffset, info.lastOffset))
          (topicAndPartition, LogAppendResult(info))
        } catch {
          // NOTE: Failed produce requests metric is not incremented for known exceptions
          // it is supposed to indicate un-expected failures of a broker in handling a produce request
          case e: KafkaStorageException =>
            fatal("Halting due to unrecoverable I/O error while handling produce request: ", e)
            Runtime.getRuntime.halt(1)
            (topicAndPartition, null)
          case utpe: UnknownTopicOrPartitionException =>
            (topicAndPartition, LogAppendResult(LogAppendInfo.UnknownLogAppendInfo, Some(utpe)))
          case nle: NotLeaderForPartitionException =>
            (topicAndPartition, LogAppendResult(LogAppendInfo.UnknownLogAppendInfo, Some(nle)))
          case mtle: MessageSizeTooLargeException =>
            (topicAndPartition, LogAppendResult(LogAppendInfo.UnknownLogAppendInfo, Some(mtle)))
          case mstle: MessageSetSizeTooLargeException =>
            (topicAndPartition, LogAppendResult(LogAppendInfo.UnknownLogAppendInfo, Some(mstle)))
          case imse: InvalidMessageSizeException =>
            (topicAndPartition, LogAppendResult(LogAppendInfo.UnknownLogAppendInfo, Some(imse)))
          case vf: ValidationFailedException =>
            (topicAndPartition, LogAppendResult(LogAppendInfo.UnknownLogAppendInfo, Some(vf)))
          case t: Throwable =>
            BrokerTopicStats.getBrokerTopicStats(topicAndPartition.topic).failedProduceRequestRate.mark()
            BrokerTopicStats.getBrokerAllTopicsStats.failedProduceRequestRate.mark()
            error("Error processing append operation on partition %s".format(topicAndPartition), t)
            (topicAndPartition, LogAppendResult(LogAppendInfo.UnknownLogAppendInfo, Some(t)))
        }
      }
    }
  }

  /**
    * Fetch messages from the leader replica, and wait until enough data can be fetched and return;
    * the callback function will be triggered either when timeout or required fetch info is satisfied
    */
  def fetchMessages(timeout: Long,
                    replicaId: Int,
                    fetchMinBytes: Int,
                    fetchInfo: immutable.Map[TopicAndPartition, PartitionFetchInfo],
                    responseCallback: Map[TopicAndPartition, FetchResponsePartitionData] => Unit) {
    val isFromFollower = replicaId >= 0
    val fetchOnlyFromLeader: Boolean = replicaId != Request.DebuggingConsumerId
    val fetchOnlyCommitted: Boolean = !Request.isValidBrokerId(replicaId)

    // read from local logs
    val logReadResults = readFromLocalLog(fetchOnlyFromLeader, fetchOnlyCommitted, fetchInfo)

    // if the fetch comes from the follower,
    // update its corresponding log end offset
    if (Request.isValidBrokerId(replicaId))
      updateFollowerLogReadResults(replicaId, logReadResults)

    // check if this fetch request can be satisfied right away
    val bytesReadable = logReadResults.values.map(_.info.messageSet.sizeInBytes).sum
    val errorReadingData = logReadResults.values.foldLeft(false)((errorIncurred, readResult) =>
      errorIncurred || (readResult.errorCode != ErrorMapping.NoError))

    // respond immediately if 1) fetch request does not want to wait
    //                        2) fetch request does not require any data
    //                        3) has enough data to respond
    //                        4) some error happens while reading data
    if (timeout <= 0 || fetchInfo.size <= 0 || bytesReadable >= fetchMinBytes || errorReadingData) {
      val fetchPartitionData = logReadResults.mapValues(result =>
        FetchResponsePartitionData(result.errorCode, result.hw, result.info.messageSet))
      responseCallback(fetchPartitionData)
    } else {
      // construct the fetch results from the read results
      val fetchPartitionStatus = logReadResults.map { case (topicAndPartition, result) =>
        (topicAndPartition, FetchPartitionStatus(result.info.fetchOffsetMetadata, fetchInfo.get(topicAndPartition).get))
      }
      val fetchMetadata = FetchMetadata(fetchMinBytes, fetchOnlyFromLeader, fetchOnlyCommitted, isFromFollower, fetchPartitionStatus)
      val delayedFetch = new DelayedFetch(timeout, fetchMetadata, this, responseCallback)

      // create a list of (topic, partition) pairs to use as keys for this delayed fetch operation
      val delayedFetchKeys = fetchPartitionStatus.keys.map(new TopicPartitionOperationKey(_)).toSeq

      // try to complete the request immediately, otherwise put it into the purgatory;
      // this is because while the delayed fetch operation is being created, new requests
      // may arrive and hence make this operation completable.
      delayedFetchPurgatory.tryCompleteElseWatch(delayedFetch, delayedFetchKeys)
    }
  }

  /**
    * Read from a single topic/partition at the given offset upto maxSize bytes
    */
  def readFromLocalLog(fetchOnlyFromLeader: Boolean,
                       readOnlyCommitted: Boolean,
                       readPartitionInfo: Map[TopicAndPartition, PartitionFetchInfo]): Map[TopicAndPartition, LogReadResult] = {

    readPartitionInfo.map { case (TopicAndPartition(topic, partition), PartitionFetchInfo(offset, fetchSize)) =>
      BrokerTopicStats.getBrokerTopicStats(topic).totalFetchRequestRate.mark()
      BrokerTopicStats.getBrokerAllTopicsStats().totalFetchRequestRate.mark()

      val partitionDataAndOffsetInfo =
        try {
          trace("Fetching log segment for topic %s, partition %d, offset %d, size %d".format(topic, partition, offset, fetchSize))

          // decide whether to only fetch from leader
          val localReplica = if (fetchOnlyFromLeader)
            getLeaderReplicaIfLocal(topic, partition)
          else
            getReplicaOrException(topic, partition)

          // decide whether to only fetch committed data (i.e. messages below high watermark)
          val maxOffsetOpt = if (readOnlyCommitted)
            Some(localReplica.highWatermark.messageOffset)
          else
            None

          /* Read the LogOffsetMetadata prior to performing the read from the log.
           * We use the LogOffsetMetadata to determine if a particular replica is in-sync or not.
           * Using the log end offset after performing the read can lead to a race condition
           * where data gets appended to the log immediately after the replica has consumed from it
           * This can cause a replica to always be out of sync.
           */
          val initialLogEndOffset = localReplica.logEndOffset
          val logReadInfo = localReplica.log match {
            case Some(log) =>
              log.read(offset, fetchSize, maxOffsetOpt)
            case None =>
              error("Leader for partition [%s,%d] does not have a local log".format(topic, partition))
              FetchDataInfo(LogOffsetMetadata.UnknownOffsetMetadata, MessageSet.Empty)
          }

          val readToEndOfLog = initialLogEndOffset.messageOffset - logReadInfo.fetchOffsetMetadata.messageOffset <= 0

          LogReadResult(logReadInfo, localReplica.highWatermark.messageOffset, fetchSize, readToEndOfLog, None)
        } catch {
          // NOTE: Failed fetch requests metric is not incremented for known exceptions since it
          // is supposed to indicate un-expected failure of a broker in handling a fetch request
          case utpe: UnknownTopicOrPartitionException =>
            LogReadResult(FetchDataInfo(LogOffsetMetadata.UnknownOffsetMetadata, MessageSet.Empty), -1L, fetchSize, false, Some(utpe))
          case nle: NotLeaderForPartitionException =>
            LogReadResult(FetchDataInfo(LogOffsetMetadata.UnknownOffsetMetadata, MessageSet.Empty), -1L, fetchSize, false, Some(nle))
          case rnae: ReplicaNotAvailableException =>
            LogReadResult(FetchDataInfo(LogOffsetMetadata.UnknownOffsetMetadata, MessageSet.Empty), -1L, fetchSize, false, Some(rnae))
          case oor: OffsetOutOfRangeException =>
            LogReadResult(FetchDataInfo(LogOffsetMetadata.UnknownOffsetMetadata, MessageSet.Empty), -1L, fetchSize, false, Some(oor))
          case e: Throwable =>
            BrokerTopicStats.getBrokerTopicStats(topic).failedFetchRequestRate.mark()
            BrokerTopicStats.getBrokerAllTopicsStats().failedFetchRequestRate.mark()
            error("Error processing fetch operation on partition [%s,%d] offset %d".format(topic, partition, offset), e)
            LogReadResult(FetchDataInfo(LogOffsetMetadata.UnknownOffsetMetadata, MessageSet.Empty), -1L, fetchSize, false, Some(e))
        }
      (TopicAndPartition(topic, partition), partitionDataAndOffsetInfo)
    }
  }

  def maybeUpdateMetadataCache(updateMetadataRequest: UpdateMetadataRequest, metadataCache: MetadataCache) {
    replicaStateChangeLock synchronized {
      if (updateMetadataRequest.controllerEpoch < controllerEpoch) {
        val stateControllerEpochErrorMessage = ("Broker %d received update metadata request with correlation id %d from an " +
          "old controller %d with epoch %d. Latest known controller epoch is %d").format(localBrokerId,
          updateMetadataRequest.correlationId, updateMetadataRequest.controllerId, updateMetadataRequest.controllerEpoch,
          controllerEpoch)
        stateChangeLogger.warn(stateControllerEpochErrorMessage)
        throw new ControllerMovedException(stateControllerEpochErrorMessage)
      } else {
        metadataCache.updateCache(updateMetadataRequest)
        controllerEpoch = updateMetadataRequest.controllerEpoch
      }
    }
  }

  def becomeLeaderOrFollower(leaderAndISRRequest: LeaderAndIsrRequest,
                             metadataCache: MetadataCache,
                             onLeadershipChange: (Iterable[Partition], Iterable[Partition]) => Unit): BecomeLeaderOrFollowerResult = {
    leaderAndISRRequest.partitionStateInfos.foreach { case ((topic, partition), stateInfo) =>
      stateChangeLogger.trace("Broker %d received LeaderAndIsr request %s correlation id %d from controller %d epoch %d for partition [%s,%d]"
        .format(localBrokerId, stateInfo, leaderAndISRRequest.correlationId,
          leaderAndISRRequest.controllerId, leaderAndISRRequest.controllerEpoch, topic, partition))
    }
    replicaStateChangeLock synchronized {
      val responseMap = new mutable.HashMap[(String, Int), Short]
      if (leaderAndISRRequest.controllerEpoch < controllerEpoch) {
        leaderAndISRRequest.partitionStateInfos.foreach { case ((topic, partition), stateInfo) =>
          stateChangeLogger.warn(("Broker %d ignoring LeaderAndIsr request from controller %d with correlation id %d since " +
            "its controller epoch %d is old. Latest known controller epoch is %d").format(localBrokerId, leaderAndISRRequest.controllerId,
            leaderAndISRRequest.correlationId, leaderAndISRRequest.controllerEpoch, controllerEpoch))
        }
        BecomeLeaderOrFollowerResult(responseMap, ErrorMapping.StaleControllerEpochCode)
      } else {
        val controllerId = leaderAndISRRequest.controllerId
        val correlationId = leaderAndISRRequest.correlationId
        controllerEpoch = leaderAndISRRequest.controllerEpoch

        // First check partition's leader epoch
        val partitionState = new mutable.HashMap[Partition, PartitionStateInfo]()
        leaderAndISRRequest.partitionStateInfos.foreach { case ((topic, partitionId), partitionStateInfo) =>
          val partition = getOrCreatePartition(topic, partitionId)
          val partitionLeaderEpoch = partition.getLeaderEpoch()
          // If the leader epoch is valid record the epoch of the controller that made the leadership decision.
          // This is useful while updating the isr to maintain the decision maker controller's epoch in the zookeeper path
          if (partitionLeaderEpoch < partitionStateInfo.leaderIsrAndControllerEpoch.leaderAndIsr.leaderEpoch) {
            if (partitionStateInfo.allReplicas.contains(config.brokerId))
              partitionState.put(partition, partitionStateInfo)
            else {
              stateChangeLogger.warn(("Broker %d ignoring LeaderAndIsr request from controller %d with correlation id %d " +
                "epoch %d for partition [%s,%d] as itself is not in assigned replica list %s")
                .format(localBrokerId, controllerId, correlationId, leaderAndISRRequest.controllerEpoch,
                  topic, partition.partitionId, partitionStateInfo.allReplicas.mkString(",")))
              responseMap.put((topic, partitionId), ErrorMapping.UnknownTopicOrPartitionCode)
            }
          } else {
            // Otherwise record the error code in response
            stateChangeLogger.warn(("Broker %d ignoring LeaderAndIsr request from controller %d with correlation id %d " +
              "epoch %d for partition [%s,%d] since its associated leader epoch %d is old. Current leader epoch is %d")
              .format(localBrokerId, controllerId, correlationId, leaderAndISRRequest.controllerEpoch,
                topic, partition.partitionId, partitionStateInfo.leaderIsrAndControllerEpoch.leaderAndIsr.leaderEpoch, partitionLeaderEpoch))
            responseMap.put((topic, partitionId), ErrorMapping.StaleLeaderEpochCode)
          }
        }

        val partitionsTobeLeader = partitionState.filter { case (partition, partitionStateInfo) =>
          partitionStateInfo.leaderIsrAndControllerEpoch.leaderAndIsr.leader == config.brokerId
        }
        val partitionsToBeFollower = (partitionState -- partitionsTobeLeader.keys)

        val partitionsBecomeLeader = if (!partitionsTobeLeader.isEmpty)
          makeLeaders(controllerId, controllerEpoch, partitionsTobeLeader, leaderAndISRRequest.correlationId, responseMap)
        else
          Set.empty[Partition]
        val partitionsBecomeFollower = if (!partitionsToBeFollower.isEmpty)
          makeFollowers(controllerId, controllerEpoch, partitionsToBeFollower, leaderAndISRRequest.correlationId, responseMap, metadataCache)
        else
          Set.empty[Partition]

        // we initialize highwatermark thread after the first leaderisrrequest. This ensures that all the partitions
        // have been completely populated before starting the checkpointing there by avoiding weird race conditions
        if (!hwThreadInitialized) {
          startHighWaterMarksCheckPointThread()
          hwThreadInitialized = true
        }
        replicaFetcherManager.shutdownIdleFetcherThreads()

        onLeadershipChange(partitionsBecomeLeader, partitionsBecomeFollower)
        BecomeLeaderOrFollowerResult(responseMap, ErrorMapping.NoError)
      }
    }
  }

  /*
   * Make the current broker to become leader for a given set of partitions by:
   *
   * 1. Stop fetchers for these partitions
   * 2. Update the partition metadata in cache
   * 3. Add these partitions to the leader partitions set
   *
   * If an unexpected error is thrown in this function, it will be propagated to KafkaApis where
   * the error message will be set on each partition since we do not know which partition caused it. Otherwise,
   * return the set of partitions that are made leader due to this method
   *
   *  TODO: the above may need to be fixed later
   */
  private def makeLeaders(controllerId: Int,
                          epoch: Int,
                          partitionState: Map[Partition, PartitionStateInfo],
                          correlationId: Int,
                          responseMap: mutable.Map[(String, Int), Short]): Set[Partition] = {
    partitionState.foreach(state =>
      stateChangeLogger.trace(("Broker %d handling LeaderAndIsr request correlationId %d from controller %d epoch %d " +
        "starting the become-leader transition for partition %s")
        .format(localBrokerId, correlationId, controllerId, epoch, TopicAndPartition(state._1.topic, state._1.partitionId))))

    for (partition <- partitionState.keys)
      responseMap.put((partition.topic, partition.partitionId), ErrorMapping.NoError)

    val partitionsToMakeLeaders: mutable.Set[Partition] = mutable.Set()

    try {
      // First stop fetchers for all the partitions
      replicaFetcherManager.removeFetcherForPartitions(partitionState.keySet.map(new TopicAndPartition(_)))
      // Update the partition information to be the leader
      partitionState.foreach { case (partition, partitionStateInfo) =>
        if (partition.makeLeader(controllerId, partitionStateInfo, correlationId))
          partitionsToMakeLeaders += partition
        else
          stateChangeLogger.info(("Broker %d skipped the become-leader state change after marking its partition as leader with correlation id %d from " +
            "controller %d epoch %d for partition %s since it is already the leader for the partition.")
            .format(localBrokerId, correlationId, controllerId, epoch, TopicAndPartition(partition.topic, partition.partitionId)));
      }
      partitionsToMakeLeaders.foreach { partition =>
        stateChangeLogger.trace(("Broker %d stopped fetchers as part of become-leader request from controller " +
          "%d epoch %d with correlation id %d for partition %s")
          .format(localBrokerId, controllerId, epoch, correlationId, TopicAndPartition(partition.topic, partition.partitionId)))
      }
    } catch {
      case e: Throwable =>
        partitionState.foreach { state =>
          val errorMsg = ("Error on broker %d while processing LeaderAndIsr request correlationId %d received from controller %d" +
            " epoch %d for partition %s").format(localBrokerId, correlationId, controllerId, epoch,
            TopicAndPartition(state._1.topic, state._1.partitionId))
          stateChangeLogger.error(errorMsg, e)
        }
        // Re-throw the exception for it to be caught in KafkaApis
        throw e
    }

    partitionState.foreach { state =>
      stateChangeLogger.trace(("Broker %d completed LeaderAndIsr request correlationId %d from controller %d epoch %d " +
        "for the become-leader transition for partition %s")
        .format(localBrokerId, correlationId, controllerId, epoch, TopicAndPartition(state._1.topic, state._1.partitionId)))
    }

    partitionsToMakeLeaders
  }

  /*
   * Make the current broker to become follower for a given set of partitions by:
   *
   * 1. Remove these partitions from the leader partitions set.
   * 2. Mark the replicas as followers so that no more data can be added from the producer clients.
   * 3. Stop fetchers for these partitions so that no more data can be added by the replica fetcher threads.
   * 4. Truncate the log and checkpoint offsets for these partitions.
   * 5. If the broker is not shutting down, add the fetcher to the new leaders.
   *
   * The ordering of doing these steps make sure that the replicas in transition will not
   * take any more messages before checkpointing offsets so that all messages before the checkpoint
   * are guaranteed to be flushed to disks
   *
   * If an unexpected error is thrown in this function, it will be propagated to KafkaApis where
   * the error message will be set on each partition since we do not know which partition caused it. Otherwise,
   * return the set of partitions that are made follower due to this method
   */
  private def makeFollowers(controllerId: Int,
                            epoch: Int,
                            partitionState: Map[Partition, PartitionStateInfo],
                            correlationId: Int,
                            responseMap: mutable.Map[(String, Int), Short],
                            metadataCache: MetadataCache): Set[Partition] = {
    partitionState.foreach { state =>
      stateChangeLogger.trace(("Broker %d handling LeaderAndIsr request correlationId %d from controller %d epoch %d " +
        "starting the become-follower transition for partition %s")
        .format(localBrokerId, correlationId, controllerId, epoch, TopicAndPartition(state._1.topic, state._1.partitionId)))
    }

    for (partition <- partitionState.keys)
      responseMap.put((partition.topic, partition.partitionId), ErrorMapping.NoError)

    val partitionsToMakeFollower: mutable.Set[Partition] = mutable.Set()

    try {

      // TODO: Delete leaders from LeaderAndIsrRequest
      partitionState.foreach { case (partition, partitionStateInfo) =>
        val leaderIsrAndControllerEpoch = partitionStateInfo.leaderIsrAndControllerEpoch
        val newLeaderBrokerId = leaderIsrAndControllerEpoch.leaderAndIsr.leader
        metadataCache.getAliveBrokers.find(_.id == newLeaderBrokerId) match {
          // Only change partition state when the leader is available
          case Some(leaderBroker) =>
            if (partition.makeFollower(controllerId, partitionStateInfo, correlationId))
              partitionsToMakeFollower += partition
            else
              stateChangeLogger.info(("Broker %d skipped the become-follower state change after marking its partition as follower with correlation id %d from " +
                "controller %d epoch %d for partition [%s,%d] since the new leader %d is the same as the old leader")
                .format(localBrokerId, correlationId, controllerId, leaderIsrAndControllerEpoch.controllerEpoch,
                  partition.topic, partition.partitionId, newLeaderBrokerId))
          case None =>
            // The leader broker should always be present in the metadata cache.
            // If not, we should record the error message and abort the transition process for this partition
            stateChangeLogger.error(("Broker %d received LeaderAndIsrRequest with correlation id %d from controller" +
              " %d epoch %d for partition [%s,%d] but cannot become follower since the new leader %d is unavailable.")
              .format(localBrokerId, correlationId, controllerId, leaderIsrAndControllerEpoch.controllerEpoch,
                partition.topic, partition.partitionId, newLeaderBrokerId))
            // Create the local replica even if the leader is unavailable. This is required to ensure that we include
            // the partition's high watermark in the checkpoint file (see KAFKA-1647)
            partition.getOrCreateReplica()
        }
      }

      replicaFetcherManager.removeFetcherForPartitions(partitionsToMakeFollower.map(new TopicAndPartition(_)))
      partitionsToMakeFollower.foreach { partition =>
        stateChangeLogger.trace(("Broker %d stopped fetchers as part of become-follower request from controller " +
          "%d epoch %d with correlation id %d for partition %s")
          .format(localBrokerId, controllerId, epoch, correlationId, TopicAndPartition(partition.topic, partition.partitionId)))
      }

      logManager.truncateTo(partitionsToMakeFollower.map(partition => (new TopicAndPartition(partition), partition.getOrCreateReplica().highWatermark.messageOffset)).toMap)

      partitionsToMakeFollower.foreach { partition =>
        stateChangeLogger.trace(("Broker %d truncated logs and checkpointed recovery boundaries for partition [%s,%d] as part of " +
          "become-follower request with correlation id %d from controller %d epoch %d").format(localBrokerId,
          partition.topic, partition.partitionId, correlationId, controllerId, epoch))
      }

      if (isShuttingDown.get()) {
        partitionsToMakeFollower.foreach { partition =>
          stateChangeLogger.trace(("Broker %d skipped the adding-fetcher step of the become-follower state change with correlation id %d from " +
            "controller %d epoch %d for partition [%s,%d] since it is shutting down").format(localBrokerId, correlationId,
            controllerId, epoch, partition.topic, partition.partitionId))
        }
      }
      else {
        // we do not need to check if the leader exists again since this has been done at the beginning of this process
        val partitionsToMakeFollowerWithLeaderAndOffset = partitionsToMakeFollower.map(partition =>
          new TopicAndPartition(partition) -> BrokerAndInitialOffset(
            metadataCache.getAliveBrokers.find(_.id == partition.leaderReplicaIdOpt.get).get.getBrokerEndPoint(config.interBrokerSecurityProtocol),
            partition.getReplica().get.logEndOffset.messageOffset)).toMap
        replicaFetcherManager.addFetcherForPartitions(partitionsToMakeFollowerWithLeaderAndOffset)

        partitionsToMakeFollower.foreach { partition =>
          stateChangeLogger.trace(("Broker %d started fetcher to new leader as part of become-follower request from controller " +
            "%d epoch %d with correlation id %d for partition [%s,%d]")
            .format(localBrokerId, controllerId, epoch, correlationId, partition.topic, partition.partitionId))
        }
      }
    } catch {
      case e: Throwable =>
        val errorMsg = ("Error on broker %d while processing LeaderAndIsr request with correlationId %d received from controller %d " +
          "epoch %d").format(localBrokerId, correlationId, controllerId, epoch)
        stateChangeLogger.error(errorMsg, e)
        // Re-throw the exception for it to be caught in KafkaApis
        throw e
    }

    partitionState.foreach { state =>
      stateChangeLogger.trace(("Broker %d completed LeaderAndIsr request correlationId %d from controller %d epoch %d " +
        "for the become-follower transition for partition %s")
        .format(localBrokerId, correlationId, controllerId, epoch, TopicAndPartition(state._1.topic, state._1.partitionId)))
    }

    partitionsToMakeFollower
  }

  private def maybeShrinkIsr(): Unit = {
    trace("Evaluating ISR list of partitions to see which replicas can be removed from the ISR")
    allPartitions.values.foreach(partition => partition.maybeShrinkIsr(config.replicaLagTimeMaxMs))
  }

  private def updateFollowerLogReadResults(replicaId: Int, readResults: Map[TopicAndPartition, LogReadResult]) {
    debug("Recording follower broker %d log read results: %s ".format(replicaId, readResults))
    readResults.foreach { case (topicAndPartition, readResult) =>
      getPartition(topicAndPartition.topic, topicAndPartition.partition) match {
        case Some(partition) =>
          partition.updateReplicaLogReadResult(replicaId, readResult)

          // for producer requests with ack > 1, we need to check
          // if they can be unblocked after some follower's log end offsets have moved
          tryCompleteDelayedProduce(new TopicPartitionOperationKey(topicAndPartition))
        case None =>
          warn("While recording the replica LEO, the partition %s hasn't been created.".format(topicAndPartition))
      }
    }
  }

  private def getLeaderPartitions(): List[Partition] = {
    allPartitions.values.filter(_.leaderReplicaIfLocal().isDefined).toList
  }

  // Flushes the highwatermark value for all partitions to the highwatermark file
  def checkpointHighWatermarks() {
    val replicas = allPartitions.values.map(_.getReplica(config.brokerId)).collect { case Some(replica) => replica }
    val replicasByDir = replicas.filter(_.log.isDefined).groupBy(_.log.get.dir.getParentFile.getAbsolutePath)
    for ((dir, reps) <- replicasByDir) {
      val hwms = reps.map(r => (new TopicAndPartition(r) -> r.highWatermark.messageOffset)).toMap
      try {
        highWatermarkCheckpoints(dir).write(hwms)
      } catch {
        case e: IOException =>
          fatal("Error writing to highwatermark file: ", e)
          Runtime.getRuntime().halt(1)
      }
    }
  }

  // High watermark do not need to be checkpointed only when under unit tests
  def shutdown(checkpointHW: Boolean = true) {
    info("Shutting down")
    replicaFetcherManager.shutdown()
    delayedFetchPurgatory.shutdown()
    delayedProducePurgatory.shutdown()
    if (checkpointHW)
      checkpointHighWatermarks()
    info("Shut down completely")
  }
}
