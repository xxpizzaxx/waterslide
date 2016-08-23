import java.util.concurrent.TimeUnit

import com.codahale.metrics.graphite.{Graphite, GraphiteReporter}
import com.codahale.metrics.{ConsoleReporter, MetricRegistry}
import scopt.OptionParser

object Main {

  case class Config(host: String = "localhost",
                    port: Int = 8090,
                    url: String = "http://localhost/",
                    ttl: Int = 30,
                    consoleMetrics: Boolean = false,
                    graphite: Option[String] = None,
                    graphitePrefix: String = "moe.pizza.waterslide")

  val parser = new OptionParser[Config]("waterslide") {
    head("waterslide, the polling/websocket slide")

    opt[String]("host").action { (x, c) =>
      c.copy(host = x)
    }.optional().text("defaults to localhost")
    opt[Int]("port").action { (x, c) =>
      c.copy(port = x)
    }.optional().text("defaults to 8090")
    opt[Int]("ttl").action { (x, c) =>
      c.copy(ttl = x)
    }.optional().text("defaults to 30 seconds")
    opt[Boolean]("console_metrics").action { (x, c) =>
      c.copy(consoleMetrics = x)
    }.optional().text("dump metrics to the console, defaults off")
    opt[String]("graphite").action { (x, c) =>
      c.copy(graphite = Some(x))
    }.optional().text("address to the graphite server, sends metrics if enabled")
    opt[String]("graphite_prefix").action { (x, c) =>
      c.copy(graphitePrefix = x)
    }.optional().text("prefix for graphite metrics, defaults to moe.pizza.waterslide")
    arg[String]("url").action { (x, c) =>
      c.copy(url = x)
    }.text("required, URL to poll and serve")

  }

  def main(args: Array[String]): Unit = {
    parser.parse(args, Config()) match {
      case Some(config) =>
        val metrics = new MetricRegistry
        if (config.consoleMetrics) {
          ConsoleReporter
            .forRegistry(metrics)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .build()
            .start(30, TimeUnit.SECONDS)
        }
        config.graphite.foreach { g =>
          GraphiteReporter
            .forRegistry(metrics)
            .convertRatesTo(TimeUnit.SECONDS)
            .convertDurationsTo(TimeUnit.MILLISECONDS)
            .prefixedWith(config.graphitePrefix)
            .build(new Graphite(g, 2003))
            .start(1, TimeUnit.SECONDS)

        }
        val w = new WaterslideServer(config.host, config.port, config.url, config.ttl, Some(metrics))
        println(s"Server starting on ${config.host}:${config.port}, serving ${config.url} with updates every ${config.ttl} seconds")
        w.server.run.awaitShutdown()
      case None =>
        ()
    }
  }

}
