package build.unstable.sonicd.service.source

import akka.actor.{ActorContext, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.{ContentTypes, HttpRequest}
import akka.stream.actor.ActorPublisherMessage.Cancel
import akka.stream.actor.{ActorPublisher, ActorPublisherMessage}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings}
import akka.testkit.{CallingThreadDispatcher, ImplicitSender, TestActorRef, TestKit}
import build.unstable.sonicd.model._
import build.unstable.sonicd.service.{Fixture, ImplicitSubscriber}
import build.unstable.sonicd.source.Presto.{ColMeta, QueryResults, StatementStats}
import build.unstable.sonicd.source.PrestoPublisher
import build.unstable.sonicd.source.http.HttpSupervisor.HttpRequestCommand
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import spray.json._
import JsonProtocol._

import scala.concurrent.Await
import scala.concurrent.duration._

class PrestoSourceSpec(_system: ActorSystem)
  extends TestKit(_system) with WordSpecLike
  with Matchers with BeforeAndAfterAll with ImplicitSender
  with ImplicitSubscriber with HandlerUtils {

  import Fixture._

  override protected def afterAll(): Unit = {
    materializer.shutdown()
    TestKit.shutdownActorSystem(system)
  }

  def this() = this(ActorSystem("PrestoSourceSpec"))

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system))

  val mockConfig =
    s"""
       | {
       |  "port" : 9200,
       |  "url" : "unstable.build",
       |  "class" : "PrestoSource"
       | }
    """.stripMargin.parseJson.asJsObject

  val controller: TestActorRef[TestController] =
    TestActorRef(Props(classOf[TestController], self).
      withDispatcher(CallingThreadDispatcher.Id))

  def newPublisher(q: String, context: RequestContext = testCtx,
                   maxRetries: Int = 0, retryIn: FiniteDuration = 1.second): TestActorRef[PrestoPublisher] = {
    val query = new Query(Some(1L), Some("traceId"), None, q, mockConfig)
    val src = new PrestoSource(maxRetries, retryIn, self, query, controller.underlyingActor.context, context)
    val ref = TestActorRef[PrestoPublisher](src.handlerProps.withDispatcher(CallingThreadDispatcher.Id))
    ActorPublisher(ref).subscribe(subs)
    watch(ref)
    ref
  }

  val query1 = """show catalogs"""
  val defaultCol = ColMeta("name", "varchar")
  val defaultColumns = Vector(defaultCol, defaultCol.copy(name = "name2"))
  val defaultRow: Vector[JsValue] = Vector(JsNumber(1), JsString("String"))
  val defaultData = Vector(defaultRow, defaultRow)
  val defaultStats = StatementStats.apply("FINISHED", true, 0, 0, 0, 0, 0, 0, 0, 0, 0)

  def assertRequest(req: HttpRequest, query: String) = {
    req.method.value shouldBe "POST"
    req._4.contentType shouldBe ContentTypes.`text/plain(UTF-8)`
    val entity = req.entity
    val payload = Await.result(entity.toStrict(10.seconds), 10.seconds).data.utf8String
    assert(req._2.toString().endsWith("/statement"))
    assert(payload == query)
  }

  def sendNext(pub: ActorRef, status: String,
               nextUri: Option[String],
               partialCancelUri: Option[String] = None) = {
    pub ! QueryResults("", "", partialCancelUri, nextUri, Some(defaultColumns),
      Some(defaultData), defaultStats, None, None, None)
    defaultData.foreach { h ⇒
      pub ! ActorPublisherMessage.Request(1)
      expectMsg(OutputChunk(defaultRow))
    }
  }

  def completeSimpleStream(pub: ActorRef,
                           data: Vector[Vector[JsValue]] = defaultData,
                           columns: Option[Vector[ColMeta]] = Some(defaultColumns),
                           partialCancelUri: Option[String] = None,
                           nextUri: Option[String] = None) = {
    pub ! QueryResults("", "", partialCancelUri, nextUri,
      columns, Some(data), defaultStats, None, None, None)
    expectMsg(QueryProgress(QueryProgress.Started, 0, None, None))
    pub ! ActorPublisherMessage.Request(1)
    expectTypeMetadata()
    data.foreach { h ⇒
      pub ! ActorPublisherMessage.Request(1)
      expectMsg(OutputChunk(defaultRow))
    }
  }

  "PrestoSource" should {
    "run a simple query" in {
      val pub = newPublisher(query1)
      pub ! ActorPublisherMessage.Request(1)
      val httpCmd = expectMsgType[HttpRequestCommand]

      assertRequest(httpCmd.request, query1)

      completeSimpleStream(pub)

      pub ! ActorPublisherMessage.Request(1)
      expectDone(pub)
    }


    "should extract type metadata from StatementStats" in {
      val pub = newPublisher(query1)
      pub ! ActorPublisherMessage.Request(1)
      val httpCmd = expectMsgType[HttpRequestCommand]

      assertRequest(httpCmd.request, query1)

      val col1 = ColMeta("a", "boolean")
      val col2 = ColMeta("b", "bigint")
      val col3 = ColMeta("c", "double")
      val col4 = ColMeta("d", "varchar")
      val col5 = ColMeta("e", "varbinary")
      val col6 = ColMeta("f", "array")
      val col7 = ColMeta("g", "json")
      val col8 = ColMeta("h", "map")
      val col9 = ColMeta("i", "time")
      val col10 = ColMeta("j", "timestamp")
      val col11 = ColMeta("k", "date")

      val columns = Vector(col1, col2, col3, col4, col5, col6, col7, col8, col9, col10, col11)

      pub ! QueryResults("", "", None, None,
        Some(columns), None, defaultStats, None, None, None)

      expectMsg(QueryProgress(QueryProgress.Started, 0, None, None))
      pub ! ActorPublisherMessage.Request(1)
      val meta = expectTypeMetadata()

      val (cols, types) = meta.typesHint.unzip

      assert(cols == columns.map(_.name))

      columns.zipWithIndex.foreach {
        case (ColMeta(n, "boolean"), i) ⇒ types(i).getClass.getSuperclass shouldBe classOf[JsBoolean]
        case (ColMeta(n, "bigint" | "double"), i) ⇒ types(i).getClass shouldBe classOf[JsNumber]
        case (ColMeta(n, "varchar" | "timestamp" | "date" | "time"), i) ⇒ types(i).getClass shouldBe classOf[JsString]
        case (ColMeta(n, "array" | "varbinary"), i) ⇒ types(i).getClass shouldBe classOf[JsArray]
        case (ColMeta(n, "json" | "map"), i) ⇒ types(i).getClass shouldBe classOf[JsObject]
        case (ColMeta(n, "panacea"), i) ⇒ types(i).getClass shouldBe classOf[JsString]
      }

      pub ! ActorPublisherMessage.Request(1)
      expectDone(pub)
    }

    "if query results state is not finished, it should use nextUri to get the next queryResults" in {
      val pub = newPublisher(query1)
      pub ! ActorPublisherMessage.Request(1)
      expectMsgType[HttpRequestCommand]

      val stats1 = StatementStats.apply("STARTING", true, 0, 0, 0, 0, 0, 0, 0, 0, 0)

      expectQueryProgress(0, QueryProgress.Started, None, None)

      pub ! QueryResults("", "", None, Some("http://1"),
        Some(defaultColumns), None, stats1, None, None, None)

      {
        val cmd = expectMsgType[HttpRequestCommand]
        assert(cmd.request._2.toString().endsWith("1"))
        pub ! ActorPublisherMessage.Request(1)
        expectTypeMetadata()

        pub ! ActorPublisherMessage.Request(1)
        expectQueryProgress(0, QueryProgress.Running, Some(0), Some("splits"))

        pub ! QueryResults("", "", None, Some("http://2"),
          Some(defaultColumns), None, stats1.copy(completedSplits = 100, totalSplits = 1000),
          None, None, None)
      }
      {
        val cmd = expectMsgType[HttpRequestCommand]
        assert(cmd.request._2.toString().endsWith("2"))
        pub ! ActorPublisherMessage.Request(1)
        expectQueryProgress(100, QueryProgress.Running, Some(1000), Some("splits"))

        pub ! QueryResults("", "", None, Some("http://3"),
          Some(defaultColumns), None, stats1.copy(completedSplits = 200, totalSplits = 1000), None, None, None)
      }
      {
        val cmd = expectMsgType[HttpRequestCommand]
        assert(cmd.request._2.toString().endsWith("3"))

        pub ! ActorPublisherMessage.Request(1)
        expectQueryProgress(100, QueryProgress.Running, Some(1000), Some("splits"))

        pub ! QueryResults("", "", None, Some("http://4"),
          Some(defaultColumns), None, stats1.copy(completedSplits = 900, totalSplits = 1000), None, None, None)
      }
      {
        val cmd = expectMsgType[HttpRequestCommand]
        assert(cmd.request._2.toString().endsWith("4"))

        pub ! ActorPublisherMessage.Request(1)
        expectQueryProgress(700, QueryProgress.Running, Some(1000), Some("splits"))

        pub ! QueryResults("", "", None, Some("http://5"),
          Some(defaultColumns), None, stats1.copy(state = "RUNNING",
            completedSplits = 950, totalSplits = 1000), None, None, None)
      }
      {
        val cmd = expectMsgType[HttpRequestCommand]
        assert(cmd.request._2.toString().endsWith("5"))

        pub ! ActorPublisherMessage.Request(1)
        expectQueryProgress(50, QueryProgress.Running, Some(1000), Some("splits"))

        pub ! QueryResults("", "", None, Some("http://6"),
          Some(defaultColumns), None, stats1.copy(state = "RUNNING", completedSplits = 1000, totalSplits = 100), None, None, None)
      }
      {
        /* 100 - 50 */
        val cmd = expectMsgType[HttpRequestCommand]
        assert(cmd.request._2.toString().endsWith("6"))

        pub ! ActorPublisherMessage.Request(1)
        expectQueryProgress(50, QueryProgress.Running, Some(100), Some("splits"))

        pub ! QueryResults("", "", None, None,
          Some(defaultColumns), None, stats1.copy(state = "FINISHED", completedSplits = 1000, totalSplits = 100), None, None, None)
      }

      pub ! ActorPublisherMessage.Request(1)
      expectDone(pub)
    }

    "should stop if user cancels" in {
      val pub = newPublisher(query1)
      pub ! ActorPublisherMessage.Request(1)
      val httpCmd = expectMsgType[HttpRequestCommand]

      pub ! ActorPublisherMessage.Request(1)
      expectMsg(QueryProgress(QueryProgress.Started, 0, None, None))

      pub ! QueryResults("", "", Some("http://cancel"), None, Some(defaultColumns),
        None, defaultStats.copy(state = "RUNNING"), None, None, None)

      pub ! ActorPublisherMessage.Request(1)
      expectTypeMetadata()

      pub ! ActorPublisherMessage.Request(1)
      expectQueryProgress(0, QueryProgress.Running, Some(0), Some("splits"))

      pub ! Cancel
      expectTerminated(pub)
    }

    "should attempt to query ahead" in {
      val pub = newPublisher(query1, watermark = 10)
      pub ! ActorPublisherMessage.Request(1)
      expectMsgType[HttpRequestCommand]

      val stats1 = StatementStats.apply("STARTING", true, 0, 0, 0, 0, 0, 0, 0, 0, 0)

      expectQueryProgress(0, QueryProgress.Started, None, None)

      pub ! QueryResults("", "", None, Some("http://1"),
        Some(defaultColumns), None, stats1, None, None, None)


      expectMsgType[HttpRequestCommand]
      pub ! ActorPublisherMessage.Request(1)
      expectTypeMetadata()

      pub ! ActorPublisherMessage.Request(1)
      expectQueryProgress(0, QueryProgress.Running, Some(0), Some("splits"))

      pub ! QueryResults("", "", None, Some("http://2"),
        Some(defaultColumns), None, stats1.copy(completedSplits = 100, totalSplits = 1000),
        None, None, None)


      expectMsgType[HttpRequestCommand]
      pub ! ActorPublisherMessage.Request(1)
      expectQueryProgress(100, QueryProgress.Running, Some(1000), Some("splits"))

      pub ! QueryResults("", "", None, Some("http://3"),
        Some(defaultColumns), Some(defaultData), stats1.copy(completedSplits = 200, totalSplits = 1000), None, None, None)


      expectMsgType[HttpRequestCommand]
      pub ! ActorPublisherMessage.Request(1)
      expectQueryProgress(100, QueryProgress.Running, Some(1000), Some("splits"))

      pub ! QueryResults("", "", None, Some("http://3"),
        Some(defaultColumns), Some(defaultData), stats1.copy(completedSplits = 200, totalSplits = 1000), None, None, None)

      //buffer is 2 streamed is 0


    }

    "should retry up to a maximum of n retries if error code is PAGE_TRANSPORT_TIMEOUT" in {
      assert(false)
    }
  }
}

//override supervisor
class PrestoSource(maxRetries: Int, retryIn: FiniteDuration, implicitSender: ActorRef,
                   query: Query, actorContext: ActorContext, context: RequestContext)
  extends build.unstable.sonicd.source.PrestoSource(query, actorContext, context) {

  override def getSupervisor(name: String): ActorRef = implicitSender

  override lazy val handlerProps: Props = {
    //if no ES supervisor has been initialized yet for this ES cluster, initialize one
    val supervisor = getSupervisor(supervisorName)

    Props(classOf[PrestoPublisher], query.traceId.get, query.query, implicitSender, maxRetries, retryIn, context)
  }
}
