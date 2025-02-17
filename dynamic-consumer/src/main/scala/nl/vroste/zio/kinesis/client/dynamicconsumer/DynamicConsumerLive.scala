package nl.vroste.zio.kinesis.client.dynamicconsumer

import nl.vroste.zio.kinesis.client.Util.ZStreamExtensions
import nl.vroste.zio.kinesis.client.serde.Deserializer
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient
import software.amazon.kinesis.common.{ ConfigsBuilder, InitialPositionInStreamExtended, StreamIdentifier }
import software.amazon.kinesis.coordinator.Scheduler
import software.amazon.kinesis.exceptions.ShutdownException
import software.amazon.kinesis.lifecycle.events._
import software.amazon.kinesis.processor.{
  RecordProcessorCheckpointer,
  ShardRecordProcessor,
  ShardRecordProcessorFactory,
  SingleStreamTracker
}
import software.amazon.kinesis.retrieval.KinesisClientRecord
import zio._
import zio.stream.ZStream

import scala.jdk.CollectionConverters._

private[client] class DynamicConsumerLive(
  kinesisAsyncClient: KinesisAsyncClient,
  cloudWatchAsyncClient: CloudWatchAsyncClient,
  dynamoDbAsyncClient: DynamoDbAsyncClient,
  implicit val unsafe: Unsafe
) extends DynamicConsumer {
  override def shardedStream[R, T](
    streamName: String,
    applicationName: String,
    deserializer: Deserializer[R, T],
    requestShutdown: UIO[Unit],
    initialPosition: InitialPositionInStreamExtended,
    leaseTableName: Option[String] = None,
    metricsNamespace: Option[String] = None,
    workerIdentifier: String,
    maxShardBufferSize: Int,
    configureKcl: SchedulerConfig => SchedulerConfig
  ): ZStream[
    R,
    Throwable,
    (String, ZStream[Any, Throwable, DynamicConsumer.Record[T]], DynamicConsumer.Checkpointer)
  ] = {
    sealed trait ShardQueueStopReason
    object ShardQueueStopReason {
      case object ShutdownRequested extends ShardQueueStopReason
      case object ShardEnded        extends ShardQueueStopReason
      case object LeaseLost         extends ShardQueueStopReason
    }

    /*
     * A queue for a single Shard and interface between the KCL threadpool and the ZIO runtime
     *
     * This queue is used by a ZStream for a single Shard
     *
     * The Queue uses the error channel (E type parameter) to signal failure (Some[Throwable])
     * and completion (None)
     */
    class ShardQueue(
      val shardId: String,
      runtime: zio.Runtime[Any],
      val q: Queue[Exit[Option[Throwable], KinesisClientRecord]],
      checkpointerInternal: CheckpointerInternal
    ) {
      def offerRecords(r: java.util.List[KinesisClientRecord]): Unit =
        // We must make sure never to throw an exception here, because KCL will consider the records processed
        // See https://github.com/awslabs/amazon-kinesis-client/issues/10
        runtime.unsafe.run {
          val records = r.asScala
          for {
            queueShutdown <- q.isShutdown
            _             <- if (queueShutdown)
                               ZIO.logWarning(
                                 s"offerRecords for ${shardId} got ${records.size} records after queue shutdown. " +
                                   s"The shard stream may have ended prematurely. Records are discarded. "
                               )
                             else
                               ZIO.logDebug(s"offerRecords for ${shardId} got ${records.size} records")
            _             <- checkpointerInternal.setMaxSequenceNumber(
                               ExtendedSequenceNumber(
                                 records.last.sequenceNumber(),
                                 Option(records.last.subSequenceNumber()).filter(_ != 0L)
                               )
                             )
            _             <- q.offerAll(records.map(Exit.succeed)).unit.catchSomeCause {
                               case c if c.isInterrupted =>
                                 // offerAll fails immediately with interrupted when the queue was already shutdown.
                                 // This happens when the main ZStream or one of the shard's ZStreams completes, in which
                                 // case getting more records may simply be a race condition. When only the shard's stream is
                                 // completed but the main stream keeps running, the KCL will keep offering us records to process.
                                 ZIO.unit
                             }
            _             <- ZIO.logTrace(s"offerRecords for ${shardId} COMPLETE")
          } yield ()
        }.getOrThrow()

      def shutdownQueue: UIO[Unit] =
        ZIO.logDebug(s"shutdownQueue for ${shardId}") *>
          q.shutdown

      /**
       * Shutdown processing for this shard
       *
       * Clear everything that is still in the queue, offer a completion signal for the queue, set an interrupt signal
       * and await stream completion (in-flight messages processed)
       */
      def stop(reason: ShardQueueStopReason): Unit =
        runtime.unsafe.run {
          // Clear the queue so it doesn't have to be drained fully
          def drainQueueUnlessShardEnded =
            q.takeAll.unit.unless(reason == ShardQueueStopReason.ShardEnded)

          for {
            _ <- ZIO.logDebug(s"stop() for ${shardId} because of ${reason}")
            _ <- checkpointerInternal.markEndOfShard.when(reason == ShardQueueStopReason.ShardEnded)
            _ <- (drainQueueUnlessShardEnded *>
                   q.offer(Exit.fail(None)).unit <* // Pass an exit signal in the queue to stop the stream
                   q.awaitShutdown).race(q.awaitShutdown)
            _ <- ZIO.logTrace(s"stop() for ${shardId} because of ${reason} - COMPLETE")
          } yield ()
        }.getOrThrow()
    }

    class ZioShardProcessorFactory(queues: Queues) extends ShardRecordProcessorFactory {
      override def shardRecordProcessor(): ShardRecordProcessor = new ZioShardProcessor(queues)
    }

    class ZioShardProcessor(queues: Queues) extends ShardRecordProcessor {
      var shardId: Option[String]        = None
      var shardQueue: Option[ShardQueue] = None

      override def initialize(input: InitializationInput): Unit =
        shardId = Some(input.shardId())

      override def processRecords(processRecordsInput: ProcessRecordsInput): Unit = {
        if (shardQueue.isEmpty)
          shardQueue = shardId.map(shardId => queues.newShard(shardId, processRecordsInput.checkpointer()))

        shardQueue.foreach(_.offerRecords(processRecordsInput.records()))
      }

      override def leaseLost(leaseLostInput: LeaseLostInput): Unit =
        shardQueue.foreach(_.stop(ShardQueueStopReason.LeaseLost))

      override def shardEnded(shardEndedInput: ShardEndedInput): Unit = {
        shardQueue.foreach(_.stop(ShardQueueStopReason.ShardEnded))
        shardEndedInput.checkpointer().checkpoint()
      }

      override def shutdownRequested(shutdownRequestedInput: ShutdownRequestedInput): Unit =
        shardQueue.foreach(_.stop(ShardQueueStopReason.ShutdownRequested))
    }

    class Queues(
      private val runtime: zio.Runtime[Any],
      val shards: Queue[Exit[Option[Throwable], (String, ShardQueue, CheckpointerInternal)]]
    ) {
      def newShard(shard: String, checkpointer: RecordProcessorCheckpointer): ShardQueue =
        runtime.unsafe.run {
          for {
            checkpointer <- Checkpointer.make(checkpointer)
            queue        <- Queue
                              .bounded[Exit[Option[Throwable], KinesisClientRecord]](
                                maxShardBufferSize
                              )
                              .map(new ShardQueue(shard, runtime, _, checkpointer))
            _            <- shards.offer(Exit.succeed((shard, queue, checkpointer))).unit
          } yield queue
        }.getOrThrow()

      def shutdown: UIO[Unit] =
        shards.offer(Exit.fail(None)).unit
    }

    object Queues {
      def make: ZIO[Scope with Any, Nothing, Queues] =
        for {
          runtime <- ZIO.runtime[Any]
          q       <- ZIO.acquireRelease(
                       Queue
                         .unbounded[Exit[Option[Throwable], (String, ShardQueue, CheckpointerInternal)]]
                     )(_.shutdown)
        } yield new Queues(runtime, q)
    }

    def toRecord(
      shardId: String,
      r: KinesisClientRecord
    ): ZIO[R, Throwable, DynamicConsumer.Record[T]] =
      deserializer.deserialize(Chunk.fromByteBuffer(r.data())).map { data =>
        DynamicConsumer.Record(
          shardId,
          r.sequenceNumber(),
          r.approximateArrivalTimestamp(),
          data,
          r.partitionKey(),
          r.encryptionType(),
          Option(r.subSequenceNumber()).filterNot(_ == 0L),
          Option(r.explicitHashKey()).filterNot(_.isEmpty),
          r.aggregated()
        )
      }

    // Run the scheduler
    val schedulerM =
      for {
        queues <- Queues.make

        configsBuilder = {
          val configsBuilder = new ConfigsBuilder(
            streamName,
            applicationName,
            kinesisAsyncClient,
            dynamoDbAsyncClient,
            cloudWatchAsyncClient,
            workerIdentifier,
            new ZioShardProcessorFactory(queues)
          )
          val withTableName  = leaseTableName.fold(configsBuilder)(configsBuilder.tableName)
          metricsNamespace.fold(withTableName)(withTableName.namespace)
        }
        config         = configureKcl(
                           SchedulerConfig.makeDefault(configsBuilder, kinesisAsyncClient, initialPosition, streamName)
                         )
        env           <- ZIO.environment[R]

        scheduler    <- ZIO.attempt(
                          new Scheduler(
                            config.checkpoint,
                            config.coordinator,
                            config.leaseManagement,
                            config.lifecycle,
                            config.metrics,
                            config.processor,
                            config.retrieval.streamTracker(
                              new SingleStreamTracker(
                                StreamIdentifier.singleStreamInstance(streamName),
                                config.initialPositionInStreamExtended
                              )
                            )
                          )
                        )
        doShutdown    = ZIO.logDebug("Starting graceful shutdown") *>
                          ZIO.fromFutureJava(scheduler.startGracefulShutdown()).unit.orDie <*
                          queues.shutdown
        schedulerFib <- zio.ZIO
                          .blocking(ZIO.attempt(scheduler.run()))
                          .fork
                          .flatMap(_.join)
                          .onInterrupt(doShutdown)
                          .forkScoped
        _            <- (requestShutdown *> doShutdown).forkScoped
      } yield ZStream
        .fromQueue(queues.shards)
        .flattenExitOption
        .map { case (shardId, shardQueue, checkpointer) =>
          val stream = ZStream
            .fromQueue(shardQueue.q)
            .terminateOnFiberFailure(schedulerFib)
            .ensuring(shardQueue.shutdownQueue)
            .flattenExitOption
            .mapChunksZIO(_.mapZIO(toRecord(shardId, _)))
            .provideEnvironment(env)
            .ensuring((checkpointer.checkEndOfShardCheckpointed *> checkpointer.checkpoint).catchSome {
              case _: ShutdownException => ZIO.unit
            }.orDie)

          (shardId, stream, checkpointer)
        }

    ZStream.unwrapScoped[R](schedulerM)

  }
}
