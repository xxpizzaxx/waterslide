import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import com.flipkart.zjsonpatch.JsonDiff
import org.http4s._
import org.http4s.client.blaze._
import org.http4s.dsl._
import org.http4s.{HttpService, _}
import org.http4s.dsl.{Root, _}
import org.http4s.server._
import org.http4s.server.MetricsSupport
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.server.websocket._
import org.http4s.websocket.WebsocketBits._
import org.json4s.JsonDSL._
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.log4s._
import spray.caching.{Cache, LruCache}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.Try
import scalaz.concurrent.{Strategy, Task}
import scalaz.stream.time.awakeEvery
import scalaz._
import Scalaz._
import scalaz.stream.{DefaultScheduler, Exchange, Process, Sink, wye}
import org.http4s.EntityDecoder._

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scalaz.stream.async.mutable.Topic
import scalaz.stream.time

class WaterslideServer(hostname: String, port: Int, url: String, ttl: Int, metrics: Option[MetricRegistry]) {
  private[this] val log = getLogger

  // metrics
  val urlFetch = metrics.map(_.meter("urlfetch"))
  val tick     = metrics.map(_.meter("tick"))
  val ping     = metrics.map(_.meter("ping"))
  val initial  = metrics.map(_.meter("initial"))
  val unavail  = metrics.map(_.meter("unavailable"))
  val diff     = metrics.map(_.meter("diff"))

  // misc http things
  val client = PooledHttp1Client()
  val OM     = new ObjectMapper()

  // last valid response cache
  val lastValid = TrieMap[String, JsonNode]()


  val topic: Topic[JsonNode] = scalaz.stream.async.topic[JsonNode](
    time.awakeEvery(ttl.seconds)(Strategy.DefaultStrategy, DefaultScheduler).map { _ =>
      urlFetch.foreach(_.mark())
      client.get[(Status, String)](url) { x =>
        EntityDecoder.decodeString(x)(Charset.`UTF-8`).map { b =>
          (x.status, b)
        }
      }.run
    }.filter { case (status, _) =>
      // we only want Ok results
      status == Status.Ok
    }.flatMap { case (_, body) =>
      // we only want valid JSON
      Try{OM.readTree(body)}.toOption.map(Process.emit).getOrElse(Process.empty)
    }
  )

  @volatile var lastValidResponse: JsonNode = null

  topic.subscribe.map(x => lastValidResponse = x).run.runAsync(f => f.leftMap(t => log.error(t)("failed to update the lastValidResponse cache")).rightMap(_ => log.error("lastValid Response cache updater exited with Unit")))

  def streamIt = {
    val cached = Process.emitAll(Option(lastValidResponse).toList)
    wye(cached, topic.subscribe)(wye.mergeHaltR).zipWithPrevious.filter {
      case (x, y)          => !x.contains(y) // deduplicate
    }.flatMap { r =>
      val mainResponse = r match {
        // transform to JSON
        case (None, current) =>
          initial.foreach(_.mark())
          Some(s"""{"initial":${current.toString}}""") // first run!
        case (Some(prev), current) => // we've had a change in the JSON
          diff.foreach(_.mark())
          val diffs = JsonDiff.asJson(prev, current).toString
          Some(s"""{"diff":${diffs}}""")
        case _ => None
      }
      Process.emitAll(mainResponse.toList)
    }
  }

  val route = HttpService {
    case _ =>
      val pings = awakeEvery(10 seconds)(Strategy.DefaultStrategy, DefaultScheduler).map { _ =>
        ping.foreach(_.mark())
        Ping()
      }
      val src = streamIt.map { x =>
        Text(x)
      }
      val sink: Sink[Task, WebSocketFrame] = Process.constant {
        case Ping(x) => Task.delay(Pong(x))
        case f       => Task.delay(println(s"Unknown type: $f"))
      }
      val joinedOutput = wye(pings, src)(wye.mergeHaltR)
      WS(Exchange(joinedOutput, sink))

  }

  val server = BlazeBuilder.bindHttp(host = "localhost", port = port).withWebSockets(true).mountService(route, "/").start

}
