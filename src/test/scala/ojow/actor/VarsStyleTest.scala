package ojow.actor

import akka.util.Timeout
import org.scalatest.FunSuite
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent.duration._
import akka.actor._
import ActorMethodDispatchMacros._
import scala.concurrent.ExecutionContext.Implicits.global


class VarsStyleTest extends FunSuite with ScalaFutures {
  import VarsStyleTest._

  test("Counter implementation with vars") {
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


object VarsStyleTest {

  class CounterActor extends Actor {

    var i: Int = 0

    override def receive = swappableMethods(new LinkedTo(this) with CounterActorInterface)

  }


  trait CounterActorInterface extends ActorMethodsOf[CounterActor] {

    def askIncrementAndGet(): Reply[Int] = {
      actor.i += 1
      Reply(actor.i)
    }

    def tellReset(): Unit = {
      actor.i = 0
    }

  }

}

