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
import scala.concurrent.Future

class WaterslideServer(hostname: String, port: Int, url: String, ttl: Int, metrics: Option[MetricRegistry]) {
  private[this] val log = getLogger

  // metrics
  val urlFetch = metrics.map(_.meter("urlfetch"))
  val tick     = metrics.map(_.meter("tick"))
  val ping     = metrics.map(_.meter("ping"))
  val initial  = metrics.map(_.meter("initial"))
  val diff     = metrics.map(_.meter("diff"))

  // misc http things
  val client = PooledHttp1Client()
  val OM = new ObjectMapper()

  // cache and cache usage
  val cache: Cache[\/[JsonNode, Response]] = LruCache[\/[JsonNode, Response]](timeToLive = ttl.seconds)
  def getLatestCrest(u: String): \/[JsonNode, Response] = {
      val r = cache(u) {
        urlFetch.foreach(_.mark())
        val resp = client.get[Response](u) { x => Task(x) }.run
        val r: \/[JsonNode, Response] = resp.status match {
          case Status.Ok =>
            val json = Option(resp).map { x =>
              EntityDecoder.decodeString(x)(Charset.`UTF-8`).run
            }.map {
              OM.readTree
            }
            json.map(-\/.apply).getOrElse(\/-(Response().withBody("Invalid JSON").run))
          case _ => \/-(resp)
        }
        r
      }
      Await.result(r, ttl.seconds)
  }

  def streamIt = {
     awakeEvery(1 second)(Strategy.DefaultStrategy, DefaultScheduler).map { _ =>
        tick.foreach(_.mark())
       val t = Try {
         getLatestCrest(url) // this function is memoized
       }
       t.get
      }.filter{_.isLeft}.map{x => x.toEither.left.get}.zipWithPrevious.filter {
        case (x, y) => !x.contains(y) // deduplicate
      }.map {
        // transform to JSON
        case (None, current) =>
          initial.foreach(_.mark())
          s"""{"initial":${current.toString}}""" // first run!
        case (Some(prev), current) => // we've had a change in the JSON
          diff.foreach(_.mark())
          val diffs = JsonDiff.asJson(prev, current).toString
          s"""{"diff":${diffs}}"""
      }
  }

  val route = HttpService {
    case GET -> Root =>
      val pings = awakeEvery(10 seconds)(Strategy.DefaultStrategy, DefaultScheduler).map { _ =>
        ping.foreach(_.mark())
        Ping()
      }
      val src = streamIt.map { x => Text(x) } // shove it in a websocket frame
    val sink: Sink[Task, WebSocketFrame] = Process.constant {
      case Ping(x) => Task.delay(Pong(x))
      case f => Task.delay(println(s"Unknown type: $f"))
    }
      val joinedOutput = wye(pings, src)(wye.mergeHaltR)
      WS(Exchange(joinedOutput, sink))

  }

  val server = BlazeBuilder.bindHttp(host = "localhost", port = port)
    .withWebSockets(true)
    .mountService(route, "/")
    .start

}