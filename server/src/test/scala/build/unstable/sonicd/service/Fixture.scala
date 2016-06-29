package build.unstable.sonicd.service

import java.io.File

import akka.actor.{Actor, Props}
import akka.stream.actor.ActorPublisher
import akka.testkit.CallingThreadDispatcher
import build.unstable.sonicd.auth.{ApiKey, ApiUser}
import build.unstable.sonicd.model.{RequestContext, SonicMessage, SonicdSource}
import build.unstable.sonicd.source.SyntheticPublisher

object Fixture {

  import build.unstable.sonicd.model.Fixture._

  val queryBytes = SonicdSource.lengthPrefixEncode(syntheticQuery.toBytes)

  // in memory db
  val H2Driver = "org.h2.Driver"

  val testUser = ApiUser("serrallonga", 10, ApiKey.Mode.ReadWrite, None)

  val testCtx = RequestContext("1", Some(testUser))

  val syntheticPubProps = Props(classOf[SyntheticPublisher], 1L, 1000, Some(1), 10, "1", false, testCtx)
    .withDispatcher(CallingThreadDispatcher.Id)

  val zombiePubProps = Props[Zombie].withDispatcher(CallingThreadDispatcher.Id)

  val tmp = new File("/tmp/sonicd_specs")
  val tmp2 = new File("/tmp/sonicd_specs/recursive")
  val tmp3 = new File("/tmp/sonicd_specs/recursive/rec2")
  val tmp32 = new File("/tmp/sonicd_specs/recursive/rec2/rec2")
  val tmp4 = new File("/tmp/sonicd_specs/recursive2")
  val file = new File("/tmp/sonicd_specs/recursive/tmp.txt")
  val file2 = new File("/tmp/sonicd_specs/recursive/rec2/tmp.txt")
  val file3 = new File("/tmp/sonicd_specs/logback.xml")
}

class Zombie extends ActorPublisher[SonicMessage] {
  override def receive: Actor.Receive = {
    case any ⇒ //ignore
  }
}
