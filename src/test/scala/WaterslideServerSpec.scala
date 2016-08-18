import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

import org.http4s.HttpService
import org.http4s.dsl.{Root, _}
import org.http4s.server.blaze.BlazeBuilder
import org.json4s.JsonDSL._
import org.json4s.jackson.JsonMethods._
import org.scalatest.{FlatSpec, MustMatchers}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scalaz.stream._
import scalaz.concurrent.Task

class WaterslideServerSpec extends FlatSpec with MustMatchers {

  def mockServer = {
    var a = 0
    val resource = HttpService {
      case req @ GET -> Root =>
        a = a + 1
        Ok(compact(render("value" -> a)))
    }
    val server = BlazeBuilder.bindHttp(9001).mountService(resource, "/").start
    server
  }

  "WaterslideServer" should "accept JSON every X seconds and stream it" in {
    val s  = mockServer.run
    val ws = new WaterslideServer("localhost", 9000, "http://localhost:9001/", 1, None)
    ws.streamIt.take(2).runLog.run must equal(
        List(
            """{"initial":{"value":1}}""",
            """{"diff":[{"op":"replace","path":"/value","value":2}]}"""
        )
    )
  }

  "WaterslideServer" should "multiplex to multiple streams" in {
    val s  = mockServer.run
    val ws = new WaterslideServer("localhost", 9000, "http://localhost:9001/", 2, None)
    val stream1 = ws.streamIt
    val stream2 = ws.streamIt
    val merged = wye(stream1, stream2)(wye.mergeHaltBoth)
    val expectedResults = List(
      """{"initial":{"value":1}}""",
      """{"diff":[{"op":"replace","path":"/value","value":2}]}""",
      """{"diff":[{"op":"replace","path":"/value","value":3}]}"""
    )
    import scala.concurrent.ExecutionContext.Implicits.global
    val r1 = Future{ stream1.take(3).runLog.run }
    val r2 = Future{ stream2.take(3).runLog.run }

    val (results1 :: results2 :: _) = Await.result(Future.sequence(List(r1, r2)), 10 seconds)
    results1 must equal(results2)
  }

  "WaterslideServer" should "massively multiplex to multiple streams" in {
    val s  = mockServer.run
    val ws = new WaterslideServer("localhost", 9000, "http://localhost:9001/", 1, None)
    val streams = Range(0, 10).map{_ => ws.streamIt}
    val expectedResults = List(
      """{"initial":{"value":1}}""",
      """{"diff":[{"op":"replace","path":"/value","value":2}]}""",
      """{"diff":[{"op":"replace","path":"/value","value":3}]}"""
    )
    import scala.concurrent.ExecutionContext
    implicit val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(10))
    val results = TrieMap[String, AtomicInteger]()
    val storeResult = sink.lift((i: String) => Task.delay { println(Thread.currentThread().getName); results.getOrElseUpdate(i, new AtomicInteger(0)).getAndIncrement(); () })

    val streams2 = streams.map(_.to(storeResult).take(2).runLog.runFor(10 seconds))
    Thread.sleep(10000)
    println(results)
  }
}
