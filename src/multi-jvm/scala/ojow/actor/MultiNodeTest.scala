package ojow.actor

import akka.actor._
import ActorMethodDispatchMacros._
import akka.remote.testkit.{MultiNodeSpecCallbacks, MultiNodeConfig}
import akka.testkit.EventFilter
import akka.testkit.TestEvent
import akka.util.Timeout
import org.scalatest.{WordSpecLike, Matchers, BeforeAndAfterAll}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender


object MultiNodeSample {
  
  class ClientActor(val dataProvider: ProviderInterface) extends Actor with ClientInterface {

    override protected def actor = this

    override def receive = selfMethods[ClientInterface]

  }
  
  trait ClientInterface extends ActorMethods {

    override type ActorState = ClientActor

    def tellSetData(data: String): Unit = { actor.dataProvider.tellSetData(data) }

    def askData(implicit replyTo: ActorMethodContext[String]): Reply[String] = {
      actor.dataProvider.askData("a string from client").handleReply(methodRefI(self, tellAcceptDataFromProvider(replyTo)))
      WillReplyLater
    }

    def tellAcceptDataFromProvider(replyTo: ActorMethodContext[String])(data: String): Unit = {
      replyTo.sendReply(data)
    }
    
  }
  

  class ProviderActor extends Actor with ProviderInterface {
    
    var data: String = ""

    override protected def actor = this

    override def receive = selfMethods[ProviderInterface]

  }

  trait ProviderInterface extends ActorMethods {

    override type ActorState = ProviderActor
    
    def tellSetData(data: String): Unit = { actor.data = data }

    def askData(param: String) = Reply(s"data with param = $param: ${actor.data}")
    
  }

}


class MultiNodeSample extends MultiNodeSpec(MultiNodeSampleConfig) with STMultiNodeSpec with ImplicitSender {

  import MultiNodeSampleConfig._
  import MultiNodeSample._

  implicit val t = Timeout(1.second)

  def initialParticipants = roles.size

  "A MultiNodeSample" must {

    "wait for all nodes to enter a barrier" in {
      enterBarrier("startup")
    }

    "send to and receive from a remote node" in {
      runOn(node1) {
        enterBarrier("deployed")
        val providerActor = Await.result(system.actorSelection(node(node2) / "user" / "provider").resolveOne(), 1.second)
        val provider = actorMethodsProxy[ProviderInterface](providerActor)
        val client = actorMethodsProxy[ClientInterface](system.actorOf(Props(classOf[ClientActor], provider), "client"))
        client.tellSetData("some data")
        client.askData.handleReply(ReplyToSender)
        expectMsg("data with param = a string from client: some data")
      }

      runOn(node2) {
        system.actorOf(Props[ProviderActor], "provider")
        enterBarrier("deployed")
      }

      enterBarrier("finished")

      system.eventStream.publish(TestEvent.Mute(EventFilter.error(start = "AssociationError")))
      system.eventStream.publish(TestEvent.Mute(EventFilter.warning(pattern = "dead letter")))
    }

  }
}


object MultiNodeSampleConfig extends MultiNodeConfig {

  val node1 = role("node1")

  val node2 = role("node2")

}

class MultiNodeTestMultiJvmNode1 extends MultiNodeSample

class MultiNodeTestMultiJvmNode2 extends MultiNodeSample

trait STMultiNodeSpec extends MultiNodeSpecCallbacks with WordSpecLike with Matchers with BeforeAndAfterAll {

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

}

