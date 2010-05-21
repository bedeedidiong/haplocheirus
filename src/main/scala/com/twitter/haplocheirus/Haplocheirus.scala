package com.twitter.haplocheirus

import com.twitter.gizzard.Future
import com.twitter.gizzard.jobs.{BoundJobParser, PolymorphicJobParser}
import com.twitter.gizzard.nameserver.{BasicShardRepository, NameServer}
import com.twitter.gizzard.scheduler.PrioritizingJobScheduler
import com.twitter.gizzard.shards._
import com.twitter.ostrich.Stats
import com.twitter.querulous.StatsCollector
import net.lag.configgy.ConfigMap
import net.lag.logging.{Logger, ThrottledLogger}


object Priority extends Enumeration {
  val Migrate = Value(1)
  val Write = Value(2)
}

object Haplocheirus {
  val statsCollector = new StatsCollector {
    def incr(name: String, count: Int) = Stats.incr(name, count)
    def time[A](name: String)(f: => A): A = Stats.time(name)(f)
  }

  def apply(config: ConfigMap): Haplocheirus = {
    val log = new ThrottledLogger[String](Logger(), config("throttled_log.period_msec").toInt,
                                          config("throttled_log.rate").toInt)
    val replicationFuture = new Future("ReplicationFuture", config.configMap("replication_pool"))
    val shardRepository = new BasicShardRepository[HaplocheirusShard](
      new HaplocheirusShardAdapter(_), log, replicationFuture)
    shardRepository += ("com.twitter.haplocheirus.RedisShard" -> new RedisShardFactory(config))

    val nameServer = NameServer(config.configMap("nameservers"), Some(statsCollector),
                                shardRepository, log, replicationFuture)
    nameServer.reload()

    val jobParser = new PolymorphicJobParser
    val scheduler = PrioritizingJobScheduler(config.configMap("queue"), jobParser,
      Map(Priority.Write.id -> "write", Priority.Migrate.id -> "migrate"))

    val writeJobParser = new BoundJobParser(nameServer)
    val copyJobParser = new BoundJobParser((nameServer, scheduler(Priority.Migrate.id)))
    jobParser += ("haplocheirus".r, writeJobParser)
    jobParser += ("(Copy|Migrate|MetadataCopy|MetadataMigrate)".r, copyJobParser)

    scheduler.start()

    val future = new Future("TimelineStoreService", config.configMap("service_pool"))
    val service = new TimelineStoreService(nameServer, scheduler, Jobs.RedisCopyFactory,
                                           future, replicationFuture)
    new Haplocheirus(service)
  }
}

class Haplocheirus(val service: TimelineStoreService) {
  //
  
}

/*
FIXME - wrap jobs with this.

object NuLoggingProxy {
  var counter = 0

  def apply[T <: AnyRef](stats: StatsProvider, name: String, obj: T)(implicit manifest: Manifest[T]): T = {
    Proxy(obj) { method =>
      stats.incr("operation-" + name + ":" + method.name)
      val (rv, msec) = Stats.duration { method() }
      stats.addTiming("x-operation-" + shortName + ":" + method.name, msec.toInt)
      rv
    }
  }
}
*/