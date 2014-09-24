package ojow.actor

import akka.util.Timeout
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._
import akka.actor._
import ActorMethodDispatchMacros._
import scala.concurrent.ExecutionContext.Implicits.global


class BecomeStyleTest extends FunSuite with ScalaFutures {
  import BecomeStyleTest._

  test("Counter implementation without vars") {
    val sys = ActorSystem("Test")

    // proxy's ask timeout
    implicit val t = Timeout(1.second)

    // Create a proxy which is then used to send messages via method calls
    val myActor = actorMethodsProxy[CounterActorInterface](sys.actorOf(Props[CounterActor]))

    // The reply message will not be sent at all
    myActor.askIncrementAndGet().ignoreReply()

    // Calling an 'ask' method, returns a Reply which can be converted to a Future
    assert(myActor.askIncrementAndGet().toFuture.futureValue == 2)

    // Calling a 'tell' method, return type Unit
    myActor.tellReset()

    assert(myActor.askIncrementAndGet().toFuture.futureValue == 1)

    sys.shutdown()
  }
}


object BecomeStyleTest {

  class CounterActor extends Actor {

    override def receive = behavior(0)

    def behavior(i: Int): Receive = swappableMethods(new LinkedTo(this) with CounterActorInterface {
      def askIncrementAndGet(): Reply[Int] = {
        context.become(behavior(i + 1))
        Reply(i + 1)
      }
    })

  }


  trait CounterActorInterface extends ActorMethodsOf[CounterActor] {

    def askIncrementAndGet(): Reply[Int]

    def tellReset(): Unit = actor.context.become(actor.behavior(0))

  }

}

