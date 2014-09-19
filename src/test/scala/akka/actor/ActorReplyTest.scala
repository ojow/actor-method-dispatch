package akka.actor

import akka.util.Timeout
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._
import ActorMethodDispatchMacros._
import scala.concurrent.ExecutionContext.Implicits.global

class ActorReplyTest extends FunSuite with ScalaFutures {
  test("Reply test") {
    val sys = ActorSystem("Test")
    implicit val t = Timeout(1.second)

    val dataProviders = (1 to 5).toList.map(_ => actorMethodsProxy[DataProviderInterface](sys.actorOf(Props[DataProviderActor])))
    val myActor = actorMethodsProxy[DataAggregatorInterface](sys.actorOf(Props(classOf[DataAggregatorActor], dataProviders)))

    assert(myActor.askCollectData.toFuture.futureValue == "Data collected: 5, 5")

    sys.shutdown()
  }
}


class DataAggregatorActor(val providers: List[DataProviderInterface]) extends Actor with DataAggregatorInterface {

  var replyAddress: Option[ReplyAddress[String]] = None

  var intData = Map[Int, Int]()

  var stringData = Map[Int, String]()

  override protected def thisActor = this

  override def receive = selfMethods[DataAggregatorInterface]

  def checkData(): Unit = {
    if (intData.size == providers.size && stringData.size == providers.size) {
      replyAddress.map(_.sendReply(s"Data collected: ${intData.size}, ${stringData.size}"))
    }
  }

}

trait DataAggregatorInterface extends ActorMethods {
  
  override type ActorState = DataAggregatorActor
  
  def askCollectData(implicit replyAddress: ReplyAddress[String]): Reply[String] = {
    for ((provider, id) <- thisActor.providers.zipWithIndex) {
      provider.askIntData.handleWith(replyHandler(tellIntDataReply(id)))
      provider.askStringData.handleWith(replyHandler(tellStringDataReply(id)))
    }
    thisActor.replyAddress = Some(replyAddress)
    WillReplyLater
  }

  def tellIntDataReply(providerId: Int)(intData: Int): Unit = {
    thisActor.intData = thisActor.intData.updated(providerId, intData)
    thisActor.checkData()
  }

  def tellStringDataReply(providerId: Int)(stringData: String): Unit = {
    thisActor.stringData = thisActor.stringData.updated(providerId, stringData)
    thisActor.checkData()
  }

}


class DataProviderActor extends Actor with DataProviderInterface {

  override protected def thisActor = this

  override def receive = selfMethods[DataProviderInterface]

}

trait DataProviderInterface extends ActorMethods {

  override type ActorState = DataProviderActor

  def askIntData = Reply(42)

  def askStringData = Reply("a")

}
