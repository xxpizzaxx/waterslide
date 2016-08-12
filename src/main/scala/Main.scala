import scopt.OptionParser

object Main {

  case class Config(host: String = "localhost", port: Int = 8090, url: String = "http://localhost/", ttl: Int = 30)

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
    arg[String]("url").action { (x, c) =>
      c.copy(url = x)
    }.text("required, URL to poll and serve")

  }

  def main(args: Array[String]): Unit = {
    parser.parse(args, Config()) match {
      case Some(config) =>
        val w = new WaterslideServer(config.host, config.port, config.url, config.ttl)
        println(s"Server starting on ${config.host}:${config.port}, serving ${config.url} with updates every ${config.ttl} seconds")
        w.server.run.awaitShutdown()
      case None =>
        ()
    }
  }

}
